package com.bko.bpmn_engine.engine;

import java.util.Map;
import java.util.UUID;

public record ServiceTaskExecutionContext(
        UUID instanceId,
        String processDefinitionId,
        String taskId,
        String taskName,
        Map<String, Object> variables,
        Map<String, Object> inputs
) {
}
