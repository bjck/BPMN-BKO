package com.bko.bpmn_engine.parser;

/**
 * Exception thrown when BPMN XML parsing fails.
 * Includes the element id and a clear message for diagnostics.
 */
public class BpmnParseException extends Exception {

    private final String elementId;

    public BpmnParseException(String message) {
        super(message);
        this.elementId = null;
    }

    public BpmnParseException(String message, Throwable cause) {
        super(message, cause);
        this.elementId = null;
    }

    public BpmnParseException(String elementId, String message) {
        super(formatMessage(elementId, message));
        this.elementId = elementId;
    }

    public BpmnParseException(String elementId, String message, Throwable cause) {
        super(formatMessage(elementId, message), cause);
        this.elementId = elementId;
    }

    public String getElementId() {
        return elementId;
    }

    private static String formatMessage(String elementId, String message) {
        if (elementId == null || elementId.isBlank()) {
            return message;
        }
        return "[" + elementId + "] " + message;
    }
}
