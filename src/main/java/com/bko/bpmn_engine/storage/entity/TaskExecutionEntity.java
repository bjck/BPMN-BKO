package com.bko.bpmn_engine.storage.entity;

import jakarta.persistence.*;
import java.time.Instant;
import java.util.UUID;

/**
 * Persisted task execution record for inspection.
 * Captures task activation and completion timing.
 */
@Entity
@Table(name = "task_execution", indexes = {
        @Index(name = "idx_task_execution_instance", columnList = "instance_id")
})
public class TaskExecutionEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "id")
    private Long id;

    @Column(name = "instance_id", nullable = false, columnDefinition = "UUID")
    private UUID instanceId;

    @Column(name = "task_id", nullable = false, length = 255)
    private String taskId;

    @Column(name = "task_type", nullable = false, length = 255)
    private String taskType;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at", nullable = false)
    private Instant completedAt;

    @Column(name = "duration_ms")
    private long durationMs;

    protected TaskExecutionEntity() {
    }

    public TaskExecutionEntity(UUID instanceId, String taskId, String taskType,
                               Instant startedAt, Instant completedAt, long durationMs) {
        this.instanceId = instanceId;
        this.taskId = taskId;
        this.taskType = taskType;
        this.startedAt = startedAt;
        this.completedAt = completedAt;
        this.durationMs = durationMs;
    }

    public Long getId() {
        return id;
    }

    public UUID getInstanceId() {
        return instanceId;
    }

    public String getTaskId() {
        return taskId;
    }

    public String getTaskType() {
        return taskType;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }

    public long getDurationMs() {
        return durationMs;
    }
}
