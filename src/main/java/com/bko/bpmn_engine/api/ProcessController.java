package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.api.dto.*;
import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.engine.ServiceTaskLogicRegistry;
import com.bko.bpmn_engine.model.*;
import com.bko.bpmn_engine.model.Active;
import com.bko.bpmn_engine.parser.BpmnParseException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/v1")
public class ProcessController {

    private final ProcessEngine processEngine;
    private final ServiceTaskLogicRegistry serviceTaskLogicRegistry;

    public ProcessController(ProcessEngine processEngine, ServiceTaskLogicRegistry serviceTaskLogicRegistry) {
        this.processEngine = processEngine;
        this.serviceTaskLogicRegistry = serviceTaskLogicRegistry;
    }

    @PostMapping("/processes")
    public ResponseEntity<DeployProcessResponse> deployProcess(@RequestBody DeployProcessRequest request) throws BpmnParseException {
        String processDefinitionId = processEngine.deployProcess(request.bpmnXml());
        return ResponseEntity.status(HttpStatus.CREATED).body(new DeployProcessResponse(processDefinitionId));
    }

    @PostMapping("/process-instances")
    public ResponseEntity<CreateInstanceResponse> createInstance(@RequestBody CreateInstanceRequest request) {
        Map<String, Object> variables = request.variables() != null ? request.variables() : Map.of();
        ProcessInstance instance = processEngine.createInstance(request.processDefinitionId(), variables);
        return ResponseEntity.status(HttpStatus.CREATED).body(toCreateInstanceResponse(instance));
    }

    @GetMapping("/process-instances/{instanceId}")
    public ResponseEntity<InstanceResponse> getInstance(@PathVariable UUID instanceId) {
        ProcessInstance instance = processEngine.getInstance(instanceId);
        if (instance == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok(toInstanceResponse(instance));
    }

    @PostMapping("/process-instances/{instanceId}/complete-task/{taskId}")
    public ResponseEntity<InstanceResponse> completeTask(
            @PathVariable UUID instanceId,
            @PathVariable String taskId,
            @RequestBody(required = false) CompleteTaskRequest request) {
        Map<String, Object> variables = request != null && request.variables() != null ? request.variables() : Map.of();
        ProcessInstance instance = processEngine.completeTask(instanceId, taskId, variables);
        return ResponseEntity.ok(toInstanceResponse(instance));
    }

    @DeleteMapping("/process-instances/{instanceId}")
    public ResponseEntity<Void> cancelInstance(@PathVariable UUID instanceId) {
        processEngine.cancelInstance(instanceId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/processes")
    public ResponseEntity<ListProcessesResponse> listProcesses() {
        var processes = processEngine.getDeployedProcessIds().stream().sorted().toList();
        return ResponseEntity.ok(new ListProcessesResponse(processes));
    }

    @GetMapping("/service-task-logics")
    public ResponseEntity<ListServiceTaskLogicsResponse> listServiceTaskLogics() {
        var serviceTaskLogics = serviceTaskLogicRegistry.listDescriptors().stream()
                .map(descriptor -> new ListServiceTaskLogicsResponse.ServiceTaskLogicSummary(
                        descriptor.beanName(),
                        descriptor.displayName(),
                        descriptor.description()
                ))
                .toList();
        return ResponseEntity.ok(new ListServiceTaskLogicsResponse(serviceTaskLogics));
    }

    @GetMapping(value = "/processes/{processDefinitionId}/bpmn", produces = MediaType.APPLICATION_XML_VALUE)
    public ResponseEntity<String> getProcessBpmn(@PathVariable String processDefinitionId) {
        String bpmnXml = processEngine.getBpmnXml(processDefinitionId);
        if (bpmnXml == null) {
            return ResponseEntity.notFound().build();
        }
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_XML_VALUE)
                .body(bpmnXml);
    }

    @GetMapping("/process-instances")
    public ResponseEntity<ListInstancesResponse> listInstances() {
        var instances = processEngine.getAllInstances().stream()
                .map(i -> new ListInstancesResponse.InstanceSummary(
                        i.instanceId(),
                        i.processDefinitionId(),
                        stateToString(i.state()),
                        i.state() instanceof Active a ? a.currentNodeId() : null,
                        i.createdAt(),
                        i.completedAt(),
                        copyVariables(i.variables())
                ))
                .toList();
        return ResponseEntity.ok(new ListInstancesResponse(instances));
    }

    private static CreateInstanceResponse toCreateInstanceResponse(ProcessInstance instance) {
        return new CreateInstanceResponse(
                instance.instanceId(),
                stateToString(instance.state()),
                copyVariables(instance.variables())
        );
    }

    private InstanceResponse toInstanceResponse(ProcessInstance instance) {
        String currentNodeId = instance.state() instanceof Active a ? a.currentNodeId() : null;
        String pendingUserTaskId = processEngine.getPendingUserTaskId(instance);
        return new InstanceResponse(
                instance.instanceId(),
                instance.processDefinitionId(),
                stateToString(instance.state()),
                currentNodeId,
                pendingUserTaskId,
                copyVariables(instance.variables()),
                instance.createdAt(),
                instance.completedAt()
        );
    }

    private static String stateToString(ProcessState state) {
        return switch (state) {
            case Created c -> "Created";
            case Active a -> "Active";
            case Completing c -> "Completing";
            case Completed c -> "Completed";
            case Failed f -> "Failed";
        };
    }

    private static Map<String, Object> copyVariables(Map<String, Object> variables) {
        return variables != null ? new HashMap<>(variables) : Map.of();
    }
}
