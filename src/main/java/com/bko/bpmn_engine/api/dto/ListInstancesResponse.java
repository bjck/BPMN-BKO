package com.bko.bpmn_engine.api.dto;

import java.util.List;
import java.util.Map;

public record ListInstancesResponse(
        List<InstanceSummary> instances,
        Integer page,
        Integer pageSize,
        Long totalCount,
        Boolean hasMore
) {

    public ListInstancesResponse(List<InstanceSummary> instances) {
        this(instances, null, null, null, null);
    }

    public record InstanceSummary(
            java.util.UUID instanceId,
            String processDefinitionId,
            String state,
            String currentNodeId,
            java.time.Instant createdAt,
            java.time.Instant completedAt,
            Map<String, Object> variables
    ) {}
}
