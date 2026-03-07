import { BpmnEditorApp } from '../bpmn/editor-app.js';

export function createEditorPage({ showToast, onProcessDeployed }) {
  let editorApp = null;

  return {
    render(container) {
      const section = document.createElement('section');
      section.className = 'view editor-view active';
      section.setAttribute('aria-labelledby', 'editor-title');
      section.innerHTML = `
        <div class="editor-shell">
          <div class="editor-header card">
            <div class="page-header">
              <div>
                <h2 id="editor-title">BPMN Editor</h2>
                <p class="card-subtitle">Design BPMN diagrams in a dedicated modeler. The instance tab stays read-only and never shares editor state.</p>
              </div>
              <div class="editor-actions">
                <button id="editor-new-btn" class="btn btn-secondary">New diagram</button>
                <button id="editor-copy-xml-btn" class="btn btn-secondary">Copy XML</button>
                <button id="editor-deploy-btn" class="btn">Deploy process</button>
              </div>
            </div>
          </div>
          <div class="editor-workspace">
            <div class="editor-canvas-card card">
              <div class="diagram-card-header">
                <h3>Modeler Canvas</h3>
                <p class="text-muted">Use the palette on the left and the context pad on selected elements to create and connect BPMN steps.</p>
              </div>
              <div id="bpmn-editor-canvas" class="bpmn-editor-canvas"><span class="text-muted">Loading editor...</span></div>
            </div>
            <aside class="editor-properties-panel card" aria-label="Element properties">
              <h3>Properties</h3>
              <p id="editor-properties-placeholder" class="editor-properties-placeholder text-muted">Select a BPMN element to edit its ID, name, and task-specific settings.</p>
              <div id="editor-properties-form" class="editor-properties-form hidden"></div>
            </aside>
          </div>
        </div>
      `;

      container.appendChild(section);

      editorApp = new BpmnEditorApp({
        canvasElement: section.querySelector('#bpmn-editor-canvas'),
        propertyPlaceholder: section.querySelector('#editor-properties-placeholder'),
        propertyForm: section.querySelector('#editor-properties-form'),
        showToast,
      });

      editorApp.init();

      section.querySelector('#editor-new-btn')?.addEventListener('click', async () => {
        try {
          await editorApp.createNewDiagram();
        } catch (error) {
          showToast(error.message, 'error');
        }
      });

      section.querySelector('#editor-copy-xml-btn')?.addEventListener('click', async () => {
        try {
          await editorApp.copyXml();
        } catch (error) {
          showToast(error.message, 'error');
        }
      });

      section.querySelector('#editor-deploy-btn')?.addEventListener('click', async () => {
        try {
          const processDefinitionId = await editorApp.deployCurrentDiagram();
          showToast(`Deployed: ${processDefinitionId}`, 'success');
          onProcessDeployed?.();
        } catch (error) {
          showToast(error.message, 'error');
        }
      });
    },

    destroy() {
      editorApp?.destroy();
      editorApp = null;
    },

    async getAiContext() {
      return {
        pageTitle: 'BPMN Editor',
        description: 'Design and configure BPMN diagrams.',
        ...await editorApp?.getContextSnapshot?.(),
      };
    },

    async applyAiResult(response) {
      if (!response?.diagramUpdate) {
        return { applied: false };
      }

      const result = await editorApp?.applyAiGeneratedDiagram?.(response.diagramUpdate);
      return {
        applied: Boolean(result?.applied),
        message: result?.message || null,
      };
    },
  };
}
