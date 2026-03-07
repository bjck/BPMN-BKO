package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class BpmnEngineConfig {

    @Bean
    public TaskWorker defaultJavaWorker() {
        return vars -> Map.of();
    }

    /** Increments process variable "counter" by 1. Starts from 0 if not present. */
    @Bean
    public TaskWorker counterWorker() {
        return vars -> {
            int count = ((Number) vars.getOrDefault("counter", 0)).intValue();
            return Map.of("counter", count + 1);
        };
    }

    @Bean
    public WorkerRegistrar workerRegistrar(ProcessEngine engine, TaskWorker defaultJavaWorker, TaskWorker counterWorker) {
        engine.registerWorker("java", defaultJavaWorker);
        engine.registerWorker("counter", counterWorker);
        return new WorkerRegistrar();
    }

    public record WorkerRegistrar() {
    }
}
