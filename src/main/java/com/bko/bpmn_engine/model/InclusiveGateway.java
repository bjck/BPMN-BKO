package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN inclusive (OR) gateway: activates one or more outgoing flows whose condition is true.
 * Join semantics: wait for all activated branches to arrive (like parallel join).
 */
public record InclusiveGateway(String id, String name, String defaultFlow, List<String> incoming, List<String> outgoing) implements FlowNode {
}
