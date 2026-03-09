package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import com.bko.bpmn_engine.model.Active;
import com.bko.bpmn_engine.model.Failed;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Unit tests for KafkaCheckpointSink: verifies checkpoint payload shape and that send is invoked with correct key.
 */
class KafkaCheckpointSinkTest {

    private KafkaTemplate<String, CheckpointEventPayload> kafkaTemplate;
    private KafkaCheckpointSink sink;
    private static final String TOPIC = "bpmn-checkpoints";

    @BeforeEach
    void setUp() {
        kafkaTemplate = mock(KafkaTemplate.class);
        sink = new KafkaCheckpointSink(kafkaTemplate, TOPIC);
    }

    @Test
    void checkpoint_sendsToTopicWithInstanceIdKeyAndFullPayload() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of("x", 1)),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );
        Map<UUID, Map<String, AtomicInteger>> tokens = Map.of();
        List<ProcessInstanceStorage.TaskExecutionRecord> records = List.of(
                new ProcessInstanceStorage.TaskExecutionRecord("task-1", "java", Instant.EPOCH, Instant.EPOCH.plusSeconds(1), 1000)
        );
        Instant eventCreatedAt = Instant.now();

        CompletableFuture<SendResult<String, CheckpointEventPayload>> future = CompletableFuture.completedFuture(mock(SendResult.class));
        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(future);

        sink.checkpoint(instance, "SERVICE_TASK_COMPLETED", "task-1", tokens, records, eventCreatedAt);

        verify(kafkaTemplate).send(eq(TOPIC), eq(instanceId.toString()), argThat(payload ->
                payload.instanceId().equals(instanceId)
                        && "proc-1".equals(payload.processDefinitionId())
                        && "ACTIVE".equals(payload.stateType())
                        && "task-1".equals(payload.currentNodeId())
                        && "SERVICE_TASK_COMPLETED".equals(payload.eventType())
                        && payload.variablesJson() != null
                        && payload.parallelJoinTokensJson() != null
                        && payload.taskExecutionRecords() != null
                        && payload.taskExecutionRecords().size() == 1
                        && payload.taskExecutionRecords().getFirst().taskId().equals("task-1")
        ));
    }

    @Test
    void checkpoint_withFailedState_includesErrorMessageInPayload() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of()),
                new Failed(instanceId, "Task failed"),
                Instant.now(),
                Instant.now()
        );

        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        sink.checkpoint(instance, "FAILED", null, Map.of(), null, Instant.now());

        verify(kafkaTemplate).send(eq(TOPIC), eq(instanceId.toString()), argThat(payload ->
                "FAILED".equals(payload.stateType())
                        && "Task failed".equals(payload.errorMessage())
        ));
    }

    @Test
    void checkpoint_withNullCurrentNodeId_usesActiveCurrentNodeId() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "waiting-task"),
                Instant.now(),
                null
        );

        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        sink.checkpoint(instance, "USER_TASK_REACHED", null, Map.of(), null, Instant.now());

        verify(kafkaTemplate).send(eq(TOPIC), eq(instanceId.toString()), argThat(payload ->
                "waiting-task".equals(payload.currentNodeId())
        ));
    }

    @Test
    void checkpoint_whenSendThrowsInterruptedException_throwsCheckpointPublishException() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );

        CompletableFuture<SendResult<String, CheckpointEventPayload>> future = new CompletableFuture<>();
        future.completeExceptionally(new InterruptedException("interrupted"));
        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(future);

        assertThrows(CheckpointPublishException.class, () ->
                sink.checkpoint(instance, "CREATED", null, Map.of(), null, Instant.now()));
    }

    @Test
    void checkpoint_withEmptyTaskRecords_passesNullToPayload() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );

        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(CompletableFuture.completedFuture(mock(SendResult.class)));

        sink.checkpoint(instance, "CREATED", null, Map.of(), List.of(), Instant.now());

        verify(kafkaTemplate).send(eq(TOPIC), eq(instanceId.toString()), argThat(payload ->
                payload.taskExecutionRecords() == null
        ));
    }

    @Test
    void checkpoint_whenSendThrowsException_throwsCheckpointPublishException() {
        UUID instanceId = UUID.randomUUID();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                "proc-1",
                new java.util.concurrent.ConcurrentHashMap<>(Map.of()),
                new Active(instanceId, "task-1"),
                Instant.now(),
                null
        );

        when(kafkaTemplate.send(eq(TOPIC), anyString(), any(CheckpointEventPayload.class)))
                .thenReturn(CompletableFuture.failedFuture(new RuntimeException("Kafka unavailable")));

        assertThrows(CheckpointPublishException.class, () ->
                sink.checkpoint(instance, "CREATED", null, Map.of(), null, Instant.now()));
    }
}
