package com.bko.bpmn_engine.engine.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class TaskActivatedEvent extends ApplicationEvent {

    private final UUID instanceId;
    private final String taskId;
    private final String taskType;

    public TaskActivatedEvent(Object source, UUID instanceId, String taskId, String taskType) {
        super(source);
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.taskType = taskType;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String taskId() {
        return taskId;
    }

    public String taskType() {
        return taskType;
    }
}
