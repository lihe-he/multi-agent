import { useEffect, useState } from 'react';
import { api } from './api';
import type { TaskExecution } from './types';
import { Badge, formatTime, statusLabel } from './ui';

type ExecutionPage = { executions: TaskExecution[]; cursor: number; hasMore: boolean };
const states: Record<string, string> = { QUEUED: '等待领取', RUNNING: '执行中', SUCCEEDED: '执行已结束', WAITING_INPUT: '需要处理', FAILED: '执行失败', UNKNOWN: '结果待核对', PAUSED: '已暂停', CANCELLED: '已取消' };
const actions: Record<string, string> = { PENDING: '尚未领取', CLAIMED: '已经领取', SETTLED: '已经收尾', WITHDRAWN: '已经撤回', UNKNOWN: '领取后结果待核对' };

export function TaskExecutions({ taskId }: { taskId: string }) {
  const [page, setPage] = useState<ExecutionPage | null>(null);
  const [state, setState] = useState(''); const [before, setBefore] = useState(0); const [error, setError] = useState('');
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout>;
    const controller = new AbortController(); setPage(null); setError('');
    async function refresh() {
      try {
        const next = await api<ExecutionPage>(`/tasks/${taskId}/task-executions?limit=30&before=${before}&state=${encodeURIComponent(state)}`, { signal: controller.signal });
        if (active) { setPage(next); setError(''); }
      } catch (exception) { if (active) setError((exception as Error).message); }
      if (active) timer = setTimeout(refresh, 2500);
    }
    void refresh();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [taskId, before, state]);

  return <section className="task-executions" aria-label="任务派发历史">
    <header className="agent-run-heading"><div><h2>任务执行尝试</h2><p className="form-note">每次确认与继续分别留存。未启动任务可保留原编号排队，已领取的未知结果需要核对。</p></div>
      <label>执行尝试状态<select value={state} onChange={event => { setState(event.target.value); setBefore(0); }}><option value="">全部尝试</option>{Object.entries(states).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
    </header>
    {error && <p role="alert" className="form-error">{error}</p>}
    {!page && !error && <p className="muted-text">正在读取任务派发历史…</p>}
    {page && !page.executions.length && <p className="muted-text">尚无匹配的执行尝试。旧版本任务保留原活动记录。</p>}
    <div className="task-execution-list">{page?.executions.map(execution => {
      const action = execution.dispatchAction;
      return <article className="card task-execution" key={execution.id}>
        <header><div><h3>第 {execution.attempt} 次执行</h3><p>{execution.trigger === 'USER_APPROVAL' ? '确认计划' : '手动继续'} · {formatTime(execution.createdAt)}</p></div><Badge state={execution.state}>{states[execution.state] || execution.state}</Badge></header>
        <dl><dt>执行尝试</dt><dd className="mono">{execution.id}</dd><dt>派发动作</dt><dd className="mono">{action.id}</dd>
          {execution.workspaceWriteLeaseId && <><dt>现场写入保护</dt><dd className="mono">{execution.workspaceWriteLeaseId}</dd></>}
          <dt>领取情况</dt><dd>{actions[action.state] || action.state} · 领取 {action.dispatchCount} 次</dd>
          <dt>固定约定</dt><dd>完成约定 v{execution.contractVersion} · 执行授权 v{execution.authorizationVersion}</dd>
          {action.claimedAt && <><dt>领取时间</dt><dd>{formatTime(action.claimedAt)}</dd><dt>领取核心</dt><dd>PID {action.ownerCorePid ?? '未提供'}<br /><code>{action.ownerCoreSessionId}</code><br />出生 {action.ownerCoreStartedAt || '未提供'}</dd></>}
          <dt>根调用</dt><dd>本次起点 {execution.budgetAtStart?.usedCalls ?? execution.budgetAtEnqueue?.usedCalls ?? '未知'} 次{execution.budgetAtEnd && ` · 结束时累计 ${execution.budgetAtEnd.usedCalls ?? '未知'} 次`}</dd>
          {execution.endedAt && <><dt>结束时间</dt><dd>{formatTime(execution.endedAt)}</dd><dt>当时任务</dt><dd>{statusLabel(execution.taskStateAtEnd)}{execution.taskStateAtEnd === 'AWAITING_ACCEPTANCE' && ' · 等待人工验收'}</dd></>}
        </dl>
        {action.waitReason && <p className="form-note" role="status">等待原因：{action.waitReason}</p>}
        {execution.reason && <p className="task-execution-reason">{execution.reason}</p>}
        {execution.reconciliation && <div className="task-execution-reconciliation"><b>已记录人工核对</b><p>{execution.reconciliation.note}</p><small>{formatTime(execution.reconciliation.createdAt)} · 原结果与新尝试分别留存</small></div>}
      </article>;
    })}</div>
    <div className="agent-run-pagination">{before > 0 && <button className="button secondary small" onClick={() => setBefore(0)}>返回最新尝试</button>}{page?.hasMore && <button className="button secondary small" onClick={() => setBefore(page.cursor)}>加载更早尝试</button>}</div>
  </section>;
}
