package com.bko.bpmn_engine.model;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public record ProcessInstance(
        UUID instanceId,
        String processDefinitionId,
        ConcurrentHashMap<String, Object> variables,
        ProcessState state,
        Instant createdAt,
        Instant completedAt
) {
}
