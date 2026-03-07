package com.bko.bpmn_engine.model;

import java.util.UUID;

public record Completed(UUID instanceId) implements ProcessState {
}
