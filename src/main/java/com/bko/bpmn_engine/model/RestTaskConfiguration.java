package com.bko.bpmn_engine.model;

public record RestTaskConfiguration(
        String method,
        String url,
        String authenticationType,
        String apiKeyLocation,
        String apiKeyName,
        String apiKeyValue,
        String username,
        String password,
        String bearerToken,
        String headers,
        String queryParameters,
        String body,
        String resultVariable,
        Integer timeoutSeconds
) {
}
