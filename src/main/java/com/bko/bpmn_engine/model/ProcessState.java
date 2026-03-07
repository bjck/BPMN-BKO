package com.bko.bpmn_engine.model;

import java.util.UUID;

/**
 * Sealed interface representing process instance lifecycle states.
 */
public sealed interface ProcessState permits Created, Active, Completing, Completed, Failed {

    UUID instanceId();
}
