package com.bko.bpmn_engine.model;

import java.util.List;

public record ExclusiveGateway(String id, String name, String defaultFlow, List<String> incoming, List<String> outgoing) implements FlowNode {
}
