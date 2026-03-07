package com.bko.bpmn_engine.model;

public record BeanTaskConfiguration(
        String beanName,
        String inputMapping,
        String resultVariable
) {
}
