import { getBpmnViewerConstructor } from '../core/bpmn-bundles.js';
import { formatDate } from '../core/ui.js';

export class BpmnViewerApp {
  constructor({ containerElement, detailElement, detailIdElement, detailVariablesElement }) {
    this.containerElement = containerElement;
    this.detailElement = detailElement;
    this.detailIdElement = detailIdElement;
    this.detailVariablesElement = detailVariablesElement;
    this.viewer = null;
    this.elementVariablesMap = {};
    this.selectedElementId = null;
    this.currentNodeId = null;
    this.currentVariables = {};
    this.historySummary = { events: 0, taskExecutions: 0 };
  }

  destroy() {
    if (this.viewer) {
      this.viewer.destroy();
      this.viewer = null;
    }

    if (this.containerElement) {
      this.containerElement.innerHTML = '';
    }

    this.selectedElementId = null;
    this.currentNodeId = null;
    this.currentVariables = {};
    this.historySummary = { events: 0, taskExecutions: 0 };
    this.hideElementDetail();
  }

  setIdleState(message) {
    this.destroy();
    this.containerElement.innerHTML = `<span class="text-muted">${message}</span>`;
  }

  hideElementDetail() {
    this.selectedElementId = null;
    if (this.detailElement) {
      this.detailElement.classList.add('hidden');
    }

    if (this.detailIdElement) {
      this.detailIdElement.textContent = '';
    }

    if (this.detailVariablesElement) {
      this.detailVariablesElement.innerHTML = '';
    }
  }

  async render({ xml, history = { events: [], taskExecutions: [] }, currentNodeId, currentVariables }) {
    if (!this.containerElement) {
      return;
    }

    this.destroy();

    this.containerElement.innerHTML = '';
    let Viewer;
    try {
      Viewer = await getBpmnViewerConstructor();
    } catch (error) {
      this.containerElement.innerHTML = `<span class="error">${error.message}</span>`;
      return;
    }

    if (!Viewer) {
      this.containerElement.innerHTML = '<span class="error">The BPMN viewer bundle did not load.</span>';
      return;
    }

    this.viewer = new Viewer({ container: this.containerElement });
    this.elementVariablesMap = this.buildElementVariableMap(history, currentNodeId, currentVariables);
    this.selectedElementId = null;
    this.currentNodeId = currentNodeId || null;
    this.currentVariables = currentVariables || {};
    this.historySummary = {
      events: history.events?.length || 0,
      taskExecutions: history.taskExecutions?.length || 0,
    };

    try {
      await this.viewer.importXML(xml);
      this.fitDiagram();

      const canvas = this.viewer.get('canvas');

      const completedTaskIds = new Set((history.taskExecutions || []).map((task) => task.taskId));
      completedTaskIds.forEach((taskId) => {
        try {
          canvas.addMarker(taskId, 'completed');
        } catch (_) {
          // Ignore invalid markers for nodes not present in the diagram.
        }
      });

      if (currentNodeId) {
        try {
          canvas.addMarker(currentNodeId, 'highlight');
        } catch (_) {
          // Ignore invalid markers for nodes not present in the diagram.
        }
      }

      const eventBus = this.viewer.get('eventBus');
      eventBus.on('element.click', ({ element }) => this.showElementDetail(element.id));
    } catch (error) {
      this.containerElement.innerHTML = `<span class="error">Failed to render diagram: ${error.message}</span>`;
    }
  }

  fitDiagram() {
    if (!this.viewer || !this.containerElement) {
      return;
    }

    const canvas = this.viewer.get('canvas');

    const applyFit = () => {
      canvas.zoom('fit-viewport', 'auto');

      try {
        const viewbox = canvas.viewbox();
        if (viewbox && typeof canvas.scroll === 'function') {
          canvas.scroll({
            x: -viewbox.x,
            y: -viewbox.y,
          });
        }
      } catch (_) {
        // Some viewer variants may not expose scroll in the same way.
      }
    };

    requestAnimationFrame(() => {
      requestAnimationFrame(applyFit);
    });

    window.setTimeout(applyFit, 150);
  }

  buildElementVariableMap(history, currentNodeId, currentVariables) {
    const variablesByElement = {};

    (history.events || []).forEach((event) => {
      if (event.currentNodeId && event.variables != null) {
        variablesByElement[event.currentNodeId] = {
          variables: event.variables,
          eventType: event.eventType,
          createdAt: event.createdAt,
        };
      }
    });

    if (currentNodeId && currentVariables && Object.keys(currentVariables).length > 0) {
      variablesByElement[currentNodeId] = {
        variables: currentVariables,
        eventType: 'current',
        createdAt: null,
      };
    }

    return variablesByElement;
  }

  showElementDetail(elementId) {
    if (!this.detailElement || !this.detailIdElement || !this.detailVariablesElement) {
      return;
    }

    this.selectedElementId = elementId;
    this.detailIdElement.textContent = elementId;
    const detail = this.elementVariablesMap[elementId];

    if (detail?.variables && Object.keys(detail.variables).length > 0) {
      const meta = detail.eventType
        ? `<p class="text-muted">${detail.eventType}${detail.createdAt ? ` · ${formatDate(detail.createdAt)}` : ''}</p>`
        : '';

      this.detailVariablesElement.innerHTML = `${meta}<pre>${JSON.stringify(detail.variables, null, 2)}</pre>`;
    } else {
      this.detailVariablesElement.innerHTML = '<p class="text-muted">No variables recorded for this element.</p>';
    }

    this.detailElement.classList.remove('hidden');
  }

  getContextSnapshot() {
    const selectedDetail = this.selectedElementId ? this.elementVariablesMap[this.selectedElementId] : null;
    return {
      currentNodeId: this.currentNodeId,
      currentVariables: this.currentVariables,
      historySummary: this.historySummary,
      selectedElement: this.selectedElementId
        ? {
            id: this.selectedElementId,
            variables: selectedDetail?.variables || {},
            eventType: selectedDetail?.eventType || null,
          }
        : null,
    };
  }
}
