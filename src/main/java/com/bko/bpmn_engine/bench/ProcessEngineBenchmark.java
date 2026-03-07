package com.bko.bpmn_engine.bench;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.parser.BpmnParser;
import org.openjdk.jmh.annotations.*;
import org.springframework.context.ApplicationEventPublisher;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * JMH benchmarks for the BPMN process engine.
 * Target: ≥100 completed process instances per second (10 sequential tasks each).
 */
@State(Scope.Benchmark)
@Fork(value = 1, jvmArgsAppend = {"-XX:+UseZGC", "-XX:MaxGCPauseMillis=1"})
public class ProcessEngineBenchmark {

    private ProcessEngine engine;
    private String benchmarkProcessId;

    @Setup
    public void setup() throws Exception {
        BpmnParser parser = new BpmnParser();
        ApplicationEventPublisher noOpPublisher = event -> {};
        engine = new ProcessEngine(parser, noOpPublisher, null, null, null, null, null);

        String bpmnXml = loadResource("fixtures/sequential_10_tasks.bpmn");
        benchmarkProcessId = engine.deployProcess(bpmnXml);

        TaskWorker counterWorker = vars -> {
            int count = ((Number) vars.getOrDefault("counter", 0)).intValue();
            return Map.of("counter", count + 1);
        };
        engine.registerWorker("java", counterWorker);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(1)
    public ProcessInstance singleThreaded100Instances() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("counter", 0);
        return engine.createInstance(benchmarkProcessId, vars);
    }

    @Benchmark
    @BenchmarkMode(Mode.Throughput)
    @OutputTimeUnit(TimeUnit.SECONDS)
    @Threads(8)
    public ProcessInstance multiThreaded() {
        Map<String, Object> vars = new HashMap<>();
        vars.put("counter", 0);
        return engine.createInstance(benchmarkProcessId, vars);
    }

    private static String loadResource(String path) throws IOException {
        try (InputStream is = ProcessEngineBenchmark.class.getClassLoader().getResourceAsStream(path)) {
            if (is == null) {
                throw new IOException("Resource not found: " + path);
            }
            return new String(is.readAllBytes(), StandardCharsets.UTF_8);
        }
    }
}
