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

    /** Validates invoice fields. Expects: invoiceId, amount. Sets: valid. */
    @Bean
    public TaskWorker validateInvoiceWorker() {
        return vars -> {
            String invoiceId = (String) vars.getOrDefault("invoiceId", "");
            double amount = ((Number) vars.getOrDefault("amount", 0)).doubleValue();
            return Map.of("valid", !invoiceId.isBlank() && amount > 0);
        };
    }

    /** Matches invoice amount to PO. Sets: matched. */
    @Bean
    public TaskWorker matchInvoiceWorker() {
        return vars -> Map.of("matched", true);
    }

    @Bean
    public WorkerRegistrar workerRegistrar(ProcessEngine engine, TaskWorker defaultJavaWorker, TaskWorker counterWorker,
                                           TaskWorker validateInvoiceWorker, TaskWorker matchInvoiceWorker) {
        engine.registerWorker("java", defaultJavaWorker);
        engine.registerWorker("counter", counterWorker);
        engine.registerWorker("validate-invoice", validateInvoiceWorker);
        engine.registerWorker("match-invoice", matchInvoiceWorker);
        return new WorkerRegistrar();
    }

    public record WorkerRegistrar() {
    }
}
