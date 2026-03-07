package com.bko.bpmn_engine.storage;

/**
 * Persists process definitions (BPMN XML) for recovery.
 */
public interface ProcessDefinitionStorage {

    /**
     * Persist process definition. Blocks until committed.
     */
    void save(String id, String bpmnXml);

    /**
     * Load BPMN XML by definition id.
     */
    String findBpmnXmlById(String id);

    /**
     * Load all deployed definition ids for recovery.
     */
    java.util.Set<String> findAllDefinitionIds();
}
