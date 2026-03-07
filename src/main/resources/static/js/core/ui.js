export function showToast(message, type = 'info') {
  const toast = document.getElementById('toast');
  if (!toast) {
    return;
  }

  toast.textContent = message;
  toast.className = `toast ${type}`;
  toast.classList.remove('hidden');

  window.clearTimeout(showToast.timerId);
  showToast.timerId = window.setTimeout(() => {
    toast.classList.add('hidden');
  }, 3000);
}

export function formatDate(isoValue) {
  if (!isoValue) {
    return '—';
  }

  return new Date(isoValue).toLocaleString();
}

export function escapeHtml(value) {
  if (value == null) {
    return '';
  }

  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/"/g, '&quot;')
    .replace(/'/g, '&#39;');
}
