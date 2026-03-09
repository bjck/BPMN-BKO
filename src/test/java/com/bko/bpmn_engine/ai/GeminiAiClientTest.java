package com.bko.bpmn_engine.ai;

import com.bko.bpmn_engine.api.dto.ChatMessageDto;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for GeminiAiClient using a test HTTP server to simulate Gemini API responses.
 */
class GeminiAiClientTest {

    private com.sun.net.httpserver.HttpServer server;
    private GeminiProperties properties;
    private GeminiAiClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = com.sun.net.httpserver.HttpServer.create(new InetSocketAddress(0), 0);
        properties = new GeminiProperties();
        properties.setApiKey("test-key");
        properties.setModel("gemini-test");
        properties.setTimeout(Duration.ofSeconds(5));
        properties.setBaseUrl("http://localhost:" + server.getAddress().getPort());
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    @Test
    void generate_returnsContentFromGeminiResponse() throws Exception {
        server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
            String body = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": [{"text": "Hello from Gemini"}]
                        }
                      }],
                      "usageMetadata": {
                        "promptTokenCount": 10,
                        "candidatesTokenCount": 5,
                        "totalTokenCount": 15
                      }
                    }
                    """;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();

        client = new GeminiAiClient(properties);
        AiProviderRequest request = new AiProviderRequest("You are helpful", "gemini-test",
                List.of(new ChatMessageDto("user", "Hi")));

        AiProviderResponse response = client.generate(request);

        assertEquals("gemini-test", response.model());
        assertEquals("Hello from Gemini", response.content());
        assertEquals(10, response.usage().get("promptTokenCount"));
        assertEquals(5, response.usage().get("candidatesTokenCount"));
        assertEquals(15, response.usage().get("totalTokenCount"));
    }

    @Test
    void generate_throwsWhenHttpError() throws Exception {
        server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
            String body = "{\"error\":\"Invalid request\"}";
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(400, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();

        client = new GeminiAiClient(properties);
        AiProviderRequest request = new AiProviderRequest("", "gemini-test", List.of(new ChatMessageDto("user", "Hi")));

        Exception ex = assertThrows(IllegalStateException.class, () -> client.generate(request));
        assertTrue(ex.getMessage().contains("400"));
        assertTrue(ex.getMessage().contains("Invalid request"));
    }

    @Test
    void generate_throwsWhenNoContent() throws Exception {
        server.createContext("/v1beta/models/gemini-test:generateContent", exchange -> {
            String body = """
                    {
                      "candidates": [{
                        "content": {
                          "parts": []
                        }
                      }]
                    }
                    """;
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.getBytes(StandardCharsets.UTF_8).length);
            exchange.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            exchange.close();
        });
        server.start();

        client = new GeminiAiClient(properties);
        AiProviderRequest request = new AiProviderRequest("", "gemini-test", List.of(new ChatMessageDto("user", "Hi")));

        Exception ex = assertThrows(IllegalStateException.class, () -> client.generate(request));
        assertTrue(ex.getMessage().contains("no content"));
    }
}
