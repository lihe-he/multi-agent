import type { Connection } from './types';

let connection: Connection = {
  baseUrl: import.meta.env.VITE_API_BASE || '',
  token: import.meta.env.VITE_API_TOKEN || sessionStorage.getItem('workbench-token') || '',
};

export async function initializeConnection() {
  if (window.workbench) connection = await window.workbench.getConnection();
  return connection;
}

export function currentConnection() { return connection; }
export function setConnection(next: Connection) {
  connection = { baseUrl: next.baseUrl.replace(/\/$/, ''), token: next.token };
  sessionStorage.setItem('workbench-token', next.token);
}

export async function api<T>(path: string, options: RequestInit = {}): Promise<T> {
  const response = await fetch(`${connection.baseUrl}/api${path}`, {
    ...options,
    headers: {
      ...(options.body ? { 'Content-Type': 'application/json' } : {}),
      ...(connection.token ? { Authorization: `Bearer ${connection.token}` } : {}),
      ...options.headers,
    },
    signal: options.signal || AbortSignal.timeout(30000),
  });
  if (!response.ok) {
    let message = response.status === 401 ? '连接令牌无效，请在连接设置中更新。' : `请求未完成（${response.status}）`;
    try {
      const data = await response.json();
      message = data.message || data.error || message;
      if (typeof message !== 'string') message = JSON.stringify(message);
    } catch { /* A failed proxy may return an empty response. */ }
    throw new Error(message);
  }
  if (response.status === 204) return undefined as T;
  const text = await response.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

export function post<T>(path: string, body: unknown = {}) {
  return api<T>(path, { method: 'POST', body: JSON.stringify(body) });
}

export async function downloadTask(taskId: string) {
  const response = await fetch(`${connection.baseUrl}/api/tasks/${encodeURIComponent(taskId)}/export`, {
    headers: connection.token ? { Authorization: `Bearer ${connection.token}` } : {},
  });
  if (!response.ok) {
    const data = await response.json().catch(() => ({}));
    throw new Error(data.message || data.error || '交付包导出失败');
  }
  const url = URL.createObjectURL(await response.blob());
  const a = document.createElement('a');
  a.href = url; a.download = `agent-task-${taskId}.zip`; a.click();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
}
