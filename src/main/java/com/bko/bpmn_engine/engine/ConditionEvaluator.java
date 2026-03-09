package com.bko.bpmn_engine.engine;

import com.fasterxml.jackson.core.type.TypeReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.MapAccessor;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import scala.jdk.javaapi.CollectionConverters;

import javax.script.Bindings;
import javax.script.ScriptEngine;
import javax.script.ScriptEngineManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

/**
 * Evaluates BPMN expressions against process variables.
 */
final class ConditionEvaluator {

    private static final Logger log = LoggerFactory.getLogger(ConditionEvaluator.class);
    private static final SpelExpressionParser SPEL_PARSER = new SpelExpressionParser();
    private static final ScriptEngineManager SCRIPT_ENGINE_MANAGER = new ScriptEngineManager();
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() {
    };

    private ConditionEvaluator() {
    }

    /**
     * Backward-compatible condition evaluation. Uses FEEL when the expression starts with "=" and
     * legacy SpEL for ${...} conditions without explicit language metadata.
     */
    static boolean evaluate(String condition, Map<String, Object> variables) {
        return evaluate(condition, null, variables);
    }

    /**
     * Evaluate a condition expression against variables.
     *
     * @param condition BPMN condition
     * @param language BPMN expression language, e.g. "feel"
     * @param variables process variables
     * @return true if condition evaluates to true, false otherwise
     */
    static boolean evaluate(String condition, String language, Map<String, Object> variables) {
        if (condition == null || condition.isBlank()) {
            return false;
        }

        try {
            Object result = resolveValue(condition, language, variables);
            boolean outcome = switch (result) {
                case Boolean b -> b;
                case String s -> Boolean.parseBoolean(s);
                default -> false;
            };
            log.trace("Condition evaluated condition={} language={} result={} outcome={}", condition, language, result, outcome);
            return outcome;
        } catch (Exception e) {
            log.trace("Condition evaluation failed condition={} language={} error={}", condition, language, e.getMessage());
            return false;
        }
    }

    static Object resolveValue(String expression, Map<String, Object> variables) {
        return resolveValue(expression, null, variables);
    }

    static Object resolveValue(String expression, String language, Map<String, Object> variables) {
        if (expression == null || expression.isBlank()) {
            return null;
        }

        String normalized = expression.trim();
        if (isFeelExpression(normalized, language)) {
            return evaluateFeel(normalized, variables);
        }

        if (isLegacySpelExpression(normalized, language)) {
            return evaluateSpel(normalized, variables);
        }

        if (looksLikeJson(normalized)) {
            try {
                return OBJECT_MAPPER.readValue(normalized, Object.class);
            } catch (Exception ignored) {
                return normalized;
            }
        }

        return normalized;
    }

    static String resolveString(String expression, Map<String, Object> variables) {
        Object result = resolveValue(expression, variables);
        return result != null ? String.valueOf(result) : null;
    }

    static Map<String, Object> resolveMap(String expression, Map<String, Object> variables) {
        Object result = resolveValue(expression, variables);
        if (result == null) {
            return Map.of();
        }
        if (result instanceof Map<?, ?> rawMap) {
            return rawMap.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .collect(java.util.stream.Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            Map.Entry::getValue,
                            (left, right) -> right,
                            java.util.LinkedHashMap::new
                    ));
        }
        if (result instanceof String stringValue && looksLikeJson(stringValue.trim())) {
            try {
                return OBJECT_MAPPER.readValue(stringValue, MAP_TYPE);
            } catch (Exception ignored) {
                return Map.of();
            }
        }
        return Map.of();
    }

    private static Object evaluateFeel(String expression, Map<String, Object> variables) {
        ScriptEngine engine = SCRIPT_ENGINE_MANAGER.getEngineByName("feel");
        if (engine == null) {
            throw new IllegalStateException("FEEL engine is not available");
        }

        String normalized = stripLeadingEquals(expression);
        Bindings bindings = engine.createBindings();
        if (variables != null) {
            bindings.putAll(variables);
        }

        try {
            return normalizeFeelValue(engine.eval(normalized, bindings));
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid FEEL expression: " + expression, e);
        }
    }

    private static Object evaluateSpel(String expression, Map<String, Object> variables) {
        String normalized = expression.trim();
        if (normalized.startsWith("${") && normalized.endsWith("}")) {
            normalized = normalized.substring(2, normalized.length() - 1).trim();
        }

        StandardEvaluationContext context = new StandardEvaluationContext(variables);
        context.addPropertyAccessor(new MapAccessor());
        return SPEL_PARSER.parseExpression(normalized).getValue(context);
    }

    private static boolean isFeelExpression(String expression, String language) {
        return "feel".equalsIgnoreCase(Objects.toString(language, "").trim()) || expression.startsWith("=");
    }

    private static boolean isLegacySpelExpression(String expression, String language) {
        if ("spel".equalsIgnoreCase(Objects.toString(language, "").trim())) {
            return true;
        }
        return expression.startsWith("${") && expression.endsWith("}");
    }

    private static String stripLeadingEquals(String expression) {
        String normalized = expression.trim();
        return normalized.startsWith("=") ? normalized.substring(1).trim() : normalized;
    }

    private static boolean looksLikeJson(String value) {
        return value.startsWith("{") || value.startsWith("[");
    }

    private static Object normalizeFeelValue(Object value) {
        if (value == null) {
            return null;
        }
        if (value instanceof scala.collection.Map<?, ?> scalaMap) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            CollectionConverters.asJava(scalaMap).forEach((key, nestedValue) -> {
                if (key != null) {
                    normalized.put(String.valueOf(key), normalizeFeelValue(nestedValue));
                }
            });
            return normalized;
        }
        if (value instanceof scala.collection.Iterable<?> scalaIterable) {
            java.util.ArrayList<Object> normalized = new java.util.ArrayList<>();
            for (Object nestedValue : CollectionConverters.asJava(scalaIterable)) {
                normalized.add(normalizeFeelValue(nestedValue));
            }
            return normalized;
        }
        if (value instanceof Map<?, ?> javaMap) {
            return javaMap.entrySet().stream()
                    .filter(entry -> entry.getKey() != null)
                    .collect(Collectors.toMap(
                            entry -> String.valueOf(entry.getKey()),
                            entry -> normalizeFeelValue(entry.getValue()),
                            (left, right) -> right,
                            LinkedHashMap::new
                    ));
        }
        if (value instanceof List<?> listValue) {
            return listValue.stream()
                    .map(ConditionEvaluator::normalizeFeelValue)
                    .toList();
        }
        return value;
    }
}
