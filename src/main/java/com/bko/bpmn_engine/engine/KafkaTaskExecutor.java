package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.KafkaTaskConfiguration;
import com.bko.bpmn_engine.model.ServiceTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Executes a Kafka service task: maps process variables to a message (via messageMapping expression),
 * optionally sets the message key (keyMapping), and sends to the configured topic.
 */
public final class KafkaTaskExecutor {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final KafkaTemplate<String, String> kafkaTemplate;

    public KafkaTaskExecutor(KafkaTemplate<String, String> kafkaTemplate) {
        this.kafkaTemplate = kafkaTemplate;
    }

    Object execute(ServiceTask task, Map<String, Object> variables) {
        KafkaTaskConfiguration config = task.kafkaConfiguration();
        if (config == null || config.topic() == null || config.topic().isBlank()) {
            throw new IllegalArgumentException("Kafka task is missing topic configuration: " + task.id());
        }

        String topic = ConditionEvaluator.resolveString(config.topic(), variables);
        if (topic == null || topic.isBlank()) {
            throw new IllegalArgumentException("Kafka task topic resolved to empty for task: " + task.id());
        }

        Object payload = config.messageMapping() != null && !config.messageMapping().isBlank()
                ? ConditionEvaluator.resolveMap(config.messageMapping(), variables)
                : Map.copyOf(variables);
        if (payload == null) {
            payload = Map.of();
        }
        if (!(payload instanceof Map<?, ?>)) {
            payload = Map.of("value", payload);
        }

        String body;
        try {
            body = OBJECT_MAPPER.writeValueAsString(payload);
        } catch (Exception e) {
            throw new IllegalArgumentException("Kafka task messageMapping did not serialize to JSON: " + task.id(), e);
        }

        String key = null;
        if (config.keyMapping() != null && !config.keyMapping().isBlank()) {
            Object keyObj = ConditionEvaluator.resolveValue(config.keyMapping(), variables);
            if (keyObj != null) {
                key = String.valueOf(keyObj);
            }
        }

        try {
            SendResult<String, String> result = key != null
                    ? kafkaTemplate.send(topic, key, body).get(10, TimeUnit.SECONDS)
                    : kafkaTemplate.send(topic, body).get(10, TimeUnit.SECONDS);
            return Map.of("sent", true, "topic", topic, "partition", result.getRecordMetadata().partition(), "offset", result.getRecordMetadata().offset());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Kafka send interrupted for task " + task.id(), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new IllegalStateException("Kafka send failed for task " + task.id() + ": " + cause.getMessage(), cause);
        } catch (TimeoutException e) {
            throw new IllegalStateException("Kafka send timed out for task " + task.id() + " (topic: " + topic + "). Check broker is reachable.", e);
        }
    }
}
