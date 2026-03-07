package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN intermediate catch event: wait for message (engine:messageRef) or timer (engine:timerDefinition).
 */
public record IntermediateCatchEvent(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        CatchEventType catchType,
        String messageRef,
        String timerDefinition
) implements FlowNode {
}
