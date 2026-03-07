package com.bko.bpmn_engine.model;

import java.util.UUID;

public record Completing(UUID instanceId) implements ProcessState {
}
