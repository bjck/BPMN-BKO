// @ts-check
/** Mock API responses for SPA E2E tests (no backend required). */

const MOCK_PROCESS_ID = 'Process_Counting';
const MOCK_INSTANCE_ID = '11111111-2222-3333-4444-555555555555';

/** Minimal BPMN 2.0 XML with bpmndi so bpmn-js can render */
const MOCK_BPMN_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI">
  <bpmn:process id="Process_Counting" name="Counting">
    <bpmn:startEvent id="Start_1"><bpmn:outgoing>Flow_1</bpmn:outgoing></bpmn:startEvent>
    <bpmn:sequenceFlow id="Flow_1" sourceRef="Start_1" targetRef="Task_1"/>
    <bpmn:serviceTask id="Task_1" name="Step"><bpmn:incoming>Flow_1</bpmn:incoming><bpmn:outgoing>Flow_2</bpmn:outgoing></bpmn:serviceTask>
    <bpmn:sequenceFlow id="Flow_2" sourceRef="Task_1" targetRef="End_1"/>
    <bpmn:endEvent id="End_1"><bpmn:incoming>Flow_2</bpmn:incoming></bpmn:endEvent>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_1">
    <bpmndi:BPMNPlane id="BPMNPlane_1" bpmnElement="Process_Counting">
      <bpmndi:BPMNShape id="Start_1_di" bpmnElement="Start_1"><dc:Bounds x="152" y="102" width="36" height="36"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Task_1_di" bpmnElement="Task_1"><dc:Bounds x="240" y="80" width="100" height="80"/></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="End_1_di" bpmnElement="End_1"><dc:Bounds x="382" y="102" width="36" height="36"/></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

const mockProcesses = { processes: [MOCK_PROCESS_ID] };

const mockInstances = {
  instances: [
    {
      instanceId: MOCK_INSTANCE_ID,
      processDefinitionId: MOCK_PROCESS_ID,
      currentNodeId: null,
      state: 'Completed',
      variables: { counter: 1 },
    },
  ],
  totalCount: 1,
  hasMore: false,
};

const mockInstanceDetail = {
  instanceId: MOCK_INSTANCE_ID,
  processDefinitionId: MOCK_PROCESS_ID,
  currentNodeId: null,
  state: 'Completed',
  variables: { counter: 1 },
  createdAt: '2025-01-15T10:00:00Z',
  completedAt: '2025-01-15T10:00:01Z',
};

const mockHistory = {
  events: [
    { eventType: 'ProcessInstanceCreated', currentNodeId: 'Start_1', variables: {}, createdAt: '2025-01-15T10:00:00Z' },
    { eventType: 'TaskCompleted', currentNodeId: 'Task_1', variables: { counter: 1 }, createdAt: '2025-01-15T10:00:01Z' },
  ],
  taskExecutions: [{ taskId: 'Task_1', taskType: 'service', durationMs: 10, startedAt: '2025-01-15T10:00:00Z' }],
};

const mockPerfResponse = {
  requested: 10,
  completed: 10,
  durationMs: 500,
  instancesPerSecond: 20,
};

module.exports = {
  MOCK_INSTANCE_ID,
  MOCK_PROCESS_ID,
  MOCK_BPMN_XML,
  mockProcesses,
  mockInstances,
  mockInstanceDetail,
  mockHistory,
  mockPerfResponse,
};
