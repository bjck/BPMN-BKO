package com.bko.bpmn_engine.ai;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class AiPromptBuilder {

    static final int MAX_CONTEXT_CHARS = 4000;
    private static final String RESPONSE_SCHEMA = """
            {
              "reply": "short helpful assistant response",
              "diagramUpdate": {
                "mode": "anchor|append|replace",
                "anchorElementId": "optional existing selected element id",
                "summary": "what the diagram change does",
                "bpmnXml": "full BPMN 2.0 XML string with BPMN DI"
              }
            }
            """;
    private final ObjectMapper objectMapper;

    public AiPromptBuilder() {
        this(new ObjectMapper());
    }

    AiPromptBuilder(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public String buildSystemInstruction() {
        return """
                You are an in-app BPMN assistant for a Java BPMN engine web application.
                Help the user understand the current page, BPMN elements, and possible next actions.
                Prefer concrete explanations tied to the provided page context.
                Do not invent features or claim integrations that are not present in the context.
                When explaining BPMN, keep it practical and focused on what the user can do in this app.
                Always return valid JSON and no markdown fences.
                Use this JSON shape:
                %s
                Only include diagramUpdate when the user explicitly asks to create, generate, model, modify, extend, or add BPMN in the editor.
                If the current route is editor and context includes a selectedElement, prefer mode "anchor" and set anchorElementId to that selected element id when the generated flow can be attached there.
                Otherwise use mode "append" for a separate sub-flow, or "replace" only for a clearly requested full replacement or an empty/minimal diagram.
                When you include bpmnXml, it must be complete BPMN 2.0 XML with BPMN DI and preserve these app conventions:
                - use the engine namespace https://bko.dev/schema/bpmn-engine/1.0 with prefix engine
                - REST service tasks use implementation="rest" and engine:taskConfiguration type="rest"
                - bean service tasks use implementation="bean" and engine:taskConfiguration type="bean"
                - FEEL conditions use bpmn:conditionExpression with language="feel" and body starting with "="
                - bean task FEEL inputs go into engine:taskConfiguration inputMapping
                Keep reply concise and mention what was created when diagramUpdate is present.
                """.formatted(RESPONSE_SCHEMA);
    }

    public String buildContextMessage(String route, Map<String, Object> context) {
        String safeRoute = route == null || route.isBlank() ? "processes" : route.trim();
        String serialized = serializeContext(context);

        return """
                Current route: %s

                Page context:
                %s

                Use this to ground your answer in the current screen and selected BPMN/runtime data.
                """.formatted(safeRoute, serialized);
    }

    public String buildUnavailableMessage(String route) {
        return switch (route == null ? "" : route) {
            case "editor" -> "The AI provider is not configured yet. Add `GEMINI_API_KEY` on the server, then I can explain the selected BPMN element, review task configuration, suggest what to model next, and generate BPMN directly into the editor.";
            case "instances" -> "The AI provider is not configured yet. Add `GEMINI_API_KEY` on the server, then I can explain instance state, summarize variables and history, and help interpret BPMN elements.";
            case "performance" -> "The AI provider is not configured yet. Add `GEMINI_API_KEY` on the server, then I can help explain performance-test results and suggest benchmark settings.";
            default -> "The AI provider is not configured yet. Add `GEMINI_API_KEY` on the server, then I can help with deployment steps, BPMN XML, variables, and BPMN explanations.";
        };
    }

    public String buildFailureMessage(String detail) {
        if (detail == null || detail.isBlank()) {
            return "I ran into a problem while contacting the AI provider. Please try again in a moment.";
        }
        return "I ran into a problem while contacting the AI provider: " + detail;
    }

    String serializeContext(Map<String, Object> context) {
        if (context == null || context.isEmpty()) {
            return "{}";
        }

        try {
            String json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsString(context);
            return truncate(json, MAX_CONTEXT_CHARS);
        } catch (JsonProcessingException e) {
            return truncate(String.valueOf(context), MAX_CONTEXT_CHARS);
        }
    }

    static String truncate(String value, int maxLength) {
        if (value == null || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "\n... [truncated]";
    }
}
