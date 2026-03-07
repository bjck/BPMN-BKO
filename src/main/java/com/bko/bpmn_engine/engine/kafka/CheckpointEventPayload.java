package com.bko.bpmn_engine.engine.kafka;

import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Payload for checkpoint events published to the checkpoint Kafka topic.
 * Carries everything the checkpoint consumer needs to call ProcessInstanceStorage.
 * Key = instanceId.toString() for ordering per instance.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record CheckpointEventPayload(
        UUID instanceId,
        String processDefinitionId,
        String stateType,
        String currentNodeId,
        String errorMessage,
        String variablesJson,
        String parallelJoinTokensJson,
        Instant createdAt,
        Instant completedAt,
        String eventType,
        Instant eventCreatedAt,
        List<TaskExecutionRecordDto> taskExecutionRecords
) {
    /** Same shape as ProcessInstanceStorage.TaskExecutionRecord for JSON (de)serialization. */
    public record TaskExecutionRecordDto(
            String taskId,
            String taskType,
            Instant startedAt,
            Instant completedAt,
            long durationMs
    ) {
        public static TaskExecutionRecordDto from(ProcessInstanceStorage.TaskExecutionRecord r) {
            return new TaskExecutionRecordDto(
                    r.taskId(), r.taskType(), r.startedAt(), r.completedAt(), r.durationMs()
            );
        }

        public ProcessInstanceStorage.TaskExecutionRecord toRecord() {
            return new ProcessInstanceStorage.TaskExecutionRecord(
                    taskId, taskType, startedAt, completedAt, durationMs
            );
        }
    }
}
