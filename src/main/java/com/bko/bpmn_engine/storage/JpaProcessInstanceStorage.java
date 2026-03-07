package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.model.ProcessState;
import com.bko.bpmn_engine.storage.entity.ProcessInstanceEntity;
import com.bko.bpmn_engine.storage.entity.ProcessInstanceEventEntity;
import com.bko.bpmn_engine.storage.entity.ProcessInstanceHistoryEntity;
import com.bko.bpmn_engine.storage.entity.TaskExecutionEntity;
import com.bko.bpmn_engine.storage.repository.ProcessInstanceEventRepository;
import com.bko.bpmn_engine.storage.repository.ProcessInstanceHistoryRepository;
import com.bko.bpmn_engine.storage.repository.ProcessInstanceRepository;
import com.bko.bpmn_engine.storage.repository.TaskExecutionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * JPA persistence for process instances. Synchronous writes at checkpoint boundaries.
 */
@Component
@Profile("persistence")
public class JpaProcessInstanceStorage implements ProcessInstanceStorage {

    private static final int DEFAULT_PAGE_SIZE = 50;
    private static final int MAX_PAGE_SIZE = 100;

    private final ProcessInstanceRepository instanceRepository;
    private final ProcessInstanceHistoryRepository historyRepository;
    private final ProcessInstanceEventRepository eventRepository;
    private final TaskExecutionRepository taskExecutionRepository;

    public JpaProcessInstanceStorage(ProcessInstanceRepository instanceRepository,
                                    ProcessInstanceHistoryRepository historyRepository,
                                    ProcessInstanceEventRepository eventRepository,
                                    TaskExecutionRepository taskExecutionRepository) {
        this.instanceRepository = instanceRepository;
        this.historyRepository = historyRepository;
        this.eventRepository = eventRepository;
        this.taskExecutionRepository = taskExecutionRepository;
    }

    @Override
    @Transactional
    public void save(ProcessInstance instance, Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens) {
        String stateType = StateSerializer.stateType(instance.state());
        String variablesJson = VariableSerializer.toJson(Map.copyOf(instance.variables()));
        String tokensJson = parallelJoinTokensToJson(parallelJoinTokens, instance.instanceId());

        if ("COMPLETED".equals(stateType) || "FAILED".equals(stateType)) {
            // Move to history and remove from active
            ProcessInstanceHistoryEntity historyEntity = new ProcessInstanceHistoryEntity(
                    instance.instanceId(),
                    instance.processDefinitionId(),
                    stateType,
                    StateSerializer.currentNodeId(instance.state()),
                    StateSerializer.errorMessage(instance.state()),
                    variablesJson,
                    tokensJson,
                    instance.createdAt(),
                    instance.completedAt()
            );
            historyRepository.save(historyEntity);
            instanceRepository.deleteById(instance.instanceId());
        } else {
            // Upsert active only
            ProcessInstanceEntity entity = instanceRepository.findById(instance.instanceId()).orElse(null);
            if (entity == null) {
                entity = new ProcessInstanceEntity(
                        instance.instanceId(),
                        instance.processDefinitionId(),
                        stateType,
                        StateSerializer.currentNodeId(instance.state()),
                        StateSerializer.errorMessage(instance.state()),
                        variablesJson,
                        tokensJson,
                        instance.createdAt(),
                        instance.completedAt()
                );
            } else {
                entity.setState(stateType);
                entity.setCurrentNodeId(StateSerializer.currentNodeId(instance.state()));
                entity.setErrorMessage(StateSerializer.errorMessage(instance.state()));
                entity.setVariablesJson(variablesJson);
                entity.setParallelJoinTokensJson(tokensJson);
                entity.setCompletedAt(instance.completedAt());
            }
            instanceRepository.save(entity);
        }
    }

    @Override
    @Transactional
    public void saveEvent(UUID instanceId, String eventType, String currentNodeId,
                          Map<String, Object> variables, Map<String, Integer> parallelJoinTokens,
                          Instant createdAt) {
        String variablesJson = variables != null ? VariableSerializer.toJson(variables) : "{}";
        String tokensJson = parallelJoinTokens != null && !parallelJoinTokens.isEmpty()
                ? serializeTokens(parallelJoinTokens) : "{}";

        ProcessInstanceEventEntity event = new ProcessInstanceEventEntity(
                instanceId, eventType, currentNodeId, variablesJson, tokensJson, createdAt
        );
        eventRepository.save(event);
    }

    @Override
    @Transactional
    public void saveTaskExecutions(UUID instanceId, List<TaskExecutionRecord> records) {
        if (records == null || records.isEmpty()) {
            return;
        }
        for (TaskExecutionRecord r : records) {
            TaskExecutionEntity entity = new TaskExecutionEntity(
                    instanceId, r.taskId(), r.taskType(),
                    r.startedAt(), r.completedAt(), r.durationMs()
            );
            taskExecutionRepository.save(entity);
        }
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecoveredInstance> findAllActive() {
        List<ProcessInstanceEntity> entities = instanceRepository.findByStateIn(
                List.of("CREATED", "ACTIVE", "COMPLETING")
        );
        return entities.stream()
                .map(this::toRecoveredInstance)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecoveredInstance findById(UUID instanceId) {
        return instanceRepository.findById(instanceId)
                .map(this::toRecoveredInstance)
                .or(() -> historyRepository.findById(instanceId).map(this::historyToRecoveredInstance))
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecoveredInstance> findAll() {
        return findAllPage(1, DEFAULT_PAGE_SIZE).instances();
    }

    @Override
    @Transactional(readOnly = true)
    public InstancePage findAllPage(int page, int size) {
        int safeSize = Math.min(Math.max(1, size), MAX_PAGE_SIZE);
        int safePage = Math.max(1, page);

        List<ProcessInstanceEntity> activeEntities = instanceRepository.findByStateIn(
                List.of("CREATED", "ACTIVE", "COMPLETING")
        );
        List<RecoveredInstance> activeList = activeEntities.stream()
                .map(this::toRecoveredInstance)
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
        int activeCount = activeList.size();

        long historyTotal = historyRepository.count();
        int startIdx = (safePage - 1) * safeSize;
        int endIdx = safePage * safeSize;

        List<RecoveredInstance> pageInstances = new ArrayList<>();
        if (startIdx < activeCount) {
            int activeEnd = Math.min(activeCount, endIdx);
            pageInstances.addAll(activeList.subList(startIdx, activeEnd));
        }
        int fromHistory = endIdx - startIdx - pageInstances.size();
        if (fromHistory > 0 && historyTotal > 0) {
            int historyOffset = Math.max(0, startIdx - activeCount);
            int historyPageIndex = historyOffset / safeSize;
            int skipInPage = historyOffset % safeSize;
            var historyPage = historyRepository.findAllByOrderByCompletedAtDesc(
                    PageRequest.of(historyPageIndex, safeSize)
            );
            historyPage.getContent().stream()
                    .skip(skipInPage)
                    .limit(fromHistory)
                    .map(this::historyToRecoveredInstance)
                    .forEach(pageInstances::add);
        }

        long totalCount = activeCount + historyTotal;
        boolean hasMore = endIdx < totalCount;

        return new InstancePage(pageInstances, safePage, safeSize, totalCount, hasMore);
    }

    private RecoveredInstance toRecoveredInstance(ProcessInstanceEntity entity) {
        ProcessState state = StateSerializer.fromPersistence(
                entity.getState(),
                entity.getCurrentNodeId(),
                entity.getErrorMessage(),
                entity.getInstanceId()
        );
        Map<String, Object> variables = VariableSerializer.fromJson(entity.getVariablesJson());
        Map<String, Integer> tokens = StateSerializer.parallelJoinTokensFromJson(entity.getParallelJoinTokensJson());
        return new RecoveredInstance(
                entity.getInstanceId(),
                entity.getProcessDefinitionId(),
                state,
                entity.getCurrentNodeId(),
                variables,
                tokens,
                entity.getCreatedAt(),
                entity.getCompletedAt()
        );
    }

    private RecoveredInstance historyToRecoveredInstance(ProcessInstanceHistoryEntity entity) {
        ProcessState state = StateSerializer.fromPersistence(
                entity.getState(),
                entity.getCurrentNodeId(),
                entity.getErrorMessage(),
                entity.getInstanceId()
        );
        Map<String, Object> variables = VariableSerializer.fromJson(entity.getVariablesJson());
        Map<String, Integer> tokens = StateSerializer.parallelJoinTokensFromJson(entity.getParallelJoinTokensJson());
        return new RecoveredInstance(
                entity.getInstanceId(),
                entity.getProcessDefinitionId(),
                state,
                entity.getCurrentNodeId(),
                variables,
                tokens,
                entity.getCreatedAt(),
                entity.getCompletedAt()
        );
    }

    private static String parallelJoinTokensToJson(Map<UUID, Map<String, AtomicInteger>> tokens, UUID instanceId) {
        Map<String, AtomicInteger> inner = tokens.get(instanceId);
        if (inner == null || inner.isEmpty()) {
            return "{}";
        }
        Map<String, Integer> plain = new java.util.HashMap<>();
        inner.forEach((k, v) -> plain.put(k, v.get()));
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(plain);
        } catch (Exception e) {
            return "{}";
        }
    }

    private static String serializeTokens(Map<String, Integer> tokens) {
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(tokens);
        } catch (Exception e) {
            return "{}";
        }
    }
}
