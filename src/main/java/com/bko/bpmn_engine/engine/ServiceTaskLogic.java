package com.bko.bpmn_engine.engine;

public interface ServiceTaskLogic {

    default String displayName() {
        return getClass().getSimpleName();
    }

    default String description() {
        return "";
    }

    Object execute(ServiceTaskExecutionContext context);
}
