package com.bko.bpmn_engine.ai;

import com.bko.bpmn_engine.api.dto.ChatMessageDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class GeminiAiClient implements AiProviderClient {

    private final GeminiProperties properties;
    private final ObjectMapper objectMapper;
    private final HttpClient httpClient;

    public GeminiAiClient(GeminiProperties properties) {
        this.properties = properties;
        this.objectMapper = new ObjectMapper();
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.getTimeout())
                .build();
    }

    @Override
    public AiProviderResponse generate(AiProviderRequest request) throws Exception {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("system_instruction", Map.of(
                "parts", List.of(Map.of("text", request.systemInstruction()))
        ));
        payload.put("contents", buildContents(request.messages()));

        HttpRequest httpRequest = HttpRequest.newBuilder()
                .uri(buildUri(request.model()))
                .timeout(properties.getTimeout())
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(objectMapper.writeValueAsString(payload)))
                .build();

        HttpResponse<String> response = httpClient.send(httpRequest, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            throw new IllegalStateException("Gemini returned HTTP " + response.statusCode() + ": " + response.body());
        }

        JsonNode root = objectMapper.readTree(response.body());
        JsonNode parts = root.path("candidates").path(0).path("content").path("parts");
        if (!parts.isArray() || parts.isEmpty()) {
            throw new IllegalStateException("Gemini returned no content");
        }

        StringBuilder content = new StringBuilder();
        for (JsonNode part : parts) {
          if (part.hasNonNull("text")) {
              if (!content.isEmpty()) {
                  content.append('\n');
              }
              content.append(part.get("text").asText());
          }
        }

        Map<String, Object> usage = new HashMap<>();
        JsonNode usageNode = root.path("usageMetadata");
        if (!usageNode.isMissingNode()) {
            usage.put("promptTokenCount", usageNode.path("promptTokenCount").asInt(0));
            usage.put("candidatesTokenCount", usageNode.path("candidatesTokenCount").asInt(0));
            usage.put("totalTokenCount", usageNode.path("totalTokenCount").asInt(0));
        }

        return new AiProviderResponse(request.model(), content.toString().trim(), usage);
    }

    private URI buildUri(String model) {
        String normalizedBase = properties.getBaseUrl().endsWith("/")
                ? properties.getBaseUrl().substring(0, properties.getBaseUrl().length() - 1)
                : properties.getBaseUrl();
        String encodedKey = URLEncoder.encode(properties.getApiKey(), StandardCharsets.UTF_8);
        return URI.create("%s/v1beta/models/%s:generateContent?key=%s".formatted(normalizedBase, model, encodedKey));
    }

    private static List<Map<String, Object>> buildContents(List<ChatMessageDto> messages) {
        List<Map<String, Object>> contents = new ArrayList<>();
        for (ChatMessageDto message : messages) {
            if (message == null || message.content() == null || message.content().isBlank()) {
                continue;
            }

            String role = "assistant".equalsIgnoreCase(message.role()) ? "model" : "user";
            contents.add(Map.of(
                    "role", role,
                    "parts", List.of(Map.of("text", message.content()))
            ));
        }
        return contents;
    }
}
