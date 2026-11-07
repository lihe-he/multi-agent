import { useEffect, useState } from 'react';
import type { AgentRun, Subtask, TeamMessage } from './types';
import { api, currentConnection, post } from './api';
import { Badge, Field, formatTime, shortHash } from './ui';
import { ModelRequests } from './ModelRequests';

type TeamPage = { team: { id: string; name: string; taskState: string } | null; agents: Subtask[]; runs: AgentRun[]; limits?: { maxActive: number; maxDepth: number; maxTotal: number; spawned: number } };
type MessagePage = { messages: TeamMessage[]; cursor: number; hasMore: boolean };
const liveStates = ['WAITING_RESOURCE', 'WAITING_DEPENDENCY', 'WAITING_CHILDREN', 'WAITING_TOOL', 'RUNNING'];
type PendingMessage = { requestId: string; recipient: string; content: string };
type TeamDraft = { recipient: string; content: string; pending: PendingMessage | null };

function readDraft(key: string): TeamDraft {
  try {
    const draft = JSON.parse(sessionStorage.getItem(key) || '{}');
    const pending = draft.pending && typeof draft.pending.requestId === 'string' && draft.pending.requestId.trim() && draft.pending.requestId.length <= 200
      && typeof draft.pending.recipient === 'string' && draft.pending.recipient.length <= 100 && typeof draft.pending.content === 'string' && draft.pending.content.trim() && draft.pending.content.length <= 6000 ? draft.pending as PendingMessage : null;
    return { recipient: pending?.recipient || (typeof draft.recipient === 'string' ? draft.recipient : ''), content: pending?.content || (typeof draft.content === 'string' ? draft.content.slice(0, 6000) : ''), pending };
  } catch { return { recipient: '', content: '', pending: null }; }
}

function saveDraft(key: string, draft: TeamDraft) {
  try { sessionStorage.setItem(key, JSON.stringify(draft)); } catch { /* The in-memory draft remains usable if browser storage is unavailable. */ }
}

export function TeamPanel({ taskId, onEvidence }: { taskId: string; onEvidence: (id: string) => void }) {
  const [team, setTeam] = useState<TeamPage | null>(null); const [messages, setMessages] = useState<MessagePage | null>(null);
  const [before, setBefore] = useState(0); const [filter, setFilter] = useState(''); const [expanded, setExpanded] = useState('');
  const storageKey = `workbench-team-draft:${currentConnection().baseUrl}:${taskId}`;
  const [draft] = useState(() => readDraft(storageKey));
  const [recipient, setRecipient] = useState(draft.recipient); const [content, setContent] = useState(draft.content); const [pending, setPending] = useState(draft.pending);
  const [busy, setBusy] = useState(false); const [error, setError] = useState(''); const [sendError, setSendError] = useState(''); const [sendNotice, setSendNotice] = useState(''); const [refreshTick, setRefreshTick] = useState(0);
  useEffect(() => { saveDraft(storageKey, { recipient, content, pending }); }, [storageKey, recipient, content, pending]);
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout>; const controller = new AbortController(); setMessages(null);
    async function refresh() {
      try {
        const [next, page] = await Promise.all([api<TeamPage>(`/tasks/${taskId}/team`, { signal: controller.signal }), api<MessagePage>(`/tasks/${taskId}/team/messages?before=${before}&limit=40`, { signal: controller.signal })]);
        if (active) { setTeam(next); setMessages(page); setError(''); }
      } catch (error) { if (active) setError((error as Error).message); }
      if (active) timer = setTimeout(refresh, 3000);
    }
    void refresh(); return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [taskId, before, refreshTick]);
  const members = team?.runs || [];
  const name = (runId: string) => runId === 'USER' ? '用户' : members.find(run => run.id === runId)?.title || shortHash(runId);
  const children = (team?.agents || []).filter(agent => !filter || (filter === 'live' ? liveStates.includes(agent.state) : agent.state === filter));
  return <section className="team-panel" aria-label="团队与副 Agent">
    <div className="agent-run-heading"><div><h2>团队与动态副 Agent</h2><p className="form-note">副任务使用独立现场，等待时释放模型容量；写入成果由父 Agent 核对采纳，根验证与人工验收仍需完成。</p></div></div>
    {error && <p role="alert" className="form-error">{error}</p>}
    {!team && !error && <p className="muted-text">正在读取实际团队记录…</p>}
    {team && !team.team && <p className="muted-text">此任务未启用动态委派。创建任务时可选择允许的副 Agent 定义与根限制。</p>}
    {team?.team && <>
      <div className="team-summary"><span>{team.team.name}</span><span>活动 {members.filter(run => liveStates.includes(run.state)).length} / {team.limits?.maxActive}</span><span>累计委派 {team.limits?.spawned} / {team.limits?.maxTotal}</span><span>最大深度 {team.limits?.maxDepth}</span><Badge state={team.team.taskState} /></div>
      <div className="team-parent-runs"><h3>父阶段执行</h3>{members.filter(run => !run.subtaskId).map(run => <div key={run.id}><span>{run.title || run.stage} · {run.role}</span><code>{shortHash(run.id)}</code><Badge state={run.state} /></div>)}</div>
      <label className="team-filter">副任务状态<select value={filter} onChange={event => setFilter(event.target.value)}><option value="">全部副任务</option><option value="live">活动与等待</option><option value="SUCCEEDED">已执行完成</option><option value="UNKNOWN">结果待核对</option><option value="FAILED">失败</option><option value="CANCELLED">已取消</option><option value="BUDGET_EXHAUSTED">预算不足</option></select></label>
      <div className="team-lanes">{[...new Set(children.map(agent => agent.depth))].sort((a, b) => a - b).map(depth => <div className="team-lane" key={depth}><h3>第 {depth} 层副任务</h3>{children.filter(agent => agent.depth === depth).map(agent => <article className="card team-agent" key={agent.id}>
        <header><div><h4>{agent.title}</h4><p>{agent.role} · 定义 v{agent.definitionVersion} · {agent.model}</p></div><Badge state={agent.state}>{agent.state === 'SUCCEEDED' ? '已执行完成' : undefined}</Badge></header>
        <p className="team-goal">{agent.goal}</p><dl><dt>父运行</dt><dd>{name(agent.parentRunId)} <code>{shortHash(agent.parentRunId)}</code></dd><dt>依赖</dt><dd>{agent.dependsOn.length ? agent.dependsOn.map(id => team.agents.find(value => value.id === id)?.title || shortHash(id)).join('，') : '无前置副任务'}</dd><dt>文件交接</dt><dd>{agent.integrationState === 'ADOPTED' ? '父 Agent 已核对采纳' : agent.permissions.capabilities.includes('write') ? '尚未采纳文件' : '只读结果留作证据'} · <code>{shortHash(agent.resultVersion)}</code></dd><dt>时间</dt><dd>{formatTime(agent.createdAt)}{agent.endedAt && ` → ${formatTime(agent.endedAt)}`}</dd></dl>
        <details><summary>查看实际权限与来源</summary><p>能力：{agent.permissions.capabilities.join('，') || '无'}</p><p>读取范围（各行相交）：</p><pre>{agent.permissions.readScopeLayers.map(layer => layer.join('，')).join('\n')}</pre><p>写入范围（各行相交）：</p><pre>{agent.permissions.writeScopeLayers.map(layer => layer.join('，')).join('\n')}</pre><p>原请求 <code>{agent.requestId}</code><br />工具调用 <code>{agent.toolCallId}</code><br />副任务 <code>{agent.id}</code><br />实际运行 <code>{agent.agentRunId}</code></p>{agent.integrations?.map(integration => <p key={integration.operationId}>采纳回执 <code>{integration.operationId}</code> · 父成果 <code>{shortHash(integration.resultVersion)}</code></p>)}</details>
        {agent.reason && <p className="agent-run-reason">{agent.reason}</p>}
        <footer><button className="text-button" onClick={() => setExpanded(expanded === agent.id ? '' : agent.id)}>{expanded === agent.id ? '收起副任务请求' : '查看副任务请求'}</button>{agent.evidenceId && <button className="text-button" onClick={() => onEvidence(agent.evidenceId!)}>查看原始证据</button>}</footer>
        {expanded === agent.id && <ModelRequests taskId={taskId} runId={agent.agentRunId} />}
      </article>)}</div>)}</div>
      {!children.length && <p className="muted-text">尚无匹配的实际副任务。模型提出分工后，调度器会按根限制建立记录。</p>}
      <section className="team-messages" aria-label="团队消息"><h3>成员通信</h3><p className="form-note">消息保留发件人和原请求来源；活动成员在下一次模型请求中作为资料接收，已结束成员的定向消息仅留档。</p>
        <form onSubmit={async event => {
          event.preventDefault(); if (busy) return; setBusy(true); setSendError(''); setSendNotice('');
          const message = pending || { requestId: crypto.randomUUID(), recipient, content: content.trim() }; setPending(message); saveDraft(storageKey, { recipient: message.recipient, content: message.content, pending: message });
          try { await post(`/tasks/${taskId}/team/messages`, message); setPending(null); setContent(''); saveDraft(storageKey, { recipient, content: '', pending: null }); setBefore(0); setRefreshTick(current => current + 1); setSendNotice('消息已保存'); }
          catch (error) { setSendError((error as Error).message); } finally { setBusy(false); }
        }}><Field label="收件人"><select disabled={busy || !!pending} value={recipient} onChange={event => { setRecipient(event.target.value); setSendNotice(''); }}><option value="">广播给本团队</option>{members.map(run => <option key={run.id} value={run.id}>{run.title || run.stage} · {shortHash(run.id)}{liveStates.includes(run.state) ? '' : '（已结束）'}</option>)}</select></Field><Field label="团队消息"><textarea required rows={2} maxLength={6000} disabled={busy || !!pending} value={content} onChange={event => { setContent(event.target.value); setSendNotice(''); }} /></Field><div><button className="button secondary small" disabled={busy || (!pending && !content.trim())}>{busy ? '正在发送…' : pending ? '重试原消息' : '发送团队消息'}</button>{pending && !busy && <button type="button" className="text-button" onClick={() => { setPending(null); setSendError(''); }}>保留草稿并结束重试</button>}</div></form>
        {sendError && <p role="alert" className="form-error">{sendError}</p>}
        {pending && !busy && <p className="form-note">上次发送未确认，重试沿用原编号。结束重试后再次发送会建立一条新消息。</p>}
        {sendNotice && <p role="status" className="form-note">{sendNotice}</p>}
        <div className="team-message-list">{messages?.messages.map(message => <article key={message.id}><header><b>{name(message.sender)} → {message.recipient ? name(message.recipient) : '团队广播'}</b><time>{formatTime(message.createdAt)} · #{message.sequence}</time></header><pre>{message.content}</pre>{message.requestId && <small>原请求 <code>{message.requestId}</code> · 工具 <code>{message.toolCallId}</code></small>}</article>)}</div>
        <div className="agent-run-pagination">{before > 0 && <button className="button secondary small" onClick={() => setBefore(0)}>返回最新消息</button>}{messages?.hasMore && <button className="button secondary small" onClick={() => setBefore(messages.cursor)}>加载更早消息</button>}</div>
      </section>
    </>}
  </section>;
}
