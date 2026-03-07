package com.bko.bpmn_engine.model;

/**
 * Configuration for a Kafka service task: topic, and mapping from process variables to message payload (and optional key).
 */
public record KafkaTaskConfiguration(
        String topic,
        String messageMapping,
        String keyMapping,
        String resultVariable
) {
}
