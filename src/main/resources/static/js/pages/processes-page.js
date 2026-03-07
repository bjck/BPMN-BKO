import { API, fetchJson, fetchText } from '../core/api.js';
import { escapeHtml } from '../core/ui.js';

export function createProcessesPage({ showToast, navigate }) {
  let root = null;
  let destroyed = false;
  let deployedProcesses = [];

  async function loadProcessesList() {
    const list = root?.querySelector('#processes-list');
    const select = root?.querySelector('#create-process-select');

    if (!list || !select) {
      return;
    }

    list.innerHTML = '<span class="text-muted">Loading...</span>';

    try {
      const { processes } = await fetchJson(`${API}/processes`);
      if (destroyed) {
        return;
      }

      deployedProcesses = processes;

      list.innerHTML = processes.length
        ? processes.map((processId) => `<div class="list-item"><span>${escapeHtml(processId)}</span></div>`).join('')
        : '<span class="text-muted">No processes deployed</span>';

      select.innerHTML = processes.length
        ? processes.map((processId) => `<option value="${escapeHtml(processId)}">${escapeHtml(processId)}</option>`).join('')
        : '<option value="">—</option>';
    } catch (error) {
      if (!destroyed) {
        list.innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`;
      }
    }
  }

  async function deployXml(xmlField) {
    const xml = xmlField.value.trim();
    if (!xml) {
      showToast('Paste BPMN XML first', 'error');
      return;
    }

    try {
      const { processDefinitionId } = await fetchJson(`${API}/processes`, {
        method: 'POST',
        body: JSON.stringify({ bpmnXml: xml }),
      });

      showToast(`Deployed: ${processDefinitionId}`, 'success');
      xmlField.value = '';
      loadProcessesList();
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  async function createInstance() {
    const processSelect = root?.querySelector('#create-process-select');
    const variablesField = root?.querySelector('#create-vars');
    if (!processSelect || !variablesField) {
      return;
    }

    const processDefinitionId = processSelect.value;
    if (!processDefinitionId) {
      showToast('Select a process', 'error');
      return;
    }

    let variables = {};
    const variablesText = variablesField.value.trim();
    if (variablesText) {
      try {
        variables = JSON.parse(variablesText);
      } catch {
        showToast('Invalid JSON in variables', 'error');
        return;
      }
    }

    try {
      const result = await fetchJson(`${API}/process-instances`, {
        method: 'POST',
        body: JSON.stringify({ processDefinitionId, variables }),
      });

      showToast(`Created instance ${result.instanceId}`, 'success');
      navigate('instances');
    } catch (error) {
      showToast(error.message, 'error');
    }
  }

  return {
    async refresh() {
      if (!destroyed) {
        await loadProcessesList();
      }
    },

    render(container) {
      destroyed = false;
      root = document.createElement('section');
      root.className = 'view processes-view active';
      root.setAttribute('aria-labelledby', 'processes-title');
      root.innerHTML = `
        <div class="card">
          <div class="page-header">
            <div>
              <h2 id="processes-title">Deployed Processes</h2>
              <p class="card-subtitle">Deploy BPMN XML directly or create instances from the currently deployed definitions.</p>
            </div>
          </div>
          <div id="processes-list" class="list"></div>
          <details class="deploy-section">
            <summary>Deploy Process (BPMN XML)</summary>
            <div class="sample-buttons">
              <button id="load-sample-btn" class="btn btn-secondary">Load sample (UserTask)</button>
              <button id="load-counting-deploy-btn" class="btn btn-secondary">Load counting BPMN</button>
            </div>
            <textarea id="bpmn-xml" placeholder="Paste BPMN 2.0 XML here..." rows="8"></textarea>
            <button id="deploy-btn" class="btn">Deploy</button>
          </details>
        </div>
        <div class="card">
          <h2>Create Instance</h2>
          <div class="form-row">
            <label>Process</label>
            <select id="create-process-select"></select>
          </div>
          <div class="form-row">
            <label>Initial variables (JSON)</label>
            <input type="text" id="create-vars" placeholder='{"key": "value"}' />
          </div>
          <button id="create-btn" class="btn">Create Instance</button>
        </div>
      `;

      container.appendChild(root);

      const xmlField = root.querySelector('#bpmn-xml');

      root.querySelector('#load-sample-btn')?.addEventListener('click', async () => {
        try {
          xmlField.value = await fetchText('/samples/with-user-task.bpmn');
          showToast('Sample loaded', 'success');
        } catch (error) {
          showToast(error.message, 'error');
        }
      });

      root.querySelector('#load-counting-deploy-btn')?.addEventListener('click', async () => {
        try {
          xmlField.value = await fetchText('/samples/counting.bpmn');
          showToast('Counting BPMN loaded', 'success');
        } catch (error) {
          showToast(error.message, 'error');
        }
      });

      root.querySelector('#deploy-btn')?.addEventListener('click', () => deployXml(xmlField));
      root.querySelector('#create-btn')?.addEventListener('click', createInstance);

      loadProcessesList();
    },

    destroy() {
      destroyed = true;
      root = null;
    },

    getAiContext() {
      return {
        pageTitle: 'Deploy',
        deployedProcesses,
        selectedProcess: root?.querySelector('#create-process-select')?.value || '',
        draftVariables: root?.querySelector('#create-vars')?.value || '',
        draftBpmnXmlLength: root?.querySelector('#bpmn-xml')?.value?.length || 0,
      };
    },
  };
}
