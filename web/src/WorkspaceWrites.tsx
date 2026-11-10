import { useEffect, useState } from 'react';
import { api } from './api';
import type { WorkspaceWriteLease } from './types';
import { Badge, Field, formatTime } from './ui';
import './workspace-writes.css';

type WritePage = { leases: WorkspaceWriteLease[]; cursor: number; hasMore: boolean };

export function WorkspaceWrites({ taskId }: { taskId: string }) {
  const [page, setPage] = useState<WritePage | null>(null), [state, setState] = useState(''), [before, setBefore] = useState(0), [error, setError] = useState('');
  useEffect(() => {
    let active = true, timer: ReturnType<typeof setTimeout>; const controller = new AbortController(); setPage(null); setError('');
    async function poll() {
      try {
        const next = await api<WritePage>(`/tasks/${taskId}/workspace-write-leases?limit=30&before=${before}&state=${encodeURIComponent(state)}`, { signal: controller.signal });
        if (active) { setPage(next); setError(''); }
      } catch (error) { if (active) setError((error as Error).message); }
      if (active) timer = setTimeout(poll, 3000);
    }
    void poll(); return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [taskId, state, before]);
  return <section className="workspace-writes worker-activity" aria-label="现场写入保护历史">
    <div className="agent-run-heading"><div><h2>现场写入保护</h2><p className="form-note">原 Worker 从准备到根执行收尾持有保护。失联后须核对原进程及效果，才能再次写入。</p></div>
      <Field label="写入保护状态"><select value={state} onChange={event => { setState(event.target.value); setBefore(0); }}><option value="">全部保护记录</option><option value="PREPARED">等待取得保护</option><option value="HELD">原根持有保护</option><option value="RELEASED">保护已释放</option><option value="UNKNOWN">原结果未知</option></select></Field></div>
    {error && <p role="alert" className="form-error">{error}</p>}{!page && !error && <p className="muted-text">正在读取原根写入归属…</p>}{page && !page.leases.length && <p className="muted-text">尚无匹配的写入保护记录。</p>}
    <div className="worker-lease-list">{page?.leases.map(lease => <article className="card worker-lease" key={lease.id}>
      <header><div><h3>第 {lease.attempt} 次根执行</h3><p>{lease.strategy} · 授权 v{lease.authorizationVersion} · 启动选择 v{lease.workspaceSelectionVersion}</p></div>
        <Badge state={lease.state === 'HELD' ? 'RUNNING' : lease.state === 'RELEASED' ? 'COMPLETED' : lease.state}>{lease.state === 'UNKNOWN' ? lease.protectionReleased ? '未知记录已核对' : '未知结果待核对' : lease.state === 'HELD' ? '原根持有保护' : lease.state === 'RELEASED' ? '保护已释放' : '等待取得保护'}</Badge></header>
      <dl><dt>实际写入目录</dt><dd className="mono">{lease.scopePath}</dd><dt>原根 / 动作</dt><dd className="mono">{lease.taskExecutionId}<br />{lease.dispatchActionId}</dd><dt>写入归属编号</dt><dd className="mono">{lease.id}</dd>
        <dt>原 Worker</dt><dd className="mono">{lease.workerId} · 代次 {lease.workerEpoch}</dd><dt>原执行进程</dt><dd>PID {lease.executorPid} · {formatTime(lease.processStartedAt)}</dd><dt>原核心进程</dt><dd>PID {lease.ownerCorePid} · {formatTime(lease.ownerCoreStartedAt)}</dd>
        <dt>保护事实</dt><dd>{lease.protectionReleased ? '原保护已释放' : '原归属继续保留，尚无释放回执'}</dd><dt>准备 / 取得时间</dt><dd>{formatTime(lease.createdAt)} / {lease.grantedAt ? formatTime(lease.grantedAt) : '尚无成功回执'}</dd>
        {lease.releasedAt && <><dt>释放时间</dt><dd>{formatTime(lease.releasedAt)}</dd></>}
      </dl>{lease.reason && <p className="form-note">{lease.reason}</p>}
      <details><summary>查看原回执与核对依据</summary>{lease.grant ? <dl><dt>原锁路径</dt><dd className="mono">{lease.grant.lockPath}</dd><dt>原文件身份</dt><dd className="mono">{lease.grant.fileKey}</dd></dl> : <p className="form-note">尚无实际写入锁的成功回执。</p>}
        {lease.release && <p className="form-note">释放核验：PID {lease.release.receiptExecutorPid} · {formatTime(lease.release.receiptProcessStartedAt)} · {formatTime(lease.release.observedAt)}</p>}
        {lease.reconciliation && <p className="form-note">{formatTime(lease.reconciliation.createdAt)} · {lease.reconciliation.note}</p>}
      </details>
    </article>)}</div>
    <div className="agent-run-pagination">{before > 0 && <button className="button secondary small" onClick={() => setBefore(0)}>返回最新写入记录</button>}{page?.hasMore && <button className="button secondary small" onClick={() => setBefore(page.cursor)}>加载更早写入记录</button>}</div>
  </section>;
}
