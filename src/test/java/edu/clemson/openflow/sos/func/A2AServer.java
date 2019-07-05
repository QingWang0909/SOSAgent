package edu.clemson.openflow.sos.func;

import edu.clemson.openflow.sos.agent.AgentServer;
import edu.clemson.openflow.sos.rest.RestServer;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.StandardSocketOptions;

import static junit.framework.TestCase.assertEquals;

public class A2AServer {

    private static final Logger log = LoggerFactory.getLogger(A2AServer.class);

    private ServerSocket setupServerSocket(int port) throws IOException {
        log.info("Started mock server on port {}", port);
        return new ServerSocket(port);
    }

    @Test
    public void main() {

        RestServer restServer = new RestServer();
        assertEquals(restServer.startComponent(), true);

        AgentServer agentServer = new AgentServer();
        assertEquals(agentServer.start(), true);



        try {
            ServerSocket listener = setupServerSocket(5001);
            Socket socket = listener.accept();
            InputStream inputStream = socket.getInputStream();
            int c;
            long startTime = System.nanoTime()/1000000;
            long totalBytes = 0;
            while ((c = inputStream.read()) != -1) {
                //System.out.print((char) c);
                totalBytes++;
                long currentTime = System.nanoTime()/1000000;
                if (totalBytes > 9000000) {
                   long diffInSec = (currentTime - startTime) / 1000;
                  System.out.println("llllThroughput Mbps " + (totalBytes / diffInSec) * 8 / 1000000);
               }
            }

            long endTime = System.nanoTime()/1000000;
            long diffInSec = (endTime - startTime) / 1000;
            System.out.println("Total bytes "+ totalBytes);
            System.out.println("Total time "+ diffInSec);
            System.out.println("Throughput Mbps "+  (totalBytes / diffInSec) * 8 / 1000000 );

        } catch (IOException e) {
            e.printStackTrace();
        }


    }
}