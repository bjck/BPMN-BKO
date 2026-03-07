package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN end event. Optional type: message (engine:messageRef) or error (engine:errorCode).
 */
public record EndEvent(
        String id,
        String name,
        List<String> incoming,
        EndEventType endType,
        String messageRef,
        String errorCode
) implements FlowNode {

    public EndEvent(String id, String name, List<String> incoming) {
        this(id, name, incoming, EndEventType.NONE, null, null);
    }
}
