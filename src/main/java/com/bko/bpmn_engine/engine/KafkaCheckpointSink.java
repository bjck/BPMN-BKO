package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.engine.kafka.CheckpointEventPayload;
import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import com.bko.bpmn_engine.storage.StateSerializer;
import com.bko.bpmn_engine.storage.VariableSerializer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Checkpoint sink that publishes to the checkpoint Kafka topic and waits for ack.
 * Does not call the database; a separate consumer writes to JPA (eventually consistent).
 */
public class KafkaCheckpointSink implements CheckpointSink {

    private static final Logger log = LoggerFactory.getLogger(KafkaCheckpointSink.class);
    /** Max time to wait for Kafka ack on the hot path. */
    private static final int SEND_TIMEOUT_SECONDS = 10;

    private final KafkaTemplate<String, CheckpointEventPayload> checkpointKafkaTemplate;
    private final String checkpointTopic;

    public KafkaCheckpointSink(KafkaTemplate<String, CheckpointEventPayload> checkpointKafkaTemplate,
                               String checkpointTopic) {
        this.checkpointKafkaTemplate = checkpointKafkaTemplate;
        this.checkpointTopic = checkpointTopic;
    }

    @Override
    public void checkpoint(ProcessInstance instance, String eventType, String currentNodeId,
                           Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens,
                           List<ProcessInstanceStorage.TaskExecutionRecord> taskExecutionRecords,
                           Instant eventCreatedAt) {
        String stateType = StateSerializer.stateType(instance.state());
        String nodeId;
        if (currentNodeId != null) {
            nodeId = currentNodeId;
        } else {
            nodeId = instance.state() instanceof com.bko.bpmn_engine.model.Active a ? a.currentNodeId() : null;
        }
        String errorMessage = instance.state() instanceof com.bko.bpmn_engine.model.Failed f ? f.errorMessage() : null;
        String variablesJson = VariableSerializer.toJson(Map.copyOf(instance.variables()));
        String parallelJoinTokensJson = StateSerializer.parallelJoinTokensToJson(parallelJoinTokens, instance.instanceId());

        List<CheckpointEventPayload.TaskExecutionRecordDto> records = taskExecutionRecords == null ? List.of()
                : taskExecutionRecords.stream()
                .map(CheckpointEventPayload.TaskExecutionRecordDto::from)
                .toList();

        CheckpointEventPayload payload = new CheckpointEventPayload(
                instance.instanceId(),
                instance.processDefinitionId(),
                stateType,
                nodeId,
                errorMessage,
                variablesJson,
                parallelJoinTokensJson,
                instance.createdAt(),
                instance.completedAt(),
                eventType,
                eventCreatedAt,
                records.isEmpty() ? null : records
        );

        String key = instance.instanceId().toString();
        log.debug("Publishing checkpoint to {} key={} eventType={}", checkpointTopic, key, eventType);
        log.trace("Checkpoint publish topic={} instanceId={} eventType={} stateType={} currentNodeId={}",
                checkpointTopic, instance.instanceId(), eventType, stateType, nodeId);
        try {
            SendResult<String, CheckpointEventPayload> result =
                    checkpointKafkaTemplate.send(checkpointTopic, key, payload)
                            .get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
            if (log.isTraceEnabled()) {
                log.trace("Checkpoint sent partition={} offset={}", result.getRecordMetadata().partition(), result.getRecordMetadata().offset());
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CheckpointPublishException("Failed to publish checkpoint for instance " + instance.instanceId(), e);
        } catch (Exception e) {
            throw new CheckpointPublishException("Failed to publish checkpoint for instance " + instance.instanceId(), e);
        }
    }
}
