package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.Map;

@Configuration
public class BpmnEngineConfig {

    private static final String VAR_COUNTER = "counter";

    @Bean
    public TaskWorker defaultJavaWorker() {
        return vars -> Map.of();
    }

    /** Increments process variable "counter" by 1. Starts from 0 if not present. */
    @Bean
    public TaskWorker counterWorker() {
        return vars -> {
            int count = ((Number) vars.getOrDefault(VAR_COUNTER, 0)).intValue();
            return Map.of(VAR_COUNTER, count + 1);
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

    /** Throws RuntimeException. Used for testing failure and restart flows. */
    @Bean
    public TaskWorker failWorker() {
        return vars -> { throw new RuntimeException("Simulated failure"); };
    }

    @Bean
    public WorkerRegistrar workerRegistrar(ProcessEngine engine, TaskWorker defaultJavaWorker, TaskWorker counterWorker,
                                           TaskWorker validateInvoiceWorker, TaskWorker matchInvoiceWorker,
                                           TaskWorker failWorker) {
        engine.registerWorker("java", defaultJavaWorker);
        engine.registerWorker(VAR_COUNTER, counterWorker);
        engine.registerWorker("validate-invoice", validateInvoiceWorker);
        engine.registerWorker("match-invoice", matchInvoiceWorker);
        engine.registerWorker("fail", failWorker);
        return new WorkerRegistrar();
    }

    /** Marker for worker registration; no methods needed. */
    public record WorkerRegistrar() {
    }
}
