import type { Connection } from './types';
import { readBrowserValue, writeBrowserValue } from './browser-storage';

let connection: Connection = {
  baseUrl: import.meta.env.VITE_API_BASE || '',
  token: readBrowserValue('sessionStorage', 'workbench-token') || '',
};

export async function initializeConnection() {
  if (window.workbench) connection = await window.workbench.getConnection();
  else {
    const response = await fetch('./workbench-config.json', { cache: 'no-store', signal: AbortSignal.timeout(5000) });
    if (!response.ok) throw new Error('无法读取工作台运行时配置，请检查 workbench-config.json。');
    const config: unknown = await response.json();
    if (!config || typeof config !== 'object' || Array.isArray(config) || Object.keys(config).some(key => key !== 'apiBaseUrl')) throw new Error('运行时配置仅允许 apiBaseUrl，不能包含令牌或凭据。');
    const baseUrl = (config as { apiBaseUrl?: unknown }).apiBaseUrl;
    if (baseUrl !== undefined && typeof baseUrl !== 'string') throw new Error('apiBaseUrl 必须为字符串。');
    connection = { ...connection, baseUrl: validateApiBase(baseUrl as string | undefined ?? connection.baseUrl) };
  }
  return connection;
}

export function validateApiBase(value: string) {
  const base = value.trim().replace(/\/+$/, '');
  if (!base) return '';
  if (base.startsWith('/') && !base.startsWith('//') && !/[?#\\]/.test(base)) return base;
  const url = new URL(base);
  if (!['http:', 'https:'].includes(url.protocol) || !['127.0.0.1', 'localhost', '[::1]'].includes(url.hostname) || url.username || url.password || url.search || url.hash || url.pathname !== '/') throw new Error('核心地址必须是本机回环地址或同源代理路径。');
  return url.origin;
}

export function currentConnection() { return connection; }
export function setConnection(next: Connection) {
  connection = { baseUrl: validateApiBase(next.baseUrl), token: next.token };
  writeBrowserValue('sessionStorage', 'workbench-token', next.token);
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
