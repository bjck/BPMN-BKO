package com.bko.bpmn_engine.storage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted process instance snapshot for recovery.
 * Updated at checkpoint boundaries (create, UserTask, complete).
 */
@Entity
@Table(name = "process_instance", indexes = {
        @Index(name = "idx_process_instance_state", columnList = "state"),
        @Index(name = "idx_process_instance_definition", columnList = "process_definition_id")
})
public class ProcessInstanceEntity {

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

    protected ProcessInstanceEntity() {
    }

    public ProcessInstanceEntity(UUID instanceId, String processDefinitionId, String state,
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

    public void setState(String state) {
        this.state = state;
    }

    public String getCurrentNodeId() {
        return currentNodeId;
    }

    public void setCurrentNodeId(String currentNodeId) {
        this.currentNodeId = currentNodeId;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getVariablesJson() {
        return variablesJson;
    }

    public void setVariablesJson(String variablesJson) {
        this.variablesJson = variablesJson;
    }

    public String getParallelJoinTokensJson() {
        return parallelJoinTokensJson;
    }

    public void setParallelJoinTokensJson(String parallelJoinTokensJson) {
        this.parallelJoinTokensJson = parallelJoinTokensJson;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Instant completedAt) {
        this.completedAt = completedAt;
    }

    public long getVersion() {
        return version;
    }
}
