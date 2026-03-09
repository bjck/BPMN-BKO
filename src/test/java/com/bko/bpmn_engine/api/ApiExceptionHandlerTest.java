package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.api.exception.IllegalStateTransitionException;
import com.bko.bpmn_engine.api.exception.ProcessNotFoundException;
import com.bko.bpmn_engine.parser.BpmnParseException;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for ApiExceptionHandler.
 */
class ApiExceptionHandlerTest {

    private final ApiExceptionHandler handler = new ApiExceptionHandler();

    @Test
    void handleBpmnParseException_returns400WithMessage() {
        BpmnParseException ex = new BpmnParseException("Invalid XML structure");

        ResponseEntity<Map<String, String>> response = handler.handleBpmnParseException(ex);

        assertEquals(400, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Invalid XML structure", response.getBody().get("message"));
    }

    @Test
    void handleProcessNotFoundException_returns404WithMessage() {
        ProcessNotFoundException ex = new ProcessNotFoundException("Process not found: proc-1");

        ResponseEntity<Map<String, String>> response = handler.handleProcessNotFoundException(ex);

        assertEquals(404, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Process not found: proc-1", response.getBody().get("message"));
    }

    @Test
    void handleIllegalStateTransitionException_returns409WithMessage() {
        IllegalStateTransitionException ex = new IllegalStateTransitionException("Instance already completed");

        ResponseEntity<Map<String, String>> response = handler.handleIllegalStateTransitionException(ex);

        assertEquals(409, response.getStatusCode().value());
        assertNotNull(response.getBody());
        assertEquals("Instance already completed", response.getBody().get("message"));
    }
}
