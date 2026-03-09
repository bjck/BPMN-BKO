package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.model.Completed;
import com.bko.bpmn_engine.model.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Tests for PerformanceTestController InterruptedException paths (lines 57-59, 65-68).
 */
class PerformanceTestControllerInterruptTest {

    private static final String PROCESS_DEF_ID = "Process_Minimal";

    @Test
    void runTest_interruptedDuringAwaitTermination_returns500() {
        ProcessEngine engine = mock(ProcessEngine.class);
        when(engine.createInstance(eq(PROCESS_DEF_ID), any())).thenAnswer(inv ->
                new ProcessInstance(UUID.randomUUID(), PROCESS_DEF_ID, new ConcurrentHashMap<>(Map.of()),
                        new Completed(UUID.randomUUID()), Instant.now(), Instant.now()));

        ExecutorService throwingExecutor = new java.util.concurrent.AbstractExecutorService() {
            @Override
            public void shutdown() {
                // noop
            }

            @Override
            public java.util.List<Runnable> shutdownNow() {
                return java.util.Collections.emptyList();
            }

            @Override
            public boolean isShutdown() {
                return true;
            }

            @Override
            public boolean isTerminated() {
                return true;
            }

            @Override
            public boolean awaitTermination(long timeout, TimeUnit unit) throws InterruptedException {
                throw new InterruptedException("simulated");
            }

            @Override
            public void execute(Runnable command) {
                command.run();
            }
        };

        PerformanceTestController controller = new PerformanceTestController(engine, new Semaphore(10),
                () -> throwingExecutor);

        var response = controller.runTest(new PerformanceTestController.PerformanceTestRequest(PROCESS_DEF_ID, 2));

        assertEquals(500, response.getStatusCode().value(), "Interrupted during awaitTermination should return 500");
    }

    @Test
    @Timeout(value = 15, unit = TimeUnit.SECONDS)
    void runTest_interruptedDuringAcquire_handlesException() throws Exception {
        AtomicReference<Thread> blockingThread = new AtomicReference<>();
        CountDownLatch blockedSignal = new CountDownLatch(1);
        Semaphore interruptibleSemaphore = new Semaphore(1) {
            private int acquireCount = 0;

            @Override
            public void acquire() throws InterruptedException {
                acquireCount++;
                if (acquireCount > 1) {
                    blockingThread.set(Thread.currentThread());
                    blockedSignal.countDown();
                }
                super.acquire();
            }
        };

        CountDownLatch blockLatch = new CountDownLatch(1);
        ProcessEngine blockingEngine = mock(ProcessEngine.class);
        when(blockingEngine.createInstance(eq(PROCESS_DEF_ID), any())).thenAnswer(inv -> {
            blockLatch.await();
            return new ProcessInstance(
                    UUID.randomUUID(), PROCESS_DEF_ID, new ConcurrentHashMap<>(Map.of()),
                    new Completed(UUID.randomUUID()), Instant.now(), Instant.now());
        });

        ExecutorService platformExecutor = Executors.newFixedThreadPool(2);
        PerformanceTestController controller = new PerformanceTestController(blockingEngine, interruptibleSemaphore,
                () -> platformExecutor);

        Thread requestThread = new Thread(() -> {
            try {
                controller.runTest(new PerformanceTestController.PerformanceTestRequest(PROCESS_DEF_ID, 2));
            } catch (Throwable ignored) {
            }
        });
        requestThread.start();
        blockedSignal.await();
        Thread.sleep(100);
        Thread t = blockingThread.get();
        if (t != null) {
            t.interrupt();
        }
        blockLatch.countDown();
        requestThread.join(10000);
    }
}
