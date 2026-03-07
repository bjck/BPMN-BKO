package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.storage.VariableSerializer;
import com.bko.bpmn_engine.storage.entity.ProcessInstanceEventEntity;
import com.bko.bpmn_engine.storage.entity.TaskExecutionEntity;
import com.bko.bpmn_engine.storage.repository.ProcessInstanceEventRepository;
import com.bko.bpmn_engine.storage.repository.TaskExecutionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * REST API for inspecting persisted process history.
 * Only available when persistence profile is active.
 */
@RestController
@RequestMapping("/v1")
@Profile("persistence")
public class ProcessHistoryController {

    private final ProcessInstanceEventRepository eventRepository;
    private final TaskExecutionRepository taskExecutionRepository;

    public ProcessHistoryController(ProcessInstanceEventRepository eventRepository,
                                    TaskExecutionRepository taskExecutionRepository) {
        this.eventRepository = eventRepository;
        this.taskExecutionRepository = taskExecutionRepository;
    }

    @GetMapping("/process-instances/{instanceId}/history")
    public ResponseEntity<ProcessHistoryResponse> getProcessHistory(@PathVariable UUID instanceId) {
        List<ProcessInstanceEventEntity> events = eventRepository.findByInstanceIdOrderByCreatedAtAsc(instanceId);
        List<TaskExecutionEntity> tasks = taskExecutionRepository.findByInstanceIdOrderByStartedAtAsc(instanceId);

        if (events.isEmpty() && tasks.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        List<EventDto> eventDtos = events.stream()
                .map(e -> new EventDto(
                        e.getEventType(),
                        e.getCurrentNodeId(),
                        VariableSerializer.fromJson(e.getVariablesJson()),
                        e.getCreatedAt()
                ))
                .toList();

        List<TaskExecutionDto> taskDtos = tasks.stream()
                .map(t -> new TaskExecutionDto(
                        t.getTaskId(),
                        t.getTaskType(),
                        t.getStartedAt(),
                        t.getCompletedAt(),
                        t.getDurationMs()
                ))
                .toList();

        return ResponseEntity.ok(new ProcessHistoryResponse(instanceId, eventDtos, taskDtos));
    }

    public record ProcessHistoryResponse(
            UUID instanceId,
            List<EventDto> events,
            List<TaskExecutionDto> taskExecutions
    ) {}

    public record EventDto(
            String eventType,
            String currentNodeId,
            Map<String, Object> variables,
            java.time.Instant createdAt
    ) {}

    public record TaskExecutionDto(
            String taskId,
            String taskType,
            java.time.Instant startedAt,
            java.time.Instant completedAt,
            long durationMs
    ) {}
}
