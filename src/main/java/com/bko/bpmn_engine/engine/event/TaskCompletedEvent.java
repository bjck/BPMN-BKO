package com.bko.bpmn_engine.engine.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class TaskCompletedEvent extends ApplicationEvent {

    private final UUID instanceId;
    private final String taskId;
    private final long durationMs;

    public TaskCompletedEvent(Object source, UUID instanceId, String taskId, long durationMs) {
        super(source);
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.durationMs = durationMs;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String taskId() {
        return taskId;
    }

    public long durationMs() {
        return durationMs;
    }
}
