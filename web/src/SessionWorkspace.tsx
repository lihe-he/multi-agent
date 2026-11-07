import { useCallback, useEffect, useRef, useState } from 'react';
import { History, MessageSquare, Plus, RefreshCw, X } from 'lucide-react';
import { api, currentConnection, post } from './api';
import type { RunEvent, Session, SessionMessage, SessionSearchResult, Task } from './types';
import { Badge, Empty, Field, formatTime, PageTitle } from './ui';
import './sessions.css';
import { AgentRuns } from './AgentRuns';

type SessionPage = { sessions: Session[]; cursor: number; hasMore: boolean };
type MessagePage = { messages: SessionMessage[]; cursor: number; latestCursor: number; hasMore: boolean };
type EventPage = { events: RunEvent[]; cursor: number; hasMore: boolean };
type NoteRequest = { id: string; content: string };
type SearchTarget = { sessionId: string; sequence: number; messageId: string };

export function SessionWorkspace({ projectId, tasks, connected, focusRequest = 0, onTask, onCreate }: { projectId: string; tasks: Task[]; connected: boolean; focusRequest?: number; onTask: (task: Task) => void; onCreate: (sessionId: string) => void }) {
  const base = `/projects/${encodeURIComponent(projectId)}/sessions`;
  const selectionKey = `workbench-session:${currentConnection().baseUrl}:${projectId}`;
  const [sessions, setSessions] = useState<Session[]>([]);
  const [activeId, setActiveId] = useState(() => localStorage.getItem(selectionKey) || '');
  const [history, setHistory] = useState<SessionPage | null>(null);
  const [showHistory, setShowHistory] = useState(false); const [title, setTitle] = useState('');
  const [searchQuery, setSearchQuery] = useState(''); const [searchResults, setSearchResults] = useState<SessionSearchResult[] | null>(null);
  const [searchBusy, setSearchBusy] = useState(false); const [searchTarget, setSearchTarget] = useState<SearchTarget | null>(null);
  const [searchMore, setSearchMore] = useState(false);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const noteRequests = useRef<Record<string, NoteRequest>>({});
  const requestGeneration = useRef(0);
  const searchInput = useRef<HTMLInputElement>(null);
  useEffect(() => { if (focusRequest) searchInput.current?.focus(); }, [focusRequest]);
  const active = sessions.find(session => session.id === activeId);
  const refresh = useCallback(async (signal?: AbortSignal) => {
    const generation = ++requestGeneration.current;
    let before = 0; const open: Session[] = [];
    do {
      const page = await api<SessionPage>(`${base}?openOnly=true&limit=100&before=${before}`, { signal });
      open.push(...page.sessions); before = page.hasMore ? page.cursor : 0;
    } while (before);
    if (signal?.aborted || generation !== requestGeneration.current) return;
    setSessions(open); setActiveId(current => open.some(item => item.id === current) ? current : open[0]?.id || '');
  }, [base]);
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try { await refresh(controller.signal); setError(''); } catch (e) { if (!controller.signal.aborted) setError((e as Error).message); }
      if (!controller.signal.aborted) timer = setTimeout(poll, 3000);
    }
    if (connected) void poll();
    return () => { controller.abort(); clearTimeout(timer); };
  }, [refresh, connected]);
  useEffect(() => { if (activeId) localStorage.setItem(selectionKey, activeId); }, [activeId, selectionKey]);
  async function create(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try { const session = await post<Session>(base, { title }); ++requestGeneration.current; setSessions(items => [session, ...items.filter(item => item.id !== session.id)]); setActiveId(session.id); setTitle(''); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function change(session: Session, values: Partial<Session>) {
    setBusy(true); setError('');
    try {
      const updated = await post<Session>(`${base}/${session.id}`, { expectedVersion: session.version, ...values });
      await refresh(); if (updated.isOpen) setActiveId(updated.id);
      if (showHistory) await loadHistory();
    } catch (e) { setError((e as Error).message); await refresh().catch(() => {}); } finally { setBusy(false); }
  }
  async function loadHistory(before = 0) {
    try {
      const page = await api<SessionPage>(`${base}?limit=30&before=${before}`);
      setHistory(current => before && current ? { ...page, sessions: [...current.sessions, ...page.sessions] } : page);
    } catch (e) { setError((e as Error).message); }
  }
  async function search(event: React.FormEvent) {
    event.preventDefault(); setSearchBusy(true); setError(''); setSearchTarget(null);
    try {
      const params = new URLSearchParams({ q: searchQuery.trim(), limit: '30' });
      const page = await api<{ results: SessionSearchResult[]; hasMore: boolean }>(`${base}/search?${params}`);
      setSearchResults(page.results); setSearchMore(page.hasMore);
    } catch (e) { setError((e as Error).message); setSearchResults(null); } finally { setSearchBusy(false); }
  }
  async function openResult(result: SessionSearchResult) {
    setBusy(true); setError('');
    try {
      let session = await api<Session>(`${base}/${encodeURIComponent(result.sessionId)}`);
      if (!session.isOpen) session = await post<Session>(`${base}/${encodeURIComponent(session.id)}`, { expectedVersion: session.version, isOpen: true });
      ++requestGeneration.current;
      setSessions(items => [session, ...items.filter(item => item.id !== session.id)]);
      setSearchTarget({ sessionId: session.id, sequence: result.sequence, messageId: result.messageId }); setActiveId(session.id);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <>
    <PageTitle eyebrow="PROJECT SESSIONS" title="协作会话" description="在同一会话中记录目标、组织多个任务，并追溯各任务的执行活动。">
      <button className="button secondary" onClick={() => { setShowHistory(value => !value); void loadHistory(); }}><History size={16} />会话历史</button>
    </PageTitle>
    {error && <div className="form-error" role="alert">{error}</div>}
    <form className="session-create card" onSubmit={create}>
      <Field label="新会话名称"><input required maxLength={180} value={title} onChange={event => setTitle(event.target.value)} placeholder="为这组协作工作命名" /></Field>
      <button className="button primary" disabled={busy || !connected} type="submit"><Plus size={16} />创建会话</button>
    </form>
    <form className="session-search" role="search" onSubmit={search}>
      <Field label="搜索会话记录"><input ref={searchInput} value={searchQuery} onChange={event => setSearchQuery(event.target.value)} maxLength={120} placeholder="搜索任务目标和协作笔记" /></Field>
      <button className="button secondary" type="submit" disabled={!connected || searchBusy || !searchQuery.trim()}><MessageSquare size={15} />搜索</button>
    </form>
    {searchResults && <section className="session-search-results" aria-label="会话搜索结果">
      <header><h2>搜索结果</h2><button className="text-button" onClick={() => setSearchResults(null)}>清除</button></header>
      {!searchResults.length && <p className="muted-text">没有找到匹配记录。</p>}
      {searchResults.map(result => <button className="session-search-result" key={result.messageId} disabled={busy} onClick={() => void openResult(result)}>
        <span><b>{result.sessionTitle}</b><small>{formatTime(result.createdAt)} · {result.sequence ? `记录 #${result.sequence}` : '会话记录'}</small></span>
        <span className="session-search-preview">{highlightMatch(result.content, result.matchStart, result.matchEnd)}</span>
      </button>)}
      {searchMore && <p className="muted-text">仅显示最近 30 条匹配记录，请补充关键词缩小范围。</p>}
    </section>}
    {showHistory && <section className="card session-history" aria-label="会话历史列表">
      <h2>会话历史</h2>{history?.sessions.map(session => <div key={session.id}><span>{session.title}<small>{formatTime(session.updatedAt)}</small></span><button className="button secondary small" disabled={busy} onClick={() => void change(session, { isOpen: true })}>打开会话</button></div>)}
      {history?.hasMore && <button className="button secondary" onClick={() => void loadHistory(history.cursor)}>加载更早会话</button>}
      {history && !history.sessions.length && <p>暂无会话历史</p>}
    </section>}
    <div className="session-tabs" role="tablist" aria-label="打开的会话">{sessions.map(session => <div key={session.id} className={session.id === activeId ? 'active' : ''}>
      <button role="tab" aria-selected={session.id === activeId} aria-controls={`session-panel-${session.id}`} id={`session-tab-${session.id}`} onClick={() => setActiveId(session.id)}><MessageSquare size={14} /><span>{session.title}</span></button>
      <button className="icon-button" aria-label={`关闭会话 ${session.title}`} title="关闭标签，后台任务继续" disabled={busy} onClick={() => void change(session, { isOpen: false })}><X size={13} /></button>
    </div>)}</div>
    {active ? <SessionPanel key={`${active.id}:${searchTarget?.sessionId === active.id ? searchTarget.messageId : 'latest'}`} session={active} base={base} target={searchTarget?.sessionId === active.id ? searchTarget : null} onLatest={() => setSearchTarget(null)} tasks={tasks.filter(task => task.sessionId === active.id)} onTask={onTask} onCreate={() => onCreate(active.id)}
      noteRequests={noteRequests.current} onNoteSaved={content => setDrafts(current => current[active.id] === content ? { ...current, [active.id]: '' } : current)}
      draft={drafts[active.id] || ''} onDraft={value => setDrafts(current => ({ ...current, [active.id]: value }))} onRename={name => change(active, { title: name })} />
      : <section className="card"><Empty icon={<MessageSquare size={28} />} title="选择或创建一个会话" description="关闭标签后，历史记录与后台任务仍会保留。" /></section>}
  </>;
}

function SessionPanel({ session, base, target, onLatest, tasks, draft, onDraft, onTask, onCreate, onRename, noteRequests, onNoteSaved }: { session: Session; base: string; target: SearchTarget | null; onLatest: () => void; tasks: Task[]; draft: string; onDraft: (value: string) => void; onTask: (task: Task) => void; onCreate: () => void; onRename: (title: string) => Promise<void>; noteRequests: Record<string, NoteRequest>; onNoteSaved: (content: string) => void }) {
  const endpoint = `${base}/${session.id}`;
  const [messages, setMessages] = useState<SessionMessage[]>([]); const [before, setBefore] = useState(0); const [hasOlder, setHasOlder] = useState(false);
  const [events, setEvents] = useState<RunEvent[]>([]); const [moreEvents, setMoreEvents] = useState(false);
  const [error, setError] = useState(''); const [readError, setReadError] = useState(''); const [busy, setBusy] = useState(false); const [name, setName] = useState(session.title);
  const latest = useRef(0); const eventCursor = useRef(0);
  const [loaded, setLoaded] = useState(false); const initialMessagesRead = useRef(false);
  const reading = useRef<{ operation: Promise<void>; signal?: AbortSignal } | null>(null);
  const refresh = useCallback(async function read(signal?: AbortSignal): Promise<void> {
    const previous = reading.current;
    if (previous) {
      try { await previous.operation; } catch (error) { if (!previous.signal?.aborted) throw error; }
      if (previous.signal?.aborted && !signal?.aborted) return read(signal);
      return;
    }
    const operation = (async () => {
    const [page, activity] = await Promise.all([
      target && initialMessagesRead.current ? Promise.resolve(null) : api<MessagePage>(`${endpoint}/messages?limit=50${target ? `&before=${target.sequence + 1}` : latest.current ? `&after=${latest.current}` : ''}`, { signal }),
      api<EventPage>(`${endpoint}/events?after=${eventCursor.current}&limit=100`, { signal }),
    ]);
    if (signal?.aborted) return;
    if (page) {
      if (!initialMessagesRead.current) { setBefore(page.cursor); setHasOlder(page.hasMore); }
      initialMessagesRead.current = true; setLoaded(true);
      latest.current = Math.max(latest.current, page.latestCursor);
      setMessages(current => { const merged = new Map(current.map(item => [item.id, item])); page.messages.forEach(item => merged.set(item.id, item)); return [...merged.values()].sort((a, b) => a.sequence - b.sequence); });
    }
    eventCursor.current = activity.cursor; setMoreEvents(activity.hasMore);
    setEvents(current => { const merged = new Map(current.map(item => [item.id, item])); activity.events.forEach(item => merged.set(item.id, item)); return [...merged.values()].slice(-500); });
    setReadError('');
    })();
    const inFlight = { operation, signal }; reading.current = inFlight;
    try { await operation; } finally { if (reading.current === inFlight) reading.current = null; }
  }, [endpoint, target?.messageId, target?.sequence]);
  useEffect(() => {
    const controller = new AbortController(); let timer: ReturnType<typeof setTimeout>;
    async function poll() {
      try { await refresh(controller.signal); } catch (e) { if (!controller.signal.aborted) setReadError((e as Error).message); }
      if (!controller.signal.aborted) timer = setTimeout(poll, 2500);
    }
    void poll(); return () => { controller.abort(); clearTimeout(timer); };
  }, [refresh]);
  useEffect(() => setName(session.title), [session.title]);
  useEffect(() => {
    if (!target || !loaded) return;
    const element = document.getElementById(`session-message-${target.messageId}`);
    element?.scrollIntoView({ behavior: 'smooth', block: 'center' }); element?.focus({ preventScroll: true });
    if (!element) setError('原记录不存在，请重新搜索。');
  }, [target?.messageId, loaded]);
  async function saveNote(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      if (!noteRequests[session.id] || noteRequests[session.id].content !== draft) noteRequests[session.id] = { id: crypto.randomUUID(), content: draft };
      const request = noteRequests[session.id];
      await post(`${endpoint}/messages`, { requestId: request.id, content: request.content });
      onNoteSaved(request.content);
      if (noteRequests[session.id] === request) delete noteRequests[session.id];
      if (target) onLatest(); else await refresh();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  async function older() {
    setBusy(true);
    try {
      const page = await api<MessagePage>(`${endpoint}/messages?before=${before}&limit=50`);
      setMessages(current => { const merged = new Map(current.map(item => [item.id, item])); page.messages.forEach(item => merged.set(item.id, item)); return [...merged.values()].sort((a, b) => a.sequence - b.sequence); });
      setBefore(page.cursor); setHasOlder(page.hasMore);
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <section className="session-panel" id={`session-panel-${session.id}`} role="tabpanel" aria-labelledby={`session-tab-${session.id}`}>
    <form className="session-title" onSubmit={event => { event.preventDefault(); void onRename(name); }}><Field label="当前会话名称"><input required maxLength={180} value={name} onChange={event => setName(event.target.value)} /></Field><button type="submit" className="button secondary small">重命名</button></form>
    {(error || readError) && <div className="form-error" role="alert">{error || readError}</div>}
    <div className="session-columns"><section className="card session-records" aria-label="会话记录"><h2>会话记录</h2>
      {target && <p className="form-note">正在查看搜索定位的历史记录。<button className="text-button" onClick={onLatest}>返回最新记录</button></p>}
      {hasOlder && <button className="button secondary small" disabled={busy || !loaded} onClick={() => void older()}>加载更早记录</button>}
      {!messages.length && <p className="muted-text">记录协作背景，或创建第一个研发任务。</p>}
      {messages.map(message => <article id={`session-message-${message.id}`} tabIndex={-1} className={message.id === target?.messageId ? 'search-target' : undefined} key={message.id}><header><b>{message.kind === 'TASK_GOAL' ? '任务目标' : '协作笔记'}</b><time>{formatTime(message.createdAt)}</time></header><p>{message.content}</p>{message.taskId && tasks.find(task => task.id === message.taskId) && <button className="text-button" onClick={() => onTask(tasks.find(task => task.id === message.taskId)!)}>查看来源任务</button>}</article>)}
      <form onSubmit={saveNote}><Field label="协作笔记" hint="笔记保留在当前会话，不会自动发起模型请求或修改已确认的任务目标。"><textarea required rows={3} maxLength={16000} value={draft} onChange={event => onDraft(event.target.value)} /></Field><button className="button secondary" disabled={busy || !draft.trim()} type="submit">保存笔记</button></form>
    </section><section className="card session-tasks" aria-label="会话任务"><header><h2>会话任务</h2><button className="button primary small" onClick={onCreate}><Plus size={14} />新建会话任务</button></header>
      {!tasks.length && <p className="muted-text">任务仍需确认完成约定、权限与预算。</p>}
      {tasks.map(task => <button className="session-task" key={task.id} onClick={() => onTask(task)}><span>{task.title}</span><Badge state={task.state} /></button>)}
      <AgentRuns key={session.id} endpoint={`${base}/${session.id}/agent-runs`} onTask={taskId => { const task = tasks.find(item => item.id === taskId); if (task) onTask(task); }} />
      <h2>任务活动</h2><p className="form-note">显示最近 500 条已读取活动；来源链接回到实际任务。</p>
      {events.map(event => <article className="session-event" key={event.id}><small>#{event.id} · {event.type} · {formatTime(event.createdAt)}</small><p>{event.message}</p>{tasks.find(task => task.id === event.taskId) && <button className="text-button" onClick={() => onTask(tasks.find(task => task.id === event.taskId)!)}>查看活动来源</button>}</article>)}
      <button className="button secondary small" onClick={() => void refresh().catch(e => setError(e.message))}><RefreshCw size={13} />{moreEvents ? '读取后续活动' : '刷新活动'}</button>
    </section></div>
  </section>;
}

function highlightMatch(content: string, start: number, end: number) {
  if (start < 0 || end <= start || end > content.length) return content;
  return <>{content.slice(0, start)}<mark>{content.slice(start, end)}</mark>{content.slice(end)}</>;
}
