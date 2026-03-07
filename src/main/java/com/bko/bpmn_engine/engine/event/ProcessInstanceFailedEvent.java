package com.bko.bpmn_engine.engine.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ProcessInstanceFailedEvent extends ApplicationEvent {

    private final UUID instanceId;
    private final String error;

    public ProcessInstanceFailedEvent(Object source, UUID instanceId, String error) {
        super(source);
        this.instanceId = instanceId;
        this.error = error;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String error() {
        return error;
    }
}
