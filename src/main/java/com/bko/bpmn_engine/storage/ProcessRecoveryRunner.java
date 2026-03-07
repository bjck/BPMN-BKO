package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.model.CompiledProcess;
import com.bko.bpmn_engine.parser.BpmnParseException;
import com.bko.bpmn_engine.parser.BpmnParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

/**
 * Restores process definitions and active instances from persistence on startup.
 */
@Component
@Profile("persistence")
@Order(1)
public class ProcessRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(ProcessRecoveryRunner.class);

    private final ProcessEngine engine;
    private final ProcessDefinitionStorage definitionStorage;
    private final ProcessInstanceStorage instanceStorage;
    private final BpmnParser parser;

    public ProcessRecoveryRunner(ProcessEngine engine, ProcessDefinitionStorage definitionStorage,
                                 ProcessInstanceStorage instanceStorage, BpmnParser parser) {
        this.engine = engine;
        this.definitionStorage = definitionStorage;
        this.instanceStorage = instanceStorage;
        this.parser = parser;
    }

    @Override
    public void run(ApplicationArguments args) {
        restoreProcessDefinitions();
        restoreActiveInstances();
    }

    private void restoreProcessDefinitions() {
        for (String id : definitionStorage.findAllDefinitionIds()) {
            String bpmnXml = definitionStorage.findBpmnXmlById(id);
            if (bpmnXml != null) {
                try {
                    CompiledProcess compiled = parser.parse(bpmnXml);
                    engine.restoreDeployedProcess(id, compiled, bpmnXml);
                    log.info("Restored process definition: {}", id);
                } catch (BpmnParseException e) {
                    log.warn("Failed to restore process definition {}: {}", id, e.getMessage());
                }
            }
        }
    }

    private void restoreActiveInstances() {
        for (ProcessInstanceStorage.RecoveredInstance r : instanceStorage.findAllActive()) {
            engine.restoreActiveInstance(r);
            log.info("Restored active instance: {}", r.instanceId());
        }
    }
}
