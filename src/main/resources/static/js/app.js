import { showToast } from './core/ui.js';
import { createAiChatWidget } from './chat/ai-chat.js';
import { createEditorPage } from './pages/editor-page.js';
import { createInstancesPage } from './pages/instances-page.js';
import { createPerformancePage } from './pages/performance-page.js';
import { createProcessesPage } from './pages/processes-page.js';

const ROUTES = ['processes', 'editor', 'instances', 'performance'];

function getRoute() {
  const hash = window.location.hash.slice(1) || '/';
  const normalizedPath = hash.startsWith('/') ? hash : `/${hash}`;
  const route = normalizedPath.split('/')[1] || 'processes';
  return ROUTES.includes(route) ? route : 'processes';
}

function setActiveNav(route) {
  document.querySelectorAll('.nav-link').forEach((link) => {
    link.classList.toggle('active', link.dataset.route === route);
  });
}

function navigate(route) {
  window.location.hash = `#/${route}`;
}

function createApp() {
  const main = document.getElementById('main');
  const aiChatRoot = document.getElementById('ai-chat-root');
  let activePage = null;

  const pages = {
    processes: createProcessesPage({ showToast, navigate }),
    editor: createEditorPage({
      showToast,
      onProcessDeployed: () => pages.processes.refresh?.(),
    }),
    instances: createInstancesPage({ showToast }),
    performance: createPerformancePage({ showToast }),
  };

  const aiChatWidget = createAiChatWidget({
    rootElement: aiChatRoot,
    showToast,
    getAppContext: async () => ({
      route: getRoute(),
      context: await activePage?.getAiContext?.() || {},
    }),
    applyAiResult: (result) => activePage?.applyAiResult?.(result),
  });

  function render() {
    const route = getRoute();
    setActiveNav(route);

    activePage?.destroy?.();
    main.innerHTML = '';

    activePage = pages[route];
    activePage.render(main);
    aiChatWidget.refreshContext();
  }

  return {
    start() {
      window.addEventListener('hashchange', render);
      render();
    },
  };
}

document.addEventListener('DOMContentLoaded', () => {
  createApp().start();
});
