package com.bko.bpmn_engine.model;

import java.util.List;

public record ServiceTask(
        String id,
        String name,
        String implementation,
        ServiceTaskType taskType,
        RestTaskConfiguration restConfiguration,
        BeanTaskConfiguration beanConfiguration,
        KafkaTaskConfiguration kafkaConfiguration,
        List<String> incoming,
        List<String> outgoing
) implements FlowNode {
}
