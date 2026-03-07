package com.bko.bpmn_engine.model;

import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CompiledProcessTest {

    @Test
    void detectSequentialChains_tenSequentialServiceTasks_returnsOneChainOfTen() {
        ProcessDefinition definition = buildProcessWithTenSequentialServiceTasks();
        Map<String, List<String>> adjacency = buildAdjacency(definition);

        List<List<String>> chains = CompiledProcess.detectSequentialChains(definition, adjacency);

        assertEquals(1, chains.size());
        assertEquals(10, chains.getFirst().size());
        assertEquals(List.of("s1", "s2", "s3", "s4", "s5", "s6", "s7", "s8", "s9", "s10"), chains.getFirst());
    }

    @Test
    void detectSequentialChains_serviceTasksSplitByGateway_returnsTwoSeparateChains() {
        ProcessDefinition definition = buildProcessWithServiceTasksSplitByGateway();
        Map<String, List<String>> adjacency = buildAdjacency(definition);

        List<List<String>> chains = CompiledProcess.detectSequentialChains(definition, adjacency);

        assertEquals(2, chains.size());
        assertTrue(chains.contains(List.of("s1", "s2", "s3")));
        assertTrue(chains.contains(List.of("s4", "s5")));
    }

    @Test
    void detectSequentialChains_mixedTasks_onlyServiceTaskGroupsDetected() {
        ProcessDefinition definition = buildProcessWithMixedTasks();
        Map<String, List<String>> adjacency = buildAdjacency(definition);

        List<List<String>> chains = CompiledProcess.detectSequentialChains(definition, adjacency);

        assertEquals(4, chains.size());
        assertTrue(chains.contains(List.of("s1", "s2")));
        assertTrue(chains.contains(List.of("s4")));
        assertTrue(chains.contains(List.of("s6", "s7")));
        assertTrue(chains.contains(List.of("s6b")));
    }

    private ProcessDefinition buildProcessWithTenSequentialServiceTasks() {
        Map<String, FlowNode> nodes = new HashMap<>();
        nodes.put("start", new StartEvent("start", "Start", List.of("s1")));

        for (int i = 1; i <= 10; i++) {
            String id = "s" + i;
            List<String> incoming = i == 1 ? List.of("start") : List.of("s" + (i - 1));
            List<String> outgoing = i == 10 ? List.of("end") : List.of("s" + (i + 1));
            nodes.put(id, new ServiceTask(id, "Task" + i, "impl" + i, ServiceTaskType.WORKER, null, null, null, incoming, outgoing));
        }

        nodes.put("end", new EndEvent("end", "End", List.of("s10")));

        Map<String, SequenceFlow> flows = new HashMap<>();
        flows.put("f0", new SequenceFlow("f0", "start", "s1", null, null));
        for (int i = 1; i < 10; i++) {
            flows.put("f" + i, new SequenceFlow("f" + i, "s" + i, "s" + (i + 1), null, null));
        }
        flows.put("f10", new SequenceFlow("f10", "s10", "end", null, null));

        return new ProcessDefinition("proc1", "Process1", nodes, flows, "start", List.of("end"));
    }

    private ProcessDefinition buildProcessWithServiceTasksSplitByGateway() {
        Map<String, FlowNode> nodes = new HashMap<>();
        nodes.put("start", new StartEvent("start", "Start", List.of("s1")));
        nodes.put("s1", new ServiceTask("s1", "Task1", "impl1", ServiceTaskType.WORKER, null, null, null, List.of("start"), List.of("s2")));
        nodes.put("s2", new ServiceTask("s2", "Task2", "impl2", ServiceTaskType.WORKER, null, null, null, List.of("s1"), List.of("s3")));
        nodes.put("s3", new ServiceTask("s3", "Task3", "impl3", ServiceTaskType.WORKER, null, null, null, List.of("s2"), List.of("gw")));
        nodes.put("gw", new ExclusiveGateway("gw", "Gateway", "flow-to-s4", List.of("s3"), List.of("s4", "s4-alt")));
        nodes.put("s4", new ServiceTask("s4", "Task4", "impl4", ServiceTaskType.WORKER, null, null, null, List.of("gw"), List.of("s5")));
        nodes.put("s5", new ServiceTask("s5", "Task5", "impl5", ServiceTaskType.WORKER, null, null, null, List.of("s4"), List.of("end")));
        nodes.put("end", new EndEvent("end", "End", List.of("s5")));

        Map<String, SequenceFlow> flows = new HashMap<>();
        flows.put("f1", new SequenceFlow("f1", "start", "s1", null, null));
        flows.put("f2", new SequenceFlow("f2", "s1", "s2", null, null));
        flows.put("f3", new SequenceFlow("f3", "s2", "s3", null, null));
        flows.put("f4", new SequenceFlow("f4", "s3", "gw", null, null));
        flows.put("f5", new SequenceFlow("f5", "gw", "s4", null, null));
        flows.put("f6", new SequenceFlow("f6", "s4", "s5", null, null));
        flows.put("f7", new SequenceFlow("f7", "s5", "end", null, null));

        return new ProcessDefinition("proc2", "Process2", nodes, flows, "start", List.of("end"));
    }

    private ProcessDefinition buildProcessWithMixedTasks() {
        Map<String, FlowNode> nodes = new HashMap<>();
        nodes.put("start", new StartEvent("start", "Start", List.of("s1")));
        nodes.put("s1", new ServiceTask("s1", "Task1", "impl1", ServiceTaskType.WORKER, null, null, null, List.of("start"), List.of("s2")));
        nodes.put("s2", new ServiceTask("s2", "Task2", "impl2", ServiceTaskType.WORKER, null, null, null, List.of("s1"), List.of("ut1")));
        nodes.put("ut1", new UserTask("ut1", "UserTask", "user1", List.of("s2"), List.of("s4")));
        nodes.put("s4", new ServiceTask("s4", "Task4", "impl4", ServiceTaskType.WORKER, null, null, null, List.of("ut1"), List.of("gw")));
        nodes.put("gw", new ParallelGateway("gw", "Fork", List.of("s4"), List.of("s6", "s6b")));
        nodes.put("s6", new ServiceTask("s6", "Task6", "impl6", ServiceTaskType.WORKER, null, null, null, List.of("gw"), List.of("s7")));
        nodes.put("s6b", new ServiceTask("s6b", "Task6b", "impl6b", ServiceTaskType.WORKER, null, null, null, List.of("gw"), List.of("end2")));
        nodes.put("s7", new ServiceTask("s7", "Task7", "impl7", ServiceTaskType.WORKER, null, null, null, List.of("s6"), List.of("end")));
        nodes.put("end", new EndEvent("end", "End", List.of("s7")));
        nodes.put("end2", new EndEvent("end2", "End2", List.of("s6b")));

        Map<String, SequenceFlow> flows = new HashMap<>();
        flows.put("f1", new SequenceFlow("f1", "start", "s1", null, null));
        flows.put("f2", new SequenceFlow("f2", "s1", "s2", null, null));
        flows.put("f3", new SequenceFlow("f3", "s2", "ut1", null, null));
        flows.put("f4", new SequenceFlow("f4", "ut1", "s4", null, null));
        flows.put("f5", new SequenceFlow("f5", "s4", "gw", null, null));
        flows.put("f6", new SequenceFlow("f6", "gw", "s6", null, null));
        flows.put("f6b", new SequenceFlow("f6b", "gw", "s6b", null, null));
        flows.put("f7", new SequenceFlow("f7", "s6", "s7", null, null));
        flows.put("f8", new SequenceFlow("f8", "s7", "end", null, null));
        flows.put("f9", new SequenceFlow("f9", "s6b", "end2", null, null));

        return new ProcessDefinition("proc3", "Process3", nodes, flows, "start", List.of("end", "end2"));
    }

    private Map<String, List<String>> buildAdjacency(ProcessDefinition definition) {
        Map<String, List<String>> adjacency = new HashMap<>();
        for (SequenceFlow flow : definition.sequenceFlows().values()) {
            adjacency.computeIfAbsent(flow.sourceRef(), k -> new java.util.ArrayList<>()).add(flow.targetRef());
        }
        adjacency.replaceAll((k, v) -> List.copyOf(v));
        return adjacency;
    }
}
