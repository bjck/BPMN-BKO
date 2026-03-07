package com.bko.bpmn_engine.engine;

import java.util.Map;

/**
 * Functional interface for service task execution.
 * Workers are registered by task implementation (e.g. "java") and receive process variables.
 */
@FunctionalInterface
public interface TaskWorker {

    /**
     * Execute the task with the given variables.
     *
     * @param variables process variables (read-only view; returned map is merged into instance)
     * @return variables to merge into the process instance (may be empty, never null)
     */
    Map<String, Object> execute(Map<String, Object> variables);
}
