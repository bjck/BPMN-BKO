package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.KafkaTaskConfiguration;
import com.bko.bpmn_engine.model.ServiceTask;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.kafka.core.KafkaTemplate;

import java.util.Map;

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

        if (key != null) {
            kafkaTemplate.send(topic, key, body);
        } else {
            kafkaTemplate.send(topic, body);
        }

        return Map.of("sent", true, "topic", topic);
    }
}
