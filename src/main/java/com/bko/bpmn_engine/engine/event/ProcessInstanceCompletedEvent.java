package com.bko.bpmn_engine.engine.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ProcessInstanceCompletedEvent extends ApplicationEvent {

    private final UUID instanceId;
    private final long durationMs;

    public ProcessInstanceCompletedEvent(Object source, UUID instanceId, long durationMs) {
        super(source);
        this.instanceId = instanceId;
        this.durationMs = durationMs;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public long durationMs() {
        return durationMs;
    }
}
