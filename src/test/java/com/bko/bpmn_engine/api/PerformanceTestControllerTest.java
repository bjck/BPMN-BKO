package com.bko.bpmn_engine.api;

import com.bko.bpmn_engine.engine.ProcessEngine;
import com.bko.bpmn_engine.parser.BpmnParser;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicReference;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Tests for PerformanceTestController.
 */
@SpringBootTest
@AutoConfigureMockMvc
class PerformanceTestControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    @Autowired
    ProcessEngine processEngine;

    private String processDefinitionId;

    @BeforeEach
    void setUp() throws Exception {
        BpmnParser parser = new BpmnParser();
        Path fixture = Path.of(getClass().getResource("/fixtures/minimal.bpmn").toURI());
        String bpmnXml = Files.readString(fixture, StandardCharsets.UTF_8);
        processDefinitionId = processEngine.deployProcess(bpmnXml);
    }

    @Test
    void postPerformanceTest_returns200WithMetrics() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "processDefinitionId", processDefinitionId,
                "count", 5
        ));

        mockMvc.perform(post("/v1/performance-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(5))
                .andExpect(jsonPath("$.completed").value(5))
                .andExpect(jsonPath("$.durationMs").isNumber())
                .andExpect(jsonPath("$.instancesPerSecond").isNumber());
    }

    @Test
    void postPerformanceTest_clampsCountToValidRange() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of(
                "processDefinitionId", processDefinitionId,
                "count", 0
        ));

        mockMvc.perform(post("/v1/performance-test")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.requested").value(1))
                .andExpect(jsonPath("$.completed").value(1));
    }
}
