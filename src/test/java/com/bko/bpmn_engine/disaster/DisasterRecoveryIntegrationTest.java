package com.bko.bpmn_engine.disaster;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.TaskWorker;
import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import com.bko.bpmn_engine.model.Active;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.context.ActiveProfiles;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Disaster recovery integration test: Kafka has a checkpoint message (step completed)
 * but the DB has not yet been updated. The consumer processes the message, DB becomes
 * consistent, and we can restore and resume from that state.
 */
@SpringBootTest
@ActiveProfiles({"persistence", "disaster-recovery"})
@EmbeddedKafka(
        topics = "bpmn-checkpoints",
        partitions = 1,
        bootstrapServersProperty = "spring.kafka.bootstrap-servers"
)
class DisasterRecoveryIntegrationTest {

    private static final String PROCESS_ID = "Process_Sequential";
    private static final String TASK_1 = "Task_1";
    private static final String TASK_2 = "Task_2";

    @Autowired
    private ProcessEngine engine;
    @Autowired
    private ProcessInstanceStorage instanceStorage;
    @Autowired
    private KafkaTemplate<String, CheckpointEventPayload> checkpointKafkaTemplate;
    @Autowired(required = false)
    private com.bko.bpmn_engine.storage.CheckpointEventConsumer checkpointEventConsumer;

    private String sequentialBpmn;

    @BeforeEach
    void setUp() throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/sequential_10_tasks.bpmn").toURI());
        sequentialBpmn = Files.readString(fixture, StandardCharsets.UTF_8);
        TaskWorker counterWorker = vars -> {
            int count = ((Number) vars.getOrDefault("counter", 0)).intValue();
            return Map.<String, Object>of("counter", count + 1);
        };
        engine.registerWorker("java", counterWorker);
    }

    @Test
    void whenKafkaHasCheckpointButDbIsBehind_consumerUpdatesDb_thenResumeIsConsistent() throws Exception {
        // 1. Deploy process so definition exists and engine can run
        String definitionId = engine.deployProcess(sequentialBpmn);
        assertThat(definitionId).isEqualTo(PROCESS_ID);

        UUID instanceId = UUID.randomUUID();
        Instant createdAt = Instant.now().minusSeconds(60);

        // 2. Seed DB with an OLD state: instance at TASK_1 (as if we had an older checkpoint)
        ConcurrentHashMap<String, Object> varsOld = new ConcurrentHashMap<>(Map.of("counter", 1));
        ProcessInstance oldState = new ProcessInstance(
                instanceId,
                definitionId,
                varsOld,
                new Active(instanceId, TASK_1),
                createdAt,
                null
        );
        instanceStorage.save(oldState, Map.of());
        instanceStorage.saveEvent(
                instanceId,
                "SERVICE_TASK_COMPLETED",
                TASK_1,
                Map.of("counter", 1),
                Map.of(),
                createdAt
        );

        // 3. Simulate "disaster": Kafka has a NEWER checkpoint (step 2 completed) that DB doesn't have yet
        Instant eventTime = Instant.now();
        CheckpointEventPayload newerCheckpoint = new CheckpointEventPayload(
                instanceId,
                definitionId,
                "ACTIVE",
                TASK_2,  // just completed Task_2
                null,
                "{\"counter\":2}",
                "{}",
                createdAt,
                null,
                "SERVICE_TASK_COMPLETED",
                eventTime,
                List.of(
                        new CheckpointEventPayload.TaskExecutionRecordDto(
                                TASK_2, "java",
                                eventTime.minusSeconds(1), eventTime, 1000
                        )
                )
        );
        // Publish checkpoint to Kafka (as the engine would). DB has not been updated yet.
        checkpointKafkaTemplate.send("bpmn-checkpoints", instanceId.toString(), newerCheckpoint).get(5, TimeUnit.SECONDS);

        // 4. Process the checkpoint (same logic as the Kafka listener). Simulates "consumer ran and updated DB".
        assertThat(checkpointEventConsumer).as("Checkpoint consumer must be active in disaster-recovery profile").isNotNull();
        checkpointEventConsumer.onCheckpoint(newerCheckpoint);

        // 5. Verify DB has the consistent state from the checkpoint
        ProcessInstanceStorage.RecoveredInstance recoveredAfterConsumer = instanceStorage.findById(instanceId);
        assertThat(recoveredAfterConsumer).isNotNull();
        assertThat(recoveredAfterConsumer.currentNodeId()).isEqualTo(TASK_2);
        assertThat(recoveredAfterConsumer.variables()).containsEntry("counter", 2);

        // 6. Restore into engine and resume from next node (Task_3) so process can complete
        engine.restoreActiveInstance(recoveredAfterConsumer);
        ProcessInstance restored = engine.getInstance(instanceId);
        assertThat(restored).isNotNull();
        assertThat(restored.state()).isInstanceOf(Active.class);
        assertThat(((Active) restored.state()).currentNodeId()).isEqualTo(TASK_2);

        // 7. Resume: execute from Task_3 (next after Task_2) so we don't re-run Task_2
        engine.executeFrom(restored, "Task_3");

        // 8. Process should complete. Engine runs the full sequential chain from Task_1 when executing any node in the chain, so counter = 2 + 10 = 12.
        ProcessInstance afterResume = engine.getInstance(instanceId);
        assertThat(afterResume).isNotNull();
        assertThat(afterResume.state().getClass().getSimpleName()).isEqualTo("Completed");
        assertThat(afterResume.variables()).containsEntry("counter", 12);
    }

}
