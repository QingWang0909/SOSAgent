package edu.clemson.openflow.sos.agent;

import edu.clemson.openflow.sos.buf.Buffer;
import edu.clemson.openflow.sos.buf.OrderedPacketListener;
import edu.clemson.openflow.sos.host.HostClient;
import edu.clemson.openflow.sos.host.HostStatusInitiator;
import edu.clemson.openflow.sos.host.HostStatusListener;
import edu.clemson.openflow.sos.rest.RequestTemplateWrapper;
import edu.clemson.openflow.sos.stats.StatCollector;
import edu.clemson.openflow.sos.utils.Utils;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.util.ReferenceCountUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.util.ArrayList;

public class AgentToHost implements OrderedPacketListener, HostPacketListener, HostStatusListener {
    private static final Logger log = LoggerFactory.getLogger(AgentToHost.class);

    private RequestTemplateWrapper request;
    private ArrayList<Channel> channels;

    private HostPacketInitiator hostPacketInitiator;
    private final HostStatusInitiator hostStatusInitiator;
    private SendingStrategy sendingStrategy;

    private HostClient hostClient;
    private HostStatus hostStatus;
    private Buffer buffer;

    private int currentChannelNo = 0;
    private long totalBytes, startTime, endTime;
    private int wCount;
    private long writableCount, unwritableCount;
    int shouldSend = 0;



    public AgentToHost(RequestTemplateWrapper request) {
        this.request = request;
        channels = new ArrayList<>();

        hostClient = new HostClient(request);

        hostPacketInitiator = new HostPacketInitiator();
        hostPacketInitiator.addListener(this);
        hostClient.setHostPacketListenerInitiator(hostPacketInitiator);

        hostStatusInitiator = new HostStatusInitiator();
        hostStatusInitiator.addListener(hostClient);

        sendingStrategy = new RRSendingStrategy(request.getRequest().getNumParallelSockets());

        hostClient.start(request.getRequest().getServerIP(), request.getRequest().getServerPort());
        log.debug("Created & started new host handler for server {} port {}",
                request.getRequest().getServerIP(),
                request.getRequest().getServerPort());
        startTime = System.nanoTime()/1000000;

    }


    public void setBuffer(Buffer buffer) {
        this.buffer = buffer;
    }

    @Override
    public boolean orderedPacket(ByteBuf packet) {
         if (!sendToHost(packet))
             packet.release();
         return true;
         //return sendToHost(packet); //This is real
    }

    private boolean sendToHost(ByteBuf packet) {
     //   smartSend(packet);
        //TODO: @smartSend().. separate write and flush
        if (hostClient.getHostChannel().isWritable()) {
           // hostClient.getHostChannel().writeAndFlush(packet.slice(4, packet.capacity() - 4));
            hostClient.getHostChannel().writeAndFlush(packet);
            writableCount++;
            return true;
        } else {
            unwritableCount++;
            return false;
        }
    }

    private void smartSend(ByteBuf packet) {
        if (hostClient.getHostChannel().isWritable()) {
            hostClient.getHostChannel().write(packet);
            writableCount++;
        }  else {
            packet.release();
            unwritableCount++;
        }
        shouldSend++;

        if (shouldSend > request.getRequest().getQueueCapacity()) {
            hostClient.getHostChannel().flush();
            shouldSend = 0;
          //  log.info("FLUSHHHED");
        }
    }

    public void addChannel(Channel channel) {
        channels.add(channel);
        log.debug("added channel for client {} : {} server {} : {}",
                request.getRequest().getClientIP(),
                request.getRequest().getClientPort(),
                request.getRequest().getServerIP(),
                request.getRequest().getServerPort());
    }

    public RequestTemplateWrapper getRequest() {
        return request;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        try {
            RequestTemplateWrapper hostRequest = (RequestTemplateWrapper) o;
            if (request.getRequest().getServerPort() != hostRequest.getRequest().getServerPort() ||
                    request.getRequest().getClientPort() != hostRequest.getRequest().getClientPort())
                return false; // also doing check on client port as if same client can have multiple parallel conns opened to single server
            return request.getRequest().getServerIP().equals(hostRequest.getRequest().getServerIP());
        } catch (ClassCastException exp) {
            AgentToHost host = (AgentToHost) o;
            if (request.getRequest().getServerPort() != host.request.getRequest().getServerPort() ||
                    request.getRequest().getClientPort() != host.request.getRequest().getClientPort())
                return false; // also doing check on client port as if same client can have multiple parallel conns opened to single server
            return request.getRequest().getServerIP().equals(host.request.getRequest().getServerIP());
        }

    }

    @Override
    public int hashCode() {
        return 0;
    }

    @Override
    public void hostPacket(ByteBuf packet) {
       //      ByteBuf data = (ByteBuf) packet;
       //      log.info("SIZE {}", data.capacity());
       //      String s = data.readCharSequence(data.capacity(), Charset.forName("utf-8")).toString();
      //       System.out.print(s);
      //  if (currentChannelNo == request.getRequest().getNumParallelSockets()) currentChannelNo = 0;
      //  writeToAgentChannel(channels.get(currentChannelNo), packet);
       // currentChannelNo++;
        writeToAgentChannel(channels.get(sendingStrategy.channelToSendOn()), packet);

    }
    public synchronized void increaseWriteCount() { ++writableCount; }
    public synchronized void decreaseWriteCount() {
        --writableCount;
    }

    private void writeToAgentChannel(Channel currentChannel, ByteBuf data) {
        ChannelFuture cf = currentChannel.write(data);
        increaseWriteCount();
        currentChannel.flush();
      //  wCount++;
      //  if (wCount >= request.getRequest().getBufferSize() * request.getRequest().getNumParallelSockets()) {
      ///      for (Channel channel : channels)
       //         channel.flush();
       //     wCount = 0;
       // }
        cf.addListener(new ChannelFutureListener() {
            @Override
            public void operationComplete(ChannelFuture channelFuture) throws Exception {
                if (cf.isSuccess()) {
                    totalBytes += data.capacity();
                    decreaseWriteCount();
                    if (writableCount == 0 && hostStatus == HostStatusListener.HostStatus.DONE) { //means host is done sending and all data have been flushed. Its time to close all channels
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
    public void transferCompleted() {
        if (hostPacketInitiator != null) hostStatusInitiator.hostStatusChanged(HostStatusListener.HostStatus.DONE);
    }

    public HostClient getHostClient() {
        return hostClient;
    }

    private void closeAllChannels(){
        for (Channel ch : channels
             ) {
            ch.close();
        }
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
        }
    }
}