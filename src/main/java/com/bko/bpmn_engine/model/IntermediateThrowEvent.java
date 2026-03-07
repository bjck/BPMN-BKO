package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN intermediate throw event: publish message (engine:messageRef) or signal (engine:signalRef) to Kafka.
 */
public record IntermediateThrowEvent(
        String id,
        String name,
        List<String> incoming,
        List<String> outgoing,
        ThrowEventType throwType,
        String messageRef,
        String signalRef
) implements FlowNode {
}
