package com.bko.bpmn_engine.config;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import com.bko.bpmn_engine.parser.BpmnParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Tests for BpmnEngineConfig worker beans and registration.
 */
class BpmnEngineConfigTest {

    private ProcessEngine engine;
    private BpmnEngineConfig config;

    @BeforeEach
    void setUp() {
        BpmnParser parser = new BpmnParser();
        ApplicationEventPublisher noOp = event -> {};
        engine = new ProcessEngine(parser, noOp, null, null, null, null, null, null);
        config = new BpmnEngineConfig();
    }

    @Test
    void counterWorker_incrementsCounterVariable() {
        TaskWorker worker = config.counterWorker();
        Map<String, Object> result = worker.execute(Map.of("counter", 5));
        assertEquals(6, result.get("counter"));
    }

    @Test
    void counterWorker_startsFromZeroWhenNotPresent() {
        TaskWorker worker = config.counterWorker();
        Map<String, Object> result = worker.execute(Map.of());
        assertEquals(1, result.get("counter"));
    }

    @Test
    void validateInvoiceWorker_setsValidTrue_whenInvoiceIdAndAmountPresent() {
        TaskWorker worker = config.validateInvoiceWorker();
        Map<String, Object> result = worker.execute(Map.of("invoiceId", "INV-1", "amount", 100.0));
        assertTrue((Boolean) result.get("valid"));
    }

    @Test
    void validateInvoiceWorker_setsValidFalse_whenInvoiceIdBlank() {
        TaskWorker worker = config.validateInvoiceWorker();
        Map<String, Object> result = worker.execute(Map.of("invoiceId", "", "amount", 100.0));
        assertFalse((Boolean) result.get("valid"));
    }

    @Test
    void matchInvoiceWorker_returnsMatchedTrue() {
        TaskWorker worker = config.matchInvoiceWorker();
        Map<String, Object> result = worker.execute(Map.of());
        assertTrue((Boolean) result.get("matched"));
    }

    @Test
    void defaultJavaWorker_returnsEmptyMap() {
        TaskWorker worker = config.defaultJavaWorker();
        Map<String, Object> result = worker.execute(Map.of("x", 1));
        assertTrue(result.isEmpty());
    }

    @Test
    void workerRegistrar_registersWorkersToEngine() throws Exception {
        config.workerRegistrar(engine, config.defaultJavaWorker(), config.counterWorker(),
                config.validateInvoiceWorker(), config.matchInvoiceWorker(), config.failWorker());

        String bpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="P1" name="Test">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="T1" name="Count" implementation="counter">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="T1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="T1" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        String defId = engine.deployProcess(bpmn);
        var instance = engine.createInstance(defId, Map.of("counter", 0));
        assertNotNull(instance);
        assertEquals(1, instance.variables().get("counter"));
    }
}
