package com.bko.bpmn_engine.engine;

import com.bko.bpmn_engine.model.RestTaskConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.bko.bpmn_engine.model.ServiceTask;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

final class RestTaskExecutor {

    private static final Logger log = LoggerFactory.getLogger(RestTaskExecutor.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private final HttpClient httpClient = HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    Object execute(ServiceTask task, Map<String, Object> variables) {
        RestTaskConfiguration configuration = task.restConfiguration();
        if (configuration == null) {
            throw new IllegalArgumentException("REST task is missing configuration: " + task.id());
        }

        String method = defaultString(ConditionEvaluator.resolveString(configuration.method(), variables), "GET").toUpperCase();
        String url = ConditionEvaluator.resolveString(configuration.url(), variables);
        if (url == null || url.isBlank()) {
            throw new IllegalArgumentException("REST task URL is required for task: " + task.id());
        }
        log.trace("REST task executing taskId={} method={} url={}", task.id(), method, url);

        Map<String, Object> queryParameters = new LinkedHashMap<>(ConditionEvaluator.resolveMap(configuration.queryParameters(), variables));
        Map<String, Object> headers = new LinkedHashMap<>(ConditionEvaluator.resolveMap(configuration.headers(), variables));

        applyAuthentication(configuration, variables, headers, queryParameters);

        HttpRequest.Builder requestBuilder = HttpRequest.newBuilder(buildUri(url, queryParameters));
        Integer timeoutSeconds = configuration.timeoutSeconds();
        if (timeoutSeconds != null && timeoutSeconds > 0) {
            requestBuilder.timeout(Duration.ofSeconds(timeoutSeconds));
        }

        String body = resolveRequestBody(configuration.body(), variables);
        if (requiresRequestBody(method)) {
            if (body != null) {
                requestBuilder.method(method, HttpRequest.BodyPublishers.ofString(body));
                ensureContentType(headers, "application/json");
            } else {
                requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
            }
        } else {
            requestBuilder.method(method, HttpRequest.BodyPublishers.noBody());
        }

        headers.forEach((key, value) -> {
            if (key != null && value != null) {
                requestBuilder.header(key, String.valueOf(value));
            }
        });

        try {
            HttpResponse<String> response = httpClient.send(requestBuilder.build(), HttpResponse.BodyHandlers.ofString());
            log.trace("REST task response taskId={} status={}", task.id(), response.statusCode());
            Map<String, Object> responsePayload = Map.of(
                    "status", response.statusCode(),
                    "headers", flattenHeaders(response.headers().map()),
                    "body", parseResponseBody(response.body())
            );

            if (response.statusCode() >= 400) {
                throw new IllegalStateException("REST task failed with status " + response.statusCode() + ": " + responsePayload.get("body"));
            }

            return responsePayload;
        } catch (Exception e) {
            throw new IllegalStateException("REST task failed for task " + task.id() + ": " + e.getMessage(), e);
        }
    }

    private void applyAuthentication(
            RestTaskConfiguration configuration,
            Map<String, Object> variables,
            Map<String, Object> headers,
            Map<String, Object> queryParameters
    ) {
        String authenticationType = defaultString(configuration.authenticationType(), "none").trim().toLowerCase();
        switch (authenticationType) {
            case "none" -> {
            }
            case "bearer" -> {
                String token = ConditionEvaluator.resolveString(configuration.bearerToken(), variables);
                if (token != null && !token.isBlank()) {
                    headers.put("Authorization", "Bearer " + token);
                }
            }
            case "basic" -> {
                String username = defaultString(ConditionEvaluator.resolveString(configuration.username(), variables), "");
                String password = defaultString(ConditionEvaluator.resolveString(configuration.password(), variables), "");
                String encoded = Base64.getEncoder().encodeToString((username + ":" + password).getBytes(StandardCharsets.UTF_8));
                headers.put("Authorization", "Basic " + encoded);
            }
            case "apikey" -> {
                String keyName = defaultString(configuration.apiKeyName(), "");
                String keyValue = ConditionEvaluator.resolveString(configuration.apiKeyValue(), variables);
                if (!keyName.isBlank() && keyValue != null) {
                    if ("query".equalsIgnoreCase(configuration.apiKeyLocation())) {
                        queryParameters.put(keyName, keyValue);
                    } else {
                        headers.put(keyName, keyValue);
                    }
                }
            }
            default -> throw new IllegalArgumentException("Unsupported REST authentication type: " + authenticationType);
        }
    }

    private URI buildUri(String baseUrl, Map<String, Object> queryParameters) {
        if (queryParameters.isEmpty()) {
            return URI.create(baseUrl);
        }

        StringBuilder query = new StringBuilder();
        queryParameters.forEach((key, value) -> {
            if (key == null || value == null) {
                return;
            }
            if (!query.isEmpty()) {
                query.append('&');
            }
            query.append(URLEncoder.encode(key, StandardCharsets.UTF_8));
            query.append('=');
            query.append(URLEncoder.encode(String.valueOf(value), StandardCharsets.UTF_8));
        });

        String separator = baseUrl.contains("?") ? "&" : "?";
        return URI.create(baseUrl + separator + query);
    }

    private String resolveRequestBody(String bodyExpression, Map<String, Object> variables) {
        Object body = ConditionEvaluator.resolveValue(bodyExpression, variables);
        if (body == null) {
            return null;
        }
        if (body instanceof String stringValue) {
            return stringValue;
        }
        try {
            return OBJECT_MAPPER.writeValueAsString(body);
        } catch (Exception e) {
            throw new IllegalArgumentException("Unable to serialize REST request body", e);
        }
    }

    private boolean requiresRequestBody(String method) {
        return "POST".equals(method) || "PUT".equals(method) || "PATCH".equals(method);
    }

    private void ensureContentType(Map<String, Object> headers, String defaultContentType) {
        boolean hasContentType = headers.keySet().stream()
                .filter(java.util.Objects::nonNull)
                .anyMatch(key -> "content-type".equalsIgnoreCase(key));
        if (!hasContentType) {
            headers.put("Content-Type", defaultContentType);
        }
    }

    private Object parseResponseBody(String responseBody) {
        if (responseBody == null || responseBody.isBlank()) {
            return null;
        }

        String trimmed = responseBody.trim();
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
            try {
                return OBJECT_MAPPER.readValue(trimmed, Object.class);
            } catch (Exception ignored) {
                return responseBody;
            }
        }

        return responseBody;
    }

    private Map<String, Object> flattenHeaders(Map<String, List<String>> headers) {
        Map<String, Object> flattened = new LinkedHashMap<>();
        headers.forEach((key, values) -> {
            if (values == null || values.isEmpty()) {
                flattened.put(key, "");
            } else if (values.size() == 1) {
                flattened.put(key, values.getFirst());
            } else {
                flattened.put(key, List.copyOf(values));
            }
        });
        return flattened;
    }

    private String defaultString(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
