package com.bko.bpmn_engine.api.dto;

import java.util.Map;

public record CreateInstanceRequest(String processDefinitionId, Map<String, Object> variables) {
}
