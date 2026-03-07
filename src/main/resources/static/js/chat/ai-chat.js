import { API, fetchJson } from '../core/api.js';
import { escapeHtml } from '../core/ui.js';

function buildStarterSuggestions(route) {
  switch (route) {
    case 'editor':
      return [
        'Explain the selected BPMN element',
        'What can I configure next on this task?',
        'Review this service task setup',
      ];
    case 'instances':
      return [
        'Explain what this instance is doing',
        'Summarize the current variables',
        'What can I do on this page?',
      ];
    case 'performance':
      return [
        'Explain how to run a useful performance test',
        'What do these results mean?',
        'How can I benchmark a process safely?',
      ];
    case 'processes':
    default:
      return [
        'What can I do on this page?',
        'How do I deploy a BPMN file?',
        'How do I create an instance with variables?',
      ];
  }
}

function buildWelcomeMessage(route, context) {
  const selectedElement = context?.selectedElement?.name || context?.selectedElement?.id;
  const processDefinitionId = context?.selectedInstance?.processDefinitionId || context?.selectedProcess;

  switch (route) {
    case 'editor':
      return selectedElement
        ? `I can explain \`${selectedElement}\`, suggest what to model next, or review the current task configuration.`
        : 'I can explain BPMN elements, suggest what to do next in the editor, or help configure tasks and flows.';
    case 'instances':
      return processDefinitionId
        ? `I can help explain this \`${processDefinitionId}\` instance, summarize variables, or walk through the selected BPMN element.`
        : 'I can explain the current instance state, summarize variables/history, and suggest what can be done on this page.';
    case 'performance':
      return 'I can explain the performance page, help interpret throughput results, and suggest sensible benchmark settings.';
    case 'processes':
    default:
      return 'I can suggest what can be done on this page, explain deployment steps, and help with BPMN XML or instance variables.';
  }
}

function createMessage(role, content) {
  return {
    role,
    content,
    createdAt: Date.now(),
  };
}

export function createAiChatWidget({ rootElement, showToast, getAppContext, applyAiResult }) {
  if (!rootElement) {
    return {
      refreshContext() {},
    };
  }

  let isOpen = false;
  let isBusy = false;
  let conversationId = null;
  let messages = [];

  rootElement.innerHTML = `
    <button id="ai-chat-toggle" class="ai-chat-toggle" type="button" aria-expanded="false" aria-controls="ai-chat-panel" aria-label="Open AI chat">
      <span class="ai-chat-toggle-icon">AI</span>
    </button>
    <section id="ai-chat-panel" class="ai-chat-panel hidden" aria-label="AI chat assistant">
      <div class="ai-chat-header">
        <div>
          <h3>AI Chat</h3>
          <p id="ai-chat-route-label" class="text-muted">Ready</p>
        </div>
        <button id="ai-chat-close" class="ai-chat-close" type="button" aria-label="Close AI chat">×</button>
      </div>
      <div id="ai-chat-messages" class="ai-chat-messages"></div>
      <div id="ai-chat-suggestions" class="ai-chat-suggestions"></div>
      <form id="ai-chat-form" class="ai-chat-form">
        <textarea id="ai-chat-input" rows="3" placeholder="Ask about this page or a BPMN element..."></textarea>
        <div class="ai-chat-actions">
          <span id="ai-chat-status" class="text-muted"></span>
          <button id="ai-chat-send" class="btn" type="submit">Send</button>
        </div>
      </form>
    </section>
  `;

  const toggleButton = rootElement.querySelector('#ai-chat-toggle');
  const panel = rootElement.querySelector('#ai-chat-panel');
  const closeButton = rootElement.querySelector('#ai-chat-close');
  const routeLabel = rootElement.querySelector('#ai-chat-route-label');
  const messagesElement = rootElement.querySelector('#ai-chat-messages');
  const suggestionsElement = rootElement.querySelector('#ai-chat-suggestions');
  const form = rootElement.querySelector('#ai-chat-form');
  const input = rootElement.querySelector('#ai-chat-input');
  const status = rootElement.querySelector('#ai-chat-status');
  const sendButton = rootElement.querySelector('#ai-chat-send');

  function renderMessages() {
    messagesElement.innerHTML = messages.length
      ? messages.map((message) => `
          <div class="ai-chat-message ${escapeHtml(message.role)}">
            <div class="ai-chat-message-role">${escapeHtml(message.role === 'assistant' ? 'Assistant' : 'You')}</div>
            <div class="ai-chat-message-body">${escapeHtml(message.content)}</div>
          </div>
        `).join('')
      : '<p class="text-muted">Open the chat to get route-aware BPMN help.</p>';

    messagesElement.scrollTop = messagesElement.scrollHeight;
  }

  async function readContext() {
    const appContext = await Promise.resolve(getAppContext?.());
    return appContext || { route: 'processes', context: {} };
  }

  async function renderStarterState() {
    const { route, context } = await readContext();
    routeLabel.textContent = `Current page: ${route}`;

    if (!messages.length) {
      messages = [createMessage('assistant', buildWelcomeMessage(route, context))];
      renderMessages();
    }

    const suggestions = buildStarterSuggestions(route);
    suggestionsElement.innerHTML = suggestions.map((suggestion) => `
      <button type="button" class="ai-chat-suggestion">${escapeHtml(suggestion)}</button>
    `).join('');

    suggestionsElement.querySelectorAll('.ai-chat-suggestion').forEach((button) => {
      button.addEventListener('click', () => {
        input.value = button.textContent || '';
        form.requestSubmit();
      });
    });
  }

  function setBusy(busy, text = '') {
    isBusy = busy;
    status.textContent = text;
    sendButton.disabled = busy;
    input.disabled = busy;
  }

  async function sendMessage(content) {
    const trimmed = content.trim();
    if (!trimmed || isBusy) {
      return;
    }

    const { route, context } = await readContext();
    messages.push(createMessage('user', trimmed));
    renderMessages();
    input.value = '';
    setBusy(true, 'Thinking...');

    try {
      const response = await fetchJson(`${API}/ai/chat`, {
        method: 'POST',
        body: JSON.stringify({
          conversationId,
          route,
          context,
          messages: messages.map((message) => ({ role: message.role, content: message.content })),
        }),
      });

      conversationId = response.conversationId || conversationId;
      const reply = response.reply?.content || 'No response received.';
      messages.push(createMessage('assistant', reply));
      renderMessages();

      if (response.diagramUpdate) {
        setBusy(true, 'Applying diagram...');
        try {
          const applyResult = await Promise.resolve(applyAiResult?.(response));
          if (applyResult?.message) {
            messages.push(createMessage('assistant', applyResult.message));
            renderMessages();
          }
        } catch (error) {
          showToast(error.message, 'error');
        }
      }
    } catch (error) {
      showToast(error.message, 'error');
    } finally {
      setBusy(false, '');
    }
  }

  async function openPanel() {
    isOpen = true;
    toggleButton.setAttribute('aria-expanded', 'true');
    toggleButton.classList.add('hidden');
    panel.classList.remove('hidden');
    await renderStarterState();
    input.focus();
  }

  function closePanel() {
    isOpen = false;
    toggleButton.setAttribute('aria-expanded', 'false');
    toggleButton.classList.remove('hidden');
    panel.classList.add('hidden');
  }

  toggleButton.addEventListener('click', async () => {
    if (isOpen) {
      closePanel();
      return;
    }

    await openPanel();
  });

  closeButton.addEventListener('click', closePanel);
  form.addEventListener('submit', async (event) => {
    event.preventDefault();
    await sendMessage(input.value);
  });

  renderMessages();

  return {
    async refreshContext() {
      const { route } = await readContext();
      routeLabel.textContent = `Current page: ${route}`;

      if (isOpen) {
        await renderStarterState();
      }
    },
  };
}
