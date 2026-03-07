package com.bko.bpmn_engine.api.dto;

import java.util.Map;

public record CompleteTaskRequest(Map<String, Object> variables) {
}
