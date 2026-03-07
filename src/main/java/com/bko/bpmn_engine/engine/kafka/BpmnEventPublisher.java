package com.bko.bpmn_engine.engine.kafka;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.stereotype.Component;

import java.util.concurrent.CompletableFuture;

/**
 * Publishes BPMN throw/catch events (message, signal, error) to Kafka.
 * Topic and key are configurable; key defaults to messageRef or signalRef for correlation.
 */
@Component
@ConditionalOnBean(name = "bpmnEventKafkaTemplate")
public class BpmnEventPublisher {

    private static final Logger log = LoggerFactory.getLogger(BpmnEventPublisher.class);

    public static final String DEFAULT_TOPIC = "bpmn-events";

    private final KafkaTemplate<String, BpmnEventPayload> kafkaTemplate;
    private final String topic;

    @Autowired
    public BpmnEventPublisher(@Qualifier("bpmnEventKafkaTemplate") KafkaTemplate<String, BpmnEventPayload> kafkaTemplate,
                              @Value("${bpmn.kafka.events-topic:bpmn-events}") String topic) {
        this.kafkaTemplate = kafkaTemplate;
        this.topic = topic != null && !topic.isBlank() ? topic : DEFAULT_TOPIC;
    }

    /**
     * Send a BPMN event (message throw, signal throw, message end, error end) to Kafka.
     */
    public CompletableFuture<SendResult<String, BpmnEventPayload>> publish(BpmnEventPayload payload) {
        String key = payload.messageRef() != null ? payload.messageRef()
                : payload.signalRef() != null ? payload.signalRef()
                : payload.errorCode() != null ? payload.errorCode()
                : payload.instanceId() != null ? payload.instanceId().toString() : "bpmn-event";
        log.debug("Publishing BPMN event to {} key={} payload={}", topic, key, payload);
        return kafkaTemplate.send(topic, key, payload);
    }
}
