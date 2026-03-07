package com.bko.bpmn_engine.ai;

import com.bko.bpmn_engine.api.dto.AiChatRequest;
import com.bko.bpmn_engine.api.dto.AiChatResponse;
import com.bko.bpmn_engine.api.dto.AiDiagramUpdateDto;
import com.bko.bpmn_engine.api.dto.ChatMessageDto;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class AiAssistantService {

    private static final int MAX_MESSAGES = 12;
    private static final int MAX_ERROR_CHARS = 400;
    private static final Logger log = LoggerFactory.getLogger(AiAssistantService.class);

    private final AiProviderClient providerClient;
    private final AiPromptBuilder promptBuilder;
    private final GeminiProperties properties;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public AiAssistantService(AiProviderClient providerClient, AiPromptBuilder promptBuilder, GeminiProperties properties) {
        this.providerClient = providerClient;
        this.promptBuilder = promptBuilder;
        this.properties = properties;
    }

    public AiChatResponse chat(AiChatRequest request) {
        String route = request.route() == null || request.route().isBlank() ? "processes" : request.route().trim();
        String conversationId = request.conversationId() == null || request.conversationId().isBlank()
                ? UUID.randomUUID().toString()
                : request.conversationId();

        if (!properties.isConfigured()) {
            return new AiChatResponse(
                    conversationId,
                    properties.getModel(),
                    false,
                    new ChatMessageDto("assistant", promptBuilder.buildUnavailableMessage(route)),
                    Map.of(),
                    null
            );
        }

        List<ChatMessageDto> providerMessages = new ArrayList<>();
        providerMessages.add(new ChatMessageDto("user", promptBuilder.buildContextMessage(route, request.context())));
        providerMessages.addAll(sanitizeMessages(request.messages()));

        try {
            AiProviderResponse response = providerClient.generate(new AiProviderRequest(
                    promptBuilder.buildSystemInstruction(),
                    properties.getModel(),
                    providerMessages
            ));
            StructuredAssistantReply structuredReply = parseAssistantReply(response.content());

            return new AiChatResponse(
                    conversationId,
                    response.model(),
                    true,
                    new ChatMessageDto("assistant", structuredReply.reply()),
                    response.usage() == null ? Map.of() : response.usage(),
                    structuredReply.diagramUpdate()
            );
        } catch (Exception e) {
            String detail = summarizeProviderError(e);
            log.error("AI provider request failed for route {}: {}", route, detail, e);
            return new AiChatResponse(
                    conversationId,
                    properties.getModel(),
                    true,
                    new ChatMessageDto("assistant", promptBuilder.buildFailureMessage(detail)),
                    Map.of("error", detail),
                    null
            );
        }
    }

    private StructuredAssistantReply parseAssistantReply(String rawContent) {
        String content = rawContent == null ? "" : rawContent.trim();
        if (content.isBlank()) {
            return new StructuredAssistantReply("No response received.", null);
        }

        String normalized = stripMarkdownFences(content);
        try {
            JsonNode root = objectMapper.readTree(normalized);
            String reply = root.path("reply").asText("").trim();
            JsonNode diagramNode = root.path("diagramUpdate");
            AiDiagramUpdateDto diagramUpdate = null;
            if (diagramNode.isObject()) {
                String mode = diagramNode.path("mode").asText("").trim();
                String bpmnXml = diagramNode.path("bpmnXml").asText("").trim();
                if (!mode.isBlank() && !bpmnXml.isBlank()) {
                    diagramUpdate = new AiDiagramUpdateDto(
                            mode,
                            diagramNode.path("anchorElementId").asText("").trim(),
                            bpmnXml,
                            diagramNode.path("summary").asText("").trim()
                    );
                }
            }

            if (reply.isBlank()) {
                reply = diagramUpdate != null && diagramUpdate.summary() != null && !diagramUpdate.summary().isBlank()
                        ? diagramUpdate.summary()
                        : "Done.";
            }

            return new StructuredAssistantReply(reply, diagramUpdate);
        } catch (Exception ignored) {
            return new StructuredAssistantReply(content, null);
        }
    }

    private static String stripMarkdownFences(String value) {
        String normalized = value.trim();
        if (normalized.startsWith("```")) {
            int firstNewline = normalized.indexOf('\n');
            if (firstNewline >= 0) {
                normalized = normalized.substring(firstNewline + 1);
            }
            if (normalized.endsWith("```")) {
                normalized = normalized.substring(0, normalized.length() - 3);
            }
        }
        return normalized.trim();
    }

    private static List<ChatMessageDto> sanitizeMessages(List<ChatMessageDto> messages) {
        if (messages == null || messages.isEmpty()) {
            return List.of(new ChatMessageDto("user", "What can I do on this page?"));
        }

        return messages.stream()
                .filter(message -> message != null && message.content() != null && !message.content().isBlank())
                .skip(Math.max(0, messages.size() - MAX_MESSAGES))
                .map(message -> new ChatMessageDto(normalizeRole(message.role()), message.content().trim()))
                .toList();
    }

    private static String normalizeRole(String role) {
        return "assistant".equalsIgnoreCase(role) ? "assistant" : "user";
    }

    private static String summarizeProviderError(Exception error) {
        String message = error == null ? "" : error.getMessage();
        if (message == null || message.isBlank()) {
            message = error != null ? error.getClass().getSimpleName() : "Unknown provider error";
        }

        String sanitized = message
                .replaceAll("(?i)(key=)[^\\s&]+", "$1[redacted]")
                .replaceAll("AIza[0-9A-Za-z_\\-]+", "[redacted-api-key]")
                .replaceAll("\\s+", " ")
                .trim();

        if (sanitized.length() > MAX_ERROR_CHARS) {
            sanitized = sanitized.substring(0, MAX_ERROR_CHARS) + "...";
        }

        return sanitized;
    }

    private record StructuredAssistantReply(String reply, AiDiagramUpdateDto diagramUpdate) {
    }
}
