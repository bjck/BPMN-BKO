package com.bko.bpmn_engine.storage.repository;

import com.bko.bpmn_engine.storage.entity.ProcessInstanceEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstanceEntity, UUID> {

    List<ProcessInstanceEntity> findByStateIn(List<String> states);
}
