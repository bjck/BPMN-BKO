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

    @Test
    void evaluate_nullOrBlank_returnsFalse() {
        assertFalse(ConditionEvaluator.evaluate(null, Map.of()));
        assertFalse(ConditionEvaluator.evaluate("", Map.of()));
        assertFalse(ConditionEvaluator.evaluate("   ", Map.of()));
    }

    @Test
    void evaluate_withLanguageFeel_usesFeelEngine() {
        assertTrue(ConditionEvaluator.evaluate("= x > 5", "feel", Map.of("x", 10)));
        assertFalse(ConditionEvaluator.evaluate("= x > 5", "feel", Map.of("x", 2)));
    }

    @Test
    void evaluate_withLanguageSpel_usesSpel() {
        assertTrue(ConditionEvaluator.evaluate("${flag}", "spel", Map.of("flag", true)));
    }

    @Test
    void resolveValue_stringResult_parsedAsBoolean() {
        assertTrue(ConditionEvaluator.evaluate("= \"true\"", "feel", Map.of()));
    }

    @Test
    void resolveMap_withSpelLanguage_parsesJsonString() {
        Map<String, Object> resolved = ConditionEvaluator.resolveMap("{\"a\":1}", Map.of());
        assertEquals(1, resolved.get("a"));
    }
}
