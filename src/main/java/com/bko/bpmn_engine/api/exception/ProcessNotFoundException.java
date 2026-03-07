package com.bko.bpmn_engine.api.exception;

/**
 * Thrown when a process definition or instance is not found.
 */
public class ProcessNotFoundException extends RuntimeException {

    public ProcessNotFoundException(String message) {
        super(message);
    }

    public ProcessNotFoundException(String message, Throwable cause) {
        super(message, cause);
    }
}
