package com.bko.bpmn_engine.model;

import java.util.UUID;

public record Created(UUID instanceId) implements ProcessState {
}
