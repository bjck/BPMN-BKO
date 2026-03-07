package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.ProcessInstance;
import com.bko.bpmn_engine.storage.ProcessInstanceStorage;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * No-op checkpoint sink when persistence is disabled. Engine never persists checkpoints.
 */
@Component
@Profile("!persistence")
@ConditionalOnMissingBean(CheckpointSink.class)
public class NoOpCheckpointSink implements CheckpointSink {

    @Override
    public void checkpoint(ProcessInstance instance, String eventType, String currentNodeId,
                           Map<UUID, Map<String, AtomicInteger>> parallelJoinTokens,
                           List<ProcessInstanceStorage.TaskExecutionRecord> taskExecutionRecords,
                           Instant eventCreatedAt) {
        // no-op
    }
}
