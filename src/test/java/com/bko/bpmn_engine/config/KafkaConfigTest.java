package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.KafkaTaskExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests that KafkaConfig beans are created when bpmn.kafka.enabled=true.
 * Checkpoint beans require bpmn.kafka.checkpoint-enabled=true.
 * Note: Requires Kafka broker or embedded Kafka for full context load.
 */
class KafkaConfigTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withPropertyValues(
                    "bpmn.kafka.enabled=true",
                    "spring.kafka.bootstrap-servers=localhost:9092"
            )
            .withUserConfiguration(KafkaConfig.class);

    @Test
    void kafkaEnabled_loadsConfigAndBeans() {
        runner.run(context -> {
            assertThat(context).hasSingleBean(KafkaConfig.class);
            assertThat(context).hasBean("bpmnEventProducerFactory");
            assertThat(context).hasBean("bpmnEventKafkaTemplate");
            assertThat(context).hasBean("bpmnServiceTaskKafkaTemplate");
            assertThat(context).getBean(KafkaTaskExecutor.class).isNotNull();
        });
    }

    @Test
    void checkpointEnabled_loadsCheckpointBeans() {
        runner.withPropertyValues("bpmn.kafka.checkpoint-enabled=true")
                .run(context -> {
                    assertThat(context).hasBean("checkpointProducerFactory");
                    assertThat(context).hasBean("checkpointKafkaTemplate");
                    assertThat(context).hasBean("checkpointKafkaListenerContainerFactory");
                });
    }
}
