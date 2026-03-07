package com.bko.bpmn_engine.model;

import java.util.List;

public record ParallelGateway(String id, String name, List<String> incoming, List<String> outgoing) implements FlowNode {
}
