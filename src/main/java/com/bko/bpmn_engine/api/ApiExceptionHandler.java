package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.api.exception.IllegalStateTransitionException;
import com.bko.bpmn_engine.api.exception.ProcessNotFoundException;
import com.bko.bpmn_engine.parser.BpmnParseException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    @ExceptionHandler(BpmnParseException.class)
    public ResponseEntity<Map<String, String>> handleBpmnParseException(BpmnParseException ex) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(ProcessNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleProcessNotFoundException(ProcessNotFoundException ex) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(Map.of("message", ex.getMessage()));
    }

    @ExceptionHandler(IllegalStateTransitionException.class)
    public ResponseEntity<Map<String, String>> handleIllegalStateTransitionException(IllegalStateTransitionException ex) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(Map.of("message", ex.getMessage()));
    }
}
