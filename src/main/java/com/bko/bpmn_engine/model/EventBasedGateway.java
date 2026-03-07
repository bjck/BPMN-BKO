package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN event-based gateway: one outgoing flow is chosen (e.g. first condition that matches).
 * Execution: same as exclusive — first matching condition wins; optional default.
 */
public record EventBasedGateway(String id, String name, String defaultFlow, List<String> incoming, List<String> outgoing) implements FlowNode {
}
