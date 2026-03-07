package com.bko.bpmn_engine.api.dto;

import java.util.Map;

public record TriggerCatchRequest(String nodeId, Map<String, Object> variables) {
}
