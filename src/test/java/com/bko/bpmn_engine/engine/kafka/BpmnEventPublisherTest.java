package com.bko.bpmn_engine.engine.kafka;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for BpmnEventPublisher: verifies key selection and topic configuration.
 */
class BpmnEventPublisherTest {

    private KafkaTemplate<String, BpmnEventPayload> kafkaTemplate;
    private BpmnEventPublisher publisher;

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
    }

    @Test
    void publish_usesMessageRefAsKey_whenPresent() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "bpmn-events");
        BpmnEventPayload payload = BpmnEventPayload.forThrow("OrderReceived", null, null, UUID.randomUUID(), "node-1", Map.of());

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq("bpmn-events"), eq("OrderReceived"), eq(payload));
    }

    @Test
    void publish_usesSignalRefAsKey_whenMessageRefNull() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "bpmn-events");
        BpmnEventPayload payload = BpmnEventPayload.forThrow(null, "SignalA", null, UUID.randomUUID(), "node-1", Map.of());

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq("bpmn-events"), eq("SignalA"), eq(payload));
    }

    @Test
    void publish_usesErrorCodeAsKey_whenMessageRefAndSignalRefNull() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "bpmn-events");
        BpmnEventPayload payload = BpmnEventPayload.forError("ERR_001", null, UUID.randomUUID(), "node-1", Map.of());

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq("bpmn-events"), eq("ERR_001"), eq(payload));
    }

    @Test
    void publish_usesInstanceIdAsKey_whenOnlyInstanceIdPresent() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "bpmn-events");
        UUID instanceId = UUID.randomUUID();
        BpmnEventPayload payload = new BpmnEventPayload(null, null, null, null, instanceId, "node-1", Map.of(), null);

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq("bpmn-events"), eq(instanceId.toString()), eq(payload));
    }

    @Test
    void publish_usesBpmnEventAsKey_whenNoRefsOrInstanceId() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "bpmn-events");
        BpmnEventPayload payload = new BpmnEventPayload(null, null, null, null, null, null, Map.of(), null);

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq("bpmn-events"), eq("bpmn-event"), eq(payload));
    }

    @Test
    void publish_usesDefaultTopic_whenTopicBlank() {
        publisher = new BpmnEventPublisher(kafkaTemplate, "  ");
        BpmnEventPayload payload = BpmnEventPayload.forThrow("Msg", null, null, UUID.randomUUID(), "n", Map.of());

        when(kafkaTemplate.send(anyString(), anyString(), any(BpmnEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        publisher.publish(payload);

        verify(kafkaTemplate).send(eq(BpmnEventPublisher.DEFAULT_TOPIC), anyString(), any(BpmnEventPayload.class));
    }
}
