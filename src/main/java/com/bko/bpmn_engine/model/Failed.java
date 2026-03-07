package com.bko.bpmn_engine.model;

import java.util.UUID;

public record Failed(UUID instanceId, String errorMessage) implements ProcessState {
}
