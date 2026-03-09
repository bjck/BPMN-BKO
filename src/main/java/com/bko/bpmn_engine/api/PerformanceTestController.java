package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.model.Completed;
import com.bko.bpmn_engine.model.ProcessInstance;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

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
    private final Supplier<ExecutorService> executorFactory;

    @Autowired
    public PerformanceTestController(ProcessEngine processEngine,
            @Value("${bpmn.performance.max-concurrency:150}") int maxConcurrency) {
        this(processEngine, new Semaphore(Math.max(1, maxConcurrency)), Executors::newVirtualThreadPerTaskExecutor);
    }

    /** For testing: inject a custom semaphore to simulate InterruptedException paths. */
    PerformanceTestController(ProcessEngine processEngine, Semaphore concurrencyLimiter) {
        this(processEngine, concurrencyLimiter, Executors::newVirtualThreadPerTaskExecutor);
    }

    /** For testing: inject executor factory to simulate InterruptedException during awaitTermination. */
    PerformanceTestController(ProcessEngine processEngine, Semaphore concurrencyLimiter,
            Supplier<ExecutorService> executorFactory) {
        this.processEngine = processEngine;
        this.concurrencyLimiter = concurrencyLimiter;
        this.executorFactory = executorFactory;
    }

    @PostMapping("/performance-test")
    public ResponseEntity<PerformanceTestResponse> runTest(@RequestBody PerformanceTestRequest request) {
        String processId = request.processDefinitionId();
        int count = Math.clamp(request.count(), 1, 100_000);

        long startMs = System.currentTimeMillis();
        AtomicInteger completed = new AtomicInteger(0);

        try (ExecutorService executor = executorFactory.get()) {
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
                        throw new IllegalStateException("Performance test interrupted", e);
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
