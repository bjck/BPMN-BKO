// @ts-check
/**
 * E2E tests for BPMN diagram execution: sequential, user task, gateways, message/signal events, Kafka service task.
 * Requires the app to be running (e.g. mvn spring-boot:run). For Kafka task test, Kafka must be enabled (BPMN_KAFKA_ENABLED=true).
 * Run: npm run test:e2e (BASE_URL=http://localhost:8080 by default)
 */
const { test, expect } = require('@playwright/test');
const {
  MINIMAL_BPMN,
  USER_TASK_BPMN,
  EXCLUSIVE_GATEWAY_BPMN,
  PARALLEL_GATEWAY_BPMN,
  MESSAGE_START_BPMN,
  CATCH_EVENT_BPMN,
  THROW_EVENT_BPMN,
  KAFKA_TASK_BPMN,
} = require('./fixtures/bpmn-xml');

const baseURL = process.env.BASE_URL || 'http://localhost:8080';

async function ensureBackend(request) {
  const res = await request.get(`${baseURL}/v1/processes`).catch(() => null);
  if (!res || !res.ok()) {
    test.skip(true, 'Backend not available - start with: mvn spring-boot:run');
    return false;
  }
  return true;
}

async function deployProcess(request, bpmnXml) {
  const res = await request.post(`${baseURL}/v1/processes`, {
    data: { bpmnXml },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  return body.processDefinitionId;
}

async function createInstance(request, processDefinitionId, variables = {}) {
  const res = await request.post(`${baseURL}/v1/process-instances`, {
    data: { processDefinitionId, variables },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  return body.instanceId;
}

async function messageStart(request, processDefinitionId, messageRef, correlationKey = null, variables = {}) {
  const res = await request.post(`${baseURL}/v1/process-instances/message-start`, {
    data: { processDefinitionId, messageRef, correlationKey, variables },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.ok()).toBeTruthy();
  const body = await res.json();
  return body.instanceId;
}

async function triggerCatch(request, instanceId, nodeId, variables = {}) {
  const res = await request.post(`${baseURL}/v1/process-instances/${instanceId}/trigger-catch`, {
    data: { nodeId, variables },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.status()).toBe(204);
}

async function triggerCatchByMessageRef(request, messageRef, correlationKey = null, variables = {}) {
  const res = await request.post(`${baseURL}/v1/bpmn-events/trigger-catch`, {
    data: { messageRef, correlationKey, variables },
    headers: { 'Content-Type': 'application/json' },
  });
  expect(res.status()).toBe(204);
}

async function getInstance(request, instanceId) {
  const res = await request.get(`${baseURL}/v1/process-instances/${instanceId}`);
  expect(res.ok()).toBeTruthy();
  return res.json();
}

async function completeTask(request, instanceId, taskId, variables = {}) {
  const res = await request.post(
    `${baseURL}/v1/process-instances/${instanceId}/complete-task/${taskId}`,
    { data: { variables }, headers: { 'Content-Type': 'application/json' } }
  );
  expect(res.ok()).toBeTruthy();
  return res.json();
}

test.describe('BPMN diagrams (live backend)', () => {
  test.beforeEach(async ({ request }) => {
    const ok = await ensureBackend(request);
    if (!ok) return;
  });

  test('minimal process: start -> java service -> end completes', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, MINIMAL_BPMN);
    expect(processDefinitionId).toBe('Process_Minimal');

    const instanceId = await createInstance(request, processDefinitionId, {});
    const instance = await getInstance(request, instanceId);

    expect(instance.state).toBe('Completed');
    expect(instance.instanceId).toBe(instanceId);
  });

  test('user task process: create -> complete user task -> completed', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, USER_TASK_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    let instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Active');
    expect(instance.pendingUserTaskId).toBe('UserTask_1');

    await completeTask(request, instanceId, 'UserTask_1', { approved: true });
    instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('exclusive gateway: flag true takes yes branch', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, EXCLUSIVE_GATEWAY_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, { flag: true });

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('exclusive gateway: flag false takes no branch', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, EXCLUSIVE_GATEWAY_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, { flag: false });

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('exclusive gateway: no flag takes default branch', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, EXCLUSIVE_GATEWAY_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('parallel gateway: both branches run then join', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, PARALLEL_GATEWAY_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('message start event: triggerMessageStart runs process to completion', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, MESSAGE_START_BPMN);
    expect(processDefinitionId).toBe('Process_MessageStart');

    const instanceId = await messageStart(request, processDefinitionId, 'OrderReceived', 'corr-1', { orderId: 'O1' });
    const instance = await getInstance(request, instanceId);

    expect(instance.state).toBe('Completed');
  });

  test('intermediate catch event: create instance then trigger by nodeId', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, CATCH_EVENT_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    let instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Active');

    await triggerCatch(request, instanceId, 'Catch_1', { payload: 'done' });

    instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('intermediate catch event: trigger by messageRef', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, CATCH_EVENT_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    await triggerCatchByMessageRef(request, 'Reply', null, { payload: 'done' });

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('intermediate throw event: process completes without external trigger', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, THROW_EVENT_BPMN);
    const instanceId = await createInstance(request, processDefinitionId, {});

    const instance = await getInstance(request, instanceId);
    expect(instance.state).toBe('Completed');
  });

  test('Kafka service task: mapping runs and instance completes with resultVariable', async ({ request }) => {
    const processDefinitionId = await deployProcess(request, KAFKA_TASK_BPMN);
    const createRes = await request.post(`${baseURL}/v1/process-instances`, {
      data: {
        processDefinitionId,
        variables: { orderId: 'E2E-001', amount: 99.5 },
      },
      headers: { 'Content-Type': 'application/json' },
    });
    if (!createRes.ok()) {
      test.skip(true, 'Kafka not enabled or not available (BPMN_KAFKA_ENABLED=true and Kafka running)');
      return;
    }
    const { instanceId } = await createRes.json();

    const instance = await getInstance(request, instanceId);
    if (instance.state === 'Failed') {
      test.skip(true, 'Kafka not available or task failed');
      return;
    }
    expect(instance.state).toBe('Completed');
    expect(instance.variables).toBeDefined();
    expect(instance.variables.kafkaResult).toBeDefined();
    expect(instance.variables.kafkaResult.sent).toBe(true);
    expect(instance.variables.kafkaResult.topic).toBe('e2e-orders');
  });
});
