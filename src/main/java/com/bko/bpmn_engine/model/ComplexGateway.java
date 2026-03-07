package com.bko.bpmn_engine.model;

import java.util.List;

/**
 * BPMN complex gateway: which flow(s) to take is defined by an activation expression
 * in our engine namespace (activationExpression / activationLanguage).
 */
public record ComplexGateway(
        String id,
        String name,
        String defaultFlow,
        String activationExpression,
        String activationLanguage,
        List<String> incoming,
        List<String> outgoing
) implements FlowNode {
}
