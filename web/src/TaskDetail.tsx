import { useEffect, useRef, useState } from 'react';
import { Activity, ArrowLeft, ArrowRight, Check, CheckCheck, ChevronDown, Circle, ClipboardCheck, Code2, Download, FileCode2, Fingerprint, Flag, FolderOpen, GitBranch, ListChecks, LockKeyhole, Pause, Play, ShieldCheck, Square, Terminal, TriangleAlert } from 'lucide-react';
import { api, downloadTask } from './api';
import type { Evidence, Project, Route, RunEvent, Stage, Task } from './types';
import { Badge, Checklist, Empty, Field, formatTime, kindLabel, Modal, modeLabel, SaveButton, SectionHeading, shortHash } from './ui';

export function TaskDetail({ task: initial, project, routes, onBack, onAction, onNotice }: { task: Task; project?: Project; routes: Route[]; onBack: () => void; onAction: (id: string, action: string) => Promise<void>; onNotice: (message: string, error?: boolean) => void }) {
  const [task, setTask] = useState(initial); const [tab, setTab] = useState('overview');
  const [events, setEvents] = useState<RunEvent[]>([]); const cursor = useRef(0);
  const [mcpOperations, setMcpOperations] = useState<{ actionKey?: string; state: string; error?: string }[]>([]);
  const [diff, setDiff] = useState<{ diff: string; version?: string; workspace?: string } | null>(null);
  const [diffError, setDiffError] = useState(''); const [busy, setBusy] = useState('');
  const [eventFilter, setEventFilter] = useState('');
  const [editBudget, setEditBudget] = useState(false);
  const [reconcile, setReconcile] = useState(false);
  useEffect(() => { setTask(current => current.id === initial.id ? { ...current, ...initial } : initial); }, [initial]);
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout>;
    cursor.current = 0; setEvents([]); setDiff(null);
    async function poll() {
      try {
        const [detail, batch, mcpOps] = await Promise.all([
          api<Task>(`/tasks/${initial.id}`),
          api<{ events: RunEvent[]; cursor: number; hasMore?: boolean }>(`/tasks/${initial.id}/events?after=${cursor.current}&limit=200`),
          api<{ actionKey?: string; state: string; error?: string }[]>(`/tasks/${initial.id}/mcp-operations`),
        ]);
        if (!active) return;
        setTask(detail);
        setMcpOperations(mcpOps);
        if (batch.events?.length) {
          cursor.current = batch.cursor || batch.events[batch.events.length - 1].id;
          setEvents(old => [...old, ...batch.events.filter(e => !old.some(o => o.id === e.id))].slice(-500));
        }
        timer = setTimeout(poll, batch.hasMore ? 150 : 2500);
      } catch { if (active) timer = setTimeout(poll, 4000); }
    }
    void poll(); return () => { active = false; clearTimeout(timer); };
  }, [initial.id]);
  useEffect(() => {
    if (tab !== 'diff') return;
    let active = true; setDiffError('');
    api<{ diff: string; version?: string; workspace?: string }>(`/tasks/${task.id}/diff`).then(value => { if (active) setDiff(value); }).catch(error => { if (active) setDiffError(error.message); });
    return () => { active = false; };
  }, [tab, task.id, task.version]);

  const stages: Stage[] = Array.isArray(task.plan) ? task.plan : task.plan?.stages || [];
  const route = task.routeSnapshot || routes.find(r => r.id === task.routeId);
  const evidence = task.evidence || [];
  const used = task.budget?.usedCalls || 0, max = task.budget?.maxCalls || 0, reserve = task.budget?.reserveCalls || 0;
  async function action(name: string) {
    if (name === 'cancel' && !window.confirm('取消后将停止根任务和全部子任务，并保留当前成果。是否继续？')) return;
    if (name === 'accept' && !window.confirm('确认已查看交付成果、验证和独立审查记录，并接受本次交付？这将记录人工验收，不直接改动原项目。')) return;
    setBusy(name); try { await onAction(task.id, name); setTask(await api<Task>(`/tasks/${task.id}`)); }
    catch (error) { onNotice((error as Error).message, true); } finally { setBusy(''); }
  }
  const finished = ['COMPLETED', 'CANCELLED', 'FAILED'].includes(task.state);
  return <div className="task-detail">
    <button className="back-link" onClick={onBack}><ArrowLeft size={15} />全部任务<span>/</span>{project?.name || '项目'}</button>
    <div className="task-title"><div><div className="inline-meta"><span className="mono">TASK {task.id.slice(0, 8).toUpperCase()}</span><Badge state={task.state} /></div><h1>{task.title}</h1><p>{task.goal}</p></div><div className="title-actions">
      <button className="button secondary" onClick={async () => { try { await downloadTask(task.id); onNotice('交付包已导出'); } catch (e) { onNotice((e as Error).message, true); } }}><Download size={16} />导出</button>
      {task.state === 'DRAFT' && <button className="button primary" disabled={!!busy} onClick={() => action('approve')}><Play size={16} />确认并执行</button>}
      {['RUNNING', 'QUEUED', 'WAITING_RESOURCE'].includes(task.state) && <button className="button secondary" disabled={!!busy} onClick={() => action('pause')}><Pause size={16} />暂停</button>}
      {['PAUSED', 'WAITING_INPUT', 'FAILED'].includes(task.state) && <button className="button primary" disabled={!!busy} onClick={() => action('resume')}><Play size={16} />核验并继续</button>}
      {task.state === 'UNKNOWN' && <button className="button primary" disabled={!!busy} onClick={() => setReconcile(true)}><ClipboardCheck size={16} />核对未知操作</button>}
      {task.state === 'AWAITING_ACCEPTANCE' && <button className="button primary" disabled={!!busy} onClick={() => action('accept')}><CheckCheck size={17} />验收交付</button>}
      {task.state === 'AWAITING_ACCEPTANCE' && <button className="button secondary" disabled={!!busy} onClick={() => action('revalidate')}><ShieldCheck size={16} />重新验证</button>}
      {!finished && task.state !== 'CANCELLING' && <button className="icon-button border" title="取消任务" aria-label="取消任务" disabled={!!busy} onClick={() => action('cancel')}><Square size={15} /></button>}
    </div></div>
    {task.error && <div className="alert-banner"><TriangleAlert size={19} /><div><b>当前需要处理</b><p>{task.error}</p></div></div>}
    {task.state === 'DRAFT' && <div className="info-banner"><ClipboardCheck size={18} /><span>请核对完成约定、协作步骤与预算。确认后将在隔离工作目录中开始执行。</span></div>}
    <div className="task-summary-strip"><span><GitBranch size={15} />{modeLabel(task.mode)}</span><span><Fingerprint size={15} />基线 <code>{shortHash(task.baseline)}</code></span><span><Activity size={15} />{route?.name || '待选择路由'}</span><span><Circle size={12} />更新于 {formatTime(task.updatedAt)}</span></div>
    <nav className="tabs" aria-label="任务详情">{[{ id: 'overview', label: '协作计划', icon: GitBranch }, { id: 'evidence', label: '验证与审查', icon: ShieldCheck, count: evidence.length }, { id: 'diff', label: '代码差异', icon: Code2 }, { id: 'context', label: '执行上下文', icon: LayersIcon }, { id: 'events', label: '活动记录', icon: Activity }].map(item => <button key={item.id} className={tab === item.id ? 'active' : ''} onClick={() => setTab(item.id)}><item.icon size={16} />{item.label}{'count' in item && !!item.count && <span>{item.count}</span>}</button>)}</nav>
    {tab === 'overview' && <div className="detail-grid"><div className="detail-main">
      <section className="card"><SectionHeading title="协作流程" subtitle={task.modeReason || '各阶段按依赖推进，验证与审查结果单独保存。'} /><div className="pipeline">{stages.length ? stages.map((stage, i) => <div className={`pipeline-step ${stage.state?.toLowerCase() || 'pending'}`} key={stage.id}><div className="stage-rail"><span>{['COMPLETED', 'SUCCEEDED'].includes(stage.state || '') ? <Check size={15} /> : String(i + 1).padStart(2, '0')}</span>{i < stages.length - 1 && <i />}</div><div className="stage-content"><div><h3>{stage.title || stage.id}</h3><Badge state={stage.state || 'PENDING'} /></div><p>{stage.role || 'Agent'}{stage.dependencies?.length ? <><span className="dot-sep">·</span>依赖 {stage.dependencies.join('、')}</> : <><span className="dot-sep">·</span>可独立开始</>}</p></div></div>) : <Empty icon={<GitBranch size={25} />} title="计划准备中" description="核心生成的执行阶段将在这里显示。" />}</div></section>
      <section className="card"><SectionHeading title="完成约定" subtitle="验证和独立审查始终以这份约定为准。" /><div className="contract-content"><h4><Flag size={15} />验收条件</h4><Checklist items={task.contract?.acceptance || []} /><h4><Terminal size={15} />必要验证</h4><div className="command-stack">{task.contract?.commands?.map((command, i) => <code key={i}><span>$</span> {command}</code>)}</div><h4><FolderOpen size={15} />修改范围</h4><div className="chips">{task.contract?.scope?.map(scope => <span className="chip mono" key={scope}>{scope}</span>)}</div></div></section>
      {!!task.mcpAuthorization?.bindings?.length && <section className="card"><SectionHeading title="MCP 固定动作" subtitle="仅开发阶段开放；每项参数均由创建任务时确认。" /><div className="contract-content"><dl className="metadata"><dt>授权版本</dt><dd>{task.mcpAuthorization.version || 1}</dd><dt>工具预算</dt><dd>{mcpOperations.length ? `${task.mcpBudget?.usedCalls || 0} / ${task.mcpBudget?.maxCalls || 0} 次 · ${task.mcpBudget?.committedDurationMs || 0} / ${task.mcpBudget?.maxDurationMs || 0} 毫秒` : `最多 ${task.mcpBudget?.maxCalls || 0} 次 · ${task.mcpBudget?.maxDurationMs || 0} 毫秒`}</dd></dl>{task.mcpAuthorization.bindings.map(binding => { const operation = mcpOperations.find(item => item.actionKey === binding.actionKey); return <article className="evidence-card" key={binding.actionKey}><div className="evidence-toggle"><div><h3>{binding.serverName || binding.serverId} / {binding.name}</h3><p>{operation?.error || `状态：${operation?.state || '等待模型选择'} · 超时 ${binding.timeoutMs} 毫秒`}</p></div><Badge state={operation?.state || 'PENDING'} /></div><pre className="evidence-output">{JSON.stringify(binding.fixedArguments, null, 2)}</pre></article>; })}</div></section>}
    </div><aside className="detail-aside"><section className="card compact"><h3>根任务预算</h3><div className="budget-number">{used}<span>/ {max} 次</span></div><div className="budget-track"><div className="budget-used" style={{ width: `${Math.min(100, max ? used / max * 100 : 0)}%` }} /><div className="budget-reserve" style={{ width: `${Math.min(100, max ? reserve / max * 100 : 0)}%` }} /></div><div className="legend"><span><i className="emerald" />已调用 {used}</span><span><i className="hatch" />验证预留 {reserve}</span></div><p className="muted-text">调用计入根预算。CLI 会话的内部请求和实际费用以工具可观测能力为准。</p></section>
      {['PAUSED', 'WAITING_INPUT', 'FAILED'].includes(task.state) && <section className="card compact"><h3>恢复与继续</h3><p className="muted-text">先处理当前阻塞。调整预算或重新验证不会抹去此前的执行记录。</p><div className="recovery-actions"><button className="button secondary small" onClick={() => setEditBudget(true)}>调整任务预算</button><button className="button secondary small" disabled={!!busy} onClick={() => action('revalidate')}>重新验证当前成果</button></div></section>}
      <section className="card compact"><h3>执行边界</h3><dl className="metadata"><dt>首选运行器</dt><dd>{route?.name || '尚未配置'}</dd><dt>模型</dt><dd className="mono">{route?.model || '运行器默认'}</dd><dt>硬锁定</dt><dd>{Object.entries(task.locks || {}).length ? Object.entries(task.locks || {}).map(([k, v]) => <div key={k} className="lock-line"><LockKeyhole size={12} />{k}: {v}</div>) : '未设置'}</dd><dt>独立工作目录</dt><dd className="path-text">{task.workspace || '确认计划后创建'}</dd></dl>{task.workspace && window.workbench?.openPath && <button className="text-button" onClick={async () => { try { await window.workbench?.openPath?.(task.workspace!); } catch (e) { onNotice((e as Error).message, true); } }}>打开工作目录<ArrowRight size={14} /></button>}</section>
      <section className="card compact activity-preview"><h3>最近活动</h3>{events.length ? events.slice(-4).reverse().map(event => <div className="mini-event" key={event.id}><span /><div><p>{event.message?.slice(0, 180)}</p><small>{formatTime(event.createdAt)}</small></div></div>) : <p className="muted-text">尚无执行事件。确认计划后开始记录。</p>}<button className="text-button" onClick={() => setTab('events')}>查看完整记录<ArrowRight size={14} /></button></section>
    </aside></div>}
    {tab === 'evidence' && <div className="evidence-page"><div className="review-banner"><div className="review-icon"><ShieldCheck size={26} /></div><div><h2>独立审查</h2><p>通过独立会话和审查目录验证交付，保留原始证据。</p></div><Badge state={task.review?.status || 'PENDING'} /></div>{task.review?.output && <pre className="review-output">{task.review.output}</pre>}{task.issues?.length ? <div className="card issue-list"><h3>需要关注的问题</h3>{task.issues.map((issue, i) => <p key={i}><TriangleAlert size={15} />{typeof issue === 'string' ? issue : issue.message || issue.title || JSON.stringify(issue)}</p>)}</div> : null}<SectionHeading title="验证证据" subtitle="失败、复现和后续成功分别保留，不覆盖历史结果。" />{evidence.length ? <div className="evidence-list">{evidence.map(item => <EvidenceCard key={item.id} item={item} />)}</div> : <section className="card"><Empty icon={<ListChecks size={28} />} title="尚未产生验证证据" description="基线检查、实现后验证与独立审查将在执行过程中逐条记录。" /></section>}</div>}
    {tab === 'diff' && <section className="card diff-card"><SectionHeading title="工作目录差异" subtitle={`内容版本 ${shortHash(diff?.version || task.version)} · ${diff?.workspace || task.workspace || '尚未创建工作目录'}`} />{diffError ? <div className="form-error">{diffError}</div> : !diff ? <div className="loading-content">正在读取代码差异…</div> : diff.diff ? <div className="diff-lines">{diff.diff.split('\n').map((line, i) => <div key={i} className={line.startsWith('+++') || line.startsWith('---') || line.startsWith('diff ') || line.startsWith('@@') ? 'diff-header' : line.startsWith('+') ? 'diff-add' : line.startsWith('-') ? 'diff-remove' : ''}><span className="diff-number">{i + 1}</span><code>{line || ' '}</code></div>)}</div> : <Empty icon={<FileCode2 size={27} />} title="当前没有代码差异" description="Agent 对任务工作目录的修改会出现在这里。" />}</section>}
    {tab === 'context' && <section><SectionHeading title="本次执行使用的上下文" subtitle="查看选入的资料、来源与固定版本；项目之间保持知识隔离。" />{task.context?.length ? <div className="context-list">{task.context.map((entry, i) => <article className="card context-card" key={entry.id || i}><div className="inline-meta"><span className="chip">{entry.kind || '上下文'}</span><code>{shortHash(entry.version)}</code></div><p>{entry.content}</p><small>来源 · {entry.source || '核心记录'}</small></article>)}</div> : <section className="card"><Empty icon={<LayersIcon size={27} />} title="上下文清单待生成" description="任务启动时会固定完成约定、代码基线和有效记忆。" /></section>}</section>}
    {tab === 'events' && <section className="card events-card"><SectionHeading title="执行活动" subtitle="展示最近 500 条事件，完整历史保存在核心与交付包中。" action={<input className="small-search" value={eventFilter} onChange={e => setEventFilter(e.target.value)} placeholder="筛选记录…" />} />{events.length ? <div className="event-list">{events.filter(e => `${e.message} ${e.type}`.toLowerCase().includes(eventFilter.toLowerCase())).map(event => <div className="event-row" key={event.id}><time>{formatTime(event.createdAt)}</time><span className="event-type">{event.type}</span><pre>{event.message}</pre><small>#{event.id}</small></div>)}</div> : <Empty icon={<Activity size={28} />} title="等待第一条活动记录" description="所有执行状态变化和工具活动会按顺序保存在这里。" />}</section>}
    {editBudget && <BudgetDialog task={task} onClose={() => setEditBudget(false)} onSaved={async () => { setTask(await api<Task>(`/tasks/${task.id}`)); onNotice('预算已更新，根任务累计用量保留'); }} />}
    {reconcile && <ReconcileDialog task={task} onClose={() => setReconcile(false)} onSaved={async () => { setTask(await api<Task>(`/tasks/${task.id}`)); onNotice('人工核对已记录，请检查状态后继续任务'); }} />}
  </div>;
}

function LayersIcon(props: { size?: number }) { return <ClipboardCheck {...props} />; }
function EvidenceCard({ item }: { item: Evidence }) {
  const [open, setOpen] = useState(false);
  return <article className={`evidence-card ${open ? 'expanded' : ''}`}><button onClick={() => setOpen(!open)} className="evidence-toggle"><div className={`evidence-symbol ${item.exitCode === 0 ? 'good' : item.exitCode !== undefined ? 'bad' : ''}`}>{item.exitCode === 0 ? <Check size={18} /> : <Terminal size={18} />}</div><div><h3>{item.command || kindLabel(item.kind || item.stage || '执行记录')}</h3><p>{kindLabel(item.kind || item.stage || '验证')}<span>·</span>版本 {shortHash(item.version)}<span>·</span>{formatTime(item.createdAt)}</p></div><Badge state={item.status || (item.exitCode === 0 ? 'PASSED' : item.exitCode !== undefined ? 'FAILED' : 'PENDING')} /><ChevronDown size={16} className={open ? 'rotated' : ''} /></button>{open && <pre className="evidence-output">{item.output || '该记录未提供文本输出。'}</pre>}</article>;
}

function BudgetDialog({ task, onClose, onSaved }: { task: Task; onClose: () => void; onSaved: () => Promise<void> }) {
  const [max, setMax] = useState(task.budget?.maxCalls || 24);
  const [reserve, setReserve] = useState(task.budget?.reserveCalls || 4);
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false);
  return <Modal title="调整根任务预算" description={`此前已使用 ${task.budget?.usedCalls || 0} 次调用，累计计数不会重置。`} onClose={onClose}><form onSubmit={async e => { e.preventDefault(); setSaving(true); setError(''); try { if (reserve >= max) throw new Error('验证预留必须小于总预算'); await api(`/tasks/${task.id}/budget`, { method: 'POST', body: JSON.stringify({ maxCalls: max, reserveCalls: reserve }) }); await onSaved(); onClose(); } catch (e) { setError((e as Error).message); } finally { setSaving(false); } }}><div className="form-body"><Field label="总调用预算"><input type="number" min={Math.max(2, task.budget?.usedCalls || 0)} max={1000} value={max} onChange={e => setMax(Number(e.target.value))} /></Field><Field label="验证与审查预留"><input type="number" min={1} max={999} value={reserve} onChange={e => setReserve(Number(e.target.value))} /></Field>{error && <div className="form-error" role="alert">{error}</div>}</div><footer><button className="button secondary" type="button" onClick={onClose}>取消</button><SaveButton busy={saving}>更新预算</SaveButton></footer></form></Modal>;
}

function ReconcileDialog({ task, onClose, onSaved }: { task: Task; onClose: () => void; onSaved: () => Promise<void> }) {
  const [stopped, setStopped] = useState(false); const [checked, setChecked] = useState(false); const [note, setNote] = useState('');
  const [error, setError] = useState(''); const [saving, setSaving] = useState(false);
  return <Modal title="核对结果未知的操作" description="中断或丢失响应不能证明操作未执行。请核对实际现场后再记录。" onClose={onClose}><form onSubmit={async e => { e.preventDefault(); setSaving(true); setError(''); try { if (!stopped || !checked || !note.trim()) throw new Error('请完成两项现场核对并填写核对依据'); await api(`/tasks/${task.id}/reconcile`, { method: 'POST', body: JSON.stringify({ processesStopped: true, sideEffectsChecked: true, note }) }); await onSaved(); onClose(); } catch (e) { setError((e as Error).message); } finally { setSaving(false); } }}><div className="form-body"><div className="info-box"><TriangleAlert size={19} /><p>工作现场：{task.workspace || '请查看事件中的操作目标'}。不要仅凭界面没有进度认定进程已停止。</p></div><label className="checkbox-card"><input type="checkbox" required checked={stopped} onChange={e => setStopped(e.target.checked)} /><span><b>我已确认相关执行与子进程停止</b><small>旧执行不再写入本次任务现场。</small></span></label><label className="checkbox-card"><input type="checkbox" required checked={checked} onChange={e => setChecked(e.target.checked)} /><span><b>我已核对未确认操作的实际副作用</b><small>包括文件变更及可能已经发生的外部操作。</small></span></label><Field label="核对依据"><textarea required rows={4} value={note} onChange={e => setNote(e.target.value)} placeholder="记录检查了什么、实际结果，以及可以恢复的依据…" /></Field>{error && <div className="form-error" role="alert">{error}</div>}</div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={saving}>记录人工核对</SaveButton></footer></form></Modal>;
}
