package com.bko.bpmn_engine.engine.kafka;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;
import java.util.UUID;

/**
 * Payload for BPMN message/signal events sent to or received from Kafka.
 * Uses our own structure (no Zeebe/Camunda naming).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record BpmnEventPayload(
        String messageRef,
        String signalRef,
        String correlationKey,
        String processDefinitionId,
        UUID instanceId,
        String nodeId,
        Map<String, Object> variables,
        String errorCode
) {
    /** For throw/message end: messageRef or signalRef, correlationKey, instanceId, nodeId, variables. */
    public static BpmnEventPayload forThrow(String messageRef, String signalRef, String correlationKey,
                                            UUID instanceId, String nodeId, Map<String, Object> variables) {
        return new BpmnEventPayload(messageRef, signalRef, correlationKey, null, instanceId, nodeId, variables, null);
    }

    /** For error end. */
    public static BpmnEventPayload forError(String errorCode, String correlationKey, UUID instanceId,
                                            String nodeId, Map<String, Object> variables) {
        return new BpmnEventPayload(null, null, correlationKey, null, instanceId, nodeId, variables, errorCode);
    }

    /** For message start: messageRef, correlationKey, processDefinitionId, variables. */
    public static BpmnEventPayload forMessageStart(String messageRef, String correlationKey,
                                                    String processDefinitionId, Map<String, Object> variables) {
        return new BpmnEventPayload(messageRef, null, correlationKey, processDefinitionId, null, null, variables, null);
    }
}
