package com.bko.bpmn_engine.storage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Append-only audit log of process instance state changes.
 * Enables full inspection of process execution history.
 */
@Entity
@Table(name = "process_instance_event", indexes = {
        @Index(name = "idx_process_instance_event_instance", columnList = "instance_id"),
        @Index(name = "idx_process_instance_event_created", columnList = "instance_id, created_at")
})
public class ProcessInstanceEventEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "instance_id", nullable = false, columnDefinition = "UUID")
    private UUID instanceId;

    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "current_node_id", length = 255)
    private String currentNodeId;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "parallel_join_tokens_json", columnDefinition = "TEXT")
    private String parallelJoinTokensJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    protected ProcessInstanceEventEntity() {
    }

    public ProcessInstanceEventEntity(UUID instanceId, String eventType, String currentNodeId,
                                      String variablesJson, String parallelJoinTokensJson, Instant createdAt) {
        this.instanceId = instanceId;
        this.eventType = eventType;
        this.currentNodeId = currentNodeId;
        this.variablesJson = variablesJson;
        this.parallelJoinTokensJson = parallelJoinTokensJson;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getEventType() {
        return eventType;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public String getVariablesJson() {
        return variablesJson;
    }

    public String getParallelJoinTokensJson() {
        return parallelJoinTokensJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
