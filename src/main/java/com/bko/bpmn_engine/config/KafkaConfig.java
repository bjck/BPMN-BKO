package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.KafkaTaskExecutor;
import com.bko.bpmn_engine.engine.kafka.BpmnEventPayload;
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
import org.springframework.kafka.support.serializer.JsonDeserializer;
import org.springframework.kafka.support.serializer.JsonSerializer;

import java.util.HashMap;
import java.util.Map;

@Configuration
@ConditionalOnProperty(prefix = "bpmn.kafka", name = "enabled", havingValue = "true")
public class KafkaConfig {

    @Value("${spring.kafka.bootstrap-servers:localhost:9092}")
    private String bootstrapServers;

    @Value("${bpmn.kafka.consumer-group:bpmn-engine}")
    private String consumerGroup;

    @Bean
    public ProducerFactory<String, BpmnEventPayload> bpmnEventProducerFactory() {
        Map<String, Object> props = new HashMap<>();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class);
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, JsonSerializer.class);
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
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, JsonDeserializer.class);
        props.put(JsonDeserializer.TRUSTED_PACKAGES, "com.bko.bpmn_engine.*");
        props.put(JsonDeserializer.VALUE_DEFAULT_TYPE, BpmnEventPayload.class.getName());
        return new DefaultKafkaConsumerFactory<>(props);
    }

    @Bean
    public ConcurrentKafkaListenerContainerFactory<String, BpmnEventPayload> bpmnEventKafkaListenerContainerFactory() {
        ConcurrentKafkaListenerContainerFactory<String, BpmnEventPayload> factory =
                new ConcurrentKafkaListenerContainerFactory<>();
        factory.setConsumerFactory(bpmnEventConsumerFactory());
        return factory;
    }
}
