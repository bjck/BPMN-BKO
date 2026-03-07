package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN start event. Optional trigger: message (engine:messageRef) or timer (engine:timerDefinition).
 */
public record StartEvent(
        String id,
        String name,
        List<String> outgoing,
        StartEventTrigger trigger,
        String messageRef,
        String timerDefinition
) implements FlowNode {

    public StartEvent(String id, String name, List<String> outgoing) {
        this(id, name, outgoing, StartEventTrigger.NONE, null, null);
    }
}
