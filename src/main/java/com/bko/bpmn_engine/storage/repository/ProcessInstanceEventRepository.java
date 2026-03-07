package com.bko.bpmn_engine.storage.repository;

import com.bko.bpmn_engine.storage.entity.ProcessInstanceEventEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProcessInstanceEventRepository extends JpaRepository<ProcessInstanceEventEntity, Long> {

    List<ProcessInstanceEventEntity> findByInstanceIdOrderByCreatedAtAsc(UUID instanceId);
}
