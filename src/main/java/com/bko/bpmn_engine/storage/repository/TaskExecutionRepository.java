package com.bko.bpmn_engine.storage.repository;

import com.bko.bpmn_engine.storage.entity.TaskExecutionEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TaskExecutionRepository extends JpaRepository<TaskExecutionEntity, Long> {

    List<TaskExecutionEntity> findByInstanceIdOrderByStartedAtAsc(UUID instanceId);
}
