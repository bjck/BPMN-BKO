package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.KafkaTaskExecutor;
import com.bko.bpmn_engine.engine.kafka.BpmnEventPayload;
import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory;
import org.springframework.kafka.core.ConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaConsumerFactory;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.serializer.JacksonJsonDeserializer;
import org.springframework.kafka.support.serializer.JacksonJsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "bpmn.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${bpmn.kafka.consumer-group:bpmn-engine}")
    private String consumerGroup;

    @Value("${bpmn.kafka.checkpoint-consumer-group:bpmn-engine-checkpoint-consumer}")
    private String checkpointConsumerGroup;

    @Value("${bpmn.kafka.checkpoint-consumer-auto-offset-reset:latest}")
    private String checkpointConsumerAutoOffsetReset;

    @Bean
    public ProducerFactory<String, BpmnEventPayload> bpmnEventProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean
    public KafkaTemplate<String, BpmnEventPayload> bpmnEventKafkaTemplate() {
        return new KafkaTemplate<>(bpmnEventProducerFactory());
    }

    @Bean
    public ProducerFactory<String, String> bpmnServiceTaskProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        // Fail fast if broker is unreachable instead of blocking up to 60s (default max.block.ms)
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = "bpmnServiceTaskKafkaTemplate")
    public KafkaTemplate<String, String> bpmnServiceTaskKafkaTemplate() {
        return new KafkaTemplate<>(bpmnServiceTaskProducerFactory());
    }

    @Bean
    public KafkaTaskExecutor kafkaTaskExecutor() {
        return new KafkaTaskExecutor(bpmnServiceTaskKafkaTemplate());
    }

    @Bean
    public ConsumerFactory<String, BpmnEventPayload> bpmnEventConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, consumerGroup);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props, () -> new StringDeserializer(),
                () -> new JacksonJsonDeserializer<>(BpmnEventPayload.class).trustedPackages("com.bko.bpmn_engine.*"));
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BpmnEventPayload> bpmnEventKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, BpmnEventPayload> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bpmnEventConsumerFactory());
        return factory;
    }

    /** Checkpoint producer: only when checkpoint-via-Kafka is enabled. acks=all, fail-fast timeouts. */
    @Bean
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "checkpoint-enabled", havingValue = "true")
    public ProducerFactory<String, CheckpointEventPayload> checkpointProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JacksonJsonSerializer.class);
        props.put(ProducerConfig.ACKS_CONFIG, "all");
        props.put(ProducerConfig.MAX_BLOCK_MS_CONFIG, 5000);
        props.put(ProducerConfig.REQUEST_TIMEOUT_MS_CONFIG, 10000);
        props.put(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, 15000);
        // Throughput: LZ4 reduces payload size and network I/O with low CPU cost; batching coalesces back-to-back checkpoints (e.g. 10 per chain).
        props.put(ProducerConfig.COMPRESSION_TYPE_CONFIG, "lz4");
        props.put(ProducerConfig.LINGER_MS_CONFIG, 2);
        props.put(ProducerConfig.BATCH_SIZE_CONFIG, 32768);
        return new DefaultKafkaProducerFactory<>(props);
    }

    @Bean(name = "checkpointKafkaTemplate")
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "checkpoint-enabled", havingValue = "true")
    public KafkaTemplate<String, CheckpointEventPayload> checkpointKafkaTemplate() {
        return new KafkaTemplate<>(checkpointProducerFactory());
    }

    /** Consumer for checkpoint topic: separate group, deserializes CheckpointEventPayload. */
    @Bean
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "checkpoint-enabled", havingValue = "true")
    public ConsumerFactory<String, CheckpointEventPayload> checkpointConsumerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.GROUP_ID_CONFIG, checkpointConsumerGroup);
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, checkpointConsumerAutoOffsetReset);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        return new DefaultKafkaConsumerFactory<>(props, () -> new StringDeserializer(),
                () -> new JacksonJsonDeserializer<>(CheckpointEventPayload.class).trustedPackages("com.bko.bpmn_engine.*"));
    }

    @Bean(name = "checkpointKafkaListenerContainerFactory")
    @ConditionalOnProperty(prefix = "bpmn.kafka", name = "checkpoint-enabled", havingValue = "true")
    public ConcurrentKafkaListenerContainerFactory<String, CheckpointEventPayload> checkpointKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, CheckpointEventPayload> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(checkpointConsumerFactory());
        return factory;
    }
}
