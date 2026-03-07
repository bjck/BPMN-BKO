package com.bko.bpmn_engine.model;

import java.util.List;

public record UserTask(String id, String name, String assignee, List<String> incoming, List<String> outgoing) implements FlowNode {
}
