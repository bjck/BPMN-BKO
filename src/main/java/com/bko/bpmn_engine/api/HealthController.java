package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.api.dto.HealthResponse;
import com.bko.bpmn_engine.engine.ProcessEngine;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/v1")
public class HealthController {

    private final ProcessEngine processEngine;

    public HealthController(ProcessEngine processEngine) {
        this.processEngine = processEngine;
    }

    @GetMapping("/health")
    public ResponseEntity<HealthResponse> health() {
        int activeInstances = processEngine.getActiveInstanceCount();
        int deployedProcesses = processEngine.getDeployedProcessIds().size();
        return ResponseEntity.ok(new HealthResponse("UP", activeInstances, deployedProcesses));
    }
}
