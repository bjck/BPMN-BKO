package com.bko.bpmn_engine.parser;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class BpmnParseExceptionTest {

    @Test
    void messageOnly_setsMessageAndNullElementId() {
        BpmnParseException ex = new BpmnParseException("Parse failed");
        assertEquals("Parse failed", ex.getMessage());
        assertNull(ex.getElementId());
    }

    @Test
    void messageAndCause_setsMessageAndCause() {
        Throwable cause = new RuntimeException("root cause");
        BpmnParseException ex = new BpmnParseException("Parse failed", cause);
        assertEquals("Parse failed", ex.getMessage());
        assertSame(cause, ex.getCause());
        assertNull(ex.getElementId());
    }

    @Test
    void elementIdAndMessage_formatsMessageWithBrackets() {
        BpmnParseException ex = new BpmnParseException("Task_1", "Missing sourceRef");
        assertEquals("[Task_1] Missing sourceRef", ex.getMessage());
        assertEquals("Task_1", ex.getElementId());
    }

    @Test
    void elementIdNullOrBlank_returnsPlainMessage() {
        BpmnParseException exNull = new BpmnParseException(null, "Error");
        assertEquals("Error", exNull.getMessage());
        assertNull(exNull.getElementId());

        BpmnParseException exBlank = new BpmnParseException("  ", "Error");
        assertEquals("Error", exBlank.getMessage());
    }

    @Test
    void elementIdMessageAndCause_formatsMessageWithBrackets() {
        Throwable cause = new IllegalArgumentException("bad");
        BpmnParseException ex = new BpmnParseException("Flow_1", "Invalid targetRef", cause);
        assertEquals("[Flow_1] Invalid targetRef", ex.getMessage());
        assertEquals("Flow_1", ex.getElementId());
        assertSame(cause, ex.getCause());
    }
}
