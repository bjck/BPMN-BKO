package com.bko.bpmn_engine.storage.entity;

import jakarta.persistence.*;
import java.time.Instant;

/**
 * Persisted process definition (BPMN XML).
 * Stored for recovery and inspection.
 */
@Entity
@Table(name = "process_definition")
public class ProcessDefinitionEntity {

    @Id
    @Column(name = "id", length = 255)
    private String id;

    @Column(name = "bpmn_xml", columnDefinition = "TEXT", nullable = false)
    private String bpmnXml;

    @Column(name = "deployed_at", nullable = false)
    private Instant deployedAt;

    protected ProcessDefinitionEntity() {
    }

    public ProcessDefinitionEntity(String id, String bpmnXml, Instant deployedAt) {
        this.id = id;
        this.bpmnXml = bpmnXml;
        this.deployedAt = deployedAt;
    }

    public String getId() {
        return id;
    }

    public String getBpmnXml() {
        return bpmnXml;
    }

    public Instant getDeployedAt() {
        return deployedAt;
    }
}
