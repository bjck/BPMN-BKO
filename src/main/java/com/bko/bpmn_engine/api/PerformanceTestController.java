package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.model.Completed;
import com.bko.bpmn_engine.model.ProcessInstance;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * REST API for running performance tests.
 * Uses a semaphore to limit concurrent process executions, preventing HikariCP connection pool exhaustion
 * when virtual threads spawn thousands of tasks. Virtual threads block on the semaphore (cheap yield)
 * until a permit is available.
 */
@RestController
@RequestMapping("/v1")
public class PerformanceTestController {

    private final ProcessEngine processEngine;
    private final Semaphore concurrencyLimiter;

    public PerformanceTestController(ProcessEngine processEngine,
            @Value("${bpmn.performance.max-concurrency:150}") int maxConcurrency) {
        this.processEngine = processEngine;
        this.concurrencyLimiter = new Semaphore(Math.max(1, maxConcurrency));
    }

    @PostMapping("/performance-test")
    public ResponseEntity<PerformanceTestResponse> runTest(@RequestBody PerformanceTestRequest request) {
        String processId = request.processDefinitionId();
        int count = Math.min(Math.max(request.count(), 1), 100_000);

        long startMs = System.currentTimeMillis();
        AtomicInteger completed = new AtomicInteger(0);

        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < count; i++) {
                executor.submit(() -> {
                    try {
                        concurrencyLimiter.acquire();
                        try {
                            ProcessInstance instance = processEngine.createInstance(processId, Map.of("counter", 0));
                            if (instance.state() instanceof Completed) {
                                completed.incrementAndGet();
                            }
                        } finally {
                            concurrencyLimiter.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new RuntimeException(e);
                    }
                });
            }
            executor.shutdown();
            executor.awaitTermination(5, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ResponseEntity.status(500).build();
        }

        long durationMs = System.currentTimeMillis() - startMs;
        double perSecond = durationMs > 0 ? (completed.get() * 1000.0) / durationMs : 0;

        return ResponseEntity.ok(new PerformanceTestResponse(
                count,
                completed.get(),
                durationMs,
                Math.round(perSecond * 100) / 100.0
        ));
    }

    public record PerformanceTestRequest(String processDefinitionId, int count) {}

    public record PerformanceTestResponse(int requested, int completed, long durationMs, double instancesPerSecond) {}
}
