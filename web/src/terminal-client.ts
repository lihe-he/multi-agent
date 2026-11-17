import { currentConnection } from './api';
import type { TerminalAction, TerminalControl, TerminalOutputPage, TerminalSession } from './types';

export const TERMINAL_INPUT_BYTES = 16_384;
export const TERMINAL_FRAME_BYTES = 65_536;
export const TERMINAL_OUTPUT_BYTES = 67_108_864;
const REQUEST_BYTES = 32_768;
const QUEUE_BYTES = 32_768;
const QUEUE_ACTIONS = 64;
const RESPONSE_BYTES = 2 * 1024 * 1024;
const encoder = new TextEncoder();

export class TerminalHttpError extends Error {
  constructor(message: string, readonly status?: number) { super(message); }
}

export async function terminalRequest<T>(path: string, body?: unknown, signal?: AbortSignal): Promise<T> {
  const connection = currentConnection(), encoded = body === undefined ? undefined : JSON.stringify(body);
  if (encoded !== undefined && encoder.encode(encoded).length > REQUEST_BYTES) throw new TerminalHttpError('终端请求超过 32 KiB，本次内容未发送。');
  const response = await fetch(`${connection.baseUrl}/api${path}`, {
    method: body === undefined ? 'GET' : 'POST', ...(encoded === undefined ? {} : { body: encoded }),
    headers: { ...(encoded === undefined ? {} : { 'Content-Type': 'application/json' }), ...(connection.token ? { Authorization: `Bearer ${connection.token}` } : {}) },
    signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(8000)]) : AbortSignal.timeout(8000),
  });
  const parts: Uint8Array[] = []; let length = 0;
  if (response.body) {
    const reader = response.body.getReader();
    try {
      for (;;) {
        const read = await reader.read(); if (read.done) break;
        length += read.value.length;
        if (length > RESPONSE_BYTES) { await reader.cancel(); throw new TerminalHttpError('终端响应超过读取边界，当前观察未确认。', response.status); }
        parts.push(read.value);
      }
    } finally { reader.releaseLock(); }
  } else {
    const bytes = new Uint8Array(await response.arrayBuffer()); length = bytes.length;
    if (length > RESPONSE_BYTES) throw new TerminalHttpError('终端响应超过读取边界，当前观察未确认。', response.status);
    parts.push(bytes);
  }
  const bytes = new Uint8Array(length); let offset = 0;
  for (const part of parts) { bytes.set(part, offset); offset += part.length; }
  let value: unknown;
  try { value = JSON.parse(new TextDecoder().decode(bytes)); }
  catch { throw new TerminalHttpError(`终端响应无法读取（${response.status}），请核对原记录。`, response.status); }
  if (!response.ok) {
    const message = value && typeof value === 'object' && 'message' in value && typeof value.message === 'string' ? value.message : `终端请求未完成（${response.status}）`;
    throw new TerminalHttpError(message, response.status);
  }
  return value as T;
}

export function utf8Input(value: string) { return encoder.encode(value); }
export function binaryInput(value: string) {
  const bytes = new Uint8Array(value.length);
  for (let index = 0; index < value.length; index++) {
    const code = value.charCodeAt(index); if (code > 255) throw new Error('终端二进制输入格式无效，本次内容未发送。');
    bytes[index] = code;
  }
  return bytes;
}
export function base64(bytes: Uint8Array) {
  let text = ''; for (let index = 0; index < bytes.length; index += 4096) text += String.fromCharCode(...bytes.subarray(index, index + 4096));
  return btoa(text);
}
export async function sha256(bytes: Uint8Array) {
  if (!window.crypto?.subtle) throw new Error('当前浏览器不能校验终端原字节，请使用支持本机安全上下文的浏览器。');
  const copy = new Uint8Array(bytes);
  const hash = new Uint8Array(await window.crypto.subtle.digest('SHA-256', copy.buffer));
  return Array.from(hash, value => value.toString(16).padStart(2, '0')).join('');
}
export async function checkedOutput(page: TerminalOutputPage, terminalId: string, epoch: string, after: number) {
  if (!page || page.terminalId !== terminalId || page.epoch !== epoch || page.offset !== after
      || !Number.isSafeInteger(page.length) || page.length < 0 || page.length > TERMINAL_FRAME_BYTES
      || !Number.isSafeInteger(page.next) || page.next !== after + page.length
      || !Number.isSafeInteger(page.publishedEnd) || page.publishedEnd < page.next || page.publishedEnd > TERMINAL_OUTPUT_BYTES
      || typeof page.hasMore !== 'boolean' || page.hasMore !== (page.next < page.publishedEnd)
      || (page.hasMore && page.length === 0)
      || typeof page.dataBase64 !== 'string' || page.dataBase64.length > 4 * Math.ceil(TERMINAL_FRAME_BYTES / 3)
      || !/^(?:[A-Za-z0-9+/]{4})*(?:[A-Za-z0-9+/]{2}==|[A-Za-z0-9+/]{3}=)?$/.test(page.dataBase64)
      || !/^[0-9a-f]{64}$/.test(page.sha256)) throw new Error('原输出编号、epoch 或字节游标不一致；当前游标保持。');
  const raw = atob(page.dataBase64), bytes = new Uint8Array(raw.length);
  for (let index = 0; index < raw.length; index++) bytes[index] = raw.charCodeAt(index);
  if (bytes.length !== page.length || base64(bytes) !== page.dataBase64 || await sha256(bytes) !== page.sha256) throw new Error('原终端输出字节缺失或摘要不一致；当前游标保持。');
  return bytes;
}

type QueuedAction = { kind: 'INPUT'; bytes: Uint8Array } | { kind: 'RESIZE'; columns: number; rows: number } | { kind: 'STOP' };
type ExpectedAction = Pick<TerminalAction, 'terminalId' | 'requestId' | 'kind' | 'sequence' | 'controllerId' | 'controllerGeneration' | 'length' | 'sha256' | 'columns' | 'rows'>;
export interface TerminalChannelState {
  controlled: boolean; claiming: boolean; sending: boolean; queuedBytes: number; queuedActions: number; stopping: boolean;
  message: string; unresolvedRequestId?: string; lastAction?: TerminalAction;
}
const wait = (ms: number) => new Promise<void>(resolve => window.setTimeout(resolve, ms));

/** Owns only this pane's in-memory control secret and unsent input. */
export class TerminalActionChannel {
  private lease?: TerminalControl;
  private queue: QueuedAction[] = [];
  private queuedBytes = 0;
  private sending = false;
  private claiming = false;
  private stopping = false;
  private readable = false;
  private disposed = false;
  private message = '';
  private unresolved?: ExpectedAction;
  private lastAction?: TerminalAction;
  private observed?: TerminalSession;
  private requestedResize?: { generation: number; columns: number; rows: number };

  constructor(private readonly terminalId: string, private readonly change: (state: TerminalChannelState) => void,
    private readonly metadata: (session: TerminalSession) => void) { this.publish(); }

  private publish() {
    if (!this.disposed) this.change({ controlled: !!this.lease, claiming: this.claiming, sending: this.sending, queuedBytes: this.queuedBytes,
      queuedActions: this.queue.length, stopping: this.stopping, message: this.message, unresolvedRequestId: this.unresolved?.requestId, lastAction: this.lastAction });
  }
  private clearControl() { this.lease = undefined; this.queue = []; this.queuedBytes = 0; this.requestedResize = undefined; }
  observe(session: TerminalSession) {
    if (session.id !== this.terminalId) return;
    this.observed = session;
    if (this.lease && (session.state !== 'RUNNING' || session.inputFenced || session.controllerGeneration !== this.lease.generation || session.controllerId !== this.lease.terminal.controllerId)) {
      this.clearControl(); this.message = '当前控制已结束或被其他窗口接管，此窗口只查看原输出。'; this.publish();
    }
  }
  setReadable(value: boolean) { this.readable = value; if (value) void this.drain(); }
  leaveControl() { this.clearControl(); this.message = '已切回查看模式；原 shell 保留。'; this.publish(); }
  dispose() { this.disposed = true; this.clearControl(); }

  async acquire(expectedGeneration: number) {
    if (this.disposed || this.claiming || this.sending || this.unresolved) return;
    this.claiming = true; this.message = ''; this.publish();
    try {
      const controllerId = window.crypto.randomUUID();
      const lease = await terminalRequest<TerminalControl>(`/terminals/${encodeURIComponent(this.terminalId)}/control`, { expectedGeneration, controllerId });
      if (this.disposed) return;
      if (!lease || lease.terminal?.id !== this.terminalId || lease.terminal.controllerId !== controllerId || lease.generation !== expectedGeneration + 1
          || lease.terminal.controllerGeneration !== lease.generation || typeof lease.controlSecret !== 'string' || !/^[A-Za-z0-9_-]{43}$/.test(lease.controlSecret)
          || !Number.isSafeInteger(lease.nextSequence) || lease.nextSequence !== lease.terminal.lastActionSequence + 1) throw new Error('控制回执不完整，请查看原终端后重新取得控制。');
      if (!this.readable || this.observed?.state !== 'RUNNING' || this.observed.inputFenced
          || this.observed.controllerGeneration > lease.generation) throw new Error('原终端状态已变化，此窗口保持查看模式。');
      this.lease = lease; this.observed = lease.terminal; this.stopping = false;
      this.metadata(lease.terminal); this.message = '此窗口已取得控制。关闭页面会保留原 shell。';
    } catch (error) { this.clearControl(); this.message = (error as Error).message; }
    finally { this.claiming = false; this.publish(); }
  }

  input(bytes: Uint8Array) {
    if (!this.lease || !this.readable || this.stopping || this.unresolved || this.observed?.state !== 'RUNNING' || this.observed.inputFenced) return;
    if (bytes.length < 1 || bytes.length > TERMINAL_INPUT_BYTES) { this.message = '一次输入最多 16 KiB；本次粘贴或输入未发送。'; this.publish(); return; }
    if (this.queuedBytes + bytes.length > QUEUE_BYTES) { this.message = '待发送输入已达到 32 KiB；本次输入未发送，请等待原动作确认。'; this.publish(); return; }
    const last = this.queue.at(-1);
    if (last?.kind === 'INPUT' && last.bytes.length + bytes.length <= TERMINAL_INPUT_BYTES) {
      const joined = new Uint8Array(last.bytes.length + bytes.length); joined.set(last.bytes); joined.set(bytes, last.bytes.length); last.bytes = joined;
    } else {
      if (this.queue.length >= QUEUE_ACTIONS) { this.message = '终端待发送动作已满；本次输入未发送。'; this.publish(); return; }
      this.queue.push({ kind: 'INPUT', bytes: new Uint8Array(bytes) });
    }
    this.queuedBytes += bytes.length; this.publish(); void this.drain();
  }
  resize(columns: number, rows: number) {
    if (!this.lease || this.stopping || this.unresolved || !this.readable) return;
    if (!Number.isInteger(columns) || columns < 2 || columns > 500 || !Number.isInteger(rows) || rows < 1 || rows > 300) return;
    if (this.requestedResize?.generation === this.lease.generation && this.requestedResize.columns === columns && this.requestedResize.rows === rows) return;
    if (this.observed?.columns === columns && this.observed.rows === rows && !this.queue.some(action => action.kind === 'RESIZE')) return;
    const last = this.queue.at(-1);
    if (last?.kind === 'RESIZE') { last.columns = columns; last.rows = rows; }
    else if (this.queue.length < QUEUE_ACTIONS) this.queue.push({ kind: 'RESIZE', columns, rows });
    else return;
    this.requestedResize = { generation: this.lease.generation, columns, rows };
    this.publish(); void this.drain();
  }
  stop() {
    if (!this.lease || this.stopping || this.unresolved) return;
    this.queue = []; this.queuedBytes = 0; this.stopping = true; this.queue.push({ kind: 'STOP' }); this.publish(); void this.drain();
  }

  private verify(action: TerminalAction, expected: ExpectedAction) {
    for (const field of ['terminalId', 'requestId', 'kind', 'sequence', 'controllerId', 'controllerGeneration', 'length', 'sha256', 'columns', 'rows'] as const)
      if (action?.[field] !== expected[field]) throw new Error('原动作回执与本次编号或内容摘要不一致；输入已停止。');
    if (!['INTENT', 'DISPATCHED', 'ACKNOWLEDGED', 'REJECTED', 'UNKNOWN', 'WITHDRAWN'].includes(action.state)) throw new Error('原动作状态无法确认；输入已停止。');
    return action;
  }
  private async original(expected: ExpectedAction) {
    let action: TerminalAction | undefined;
    for (let attempt = 0; attempt < 3 && !this.disposed; attempt++) {
      try { action = this.verify(await terminalRequest<TerminalAction>(`/terminals/${encodeURIComponent(this.terminalId)}/actions/${encodeURIComponent(expected.requestId)}`), expected); }
      catch { /* A missing read receipt cannot authorize resending input. */ }
      if (action && !['INTENT', 'DISPATCHED'].includes(action.state)) return action;
      if (attempt < 2) await wait(350);
    }
    return action;
  }
  async resolveOriginal() {
    if (!this.unresolved || this.sending || this.disposed) return;
    const expected = this.unresolved; this.sending = true; this.publish();
    const action = await this.original(expected);
    if (!this.disposed) {
      this.lastAction = action;
      if (action && ['ACKNOWLEDGED', 'REJECTED', 'WITHDRAWN'].includes(action.state)) {
        this.unresolved = undefined; this.message = `原动作已核对（${action.state}）；需显式重新取得控制。`;
      } else this.message = '原动作仍未取得完整确认；只查看输出和原记录，不重发输入。';
      this.sending = false; this.publish();
    }
  }
  private async drain() {
    if (this.disposed || this.sending || !this.lease || !this.readable || this.unresolved) return;
    this.sending = true; this.publish();
    try {
      while (!this.disposed && this.lease && this.readable && this.queue.length && !this.unresolved) {
        const queued = this.queue.shift()!;
        const lease: TerminalControl = this.lease;
        const bytes = queued.kind === 'INPUT' ? queued.bytes : new Uint8Array();
        this.queuedBytes = Math.max(0, this.queuedBytes - bytes.length);
        const columns = queued.kind === 'RESIZE' ? queued.columns : 0, rows = queued.kind === 'RESIZE' ? queued.rows : 0;
        const expected: ExpectedAction = { terminalId: this.terminalId, requestId: window.crypto.randomUUID(), kind: queued.kind,
          sequence: lease.nextSequence, controllerId: lease.terminal.controllerId!, controllerGeneration: lease.generation, length: bytes.length,
          sha256: await sha256(queued.kind === 'INPUT' ? bytes : utf8Input(`${queued.kind}:${columns}:${rows}`)), columns, rows };
        if (this.disposed || this.lease !== lease) break;
        const body = { requestId: expected.requestId, kind: expected.kind, sequence: expected.sequence, controllerId: expected.controllerId,
          generation: expected.controllerGeneration, controlSecret: lease.controlSecret,
          ...(queued.kind === 'INPUT' ? { dataBase64: base64(bytes) } : queued.kind === 'RESIZE' ? { columns: queued.columns, rows: queued.rows } : {}) };
        let action: TerminalAction | undefined;
        try { action = this.verify(await terminalRequest<TerminalAction>(`/terminals/${encodeURIComponent(this.terminalId)}/actions`, body), expected); }
        catch { if (!this.disposed) action = await this.original(expected); }
        if (this.disposed) break;
        this.lastAction = action;
        if (action?.state === 'ACKNOWLEDGED') {
          lease.nextSequence = expected.sequence + 1;
          if (queued.kind === 'RESIZE' && this.observed) this.observed = { ...this.observed, columns: queued.columns, rows: queued.rows };
          if (queued.kind === 'STOP') this.clearControl();
          this.message = ''; this.publish();
        } else {
          this.clearControl();
          if (!action || ['INTENT', 'DISPATCHED', 'UNKNOWN'].includes(action.state)) this.unresolved = expected;
          this.message = action?.state === 'REJECTED' || action?.state === 'WITHDRAWN' ? '原动作已拒绝或撤回；需查看最新状态后重新取得控制。' : '原动作回执未确认；输入已停止，保留原编号供核对。';
          this.publish();
        }
      }
    } catch (error) { this.clearControl(); this.message = (error as Error).message; }
    finally { this.sending = false; this.publish(); }
  }
}
