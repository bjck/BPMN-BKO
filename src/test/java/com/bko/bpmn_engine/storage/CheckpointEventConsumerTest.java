package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for CheckpointEventConsumer: given a checkpoint payload, verifies that
 * save, saveEvent, and saveTaskExecutions are called with the expected arguments.
 */
class CheckpointEventConsumerTest {

    private ProcessInstanceStorage instanceStorage;
    private CheckpointEventConsumer consumer;

    @BeforeEach
    void setUp() {
        instanceStorage = mock(ProcessInstanceStorage.class);
        consumer = new CheckpointEventConsumer(instanceStorage);
    }

    @Test
    void onCheckpoint_callsSaveSaveEventAndSaveTaskExecutions() {
        UUID instanceId = UUID.randomUUID();
        Instant now = Instant.now();
        CheckpointEventPayload payload = new CheckpointEventPayload(
                instanceId,
                "proc-1",
                "ACTIVE",
                "task-1",
                null,
                "{\"counter\":1}",
                "{}",
                now.minusSeconds(60),
                null,
                "SERVICE_TASK_COMPLETED",
                now,
                List.of(new CheckpointEventPayload.TaskExecutionRecordDto(
                        "task-1", "java", now.minusSeconds(1), now, 1000))
        );

        consumer.onCheckpoint(payload);

        verify(instanceStorage).save(argThat(instance ->
                instance.instanceId().equals(instanceId)
                        && "proc-1".equals(instance.processDefinitionId())
                        && instance.state() instanceof com.bko.bpmn_engine.model.Active a && "task-1".equals(a.currentNodeId())
                        && Integer.valueOf(1).equals(instance.variables().get("counter"))
        ), any());
        verify(instanceStorage).saveEvent(
                eq(instanceId),
                eq("SERVICE_TASK_COMPLETED"),
                eq("task-1"),
                any(),
                eq(java.util.Map.of()),
                eq(now)
        );
        verify(instanceStorage).saveTaskExecutions(eq(instanceId), argThat(records ->
                records.size() == 1
                        && records.getFirst().taskId().equals("task-1")
                        && records.getFirst().taskType().equals("java")
                        && records.getFirst().durationMs() == 1000
        ));
    }
}
