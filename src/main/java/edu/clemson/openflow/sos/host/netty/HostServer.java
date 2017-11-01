package edu.clemson.openflow.sos.host.netty;

import edu.clemson.openflow.sos.agent.HostStatusInitiater;
import edu.clemson.openflow.sos.agent.HostStatusListener;
import edu.clemson.openflow.sos.agent.netty.AgentClient;
import edu.clemson.openflow.sos.manager.ISocketServer;
import edu.clemson.openflow.sos.manager.RequestManager;
import edu.clemson.openflow.sos.manager.SocketManager;
import edu.clemson.openflow.sos.rest.RequestParser;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.channel.*;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.handler.codec.bytes.ByteArrayDecoder;
import io.netty.handler.codec.bytes.ByteArrayEncoder;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;


/**
 * @author Khayam Anjam kanjam@g.clemson.edu
 * this class will start a new thread for every incoming connection from clients
 */
public class HostServer extends ChannelInboundHandlerAdapter implements ISocketServer, HostStatusListener {
    private static final Logger log = LoggerFactory.getLogger(SocketManager.class);
    private static final int DATA_PORT = 9877;
    private RequestParser request;
    //private Channel remoteChannel; // remote channel to write to
    private HostStatusInitiater hostStatusInitiater, callBackhostStatusInitiater;
    private AgentClient agentClient;
    private Channel myChannel;

    private boolean startSocket(int port) {
        NioEventLoopGroup group = new NioEventLoopGroup();
        try {
            ServerBootstrap b = new ServerBootstrap();
            b.group(group)
                    .channel(NioServerSocketChannel.class)
                    .localAddress(new InetSocketAddress(port))
                    .childHandler(new ChannelInitializer() {
                                      @Override
                                      protected void initChannel(Channel channel) throws Exception {
                                          channel.pipeline().addLast("bytesDecoder",
                                                  new ByteArrayDecoder());
                                          channel.pipeline().addLast("hostHandler", new HostServer());
                                          channel.pipeline().addLast("bytesEncoder", new ByteArrayEncoder());
                                      }
                                  }
                    );

            ChannelFuture f = b.bind().sync();
            //myChannel = f.channel();
            log.info("Started host-side socket server at Port {}", port);
            return true;
            // Need to do socket closing handling. close all the remaining open sockets
            //System.out.println(EchoServer.class.getName() + " started and listen on " + f.channel().localAddress());
            //f.channel().closeFuture().sync();
        } catch (InterruptedException e) {
            log.error("Error starting host-side socket");
            e.printStackTrace();
            return false;
        } finally {
            //group.shutdownGracefully().sync();
        }
    }

    @Override
    public boolean start() {
        return startSocket(DATA_PORT);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        InetSocketAddress socketAddress = (InetSocketAddress) ctx.channel().remoteAddress();
        log.info("New host-side connection from {} at Port {}",
                socketAddress.getHostName(),
                socketAddress.getPort());

        RequestManager requestManager = RequestManager.INSTANCE;
        this.request = requestManager.getRequest(socketAddress.getHostName(),
                socketAddress.getPort(), true);

        myChannel = ctx.channel();


        if (request != null) {
            hostStatusInitiater = new HostStatusInitiater();
            callBackhostStatusInitiater = new HostStatusInitiater();
            agentClient = new AgentClient();
            agentClient.start(request.getServerAgentIP());
            hostStatusInitiater.addListener(agentClient);
            hostStatusInitiater.hostConnected(request, callBackhostStatusInitiater); //also pass the call back handler so It can respond back
        }
        else log.error("Couldn't find the request {} in request pool. Not notifying agent",
                request.toString());

    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (request != null) {
            hostStatusInitiater.packetArrived(msg); //notify handlers
        }
        else log.error("Couldn't find the request {} in request pool. " +
                "Not forwarding packet", request.toString());
        ReferenceCountUtil.release(msg);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        cause.printStackTrace();
        ctx.close();
    }

    @Override
    public void channelReadComplete(ChannelHandlerContext ctx) {
        ctx.flush();
    }


    @Override
    public void hostConnected(RequestParser request, HostStatusInitiater hostStatusInitiater) {

    }

    @Override
    public void packetArrived(Object msg) {
        log.debug("Received new packet from agent sending to host");
        if (myChannel == null) log.error("Current context is null, wont be sending packet back to host");
        else myChannel.writeAndFlush(msg);
    }

    @Override
    public void hostDisconnected(String hostIP, int hostPort) {

    }

}
