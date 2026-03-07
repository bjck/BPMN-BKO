import { API, fetchJson, fetchText } from '../core/api.js';
import { BpmnViewerApp } from '../bpmn/viewer-app.js';
import { escapeHtml, formatDate } from '../core/ui.js';

export function createInstancesPage({ showToast }) {
  let root = null;
  let viewerApp = null;
  let selectedInstanceId = null;
  let destroyed = false;
  let selectionToken = 0;
  let selectedInstance = null;
  let selectedHistory = { events: [], taskExecutions: [] };

  const PAGE_SIZE = 20;
  let currentPage = 1;
  let totalCount = 0;
  let hasMore = false;
  let allInstances = [];

  function setSelectedRow(instanceId) {
    root?.querySelectorAll('#instances-list .list-item').forEach((row) => {
      row.classList.toggle('selected', row.dataset.id === instanceId);
    });
  }

  function setViewerMessage(message) {
    viewerApp?.setIdleState(message);
  }

  function renderPagination() {
    const container = root?.querySelector('#instances-pagination');
    if (!container) {
      return;
    }

    if (allInstances.length === 0 && currentPage === 1) {
      container.innerHTML = '';
      container.classList.add('hidden');
      return;
    }

    const totalPages = Math.max(1, Math.ceil(totalCount / PAGE_SIZE));
    const start = totalCount === 0 ? 0 : (currentPage - 1) * PAGE_SIZE + 1;
    const end = Math.min(currentPage * PAGE_SIZE, totalCount);

    container.classList.remove('hidden');
    container.innerHTML = `
      <span class="pagination-summary">${start}–${end} of ${totalCount}</span>
      <div class="pagination-controls">
        <button type="button" class="btn btn-secondary pagination-prev" ${currentPage <= 1 ? 'disabled' : ''} aria-label="Previous page">Prev</button>
        <span class="pagination-page" aria-live="polite">Page ${currentPage} of ${totalPages}</span>
        <button type="button" class="btn btn-secondary pagination-next" ${currentPage >= totalPages && !hasMore ? 'disabled' : ''} aria-label="Next page">Next</button>
      </div>
    `;

    container.querySelector('.pagination-prev')?.addEventListener('click', () => {
      if (currentPage > 1) {
        currentPage -= 1;
        loadInstancesList();
      }
    });
    container.querySelector('.pagination-next')?.addEventListener('click', () => {
      if (hasMore || currentPage < totalPages) {
        currentPage += 1;
        loadInstancesList();
      }
    });
  }

  function renderCurrentPage() {
    const list = root?.querySelector('#instances-list');
    if (!list) {
      return;
    }

    const pageInstances = allInstances;

    list.innerHTML = pageInstances.length
      ? pageInstances.map((instance) => {
          const varsPreview = instance.variables && Object.keys(instance.variables).length > 0
            ? Object.entries(instance.variables).map(([key, value]) => `${key}=${JSON.stringify(value)}`).join(', ')
            : '';

          return `
            <div class="list-item" data-id="${escapeHtml(instance.instanceId)}" role="button" tabindex="0">
              <div>
                <span class="id">${escapeHtml(instance.instanceId)}</span>
                <div>${escapeHtml(instance.processDefinitionId)}${instance.currentNodeId ? ` @ ${escapeHtml(instance.currentNodeId)}` : ''}</div>
                ${varsPreview ? `<div class="vars-preview">${escapeHtml(varsPreview)}</div>` : ''}
              </div>
              <span class="state ${escapeHtml(instance.state)}">${escapeHtml(instance.state)}</span>
            </div>
          `;
        }).join('')
      : '<span class="text-muted">No instances</span>';

    list.querySelectorAll('.list-item').forEach((row) => {
      row.addEventListener('click', () => selectInstance(row.dataset.id));
      row.addEventListener('keydown', (event) => {
        if (event.key === 'Enter' || event.key === ' ') {
          event.preventDefault();
          selectInstance(row.dataset.id);
        }
      });
    });

    if (selectedInstanceId && list.querySelector(`.list-item[data-id="${CSS.escape(selectedInstanceId)}"]`)) {
      setSelectedRow(selectedInstanceId);
    }

    renderPagination();
  }

  async function loadInstancesList() {
    const list = root?.querySelector('#instances-list');
    if (!list) {
      return;
    }

    list.innerHTML = '<span class="text-muted">Loading...</span>';

    try {
      const url = `${API}/process-instances?page=${currentPage}&size=${PAGE_SIZE}`;
      const data = await fetchJson(url);
      if (destroyed) {
        return;
      }

      allInstances = data.instances || [];
      totalCount = data.totalCount != null ? Number(data.totalCount) : allInstances.length;
      hasMore = data.hasMore === true;
      renderCurrentPage();
    } catch (error) {
      if (!destroyed) {
        list.innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`;
      }
    }
  }

  function renderInstanceDetail(instance, history) {
    const placeholder = root.querySelector('#instance-placeholder');
    const info = root.querySelector('#instance-info');
    const variablesSection = root.querySelector('#instance-variables-section');
    const variables = root.querySelector('#instance-variables');
    const userTask = root.querySelector('#instance-user-task');
    const historySection = root.querySelector('#instance-history');
    const cancelButton = root.querySelector('#cancel-instance-btn');
    const restartButton = root.querySelector('#restart-instance-btn');

    placeholder.classList.add('hidden');
    info.classList.remove('hidden');
    variablesSection.classList.remove('hidden');
    userTask.classList.add('hidden');
    historySection.classList.remove('hidden');
    cancelButton.classList.toggle('hidden', instance.state !== 'Active');
    const isFailed = String(instance.state || '').toLowerCase() === 'failed';
    restartButton?.classList.toggle('hidden', !isFailed);

    info.innerHTML = `
      <p><span class="label">ID:</span> ${escapeHtml(instance.instanceId)}</p>
      <p><span class="label">Process:</span> ${escapeHtml(instance.processDefinitionId)}</p>
      <p><span class="label">State:</span> <span class="state ${escapeHtml(instance.state)}">${escapeHtml(instance.state)}</span></p>
      <p><span class="label">Created:</span> ${escapeHtml(formatDate(instance.createdAt))}</p>
      ${instance.completedAt ? `<p><span class="label">Completed:</span> ${escapeHtml(formatDate(instance.completedAt))}</p>` : ''}
    `;

    variables.innerHTML = Object.keys(instance.variables || {}).length
      ? `<pre>${escapeHtml(JSON.stringify(instance.variables, null, 2))}</pre>`
      : '<p class="text-muted">No variables</p>';

    if (instance.state === 'Active' && instance.pendingUserTaskId) {
      userTask.classList.remove('hidden');
      userTask.innerHTML = `
        <h4>User Task: ${escapeHtml(instance.pendingUserTaskId)}</h4>
        <div class="form-row">
          <label>Variables to add (JSON)</label>
          <input type="text" id="complete-task-vars" placeholder='{"key": "value"}' />
        </div>
        <button id="complete-task-btn" class="btn">Complete Task</button>
      `;

      userTask.querySelector('#complete-task-btn')?.addEventListener('click', () => {
        completeTask(instance.instanceId, instance.pendingUserTaskId);
      });
    } else {
      userTask.innerHTML = '';
    }

    const events = history.events || [];
    const taskExecutions = history.taskExecutions || [];
    historySection.innerHTML = '<h4>History</h4>';

    if (!events.length && !taskExecutions.length) {
      historySection.innerHTML += '<span class="text-muted">No history available</span>';
      return;
    }

    events.forEach((event) => {
      historySection.innerHTML += `
        <div class="history-event">
          <strong>${escapeHtml(event.eventType)}</strong> ${event.currentNodeId ? escapeHtml(event.currentNodeId) : ''}
          <div class="text-muted">${escapeHtml(formatDate(event.createdAt))}</div>
        </div>
      `;
    });

    taskExecutions.forEach((task) => {
      historySection.innerHTML += `
        <div class="history-task">
          <strong>${escapeHtml(task.taskId)}</strong> (${escapeHtml(task.taskType)}) - ${escapeHtml(task.durationMs)}ms
          <div class="text-muted">${escapeHtml(formatDate(task.startedAt))}</div>
        </div>
      `;
    });
  }

  async function selectInstance(instanceId) {
    if (!instanceId) {
      return;
    }

    selectedInstanceId = instanceId;
    selectionToken += 1;
    const currentSelection = selectionToken;
    setSelectedRow(instanceId);
    setViewerMessage('Loading diagram...');

    try {
      const instance = await fetchJson(`${API}/process-instances/${instanceId}`);
      if (destroyed || currentSelection !== selectionToken) {
        return;
      }
      selectedInstance = instance;

      let history = { events: [], taskExecutions: [] };
      try {
        history = await fetchJson(`${API}/process-instances/${instanceId}/history`);
      } catch {
        history = { events: [], taskExecutions: [] };
      }

      if (destroyed || currentSelection !== selectionToken) {
        return;
      }

      selectedHistory = history;

      renderInstanceDetail(instance, history);

      try {
        const xml = await fetchText(`${API}/processes/${encodeURIComponent(instance.processDefinitionId)}/bpmn`);
        if (destroyed || currentSelection !== selectionToken) {
          return;
        }

        await viewerApp.render({
          xml,
          history,
          currentNodeId: instance.currentNodeId,
          currentVariables: instance.variables || {},
        });
      } catch (error) {
        setViewerMessage(`Diagram unavailable: ${error.message}`);
      }
    } catch (error) {
      if (!destroyed) {
        root.querySelector('#instance-info').innerHTML = `<p class="error">${escapeHtml(error.message)}</p>`;
      }
      setViewerMessage('Failed to load diagram.');
    }
  }

  async function completeTask(instanceId, taskId) {
    const variablesField = root?.querySelector('#complete-task-vars');
    let variables = {};

    if (variablesField?.value?.trim()) {
      try {
        variables = JSON.parse(variablesField.value);
      } catch {
        showToast('Invalid JSON', 'error');
        return;
      }
    }

    try {
      await fetchJson(`${API}/process-instances/${instanceId}/complete-task/${taskId}`, {
        method: 'POST',
        body: JSON.stringify({ variables }),
      });

      showToast('Task completed', 'success');
      await loadInstancesList();
      await selectInstance(instanceId);
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function restartSelectedInstance() {
    if (!selectedInstanceId) {
      return;
    }

    if (!window.confirm('Restart this instance from the step it failed on?')) {
      return;
    }

    try {
      await fetchJson(`${API}/process-instances/${selectedInstanceId}/restart`, { method: 'POST' });
      showToast('Instance restarted', 'success');
      await loadInstancesList();
      await selectInstance(selectedInstanceId);
    } catch (error) {
      showToast(error.message || 'Restart failed', 'error');
    }
  }

  async function cancelSelectedInstance() {
    if (!selectedInstanceId) {
      return;
    }

    if (!window.confirm('Cancel this instance?')) {
      return;
    }

    try {
      const response = await fetch(`${API}/process-instances/${selectedInstanceId}`, { method: 'DELETE' });
      if (!response.ok) {
        throw new Error(await response.text() || `HTTP ${response.status}`);
      }

      showToast('Instance cancelled', 'success');
      selectedInstanceId = null;
      selectedInstance = null;
      selectedHistory = { events: [], taskExecutions: [] };
      root.querySelector('#instance-placeholder').classList.remove('hidden');
      root.querySelector('#instance-info').classList.add('hidden');
      root.querySelector('#instance-variables-section').classList.add('hidden');
      root.querySelector('#instance-user-task').classList.add('hidden');
      root.querySelector('#instance-history').innerHTML = '';
      root.querySelector('#cancel-instance-btn')?.classList.add('hidden');
      root.querySelector('#restart-instance-btn')?.classList.add('hidden');
      setViewerMessage('Select an instance to view its BPMN diagram.');
      await loadInstancesList();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  return {
    async refresh() {
      if (destroyed) {
        return;
      }

      await loadInstancesList();
      if (selectedInstanceId) {
        await selectInstance(selectedInstanceId);
      }
    },

    render(container) {
      destroyed = false;
      root = document.createElement('section');
      root.className = 'view instances-view active';
      root.setAttribute('aria-labelledby', 'instances-title');
      root.innerHTML = `
        <div class="instances-page-layout">
          <aside class="instances-sidebar" aria-label="Process instances">
            <div class="instances-sidebar-header">
              <h2 id="instances-title">Process Instances</h2>
              <p class="text-muted">Select an instance to inspect its read-only BPMN viewer and runtime data.</p>
            </div>
            <div id="instances-list" class="list instances-list"></div>
            <div id="instances-pagination" class="instances-pagination hidden" aria-label="Instances pagination"></div>
          </aside>
          <div class="instances-main">
            <div class="instances-diagram-card card">
              <div class="diagram-card-header">
                <h3>Process Diagram</h3>
                <p class="text-muted">This tab is a viewer only. Modeling and deployment live in the editor tab.</p>
              </div>
              <div id="bpmn-viewer-container" class="bpmn-viewer-container"><span class="text-muted">Select an instance to view its BPMN diagram.</span></div>
              <div id="bpmn-element-detail" class="bpmn-element-detail hidden">
                <h4>Element: <span id="bpmn-element-id"></span></h4>
                <div id="bpmn-element-variables" class="bpmn-element-variables"></div>
              </div>
            </div>

            <div class="instances-detail-card card">
              <h3>Instance Details</h3>
              <div id="instance-placeholder" class="text-muted">Select an instance from the list.</div>
              <div id="instance-info" class="hidden"></div>
              <div id="instance-variables-section" class="variables-section hidden">
                <h4>Variables</h4>
                <div id="instance-variables" class="variables"></div>
              </div>
              <div id="instance-user-task" class="user-task-section hidden"></div>
              <div id="instance-history" class="history-section hidden"></div>
              <div class="instance-actions">
                <button id="refresh-instance-btn" class="btn btn-secondary">Refresh</button>
                <button id="restart-instance-btn" class="btn btn-primary hidden">Restart from failed step</button>
                <button id="cancel-instance-btn" class="btn btn-danger hidden">Cancel Instance</button>
              </div>
            </div>
          </div>
        </div>
      `;

      container.appendChild(root);

      viewerApp = new BpmnViewerApp({
        containerElement: root.querySelector('#bpmn-viewer-container'),
        detailElement: root.querySelector('#bpmn-element-detail'),
        detailIdElement: root.querySelector('#bpmn-element-id'),
        detailVariablesElement: root.querySelector('#bpmn-element-variables'),
      });

      root.querySelector('#refresh-instance-btn')?.addEventListener('click', async () => {
        if (selectedInstanceId) {
          await selectInstance(selectedInstanceId);
        } else {
          await loadInstancesList();
        }
      });

      root.querySelector('#restart-instance-btn')?.addEventListener('click', restartSelectedInstance);
      root.querySelector('#cancel-instance-btn')?.addEventListener('click', cancelSelectedInstance);

      loadInstancesList();
    },

    destroy() {
      destroyed = true;
      selectionToken += 1;
      selectedInstance = null;
      selectedHistory = { events: [], taskExecutions: [] };
      viewerApp?.destroy();
      viewerApp = null;
      root = null;
    },

    getAiContext() {
      return {
        pageTitle: 'Instances',
        selectedInstance,
        historySummary: {
          events: selectedHistory.events?.length || 0,
          taskExecutions: selectedHistory.taskExecutions?.length || 0,
        },
        viewer: viewerApp?.getContextSnapshot?.() || null,
      };
    },
  };
}
