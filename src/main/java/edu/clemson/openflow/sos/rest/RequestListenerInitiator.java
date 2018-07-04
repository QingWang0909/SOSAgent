package edu.clemson.openflow.sos.rest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class RequestListenerInitiator {
    private static final Logger log = LoggerFactory.getLogger(RequestListenerInitiator.class);

    private List<RequestListener> requestListenerList = new ArrayList<>();

    public List<RequestListener> getRequestListenerList() {
        return requestListenerList;
    }

    public void addRequestListener(RequestListener listener) {
        requestListenerList.add(listener);
    }

    public void newIncomingRequest(RequestTemplateWrapper request) {
        for (RequestListener listener : requestListenerList
                ) {
            listener.newIncomingRequest(request);
        }
    }

}
