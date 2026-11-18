import { useEffect, useRef, useState } from 'react';
import { Terminal as XtermTerminal } from '@xterm/xterm';
import { FitAddon } from '@xterm/addon-fit';
import { ArrowLeft, ArrowRight, Eye, Hand, Play, RefreshCw, Square, Terminal, TriangleAlert } from 'lucide-react';
import { useTheme } from './theme';
import { Badge, Empty, formatTime, SectionHeading, shortHash } from './ui';
import type { Task, TerminalAction, TerminalOutputPage, TerminalSession } from './types';
import { binaryInput, checkedOutput, TerminalActionChannel, TerminalHttpError, TerminalTransportError, terminalRequest, utf8Input } from './terminal-client';
import type { TerminalChannelState } from './terminal-client';
import { startPagePolling } from './page-polling';
import '@xterm/xterm/css/xterm.css';
import './terminals.css';

type TerminalPage = { terminals: TerminalSession[]; cursor: number; hasMore: boolean };
type ActionPage = { actions: TerminalAction[]; cursor: number; hasMore: boolean };
class TerminalDrainError extends Error {}
const emptyChannel: TerminalChannelState = { controlled: false, claiming: false, sending: false, queuedBytes: 0, queuedActions: 0, stopping: false, message: '' };
const stateLabel: Record<string, string> = { QUEUED: '排队中', STARTING: '正在启动', RUNNING: '运行中', STOPPING: '正在收尾', EXITED: '已退出', CANCELLED: '已停止', UNKNOWN: '结果未知' };
const actionLabel = (kind: string) => ({ INPUT: '输入', RESIZE: '尺寸调整', STOP: '停止' }[kind] || kind);

export function TaskTerminals({ task, onChanged }: { task: Task; onChanged: () => Promise<void> }) {
  const [page, setPage] = useState<TerminalPage>({ terminals: [], cursor: 0, hasMore: false });
  const [before, setBefore] = useState(0), [previous, setPrevious] = useState<number[]>([]);
  const [selected, setSelected] = useState(task.pendingTerminalId || task.terminal?.id || task.lastTerminalId || '');
  const [loading, setLoading] = useState(false), [starting, setStarting] = useState(false), [error, setError] = useState('');
  const [readError, setReadError] = useState('');
  const [revision, setRevision] = useState(0), [viewer, setViewer] = useState(0);
  const unresolvedStart = useRef<string | undefined>(undefined);
  const active = useRef(true);
  useEffect(() => { active.current = true; return () => { active.current = false; }; }, []);
  const supported = task.terminalProtocolVersion === 1;
  useEffect(() => {
    if (!supported) return;
    setLoading(true);
    return startPagePolling(async signal => {
      const value = await terminalRequest<TerminalPage>(`/tasks/${encodeURIComponent(task.id)}/terminals?before=${before}&limit=30`, undefined, signal);
      if (signal.aborted) return;
      if (!Array.isArray(value.terminals) || !Number.isSafeInteger(value.cursor) || typeof value.hasMore !== 'boolean') throw new Error('终端历史响应不完整，请重新读取原记录。');
      setPage(value); setReadError(''); setLoading(false); setSelected(current => current || task.pendingTerminalId || value.terminals[0]?.id || '');
      if (unresolvedStart.current) {
        const original = value.terminals.find(terminal => terminal.startRequestId === unresolvedStart.current);
        if (original) { setSelected(original.id); unresolvedStart.current = undefined; setError('已找到原启动记录，未建立第二个 shell。'); }
      }
    }, 3000, { onError: failure => { setReadError((failure as Error).message); setLoading(false); } });
  }, [supported, task.id, task.pendingTerminalId, task.lastTerminalId, before, revision]);

  async function start() {
    if (starting || unresolvedStart.current) return;
    const requestId = window.crypto.randomUUID(); unresolvedStart.current = requestId; setStarting(true); setError('');
    try {
      const terminal = await terminalRequest<TerminalSession>(`/tasks/${encodeURIComponent(task.id)}/terminals`, { requestId, columns: 80, rows: 24 });
      if (!active.current) return;
      if (terminal.taskId !== task.id || terminal.startRequestId !== requestId) throw new Error('原启动响应无法确认，请读取原终端记录。');
      unresolvedStart.current = undefined; setSelected(terminal.id); setBefore(0); setPrevious([]); setRevision(value => value + 1);
      await onChanged().catch(failure => setError(`原终端已建立，任务状态刷新未完成：${(failure as Error).message}`));
    } catch (failure) {
      if (!active.current) return;
      if (failure instanceof TerminalHttpError && failure.status && failure.status >= 400 && failure.status < 500) {
        unresolvedStart.current = undefined; setError(`启动请求被拒绝：${failure.message}`);
      } else setError(`启动响应未确认：${(failure as Error).message} 只读取原启动记录，不重发启动请求。`);
      setBefore(0); setPrevious([]); setRevision(value => value + 1);
    } finally { if (active.current) setStarting(false); }
  }
  const canStart = !!task.workspace && !task.pendingTerminalId && !['UNKNOWN', 'RUNNING', 'QUEUED', 'CANCELLING', 'COMPLETED'].includes(task.state) && !unresolvedStart.current;
  const options = page.terminals.some(terminal => terminal.id === selected) || !task.terminal || task.terminal.id !== selected ? page.terminals : [task.terminal, ...page.terminals];
  return <section className="task-terminals">
    <SectionHeading title="任务终端" subtitle="查看原 shell 与已保存输出，手动操作使用独立的活动记录。" action={supported ? <button className="button secondary" disabled={starting || !canStart} onClick={() => void start()}><Play size={15} />{starting ? '正在建立原启动记录…' : '新建终端'}</button> : undefined} />
    {!supported ? <section className="card"><Empty icon={<Terminal size={28} />} title="当前核心未提供终端能力" description="请连接支持终端协议的核心后再使用；已有任务与记录保留。" /></section> : <>
      <div className="terminal-scope"><TriangleAlert size={17} /><p>终端在本次任务的独立目录中，以当前系统账户运行。核心保存原始输出，可能包含命令回显；交付包包含这些输出。手动修改会使当前验证与审查失效，完成收尾后需要重新验证。关闭或切换此页面会保留原 shell。</p></div>
      <div className="terminal-history-controls">
        <label>原终端记录<select aria-label="原终端记录" value={selected} disabled={!options.length && !selected} onChange={event => { setSelected(event.target.value); setViewer(0); }}>
          {!selected && <option value="">尚无终端</option>}
          {selected && !options.some(terminal => terminal.id === selected) && <option value={selected}>当前原终端 · {shortHash(selected)}</option>}
          {options.map(terminal => <option key={terminal.id} value={terminal.id}>{formatTime(terminal.createdAt)} · {stateLabel[terminal.state] || terminal.state} · {shortHash(terminal.id)}</option>)}
        </select></label>
        <button className="button secondary" disabled={loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={14} />读取原记录</button>
        <button className="icon-button" aria-label="返回较新的终端记录" disabled={!previous.length || loading} onClick={() => { setBefore(previous.at(-1)!); setPrevious(current => current.slice(0, -1)); }}><ArrowLeft size={16} /></button>
        <button className="icon-button" aria-label="读取更早的终端记录" disabled={!page.hasMore || loading} onClick={() => { setPrevious(current => [...current, before]); setBefore(page.cursor); }}><ArrowRight size={16} /></button>
      </div>
    {(error || readError) && <div className="form-error" role="alert">{error && <p>{error}</p>}{readError && <p>{readError}</p>}</div>}
      {unresolvedStart.current && <div className="terminal-observation incomplete" role="alert">原启动请求 <code>{unresolvedStart.current}</code> 尚未核对。读取原记录不会重新启动 shell。</div>}
      {!task.workspace && <p className="muted-text">确认任务计划并准备独立工作目录后，可建立手动终端。</p>}
      {selected ? <TerminalPane key={`${task.id}-${selected}-${viewer}`} terminalId={selected} onChanged={onChanged} onReplay={() => setViewer(value => value + 1)} /> : <section className="card"><Empty icon={<Terminal size={28} />} title={loading ? '正在读取原终端…' : '尚未建立手动终端'} description="新建终端会占用本次任务的工作目录；原自动执行与终端操作按同一现场门禁调度。" /></section>}
    </>}
  </section>;
}

function terminalTheme() {
  const style = getComputedStyle(document.documentElement), color = (name: string, fallback: string) => style.getPropertyValue(name).trim() || fallback;
  const foreground = color('--text', '#1c293d'), background = color('--surface', '#ffffff');
  return { foreground, background, cursor: color('--accent', foreground), cursorAccent: background,
    selectionBackground: color('--selection', '#eaf0fc'), selectionForeground: color('--selection-text', foreground),
    black: foreground, brightBlack: color('--muted', foreground), white: foreground, brightWhite: foreground,
    red: color('--danger', '#a33f38'), brightRed: color('--danger', '#a33f38'), green: color('--success', '#236544'), brightGreen: color('--success', '#236544'),
    yellow: color('--warning', '#8b5919'), brightYellow: color('--warning', '#8b5919'), blue: color('--info', '#2e5e98'), brightBlue: color('--info', '#2e5e98'),
    magenta: color('--purple', '#70449b'), brightMagenta: color('--purple', '#70449b'), cyan: color('--accent', '#1b60cd'), brightCyan: color('--accent', '#1b60cd') };
}

function TerminalPane({ terminalId, onChanged, onReplay }: { terminalId: string; onChanged: () => Promise<void>; onReplay: () => void }) {
  const host = useRef<HTMLDivElement>(null), terminal = useRef<XtermTerminal | undefined>(undefined), channel = useRef<TerminalActionChannel | undefined>(undefined);
  const fitPane = useRef<() => void>(() => {}), changed = useRef(onChanged);
  const [session, setSession] = useState<TerminalSession>(), [control, setControl] = useState(emptyChannel);
  const [cursor, setCursor] = useState(0), [phase, setPhase] = useState('loading'), [error, setError] = useState('');
  const [dimensions, setDimensions] = useState({ columns: 80, rows: 24 }), [showActions, setShowActions] = useState(false);
  const theme = useTheme(); changed.current = onChanged;
  useEffect(() => {
    const candidate = host.current; if (!candidate) return;
    const element: HTMLDivElement = candidate;
    let active = true, epoch = '', after = 0, lastMetadata = 0, lastState = '', current: TerminalSession | undefined, animation = 0;
    let outputFence: { state: string; integrity: string } | undefined;
    let nextDelay = 250, readHalted = false, reading: { signal: AbortSignal; operation: Promise<void> } | undefined;
    const drains = new Set<() => void>();
    const xterm = new XtermTerminal({ cols: 80, rows: 24, scrollback: 5000, disableStdin: true, cursorBlink: false, screenReaderMode: true,
      fontFamily: 'ui-monospace, SFMono-Regular, Consolas, "Liberation Mono", monospace', fontSize: 13, lineHeight: 1.2, theme: terminalTheme() });
    const fit = new FitAddon(); xterm.loadAddon(fit); xterm.open(element); terminal.current = xterm;
    const client = new TerminalActionChannel(terminalId, state => { if (active) setControl(state); }, value => {
      if (active) { current = value; setSession(value); }
    }); channel.current = client;
    function measure() {
      if (!active) return;
      cancelAnimationFrame(animation); animation = requestAnimationFrame(() => {
        if (!active || element.clientWidth < 20 || element.clientHeight < 20) return;
        fit.fit(); setDimensions({ columns: xterm.cols, rows: xterm.rows });
        client.resize(Math.max(2, Math.min(500, xterm.cols)), Math.max(1, Math.min(300, xterm.rows)));
      });
    }
    fitPane.current = measure;
    const resize = new ResizeObserver(measure); resize.observe(element); measure();
    void document.fonts.ready.then(() => { if (active) measure(); });
    const input = xterm.onData(value => client.input(utf8Input(value))), binary = xterm.onBinary(value => {
      try { client.input(binaryInput(value)); } catch (failure) { if (active) setError((failure as Error).message); }
    });
    const write = (bytes: Uint8Array) => new Promise<boolean>((resolve, reject) => {
      let finished = false;
      const complete = (value: boolean) => { if (finished) return; finished = true; clearTimeout(limit); drains.delete(detach); resolve(value); };
      const detach = () => complete(false);
      const limit = setTimeout(() => { if (finished) return; finished = true; drains.delete(detach); reject(new TerminalDrainError('当前窗口尚未排空原输出，字节游标保持。')); }, 15000);
      drains.add(detach); xterm.write(bytes, () => complete(active));
    });
    async function readOutput(signal: AbortSignal) {
      if (!active || signal.aborted) return;
      if (!current || Date.now() - lastMetadata > 1000) {
        const value = await terminalRequest<TerminalSession>(`/terminals/${encodeURIComponent(terminalId)}`, undefined, signal);
        if (!active || signal.aborted) return;
        if (!value || value.id !== terminalId || typeof value.epoch !== 'string' || !value.epoch || value.protocolVersion !== 1 || (epoch && value.epoch !== epoch)) throw new Error('原终端身份或 epoch 已变化，停止读取并保留当前字节游标。');
        if (['STOPPING', 'EXITED', 'CANCELLED', 'UNKNOWN'].includes(value.state)) outputFence = { state: value.state, integrity: value.outputIntegrity };
        current = outputFence && ['QUEUED', 'STARTING', 'RUNNING'].includes(value.state)
          ? { ...value, state: outputFence.state, inputFenced: true, outputIntegrity: outputFence.integrity } : value;
        epoch = value.epoch; lastMetadata = Date.now(); setSession(current); client.observe(current);
        if (lastState === 'RUNNING' && value.state !== 'RUNNING') void changed.current().catch(() => {});
        lastState = value.state;
      }
      const page = await terminalRequest<TerminalOutputPage>(`/terminals/${encodeURIComponent(terminalId)}/output?epoch=${encodeURIComponent(epoch)}&after=${after}&limit=65536`, undefined, signal);
      const bytes = await checkedOutput(page, terminalId, epoch, after); if (!active || signal.aborted) return;
      if (['STOPPING', 'EXITED', 'CANCELLED', 'UNKNOWN'].includes(page.terminalState) || !['PENDING', 'LIVE', 'COMPLETE'].includes(page.outputIntegrity)) {
        outputFence = { state: page.terminalState, integrity: page.outputIntegrity };
        current = { ...current, state: page.terminalState, inputFenced: true, outputIntegrity: page.outputIntegrity };
        setSession(current); client.observe(current); client.setReadable(false);
      }
      if (bytes.length && !await write(bytes)) return;
      if (!active) return;
      // A retained xterm may drain an accepted frame while its read is cancelled.
      after = page.next; setCursor(after);
      if (signal.aborted) return;
      const live = !page.hasMore && page.terminalState === 'RUNNING' && current.state === 'RUNNING' && !current.inputFenced && !outputFence;
      const pending = ['QUEUED', 'STARTING'].includes(current.state);
      setError(''); setPhase(page.hasMore ? 'replaying' : live ? 'live' : current.state === 'QUEUED' ? 'queued' : current.state === 'STARTING' ? 'starting' : 'saved'); client.setReadable(live);
      if (live) measure();
      nextDelay = page.hasMore ? 0 : live || pending ? 250 : 1500;
    }
    async function read(signal: AbortSignal): Promise<void | boolean> {
      if (readHalted) return false;
      try {
        const previous = reading;
        if (previous) {
          try { await previous.operation; } catch (failure) { if (!previous.signal.aborted || failure instanceof TerminalDrainError) throw failure; }
        }
        if (!active || signal.aborted) return;
        if (readHalted) return false;
        const operation = readOutput(signal), attempt = { signal, operation }; reading = attempt;
        try { await operation; } finally { if (reading === attempt) reading = undefined; }
      } catch (failure) {
        if (!active || (signal.aborted && !(failure instanceof TerminalDrainError))) return;
        client.setReadable(false); client.leaveControl(); setPhase('interrupted'); setError((failure as Error).message);
        nextDelay = 1500; lastMetadata = 0;
        if (!(failure instanceof TerminalTransportError) && !(failure instanceof TerminalHttpError && failure.status && failure.status >= 500)) { readHalted = true; return false; }
      }
    }
    function suspend() { client.setReadable(false); client.leaveControl(); if (!readHalted) setPhase('paused'); }
    const stopPolling = startPagePolling(read, () => nextDelay, { onPause: suspend, onResume: () => { suspend(); lastMetadata = 0; } });
    return () => {
      active = false; stopPolling(); cancelAnimationFrame(animation); resize.disconnect();
      for (const drain of [...drains]) drain(); client.dispose(); input.dispose(); binary.dispose(); xterm.dispose();
      terminal.current = undefined; channel.current = undefined; fitPane.current = () => {};
    };
  }, [terminalId]);
  useEffect(() => { if (terminal.current) { terminal.current.options.theme = terminalTheme(); fitPane.current(); } }, [theme.resolved]);
  useEffect(() => {
    const xterm = terminal.current; if (!xterm) return;
    const writable = control.controlled && phase === 'live' && !control.stopping && !control.unresolvedRequestId;
    xterm.options.disableStdin = !writable; xterm.options.cursorBlink = writable;
    const textarea = host.current?.querySelector('textarea');
    if (textarea) { textarea.setAttribute('aria-label', writable ? '终端输入' : '终端输出（仅查看）'); textarea.readOnly = !writable; }
    if (control.controlled) fitPane.current();
  }, [control.controlled, control.stopping, control.unresolvedRequestId, phase]);
  const controllable = session?.state === 'RUNNING' && !session.inputFenced && phase === 'live' && !control.unresolvedRequestId;
  const gap = session && !['PENDING', 'LIVE', 'COMPLETE'].includes(session.outputIntegrity);
  return <section className="card terminal-pane">
    <div className="terminal-toolbar">
      <div><h3><Terminal size={17} />{session?.recipe === 'SYSTEM_CMD' ? 'Windows 命令提示符' : '系统终端'}</h3><span className={`terminal-mode ${control.controlled ? 'controlling' : ''}`}>{control.controlled ? <Hand size={13} /> : <Eye size={13} />}{control.controlled ? '当前窗口控制' : '仅查看'}</span><span className="terminal-state">{stateLabel[session?.state || ''] || '正在读取原记录'}</span></div>
      <div className="terminal-buttons">
        {control.controlled ? <button className="button secondary" onClick={() => channel.current?.leaveControl()}><Eye size={14} />切回查看</button> : <button className="button secondary" disabled={!controllable || control.claiming || control.sending} onClick={async () => { if (!session) return; await channel.current?.acquire(session.controllerGeneration); terminal.current?.focus(); }}><Hand size={14} />{control.claiming ? '正在取得控制…' : session?.controllerGeneration ? '接管控制' : '取得控制'}</button>}
        <button className="button danger" disabled={!control.controlled || control.stopping || !!control.unresolvedRequestId} onClick={() => channel.current?.stop()}><Square size={13} />{control.stopping ? '等待原动作与收尾…' : '停止原终端'}</button>
        <button className="icon-button" aria-label="从原保存起点重读输出" title="从保存起点重读；当前控制会切回查看" onClick={onReplay}><RefreshCw size={16} /></button>
      </div>
    </div>
    {session?.controllerGeneration ? <p className="terminal-control-note">接管控制会使其他窗口的控制失效。控制凭据只保留在当前页面内存中。</p> : null}
    {(error || control.message || gap || session?.state === 'UNKNOWN') && <div className={`terminal-observation ${error || gap || control.unresolvedRequestId ? 'incomplete' : ''}`} role={error || control.unresolvedRequestId ? 'alert' : 'status'}>
      {error && <p>{error}</p>}{control.message && <p>{control.message}</p>}
      {(gap || session?.state === 'UNKNOWN') && <p>原终端结果或输出仍有未确认范围。{session?.outputGap || session?.reason || '查看已保存片段并核对原进程与实际文件，不能据此重建 shell 或重发输入。'}</p>}
      {control.unresolvedRequestId && <p>原动作 <code>{control.unresolvedRequestId}</code><button className="text-button" disabled={control.sending} onClick={() => void channel.current?.resolveOriginal()}>只核对原动作</button></p>}
    </div>}
    <div className="terminal-resizable"><div ref={host} className="native-terminal" role="region" aria-label="原终端输出" /></div>
    <div className="terminal-output-status" role="status"><span>{({ loading: '正在读取原记录', queued: '等待原终端领取', starting: '正在启动原终端', replaying: '正在重放已保存输出', live: '正在读取原输出', saved: '查看已保存输出', paused: '原输出保留，恢复后重新核对', interrupted: '读取中断，原状态需核对' } as Record<string, string>)[phase]}</span><span>已排空 {cursor.toLocaleString()} 字节</span><span>本窗口 {dimensions.columns}×{dimensions.rows} · 原终端 {session?.columns || '—'}×{session?.rows || '—'}</span>{control.queuedBytes > 0 && <span>待发送 {control.queuedBytes.toLocaleString()} 字节</span>}</div>
    <p className="terminal-retention">当前窗口保留最近 5000 行显示；完整已保存输出由核心保留，最多 64 MiB。结束并完成收尾后，可从任务页导出原输出及完整性记录。仅查看时，窗口布局不会调整原终端尺寸。</p>
    {session && <dl className="terminal-metadata"><dt>工作目录</dt><dd>{session.workspace}</dd><dt>原终端进程</dt><dd>{session.nativeProcess ? <><code>{session.nativeProcess.pid}</code> · {session.nativeProcess.processStartedAt} · {session.nativeProcess.backend}</> : '尚未取得原生进程身份'}</dd><dt>原 Worker</dt><dd><code>{session.source?.workerId || '等待原 Worker 领取'}</code>{session.source?.executorPid && <> · PID {session.source.executorPid}</>}</dd><dt>输出记录</dt><dd>{session.outputIntegrity} · {session.outputEnd.toLocaleString()} 字节 · {session.outputSegments} 个片段</dd><dt>原终端编号</dt><dd><code>{session.id}</code></dd></dl>}
    <details className="terminal-actions" onToggle={event => setShowActions(event.currentTarget.open)}><summary>原输入与控制动作记录</summary>{showActions && <TerminalActions terminalId={terminalId} revision={`${control.lastAction?.requestId || ''}-${control.lastAction?.state || ''}`} />}</details>
  </section>;
}

function TerminalActions({ terminalId, revision }: { terminalId: string; revision: string }) {
  const [before, setBefore] = useState(0), [previous, setPrevious] = useState<number[]>([]), [page, setPage] = useState<ActionPage>();
  const [error, setError] = useState(''), [loading, setLoading] = useState(true);
  useEffect(() => {
    setLoading(true); setError('');
    return startPagePolling(async signal => {
      const value = await terminalRequest<ActionPage>(`/terminals/${encodeURIComponent(terminalId)}/actions?before=${before}&limit=30`, undefined, signal);
      if (signal.aborted) return;
      if (!Array.isArray(value.actions) || !Number.isSafeInteger(value.cursor) || typeof value.hasMore !== 'boolean') throw new Error('原动作历史响应不完整。');
      setPage(value); setError(''); setLoading(false);
    }, 3000, { onError: failure => { setError((failure as Error).message); setLoading(false); } });
  }, [terminalId, before, before === 0 ? revision : 'older']);
  return <div className="terminal-action-history"><p className="muted-text">这里只显示原编号、内容摘要与派发结果；输入账本和控制摘要不包含在交付包中。原始终端输出可能包含命令回显。</p>
    {error && <div className="form-error" role="alert">{error}</div>}{loading && <p className="muted-text">正在读取原动作…</p>}
    {page?.actions.map(action => <article key={action.id} className="terminal-action-row"><div><b>#{action.sequence} · {actionLabel(action.kind)}</b><Badge state={action.state} /></div><p>{formatTime(action.createdAt)} · {action.kind === 'RESIZE' ? `${action.columns}×${action.rows}` : `${action.length} 字节`} · 派发 {action.dispatchCount} 次</p><code>{action.requestId}</code><small>SHA-256 {action.sha256}</small></article>)}
    {page && !page.actions.length && !loading && <p className="muted-text">尚无原输入或控制动作。</p>}
    <div className="terminal-history-pagination"><button className="button secondary" disabled={!previous.length || loading} onClick={() => { setBefore(previous.at(-1)!); setPrevious(value => value.slice(0, -1)); }}>较新记录</button><button className="button secondary" disabled={!page?.hasMore || loading} onClick={() => { setPrevious(value => [...value, before]); setBefore(page!.cursor); }}>更早记录</button></div>
  </div>;
}
