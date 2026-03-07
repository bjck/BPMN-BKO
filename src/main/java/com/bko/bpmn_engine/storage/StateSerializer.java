package com.bko.bpmn_engine.storage;

import com.bko.bpmn_engine.model.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Converts ProcessState to/from persistence format.
 */
public final class StateSerializer {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private StateSerializer() {
    }

    public static String stateType(ProcessState state) {
        return switch (state) {
            case Created ignored -> "CREATED";
            case Active ignored -> "ACTIVE";
            case Completing ignored -> "COMPLETING";
            case Completed ignored -> "COMPLETED";
            case Failed ignored -> "FAILED";
        };
    }

    public static String currentNodeId(ProcessState state) {
        return state instanceof Active active ? active.currentNodeId() : null;
    }

    public static String errorMessage(ProcessState state) {
        return state instanceof Failed failed ? failed.errorMessage() : null;
    }

    public static ProcessState fromPersistence(String stateType, String currentNodeId, String errorMessage, UUID instanceId) {
        return switch (stateType) {
            case "CREATED" -> new Created(instanceId);
            case "ACTIVE" -> new Active(instanceId, currentNodeId != null ? currentNodeId : "");
            case "COMPLETING" -> new Completing(instanceId);
            case "COMPLETED" -> new Completed(instanceId);
            case "FAILED" -> new Failed(instanceId, errorMessage != null ? errorMessage : "");
            default -> throw new IllegalArgumentException("Unknown state: " + stateType);
        };
    }

    public static String parallelJoinTokensToJson(Map<UUID, Map<String, AtomicInteger>> tokens, UUID instanceId) {
        if (tokens == null || tokens.isEmpty()) {
            return "{}";
        }
        Map<String, AtomicInteger> inner = tokens.get(instanceId);
        if (inner == null) {
            return "{}";
        }
        Map<String, Integer> plain = new HashMap<>();
        inner.forEach((k, v) -> plain.put(k, v.get()));
        try {
            return MAPPER.writeValueAsString(plain);
        } catch (Exception e) {
            return "{}";
        }
    }

    public static Map<String, Integer> parallelJoinTokensFromJson(String json) {
        if (json == null || json.isBlank()) {
            return Map.of();
        }
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Map.of();
        }
    }

    /** Restore to Map<String, AtomicInteger> for a single instance. */
    public static Map<String, AtomicInteger> toAtomicMap(Map<String, Integer> plain) {
        if (plain == null || plain.isEmpty()) {
            return new HashMap<>();
        }
        Map<String, AtomicInteger> result = new HashMap<>();
        plain.forEach((k, v) -> result.put(k, new AtomicInteger(v)));
        return result;
    }
}
