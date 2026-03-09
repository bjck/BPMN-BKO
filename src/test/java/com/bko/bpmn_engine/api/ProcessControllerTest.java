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
    void postProcessInstances_withNullVariables_usesEmptyMap() throws Exception {
        deployMinimalProcess();
        mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_Minimal\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.instanceId").exists())
                .andExpect(jsonPath("$.state").value("Completed"));
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

    @Test
    void getProcesses_returnsDeployedProcessIds() throws Exception {
        mockMvc.perform(get("/v1/processes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processes").isArray());
        deployMinimalProcess();
        mockMvc.perform(get("/v1/processes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.processes", org.hamcrest.Matchers.hasItem("Process_Minimal")));
    }

    @Test
    void messageStart_startsProcessByMessage() throws Exception {
        String messageStartBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_MessageStart" name="Message Start">
                    <bpmn:startEvent id="Start_1" engine:messageRef="OrderReceived">
                      <bpmn:outgoing>Flow_1</bpmn:outgoing>
                    </bpmn:startEvent>
                    <bpmn:serviceTask id="Task_1" implementation="java">
                      <bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
                    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="End_1"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", messageStartBpmn))))
                .andExpect(status().isCreated());
        mockMvc.perform(post("/v1/process-instances/message-start")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of(
                                "processDefinitionId", "Process_MessageStart",
                                "messageRef", "OrderReceived",
                                "variables", Map.of("orderId", "O1")))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.state").value("Completed"));
    }

    @Test
    void completeTask_completesUserTaskAndAdvances() throws Exception {
        String userTaskBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="Process_UserTask" name="User Task">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="UT1" name="Approve">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:userTask>
                    <bpmn:serviceTask id="T1" implementation="java">
                      <bpmn:incoming>F2</bpmn:incoming><bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F3</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="UT1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="UT1" targetRef="T1"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="T1" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", userTaskBpmn))))
                .andExpect(status().isCreated());
        String createResp = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_UserTask\", \"variables\": {}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = extractJsonString(createResp, "instanceId");
        mockMvc.perform(post("/v1/process-instances/" + instanceId + "/complete-task/UT1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variables\": {\"approved\": true}}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/v1/process-instances/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Completed"));
    }

    @Test
    void triggerCatch_triggersCatchEventByInstanceAndNode() throws Exception {
        String catchBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_Catch" name="Catch">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="T1" implementation="java">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:intermediateCatchEvent id="Catch_1" engine:messageRef="Reply">
                      <bpmn:incoming>F2</bpmn:incoming><bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:serviceTask id="T2" implementation="java">
                      <bpmn:incoming>F3</bpmn:incoming><bpmn:outgoing>F4</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="T1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="T1" targetRef="Catch_1"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Catch_1" targetRef="T2"/>
                    <bpmn:sequenceFlow id="F4" sourceRef="T2" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", catchBpmn))))
                .andExpect(status().isCreated());
        String createResp = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_Catch\", \"variables\": {}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = extractJsonString(createResp, "instanceId");
        mockMvc.perform(post("/v1/process-instances/" + instanceId + "/trigger-catch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"nodeId\": \"Catch_1\", \"variables\": {\"payload\": \"done\"}}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/process-instances/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Completed"));
    }

    @Test
    void triggerCatchByMessageRef_triggersWaitingCatchByMessageRef() throws Exception {
        String catchBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL"
                                  xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0">
                  <bpmn:process id="Process_CatchMsg" name="Catch Msg">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:serviceTask id="T1" implementation="java">
                      <bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:intermediateCatchEvent id="Catch_1" engine:messageRef="Reply">
                      <bpmn:incoming>F2</bpmn:incoming><bpmn:outgoing>F3</bpmn:outgoing>
                    </bpmn:intermediateCatchEvent>
                    <bpmn:serviceTask id="T2" implementation="java">
                      <bpmn:incoming>F3</bpmn:incoming><bpmn:outgoing>F4</bpmn:outgoing>
                    </bpmn:serviceTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F4</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="T1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="T1" targetRef="Catch_1"/>
                    <bpmn:sequenceFlow id="F3" sourceRef="Catch_1" targetRef="T2"/>
                    <bpmn:sequenceFlow id="F4" sourceRef="T2" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", catchBpmn))))
                .andExpect(status().isCreated());
        String createResp = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_CatchMsg\", \"variables\": {\"correlationKey\": \"c1\"}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = extractJsonString(createResp, "instanceId");
        mockMvc.perform(post("/v1/bpmn-events/trigger-catch")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"messageRef\": \"Reply\", \"correlationKey\": \"c1\", \"variables\":{}}"))
                .andExpect(status().isNoContent());
        mockMvc.perform(get("/v1/process-instances/" + instanceId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.state").value("Completed"));
    }

    @Test
    void cancelInstance_cancelsActiveInstance() throws Exception {
        String userTaskBpmn = """
                <?xml version="1.0" encoding="UTF-8"?>
                <bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL">
                  <bpmn:process id="P_Cancel" name="Cancel">
                    <bpmn:startEvent id="Start"><bpmn:outgoing>F1</bpmn:outgoing></bpmn:startEvent>
                    <bpmn:userTask id="UT1"><bpmn:incoming>F1</bpmn:incoming><bpmn:outgoing>F2</bpmn:outgoing></bpmn:userTask>
                    <bpmn:endEvent id="End"><bpmn:incoming>F2</bpmn:incoming></bpmn:endEvent>
                    <bpmn:sequenceFlow id="F1" sourceRef="Start" targetRef="UT1"/>
                    <bpmn:sequenceFlow id="F2" sourceRef="UT1" targetRef="End"/>
                  </bpmn:process>
                </bpmn:definitions>
                """;
        mockMvc.perform(post("/v1/processes")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(Map.of("bpmnXml", userTaskBpmn))))
                .andExpect(status().isCreated());
        String createResp = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"P_Cancel\", \"variables\": {}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = extractJsonString(createResp, "instanceId");
        mockMvc.perform(delete("/v1/process-instances/" + instanceId))
                .andExpect(status().isNoContent());
    }

    @Test
    void restartFailed_nonexistent_returns404() throws Exception {
        mockMvc.perform(post("/v1/process-instances/00000000-0000-0000-0000-000000000000/restart"))
                .andExpect(status().isNotFound());
    }

    @Test
    void restartFailed_completedInstance_returns400() throws Exception {
        deployMinimalProcess();
        String createResp = mockMvc.perform(post("/v1/process-instances")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"processDefinitionId\": \"Process_Minimal\", \"variables\": {}}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        String instanceId = extractJsonString(createResp, "instanceId");
        mockMvc.perform(post("/v1/process-instances/" + instanceId + "/restart"))
                .andExpect(status().isConflict());
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
