package com.bko.bpmn_engine.parser;

import com.bko.bpmn_engine.model.*;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class BpmnParserTest {

    private BpmnParser parser;
    private Path fixturesDir;

    @BeforeEach
    void setUp() throws Exception {
        parser = new BpmnParser();
        fixturesDir = Path.of(getClass().getResource("/fixtures").toURI());
    }

    private String loadFixture(String name) throws Exception {
        return Files.readString(fixturesDir.resolve(name), StandardCharsets.UTF_8);
    }

    @Test
    void parseMinimalBpmn_assert3Nodes2Flows() throws Exception {
        String xml = loadFixture("minimal.bpmn");
        CompiledProcess compiled = parser.parse(xml);

        assertEquals(3, compiled.definition().nodes().size(), "Should have 3 nodes: start, serviceTask, end");
        assertEquals(2, compiled.definition().sequenceFlows().size(), "Should have 2 sequence flows");

        assertTrue(compiled.definition().nodes().containsKey("StartEvent_1"));
        assertTrue(compiled.definition().nodes().containsKey("Task_1"));
        assertTrue(compiled.definition().nodes().containsKey("EndEvent_1"));

        assertEquals("StartEvent_1", compiled.definition().startNodeId());
        assertEquals(List.of("EndEvent_1"), compiled.definition().endNodeIds());
    }

    @Test
    void parseSequential10Tasks_assert1SequentialChainOf10() throws Exception {
        String xml = loadFixture("sequential_10_tasks.bpmn");
        CompiledProcess compiled = parser.parse(xml);

        List<List<String>> chains = compiled.sequentialChains();
        assertEquals(1, chains.size(), "Should have exactly 1 sequential chain");
        assertEquals(10, chains.getFirst().size(), "Chain should have 10 service tasks");

        List<String> expected = List.of("Task_1", "Task_2", "Task_3", "Task_4", "Task_5",
                "Task_6", "Task_7", "Task_8", "Task_9", "Task_10");
        assertEquals(expected, chains.getFirst());
    }

    @Test
    void parseExclusiveGateway_assertGatewayHas2OutgoingFlowsWithConditions() throws Exception {
        String xml = loadFixture("exclusive_gateway.bpmn");
        CompiledProcess compiled = parser.parse(xml);

        FlowNode gateway = compiled.definition().nodes().get("Gateway_1");
        assertInstanceOf(ExclusiveGateway.class, gateway);
        ExclusiveGateway xor = (ExclusiveGateway) gateway;

        assertEquals(3, xor.outgoing().size(), "Gateway should have 3 outgoing (yes, no, default)");

        Map<String, SequenceFlow> flows = compiled.definition().sequenceFlows();
        long flowsWithConditions = flows.values().stream()
                .filter(f -> f.sourceRef().equals("Gateway_1") && f.conditionExpression() != null && !f.conditionExpression().isBlank())
                .count();
        assertEquals(2, flowsWithConditions, "Gateway should have 2 outgoing flows with conditions (yes and no branches)");

        SequenceFlow flowYes = flows.get("Flow_yes");
        SequenceFlow flowNo = flows.get("Flow_no");
        assertNotNull(flowYes);
        assertNotNull(flowNo);
        assertTrue(flowYes.conditionExpression() != null && flowYes.conditionExpression().contains("true"));
        assertTrue(flowNo.conditionExpression() != null && flowNo.conditionExpression().contains("false"));
    }

    @Test
    void parseFeelConditionAndRestTaskConfiguration_readsCustomEngineExtensions() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Rest_Process" name="REST Process">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Call_Api" name="Call API" implementation="rest">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:extensionElements>
                        <engine:taskConfiguration type="rest"
                                                  method="POST"
                                                  url="= &quot;https://api.example.test/orders/&quot; + orderId"
                                                  authenticationType="bearer"
                                                  bearerToken="= authToken"
                                                  headers="= { Accept: &quot;application/json&quot; }"
                                                  body="= { orderId: orderId }"
                                                  resultVariable="apiResult"
                                                  timeoutSeconds="15"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:exclusiveGateway id="Gateway_1">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                      <bpmn:outgoing>Flow_3</bpmn:outgoing>
                    </bpmn:exclusiveGateway>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_3</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Call_Api"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Call_Api" targetRef="Gateway_1"/>
                    <bpmn:sequenceFlow id="Flow_3" sourceRef="Gateway_1" targetRef="End">
                      <bpmn:conditionExpression language="feel">= approved = true</bpmn:conditionExpression>
                    </bpmn:sequenceFlow>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        CompiledProcess compiled = parser.parse(xml);
        ServiceTask serviceTask = (ServiceTask) compiled.definition().nodes().get("Call_Api");
        SequenceFlow flow = compiled.definition().sequenceFlows().get("Flow_3");

        assertEquals(ServiceTaskType.REST, serviceTask.taskType());
        assertNotNull(serviceTask.restConfiguration());
        assertEquals("POST", serviceTask.restConfiguration().method());
        assertEquals("bearer", serviceTask.restConfiguration().authenticationType());
        assertEquals("apiResult", serviceTask.restConfiguration().resultVariable());
        assertEquals(15, serviceTask.restConfiguration().timeoutSeconds());
        assertEquals("feel", flow.conditionExpressionLanguage());
        assertEquals("= approved = true", flow.conditionExpression());
    }

    @Test
    void parseBeanTaskConfiguration_readsEngineBeanTaskExtension() throws Exception {
        String xml = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Bean_Process" name="Bean Process">
                    <bpmn:startEvent id="Start">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Call_Bean" name="Call Bean" implementation="bean">
                      <bpmn:incoming>Flow_1</bpmn:incoming>
                      <bpmn:outgoing>Flow_2</bpmn:outgoing>
                      <bpmn:extensionElements>
                        <engine:taskConfiguration type="bean"
                                                  beanName="counterServiceTaskLogic"
                                                  inputMapping="= { step: incrementBy }"
                                                  resultVariable="beanResult"/>
                      </bpmn:extensionElements>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End">
                      <bpmn:incoming>Flow_2</bpmn:incoming>
                    </bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start" targetRef="Call_Bean"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Call_Bean" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        CompiledProcess compiled = parser.parse(xml);
        ServiceTask serviceTask = (ServiceTask) compiled.definition().nodes().get("Call_Bean");

        assertEquals(ServiceTaskType.BEAN, serviceTask.taskType());
        assertNotNull(serviceTask.beanConfiguration());
        assertEquals("counterServiceTaskLogic", serviceTask.beanConfiguration().beanName());
        assertEquals("= { step: incrementBy }", serviceTask.beanConfiguration().inputMapping());
        assertEquals("beanResult", serviceTask.beanConfiguration().resultVariable());
    }

    @Test
    void roundtrip_parseSerializeParse_semanticallyEqual() throws Exception {
        String xml = loadFixture("minimal.bpmn");
        CompiledProcess first = parser.parse(xml);
        String serialized = parser.serialize(first.definition());
        CompiledProcess second = parser.parse(serialized);

        assertSemanticallyEqual(first, second);
    }

    @Test
    void roundtrip_sequential10Tasks_semanticallyEqual() throws Exception {
        String xml = loadFixture("sequential_10_tasks.bpmn");
        CompiledProcess first = parser.parse(xml);
        String serialized = parser.serialize(first.definition());
        CompiledProcess second = parser.parse(serialized);

        assertSemanticallyEqual(first, second);
    }

    @Test
    void roundtrip_exclusiveGateway_semanticallyEqual() throws Exception {
        String xml = loadFixture("exclusive_gateway.bpmn");
        CompiledProcess first = parser.parse(xml);
        String serialized = parser.serialize(first.definition());
        CompiledProcess second = parser.parse(serialized);

        assertSemanticallyEqual(first, second);
    }

    @Test
    void roundtrip_parallelGateway_semanticallyEqual() throws Exception {
        String xml = loadFixture("parallel_gateway.bpmn");
        CompiledProcess first = parser.parse(xml);
        String serialized = parser.serialize(first.definition());
        CompiledProcess second = parser.parse(serialized);

        assertSemanticallyEqual(first, second);
    }

    @Test
    void invalidXml_throwsBpmnParseExceptionWithClearMessage() {
        String invalidXml = "<not-valid><broken";

        BpmnParseException ex = assertThrows(BpmnParseException.class, () -> parser.parse(invalidXml));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().toLowerCase().contains("invalid") || ex.getMessage().toLowerCase().contains("xml"),
                "Message should indicate XML parsing problem: " + ex.getMessage());
    }

    @Test
    void parseUserTask_withCamundaAssignee() throws Exception {
        String xml = """
                <?xml version="1.0"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
                  <bpmn:process id="P1" name="Test">
                    <bpmn:startEvent id="Start" name="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="UT1" name="Review" camunda:assignee="john">
                      <bpmn:incoming>F1</bpmn:incoming>
                      <bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:endEvent id="End" name="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="UT1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="UT1" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;

        CompiledProcess compiled = parser.parse(xml);
        FlowNode node = compiled.definition().nodes().get("UT1");

        assertInstanceOf(UserTask.class, node);
        assertEquals("john", ((UserTask) node).assignee());
    }

    @Test
    void emptyProcess_throwsBpmnParseException() {
        String xml = """
                <?xml version="1.0"?>
                <root>
                  <something>no process here</something>
                </root>
                """;

        BpmnParseException ex = assertThrows(BpmnParseException.class, () -> parser.parse(xml));

        assertNotNull(ex.getMessage());
        assertTrue(ex.getMessage().contains("process") || ex.getMessage().contains("No process"),
                "Message should mention process: " + ex.getMessage());
    }

    private void assertSemanticallyEqual(CompiledProcess a, CompiledProcess b) {
        ProcessDefinition defA = a.definition();
        ProcessDefinition defB = b.definition();

        assertEquals(defA.id(), defB.id());
        assertEquals(defA.name(), defB.name());
        assertEquals(defA.startNodeId(), defB.startNodeId());
        assertEquals(defA.endNodeIds(), defB.endNodeIds());
        assertEquals(defA.nodes().size(), defB.nodes().size());
        assertEquals(defA.sequenceFlows().size(), defB.sequenceFlows().size());

        for (String nodeId : defA.nodes().keySet()) {
            assertTrue(defB.nodes().containsKey(nodeId), "Missing node: " + nodeId);
            FlowNode na = defA.nodes().get(nodeId);
            FlowNode nb = defB.nodes().get(nodeId);
            assertEquals(na.getClass(), nb.getClass());
            assertEquals(na.id(), nb.id());
            assertEquals(na.name(), nb.name());
            if (na instanceof ServiceTask sa && nb instanceof ServiceTask sb) {
                assertEquals(sa.implementation(), sb.implementation());
                assertEquals(sa.taskType(), sb.taskType());
                assertEquals(sa.restConfiguration(), sb.restConfiguration());
                assertEquals(sa.beanConfiguration(), sb.beanConfiguration());
            }
        }

        for (String flowId : defA.sequenceFlows().keySet()) {
            assertTrue(defB.sequenceFlows().containsKey(flowId), "Missing flow: " + flowId);
            SequenceFlow fa = defA.sequenceFlows().get(flowId);
            SequenceFlow fb = defB.sequenceFlows().get(flowId);
            assertEquals(fa.sourceRef(), fb.sourceRef());
            assertEquals(fa.targetRef(), fb.targetRef());
            assertEquals(fa.conditionExpression(), fb.conditionExpression());
            assertEquals(fa.conditionExpressionLanguage(), fb.conditionExpressionLanguage());
        }

        assertEquals(a.sequentialChains().size(), b.sequentialChains().size());
        List<List<String>> chainsA = a.sequentialChains().stream().sorted((x, y) -> String.join(",", x).compareTo(String.join(",", y))).toList();
        List<List<String>> chainsB = b.sequentialChains().stream().sorted((x, y) -> String.join(",", x).compareTo(String.join(",", y))).toList();
        assertEquals(chainsA, chainsB);
    }
}
