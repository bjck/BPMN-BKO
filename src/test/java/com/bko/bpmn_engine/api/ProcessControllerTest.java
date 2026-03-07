package com.bko.bpmn_engine.api;

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

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class ProcessControllerTest {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    @Autowired
    MockMvc mockMvc;

    private String minimalBpmn;

    @BeforeEach
    void setUp() throws Exception {
        Path fixture = Path.of(getClass().getResource("/fixtures/minimal.bpmn").toURI());
        minimalBpmn = Files.readString(fixture, StandardCharsets.UTF_8);
    }

    @Test
    void postProcesses_withMinimalBpmn_returns201() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("bpmnXml", minimalBpmn));
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.processDefinitionId").value("Process_Minimal"));
    }

    @Test
    void postProcessInstances_returns201_instanceReachesCompletedImmediately() throws Exception {
        deployMinimalProcess();

        mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_Minimal\", \"variables\": {}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.state").value("Completed"))
                .andExpect(jsonPath("$.variables").isMap());
    }

    @Test
    void getProcessInstances_returns200_withCorrectState() throws Exception {
        deployMinimalProcess();

        String createResponse = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_Minimal\", \"variables\": {\"foo\": \"bar\"}}"))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString();

        String instanceId = extractJsonString(createResponse, "instanceId");

        mockMvc.perform(get("/v1/process-instances/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instanceId").value(instanceId))
                .andExpect(jsonPath("$.state").value("Completed"))
                .andExpect(jsonPath("$.variables.foo").value("bar"))
                .andExpect(jsonPath("$.processDefinitionId").value("Process_Minimal"));
    }

    @Test
    void getProcessInstances_nonexistent_returns404() throws Exception {
        mockMvc.perform(get("/v1/process-instances/00000000-0000-0000-0000-000000000000"))
                .andExpect(status().isNotFound());
    }

    @Test
    void postProcesses_withInvalidBpmnXml_returns400_withErrorMessage() throws Exception {
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"bpmnXml\": \"<invalid>not bpmn</invalid>\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").exists())
                .andExpect(jsonPath("$.message").isString());
    }

    @Test
    void getProcessBpmn_nonexistent_returns404() throws Exception {
        mockMvc.perform(get("/v1/processes/NonExistentProcess/bpmn"))
                .andExpect(status().isNotFound());
    }

    @Test
    void getProcessBpmn_deployed_returns200_withXml() throws Exception {
        deployMinimalProcess();
        mockMvc.perform(get("/v1/processes/Process_Minimal/bpmn"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_XML))
                .andExpect(content().string(containsString("bpmn:definitions")));
    }

    @Test
    void getServiceTaskLogics_returnsDiscoveredBeans() throws Exception {
        mockMvc.perform(get("/v1/service-task-logics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.serviceTaskLogics").isArray())
                .andExpect(jsonPath("$.serviceTaskLogics[?(@.beanName=='counterServiceTaskLogic')]").exists());
    }

    @Test
    void getProcessInstancesList_returns200_withPaginationFields() throws Exception {
        mockMvc.perform(get("/v1/process-instances"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instances").isArray())
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.pageSize").exists())
                .andExpect(jsonPath("$.totalCount").exists())
                .andExpect(jsonPath("$.hasMore").exists());
    }

    @Test
    void getProcessInstancesList_withPageAndSize_returnsRequestedPage() throws Exception {
        mockMvc.perform(get("/v1/process-instances").param("page", "2").param("size", "10"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.instances").isArray())
                .andExpect(jsonPath("$.page").value(2))
                .andExpect(jsonPath("$.pageSize").value(10));
    }

    private void deployMinimalProcess() throws Exception {
        String body = objectMapper.writeValueAsString(Map.of("bpmnXml", minimalBpmn));
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body));
    }

    private String extractJsonString(String json, String key) {
        try {
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            Object val = map.get(key);
            return val != null ? val.toString() : null;
        } catch (Exception e) {
            return null;
        }
    }
}
