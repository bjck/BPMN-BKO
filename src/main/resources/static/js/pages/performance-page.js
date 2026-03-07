import { API, fetchJson, fetchText } from '../core/api.js';
import { escapeHtml } from '../core/ui.js';

export function createPerformancePage({ showToast }) {
  let root = null;
  let destroyed = false;
  let deployedProcesses = [];
  let lastResults = null;

  async function loadProcesses() {
    const select = root?.querySelector('#perf-process-select');
    if (!select) {
      return;
    }

    try {
      const { processes } = await fetchJson(`${API}/processes`);
      if (destroyed) {
        return;
      }

      deployedProcesses = processes;

      select.innerHTML = processes.length
        ? processes.map((processId) => `<option value="${escapeHtml(processId)}">${escapeHtml(processId)}</option>`).join('')
        : '<option value="">— Deploy a process first</option>';
    } catch {
      if (!destroyed) {
        select.innerHTML = '<option value="">—</option>';
      }
    }
  }

  return {
    render(container) {
      destroyed = false;
      root = document.createElement('section');
      root.className = 'view performance-view active';
      root.setAttribute('aria-labelledby', 'perf-title');
      root.innerHTML = `
        <div class="card">
          <div class="page-header">
            <div>
              <h2 id="perf-title">Performance Test</h2>
              <p class="card-subtitle">Run the counting process and measure completed instances per second.</p>
            </div>
          </div>
          <div class="form-row">
            <label>Process</label>
            <select id="perf-process-select"></select>
          </div>
          <div class="form-row">
            <label>Number of instances</label>
            <input type="number" id="perf-count" value="100" min="1" max="100000" />
          </div>
          <div class="perf-actions">
            <button id="load-counting-btn" class="btn btn-secondary">Load & deploy counting BPMN</button>
            <button id="run-perf-btn" class="btn">Run Test</button>
          </div>
          <div id="perf-results" class="perf-results hidden">
            <h3>Results</h3>
            <div id="perf-results-content"></div>
          </div>
        </div>
      `;

      container.appendChild(root);

      root.querySelector('#load-counting-btn')?.addEventListener('click', async () => {
        try {
          const xml = await fetchText('/samples/counting.bpmn');
          const { processDefinitionId } = await fetchJson(`${API}/processes`, {
            method: 'POST',
            body: JSON.stringify({ bpmnXml: xml }),
          });

          showToast(`Deployed: ${processDefinitionId}`, 'success');
          await loadProcesses();
          root.querySelector('#perf-process-select').value = processDefinitionId;
        } catch (error) {
          showToast(error.message, 'error');
        }
      });

      root.querySelector('#run-perf-btn')?.addEventListener('click', async () => {
        const processDefinitionId = root.querySelector('#perf-process-select').value;
        const count = parseInt(root.querySelector('#perf-count').value, 10) || 100;
        const results = root.querySelector('#perf-results');
        const content = root.querySelector('#perf-results-content');
        const button = root.querySelector('#run-perf-btn');

        if (!processDefinitionId) {
          showToast('Select a process or load counting BPMN first', 'error');
          return;
        }

        button.disabled = true;
        results.classList.remove('hidden');
        content.textContent = 'Running...';

        try {
          const result = await fetchJson(`${API}/performance-test`, {
            method: 'POST',
            body: JSON.stringify({ processDefinitionId, count }),
          });
          lastResults = result;

          content.innerHTML = `
            <p><strong>Requested:</strong> ${escapeHtml(result.requested)}</p>
            <p><strong>Completed:</strong> ${escapeHtml(result.completed)}</p>
            <p><strong>Duration:</strong> ${escapeHtml(result.durationMs)} ms</p>
            <p><strong>Throughput:</strong> <span class="perf-throughput">${Number(result.instancesPerSecond).toFixed(2)}</span> instances/sec</p>
          `;
          showToast('Test complete', 'success');
        } catch (error) {
          content.innerHTML = `<p class="error">${escapeHtml(error.message)}</p>`;
          showToast(error.message, 'error');
        } finally {
          button.disabled = false;
        }
      });

      loadProcesses();
    },

    destroy() {
      destroyed = true;
      root = null;
    },

    getAiContext() {
      return {
        pageTitle: 'Performance',
        deployedProcesses,
        selectedProcess: root?.querySelector('#perf-process-select')?.value || '',
        requestedCount: root?.querySelector('#perf-count')?.value || '',
        lastResults,
      };
    },
  };
}
