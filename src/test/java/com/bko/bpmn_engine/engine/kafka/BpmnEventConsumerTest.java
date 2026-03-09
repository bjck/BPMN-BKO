package com.bko.bpmn_engine.engine.kafka;

import com.bko.bpmn_engine.engine.ProcessEngine;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BpmnEventConsumer. Tests the consumer logic with a mocked ProcessEngine
 * since the consumer requires bpmn.kafka.enabled=true to be instantiated as a bean.
 */
class BpmnEventConsumerTest {

    private ProcessEngine processEngine;
    private BpmnEventConsumer consumer;

    @BeforeEach
    void setUp() {
        processEngine = mock(ProcessEngine.class);
        consumer = new BpmnEventConsumer(processEngine);
    }

    @Test
    void onBpmnEvent_withNullPayload_doesNothing() {
        consumer.onBpmnEvent(null, "key");

        verifyNoInteractions(processEngine);
    }

    @Test
    void onBpmnEvent_messageStartWithProcessDefinitionId_triggersMessageStart() {
        BpmnEventPayload payload = BpmnEventPayload.forMessageStart("OrderReceived", "corr-1", "proc-1", Map.of("x", 1));

        consumer.onBpmnEvent(payload, "OrderReceived");

        verify(processEngine).triggerMessageStart("proc-1", "OrderReceived", "corr-1", Map.of("x", 1));
        verify(processEngine, never()).getProcessDefinitionIdsByMessageRef(anyString());
    }

    @Test
    void onBpmnEvent_messageStartWithMessageRefOnly_resolvesProcessDefinitionId() {
        when(processEngine.getProcessDefinitionIdsByMessageRef("OrderReceived")).thenReturn(java.util.List.of("proc-1"));

        BpmnEventPayload payload = new BpmnEventPayload("OrderReceived", null, "corr-1", null, null, null, Map.of(), null);

        consumer.onBpmnEvent(payload, "OrderReceived");

        verify(processEngine).getProcessDefinitionIdsByMessageRef("OrderReceived");
        verify(processEngine).triggerMessageStart("proc-1", "OrderReceived", "corr-1", Map.of());
    }

    @Test
    void onBpmnEvent_messageStartWithNullVariables_usesEmptyMap() {
        BpmnEventPayload payload = new BpmnEventPayload("Msg", null, null, "proc-1", null, null, null, null);

        consumer.onBpmnEvent(payload, "Msg");

        verify(processEngine).triggerMessageStart("proc-1", "Msg", null, Map.of());
    }

    @Test
    void onBpmnEvent_catchEventWithInstanceIdAndNodeId_triggersCatchEvent() {
        UUID instanceId = UUID.randomUUID();
        BpmnEventPayload payload = new BpmnEventPayload(null, null, null, null, instanceId, "Catch_1", Map.of("done", true), null);

        consumer.onBpmnEvent(payload, null);

        verify(processEngine).triggerCatchEvent(instanceId, "Catch_1", Map.of("done", true));
    }

    @Test
    void onBpmnEvent_catchByMessageRef_triggersCatchByMessageRef() {
        UUID instanceId = UUID.randomUUID();
        BpmnEventPayload payload = new BpmnEventPayload("Reply", null, "corr-1", null, instanceId, null, Map.of("payload", "ok"), null);

        consumer.onBpmnEvent(payload, "Reply");

        verify(processEngine).triggerCatchEventByMessageRef("Reply", "corr-1", Map.of("payload", "ok"));
    }

    @Test
    void onBpmnEvent_catchBySignalRef_triggersCatchByMessageRef() {
        UUID instanceId = UUID.randomUUID();
        BpmnEventPayload payload = new BpmnEventPayload(null, "SignalA", "corr-1", null, instanceId, null, Map.of(), null);

        consumer.onBpmnEvent(payload, "SignalA");

        verify(processEngine).triggerCatchEventByMessageRef("SignalA", "corr-1", Map.of());
    }
}
