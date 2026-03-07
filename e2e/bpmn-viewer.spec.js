// @ts-check
/**
 * E2E test: BPMN viewer displays the process diagram when viewing an instance.
 * Requires the app to be running (e.g. mvn spring-boot:run).
 * Run: npm run test:e2e (with BASE_URL=http://localhost:8080 by default)
 */
const { test, expect } = require('@playwright/test');

test.describe('BPMN viewer (live backend)', () => {
  test('displays diagram when an instance is selected', async ({ page, request }) => {
    const baseURL = process.env.BASE_URL || 'http://localhost:8080';

    let apiCheck;
    try {
      apiCheck = await request.get(`${baseURL}/v1/processes`);
    } catch (e) {
      test.skip(true, 'Server not available - start with: mvn spring-boot:run');
      return;
    }
    if (!apiCheck.ok()) {
      test.skip(true, 'Backend API not available (only static server?). Start Spring Boot for this test.');
      return;
    }

    let bpmnRes;
    try {
      bpmnRes = await request.get(`${baseURL}/samples/counting.bpmn`);
    } catch (e) {
      test.skip(true, 'Server not available - start with: mvn spring-boot:run');
      return;
    }
    if (!bpmnRes.ok()) {
      test.skip(true, 'counting.bpmn not available');
      return;
    }
    const bpmnXml = await bpmnRes.text();
    const deployRes = await request.post(`${baseURL}/v1/processes`, {
      data: { bpmnXml },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(deployRes.ok()).toBeTruthy();
    const { processDefinitionId } = await deployRes.json();
    expect(processDefinitionId).toBe('Process_Counting');

    const instRes = await request.post(`${baseURL}/v1/process-instances`, {
      data: { processDefinitionId, variables: {} },
      headers: { 'Content-Type': 'application/json' },
    });
    expect(instRes.ok()).toBeTruthy();
    const { instanceId } = await instRes.json();
    expect(instanceId).toBeTruthy();

    await page.goto('/#/instances');

    const instanceRow = page.locator(`#instances-list .list-item[data-id="${instanceId}"]`);
    await expect(instanceRow).toBeVisible({ timeout: 10000 });
    await instanceRow.click();

    await expect(page.locator('#instance-info').filter({ hasText: instanceId })).toBeVisible({ timeout: 5000 });

    const container = page.locator('#bpmn-viewer-container');
    await expect(container.locator('.bjs-container')).toBeVisible({ timeout: 15000 });
    const diagramContent = container.locator('.bjs-container canvas, .bjs-container svg');
    await expect(diagramContent.first()).toBeVisible({ timeout: 5000 });
    await expect(container.locator('.error')).not.toBeVisible();
  });
});
