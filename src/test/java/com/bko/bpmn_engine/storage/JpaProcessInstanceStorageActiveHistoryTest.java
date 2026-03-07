package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.model.Active;
import com.bko.bpmn_engine.model.Completed;
import com.bko.bpmn_engine.model.ProcessInstance;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Tests active/history table split: COMPLETED/FAILED go to history and are removed from active;
 * findById resolves active then history; findAllActive returns only active rows.
 */
@SpringBootTest
@ActiveProfiles("persistence")
class JpaProcessInstanceStorageActiveHistoryTest {

    @Autowired
    private ProcessInstanceStorage storage;

    @Test
    void save_active_then_completed_movesToHistoryAndRemovedFromActive() {
        UUID instanceId = UUID.randomUUID();
        String processDefinitionId = "Process_Test";
        Instant createdAt = Instant.now().minusSeconds(60);
        ConcurrentHashMap<String, Object> variables = new ConcurrentHashMap<>(Map.of("k", "v"));

        ProcessInstance activeInstance = new ProcessInstance(
                instanceId,
                processDefinitionId,
                variables,
                new Active(instanceId, "Task_1"),
                createdAt,
                null
        );
        storage.save(activeInstance, Map.of());

        assertThat(storage.findAllActive()).anyMatch(r -> r.instanceId().equals(instanceId));
        ProcessInstanceStorage.RecoveredInstance foundActive = storage.findById(instanceId);
        assertThat(foundActive).isNotNull();
        assertThat(foundActive.state()).isInstanceOf(Active.class);

        ProcessInstance completedInstance = new ProcessInstance(
                instanceId,
                processDefinitionId,
                variables,
                new Completed(instanceId),
                createdAt,
                Instant.now()
        );
        storage.save(completedInstance, Map.of());

        assertThat(storage.findAllActive()).noneMatch(r -> r.instanceId().equals(instanceId));
        ProcessInstanceStorage.RecoveredInstance foundFromHistory = storage.findById(instanceId);
        assertThat(foundFromHistory).isNotNull();
        assertThat(foundFromHistory.state()).isInstanceOf(Completed.class);
        assertThat(foundFromHistory.processDefinitionId()).isEqualTo(processDefinitionId);
    }

    @Test
    void findAllPage_returnsActiveFirstThenHistoryPage() {
        UUID activeId = UUID.randomUUID();
        ProcessInstance active = new ProcessInstance(
                activeId,
                "P1",
                new ConcurrentHashMap<>(Map.of()),
                new Active(activeId, "T1"),
                Instant.now().minusSeconds(10),
                null
        );
        storage.save(active, Map.of());

        ProcessInstanceStorage.InstancePage page1 = storage.findAllPage(1, 50);
        assertThat(page1.instances()).isNotEmpty();
        assertThat(page1.instances().stream().map(ProcessInstanceStorage.RecoveredInstance::instanceId).toList())
                .contains(activeId);
        assertThat(page1.page()).isEqualTo(1);
        assertThat(page1.pageSize()).isEqualTo(50);
    }
}
