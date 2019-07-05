package edu.clemson.openflow.sos.agent;

/**
 * @author khayam anjam kanjam@g.clemson.edu
 * This class receives data form AgentClient and writes into Buffer
 */

import com.fasterxml.jackson.databind.ObjectMapper;
import edu.clemson.openflow.sos.buf.Buffer;
import edu.clemson.openflow.sos.buf.BufferManager;
import edu.clemson.openflow.sos.manager.ISocketServer;
import edu.clemson.openflow.sos.rest.RequestListener;
import edu.clemson.openflow.sos.rest.RequestListenerInitiator;
import edu.clemson.openflow.sos.rest.RequestTemplateWrapper;
import edu.clemson.openflow.sos.rest.RestRoutes;
import edu.clemson.openflow.sos.shaping.AgentTrafficShaping;
import edu.clemson.openflow.sos.shaping.ISocketStatListener;
import edu.clemson.openflow.sos.shaping.StatsTemplate;
import edu.clemson.openflow.sos.stats.StatCollector;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
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
import java.util.List;
import java.lang.*;

public class AgentServer implements ISocketServer, ISocketStatListener {

    private static final Logger log = LoggerFactory.getLogger(AgentServer.class);

    private static final String TRAFFIC_PATH = "/traffic";
    private static final String REST_PORT = System.getenv("SOS_REST_PORT");
    private static final int AGENT_DATA_PORT = Integer.parseInt(System.getenv("SOS_AGENT_DATA_PORT"));

    private int chz;


    private BufferManager bufferManager;
    private AgentToHostManager hostManager;

    private List<RequestTemplateWrapper> incomingRequests;
    private NioEventLoopGroup group;

    private List<AgentServerHandler> handlers;
    public static RequestListenerInitiator requestListenerInitiator; //TODO: use context to send listener

    private int gotStatsFrom;
    private double totalReadThroughput, totalWriteThroughput; //also need to reset these after we send these back to other agents and before
    // we have new stats available. Also keep track of total open parallel connections to findout if we have received stats from all connections.

    public AgentServer() {
        incomingRequests = new ArrayList<>();
        bufferManager = new BufferManager(); //setup buffer manager.
        hostManager = new AgentToHostManager();

        requestListenerInitiator = new RequestListenerInitiator();

    }

    @Override
    public void SocketStats(long lastWriteThroughput, long lastReadThroughput) {
        gotStatsFrom++;
        sumThroughput(lastWriteThroughput, lastReadThroughput);
        if (gotStatsFrom == StatCollector.getStatCollector().getTotalOpenConnections()) { //mean now we have gotten stats from all conns. time to notify other agents
            log.debug("Notifying client-agent about stats for {} opened connections", gotStatsFrom);
            try {
                notifyAgents();
            } catch (IOException e) {
                e.printStackTrace();
            }
            gotStatsFrom = 0;
            totalReadThroughput = 0;
            totalWriteThroughput = 0;

        }

    }

    private void notifyAgents() throws IOException {
        for (RequestTemplateWrapper request : incomingRequests
                ) {
            String uri = RestRoutes.URIBuilder(request.getRequest().getClientAgentIP(), REST_PORT, TRAFFIC_PATH);
            HttpClient httpClient = new DefaultHttpClient();
            HttpPost httpRequest = new HttpPost(uri);

            StatsTemplate statsTemplate = new StatsTemplate(totalWriteThroughput, totalReadThroughput);

            ObjectMapper mapperObj = new ObjectMapper();
            String portMapString = mapperObj.writeValueAsString(statsTemplate);

            org.apache.http.entity.StringEntity stringEntry = new org.apache.http.entity.StringEntity(portMapString, "UTF-8");
            httpRequest.setEntity(stringEntry);
            log.debug("JSON Object to sent {}", portMapString);
            HttpResponse response = httpClient.execute(httpRequest);

            log.debug("Sending HTTP request to remote agent with port info {}", request.getRequest().getServerAgentIP());
            log.debug("Agent returned {}", response.getStatusLine().getStatusCode());
        }
    }

    private synchronized void sumThroughput(long lastWriteThroughput, long lastReadThroughput) {
        totalReadThroughput += lastReadThroughput;
        totalWriteThroughput += lastWriteThroughput;
    }

    private synchronized AgentToHost getHostHandler(RequestTemplateWrapper request) {
        addToRequestPool(request); // also remove this request once connection terminates. TODO
        return hostManager.addAgentToHost(request);
    }

    public class AgentServerHandler extends ChannelInboundHandlerAdapter implements RequestListener {

        private Buffer buffer;
        private AgentToHost endHostHandler;
        private String remoteAgentIP;
        private int remoteAgentPort;

        private ChannelHandlerContext context;
        private float totalBytes;
        private long startTime;

        boolean receivedRequest;

        @Override
        public void channelActive(ChannelHandlerContext ctx) throws Exception {
            InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
            log.debug("New agent-side connection from agent {} at Port {}",
                    socketAddress.getHostName(),
                    socketAddress.getPort());

            this.context = ctx;

            remoteAgentIP = socketAddress.getHostName();
            remoteAgentPort = socketAddress.getPort();

            requestListenerInitiator.addRequestListener(this);
            if (this == null) log.info("EHy nULLL ");
            //    Utils.router.getContext().getAttributes().put("agent-callback", requestListenerInitiator);

            StatCollector.getStatCollector().connectionAdded();

            startTime = System.nanoTime()/1000000;


        }

        private boolean isMineRequest(RequestTemplateWrapper request, AgentServerHandler handler) {
            return request.getPorts().contains(((InetSocketAddress) handler.context.channel().remoteAddress()).getPort());
        }


        /*  Whenever AgentServer receives new port request from AgentClient.
        This method will be called and all the open channels
                    will be notified.
        */

        @Override
        public void newIncomingRequest(RequestTemplateWrapper request) {
                if (isMineRequest(request, this)) {
                    endHostHandler = getHostHandler(request);
                    endHostHandler.addChannel(this.context.channel());
                    log.debug("Channel added for Client {}:{} Server {}:{}",
                            request.getRequest().getClientIP(),
                            request.getRequest().getClientPort(),
                            request.getRequest().getServerIP(),
                            request.getRequest().getServerPort());
                    this.buffer = bufferManager.addBuffer(request, endHostHandler);
                }
                endHostHandler.setBuffer(buffer);
        }

        @Override
        public void channelRead(ChannelHandlerContext ctx, Object msg) {
            if (buffer != null) buffer.incomingPacket((ByteBuf) msg);
            else {
                log.error("Receiving buffer NULL for Remote Agent {}:{} ", remoteAgentIP, remoteAgentPort);
                ((ByteBuf) msg).release();
            }
            /*   totalBytes += ((ByteBuf) msg).capacity();*/
        }

        @Override
        public void channelInactive(ChannelHandlerContext ctx) {

            long stopTime = System.nanoTime()/1000000;

            if (endHostHandler != null)
                endHostHandler.transferCompleted(); // notify the host server

            requestListenerInitiator = new RequestListenerInitiator(); //also reset the listener to remove old listeners
            hostManager.removeAgentToHost(endHostHandler);
            bufferManager.removeBuffer(buffer);

            StatCollector.getStatCollector().connectionRemoved();

        }

        private void removeFromRequestPool(RequestTemplateWrapper request) {
            if (incomingRequests.contains(request)) incomingRequests.remove(request);
        }

    }

    private void addToRequestPool(RequestTemplateWrapper request) {
        if (!incomingRequests.contains(request)) incomingRequests.add(request);
    }

    private boolean startSocket(int port) {
        group = new NioEventLoopGroup();
        AgentTrafficShaping ats = new AgentTrafficShaping(group, 5000);
        ats.setStatListener(this);
        try {
            ServerBootstrap b = new ServerBootstrap()
                    .group(group)
                    //      .option(ChannelOption.TCP_NODELAY, true)
                    //       .option(ChannelOption.SO_KEEPALIVE, true)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer() {
                                      @Override
                                      protected void initChannel(Channel channel) throws Exception {
                                          channel.pipeline()
                                                  .addLast("agent-traffic-shaping", ats)
                                                  .addLast("length-decoder", new LengthFieldBasedFrameDecoder(Integer.MAX_VALUE, 0, 4, 0, 4))
                                                  .addLast(new AgentServerHandler())
                                                  .addLast("4b-length", new LengthFieldPrepender(4));
                                      }
                                  }
                    );

            ChannelFuture f = b.bind().sync();
            log.info("Started agent-side server at Port {}", port);
            return true;
            // Need to do socket closing handling. close all the remaining open sockets
            //System.out.println(EchoServer.class.getName() + " started and listen on " + f.channel().localAddress());
            //f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("Error starting agent-side server");
            e.printStackTrace();
            return false;
        } finally {
            //group.shutdownGracefully().sync();
        }
    }

    private RequestTemplateWrapper getMyRequestByClientAgentPort(String remoteIP, int remotePort) {
        for (RequestTemplateWrapper incomingRequest : incomingRequests) {
            if (incomingRequest.getRequest().getClientAgentIP().equals(remoteIP)) {
                for (int port : incomingRequest.getPorts()
                        ) {
                    if (port == remotePort) {
                        deleteRequestFromList(incomingRequest); // also delete this request from pool. we wont be needing it.
                        return incomingRequest;
                    }
                }
            }
        }
        return null;
    }

    private void deleteRequestFromList(RequestTemplateWrapper request) {
        incomingRequests.remove(request);
    }

    @Override
    public boolean start() {
        return startSocket(AGENT_DATA_PORT);
    }

    @Override
    public boolean stop() {
        group.shutdownGracefully();
        log.info("Shutting down AgentServer");
        return true;
    }


}
