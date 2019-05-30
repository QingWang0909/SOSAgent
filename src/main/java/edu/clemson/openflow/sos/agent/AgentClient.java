package edu.clemson.openflow.sos.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.clemson.openflow.sos.buf.Buffer;
import edu.clemson.openflow.sos.buf.OrderedPacketInitiator;
import edu.clemson.openflow.sos.buf.OrderedPacketListener;
import edu.clemson.openflow.sos.host.HostStatusListener;
import edu.clemson.openflow.sos.rest.RequestTemplateWrapper;
import edu.clemson.openflow.sos.rest.RestRoutes;
import edu.clemson.openflow.sos.shaping.AgentTrafficShaping;
import edu.clemson.openflow.sos.shaping.ISocketStatListener;
import edu.clemson.openflow.sos.stats.StatCollector;
import io.netty.bootstrap.Bootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import io.netty.handler.codec.LengthFieldPrepender;
import io.netty.util.ReferenceCountUtil;
import org.apache.http.HttpResponse;
import org.apache.http.client.HttpClient;
import org.apache.http.client.methods.HttpPost;
import org.apache.http.impl.client.DefaultHttpClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.lang.*;

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
    private static final String REST_PORT = System.getenv("SOS_REST_PORT");
    private static final int AGENT_DATA_PORT = Integer.parseInt(System.getenv("SOS_AGENT_DATA_PORT"));;

    private long startTime;
    private float totalBytes;
    private HashMap<Integer, Float> perChBytes;
    int wCount = 0;


    private RequestTemplateWrapper request;
    private ArrayList<Channel> channels;
    private Buffer buffer;
    private Channel hostChannel;
    private EventLoopGroup eventLoopGroup;
    private AgentTrafficShaping ats;
    private HostStatus hostStatus;

    private OrderedPacketInitiator orderedPacketInitiator;
    private SendingStrategy sendingStrategy;

    private int gotStatsFrom;
    private double totalReadThroughput, totalWriteThroughput;
    private long writeCount = 0;

    private long writableCount, unwritableCount;


    public AgentClient(RequestTemplateWrapper request) {
        this.request = request;

        orderedPacketInitiator = new OrderedPacketInitiator();
        orderedPacketInitiator.addListener(this);
        sendingStrategy = new RRSendingStrategy(request.getRequest().getNumParallelSockets());

        perChBytes = new HashMap<>(request.getRequest().getNumParallelSockets());
        channels = new ArrayList<>(request.getRequest().getNumParallelSockets());

        buffer = new Buffer(request);
        buffer.setOrderedPacketInitiator(orderedPacketInitiator);
    }

    public synchronized void increaseWriteCount() { ++writableCount; }
    public synchronized void decreaseWriteCount() {
        --writableCount;
    }

    public void bootStrapSockets() {
        eventLoopGroup = createEventLoopGroup();
        log.debug("Bootstrapping {} connections to agent server {}",
                request.getRequest().getNumParallelSockets(),
                request.getRequest().getServerAgentIP());
        try {
            for (int i = 0; i < request.getRequest().getNumParallelSockets(); i++) {
                channels.add(connectToChannel(bootStrap(eventLoopGroup, (request.getRequest().getServerAgentIP())),
                        (request.getRequest().getServerAgentIP())));
                StatCollector.getStatCollector().connectionAdded();
            }
        } catch (InterruptedException e) {
            e.printStackTrace();
        }
        List<Integer> ports = new ArrayList<>(request.getRequest().getNumParallelSockets());
        for (Channel channel : channels) {
            InetSocketAddress socketAddress = (InetSocketAddress) channel.localAddress();
            ports.add(socketAddress.getPort());
        }
        try {
            notifyRemoteAgent(ports); //TODO: Based on remote agent response code.. take actions i.e if request is not valid than dont start sending packet

        } catch (IOException e) {
            e.printStackTrace();
        }

        StatCollector.getStatCollector().hostAdded();
        startTime = System.currentTimeMillis();
    }


    @Override
    public boolean orderedPacket(ByteBuf packet) {
        return sendToHost(packet);
      /*  byte[] bytes = new byte[packet.capacity() - 4];
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

        return false;*/
    }

    private boolean sendToHost(ByteBuf packet) {
        //   smartSend(packet);
        //TODO: @smartSend().. separate write and flush
        if (hostChannel.isWritable()) {
            // hostClient.getHostChannel().writeAndFlush(packet.slice(4, packet.capacity() - 4));
            hostChannel.writeAndFlush(packet);
            writableCount++;
            return true;
        } else {
            unwritableCount++;
            return false;
        }
    }

    public void setWriteBackChannel(Channel channel) {
        this.hostChannel = channel;
    }

    @Override
    public void HostStatusChanged(HostStatus hostStatus) {
        this.hostStatus = hostStatus;
        if (hostStatus == HostStatus.DONE && writableCount == 0) {
            log.info("Client {}:{} to server {}:{} is done",
                    request.getRequest().getClientIP(),
                    request.getRequest().getClientPort(),
                    request.getRequest().getServerIP(),
                    request.getRequest().getServerPort());

            closeAllChannels();

            StatCollector.getStatCollector().hostRemoved();

            ats.release();
            eventLoopGroup.shutdownGracefully();

            StatCollector.getStatCollector().connectionRemoved();
            long stopTime = System.currentTimeMillis();
            log.debug("AgentClient rate {}", (totalBytes * 8) / (stopTime - startTime) / 1000);

        }
    }

    @Override
    public void SocketStats(long lastWriteThroughput, long lastReadThroughput) {
        gotStatsFrom++;
        sumThroughput(lastWriteThroughput, lastReadThroughput);
        if (gotStatsFrom == StatCollector.getStatCollector().getTotalOpenConnections()) {
            log.debug("Sending Rate {} Gbps", totalWriteThroughput * 8 / 1024 / 1024 / 1024);
            gotStatsFrom = 0;
            totalReadThroughput = 0;
            totalWriteThroughput = 0;
        }
    }

    public class AgentClientHandler extends ChannelInboundHandlerAdapter {

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            StatCollector.getStatCollector().connectionAdded();
            super.channelActive(ctx);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
            if (buffer != null) buffer.incomingPacket((ByteBuf) msg);
            else {
                log.error("Receiving buffer NULL for Remote Agent ");
                ReferenceCountUtil.release(msg);
            }
            totalBytes += ((ByteBuf) msg).capacity();
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {
            StatCollector.getStatCollector().connectionRemoved();
        }
    }

    private synchronized void sumThroughput(long lastWriteThroughput, long lastReadThroughput) {
        totalReadThroughput += lastReadThroughput;
        totalWriteThroughput += lastWriteThroughput;
    }

    //TODO: apache is deprecated webclient...use some other one
    private void notifyRemoteAgent(List<Integer> ports) throws IOException {
        // request.getRequest().setServerAgentIP(maskIP(request.getRequest().getServerAgentIP()));
        String uri = RestRoutes.URIBuilder(request.getRequest().getServerAgentIP(), REST_PORT, PORTMAP_PATH);
        HttpClient httpClient = new DefaultHttpClient();
        HttpPost httpRequest = new HttpPost(uri);

        RequestTemplateWrapper portMap = new RequestTemplateWrapper(request.getRequest(), ports); //portmap contains both controller request and all the associated portss
        ObjectMapper mapperObj = new ObjectMapper();
        String requestWithPortsString = mapperObj.writeValueAsString(portMap);

        org.apache.http.entity.StringEntity stringEntry = new org.apache.http.entity.StringEntity(requestWithPortsString, "UTF-8");
        httpRequest.setEntity(stringEntry);
        log.debug("JSON Object to sent {}", requestWithPortsString);
        HttpResponse response = httpClient.execute(httpRequest);

        log.debug("Sending HTTP request to remote agent {} ", requestWithPortsString);
        log.debug("Agent returned HTTP STATUS {} Response {}", response.getStatusLine().getStatusCode(), response.toString());

    }

    public void incomingPacket(ByteBuf data) {
        writeToAgentChannel(channels.get(sendingStrategy.channelToSendOn()), data);
    }

    private void writeToAgentChannel(Channel currentChannel, ByteBuf data) {

        // log.info("Readable {}", data.readableBytes());
        //  String s = data.readCharSequence(data.capacity(), Charset.forName("utf-8")).toString();
        //   log.info("SIZE {}", data.capacity());
        //   System.out.print(s);

        ChannelFuture cf = currentChannel.write(data);
        increaseWriteCount();
        currentChannel.flush();

     /*   wCount++;
        if (wCount >= request.getRequest().getBufferSize() * request.getRequest().getNumParallelSockets()) {
            for (Channel channel : channels)
                channel.flush();
            wCount = 0;
        }*/
        cf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (cf.isSuccess()) {
                    totalBytes += data.capacity();
                    decreaseWriteCount();
                    if (writableCount == 0 && AgentClient.this.hostStatus == HostStatus.DONE) { //means host is done sending and all data have been flushed. Its time to close all channels
                        log.info("Client {}:{} to server {}:{} is done",
                                request.getRequest().getClientIP(),
                                request.getRequest().getClientPort(),
                                request.getRequest().getServerIP(),
                                request.getRequest().getServerPort());

                        closeAllChannels();
                    }
                } else
                    log.error("Failed to write packet to channel for client {}:{} cause .... ", request.getRequest().getClientIP(),
                            request.getRequest().getClientPort(), cf.cause());
            }
        });
    }

    private void closeAllChannels() {
        for (Channel ch : channels)
            ch.close();
    }

    private Bootstrap bootStrap(EventLoopGroup group, String agentServerIP) {
        try {
            ats = new AgentTrafficShaping(eventLoopGroup, 5000);
            ats.setStatListener(this);

            Bootstrap bootstrap = new Bootstrap()
                    .group(group)
                    //   .option(ChannelOption.TCP_NODELAY, true)
                    //    .option(ChannelOption.SO_KEEPALIVE, true)
                    .channel(NioSocketChannel.class)
                    .handler(new ChannelInitializer() {
                        @Override
                        protected void initChannel(Channel channel) throws Exception {
                            channel.pipeline()
                                    .addLast("agent-traffic-shaping", ats)
                                    .addLast("length-decoder", new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
                                    .addLast("agent-client", new AgentClientHandler())
                                    .addLast("4b-length", new LengthFieldPrepender(4))
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
