package com.spoqn.server.api;

import java.util.List;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.Produces;
import javax.ws.rs.core.MediaType;

import com.spoqn.server.core.Messages;
import com.spoqn.server.data.entities.Message;

@Path("/messages")
public class MessageResource {

    private Messages messages = new Messages();

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Message> get() {
        return messages.getAll();
    }
}
