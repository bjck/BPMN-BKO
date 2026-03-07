package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.CheckpointSink;
import com.bko.bpmn_engine.engine.JpaCheckpointSink;
import com.bko.bpmn_engine.engine.KafkaCheckpointSink;
import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaTemplate;

/**
 * Wires the correct CheckpointSink when persistence is enabled:
 * KafkaCheckpointSink when bpmn.kafka.enabled and bpmn.kafka.checkpoint-enabled are true;
 * otherwise JpaCheckpointSink (direct DB writes).
 */
@Configuration
@Profile("persistence")
public class CheckpointSinkConfig {

    @Bean
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "checkpoint-enabled", havingValue = "true")
    @ConditionalOnBean(name = "checkpointKafkaTemplate")
    public CheckpointSink kafkaCheckpointSink(
            KafkaTemplate<String, CheckpointEventPayload> checkpointKafkaTemplate,
            @Value("${bpmn.kafka.checkpoint-topic:bpmn-checkpoints}") String checkpointTopic) {
        return new KafkaCheckpointSink(checkpointKafkaTemplate, checkpointTopic);
    }

    @Bean
    @ConditionalOnMissingBean(CheckpointSink.class)
    public CheckpointSink jpaCheckpointSink(ProcessInstanceStorage instanceStorage) {
        return new JpaCheckpointSink(instanceStorage);
    }
}
