package com.bko.bpmn_engine.ai;

import com.bko.bpmn_engine.api.dto.AiChatRequest;
import com.bko.bpmn_engine.api.dto.AiChatResponse;
import com.bko.bpmn_engine.api.dto.ChatMessageDto;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class AiAssistantServiceTest {

    @Test
    void chat_includesRouteContextInProviderRequest() {
        AtomicReference<AiProviderRequest> captured = new AtomicReference<>();
        AiProviderClient providerClient = request -> {
            captured.set(request);
            return new AiProviderResponse("gemini-test", "Helpful reply", Map.of("totalTokenCount", 12));
        };
        AiAssistantService service = new AiAssistantService(
                providerClient,
                new AiPromptBuilder(new ObjectMapper()),
                configuredProperties()
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                "conversation-1",
                "editor",
                Map.of("selectedElement", Map.of("id", "Task_1", "type", "bpmn:ServiceTask")),
                List.of(new ChatMessageDto("user", "Explain this task"))
        ));

        assertTrue(response.providerConfigured());
        assertEquals("Helpful reply", response.reply().content());
        assertNull(response.diagramUpdate());
        assertNotNull(captured.get());
        assertTrue(captured.get().messages().getFirst().content().contains("Current route: editor"));
        assertTrue(captured.get().messages().getFirst().content().contains("Task_1"));
    }

    @Test
    void chat_parsesStructuredDiagramGenerationPayload() {
        AiAssistantService service = new AiAssistantService(
                request -> new AiProviderResponse("gemini-test", """
                        {
                          "reply": "I created a review flow.",
                          "diagramUpdate": {
                            "mode": "append",
                            "anchorElementId": "",
                            "summary": "Added a review flow",
                            "bpmnXml": "<bpmn:definitions/>"
                          }
                        }
                        """, Map.of()),
                new AiPromptBuilder(new ObjectMapper()),
                configuredProperties()
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                "conversation-2",
                "editor",
                Map.of("selectedElement", Map.of("id", "Task_1")),
                List.of(new ChatMessageDto("user", "Create a review process"))
        ));

        assertEquals("I created a review flow.", response.reply().content());
        assertNotNull(response.diagramUpdate());
        assertEquals("append", response.diagramUpdate().mode());
        assertEquals("Added a review flow", response.diagramUpdate().summary());
    }

    @Test
    void chat_returnsFriendlyFallbackWhenProviderFails() {
        AiAssistantService service = new AiAssistantService(
                request -> {
                    throw new IllegalStateException("Gemini returned HTTP 400: {\"error\":\"boom\"}");
                },
                new AiPromptBuilder(new ObjectMapper()),
                configuredProperties()
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                null,
                "instances",
                Map.of("selectedInstance", Map.of("instanceId", "123")),
                List.of(new ChatMessageDto("user", "What is happening?"))
        ));

        assertTrue(response.providerConfigured());
        assertTrue(response.reply().content().contains("problem while contacting the AI provider"));
        assertTrue(response.reply().content().contains("Gemini returned HTTP 400"));
        assertEquals("Gemini returned HTTP 400: {\"error\":\"boom\"}", response.usage().get("error"));
        assertNull(response.diagramUpdate());
    }

    @Test
    void chat_usesDiagramSummaryWhenReplyBlank() {
        AiAssistantService service = new AiAssistantService(
                request -> new AiProviderResponse("gemini-test", """
                        {
                          "reply": "",
                          "diagramUpdate": {
                            "mode": "append",
                            "anchorElementId": "Task_1",
                            "summary": "Added approval step",
                            "bpmnXml": "<bpmn:definitions/>"
                          }
                        }
                        """, Map.of()),
                new AiPromptBuilder(new ObjectMapper()),
                configuredProperties()
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                "conv-1",
                "editor",
                Map.of("selectedElement", Map.of("id", "Task_1")),
                List.of(new ChatMessageDto("user", "Add approval"))
        ));

        assertEquals("Added approval step", response.reply().content());
        assertNotNull(response.diagramUpdate());
    }

    @Test
    void chat_returnsDoneWhenReplyAndSummaryBlank() {
        AiAssistantService service = new AiAssistantService(
                request -> new AiProviderResponse("gemini-test", """
                        {
                          "reply": "",
                          "diagramUpdate": {
                            "mode": "append",
                            "anchorElementId": "",
                            "summary": "",
                            "bpmnXml": "<bpmn:definitions/>"
                          }
                        }
                        """, Map.of()),
                new AiPromptBuilder(new ObjectMapper()),
                configuredProperties()
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                "conv-1",
                "editor",
                Map.of(),
                List.of(new ChatMessageDto("user", "Do something"))
        ));

        assertEquals("Done.", response.reply().content());
    }

    @Test
    void chat_returnsSetupHintWhenApiKeyMissing() {
        GeminiProperties properties = new GeminiProperties();
        properties.setTimeout(Duration.ofSeconds(5));
        AiAssistantService service = new AiAssistantService(
                request -> fail("Provider should not be called when API key is missing"),
                new AiPromptBuilder(new ObjectMapper()),
                properties
        );

        AiChatResponse response = service.chat(new AiChatRequest(
                null,
                "processes",
                Map.of(),
                List.of(new ChatMessageDto("user", "Help"))
        ));

        assertFalse(response.providerConfigured());
        assertTrue(response.reply().content().contains("GEMINI_API_KEY"));
        assertNull(response.diagramUpdate());
    }

    private static GeminiProperties configuredProperties() {
        GeminiProperties properties = new GeminiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gemini-test");
        properties.setTimeout(Duration.ofSeconds(5));
        return properties;
    }
}
