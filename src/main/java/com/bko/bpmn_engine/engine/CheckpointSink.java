package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Sink for process instance checkpoints. At each checkpoint the engine calls this
 * instead of (or in addition to) writing to the database.
 * <ul>
 *   <li>NoOp: no persistence (e.g. when persistence profile is off).</li>
 *   <li>Jpa: writes to ProcessInstanceStorage on the same thread (current behavior when checkpoint-via-Kafka is off).</li>
 *   <li>Kafka: produces to the checkpoint topic and waits for ack; DB is updated by a separate consumer (eventually consistent).</li>
 * </ul>
 */
public interface CheckpointSink {

    /**
     * Persist a checkpoint: instance snapshot, audit event, and optional task execution records.
     *
     * @param instance              current process instance
     * @param eventType             e.g. CREATED, USER_TASK_REACHED, SERVICE_TASK_COMPLETED, COMPLETED, FAILED
     * @param currentNodeId         node id to persist (use for event and for instance state when Active; may override instance.state() in chain)
     * @param parallelJoinTokens    full map; only this instance's tokens are persisted
     * @param taskExecutionRecords  pending task executions to persist (may be empty)
     * @param eventCreatedAt        timestamp of the checkpoint event
     */
    void checkpoint(ProcessInstance instance,
                    String eventType,
                    String currentNodeId,
                    Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens,
                    List<ProcessInstanceStorage.TaskExecutionRecord> taskExecutionRecords,
                    Instant eventCreatedAt);
}
