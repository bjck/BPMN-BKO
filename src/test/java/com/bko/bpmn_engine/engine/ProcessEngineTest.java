package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.engine.event.*;
import com.bko.bpmn_engine.model.*;
import com.bko.bpmn_engine.parser.BpmnParser;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.context.ApplicationEventPublisher;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class ProcessEngineTest {

    private ProcessEngine engine;
    private BpmnParser parser;
    private Path fixturesDir;

    @BeforeEach
    void setUp() throws Exception {
        parser = new BpmnParser();
        engine = new ProcessEngine(parser, new NoOpEventPublisher(), null, null, null, null, null, null);
        fixturesDir = Path.of(getClass().getResource("/fixtures").toURI());
    }

    private String loadFixture(String name) throws Exception {
        return Files.readString(fixturesDir.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void deployAndCreateInstance_reachesEndEventAutomatically_forMinimalBpmn() throws Exception {
        String xml = loadFixture("minimal.bpmn");
        String defId = engine.deployProcess(xml);
        assertEquals("Process_Minimal", defId);

        engine.registerWorker("java", vars -> Map.of());

        ProcessInstance instance = engine.createInstance(defId, Map.of());

        assertNotNull(instance);
        assertTrue(instance.state() instanceof Completed, "Instance should be completed: " + instance.state());
        assertEquals("Process_Minimal", instance.processDefinitionId());
        ProcessInstance retrieved = engine.getInstance(instance.instanceId());
        assertNotNull(retrieved);
        assertTrue(retrieved.state() instanceof Completed);
    }

    @Test
    void sequentialChainOf10Tasks_executesOnSameThread() throws Exception {
        String xml = loadFixture("sequential_10_tasks.bpmn");
        String defId = engine.deployProcess(xml);

        Thread executionThread = Thread.currentThread();
        List<Thread> threads = new ArrayList<>();

        engine.registerWorker("java", vars -> {
            threads.add(Thread.currentThread());
            return Map.of();
        });

        ProcessInstance instance = engine.createInstance(defId, Map.of());

        assertTrue(instance.state() instanceof Completed);
        assertEquals(10, threads.size(), "All 10 tasks should have executed");
        for (Thread t : threads) {
            assertSame(executionThread, t, "Each task should run on the same thread");
        }
    }

    @Test
    void variableMutationsFromWorkers_visibleAfterChainCompletes() throws Exception {
        String xml = loadFixture("sequential_10_tasks.bpmn");
        String defId = engine.deployProcess(xml);

        engine.registerWorker("java", vars -> {
            int count = (int) vars.getOrDefault("count", 0);
            return Map.of("count", count + 1);
        });

        ProcessInstance instance = engine.createInstance(defId, Map.of("count", 0));

        assertTrue(instance.state() instanceof Completed);
        assertEquals(10, instance.variables().get("count"));
    }

    @Test
    void exclusiveGateway_routesToCorrectBranch_basedOnCondition() throws Exception {
        String xml = loadFixture("exclusive_gateway.bpmn");
        List<String> yesBranchTasks = new ArrayList<>();
        List<String> noBranchTasks = new ArrayList<>();
        List<String> defaultBranchTasks = new ArrayList<>();

        EventCollector yesCollector = new EventCollector(yesBranchTasks);
        EventCollector noCollector = new EventCollector(noBranchTasks);
        EventCollector defaultCollector = new EventCollector(defaultBranchTasks);

        ProcessEngine yesEngine = new ProcessEngine(parser, yesCollector, null, null, null, null, null, null);
        ProcessEngine noEngine = new ProcessEngine(parser, noCollector, null, null, null, null, null, null);
        ProcessEngine defaultEngine = new ProcessEngine(parser, defaultCollector, null, null, null, null, null, null);

        String defId = yesEngine.deployProcess(xml);
        noEngine.deployProcess(xml);
        defaultEngine.deployProcess(xml);

        yesEngine.registerWorker("java", vars -> Map.of());
        noEngine.registerWorker("java", vars -> Map.of());
        defaultEngine.registerWorker("java", vars -> Map.of());

        ProcessInstance yesInstance = yesEngine.createInstance(defId, Map.of("flag", true));
        assertTrue(yesInstance.state() instanceof Completed);
        assertTrue(yesBranchTasks.contains("Task_yes"), "flag=true should route to Task_yes, got: " + yesBranchTasks);

        ProcessInstance noInstance = noEngine.createInstance(defId, Map.of("flag", false));
        assertTrue(noInstance.state() instanceof Completed);
        assertTrue(noBranchTasks.contains("Task_no"), "flag=false should route to Task_no, got: " + noBranchTasks);

        ProcessInstance defaultInstance = defaultEngine.createInstance(defId, Map.of("flag", "other"));
        assertTrue(defaultInstance.state() instanceof Completed);
        assertFalse(defaultBranchTasks.contains("Task_yes") && defaultBranchTasks.contains("Task_no"),
                "default should skip both branches, got: " + defaultBranchTasks);
    }

    @Test
    void parallelGateway_firesOnlyAfterBothBranchesComplete() throws Exception {
        String xml = loadFixture("parallel_gateway.bpmn");
        String defId = engine.deployProcess(xml);

        List<String> activatedTaskIds = new ArrayList<>();
        EventCollector collector = new EventCollector(activatedTaskIds);
        ProcessEngine parallelEngine = new ProcessEngine(parser, collector, null, null, null, null, null, null);

        parallelEngine.deployProcess(xml);
        parallelEngine.registerWorker("java", vars -> Map.of());

        ProcessInstance instance = parallelEngine.createInstance(defId, Map.of());

        assertTrue(instance.state() instanceof Completed);
        assertTrue(activatedTaskIds.contains("Task_A"), "Task_A should have been executed");
        assertTrue(activatedTaskIds.contains("Task_B"), "Task_B should have been executed");
    }

    @Test
    void restServiceTask_executesBuiltInHttpCall_andStoresResponseVariable() throws Exception {
        AtomicReference<String> authHeader = new AtomicReference<>();
        AtomicReference<String> requestBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
        server.createContext("/orders", exchange -> respondWithJson(exchange, authHeader, requestBody));
        server.start();

        try {
            int port = server.getAddress().getPort();
            String xml = """
                    <?xml version="1.0" encoding="UTF-8"?>
                    <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                      xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                      <bpmn:process id="Rest_Process" name="REST Process">
                        <bpmn:startEvent id="Start">
                          <bpmn:outgoing>Flow_1</bpmn:outgoing>
                        </bpmn:startEvent>
                        <bpmn:serviceTask id="Call_Rest" name="Call REST" implementation="rest">
                          <bpmn:incoming>Flow_1</bpmn:incoming>
                          <bpmn:outgoing>Flow_2</bpmn:outgoing>
                          <bpmn:extensionElements>
                            <engine:taskConfiguration type="rest"
                                                      method="POST"
                                                      url="= &quot;http://localhost:%d/orders&quot;"
                                                      authenticationType="bearer"
                                                      bearerToken="= authToken"
                                                      headers="= { Accept: &quot;application/json&quot; }"
                                                      body="= { orderId: orderId }"
                                                      resultVariable="apiResult"
                                                      timeoutSeconds="5"/>
                          </bpmn:extensionElements>
                        </bpmn:serviceTask>
                        <bpmn:endEvent id="End">
                          <bpmn:incoming>Flow_2</bpmn:incoming>
                        </bpmn:endEvent>
                        <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Call_Rest"/>
                        <bpmn:sequenceFlow id="Flow_2" sourceRef="Call_Rest" targetRef="End"/>
                      </bpmn:process>
                    </bpmn:definitions>
                    """.formatted(port);

            String defId = engine.deployProcess(xml);
            ProcessInstance instance = engine.createInstance(defId, Map.of("orderId", 42, "authToken", "secret-token"));

            assertTrue(instance.state() instanceof Completed);
            assertEquals("Bearer secret-token", authHeader.get());
            assertTrue(requestBody.get().contains("\"orderId\""));
            Object response = instance.variables().get("apiResult");
            assertInstanceOf(Map.class, response);
            assertEquals(201, ((Map<?, ?>) response).get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void exclusiveGateway_withFeelCondition_routesCorrectly() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="Feel_Process" name="FEEL Process">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:exclusiveGateway id="Gateway_1">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_yes</bpmn:outgoing>
                      <bpmn:outgoing>Flow_no</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:serviceTask id="Task_yes" name="Yes Branch" implementation="java">
                      <bpmn:incoming>Flow_yes</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:serviceTask id="Task_no" name="No Branch" implementation="java">
                      <bpmn:incoming>Flow_no</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End_yes"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:endEvent id="End_no"><bpmn:incoming>Flow_3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Gateway_1"/>
                    <bpmn:sequenceFlow id="Flow_yes" sourceRef="Gateway_1" targetRef="Task_yes">
                      <bpmn:conditionExpression language="feel">= approved = true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_no" sourceRef="Gateway_1" targetRef="Task_no">
                      <bpmn:conditionExpression language="feel">= approved = false</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_yes" targetRef="End_yes"/>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Task_no" targetRef="End_no"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        List<String> taskIds = new ArrayList<>();
        ProcessEngine feelEngine = new ProcessEngine(parser, new EventCollector(taskIds), null, null, null, null, null, null);
        String defId = feelEngine.deployProcess(xml);
        feelEngine.registerWorker("java", vars -> Map.of());

        ProcessInstance instance = feelEngine.createInstance(defId, Map.of("approved", true));

        assertTrue(instance.state() instanceof Completed);
        assertTrue(taskIds.contains("Task_yes"));
        assertFalse(taskIds.contains("Task_no"));
    }

    @Test
    void beanServiceTask_executesApplicationLogic_withFeelInputs() throws Exception {
        ProcessEngine beanEngine = new ProcessEngine(
                parser,
                new NoOpEventPublisher(),
                null,
                null,
                new ServiceTaskLogicRegistry(Map.of(
                        "testLogic", new ServiceTaskLogic() {
                            @Override
                            public String displayName() {
                                return "Test Logic";
                            }

                            @Override
                            public Object execute(ServiceTaskExecutionContext context) {
                                int counter = ((Number) context.variables().getOrDefault("counter", 0)).intValue();
                                int step = ((Number) context.inputs().getOrDefault("step", 1)).intValue();
                                return Map.of(
                                        "counter", counter + step,
                                        "beanSeen", context.inputs().get("customerId")
                                );
                            }
                        }
                )),
                null,
                null,
                null
        );

        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Bean_Process" name="Bean Process">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="Call_Bean" name="Call Bean" implementation="bean">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:extensionElements>
                        <engine:taskConfiguration type="bean"
                                                  beanName="testLogic"
                                                  inputMapping="= { step: incrementBy, customerId: customerId }"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Call_Bean"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Call_Bean" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        String defId = beanEngine.deployProcess(xml);
        ProcessInstance instance = beanEngine.createInstance(defId, Map.of("counter", 2, "incrementBy", 3, "customerId", "C-42"));

        assertTrue(instance.state() instanceof Completed);
        assertEquals(5, instance.variables().get("counter"));
        assertEquals("C-42", instance.variables().get("beanSeen"));
    }

    @Test
    void allGatewaysE2E_exclusiveParallelInclusiveComplexEventBased_completesSuccessfully() throws Exception {
        String xml = loadFixture("all_gateways.bpmn");
        String defId = engine.deployProcess(xml);
        engine.registerWorker("java", vars -> Map.of());

        // XOR: take yes branch; Inclusive: take default only (one branch); Complex: take Flow_complex_1; Event-based: take OK
        Map<String, Object> variables = Map.<String, Object>of(
                "useXorYes", true,
                "chosenFlow", "Flow_complex_1",
                "useEvOk", true
        );

        ProcessInstance instance = engine.createInstance(defId, variables);

        assertNotNull(instance, "Instance should be created");
        assertTrue(instance.state() instanceof Completed,
                "Instance should complete: " + instance.state());
        assertEquals("Process_AllGateways", instance.processDefinitionId());
    }

    @Test
    void messageStartEvent_registersProcessDefinitionByMessageRef_andTriggerMessageStartRunsProcess() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_MessageStart" name="Message Start">
                    <bpmn:startEvent id="Start_1" engine:messageRef="OrderReceived">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Task_1" name="Handle" implementation="java">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        String defId = engine.deployProcess(xml);
        engine.registerWorker("java", vars -> Map.of("handled", true));

        assertEquals(List.of(defId), engine.getProcessDefinitionIdsByMessageRef("OrderReceived"));

        ProcessInstance instance = engine.triggerMessageStart(defId, "OrderReceived", "corr-1", Map.of("orderId", "O1"));

        assertNotNull(instance);
        assertTrue(instance.state() instanceof Completed);
        assertEquals(Boolean.TRUE, instance.variables().get("handled"));
    }

    @Test
    void intermediateMessageCatchEvent_waitsUntilTriggerCatchEvent_thenAdvances() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_Catch" name="Catch">
                    <bpmn:startEvent id="Start_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Task_1" name="Before" implementation="java">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:intermediateCatchEvent id="Catch_1" engine:messageRef="Reply">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:serviceTask id="Task_2" name="After" implementation="java">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                      <bpmn:outgoing>Flow_4</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End_1">
                      <bpmn:incoming>Flow_4</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="Catch_1"/>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Catch_1" targetRef="Task_2"/>
                    <bpmn:sequenceFlow id="Flow_4" sourceRef="Task_2" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        List<String> activated = new ArrayList<>();
        EventCollector collector = new EventCollector(activated);
        ProcessEngine catchEngine = new ProcessEngine(parser, collector, null, null, null, null, null, null);
        catchEngine.deployProcess(xml);
        catchEngine.registerWorker("java", vars -> {
            activated.add(Thread.currentThread().getName());
            return Map.of();
        });

        ProcessInstance instance = catchEngine.createInstance("Process_Catch", Map.of());
        assertTrue(instance.state() instanceof Active);
        assertTrue(activated.contains("Task_1"), "Task_1 (Before) should have run; got " + activated);
        assertFalse(activated.contains("Task_2"), "Task_2 (After) should not run until catch is triggered");

        catchEngine.triggerCatchEvent(instance.instanceId(), "Catch_1", Map.of("payload", "done"));

        ProcessInstance after = catchEngine.getInstance(instance.instanceId());
        assertNotNull(after);
        assertTrue(after.state() instanceof Completed);
        assertTrue(activated.contains("Task_2"), "Task_2 (After) should run after catch; got " + activated);
    }

    @Test
    void intermediateThrowEvent_advancesWithoutPublisher() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_Throw" name="Throw">
                    <bpmn:startEvent id="Start_1">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:intermediateThrowEvent id="Throw_1" engine:messageRef="Outbound">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:intermediateThrowEvent>
                    <bpmn:endEvent id="End_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Throw_1"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Throw_1" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        engine.deployProcess(xml);
        ProcessInstance instance = engine.createInstance("Process_Throw", Map.of());
        assertNotNull(instance);
        assertTrue(instance.state() instanceof Completed);
    }

    private static void respondWithJson(HttpExchange exchange, AtomicReference<String> authHeader, AtomicReference<String> requestBody) throws java.io.IOException {
        authHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
        requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
        byte[] responseBytes = "{\"ok\":true,\"orderStatus\":\"created\"}".getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(201, responseBytes.length);
        exchange.getResponseBody().write(responseBytes);
        exchange.close();
    }

    private static final class EventCollector implements ApplicationEventPublisher {
        private final List<String> taskIds;

        EventCollector(List<String> taskIds) {
            this.taskIds = taskIds;
        }

        @Override
        public void publishEvent(Object event) {
            if (event instanceof TaskActivatedEvent e) {
                taskIds.add(e.taskId());
            }
        }
    }

    private static final class NoOpEventPublisher implements ApplicationEventPublisher {
        @Override
        public void publishEvent(Object event) {
        }
    }
}
