package com.bko.bpmn_engine.model;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Compiled process with precomputed adjacency and sequential service task chains.
 * Sequential chains are used for synchronous execution on the same virtual thread.
 */
public record CompiledProcess(
        ProcessDefinition definition,
        Map<String, List<String>> adjacency,
        List<List<String>> sequentialChains
) {

    /**
     * Detects consecutive ServiceTask sequences with no gateways between them.
     * Gateways (ExclusiveGateway, ParallelGateway) and other node types (UserTask, StartEvent, EndEvent)
     * break chains.
     *
     * @param definition the process definition
     * @param adjacency  nodeId -> list of next nodeIds
     * @return list of chains, each chain is a list of ServiceTask ids in order
     */
    public static List<List<String>> detectSequentialChains(ProcessDefinition definition, Map<String, List<String>> adjacency) {
        Set<String> visited = new HashSet<>();
        List<List<String>> chains = new ArrayList<>();

        for (String nodeId : definition.nodes().keySet()) {
            FlowNode node = definition.nodes().get(nodeId);
            if (!(node instanceof ServiceTask st)) {
                continue;
            }
            if (visited.contains(nodeId)) {
                continue;
            }

            // Only start a chain from a "chain head": no incoming from another ServiceTask
            boolean hasIncomingFromServiceTask = st.incoming().stream()
                    .anyMatch(inId -> definition.nodes().get(inId) instanceof ServiceTask);
            if (hasIncomingFromServiceTask) {
                continue;
            }

            List<String> chain = new ArrayList<>();
            String current = nodeId;

            while (true) {
                FlowNode n = definition.nodes().get(current);
                if (!(n instanceof ServiceTask)) {
                    break;
                }
                if (visited.contains(current)) {
                    break;
                }
                chain.add(current);
                visited.add(current);

                List<String> nextIds = adjacency.get(current);
                if (nextIds == null || nextIds.isEmpty()) {
                    break;
                }

                List<String> serviceTaskSuccessors = nextIds.stream()
                        .filter(id -> definition.nodes().get(id) instanceof ServiceTask)
                        .toList();

                if (serviceTaskSuccessors.size() != 1) {
                    break;
                }

                current = serviceTaskSuccessors.getFirst();
            }

            if (!chain.isEmpty()) {
                chains.add(List.copyOf(chain));
            }
        }

        return chains;
    }
}
