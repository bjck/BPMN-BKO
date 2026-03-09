package com.bko.bpmn_engine;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import com.bko.bpmn_engine.parser.BpmnParser;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Quick sanity check: 10,000 process instances using virtual threads.
 * Run before k6 load tests. Prints: "Completed X instances in Yms = Z PI/s"
 */
public class LoadTest {

    @Test
    void loadTest_completesInstances() throws Exception {
        BpmnParser parser = new BpmnParser();
        ApplicationEventPublisher noOpPublisher = event -> {};
        ProcessEngine engine = new ProcessEngine(parser, noOpPublisher, null, null, null, null, null, null);

        Path fixturesDir = Path.of(LoadTest.class.getResource("/fixtures").toURI());
        String bpmnXml = Files.readString(fixturesDir.resolve("sequential_10_tasks.bpmn"), StandardCharsets.UTF_8);
        String processId = engine.deployProcess(bpmnXml);

        TaskWorker counterWorker = vars -> {
            int count = ((Number) vars.getOrDefault("counter", 0)).intValue();
            return Map.of("counter", count + 1);
        };
        engine.registerWorker("java", counterWorker);

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future[10];
            for (int i = 0; i < 10; i++) {
                futures[i] = executor.submit(() -> {
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("counter", 0);
                    return engine.createInstance(processId, vars);
                });
            }
            for (Future<?> f : futures) {
                assertThat(f.get()).isNotNull();
            }
        }
    }

    public static void main(String[] args) throws Exception {
        BpmnParser parser = new BpmnParser();
        ApplicationEventPublisher noOpPublisher = event -> {};
        ProcessEngine engine = new ProcessEngine(parser, noOpPublisher, null, null, null, null, null, null);

        Path fixturesDir = Path.of(LoadTest.class.getResource("/fixtures").toURI());
        String bpmnXml = Files.readString(fixturesDir.resolve("sequential_10_tasks.bpmn"), StandardCharsets.UTF_8);
        String processId = engine.deployProcess(bpmnXml);

        TaskWorker counterWorker = vars -> {
            int count = ((Number) vars.getOrDefault("counter", 0)).intValue();
            return Map.of("counter", count + 1);
        };
        engine.registerWorker("java", counterWorker);

        int instanceCount = 10_000;
        long start = System.currentTimeMillis();

        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            Future<?>[] futures = new Future[instanceCount];
            for (int i = 0; i < instanceCount; i++) {
                futures[i] = executor.submit(() -> {
                    Map<String, Object> vars = new HashMap<>();
                    vars.put("counter", 0);
                    return engine.createInstance(processId, vars);
                });
            }
            for (Future<?> f : futures) {
                f.get();
            }
        }

        long elapsedMs = System.currentTimeMillis() - start;
        double piPerSec = instanceCount * 1000.0 / elapsedMs;
        System.out.printf("Completed %d instances in %dms = %.1f PI/s%n", instanceCount, elapsedMs, piPerSec);
    }
}
