import { useEffect, useState } from 'react';
import { api } from './api';
import type { ModelRequest } from './types';
import { Badge, formatTime } from './ui';

type RequestPage = { requests: ModelRequest[]; cursor: number; hasMore: boolean };
const token = (value: number | string | undefined) => typeof value === 'number' ? value.toLocaleString() : '未知';
const requestLabels: Record<string, string> = { DISPATCHING: '派发中', SUCCEEDED: '已收到结果', FAILED: '请求失败', UNKNOWN: '结果待核对', CANCELLED: '本地已取消' };

export function ModelRequests({ taskId, runId = '' }: { taskId: string; runId?: string }) {
  const [page, setPage] = useState<RequestPage | null>(null);
  const [state, setState] = useState(''); const [before, setBefore] = useState(0);
  const [search, setSearch] = useState(''); const [query, setQuery] = useState(''); const [error, setError] = useState('');
  useEffect(() => {
    let active = true; let timer: ReturnType<typeof setTimeout>;
    const controller = new AbortController(); setPage(null); setError('');
    async function refresh() {
      try {
        const next = await api<RequestPage>(`/tasks/${taskId}/model-requests?runId=${encodeURIComponent(runId)}&before=${before}&limit=30&state=${state}&q=${encodeURIComponent(query)}`, { signal: controller.signal });
        if (active) { setPage(next); setError(''); }
      } catch (e) { if (active) setError((e as Error).message); }
      if (active) timer = setTimeout(refresh, 3000);
    }
    void refresh();
    return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [taskId, runId, before, state, query]);
  return <section className="model-requests" aria-label="模型请求追踪">
    <div className="agent-run-heading"><div><h2>模型请求追踪</h2><p className="form-note">每轮 API 请求或 CLI 会话分别记录；未知用量不按零计算，失败不会自动重试。</p></div></div>
    <form className="request-filters" onSubmit={event => { event.preventDefault(); setQuery(search.trim()); setBefore(0); }}>
      <label>请求状态<select value={state} onChange={event => { setState(event.target.value); setBefore(0); }}><option value="">全部状态</option>{Object.entries(requestLabels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></label>
      <input aria-label="检索请求记录" maxLength={200} value={search} onChange={event => setSearch(event.target.value)} placeholder="模型、请求编号、失败类型或输出片段" /><button className="button secondary small" type="submit">检索</button>
    </form>
    {error && <p role="alert" className="form-error">{error}</p>}
    {!page && !error && <p className="muted-text">正在读取请求记录…</p>}
    {page && !page.requests.length && <p className="muted-text">尚无匹配的模型请求。旧版本的内部请求不会推测补造。</p>}
    <div className="agent-run-list">{page?.requests.map(request => <article key={request.id} className="card agent-run request-card">
      <header><div><h3>{request.stage} · 第 {request.sequence} 次{request.unit === 'CLI_SESSION' ? ' CLI 会话' : ' API 请求'}</h3><p>{request.engine} · {request.model} · {formatTime(request.createdAt)}</p></div><Badge state={request.state}>{requestLabels[request.state]}</Badge></header>
      <dl><dt>耗时</dt><dd>{request.elapsedMs === undefined ? '等待回执' : `${(request.elapsedMs / 1000).toFixed(2)} 秒`}{request.httpStatus !== undefined && ` · HTTP ${request.httpStatus}`}</dd>
        <dt>用量</dt><dd>输入 {token(request.usage?.inputTokens)} · 输出 {token(request.usage?.outputTokens)} · 缓存读取 {token(request.usage?.cachedInputTokens)} · 推理 {token(request.usage?.reasoningTokens)}<br />费用未知{request.unit === 'CLI_SESSION' && ' · CLI 内部模型请求数未知'}</dd>
        <dt>输入摘要</dt><dd>{request.inputBytes === undefined ? '运行器未提供' : `${request.inputBytes.toLocaleString()} 字节 · ${request.messageCount ?? '未知'} 条消息 · ${request.toolCount ?? '未知'} 项工具`}<br /><code>{request.inputDigest || '摘要未提供'}</code></dd>
        <dt>来源</dt><dd><code>RUN {request.agentRunId}</code><br /><code>REQ {request.id}</code></dd>
        {request.taskExecutionId && <><dt>任务尝试</dt><dd><code>{request.taskExecutionId}</code><br />派发 <code>{request.dispatchActionId}</code></dd></>}
        {(request.providerRequestId || request.responseId) && <><dt>供应商</dt><dd>请求 {request.providerRequestId || '未提供'}<br />响应 {request.responseId || '未提供'}{request.responseModel && ` · ${request.responseModel}`}{request.finishReason && ` · ${request.finishReason}`}</dd></>}
      </dl>
      {request.errorType && <p className="agent-run-reason"><b>{request.errorType}</b> · {request.reason || '运行未取得成功回执'}</p>}
      {request.remoteOutcome === 'UNKNOWN' && <p className="form-note">远端执行和费用仍未知，本地停止等待不能证明远端停止。</p>}
      {request.inputPreview && <details><summary>查看脱敏输入片段{request.inputTruncated ? '（已截断）' : ''}</summary><pre className="request-output">{request.inputPreview}</pre></details>}
      {request.outputPreview && <details><summary>查看脱敏输出片段{request.outputTruncated ? '（已截断）' : ''}</summary><pre className="request-output">{request.outputPreview}</pre></details>}
    </article>)}</div>
    <div className="agent-run-pagination">{before > 0 && <button className="button secondary small" onClick={() => setBefore(0)}>返回最新请求</button>}{page?.hasMore && <button className="button secondary small" onClick={() => setBefore(page.cursor)}>加载更早请求</button>}</div>
  </section>;
}
