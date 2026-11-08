import { useState } from 'react';
import { RefreshCw, Square, TriangleAlert } from 'lucide-react';
import { api, post } from './api';
import type { ProcessObservation, ProcessRecovery, ProcessSession } from './types';
import { Badge, Field, Modal } from './ui';
import './process-recovery.css';

const sessionName = (session: ProcessSession) => session.type === 'MCP_STDIO' ? 'MCP stdio 会话' : session.type === 'LEGACY_MCP' ? '历史 MCP 会话' : session.type === 'LEGACY_WORKER' ? '历史 Worker 会话' : 'Worker 会话';
const observationName: Record<string, string> = { RUNNING: '原进程仍在运行', EXITED: '原进程已退出', PID_REUSED: '原 PID 已复用', UNVERIFIABLE: '出生时间无法核验', OBSERVATION_FAILED: '进程观察失败', CURRENT_CORE: '仍是当前核心进程' };
const originName: Record<string, string> = { ACTUAL_START: '实际启动句柄', WORKER_PIPE: 'Worker 原管道', CORE_SNAPSHOT: '核心进程快照', MCP_SNAPSHOT: 'MCP 进程快照', RECOVERY_SNAPSHOT: '恢复时的进程快照', LEGACY_RECORD: '历史回执' };

export function ProcessRecoveryPanel({ recovery, onRefresh }: { recovery?: ProcessRecovery; onRefresh: () => Promise<void> }) {
  const [selected, setSelected] = useState<ProcessSession | null>(null);
  const [busy, setBusy] = useState(''), [error, setError] = useState('');
  if (!recovery) return null;
  const active = selected && (recovery.sessions.find(session => session.id === selected.id) || selected);
  const refresh = async () => { setBusy('refresh'); setError(''); try { await api('/process-recovery'); await onRefresh(); } catch (failure) { setError((failure as Error).message); } finally { setBusy(''); } };
  const stop = async (session: ProcessSession) => {
    setBusy(session.id); setError('');
    try { const result = await post<ProcessSession>(`/process-recovery/${encodeURIComponent(session.id)}/stop`); if (selected?.id === session.id) setSelected(result); await onRefresh(); }
    catch (failure) { setError((failure as Error).message); }
    finally { setBusy(''); }
  };
  return <section className="process-recovery" aria-label="旧进程核验">
    <div className="agent-run-heading"><div><h3>{recovery.pendingCount ? '旧执行需要核验' : '旧进程核验'}</h3><p className="form-note">{recovery.pendingCount ? `${recovery.pendingCount} 个会话尚待核验，完成后才允许新的本机执行。` : '当前没有待核验的旧进程会话。'}</p></div><button className="button secondary small" disabled={!!busy} onClick={refresh}><RefreshCw size={14} />重新核验进程</button></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {recovery.sessions.map(session => <article className="card process-recovery-card" key={session.id}>
      <div className="worker-process-heading"><h4>{sessionName(session)}</h4><Badge state="UNKNOWN">{session.unconfirmedProcessCount ? `${session.unconfirmedProcessCount} 个进程尚未确认停止` : '需要核对清单覆盖'}</Badge></div>
      <dl className="process-recovery-facts"><dt>原会话</dt><dd className="mono">{session.id}</dd><dt>原根 PID</dt><dd>{session.pid || '未记录'}</dd><dt>原出生时间</dt><dd className="mono">{session.processStartedAt || '未提供'}</dd>{session.workerId && <><dt>Worker 来源</dt><dd className="mono">{session.workerId}</dd></>}{session.serverId && <><dt>MCP 来源</dt><dd className="mono">{session.serverId}</dd></>}{session.taskId && <><dt>关联任务</dt><dd className="mono">{session.taskId}</dd></>}</dl>
      <p className="form-note">{session.coverage === 'LEGACY_NO_LIST' ? '旧版本未保存完整的进程清单，需补充核对未列出的相关进程。' : '清单保存已观察的进程；仍需核对未列出的相关进程。'}{session.launchReceiptMissing && ' 启动回执缺失或历史字段未提供。'}</p>
      <ProcessFacts session={session} />
      <div className="process-recovery-actions"><button className="button secondary small" disabled={!!busy || !session.unconfirmedProcessCount} onClick={() => stop(session)}><Square size={14} />请求停止原进程</button><button className="button primary small" disabled={!!busy} onClick={() => { setError(''); setSelected(session); }}>记录核对依据</button></div>
    </article>)}
    {recovery.hasMore && <p className="form-note">本页显示前 30 个待核验会话，其余会话继续阻止新增派发；完成本页核验后刷新查看。</p>}
    {active && <RecoveryDialog session={active} onClose={() => setSelected(null)} onSaved={onRefresh} />}
  </section>;
}

function ProcessFacts({ session }: { session: ProcessSession }) {
  const facts = session.processes.length ? session.processes : session.recordedProcesses;
  return <div className="process-recovery-observations">
    {!facts.length && <p className="form-note">未保存可核验的原 PID，请记录如何核对未列出的进程。</p>}
    {facts.map((process: ProcessObservation) => <div className="process-recovery-observation" key={process.id}>
      <div><b>PID {process.pid}</b><Badge state={process.observation === 'RUNNING' ? 'RUNNING' : ['EXITED', 'PID_REUSED'].includes(process.observation) ? 'COMPLETED' : 'UNKNOWN'}>{observationName[process.observation] || process.observation}</Badge></div>
      <p className="form-note">{originName[process.origin] || process.origin || '历史来源未提供'} · 原出生 <span className="mono">{process.processStartedAt || '未提供'}</span></p>
      {process.currentProcessStartedAt && process.currentProcessStartedAt !== process.processStartedAt && <p className="form-note">当前 PID 的出生 <span className="mono">{process.currentProcessStartedAt}</span></p>}
    </div>)}
    {session.processesTruncated && <p className="form-note">已记录 {session.observedProcessCount} 个进程，当前最多显示 50 条；服务端核验原清单中的全部记录。</p>}
  </div>;
}

function RecoveryDialog({ session, onClose, onSaved }: { session: ProcessSession; onClose: () => void; onSaved: () => Promise<void> }) {
  const [stopped, setStopped] = useState(false), [coverage, setCoverage] = useState(false), [note, setNote] = useState('');
  const [saving, setSaving] = useState(false), [error, setError] = useState('');
  const unresolved = session.unconfirmedProcessCount > 0 || !session.observedProcessesStopped;
  return <Modal title="记录旧进程核对依据" description="进程停止、未记录的进程覆盖与任务的外部效果分别核对。" onClose={onClose}>
    <form onSubmit={async event => {
      event.preventDefault(); setError(''); setSaving(true);
      try {
        if (unresolved) throw new Error('原进程仍在运行或无法核验，请先停止并重新核验');
        if (!stopped || !coverage || !note.trim()) throw new Error('请完成原进程与清单覆盖核对，并填写依据');
        await post(`/process-recovery/${encodeURIComponent(session.id)}/reconcile`, { processesStopped: true, coverageChecked: true, note });
        await onSaved(); onClose();
      } catch (failure) { setError((failure as Error).message); }
      finally { setSaving(false); }
    }}>
      <div className="form-body"><p className="form-note mono">原会话 {session.id}</p>
        {unresolved && <div className="info-box" role="status"><TriangleAlert size={18} /><p>仍有 {session.unconfirmedProcessCount} 个原进程尚未确认停止，暂不能提交人工核对。</p></div>}
        <ProcessFacts session={session} />
        <label className="checkbox-card"><input type="checkbox" required checked={stopped} onChange={event => setStopped(event.target.checked)} /><span><b>我已核对旧执行和已记录进程停止</b><small>提交时服务端再次检查全部已记录的原 PID 与出生时间。</small></span></label>
        <label className="checkbox-card"><input type="checkbox" required checked={coverage} onChange={event => setCoverage(event.target.checked)} /><span><b>我已核对清单未列出的相关进程</b><small>包括缺失启动回执或历史快照未覆盖的进程。</small></span></label>
        <Field label="旧进程核对依据"><textarea required rows={4} maxLength={4000} value={note} onChange={event => setNote(event.target.value)} placeholder="记录如何检查旧现场、未列出的进程，以及是否仍有相关写入…" /></Field>
        <p className="form-note">这份记录核对本机进程。文件变更和外部效果还需在结果未知的任务中另行核对。</p>
        {error && <p className="form-error" role="alert">{error}</p>}
      </div>
      <footer><button type="button" className="button secondary" onClick={onClose}>取消</button><button className="button primary" type="submit" disabled={saving || unresolved}>{saving ? '正在保存…' : '保存旧进程核对'}</button></footer>
    </form>
  </Modal>;
}
