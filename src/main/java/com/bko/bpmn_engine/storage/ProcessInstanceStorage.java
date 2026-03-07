package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.model.ProcessState;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Persists process instance state at checkpoint boundaries.
 * Synchronous writes guarantee no data loss on crash.
 */
public interface ProcessInstanceStorage {

    /**
     * Persist instance snapshot. Blocks until committed.
     * Called at: create, UserTask reached, complete.
     */
    void save(ProcessInstance instance, Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens);

    /**
     * Persist audit event (append-only). Same transaction as save when applicable.
     */
    void saveEvent(UUID instanceId, String eventType, String currentNodeId,
                   Map<String, Object> variables, Map<String, Integer> parallelJoinTokens,
                   Instant createdAt);

    /**
     * Persist task execution records. Same transaction as checkpoint save.
     */
    void saveTaskExecutions(UUID instanceId, List<TaskExecutionRecord> records);

    /**
     * Load all active instances for recovery on startup.
     */
    List<RecoveredInstance> findAllActive();

    /**
     * Load instance by id (active or completed).
     */
    RecoveredInstance findById(UUID instanceId);

    /**
     * Load all instances from storage (for listing when persistence is enabled).
     */
    List<RecoveredInstance> findAll();

    record TaskExecutionRecord(String taskId, String taskType, Instant startedAt, Instant completedAt, long durationMs) {
    }

    /** Recovered instance with parallel join tokens for this instance only (nodeId -> count). */
    record RecoveredInstance(
            UUID instanceId,
            String processDefinitionId,
            ProcessState state,
            String currentNodeId,
            Map<String, Object> variables,
            Map<String, Integer> parallelJoinTokens,
            Instant createdAt,
            Instant completedAt
    ) {
    }
}
