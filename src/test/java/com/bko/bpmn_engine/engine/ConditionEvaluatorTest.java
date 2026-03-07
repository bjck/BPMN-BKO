package com.bko.bpmn_engine.engine;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class ConditionEvaluatorTest {

    @Test
    void evaluate_flagTrue_returnsTrue() {
        assertTrue(ConditionEvaluator.evaluate("${flag == true}", Map.of("flag", true)));
    }

    @Test
    void evaluate_flagFalse_returnsTrue() {
        assertTrue(ConditionEvaluator.evaluate("${flag == false}", Map.of("flag", false)));
    }

    @Test
    void evaluate_flagTrue_withFalse_returnsFalse() {
        assertFalse(ConditionEvaluator.evaluate("${flag == true}", Map.of("flag", false)));
    }

    @Test
    void evaluateFeelCondition_returnsTrue() {
        assertTrue(ConditionEvaluator.evaluate("= approved = true", "feel", Map.of("approved", true)));
    }

    @Test
    void resolveFeelMap_returnsJavaMap() {
        Map<String, Object> resolved = ConditionEvaluator.resolveMap("= { Authorization: \"Bearer \" + token }", Map.of("token", "abc"));
        assertEquals("Bearer abc", resolved.get("Authorization"));
    }
}
