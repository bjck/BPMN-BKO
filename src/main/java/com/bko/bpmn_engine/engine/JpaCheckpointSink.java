package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.Active;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checkpoint sink that writes directly to ProcessInstanceStorage (JPA).
 * Used when persistence is enabled but checkpoint-via-Kafka is disabled.
 */
public class JpaCheckpointSink implements CheckpointSink {

    private static final Logger log = LoggerFactory.getLogger(JpaCheckpointSink.class);

    private final ProcessInstanceStorage instanceStorage;

    public JpaCheckpointSink(ProcessInstanceStorage instanceStorage) {
        this.instanceStorage = instanceStorage;
    }

    @Override
    public void checkpoint(ProcessInstance instance, String eventType, String currentNodeId,
                           Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens,
                           List<ProcessInstanceStorage.TaskExecutionRecord> taskExecutionRecords,
                           Instant eventCreatedAt) {
        ProcessInstance toSave = instance;
        if (currentNodeId != null && instance.state() instanceof Active) {
            toSave = new ProcessInstance(
                    instance.instanceId(),
                    instance.processDefinitionId(),
                    instance.variables(),
                    new Active(instance.instanceId(), currentNodeId),
                    instance.createdAt(),
                    instance.completedAt()
            );
        }
        String nodeIdForEvent = currentNodeId != null ? currentNodeId
                : (instance.state() instanceof Active a ? a.currentNodeId() : null);
        log.trace("JPA checkpoint instanceId={} eventType={} currentNodeId={}", instance.instanceId(), eventType, nodeIdForEvent);
        instanceStorage.save(toSave, parallelJoinTokens);
        instanceStorage.saveEvent(
                instance.instanceId(),
                eventType,
                nodeIdForEvent,
                Map.copyOf(instance.variables()),
                plainTokens(parallelJoinTokens, instance.instanceId()),
                eventCreatedAt
        );
        if (taskExecutionRecords != null && !taskExecutionRecords.isEmpty()) {
            instanceStorage.saveTaskExecutions(instance.instanceId(), taskExecutionRecords);
        }
    }

    private static Map<String, Integer> plainTokens(Map<UUID, Map<String, AtomicInteger>> tokens, UUID instanceId) {
        Map<String, AtomicInteger> inner = tokens.get(instanceId);
        if (inner == null || inner.isEmpty()) return Map.of();
        Map<String, Integer> plain = new java.util.HashMap<>();
        inner.forEach((k, v) -> plain.put(k, v.get()));
        return plain;
    }
}
