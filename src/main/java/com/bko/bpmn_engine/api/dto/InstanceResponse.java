package com.bko.bpmn_engine.api.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

public record InstanceResponse(
        UUID instanceId,
        String processDefinitionId,
        String state,
        String currentNodeId,
        String pendingUserTaskId,
        Map<String, Object> variables,
        Instant createdAt,
        Instant completedAt
) {
}
