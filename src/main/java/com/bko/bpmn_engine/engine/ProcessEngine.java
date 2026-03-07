package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.api.exception.IllegalStateTransitionException;
import com.bko.bpmn_engine.api.exception.ProcessNotFoundException;
import com.bko.bpmn_engine.engine.event.*;
import com.bko.bpmn_engine.engine.kafka.BpmnEventPayload;
import com.bko.bpmn_engine.engine.kafka.BpmnEventPublisher;
import com.bko.bpmn_engine.model.*;
import com.bko.bpmn_engine.parser.BpmnParseException;
import com.bko.bpmn_engine.parser.BpmnParser;
import com.bko.bpmn_engine.storage.ProcessDefinitionStorage;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Core BPMN process execution engine.
 * Deploys processes, creates instances, and executes flow nodes inline on the current thread.
 * Persists at checkpoint boundaries (create, UserTask, complete) when storage is configured.
 */
@Service
public class ProcessEngine {

    private final BpmnParser parser;
    private final ApplicationEventPublisher eventPublisher;
    private final ProcessInstanceStorage instanceStorage;
    private final ProcessDefinitionStorage definitionStorage;
    private final ServiceTaskLogicRegistry serviceTaskLogicRegistry;
    private final RestTaskExecutor restTaskExecutor = new RestTaskExecutor();
    private final KafkaTaskExecutor kafkaTaskExecutor;

    private final Map<String, CompiledProcess> deployedProcesses = new ConcurrentHashMap<>();
    private final Map<String, String> bpmnXmlByDefinitionId = new ConcurrentHashMap<>();
    private final Map<UUID, ProcessInstance> activeInstances = new ConcurrentHashMap<>();
    private final Map<UUID, ProcessInstance> completedInstances = new ConcurrentHashMap<>();
    private final Map<String, TaskWorker> workers = new ConcurrentHashMap<>();
    private final Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens = new ConcurrentHashMap<>();
    private final Map<UUID, List<ProcessInstanceStorage.TaskExecutionRecord>> pendingTaskExecutions = new ConcurrentHashMap<>();
    /** messageRef -> list of (instanceId, nodeId, correlationKey) for catch events */
    private final Map<String, List<MessageSubscription>> messageSubscriptions = new ConcurrentHashMap<>();
    /** messageRef -> process definition ids that have message start (for triggerMessageStart) */
    private final Map<String, List<String>> processDefinitionIdsByMessageRef = new ConcurrentHashMap<>();

    private final BpmnEventPublisher bpmnEventPublisher;
    private final CheckpointSink checkpointSink;

    @Autowired
    public ProcessEngine(BpmnParser parser, ApplicationEventPublisher eventPublisher,
                         @Autowired(required = false) ProcessInstanceStorage instanceStorage,
                         @Autowired(required = false) ProcessDefinitionStorage definitionStorage,
                         @Autowired(required = false) ServiceTaskLogicRegistry serviceTaskLogicRegistry,
                         @Autowired(required = false) BpmnEventPublisher bpmnEventPublisher,
                         @Autowired(required = false) KafkaTaskExecutor kafkaTaskExecutor,
                         @Autowired(required = false) CheckpointSink checkpointSink) {
        this.parser = parser;
        this.eventPublisher = eventPublisher;
        this.instanceStorage = instanceStorage;
        this.definitionStorage = definitionStorage;
        this.serviceTaskLogicRegistry = serviceTaskLogicRegistry;
        this.bpmnEventPublisher = bpmnEventPublisher;
        this.kafkaTaskExecutor = kafkaTaskExecutor;
        this.checkpointSink = checkpointSink;
    }

    private record MessageSubscription(UUID instanceId, String nodeId, String correlationKey) {}

    /**
     * Deploy a process from BPMN XML.
     *
     * @param bpmnXml BPMN 2.0 XML string
     * @return process definition id
     */
    public String deployProcess(String bpmnXml) throws BpmnParseException {
        CompiledProcess compiled = parser.parse(bpmnXml);
        String definitionId = compiled.definition().id();
        deployedProcesses.put(definitionId, compiled);
        bpmnXmlByDefinitionId.put(definitionId, bpmnXml);
        FlowNode startNode = compiled.definition().nodes().get(compiled.definition().startNodeId());
        if (startNode instanceof StartEvent start && start.trigger() == StartEventTrigger.MESSAGE && start.messageRef() != null) {
            processDefinitionIdsByMessageRef
                    .computeIfAbsent(start.messageRef(), k -> new CopyOnWriteArrayList<>())
                    .add(definitionId);
        }
        if (definitionStorage != null) {
            definitionStorage.save(definitionId, bpmnXml);
        }
        return definitionId;
    }

    /**
     * Returns BPMN 2.0 XML for a deployed process, or null if not found.
     * If the stored XML has no diagram edges (BPMNEdge), returns a version with generated
     * diagram interchange so the viewer can render sequence flow arrows.
     */
    public String getBpmnXml(String processDefinitionId) {
        String xml = bpmnXmlByDefinitionId.get(processDefinitionId);
        if (xml == null && definitionStorage != null) {
            xml = definitionStorage.findBpmnXmlById(processDefinitionId);
        }
        if (xml == null) return null;
        if (hasDiagramEdges(xml)) return xml;
        CompiledProcess compiled = deployedProcesses.get(processDefinitionId);
        if (compiled != null) {
            return parser.serializeWithDiagram(compiled.definition());
        }
        return xml;
    }

    private static boolean hasDiagramEdges(String bpmnXml) {
        return bpmnXml.contains("BPMNEdge") || bpmnXml.contains("bpmnEdge");
    }

    /**
     * Create a new process instance and execute from the start event.
     *
     * @param processDefinitionId deployed process id
     * @param variables          initial variables (copied into instance)
     * @return the created instance (may already be completed)
     */
    public ProcessInstance createInstance(String processDefinitionId, Map<String, Object> variables) {
        CompiledProcess compiled = deployedProcesses.get(processDefinitionId);
        if (compiled == null) {
            throw new ProcessNotFoundException("Process not found: " + processDefinitionId);
        }

        UUID instanceId = UUID.randomUUID();
        ConcurrentHashMap<String, Object> vars = new ConcurrentHashMap<>();
        if (variables != null) {
            vars.putAll(variables);
        }

        ProcessInstance instance = new ProcessInstance(
                instanceId,
                processDefinitionId,
                vars,
                new Created(instanceId),
                Instant.now(),
                null
        );

        eventPublisher.publishEvent(new ProcessInstanceCreatedEvent(this, instanceId, processDefinitionId));

        ProcessInstance active = transitionTo(instance, new Active(instanceId, compiled.definition().startNodeId()));
        activeInstances.put(instanceId, active);

        persistCheckpoint(active, "CREATED");

        StartEvent startEvent = compiled.definition().nodes().get(compiled.definition().startNodeId()) instanceof StartEvent s ? s : null;
        if (startEvent == null || startEvent.trigger() == StartEventTrigger.NONE) {
            executeFrom(active, compiled.definition().startNodeId());
        }

        return activeInstances.getOrDefault(instanceId, completedInstances.getOrDefault(instanceId, active));
    }

    /**
     * Start a process instance by message (message start event). Creates instance and executes from start.
     */
    public ProcessInstance triggerMessageStart(String processDefinitionId, String messageRef, String correlationKey, Map<String, Object> variables) {
        CompiledProcess compiled = deployedProcesses.get(processDefinitionId);
        if (compiled == null) {
            throw new ProcessNotFoundException("Process not found: " + processDefinitionId);
        }
        FlowNode startNode = compiled.definition().nodes().get(compiled.definition().startNodeId());
        if (!(startNode instanceof StartEvent start) || start.trigger() != StartEventTrigger.MESSAGE) {
            throw new IllegalArgumentException("Process does not have a message start event: " + processDefinitionId);
        }
        UUID instanceId = UUID.randomUUID();
        ConcurrentHashMap<String, Object> vars = new ConcurrentHashMap<>();
        if (variables != null) vars.putAll(variables);
        if (correlationKey != null) vars.put("correlationKey", correlationKey);

        ProcessInstance instance = new ProcessInstance(
                instanceId, processDefinitionId, vars,
                new Created(instanceId), Instant.now(), null);
        eventPublisher.publishEvent(new ProcessInstanceCreatedEvent(this, instanceId, processDefinitionId));
        ProcessInstance active = transitionTo(instance, new Active(instanceId, compiled.definition().startNodeId()));
        activeInstances.put(instanceId, active);
        persistCheckpoint(active, "CREATED");
        executeFrom(active, compiled.definition().startNodeId());
        return activeInstances.getOrDefault(instanceId, completedInstances.getOrDefault(instanceId, active));
    }

    /**
     * Return process definition ids that have a message start with the given messageRef.
     */
    public List<String> getProcessDefinitionIdsByMessageRef(String messageRef) {
        List<String> ids = processDefinitionIdsByMessageRef.get(messageRef);
        return ids != null ? List.copyOf(ids) : List.of();
    }

    /**
     * Trigger a catch event: merge variables and advance the instance from the given node.
     */
    public void triggerCatchEvent(UUID instanceId, String nodeId, Map<String, Object> variables) {
        ProcessInstance instance = activeInstances.get(instanceId);
        if (instance == null) {
            throw new ProcessNotFoundException("Instance not found: " + instanceId);
        }
        if (variables != null) variables.forEach(instance.variables()::put);
        removeSubscription(instanceId, nodeId);
        CompiledProcess compiled = deployedProcesses.get(instance.processDefinitionId());
        if (compiled == null) return;
        List<String> next = compiled.adjacency().get(nodeId);
        if (next != null && !next.isEmpty()) {
            advanceAndExecute(instance, next.getFirst(), compiled);
        }
    }

    /**
     * Trigger catch event by messageRef (and optional correlationKey). Finds a waiting subscription and triggers it.
     */
    public void triggerCatchEventByMessageRef(String messageRef, String correlationKey, Map<String, Object> variables) {
        List<MessageSubscription> list = messageSubscriptions.get(messageRef);
        if (list == null || list.isEmpty()) return;
        MessageSubscription sub = list.stream()
                .filter(s -> correlationKey == null || correlationKey.equals(s.correlationKey()))
                .findFirst()
                .orElse(list.getFirst());
        list.remove(sub);
        triggerCatchEvent(sub.instanceId(), sub.nodeId(), variables);
    }

    private void removeSubscription(UUID instanceId, String nodeId) {
        messageSubscriptions.values().forEach(list -> list.removeIf(s -> s.instanceId().equals(instanceId) && s.nodeId().equals(nodeId)));
    }

    /**
     * Execute from the given node. Uses pattern matching on FlowNode type.
     */
    public void executeFrom(ProcessInstance instance, String nodeId) {
        CompiledProcess compiled = deployedProcesses.get(instance.processDefinitionId());
        if (compiled == null) return;

        ProcessDefinition def = compiled.definition();
        FlowNode node = def.nodes().get(nodeId);
        if (node == null) return;

        ProcessInstance current = getCurrentInstance(instance.instanceId());
        if (current == null) return;

        switch (node) {
            case StartEvent start -> {
                if (start.trigger() == StartEventTrigger.NONE || start.trigger() == StartEventTrigger.MESSAGE) {
                    List<String> next = compiled.adjacency().get(nodeId);
                    if (next != null && !next.isEmpty()) {
                        advanceAndExecute(current, next.getFirst(), compiled);
                    }
                }
                // TIMER: advance when timer fires (scheduled or external trigger)
            }
            case EndEvent end -> {
                if (bpmnEventPublisher != null) {
                    String correlationKey = current.variables().get("correlationKey") != null ? String.valueOf(current.variables().get("correlationKey")) : null;
                    if (end.endType() == EndEventType.MESSAGE && end.messageRef() != null) {
                        bpmnEventPublisher.publish(BpmnEventPayload.forThrow(end.messageRef(), null, correlationKey, current.instanceId(), nodeId, Map.copyOf(current.variables())));
                    } else if (end.endType() == EndEventType.ERROR && end.errorCode() != null) {
                        bpmnEventPublisher.publish(BpmnEventPayload.forError(end.errorCode(), correlationKey, current.instanceId(), nodeId, Map.copyOf(current.variables())));
                    }
                }
                long durationMs = java.time.Duration.between(current.createdAt(), Instant.now()).toMillis();
                ProcessInstance completing = transitionTo(current, new Completing(current.instanceId()));
                updateInstance(completing);
                Instant now = Instant.now();
                ProcessInstance completed = transitionTo(completing, new Completed(current.instanceId()), now);
                persistCheckpoint(completed, "COMPLETED");
                completedInstances.put(current.instanceId(), completed);
                activeInstances.remove(current.instanceId());
                parallelJoinTokens.remove(current.instanceId());
                pendingTaskExecutions.remove(current.instanceId());
                messageSubscriptions.values().forEach(list -> list.removeIf(s -> s.instanceId().equals(current.instanceId())));
                eventPublisher.publishEvent(new ProcessInstanceCompletedEvent(this, current.instanceId(), durationMs));
            }
            case ServiceTask st -> {
                List<String> chain = findChainContaining(compiled, nodeId);
                if (chain != null) {
                    executeSequentialChain(getCurrentInstance(instance.instanceId()), chain, compiled);
                } else {
                    executeServiceTask(getCurrentInstance(instance.instanceId()), st, compiled);
                }
            }
            case ExclusiveGateway ex -> {
                String targetId = selectConditionalBranch(ex.id(), ex.defaultFlow(), def, current.variables());
                if (targetId != null) {
                    advanceAndExecute(current, targetId, compiled);
                }
            }
            case ParallelGateway pg -> {
                if (pg.incoming().size() > 1) {
                    int arrived = parallelJoinTokens
                            .computeIfAbsent(current.instanceId(), k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(nodeId, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    if (arrived >= pg.incoming().size()) {
                        List<String> next = compiled.adjacency().get(nodeId);
                        if (next != null && !next.isEmpty()) {
                            advanceAndExecute(current, next.getFirst(), compiled);
                        }
                    }
                } else {
                    for (String nextId : compiled.adjacency().getOrDefault(nodeId, List.of())) {
                        executeFrom(getCurrentInstance(instance.instanceId()), nextId);
                    }
                }
            }
            case InclusiveGateway inc -> {
                if (inc.incoming().size() > 1) {
                    // Join: wait for tokens. Simplified: fire when at least one token has arrived.
                    int arrived = parallelJoinTokens
                            .computeIfAbsent(current.instanceId(), k -> new ConcurrentHashMap<>())
                            .computeIfAbsent(nodeId, k -> new AtomicInteger(0))
                            .incrementAndGet();
                    int expected = current.variables().get("__inclusive_expected_" + nodeId) instanceof Number n
                        ? n.intValue() : 1;
                    if (arrived >= expected) {
                        List<String> next = compiled.adjacency().get(nodeId);
                        if (next != null && !next.isEmpty()) {
                            advanceAndExecute(current, next.getFirst(), compiled);
                        }
                    }
                } else {
                    List<String> targetIds = selectInclusiveBranches(inc, def, current.variables());
                    for (String targetId : targetIds) {
                        advanceAndExecute(current, targetId, compiled);
                    }
                }
            }
            case ComplexGateway cg -> {
                List<String> targetIds = selectComplexBranches(cg, def, current.variables());
                for (String targetId : targetIds) {
                    advanceAndExecute(current, targetId, compiled);
                }
            }
            case EventBasedGateway ev -> {
                String targetId = selectConditionalBranch(ev.id(), ev.defaultFlow(), def, current.variables());
                if (targetId != null) {
                    advanceAndExecute(current, targetId, compiled);
                }
            }
            case UserTask ignored -> {
                persistCheckpoint(current, "USER_TASK_REACHED");
                // UserTask blocks until completeTask is called
            }
            case IntermediateCatchEvent ice -> {
                if (ice.catchType() == CatchEventType.MESSAGE && ice.messageRef() != null) {
                    String correlationKey = current.variables().get("correlationKey") != null ? String.valueOf(current.variables().get("correlationKey")) : null;
                    messageSubscriptions
                            .computeIfAbsent(ice.messageRef(), k -> new CopyOnWriteArrayList<>())
                            .add(new MessageSubscription(current.instanceId(), nodeId, correlationKey));
                    persistCheckpoint(current, "MESSAGE_CATCH_WAITING");
                } else if (ice.catchType() == CatchEventType.TIMER && ice.timerDefinition() != null) {
                    long delayMs = parseTimerToMillis(ice.timerDefinition());
                    if (delayMs >= 0) {
                        UUID instId = current.instanceId();
                        String nId = nodeId;
                        java.util.concurrent.ScheduledExecutorService scheduler = getTimerScheduler();
                        scheduler.schedule(() -> triggerCatchEvent(instId, nId, Map.of()), delayMs, java.util.concurrent.TimeUnit.MILLISECONDS);
                    } else {
                        String correlationKey = current.variables().get("correlationKey") != null ? String.valueOf(current.variables().get("correlationKey")) : null;
                        messageSubscriptions
                                .computeIfAbsent("__timer__" + nodeId, k -> new CopyOnWriteArrayList<>())
                                .add(new MessageSubscription(current.instanceId(), nodeId, correlationKey));
                        persistCheckpoint(current, "TIMER_CATCH_WAITING");
                    }
                } else {
                    List<String> next = compiled.adjacency().get(nodeId);
                    if (next != null && !next.isEmpty()) advanceAndExecute(current, next.getFirst(), compiled);
                }
            }
            case IntermediateThrowEvent ite -> {
                if (bpmnEventPublisher != null) {
                    String correlationKey = current.variables().get("correlationKey") != null ? String.valueOf(current.variables().get("correlationKey")) : null;
                    if (ite.throwType() == ThrowEventType.MESSAGE && ite.messageRef() != null) {
                        bpmnEventPublisher.publish(BpmnEventPayload.forThrow(ite.messageRef(), null, correlationKey, current.instanceId(), nodeId, Map.copyOf(current.variables())));
                    } else if (ite.throwType() == ThrowEventType.SIGNAL && ite.signalRef() != null) {
                        bpmnEventPublisher.publish(BpmnEventPayload.forThrow(null, ite.signalRef(), correlationKey, current.instanceId(), nodeId, Map.copyOf(current.variables())));
                    }
                }
                List<String> next = compiled.adjacency().get(nodeId);
                if (next != null && !next.isEmpty()) advanceAndExecute(current, next.getFirst(), compiled);
            }
        }
    }

    private static java.util.concurrent.ScheduledExecutorService timerScheduler;

    private static synchronized java.util.concurrent.ScheduledExecutorService getTimerScheduler() {
        if (timerScheduler == null) {
            timerScheduler = java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "bpmn-timer-catch");
                t.setDaemon(true);
                return t;
            });
        }
        return timerScheduler;
    }

    /** Parse ISO 8601 duration (e.g. PT5M, PT30S) or plain seconds to millis. Returns -1 if not parseable. */
    private static long parseTimerToMillis(String timerDefinition) {
        if (timerDefinition == null || timerDefinition.isBlank()) return -1;
        String s = timerDefinition.trim();
        try {
            if (s.startsWith("PT") && s.length() > 2) {
                java.time.Duration d = java.time.Duration.parse("P" + s.substring(1));
                return d.toMillis();
            }
            return Long.parseLong(s) * 1000L;
        } catch (Exception e) {
            return -1;
        }
    }

    /**
     * Execute a sequential chain of service tasks on the current thread without yielding.
     */
    public void executeSequentialChain(ProcessInstance instance, List<String> chainNodeIds, CompiledProcess compiled) {
        ProcessInstance current = getCurrentInstance(instance.instanceId());
        if (current == null) return;

        for (String taskId : chainNodeIds) {
            FlowNode node = compiled.definition().nodes().get(taskId);
            if (node instanceof ServiceTask st) {
                long startMs = System.currentTimeMillis();
                eventPublisher.publishEvent(new TaskActivatedEvent(this, current.instanceId(), taskId, st.implementation()));
                try {
                    applyTaskResult(current, st, executeTask(st, current));
                } catch (Exception e) {
                    failInstance(current, e);
                    throw e;
                }

                long durationMs = System.currentTimeMillis() - startMs;
                recordTaskExecution(current.instanceId(), taskId, st.implementation(), startMs, durationMs);
                eventPublisher.publishEvent(new TaskCompletedEvent(this, current.instanceId(), taskId, durationMs));
                // Checkpoint after each task in the chain (currentNodeId = task just completed)
                persistCheckpoint(current, "SERVICE_TASK_COMPLETED", taskId);
            }
        }

        String lastInChain = chainNodeIds.getLast();
        List<String> next = compiled.adjacency().get(lastInChain);
        if (next != null && !next.isEmpty()) {
            persistCheckpoint(current, "SERVICE_TASKS_COMPLETED");
            advanceAndExecute(current, next.getFirst(), compiled);
        }
    }

    /**
     * Complete a user task and advance execution.
     *
     * @param instanceId process instance id
     * @param taskId     user task node id
     * @param variables  variables to merge into instance
     * @return updated instance
     */
    public ProcessInstance completeTask(UUID instanceId, String taskId, Map<String, Object> variables) {
        ProcessInstance instance = activeInstances.get(instanceId);
        if (instance == null) {
            ProcessInstance completed = completedInstances.get(instanceId);
            if (completed != null) {
                throw new IllegalStateTransitionException("Instance already completed: " + instanceId);
            }
            throw new ProcessNotFoundException("Instance not found: " + instanceId);
        }

        if (variables != null) {
            variables.forEach(instance.variables()::put);
        }

        persistCheckpoint(instance, "USER_TASK_COMPLETED");

        CompiledProcess compiled = deployedProcesses.get(instance.processDefinitionId());
        if (compiled == null) return instance;

        List<String> next = compiled.adjacency().get(taskId);
        if (next != null && !next.isEmpty()) {
            advanceAndExecute(instance, next.getFirst(), compiled);
        }

        return activeInstances.getOrDefault(instanceId, instance);
    }

    public ProcessInstance getInstance(UUID instanceId) {
        ProcessInstance active = activeInstances.get(instanceId);
        if (active != null) return active;
        ProcessInstance completed = completedInstances.get(instanceId);
        if (completed != null) return completed;
        if (instanceStorage != null) {
            ProcessInstanceStorage.RecoveredInstance recovered = instanceStorage.findById(instanceId);
            if (recovered != null) {
                return toProcessInstance(recovered);
            }
        }
        return null;
    }

    private static ProcessInstance toProcessInstance(ProcessInstanceStorage.RecoveredInstance r) {
        ConcurrentHashMap<String, Object> vars = new ConcurrentHashMap<>(r.variables());
        return new ProcessInstance(
                r.instanceId(),
                r.processDefinitionId(),
                vars,
                r.state(),
                r.createdAt(),
                r.completedAt()
        );
    }

    /**
     * Cancel a running process instance.
     *
     * @param instanceId process instance id
     * @throws ProcessNotFoundException if instance not found or already completed
     */
    public void cancelInstance(UUID instanceId) {
        ProcessInstance instance = activeInstances.remove(instanceId);
        if (instance == null) {
            if (completedInstances.containsKey(instanceId)) {
                throw new IllegalStateTransitionException("Cannot cancel completed instance: " + instanceId);
            }
            throw new ProcessNotFoundException("Instance not found: " + instanceId);
        }
        parallelJoinTokens.remove(instanceId);
    }

    /**
     * Restart a failed process instance from the node it failed on.
     *
     * @param instanceId process instance id (must be in Failed state)
     * @return the process instance after restart (may have advanced or failed again)
     * @throws ProcessNotFoundException if instance not found or process definition not deployed
     * @throws IllegalStateTransitionException if instance is not in Failed state or failed-at node is missing
     */
    public ProcessInstance restartFailedInstance(UUID instanceId) {
        ProcessInstance instance = getInstance(instanceId);
        if (instance == null) {
            throw new ProcessNotFoundException("Instance not found: " + instanceId);
        }
        if (!(instance.state() instanceof Failed)) {
            throw new IllegalStateTransitionException("Instance is not failed; cannot restart: " + instanceId);
        }
        Object nodeIdObj = instance.variables().get("failedAtNodeId");
        if (!(nodeIdObj instanceof String nodeId) || nodeId.isBlank()) {
            throw new IllegalStateTransitionException("Cannot restart: failed-at node id is missing for instance: " + instanceId);
        }
        CompiledProcess compiled = deployedProcesses.get(instance.processDefinitionId());
        if (compiled == null) {
            throw new ProcessNotFoundException("Process definition not deployed: " + instance.processDefinitionId());
        }
        completedInstances.remove(instanceId);
        instance.variables().remove("errorMessage");
        instance.variables().remove("failedAtNodeId");
        ProcessInstance restarted = transitionTo(instance, new Active(instance.instanceId(), nodeId));
        persistCheckpoint(restarted, "RESTARTED");
        executeFrom(restarted, nodeId);
        return activeInstances.getOrDefault(instanceId, completedInstances.getOrDefault(instanceId, restarted));
    }

    /**
     * Returns the set of deployed process definition ids.
     */
    public Set<String> getDeployedProcessIds() {
        return Set.copyOf(deployedProcesses.keySet());
    }

    /**
     * Returns the count of active (running) process instances.
     */
    public int getActiveInstanceCount() {
        return activeInstances.size();
    }

    /**
     * Returns the pending user task id if the instance is waiting at a UserTask, null otherwise.
     */
    public String getPendingUserTaskId(ProcessInstance instance) {
        if (!(instance.state() instanceof Active active)) return null;
        CompiledProcess compiled = deployedProcesses.get(instance.processDefinitionId());
        if (compiled == null) return null;
        FlowNode node = compiled.definition().nodes().get(active.currentNodeId());
        return node instanceof UserTask ? node.id() : null;
    }

    /**
     * Returns all process instances (active and completed).
     * When persistence is enabled, includes instances from storage.
     */
    public List<ProcessInstance> getAllInstances() {
        if (instanceStorage != null) {
            return instanceStorage.findAll().stream()
                    .map(ProcessEngine::toProcessInstance)
                    .toList();
        }
        List<ProcessInstance> all = new ArrayList<>();
        all.addAll(activeInstances.values());
        all.addAll(completedInstances.values());
        return all.stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
    }

    /**
     * Returns one page of process instances (active first, then history by completed_at desc when persistence is enabled).
     * When persistence is disabled, returns a page of in-memory instances.
     */
    public InstancesPage getInstancesPage(int page, int size) {
        if (instanceStorage != null) {
            ProcessInstanceStorage.InstancePage pageResult = instanceStorage.findAllPage(page, size);
            return new InstancesPage(
                    pageResult.instances().stream().map(ProcessEngine::toProcessInstance).toList(),
                    pageResult.page(),
                    pageResult.pageSize(),
                    pageResult.totalCount(),
                    pageResult.hasMore()
            );
        }
        List<ProcessInstance> all = new ArrayList<>();
        all.addAll(activeInstances.values());
        all.addAll(completedInstances.values());
        List<ProcessInstance> sorted = all.stream()
                .sorted((a, b) -> b.createdAt().compareTo(a.createdAt()))
                .toList();
        int total = sorted.size();
        int safeSize = Math.min(Math.max(1, size), 100);
        int safePage = Math.max(1, page);
        int start = (safePage - 1) * safeSize;
        int end = Math.min(start + safeSize, total);
        List<ProcessInstance> pageList = start < total ? sorted.subList(start, end) : List.of();
        return new InstancesPage(pageList, safePage, safeSize, total, end < total);
    }

    /** One page of process instances with pagination info. */
    public record InstancesPage(
            List<ProcessInstance> instances,
            int page,
            int pageSize,
            long totalCount,
            boolean hasMore
    ) {}

    public void registerWorker(String taskImplementation, TaskWorker worker) {
        workers.put(taskImplementation, worker);
    }

    private ProcessInstance getCurrentInstance(UUID instanceId) {
        return activeInstances.get(instanceId);
    }

    private void updateInstance(ProcessInstance instance) {
        activeInstances.put(instance.instanceId(), instance);
    }

    private ProcessInstance transitionTo(ProcessInstance instance, ProcessState newState) {
        return transitionTo(instance, newState, null);
    }

    private ProcessInstance transitionTo(ProcessInstance instance, ProcessState newState, Instant completedAt) {
        ProcessInstance updated = new ProcessInstance(
                instance.instanceId(),
                instance.processDefinitionId(),
                instance.variables(),
                newState,
                instance.createdAt(),
                completedAt != null ? completedAt : instance.completedAt()
        );
        if (newState instanceof Active) {
            activeInstances.put(instance.instanceId(), updated);
        }
        return updated;
    }

    private void advanceAndExecute(ProcessInstance instance, String nextNodeId, CompiledProcess compiled) {
        ProcessInstance current = getCurrentInstance(instance.instanceId());
        if (current == null) return;

        ProcessInstance withNode = transitionTo(current, new Active(current.instanceId(), nextNodeId));
        updateInstance(withNode);

        executeFrom(withNode, nextNodeId);
    }

    private void executeServiceTask(ProcessInstance instance, ServiceTask st, CompiledProcess compiled) {
        ProcessInstance current = getCurrentInstance(instance.instanceId());
        if (current == null) return;

        long startMs = System.currentTimeMillis();
        eventPublisher.publishEvent(new TaskActivatedEvent(this, current.instanceId(), st.id(), st.implementation()));
        try {
            applyTaskResult(current, st, executeTask(st, current));
        } catch (Exception e) {
            failInstance(current, e);
            throw e;
        }

        long durationMs = System.currentTimeMillis() - startMs;
        recordTaskExecution(current.instanceId(), st.id(), st.implementation(), startMs, durationMs);
        eventPublisher.publishEvent(new TaskCompletedEvent(this, current.instanceId(), st.id(), durationMs));

        List<String> next = compiled.adjacency().get(st.id());
        if (next != null && !next.isEmpty()) {
            persistCheckpoint(current, "SERVICE_TASK_COMPLETED");
            advanceAndExecute(current, next.getFirst(), compiled);
        }
    }

    private void failInstance(ProcessInstance current, Exception e) {
        String message = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
        current.variables().put("errorMessage", message);
        if (current.state() instanceof Active a) {
            current.variables().put("failedAtNodeId", a.currentNodeId());
        }
        ProcessInstance failed = transitionTo(current, new Failed(current.instanceId(), message));
        activeInstances.remove(current.instanceId());
        completedInstances.put(current.instanceId(), failed);
        parallelJoinTokens.remove(current.instanceId());
        pendingTaskExecutions.remove(current.instanceId());
        messageSubscriptions.values().forEach(list -> list.removeIf(s -> s.instanceId().equals(current.instanceId())));
        persistCheckpoint(failed, "FAILED");
        eventPublisher.publishEvent(new ProcessInstanceFailedEvent(this, current.instanceId(), message));
    }

    private List<String> findChainContaining(CompiledProcess compiled, String nodeId) {
        for (List<String> chain : compiled.sequentialChains()) {
            if (chain.contains(nodeId)) {
                return chain;
            }
        }
        return null;
    }

    private Map<String, Object> executeTask(ServiceTask task, ProcessInstance instance) {
        if (task.taskType() == ServiceTaskType.REST) {
            Object result = restTaskExecutor.execute(task, Map.copyOf(instance.variables()));
            return wrapTaskResult(task, result);
        }

        if (task.taskType() == ServiceTaskType.BEAN) {
            return executeBeanTask(task, instance);
        }

        if (task.taskType() == ServiceTaskType.KAFKA) {
            if (kafkaTaskExecutor == null) {
                throw new IllegalStateException("Kafka service task requires Kafka to be enabled (bpmn.kafka.enabled=true) and bpmnServiceTaskKafkaTemplate: " + task.id());
            }
            Object result = kafkaTaskExecutor.execute(task, Map.copyOf(instance.variables()));
            return wrapTaskResult(task, result);
        }

        TaskWorker worker = workers.get(task.implementation());
        if (worker == null) {
            return Map.of();
        }

        return worker.execute(Map.copyOf(instance.variables()));
    }

    private void applyTaskResult(ProcessInstance instance, ServiceTask task, Map<String, Object> result) {
        if (result == null || result.isEmpty()) {
            return;
        }

        result.forEach(instance.variables()::put);
    }

    private Map<String, Object> executeBeanTask(ServiceTask task, ProcessInstance instance) {
        BeanTaskConfiguration configuration = task.beanConfiguration();
        if (configuration == null || configuration.beanName() == null || configuration.beanName().isBlank()) {
            throw new IllegalArgumentException("Bean task is missing beanName configuration: " + task.id());
        }
        if (serviceTaskLogicRegistry == null) {
            throw new IllegalStateException("No ServiceTaskLogicRegistry is available");
        }

        ServiceTaskLogic logic = serviceTaskLogicRegistry.getByBeanName(configuration.beanName());
        if (logic == null) {
            throw new IllegalArgumentException("No ServiceTaskLogic bean found: " + configuration.beanName());
        }

        Map<String, Object> inputs = ConditionEvaluator.resolveMap(configuration.inputMapping(), Map.copyOf(instance.variables()));
        ServiceTaskExecutionContext context = new ServiceTaskExecutionContext(
                instance.instanceId(),
                instance.processDefinitionId(),
                task.id(),
                task.name(),
                Map.copyOf(instance.variables()),
                inputs
        );

        Object result = logic.execute(context);
        return wrapTaskResult(task, result);
    }

    private Map<String, Object> wrapTaskResult(ServiceTask task, Object result) {
        if (result == null) {
            return Map.of();
        }

        String resultVariable = switch (task.taskType()) {
            case REST -> task.restConfiguration() != null ? task.restConfiguration().resultVariable() : null;
            case BEAN -> task.beanConfiguration() != null ? task.beanConfiguration().resultVariable() : null;
            case KAFKA -> task.kafkaConfiguration() != null ? task.kafkaConfiguration().resultVariable() : null;
            case WORKER -> null;
        };

        if (resultVariable != null && !resultVariable.isBlank()) {
            return Map.of(resultVariable.trim(), result);
        }

        if (result instanceof Map<?, ?> rawMap) {
            java.util.LinkedHashMap<String, Object> normalized = new java.util.LinkedHashMap<>();
            rawMap.forEach((key, value) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), value);
                }
            });
            return normalized;
        }

        return Map.of();
    }

    private void persistCheckpoint(ProcessInstance instance, String eventType) {
        persistCheckpoint(instance, eventType, null);
    }

    /**
     * Persist a checkpoint via the configured sink (Kafka or JPA) or directly to storage.
     *
     * @param currentNodeIdOverride when non-null (e.g. after a task in a sequential chain), use this as the persisted currentNodeId
     */
    private void persistCheckpoint(ProcessInstance instance, String eventType, String currentNodeIdOverride) {
        Instant eventCreatedAt = Instant.now();
        List<ProcessInstanceStorage.TaskExecutionRecord> pending = pendingTaskExecutions.remove(instance.instanceId());
        if (pending == null) pending = List.of();

        String currentNodeId = currentNodeIdOverride != null ? currentNodeIdOverride
                : (instance.state() instanceof com.bko.bpmn_engine.model.Active a ? a.currentNodeId() : null);

        if (checkpointSink != null) {
            checkpointSink.checkpoint(instance, eventType, currentNodeId, parallelJoinTokens, pending, eventCreatedAt);
            return;
        }
        if (instanceStorage == null) return;
        ProcessInstance toSave = instance;
        if (currentNodeId != null && instance.state() instanceof com.bko.bpmn_engine.model.Active) {
            toSave = new ProcessInstance(
                    instance.instanceId(),
                    instance.processDefinitionId(),
                    instance.variables(),
                    new com.bko.bpmn_engine.model.Active(instance.instanceId(), currentNodeId),
                    instance.createdAt(),
                    instance.completedAt()
            );
        }
        instanceStorage.save(toSave, parallelJoinTokens);
        instanceStorage.saveEvent(
                instance.instanceId(),
                eventType,
                currentNodeId,
                Map.copyOf(instance.variables()),
                parallelJoinTokensToPlain(instance.instanceId()),
                eventCreatedAt
        );
        if (!pending.isEmpty()) {
            instanceStorage.saveTaskExecutions(instance.instanceId(), pending);
        }
    }

    private Map<String, Integer> parallelJoinTokensToPlain(UUID instanceId) {
        Map<String, AtomicInteger> inner = parallelJoinTokens.get(instanceId);
        if (inner == null || inner.isEmpty()) return Map.of();
        Map<String, Integer> plain = new java.util.HashMap<>();
        inner.forEach((k, v) -> plain.put(k, v.get()));
        return plain;
    }

    private void recordTaskExecution(UUID instanceId, String taskId, String taskType, long startMs, long durationMs) {
        if (instanceStorage == null) return;
        Instant startedAt = Instant.ofEpochMilli(startMs);
        Instant completedAt = Instant.ofEpochMilli(startMs + durationMs);
        pendingTaskExecutions
                .computeIfAbsent(instanceId, k -> new ArrayList<>())
                .add(new ProcessInstanceStorage.TaskExecutionRecord(taskId, taskType, startedAt, completedAt, durationMs));
    }

    /**
     * Restore deployed process from persistence. Used on startup recovery.
     */
    public void restoreDeployedProcess(String id, CompiledProcess compiled) {
        restoreDeployedProcess(id, compiled, null);
    }

    /**
     * Restore deployed process from persistence with BPMN XML cache for the viewer.
     */
    public void restoreDeployedProcess(String id, CompiledProcess compiled, String bpmnXml) {
        deployedProcesses.put(id, compiled);
        if (bpmnXml != null) {
            bpmnXmlByDefinitionId.put(id, bpmnXml);
        }
    }

    /**
     * Restore active instance from persistence. Used on startup recovery.
     */
    public void restoreActiveInstance(ProcessInstanceStorage.RecoveredInstance r) {
        ConcurrentHashMap<String, Object> vars = new ConcurrentHashMap<>(r.variables());
        ProcessInstance instance = new ProcessInstance(
                r.instanceId(),
                r.processDefinitionId(),
                vars,
                r.state(),
                r.createdAt(),
                r.completedAt()
        );
        activeInstances.put(r.instanceId(), instance);
        if (!r.parallelJoinTokens().isEmpty()) {
            Map<String, AtomicInteger> tokens = new ConcurrentHashMap<>();
            r.parallelJoinTokens().forEach((k, v) -> tokens.put(k, new AtomicInteger(v)));
            parallelJoinTokens.put(r.instanceId(), tokens);
        }
    }

    private String selectConditionalBranch(String gatewayId, String defaultFlowId, ProcessDefinition def, Map<String, Object> variables) {
        for (SequenceFlow flow : def.sequenceFlows().values()) {
            if (!flow.sourceRef().equals(gatewayId)) continue;
            if (flow.conditionExpression() != null && !flow.conditionExpression().isBlank()) {
                if (ConditionEvaluator.evaluate(flow.conditionExpression(), flow.conditionExpressionLanguage(), variables)) {
                    return flow.targetRef();
                }
            }
        }
        if (defaultFlowId != null && !defaultFlowId.isBlank()) {
            SequenceFlow defaultFlow = def.sequenceFlows().get(defaultFlowId);
            if (defaultFlow != null) {
                return defaultFlow.targetRef();
            }
        }
        return null;
    }

    private List<String> selectInclusiveBranches(InclusiveGateway gateway, ProcessDefinition def, Map<String, Object> variables) {
        List<String> targetIds = new ArrayList<>();
        for (SequenceFlow flow : def.sequenceFlows().values()) {
            if (!flow.sourceRef().equals(gateway.id())) continue;
            if (flow.conditionExpression() != null && !flow.conditionExpression().isBlank()) {
                if (ConditionEvaluator.evaluate(flow.conditionExpression(), flow.conditionExpressionLanguage(), variables)) {
                    targetIds.add(flow.targetRef());
                }
            }
        }
        if (targetIds.isEmpty() && gateway.defaultFlow() != null && !gateway.defaultFlow().isBlank()) {
            SequenceFlow defaultFlow = def.sequenceFlows().get(gateway.defaultFlow());
            if (defaultFlow != null) {
                targetIds.add(defaultFlow.targetRef());
            }
        }
        return targetIds;
    }

    @SuppressWarnings("unchecked")
    private List<String> selectComplexBranches(ComplexGateway gateway, ProcessDefinition def, Map<String, Object> variables) {
        String expr = gateway.activationExpression();
        if (expr == null || expr.isBlank()) {
            if (gateway.defaultFlow() != null && !gateway.defaultFlow().isBlank()) {
                SequenceFlow f = def.sequenceFlows().get(gateway.defaultFlow());
                return f != null ? List.of(f.targetRef()) : List.of();
            }
            return List.of();
        }
        Object result = ConditionEvaluator.resolveValue(expr, gateway.activationLanguage(), variables);
        if (result == null) {
            if (gateway.defaultFlow() != null && !gateway.defaultFlow().isBlank()) {
                SequenceFlow f = def.sequenceFlows().get(gateway.defaultFlow());
                return f != null ? List.of(f.targetRef()) : List.of();
            }
            return List.of();
        }
        if (result instanceof String flowId) {
            SequenceFlow f = def.sequenceFlows().get(flowId);
            if (f != null && f.sourceRef().equals(gateway.id())) {
                return List.of(f.targetRef());
            }
            return List.of();
        }
        if (result instanceof List<?> list) {
            List<String> targetIds = new ArrayList<>();
            for (Object item : list) {
                String flowId = item != null ? String.valueOf(item) : null;
                if (flowId == null || flowId.isBlank()) continue;
                SequenceFlow f = def.sequenceFlows().get(flowId);
                if (f != null && f.sourceRef().equals(gateway.id())) {
                    targetIds.add(f.targetRef());
                }
            }
            return targetIds;
        }
        if (gateway.defaultFlow() != null && !gateway.defaultFlow().isBlank()) {
            SequenceFlow f = def.sequenceFlows().get(gateway.defaultFlow());
            return f != null ? List.of(f.targetRef()) : List.of();
        }
        return List.of();
    }
}
