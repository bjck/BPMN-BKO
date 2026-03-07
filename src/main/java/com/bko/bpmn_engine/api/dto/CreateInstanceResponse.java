package com.bko.bpmn_engine.api.dto;

import java.util.Map;
import java.util.UUID;

public record CreateInstanceResponse(UUID instanceId, String state, Map<String, Object> variables) {
}
