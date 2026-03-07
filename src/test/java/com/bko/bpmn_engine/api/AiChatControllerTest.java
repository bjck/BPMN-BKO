package com.bko.bpmn_engine.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AiChatControllerTest {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Test
    void postAiChat_withoutGeminiKey_returnsHelpfulSetupMessage() throws Exception {
        String body = OBJECT_MAPPER.writeValueAsString(Map.of(
                "route", "editor",
                "context", Map.of("selectedElement", Map.of("id", "Task_1")),
                "messages", List.of(Map.of("role", "user", "content", "Explain this"))
        ));

        mockMvc.perform(post("/v1/ai/chat")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.providerConfigured").value(false))
                .andExpect(jsonPath("$.reply.role").value("assistant"))
                .andExpect(jsonPath("$.reply.content").value(org.hamcrest.Matchers.containsString("GEMINI_API_KEY")))
                .andExpect(jsonPath("$.diagramUpdate").doesNotExist());
    }
}
