package com.bko.bpmn_engine.engine;

/**
 * Thrown when the engine fails to publish a checkpoint to Kafka (e.g. timeout or broker unavailable).
 */
public class CheckpointPublishException extends RuntimeException {

    public CheckpointPublishException(String message, Throwable cause) {
        super(message, cause);
    }
}
