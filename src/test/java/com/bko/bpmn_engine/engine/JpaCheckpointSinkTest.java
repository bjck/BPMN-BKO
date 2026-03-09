package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.Active;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for JpaCheckpointSink.
 */
class JpaCheckpointSinkTest {

    private ProcessInstanceStorage instanceStorage;
    private JpaCheckpointSink sink;

    @BeforeEach
    void setUp() {
        instanceStorage = mock(ProcessInstanceStorage.class);
        sink = new JpaCheckpointSink(instanceStorage);
    }

    @Test
    void checkpoint_savesToStorageWithCorrectData() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new ConcurrentHashMap<>(Map.of("x", 1)),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );
        Map<UUID, Map<String, AtomicInteger>> tokens = Map.of();
        List<ProcessInstanceStorage.TaskExecutionRecord> records = List.of(
                new ProcessInstanceStorage.TaskExecutionRecord("task-1", "java", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 1000)
        );

        sink.checkpoint(instance, "SERVICE_TASK_COMPLETED", "task-1", tokens, records, Instant.now());

        verify(instanceStorage).save(argThat(pi ->
                pi.instanceId().equals(instanceId)
                        && pi.processDefinitionId().equals("proc-1")
                        && pi.state() instanceof Active a && "task-1".equals(a.currentNodeId())
        ), eq(tokens));
        verify(instanceStorage).saveEvent(eq(instanceId), eq("SERVICE_TASK_COMPLETED"), eq("task-1"), any(), any(), any());
        verify(instanceStorage).saveTaskExecutions(eq(instanceId), eq(records));
    }

    @Test
    void checkpoint_withNullCurrentNodeId_usesActiveCurrentNodeIdForEvent() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "waiting-node"),
                Instant.now(),
                null
        );

        sink.checkpoint(instance, "USER_TASK_REACHED", null, Map.of(), null, Instant.now());

        verify(instanceStorage).saveEvent(eq(instanceId), eq("USER_TASK_REACHED"), eq("waiting-node"), any(), any(), any());
    }

    @Test
    void checkpoint_withNullTaskRecords_doesNotCallSaveTaskExecutions() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );

        sink.checkpoint(instance, "CREATED", null, Map.of(), null, Instant.now());

        verify(instanceStorage, never()).saveTaskExecutions(any(), any());
    }
}
