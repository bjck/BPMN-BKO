package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.model.ProcessState;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Consumes checkpoint events from Kafka and writes to the database.
 * Ensures the DB is eventually consistent with the engine (Kafka is the durable log).
 * One transaction per record; idempotent (last write wins per instance).
 * Only active when persistence profile and bpmn.kafka.checkpoint-enabled are true.
 */
@Component
@Profile("persistence")
@ConditionalOnBean(name = "checkpointKafkaListenerContainerFactory")
public class CheckpointEventConsumer {

    private static final Logger log = LoggerFactory.getLogger(CheckpointEventConsumer.class);

    private final ProcessInstanceStorage instanceStorage;

    public CheckpointEventConsumer(ProcessInstanceStorage instanceStorage) {
        this.instanceStorage = instanceStorage;
    }

    @KafkaListener(
            topics = "${bpmn.kafka.checkpoint-topic:bpmn-checkpoints}",
            groupId = "${bpmn.kafka.checkpoint-consumer-group:bpmn-engine-checkpoint-consumer}",
            containerFactory = "checkpointKafkaListenerContainerFactory"
    )
    @Transactional
    public void onCheckpoint(CheckpointEventPayload payload) {
        if (payload == null) return;
        UUID instanceId = payload.instanceId();
        log.debug("Processing checkpoint instanceId={} eventType={}", instanceId, payload.eventType());
        log.trace("Checkpoint consume instanceId={} eventType={} stateType={} currentNodeId={} processDefinitionId={}",
                instanceId, payload.eventType(), payload.stateType(), payload.currentNodeId(), payload.processDefinitionId());

        ProcessState state = StateSerializer.fromPersistence(
                payload.stateType(),
                payload.currentNodeId(),
                payload.errorMessage(),
                instanceId
        );
        ConcurrentHashMap<String, Object> variables = payload.variablesJson() != null && !payload.variablesJson().isBlank()
                ? new ConcurrentHashMap<>(VariableSerializer.fromJson(payload.variablesJson()))
                : new ConcurrentHashMap<>();
        ProcessInstance instance = new ProcessInstance(
                instanceId,
                payload.processDefinitionId(),
                variables,
                state,
                payload.createdAt() != null ? payload.createdAt() : java.time.Instant.now(),
                payload.completedAt()
        );

        Map<String, Integer> plainTokens = payload.parallelJoinTokensJson() != null && !payload.parallelJoinTokensJson().isBlank()
                ? StateSerializer.parallelJoinTokensFromJson(payload.parallelJoinTokensJson())
                : Map.of();
        Map<String, AtomicInteger> tokensForInstance = StateSerializer.toAtomicMap(plainTokens);
        Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens = Map.of(instanceId, tokensForInstance);

        log.trace("Checkpoint persisted instanceId={} eventType={}", instanceId, payload.eventType());
        instanceStorage.save(instance, parallelJoinTokens);
        instanceStorage.saveEvent(
                instanceId,
                payload.eventType(),
                payload.currentNodeId(),
                Map.copyOf(instance.variables()),
                plainTokens,
                payload.eventCreatedAt() != null ? payload.eventCreatedAt() : java.time.Instant.now()
        );

        List<CheckpointEventPayload.TaskExecutionRecordDto> records = payload.taskExecutionRecords();
        if (records != null && !records.isEmpty()) {
            List<ProcessInstanceStorage.TaskExecutionRecord> taskRecords = records.stream()
                    .map(CheckpointEventPayload.TaskExecutionRecordDto::toRecord)
                    .toList();
            instanceStorage.saveTaskExecutions(instanceId, taskRecords);
        }
    }
}
