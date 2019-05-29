package edu.clemson.openflow.sos.rest;

import edu.clemson.openflow.sos.utils.Utils;
import org.restlet.Application;
import org.restlet.Component;
import org.restlet.Restlet;
import org.restlet.Server;
import org.restlet.data.Protocol;
import org.restlet.routing.Router;
import org.slf4j.LoggerFactory;
import java.lang.*;

/**
 * @author Khayam Anjam kanjam@g.clemson.edu
 * RestServer to receive http requests from controller and other agents. It uses port 8002
 */
public class RestServer {
    private Component component;
    private static final org.slf4j.Logger log = LoggerFactory.getLogger(RestServer.class);
    protected static final int REST_SERVER_PORT = Integer.parseInt(System.getenv("SOS_REST_PORT"));



    public RestServer() {
        component = new Component();
        Server server = new Server(Protocol.HTTP, REST_SERVER_PORT);
        component.getServers().add(server);
        component.getDefaultHost().attach(getRoutes());

    }



    private Restlet getRoutes() {
        RestRoutes restRoutes = new RestRoutes(component.getContext().createChildContext());
        return restRoutes.getRoutes();
    }


    public boolean startComponent()  {
        try {
            component.start();
            log.info("Rest Server started on port {}", REST_SERVER_PORT);
            return true;
        } catch (Exception e) {
            log.error("Failed to start Rest Server");
            e.printStackTrace();
            return false;
        }

    }

    public boolean stopComponent()  {
        try {
            component.stop();
            log.info("Rest Server stopped");
            return true;
        } catch (Exception e) {
            log.error("Error stopping Rest Server");
            e.printStackTrace();
            return false;
        }
    }


}