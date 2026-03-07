package com.bko.bpmn_engine.api.exception;

/**
 * Thrown when an operation cannot be performed due to the current process state.
 */
public class IllegalStateTransitionException extends RuntimeException {

    public IllegalStateTransitionException(String message) {
        super(message);
    }

    public IllegalStateTransitionException(String message, Throwable cause) {
        super(message, cause);
    }
}
