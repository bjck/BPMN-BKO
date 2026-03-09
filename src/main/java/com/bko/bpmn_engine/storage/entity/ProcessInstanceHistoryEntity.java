package com.bko.bpmn_engine.storage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Completed/failed process instance snapshot in history table.
 * Same column layout as {@link ProcessInstanceEntity}; only COMPLETED/FAILED rows.
 */
@Entity
@Table(name = "process_instance_history", indexes = {
        @Index(name = "idx_process_instance_history_completed_at", columnList = "completed_at"),
        @Index(name = "idx_process_instance_history_definition", columnList = "process_definition_id")
})
public class ProcessInstanceHistoryEntity {

    @Id
    @Column(name = "instance_id", columnDefinition = "UUID")
    private UUID instanceId;

    @Column(name = "process_definition_id", nullable = false, length = 255)
    private String processDefinitionId;

    @Column(name = "state", nullable = false, length = 32)
    private String state;

    @Column(name = "current_node_id", length = 255)
    private String currentNodeId;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "variables_json", columnDefinition = "TEXT")
    private String variablesJson;

    @Column(name = "parallel_join_tokens_json", columnDefinition = "TEXT")
    private String parallelJoinTokensJson;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Version
    @Column(name = "version")
    private long version;

    protected ProcessInstanceHistoryEntity() {
    }

    @SuppressWarnings("java:S107")
    public ProcessInstanceHistoryEntity(UUID instanceId, String processDefinitionId, String state,
                                       String currentNodeId, String errorMessage, String variablesJson,
                                       String parallelJoinTokensJson, Instant createdAt, Instant completedAt) {
        this.instanceId = instanceId;
        this.processDefinitionId = processDefinitionId;
        this.state = state;
        this.currentNodeId = currentNodeId;
        this.errorMessage = errorMessage;
        this.variablesJson = variablesJson;
        this.parallelJoinTokensJson = parallelJoinTokensJson;
        this.createdAt = createdAt;
        this.completedAt = completedAt;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getProcessDefinitionId() {
        return processDefinitionId;
    }

    public String getState() {
        return state;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public String getErrorMessage() {
        return errorMessage;
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

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getVersion() {
        return version;
    }
}
