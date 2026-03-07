export const API = '/v1';

function buildHeaders(options) {
  const headers = { ...options.headers };
  const hasBody = options.body != null;
  const isFormData = typeof FormData !== 'undefined' && options.body instanceof FormData;

  if (hasBody && !isFormData && !headers['Content-Type']) {
    headers['Content-Type'] = 'application/json';
  }

  return headers;
}

async function ensureOk(response) {
  if (response.ok) {
    return response;
  }

  const errorText = await response.text();
  throw new Error(errorText || `HTTP ${response.status}`);
}

export async function fetchJson(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: buildHeaders(options),
  });

  await ensureOk(response);
  return response.json();
}

export async function fetchText(url, options = {}) {
  const response = await fetch(url, {
    ...options,
    headers: buildHeaders(options),
  });

  await ensureOk(response);
  return response.text();
}
