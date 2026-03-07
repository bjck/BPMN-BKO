package com.bko.bpmn_engine.engine.event;

import org.springframework.context.ApplicationEvent;

import java.util.UUID;

public class ProcessInstanceCreatedEvent extends ApplicationEvent {

    private final UUID instanceId;
    private final String processDefinitionId;

    public ProcessInstanceCreatedEvent(Object source, UUID instanceId, String processDefinitionId) {
        super(source);
        this.instanceId = instanceId;
        this.processDefinitionId = processDefinitionId;
    }

    public UUID instanceId() {
        return instanceId;
    }

    public String processDefinitionId() {
        return processDefinitionId;
    }
}
