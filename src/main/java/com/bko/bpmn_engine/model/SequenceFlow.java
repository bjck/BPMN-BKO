package com.bko.bpmn_engine.model;

public record SequenceFlow(
        String id,
        String sourceRef,
        String targetRef,
        String conditionExpression,
        String conditionExpressionLanguage
) {
}
