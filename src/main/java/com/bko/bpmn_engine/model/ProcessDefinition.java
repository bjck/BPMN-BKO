package com.bko.bpmn_engine.model;

import java.util.List;
import java.util.Map;

public record ProcessDefinition(
        String id,
        String name,
        Map<String, FlowNode> nodes,
        Map<String, SequenceFlow> sequenceFlows,
        String startNodeId,
        List<String> endNodeIds
) {
}
