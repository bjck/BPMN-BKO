package com.bko.bpmn_engine.model;

import java.util.UUID;

public record Active(UUID instanceId, String currentNodeId) implements ProcessState {
}
