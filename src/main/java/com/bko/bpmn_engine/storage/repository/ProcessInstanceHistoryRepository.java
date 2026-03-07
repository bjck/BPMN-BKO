package com.bko.bpmn_engine.storage.repository;

import com.bko.bpmn_engine.storage.entity.ProcessInstanceHistoryEntity;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.UUID;

public interface ProcessInstanceHistoryRepository extends JpaRepository<ProcessInstanceHistoryEntity, UUID> {

    Page<ProcessInstanceHistoryEntity> findAllByOrderByCompletedAtDesc(Pageable pageable);
}
