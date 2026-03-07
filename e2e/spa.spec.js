// @ts-check
/**
 * SPA E2E tests with mocked API. No backend required.
 * Verifies the split between the dedicated BPMN editor and the read-only instance viewer.
 */
const { test, expect } = require('@playwright/test');
const {
  MOCK_INSTANCE_ID,
  MOCK_PROCESS_ID,
  MOCK_BPMN_XML,
  mockProcesses,
  mockInstances,
  mockInstanceDetail,
  mockHistory,
  mockPerfResponse,
} = require('./fixtures/mock-data');

const AI_REVIEW_PROCESS_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI" xmlns:engine="https://bko.dev/schema/bpmn-engine/1.0" xmlns:camunda="http://camunda.org/schema/1.0/bpmn">
  <bpmn:process id="Process_AI_Review" name="AI Review Flow" isExecutable="true">
    <bpmn:startEvent id="StartEvent_AI" name="Start">
      <bpmn:outgoing>Flow_AI_1</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:userTask id="Task_Review" name="Review request" camunda:assignee="demo">
      <bpmn:incoming>Flow_AI_1</bpmn:incoming>
      <bpmn:outgoing>Flow_AI_2</bpmn:outgoing>
    </bpmn:userTask>
    <bpmn:serviceTask id="Task_Call_Api" name="Call API" implementation="rest">
      <bpmn:incoming>Flow_AI_2</bpmn:incoming>
      <bpmn:outgoing>Flow_AI_3</bpmn:outgoing>
      <bpmn:extensionElements>
        <engine:taskConfiguration type="rest" method="POST" url='= "https://api.example.test/orders/" + orderId' authenticationType="bearer" bearerToken="= authToken" headers='= { Accept: "application/json" }' body='= { orderId: orderId }' resultVariable="apiResult" timeoutSeconds="10" />
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:serviceTask id="Task_Invoke_Bean" name="Invoke Bean" implementation="bean">
      <bpmn:incoming>Flow_AI_3</bpmn:incoming>
      <bpmn:outgoing>Flow_AI_4</bpmn:outgoing>
      <bpmn:extensionElements>
        <engine:taskConfiguration type="bean" beanName="counterServiceTaskLogic" inputMapping='= { step: 2, customerId: customerId }' resultVariable="beanResult" />
      </bpmn:extensionElements>
    </bpmn:serviceTask>
    <bpmn:exclusiveGateway id="Gateway_Decision" name="Approved?">
      <bpmn:incoming>Flow_AI_4</bpmn:incoming>
      <bpmn:outgoing>Flow_AI_5</bpmn:outgoing>
      <bpmn:outgoing>Flow_AI_6</bpmn:outgoing>
    </bpmn:exclusiveGateway>
    <bpmn:endEvent id="EndEvent_Approved" name="Approved">
      <bpmn:incoming>Flow_AI_5</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:endEvent id="EndEvent_Rejected" name="Rejected">
      <bpmn:incoming>Flow_AI_6</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_AI_1" sourceRef="StartEvent_AI" targetRef="Task_Review" />
    <bpmn:sequenceFlow id="Flow_AI_2" sourceRef="Task_Review" targetRef="Task_Call_Api" />
    <bpmn:sequenceFlow id="Flow_AI_3" sourceRef="Task_Call_Api" targetRef="Task_Invoke_Bean" />
    <bpmn:sequenceFlow id="Flow_AI_4" sourceRef="Task_Invoke_Bean" targetRef="Gateway_Decision" />
    <bpmn:sequenceFlow id="Flow_AI_5" sourceRef="Gateway_Decision" targetRef="EndEvent_Approved">
      <bpmn:conditionExpression language="feel">= approved = true</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
    <bpmn:sequenceFlow id="Flow_AI_6" sourceRef="Gateway_Decision" targetRef="EndEvent_Rejected">
      <bpmn:conditionExpression language="feel">= approved = false</bpmn:conditionExpression>
    </bpmn:sequenceFlow>
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_AI_Review">
    <bpmndi:BPMNPlane id="BPMNPlane_AI_Review" bpmnElement="Process_AI_Review">
      <bpmndi:BPMNShape id="Shape_StartEvent_AI" bpmnElement="StartEvent_AI"><dc:Bounds x="120" y="160" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_Review" bpmnElement="Task_Review"><dc:Bounds x="220" y="138" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_Call_Api" bpmnElement="Task_Call_Api"><dc:Bounds x="380" y="138" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_Invoke_Bean" bpmnElement="Task_Invoke_Bean"><dc:Bounds x="540" y="138" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Gateway_Decision" bpmnElement="Gateway_Decision"><dc:Bounds x="700" y="153" width="50" height="50" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndEvent_Approved" bpmnElement="EndEvent_Approved"><dc:Bounds x="820" y="110" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndEvent_Rejected" bpmnElement="EndEvent_Rejected"><dc:Bounds x="820" y="220" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

const AI_ANCHOR_FLOW_XML = `<?xml version="1.0" encoding="UTF-8"?>
<bpmn:definitions xmlns:bpmn="http://www.omg.org/spec/BPMN/20100524/MODEL" xmlns:bpmndi="http://www.omg.org/spec/BPMN/20100524/DI" xmlns:dc="http://www.omg.org/spec/DD/20100524/DC" xmlns:di="http://www.omg.org/spec/DD/20100524/DI">
  <bpmn:process id="Process_AI_Anchor" name="Anchor Flow" isExecutable="true">
    <bpmn:startEvent id="StartEvent_AI_Anchor" name="AI Start">
      <bpmn:outgoing>Flow_ai_start_to_Task_Follow_Up</bpmn:outgoing>
    </bpmn:startEvent>
    <bpmn:task id="Task_Follow_Up" name="Follow up">
      <bpmn:incoming>Flow_ai_start_to_Task_Follow_Up</bpmn:incoming>
      <bpmn:outgoing>Flow_ai_follow_up_to_End</bpmn:outgoing>
    </bpmn:task>
    <bpmn:endEvent id="EndEvent_AI_Anchor" name="Done">
      <bpmn:incoming>Flow_ai_follow_up_to_End</bpmn:incoming>
    </bpmn:endEvent>
    <bpmn:sequenceFlow id="Flow_ai_start_to_Task_Follow_Up" sourceRef="StartEvent_AI_Anchor" targetRef="Task_Follow_Up" />
    <bpmn:sequenceFlow id="Flow_ai_follow_up_to_End" sourceRef="Task_Follow_Up" targetRef="EndEvent_AI_Anchor" />
  </bpmn:process>
  <bpmndi:BPMNDiagram id="BPMNDiagram_AI_Anchor">
    <bpmndi:BPMNPlane id="BPMNPlane_AI_Anchor" bpmnElement="Process_AI_Anchor">
      <bpmndi:BPMNShape id="Shape_StartEvent_AI_Anchor" bpmnElement="StartEvent_AI_Anchor"><dc:Bounds x="120" y="150" width="36" height="36" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_Task_Follow_Up" bpmnElement="Task_Follow_Up"><dc:Bounds x="220" y="128" width="100" height="80" /></bpmndi:BPMNShape>
      <bpmndi:BPMNShape id="Shape_EndEvent_AI_Anchor" bpmnElement="EndEvent_AI_Anchor"><dc:Bounds x="380" y="150" width="36" height="36" /></bpmndi:BPMNShape>
    </bpmndi:BPMNPlane>
  </bpmndi:BPMNDiagram>
</bpmn:definitions>`;

function installMockApi(page) {
  page.route('**/v1/ai/chat', async (route) => {
    const body = JSON.parse(route.request().postData() || '{}');
    const selectedElementId = body.context?.selectedElement?.id || body.context?.viewer?.selectedElement?.id || 'none';
    const latestMessage = (body.messages || []).at(-1)?.content?.toLowerCase() || '';

    if (latestMessage.includes('review process')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          conversationId: body.conversationId || 'conversation-1',
          model: 'gemini-test',
          providerConfigured: true,
          reply: {
            role: 'assistant',
            content: 'I created an AI review flow with a user task, REST service task, bean task, and FEEL gateway conditions.',
          },
          usage: {},
          diagramUpdate: {
            mode: 'replace',
            anchorElementId: '',
            summary: 'Created an AI review flow.',
            bpmnXml: AI_REVIEW_PROCESS_XML,
          },
        }),
      });
    }

    if (latestMessage.includes('follow up after selected element')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          conversationId: body.conversationId || 'conversation-1',
          model: 'gemini-test',
          providerConfigured: true,
          reply: {
            role: 'assistant',
            content: 'I added a follow-up step after the selected element.',
          },
          usage: {},
          diagramUpdate: {
            mode: 'anchor',
            anchorElementId: selectedElementId === 'none' ? 'StartEvent_1' : selectedElementId,
            summary: 'Anchored a follow-up task to the selected element.',
            bpmnXml: AI_ANCHOR_FLOW_XML,
          },
        }),
      });
    }

    if (latestMessage.includes('broken diagram')) {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({
          conversationId: body.conversationId || 'conversation-1',
          model: 'gemini-test',
          providerConfigured: true,
          reply: {
            role: 'assistant',
            content: 'I tried to generate a diagram.',
          },
          usage: {},
          diagramUpdate: {
            mode: 'append',
            anchorElementId: selectedElementId,
            summary: 'Tried to add a broken diagram.',
            bpmnXml: '<broken-diagram',
          },
        }),
      });
    }

    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        conversationId: body.conversationId || 'conversation-1',
        model: 'gemini-test',
        providerConfigured: true,
        reply: {
          role: 'assistant',
          content: `Context route: ${body.route}; selected element: ${selectedElementId}`,
        },
        usage: {},
      }),
    });
  });

  page.route('**/v1/service-task-logics', (route) => {
    return route.fulfill({
      status: 200,
      contentType: 'application/json',
      body: JSON.stringify({
        serviceTaskLogics: [
          {
            beanName: 'counterServiceTaskLogic',
            displayName: 'Counter Logic',
            description: "Increments the process variable 'counter' by the FEEL input 'step' or 1 by default.",
          },
        ],
      }),
    });
  });

  page.route('**/v1/processes', (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockProcesses) });
    }

    if (route.request().method() === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ processDefinitionId: MOCK_PROCESS_ID }),
      });
    }

    return route.continue();
  });

  // List endpoint: match /v1/process-instances or /v1/process-instances?page=... (glob can miss query string)
  page.route(/\/v1\/process-instances(\?|$)/, (route) => {
    if (route.request().method() === 'GET') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockInstances) });
    }

    if (route.request().method() === 'POST') {
      return route.fulfill({
        status: 200,
        contentType: 'application/json',
        body: JSON.stringify({ instanceId: MOCK_INSTANCE_ID }),
      });
    }

    return route.continue();
  });

  page.route(`**/v1/process-instances/${MOCK_INSTANCE_ID}**`, (route) => {
    const url = route.request().url();

    if (url.includes('/history')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockHistory) });
    }

    if (url.includes('/complete-task/')) {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify({ ok: true }) });
    }

    if (route.request().method() === 'DELETE') {
      return route.fulfill({ status: 200 });
    }

    return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockInstanceDetail) });
  });

  page.route(`**/v1/processes/${encodeURIComponent(MOCK_PROCESS_ID)}/bpmn**`, (route) => {
    return route.fulfill({ status: 200, contentType: 'application/xml', body: MOCK_BPMN_XML });
  });

  page.route('**/v1/performance-test', (route) => {
    if (route.request().method() === 'POST') {
      return route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(mockPerfResponse) });
    }

    return route.continue();
  });
}

test.describe('SPA', () => {
  test.beforeEach(async ({ page }) => {
    installMockApi(page);
  });

  test('navigates between Deploy, Editor, Instances, and Performance pages', async ({ page }) => {
    await page.goto('/#/processes');
    await expect(page.locator('#processes-list')).toContainText(MOCK_PROCESS_ID, { timeout: 15000 });

    await page.locator('a[href="#/editor"]').click();
    await expect(page).toHaveURL(/#\/editor/);
    await expect(page.locator('#bpmn-editor-canvas')).toBeVisible();

    await page.locator('a[href="#/instances"]').click();
    await expect(page).toHaveURL(/#\/instances/);
    await expect(page.locator('#instances-list')).toContainText(MOCK_INSTANCE_ID);
    await expect(page.locator('#bpmn-viewer-container')).toBeVisible();

    await page.locator('a[href="#/performance"]').click();
    await expect(page).toHaveURL(/#\/performance/);
    await expect(page.locator('#perf-process-select')).toBeVisible();
  });

  test('editor page renders a dedicated modeler with palette and actions', async ({ page }) => {
    await page.goto('/#/editor');

    await expect(page.getByRole('button', { name: /new diagram/i })).toBeVisible({ timeout: 15000 });
    await expect(page.getByRole('button', { name: /copy xml/i })).toBeVisible();
    await expect(page.getByRole('button', { name: /deploy process/i })).toBeVisible();
    await expect(page.locator('#bpmn-editor-canvas .djs-container')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#bpmn-editor-canvas .djs-palette')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#editor-properties-placeholder')).toBeVisible();
  });

  test('ai chat bubble opens and closes globally', async ({ page }) => {
    await page.goto('/#/processes');

    await expect(page.locator('#ai-chat-toggle')).toBeVisible();
    await page.locator('#ai-chat-toggle').click();
    await expect(page.locator('#ai-chat-panel')).toBeVisible();
    await expect(page.locator('#ai-chat-messages')).toContainText('deployment steps');
    await page.locator('#ai-chat-close').click();
    await expect(page.locator('#ai-chat-panel')).toBeHidden();
  });

  test('ai chat sends current route context to backend', async ({ page }) => {
    await page.goto('/#/editor');
    await page.locator('#ai-chat-toggle').click();

    await page.locator('#ai-chat-input').fill('Explain this page');
    await page.locator('#ai-chat-send').click();

    await expect(page.locator('#ai-chat-messages')).toContainText('Context route: editor');
  });

  test('ai chat can generate BPMN with app-specific service tasks', async ({ page }) => {
    await page.goto('/#/editor');
    await page.locator('#ai-chat-toggle').click();

    await page.locator('#ai-chat-input').fill('Create a review process with a REST API call and bean task');
    await page.locator('#ai-chat-send').click();

    const restTask = page.locator('#bpmn-editor-canvas .djs-element[data-element-id="Task_Call_Api"]');
    await expect(restTask).toBeVisible({ timeout: 15000 });
    await restTask.click();
    await expect(page.locator('#prop-service-task-mode')).toHaveValue('rest');
    await expect(page.locator('#prop-rest-url')).toHaveValue('= "https://api.example.test/orders/" + orderId');

    const beanTask = page.locator('#bpmn-editor-canvas .djs-element[data-element-id="Task_Invoke_Bean"]');
    await expect(beanTask).toBeVisible();
    await beanTask.click();
    await expect(page.locator('#prop-service-task-mode')).toHaveValue('bean');
    await expect(page.locator('#prop-bean-name')).toHaveValue('counterServiceTaskLogic');
    await expect(page.locator('#prop-bean-input-mapping')).toHaveValue('= { step: 2, customerId: customerId }');
  });

  test('ai chat can anchor generated BPMN to the selected editor element', async ({ page }) => {
    await page.goto('/#/editor');
    await page.locator('#bpmn-editor-canvas .djs-element[data-element-id="StartEvent_1"]').click();
    await page.locator('#ai-chat-toggle').click();

    await page.locator('#ai-chat-input').fill('Add follow up after selected element');
    await page.locator('#ai-chat-send').click();

    await expect(page.locator('#bpmn-editor-canvas .djs-element[data-element-id="Task_Follow_Up"]')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#bpmn-editor-canvas .djs-element[data-element-id="StartEvent_AI_Anchor"]')).toHaveCount(0);
  });

  test('ai chat leaves the current diagram untouched when generated BPMN is invalid', async ({ page }) => {
    await page.goto('/#/editor');
    await page.locator('#ai-chat-toggle').click();

    await page.locator('#ai-chat-input').fill('Generate a broken diagram');
    await page.locator('#ai-chat-send').click();

    await expect(page.locator('#bpmn-editor-canvas .djs-element[data-element-id="StartEvent_1"]')).toBeVisible();
    await expect(page.locator('#bpmn-editor-canvas .djs-element[data-element-id="EndEvent_1"]')).toBeVisible();
    await expect(page.locator('#ai-chat-messages')).toContainText('I tried to generate a diagram.');
  });

  test('deploy page shows process list and create-instance form', async ({ page }) => {
    await page.goto('/#/processes');

    await expect(page.getByText('Deployed Processes')).toBeVisible({ timeout: 10000 });
    await expect(page.locator('#processes-list')).toContainText(MOCK_PROCESS_ID);
    await expect(page.locator('#create-process-select')).toContainText(MOCK_PROCESS_ID);
    await expect(page.getByText('Deploy Process (BPMN XML)')).toBeVisible();
  });

  test('instances page stays read-only and loads BPMN viewer for selected instance', async ({ page }) => {
    await page.goto('/#/instances');

    await expect(page.locator('#instances-list')).toContainText(MOCK_INSTANCE_ID, { timeout: 10000 });
    await page.locator(`#instances-list .list-item[data-id="${MOCK_INSTANCE_ID}"]`).click();

    await expect(page.locator('#instance-info')).toContainText(MOCK_INSTANCE_ID);
    await expect(page.locator('#instance-info')).toContainText('Completed');
    await expect(page.locator('#bpmn-viewer-container .bjs-container')).toBeVisible({ timeout: 15000 });
    await expect(page.locator('#bpmn-viewer-container .bjs-container canvas, #bpmn-viewer-container .bjs-container svg').first()).toBeVisible({ timeout: 5000 });
    await expect(page.locator('#bpmn-viewer-container .djs-palette')).toHaveCount(0);
    await expect(page.locator('#bpmn-viewer-container .djs-context-pad')).toHaveCount(0);
    await expect(page.getByRole('button', { name: /view diagram/i })).toHaveCount(0);
  });

  test('clicking a BPMN element in the instance viewer shows its variables', async ({ page }) => {
    await page.goto('/#/instances');
    await page.locator(`#instances-list .list-item[data-id="${MOCK_INSTANCE_ID}"]`).click();

    const shape = page.locator('#bpmn-viewer-container .djs-element[data-element-id="Task_1"]');
    await expect(shape).toBeVisible({ timeout: 15000 });
    await shape.click();

    await expect(page.locator('#bpmn-element-detail')).toBeVisible();
    await expect(page.locator('#bpmn-element-variables')).toContainText('counter');
  });

  test('performance page shows form and results after run', async ({ page }) => {
    await page.goto('/#/performance');

    await expect(page.locator('#perf-process-select')).toContainText(MOCK_PROCESS_ID, { timeout: 10000 });
    await page.getByRole('button', { name: /run test/i }).click();
    await expect(page.locator('#perf-results')).toBeVisible();
    await expect(page.locator('#perf-results-content')).toContainText('Throughput');
    await expect(page.locator('#perf-results-content')).toContainText('20');
  });

  test('hash / defaults to processes page', async ({ page }) => {
    await page.goto('/');
    await expect(page.locator('#processes-list')).toContainText(MOCK_PROCESS_ID, { timeout: 15000 });

    await page.goto('/#/');
    await expect(page.locator('#processes-list')).toBeVisible({ timeout: 5000 });
  });
});
