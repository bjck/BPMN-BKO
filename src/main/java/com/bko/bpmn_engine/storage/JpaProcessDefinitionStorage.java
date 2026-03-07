package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.storage.entity.ProcessDefinitionEntity;
import com.bko.bpmn_engine.storage.repository.ProcessDefinitionRepository;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * JPA persistence for process definitions.
 */
@Component
@Profile("persistence")
public class JpaProcessDefinitionStorage implements ProcessDefinitionStorage {

    private final ProcessDefinitionRepository repository;

    public JpaProcessDefinitionStorage(ProcessDefinitionRepository repository) {
        this.repository = repository;
    }

    @Override
    @Transactional
    public void save(String id, String bpmnXml) {
        ProcessDefinitionEntity entity = repository.findById(id).orElse(null);
        if (entity == null) {
            entity = new ProcessDefinitionEntity(id, bpmnXml, Instant.now());
        } else {
            entity = new ProcessDefinitionEntity(id, bpmnXml, entity.getDeployedAt());
        }
        repository.save(entity);
    }

    @Override
    @Transactional(readOnly = true)
    public String findBpmnXmlById(String id) {
        return repository.findById(id)
                .map(ProcessDefinitionEntity::getBpmnXml)
                .orElse(null);
    }

    @Override
    @Transactional(readOnly = true)
    public Set<String> findAllDefinitionIds() {
        return repository.findAll().stream()
                .map(ProcessDefinitionEntity::getId)
                .collect(Collectors.toSet());
    }
}
