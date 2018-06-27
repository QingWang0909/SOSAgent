package edu.clemson.openflow.sos.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.clemson.openflow.sos.buf.Buffer;
import edu.clemson.openflow.sos.buf.OrderedPacketListener;
import edu.clemson.openflow.sos.host.HostStatusListener;
import edu.clemson.openflow.sos.rest.RequestTemplateWrapper;
import edu.clemson.openflow.sos.rest.RestRoutes;
import edu.clemson.openflow.sos.shaping.AgentTrafficShaping;
import edu.clemson.openflow.sos.shaping.ISocketStatListener;
import edu.clemson.openflow.sos.stats.StatCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.regex.Pattern;

/**
 * @author Khayam Gondal kanjam@g.clemson.edu
 * This class will be doing following
 * 1: Open parallel connections with AgentServer
 * 2: Notify AgentServer about the ports belongin to certain client
 * 3: Append seq. no
 * 4: Write packet to open channels in circular order
 */

public class AgentClient implements OrderedPacketListener, HostStatusListener, ISocketStatListener {
    private static final Logger log = LoggerFactory.getLogger(AgentClient.class);

    private static final String PORTMAP_PATH = "/portmap";
    private static final String REST_PORT = "8002";
    private static final int AGENT_DATA_PORT = 9878;

    private long startTime;
    private float totalBytes;
    private HashMap<Integer, Float> perChBytes;
    int wCount = 0;

    private int currentChannelNo = 0;

    private RequestTemplateWrapper request;
    private ArrayList<Channel> channels;
    private Buffer myBuffer;
    private Channel hostChannel;
    private EventLoopGroup eventLoopGroup;
    private AgentTrafficShaping ats;

    public AgentClient(RequestTemplateWrapper request) {
        this.request = request;

        perChBytes = new HashMap<>(request.getRequest().getNumParallelSockets());
        channels = new ArrayList<>(request.getRequest().getNumParallelSockets());
        myBuffer = new Buffer(request, this);
        //    this.statListener = statListener;
        //   myBuffer.setListener(this); // notify me when you have sorted packs


    }

    private String maskIP(String IP) {
        String[] parts = IP.split(Pattern.quote("."));
        log.info("Before mask {} and split length {}", IP, parts.length);
        String maskedIP = "172";
        for (int i=1; i < parts.length; i++) {
            maskedIP += ".";
            maskedIP += parts[i];
        }
        log.info("Masked IP is {}", maskedIP);
        return maskedIP;
    }

    public void bootStrapSockets() {
        eventLoopGroup = createEventLoopGroup();
        log.info("Bootstrapping {} connections to agent server", request.getRequest().getNumParallelSockets());
        try {
            for (int i = 0; i < request.getRequest().getNumParallelSockets(); i++) {

                channels.add(connectToChannel(bootStrap(eventLoopGroup, maskIP(request.getRequest().getServerAgentIP())),
                        request.getRequest().getServerAgentIP()));
                StatCollector.getStatCollector().connectionAdded();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        List<Integer> ports = new ArrayList<>(request.getRequest().getNumParallelSockets());
        for (Channel channel : channels
                ) {
            InetSocketAddress socketAddress = (InetSocketAddress) channel.localAddress();
            ports.add(socketAddress.getPort());
        }
        try {
            boolean remoteAgentRes = notifyRemoteAgent(ports);
        } catch (IOException e) {
            e.printStackTrace();
        }

        StatCollector.getStatCollector().hostAdded();
        startTime = System.currentTimeMillis();
    }



    @Override
    public boolean orderedPacket(ByteBuf packet) {
        byte[] bytes = new byte[packet.capacity() - 4];
        packet.getBytes(4, bytes);
        if (hostChannel.isWritable()) {
            ChannelFuture cf = hostChannel.writeAndFlush(bytes);
            cf.addListener(new ChannelFutureListener() {
                @Override
                public void operationComplete(ChannelFuture channelFuture) throws Exception {
                    totalBytes += packet.capacity();
                }
            });
            if (!cf.isSuccess()) log.error("write back to host not successful {}", cf.cause());
            return true;
        }

        return false;
    }

    public void setWriteBackChannel(Channel channel) {
        this.hostChannel = channel;
    }

    @Override
    public void HostStatusChanged(HostStatus hostStatus) {
        if (hostStatus == HostStatus.DONE) {
            //    log.info("DDD {}", ats.channelTrafficCounters().size());

            log.info("Client done sending ...shutting down all opened parallel socks. ");
            /*
                Send and empty buffer on all channels. and add listener for them. Once write is successful
                we will close all channels
             */
            for (Channel ch : channels
                    ) {
                ch.writeAndFlush(Unpooled.EMPTY_BUFFER)
                        .addListener(ChannelFutureListener.CLOSE);
            }
            StatCollector.getStatCollector().hostRemoved();

            // ats.release();
            //  eventLoopGroup.shutdownGracefully();

            StatCollector.getStatCollector().connectionRemoved();
            long stopTime = System.currentTimeMillis();
            log.info("Agentclient rate {}", (totalBytes * 8) / (stopTime - startTime) / 1000);

        }
    }

    @Override
    public void SocketStats(long lastWriteThroughput, long lastReadThroughput) {
        log.info("Write {}", lastWriteThroughput * 8 / 1024 /1024);
    }

    public class AgentClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            //    log.debug("Reading from remote agent");
       //     int size = ((ByteBuf) msg).capacity();
            //log.info(size + "");
        //    if (size > 0)
                myBuffer.incomingPacket((ByteBuf) msg);

            //ReferenceCountUtil.release(msg);
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            ctx.flush(); //flush any unsent data
            log.debug("Channel is inactive");
        }

    }


    //TODO: apache is deprecated webclient...use some other one
    private boolean notifyRemoteAgent(List<Integer> ports) throws IOException {
        String uri = RestRoutes.URIBuilder(request.getRequest().getServerAgentIP(), REST_PORT, PORTMAP_PATH);
        HttpClient httpClient = new DefaultHttpClient();
        HttpPost httpRequest = new HttpPost(uri);

        RequestTemplateWrapper portMap = new RequestTemplateWrapper(request.getRequest(), ports); //portmap contains both controller request and all the associated portss
        ObjectMapper mapperObj = new ObjectMapper();
        String portMapString = mapperObj.writeValueAsString(portMap);

        org.apache.http.entity.StringEntity stringEntry = new org.apache.http.entity.StringEntity(portMapString, "UTF-8");
        httpRequest.setEntity(stringEntry);
        log.debug("JSON Object to sent {}", portMapString);
        HttpResponse response = httpClient.execute(httpRequest);

        log.debug("Sending HTTP request to remote agent with port info {}", request.getRequest().getServerAgentIP());
        log.debug("Agent returned {}", response.getStatusLine().getStatusCode());
        return Boolean.parseBoolean(response.toString());
    }


    public void incomingPacket(ByteBuf data) {
        if (currentChannelNo == request.getRequest().getNumParallelSockets()) currentChannelNo = 0;
        writeToAgentChannel(channels.get(currentChannelNo), data);
        currentChannelNo++;
    }

    private void writeToAgentChannel(Channel currentChannel, ByteBuf data) {
        ChannelFuture cf = currentChannel.write(data);
        wCount++;
        if (wCount >= request.getRequest().getBufferSize() * request.getRequest().getNumParallelSockets()) {
            for (Channel channel : channels)
                channel.flush();
            wCount = 0;
        }
  /*      cf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (cf.isSuccess()) totalBytes += data.capacity();
                else log.error("Failed to write packet to channel {}", cf.cause());
            }
        });*/
    }

    private Bootstrap bootStrap(EventLoopGroup group, String agentServerIP) {
        try {
            ats = new AgentTrafficShaping(eventLoopGroup, 5000);
            ats.setStatListener(this);

            Bootstrap bootstrap = new Bootstrap().group(group)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer() {
                        @Override
                        protected void initChannel(Channel channel) throws Exception {
                            channel.pipeline()
                                    .addLast("agent-traffic-shaping", ats)
                                    .addLast("lengthdecorder", new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
                                    .addLast("agentClient", new AgentClientHandler())
                                    .addLast("4blength", new LengthFieldPrepender(4))
                            //  .addLast("bytesEncoder", new ByteArrayEncoder())
                            ;
                        }
                    });

            //    Channel myChannel = bootstrap.connect(agentServerIP, AGENT_DATA_PORT).sync().channel();
            //if (myChannel == null) log.debug("in start it is nul");
            //      log.debug("Connected to Agent-Server {} on Port {}", agentServerIP, AGENT_DATA_PORT);
            //      return myChannel;
            return bootstrap;
        } catch (Exception e) {
            log.error("Error connecting to Agent-Server {} on Port{}", agentServerIP, AGENT_DATA_PORT);
            e.printStackTrace();
            return null;
        } finally {
            //group.shutdownGracefully();
        }
    }

    private Channel connectToChannel(Bootstrap bootstrap, String agentServerIP) throws InterruptedException {
        log.debug("Connected to Agent-Server {} on Port {}", agentServerIP, AGENT_DATA_PORT);
        return bootstrap.connect(agentServerIP, AGENT_DATA_PORT).sync().channel();
    }

    private NioEventLoopGroup createEventLoopGroup() {
        return new NioEventLoopGroup();
    }

}
