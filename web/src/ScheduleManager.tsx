import { useEffect, useRef, useState } from 'react';
import { AlertTriangle, CalendarClock, Check, Clock3, LoaderCircle, Pause, Pencil, Play, Plus, RefreshCw, RotateCcw, ShieldCheck } from 'lucide-react';
import { ApiError, api, currentConnection, post } from './api';
import { startPagePolling } from './page-polling';
import type { Project, Route } from './types';
import { Badge, Empty, Field, formatTime, Modal, PageTitle, SaveButton, statusLabel } from './ui';
import './schedules.css';

type ScheduleTask = {
  projectId: string; routeId: string; title: string; goal: string; mode: string;
  contract: { acceptance?: string[]; commands?: string[]; [key: string]: unknown };
  budget: { maxCalls?: number; reserveCalls?: number; [key: string]: unknown };
  locks: Record<string, string>;
  includeChanges?: boolean;
  [key: string]: unknown;
};
type Schedule = { id: string; version: number; name: string; timezone: string; localTime: string; enabled: boolean; task: ScheduleTask };
type Recovery = { action: string; actor?: string; reason?: string; createdAt?: string; taskState?: string };
type Occurrence = {
  id: string; version: number; scheduleId: string; scheduleVersion: number; localDate: string; timezone: string; localTime: string;
  scheduledAt: string; state: string; message?: string; taskId?: string; taskState?: string; recoveries?: Recovery[];
};
type RecoveryAction = 'RETRY_SNAPSHOT' | 'RETRY_CURRENT_VERSION' | 'RECONCILE_EXISTING_TASK';
type Props = { projects: Project[]; routes: Route[]; connected: boolean; onNotice: (message: string, error?: boolean) => void; onTask: (id: string) => void };

const recoveryLabels: Record<string, string> = {
  RETRY_SNAPSHOT: '按触发时保存的模板重试', RETRY_CURRENT_VERSION: '使用当前计划版本重试', RECONCILE_EXISTING_TASK: '核对并关联已有任务',
};
const initialZone = () => Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC';
const lines = (value: string) => value.split('\n').map(item => item.trim()).filter(Boolean);

export function ScheduleManager(props: Props) {
  const connection = currentConnection();
  const [schedules, setSchedules] = useState<Schedule[]>([]);
  const [occurrences, setOccurrences] = useState<Occurrence[]>([]);
  const [occurrenceScheduleId, setOccurrenceScheduleId] = useState('');
  const [selectedId, setSelectedId] = useState('');
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState('');
  const [revision, setRevision] = useState(0);
  const [editorOpen, setEditorOpen] = useState(false);
  const [editing, setEditing] = useState<Schedule | undefined>();

  useEffect(() => {
    if (!props.connected) { setSchedules([]); setOccurrences([]); setLoading(false); return; }
    return startPagePolling(async signal => {
      if (signal.aborted || currentConnection() !== connection) return false;
      setLoading(true);
      try {
        const next = await api<Schedule[]>('/schedules', { signal });
        if (!Array.isArray(next)) throw new Error('计划列表响应格式无效。');
        if (signal.aborted || currentConnection() !== connection) return false;
        setSchedules(next);
        const activeId = next.some(item => item.id === selectedId) ? selectedId : next[0]?.id || '';
        if (activeId !== selectedId) {
          setSelectedId(activeId);
          setOccurrences([]);
          setOccurrenceScheduleId('');
        }
        if (activeId) {
          const history = await api<Occurrence[]>(`/schedules/${encodeURIComponent(activeId)}/occurrences`, { signal });
          if (!Array.isArray(history)) throw new Error('触发历史响应格式无效。');
          if (signal.aborted || currentConnection() !== connection) return false;
          if (activeId === selectedId || selectedId === '') { setOccurrences(history); setOccurrenceScheduleId(activeId); }
        } else { setOccurrences([]); setOccurrenceScheduleId(''); }
        setError('');
      } catch (failure) {
        if (!signal.aborted && currentConnection() === connection) setError((failure as Error).message || '每日计划读取失败。');
      } finally { if (!signal.aborted && currentConnection() === connection) setLoading(false); }
    }, 15000, { onPause: () => setLoading(false) });
  }, [props.connected, selectedId, revision, connection]);

  const selected = schedules.find(item => item.id === selectedId);
  function openEditor(schedule?: Schedule) { setEditing(schedule); setEditorOpen(true); }
  async function toggle(schedule: Schedule) {
    try {
      await api(`/schedules/${encodeURIComponent(schedule.id)}`, { method: 'PATCH', body: JSON.stringify({ expectedVersion: schedule.version, enabled: !schedule.enabled }) });
      setRevision(value => value + 1);
      props.onNotice(schedule.enabled ? '计划已暂停；已经记录的触发仍会继续处理。' : '计划已启用。');
    } catch (failure) { props.onNotice((failure as Error).message, true); }
  }

  return <div className="schedule-manager">
    <PageTitle eyebrow="PERSISTENT SCHEDULING" title="每日计划" description="按固定时区生成独立任务。暂停只停止未来触发；已记录的触发保留原模板继续处理。">
      <button className="button secondary small" disabled={!props.connected || loading} onClick={() => setRevision(value => value + 1)}><RefreshCw size={15} className={loading ? 'spin' : ''} />刷新</button>
      <button className="button primary" disabled={!props.connected} onClick={() => openEditor()}><Plus size={16} />新建计划</button>
    </PageTitle>
    {!props.connected && <section className="card schedule-read-error"><AlertTriangle size={19} /><div><b>连接本地核心后管理每日计划</b><p>计划定义和触发历史保存在本机核心的数据目录。</p></div></section>}
    {error && <section className="card schedule-read-error" role="alert"><AlertTriangle size={19} /><div><b>无法读取每日计划</b><p>{error}</p></div><button className="button secondary small" onClick={() => setRevision(value => value + 1)}>重试读取</button></section>}
    <div className="schedule-layout">
      <section className="schedule-list card" aria-label="每日计划列表">
        <header><div><h2>计划</h2><p>{schedules.length} 个计划 · 本机保存</p></div><CalendarClock size={19} /></header>
        {schedules.length ? <div className="schedule-list-items">{schedules.map(schedule => <button key={schedule.id} className={`schedule-list-item ${schedule.id === selectedId ? 'selected' : ''}`} aria-pressed={schedule.id === selectedId} onClick={() => { setOccurrences([]); setOccurrenceScheduleId(''); setSelectedId(schedule.id); }}>
          <span className={`schedule-list-dot ${schedule.enabled ? 'enabled' : ''}`} /><span className="schedule-list-copy"><b>{schedule.name}</b><small>{schedule.localTime} · {schedule.timezone}</small></span><Badge state={schedule.enabled ? 'ACTIVE' : 'DISABLED'} />
        </button>)}</div> : <Empty icon={<Clock3 size={27} />} title={loading ? '正在读取计划' : '还没有每日计划'} description="创建计划后，核心会按固定 IANA 时区为每个日期保存唯一触发记录。" />}
      </section>
      {selected ? <section className="schedule-detail">
        <article className="card schedule-summary">
          <div className="schedule-summary-title"><div><div className="eyebrow">SCHEDULE · V{selected.version}</div><h2>{selected.name}</h2><p><Clock3 size={14} />每天 {selected.localTime} · {selected.timezone}</p></div><Badge state={selected.enabled ? 'ACTIVE' : 'DISABLED'} /></div>
          <div className="schedule-summary-project"><span>任务项目</span><b>{props.projects.find(project => project.id === selected.task.projectId)?.name || '项目不可用'}</b><span>执行路由</span><b>{props.routes.find(route => route.id === selected.task.routeId)?.name || '路由不可用'}</b></div>
          <div className="schedule-template"><b>{selected.task.title}</b><p>{selected.task.goal}</p><small>{selected.task.contract?.acceptance?.length || 0} 项验收条件 · {selected.task.contract?.commands?.length || 0} 条验证命令</small></div>
          <footer><button className="button secondary small" onClick={() => openEditor(selected)}><Pencil size={14} />编辑计划</button><button className={`button ${selected.enabled ? 'secondary' : 'primary'} small`} onClick={() => void toggle(selected)}>{selected.enabled ? <><Pause size={14} />暂停计划</> : <><Play size={14} />启用计划</>}</button></footer>
        </article>
        <section className="card schedule-history" aria-label="定时触发历史">
          <header><div><h2>触发历史</h2><p>最近 100 次 · 修改计划不会改写已记录的日期和模板快照</p></div>{loading && <LoaderCircle size={16} className="spin" aria-label="正在刷新" />}</header>
          {(occurrenceScheduleId === selectedId ? occurrences : []).length ? <div className="schedule-occurrences">{(occurrenceScheduleId === selectedId ? occurrences : []).map(occurrence => <article key={occurrence.id} className={`schedule-occurrence ${occurrence.state === 'NEEDS_ATTENTION' ? 'needs-attention' : ''}`}>
            <div className="schedule-occurrence-head"><div><b>{occurrence.localDate}</b><small>{occurrence.localTime} · {occurrence.timezone} · 配置 v{occurrence.scheduleVersion}</small></div><Badge state={occurrence.state}>{statusLabel(occurrence.state)}</Badge></div>
            {occurrence.message && <p className="schedule-occurrence-message">{occurrence.message}</p>}
            {occurrence.taskId && <div className="schedule-linked-task"><span>关联任务：{occurrence.taskState ? statusLabel(occurrence.taskState) : '已创建'}</span><button className="text-button" onClick={() => props.onTask(occurrence.taskId!)}>打开任务</button></div>}
            {occurrence.state === 'NEEDS_ATTENTION' && <OccurrenceRecovery occurrence={occurrence} currentSchedule={selected} onNotice={props.onNotice} onTask={props.onTask} onRecovered={() => setRevision(value => value + 1)} />}
            {!!occurrence.recoveries?.length && <details className="schedule-recovery-history"><summary>人工处理记录（{occurrence.recoveries.length}）</summary><ol>{occurrence.recoveries.map((recovery, index) => <li key={`${recovery.createdAt}-${index}`}><b>{recoveryLabels[recovery.action] || recovery.action}</b><span>{formatTime(recovery.createdAt)} · {recovery.actor || 'LOCAL_USER'}</span>{recovery.reason && <small>{recovery.reason}</small>}</li>)}</ol></details>}
          </article>)}</div> : <Empty icon={<Check size={26} />} title="尚无触发记录" description="触发状态、错过窗口和夏令时缺口将在这里保留。" />}
        </section>
      </section> : <section className="card schedule-no-selection"><Empty icon={<Clock3 size={28} />} title={props.connected ? '选择一个计划' : '本机计划不可用'} description={props.connected ? '选择左侧计划查看触发历史和管理操作。' : '连接本地核心后可以创建、编辑和暂停计划。'} /></section>}
    </div>
    {editorOpen && <ScheduleEditor schedule={editing} projects={props.projects} routes={props.routes} onClose={() => setEditorOpen(false)} onSaved={async () => { setEditorOpen(false); setRevision(value => value + 1); props.onNotice(editing ? '每日计划已更新。' : '每日计划已创建。'); }} />}
  </div>;
}

function OccurrenceRecovery({ occurrence, currentSchedule, onNotice, onTask, onRecovered }: {
  occurrence: Occurrence; currentSchedule: Schedule; onNotice: Props['onNotice']; onTask: Props['onTask']; onRecovered: () => void;
}) {
  const [action, setAction] = useState<RecoveryAction | ''>('');
  const [reason, setReason] = useState(''); const [confirmed, setConfirmed] = useState(false); const [busy, setBusy] = useState(false);
  const pendingRequest = useRef<{ action: RecoveryAction; id: string } | null>(null);
  const hasDifferentTemplate = currentSchedule.version !== occurrence.scheduleVersion;
  const linked = Boolean(occurrence.taskId);

  async function submit(event: React.FormEvent) {
    event.preventDefault();
    if (!action || !confirmed || busy) return;
    if (!pendingRequest.current || pendingRequest.current.action !== action) pendingRequest.current = { action, id: crypto.randomUUID() };
    const requestId = pendingRequest.current.id;
    setBusy(true);
    try {
      const result = await post<Occurrence>(`/schedules/${encodeURIComponent(occurrence.scheduleId)}/occurrences/${encodeURIComponent(occurrence.id)}/recover`, {
        expectedVersion: occurrence.version, action, requestId, ...(reason.trim() ? { reason: reason.trim() } : {}),
      });
      pendingRequest.current = null;
      if (result.state === 'TRIGGERED') onNotice(action === 'RECONCILE_EXISTING_TASK' ? '现有任务已关联；没有重新派发。' : '已恢复并将本次任务排入执行。');
      else onNotice('已执行一次恢复尝试；该触发仍需要处理。', true);
      setAction(''); setReason(''); setConfirmed(false); onRecovered();
    } catch (failure) {
      // Preserve the id for transport/5xx failures because the server may already have committed the audit.
      if (failure instanceof ApiError && failure.status >= 400 && failure.status < 500) pendingRequest.current = null;
      onNotice((failure as Error).message, true); onRecovered();
    }
    finally { setBusy(false); }
  }

  return <div className="schedule-recovery">
    <div className="schedule-recovery-alert"><AlertTriangle size={16} /><p>该触发需要处理。查看错误和关联任务后，再选择一个单次操作。</p></div>
    {linked ? occurrence.taskState === 'DRAFT' ? <p className="schedule-recovery-guidance">任务已创建但仍待确认。请先打开任务详情处理；本页不会替你确认或重新创建。</p> : <p className="schedule-recovery-guidance">任务已存在；关联操作只修复触发记录，不会再次派发任务。</p> : <p className="schedule-recovery-guidance">该触发目前没有关联任务。重试会立即创建并排入一个任务。</p>}
    <div className="schedule-recovery-actions">
      {!linked && <><button className="text-button" onClick={() => { setAction('RETRY_SNAPSHOT'); setConfirmed(false); }}><RotateCcw size={14} />按原模板重试</button>{hasDifferentTemplate && <button className="text-button" onClick={() => { setAction('RETRY_CURRENT_VERSION'); setConfirmed(false); }}><RotateCcw size={14} />使用当前计划版本重试</button>}</>}
      {linked && occurrence.taskState === 'DRAFT' && <button className="text-button" onClick={() => onTask(occurrence.taskId!)}>打开待确认任务</button>}
      {linked && occurrence.taskState !== 'DRAFT' && <button className="text-button" onClick={() => { setAction('RECONCILE_EXISTING_TASK'); setConfirmed(false); }}><ShieldCheck size={14} />核对并关联已有任务</button>}
    </div>
    {action && <form className="schedule-recovery-form" onSubmit={submit}>
      <b>{recoveryLabels[action]}</b>
      {action !== 'RECONCILE_EXISTING_TASK' && <Field label="处理原因（可选）"><textarea rows={2} maxLength={500} value={reason} onChange={event => setReason(event.target.value)} placeholder="例如：已恢复失效的工作区引用" /></Field>}
      {action === 'RECONCILE_EXISTING_TASK' && <p>将核对已有任务并把触发记录标为已关联，不触碰任务状态或执行队列。</p>}
      {action === 'RETRY_CURRENT_VERSION' && <p>触发快照将更新为计划 v{currentSchedule.version}；旧模板会保存在本次审计记录。</p>}
      <label className="checkbox-row"><input type="checkbox" checked={confirmed} onChange={event => setConfirmed(event.target.checked)} />{action === 'RECONCILE_EXISTING_TASK' ? '我已核对该任务状态' : '我确认只重试这一次；任务会立即排入执行'}</label>
      <div><button type="button" className="button secondary small" disabled={busy} onClick={() => { setAction(''); setConfirmed(false); }}>取消</button><button className="button primary small" disabled={busy || !confirmed}>{busy ? <LoaderCircle size={14} className="spin" /> : <Check size={14} />}{action === 'RECONCILE_EXISTING_TASK' ? '核对并关联' : '确认单次重试'}</button></div>
    </form>}
  </div>;
}

function ScheduleEditor({ schedule, projects, routes, onClose, onSaved }: {
  schedule?: Schedule; projects: Project[]; routes: Route[]; onClose: () => void; onSaved: () => Promise<void>;
}) {
  const original = schedule?.task;
  const [name, setName] = useState(schedule?.name || '每日研发任务');
  const [timezone, setTimezone] = useState(schedule?.timezone || initialZone());
  const [localTime, setLocalTime] = useState(schedule?.localTime || '09:00');
  const [enabled, setEnabled] = useState(schedule?.enabled ?? true);
  const [projectId, setProjectId] = useState(original?.projectId || projects[0]?.id || '');
  const [routeId, setRouteId] = useState(original?.routeId || routes.find(route => route.enabled !== false)?.id || routes[0]?.id || '');
  const [title, setTitle] = useState(original?.title || '每日研发任务');
  const [goal, setGoal] = useState(original?.goal || '检查当前项目进展，完成计划内的维护任务，并提交验证结果。');
  const [mode, setMode] = useState(original?.mode || 'LEAD');
  const [acceptance, setAcceptance] = useState(original?.contract?.acceptance?.join('\n') || '关键变化已检查并记录\n所有指定验证均通过');
  const [commands, setCommands] = useState(original?.contract?.commands?.join('\n') || 'npm test');
  const [maxCalls, setMaxCalls] = useState(original?.budget?.maxCalls || 24);
  const [reserveCalls, setReserveCalls] = useState(original?.budget?.reserveCalls || 6);
  const [includeChanges, setIncludeChanges] = useState(Boolean(original?.includeChanges));
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const availableRoutes = routes.filter(route => route.enabled !== false || route.id === routeId);

  async function save(event: React.FormEvent) {
    event.preventDefault(); setBusy(true); setError('');
    try {
      if (!projects.some(project => project.id === projectId)) throw new Error('请选择一个有效项目。');
      if (!routes.some(route => route.id === routeId)) throw new Error('请选择一个有效执行路由。');
      const task: ScheduleTask = {
        ...(original || {}), projectId, routeId, title: title.trim(), goal: goal.trim(), mode,
        contract: { ...(original?.contract || {}), acceptance: lines(acceptance), commands: lines(commands) },
        budget: { ...(original?.budget || {}), maxCalls: Number(maxCalls), reserveCalls: Number(reserveCalls) },
        locks: original?.locks || {}, includeChanges,
      };
      const payload = { name: name.trim(), timezone: timezone.trim(), localTime, enabled, task, ...(schedule ? { expectedVersion: schedule.version } : {}) };
      await api(schedule ? `/schedules/${encodeURIComponent(schedule.id)}` : '/schedules', { method: schedule ? 'PATCH' : 'POST', body: JSON.stringify(payload) });
      await onSaved();
    } catch (failure) { setError((failure as Error).message || '每日计划保存失败。'); }
    finally { setBusy(false); }
  }

  return <Modal title={schedule ? '编辑每日计划' : '新建每日计划'} description="保存后由本机核心按计划创建独立任务；任务会依照这份计划自动排入执行。" onClose={onClose} wide scrollable>
    <form onSubmit={save}>
      <div className="form-body schedule-editor-body">
        <div className="form-grid"><Field label="计划名称"><input required maxLength={160} value={name} onChange={event => setName(event.target.value)} /></Field><Field label="本地触发时间"><input required type="time" step={60} value={localTime} onChange={event => setLocalTime(event.target.value)} /></Field></div>
        <div className="form-grid"><Field label="IANA 时区" hint="使用地区时区名称，例如 Asia/Shanghai；夏令时规则按该时区计算。"><input required maxLength={100} value={timezone} onChange={event => setTimezone(event.target.value)} placeholder="Asia/Shanghai" /></Field><label className="checkbox-row schedule-enabled"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} />保存后启用计划</label></div>
        <div className="form-grid"><Field label="任务项目"><select required value={projectId} onChange={event => setProjectId(event.target.value)}>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></Field><Field label="执行路由"><select required value={routeId} onChange={event => setRouteId(event.target.value)}>{availableRoutes.map(route => <option key={route.id} value={route.id}>{route.name} · {route.model || route.engine}</option>)}</select></Field></div>
        <Field label="任务标题"><input required maxLength={180} value={title} onChange={event => setTitle(event.target.value)} /></Field>
        <Field label="任务目标"><textarea required rows={3} maxLength={40000} value={goal} onChange={event => setGoal(event.target.value)} /></Field>
        <div className="form-grid"><Field label="协作模式"><select value={mode} onChange={event => setMode(event.target.value)}><option value="LEAD">主副协作</option><option value="ROUNDTABLE">圆桌讨论</option><option value="PIPELINE">研发流水线</option><option value="AUTO">自动选择</option></select></Field><label className="checkbox-row schedule-enabled"><input type="checkbox" checked={includeChanges} onChange={event => setIncludeChanges(event.target.checked)} />将工作区现有改动纳入任务</label></div>
        <Field label="完成验收条件" hint="每行一项。至少填写一项。"><textarea required rows={3} value={acceptance} onChange={event => setAcceptance(event.target.value)} /></Field>
        <Field label="验证命令" hint="每行一条。至少填写一条。"><textarea required rows={3} className="mono" value={commands} onChange={event => setCommands(event.target.value)} /></Field>
        <div className="form-grid"><Field label="总模型调用预算"><input type="number" min={2} max={1000} value={maxCalls} onChange={event => setMaxCalls(Number(event.target.value))} /></Field><Field label="验证预留调用"><input type="number" min={1} max={999} value={reserveCalls} onChange={event => setReserveCalls(Number(event.target.value))} /></Field></div>
        {error && <p role="alert" className="form-error">{error}</p>}
      </div>
      <footer><button type="button" className="button secondary" onClick={onClose} disabled={busy}>取消</button><SaveButton busy={busy} disabled={!projects.length || !routes.length}>{schedule ? '保存计划版本' : '创建每日计划'}</SaveButton></footer>
    </form>
  </Modal>;
}
