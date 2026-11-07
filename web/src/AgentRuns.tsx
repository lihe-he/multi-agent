import { useEffect, useState } from 'react';
import { api } from './api';
import type { AgentRun } from './types';
import { Badge, formatTime, shortHash } from './ui';

type RunPage = { runs: AgentRun[]; cursor: number; hasMore: boolean };

export function AgentRuns({ endpoint, onTask, onEvidence }: { endpoint: string; onTask?: (taskId: string) => void; onEvidence?: (evidenceId: string) => void }) {
  const [page, setPage] = useState<RunPage | null>(null);
  const [state, setState] = useState(''); const [error, setError] = useState(''); const [before, setBefore] = useState(0);
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout>;
    const controller = new AbortController(); setPage(null); setError('');
    async function refresh() {
      try {
        const next = await api<RunPage>(`${endpoint}?limit=30&before=${before}&state=${encodeURIComponent(state)}`, { signal: controller.signal });
        if (active) { setPage(next); setError(''); }
      } catch (e) { if (active) setError((e as Error).message); }
      if (active) timer = setTimeout(refresh, 3000);
    }
    void refresh();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [endpoint, state, before]);
  return <section className="agent-runs" aria-label="Agent 运行记录">
    <header className="agent-run-heading"><div><h2>Agent 运行记录</h2><p className="form-note">每次执行独立记录；运行成功与任务审查、人工验收分别保存。</p></div><label>运行状态<select value={state} onChange={event => { setState(event.target.value); setBefore(0); }}>
      <option value="">全部状态</option>{[['WAITING_RESOURCE', '等待资源'], ['WAITING_TOOL', '等待工具'], ['RUNNING', '执行中'], ['SUCCEEDED', '执行成功'], ['FAILED', '失败'], ['UNKNOWN', '结果待核对'], ['PAUSED', '已暂停'], ['CANCELLED', '已取消'], ['WAITING_INPUT', '需要处理'], ['BUDGET_EXHAUSTED', '预算不足']].map(([value, label]) => <option key={value} value={value}>{label}</option>)}
    </select></label></header>
    {error && <div role="alert" className="form-error">{error}</div>}
    {!page && !error && <p className="muted-text">正在读取运行记录…</p>}
    {page && !page.runs.length && <p className="muted-text">尚无匹配的 Agent 运行记录。旧版本历史不会推测补造。</p>}
    <div className="agent-run-list">{page?.runs.map(run => <article className="card agent-run" key={run.id}>
      <header><div><h3>{run.title || run.stage}</h3><p>{run.role} · 第 {run.attempt} 次 · {run.readOnly ? '只读' : '允许范围内修改'}</p></div><Badge state={run.state} /></header>
      <dl><dt>运行器</dt><dd>{run.routeName || '已固定路由'} · {run.engine} · {run.model || '默认模型'}</dd><dt>调用</dt><dd>{run.calls} {run.callUnit === 'CLI_SESSION' ? '次 CLI 会话（内部请求未知）' : '次 API 请求'} · {run.elapsedMs === undefined ? '尚未结束' : `${(run.elapsedMs / 1000).toFixed(1)} 秒`}</dd><dt>时间</dt><dd>{formatTime(run.createdAt)}{run.endedAt ? ` → ${formatTime(run.endedAt)}` : ''}</dd><dt>版本</dt><dd><code>{shortHash(run.inputVersion)} → {shortHash(run.resultVersion)}</code></dd></dl>
      {run.reason && <p className="agent-run-reason">{run.reason}</p>}
      <footer><code title={run.id}>RUN {run.id.slice(0, 8)}</code>{onTask && <button className="text-button" onClick={() => onTask(run.taskId)}>查看来源任务</button>}{run.evidenceId && onEvidence && <button className="text-button" onClick={() => onEvidence(run.evidenceId!)}>查看关联证据</button>}</footer>
    </article>)}</div>
    <div className="agent-run-pagination">{before > 0 && <button className="button secondary small" onClick={() => setBefore(0)}>返回最新运行</button>}{page?.hasMore && <button className="button secondary small" onClick={() => setBefore(page.cursor)}>加载更早运行</button>}</div>
  </section>;
}
