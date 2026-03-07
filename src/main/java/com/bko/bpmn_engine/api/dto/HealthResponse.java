package com.bko.bpmn_engine.api.dto;

public record HealthResponse(String status, int activeInstances, int deployedProcesses) {
}
