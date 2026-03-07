package com.bko.bpmn_engine.storage;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.util.Map;

/**
 * Serializes process variables to/from JSON for persistence.
 * Supports primitives, String, Map, List. Complex objects are best stored as JSON-serializable structures.
 */
public final class VariableSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private VariableSerializer() {
    }

    public static String toJson(Map<String, Object> variables) {
        if (variables == null || variables.isEmpty()) {
            return "{}";
        }
        try {
            return MAPPER.writeValueAsString(variables);
        } catch (JsonProcessingException e) {
            throw new VariableSerializationException("Failed to serialize variables", e);
        }
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> fromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<Map<String, Object>>() {});
        } catch (JsonProcessingException e) {
            throw new VariableSerializationException("Failed to deserialize variables: " + json, e);
        }
    }
}
