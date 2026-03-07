package com.bko.bpmn_engine.api.dto;

import java.util.List;

public record ListServiceTaskLogicsResponse(List<ServiceTaskLogicSummary> serviceTaskLogics) {

    public record ServiceTaskLogicSummary(
            String beanName,
            String displayName,
            String description
    ) {
    }
}
