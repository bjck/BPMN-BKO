package com.bko.bpmn_engine.storage.repository;

import com.bko.bpmn_engine.storage.entity.ProcessDefinitionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinitionEntity, String> {
}
