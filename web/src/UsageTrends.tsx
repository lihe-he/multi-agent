import { useEffect, useRef, useState } from 'react';
import { ChartNoAxesCombined, ChevronDown, CircleHelp, Globe2, Info, LoaderCircle, RefreshCw, Terminal, TriangleAlert } from 'lucide-react';
import { api, currentConnection } from './api';
import { startPagePolling } from './page-polling';
import type { Connection, Session, Task } from './types';
import type { TokenField, UsageMetric, UsageState, UsageTrendsResponse, UsageUnit, UsageUnitName, UsageWindow } from './usage-types';
import { Empty, Field, PageTitle } from './ui';
import './usage-trends.css';

type Props = { projectId: string; tasks: Task[]; connected: boolean };
type SessionPage = { sessions: Session[]; cursor: number; hasMore: boolean };
type ChartMetric = 'recordCount' | 'inputTokens' | 'outputTokens';
const units: UsageUnitName[] = ['API_REQUEST', 'CLI_SESSION', 'UNKNOWN'];
const fields: { key: TokenField; label: string; detail?: string }[] = [
  { key: 'inputTokens', label: '输入 tokens' }, { key: 'outputTokens', label: '输出 tokens' },
  { key: 'cachedInputTokens', label: '缓存输入', detail: '已含在输入内' }, { key: 'reasoningTokens', label: '推理输出', detail: '已含在输出内' },
];
const unitLabels: Record<UsageUnitName, { title: string; count: string; description: string }> = {
  API_REQUEST: { title: 'API 请求', count: 'API 请求数', description: '逐次 API 请求的持久记录' },
  CLI_SESSION: { title: 'CLI 会话', count: 'CLI 外层会话数', description: '每条记录代表一次外层 CLI 会话' },
  UNKNOWN: { title: '未归类记录', count: '未归类记录数', description: '历史记录未提供可识别的计量单位' },
};
const stateLabels: Record<UsageState, string> = { DISPATCHING: '派发中', SUCCEEDED: '成功', FAILED: '失败', CANCELLED: '已取消', UNKNOWN: '状态未知' };
const number = (value: number) => new Intl.NumberFormat('zh-CN').format(value);
const errorMessage = (error: unknown) => error instanceof Error ? error.message : '读取未完成，请重试。';

// Connection identity also changes when only the token changes at the same address.
// Replace the child scope to clear filters and paged options before it commits.
export function UsageTrends(props: Props) {
  const connection = currentConnection();
  const [scope, setScope] = useState({ projectId: props.projectId, connection, version: 0 });
  if (scope.projectId !== props.projectId || scope.connection !== connection) {
    setScope({ projectId: props.projectId, connection, version: scope.version + 1 });
  }
  return <UsageTrendsScope key={scope.version} {...props} connection={connection} />;
}

function UsageTrendsScope({ projectId, tasks, connected, connection }: Props & { connection: Connection }) {
  const [days, setDays] = useState<UsageWindow>(7);
  const [sessionId, setSessionId] = useState(''); const [taskId, setTaskId] = useState('');
  const [unit, setUnit] = useState<UsageUnitName>('API_REQUEST'); const [chartMetric, setChartMetric] = useState<ChartMetric>('recordCount');
  const [data, setData] = useState<UsageTrendsResponse | null>(null);
  const [error, setError] = useState(''); const [reading, setReading] = useState(false); const [revision, setRevision] = useState(0);
  const [sessionPage, setSessionPage] = useState<SessionPage | null>(null);
  const [sessionError, setSessionError] = useState(''); const [sessionsReading, setSessionsReading] = useState(false);
  const [moreReading, setMoreReading] = useState(false); const [sessionRevision, setSessionRevision] = useState(0);
  const filterGeneration = useRef(0), sessionGeneration = useRef(0);
  const moreRequest = useRef<AbortController | null>(null);
  const base = `/projects/${encodeURIComponent(projectId)}`;
  const sessionTasks = sessionId ? tasks.filter(task => task.projectId === projectId && task.sessionId === sessionId) : [];

  function invalidate() { ++filterGeneration.current; setData(null); setError(''); setReading(connected); }
  function chooseSession(value: string) { invalidate(); setSessionId(value); setTaskId(''); }
  function chooseTask(value: string) { invalidate(); setTaskId(value); }
  useEffect(() => {
    if (taskId && !sessionTasks.some(task => task.id === taskId)) { ++filterGeneration.current; setData(null); setTaskId(''); }
  }, [taskId, sessionId, tasks, projectId]);

  useEffect(() => {
    const generation = ++filterGeneration.current;
    if (!connected) { setReading(false); return; }
    const valid = (signal: AbortSignal) => !signal.aborted && generation === filterGeneration.current && currentConnection() === connection;
    return startPagePolling(async signal => {
      if (!valid(signal)) return false;
      setReading(true);
      try {
        const query = new URLSearchParams({ days: String(days), sessionId, taskId });
        const next = await api<UsageTrendsResponse>(`${base}/usage-trends?${query}`, { signal });
        if (!valid(signal)) return;
        if (next.schemaVersion !== 1 || next.source !== 'MODEL_REQUEST' || next.days !== days || next.timeZone !== 'UTC' || next.bucket !== 'DAY'
          || next.scope.projectId !== projectId || next.scope.sessionId !== sessionId || next.scope.taskId !== taskId || next.buckets.length !== days) {
          throw new Error('用量响应与当前筛选不一致，请重新读取。');
        }
        setData(next); setError('');
      } catch (failure) { if (valid(signal)) setError(errorMessage(failure)); }
      finally { if (valid(signal)) setReading(false); }
    }, 5000, { onPause: () => setReading(false) });
  }, [base, projectId, connection, connected, days, sessionId, taskId, revision]);

  useEffect(() => {
    const generation = ++sessionGeneration.current;
    moreRequest.current?.abort(); setMoreReading(false);
    if (!connected) { setSessionsReading(false); return; }
    const valid = (signal: AbortSignal) => !signal.aborted && generation === sessionGeneration.current && currentConnection() === connection;
    const stop = startPagePolling(async signal => {
      if (!valid(signal)) return false;
      setSessionsReading(true);
      try {
        const next = await api<SessionPage>(`${base}/sessions?openOnly=false&limit=50&before=0`, { signal });
        if (!valid(signal)) return;
        setSessionPage(previous => previous && previous.sessions.length ? { ...previous, sessions: mergeSessions(next.sessions, previous.sessions) } : next);
        setSessionError('');
      } catch (failure) { if (valid(signal)) setSessionError(errorMessage(failure)); }
      finally { if (valid(signal)) setSessionsReading(false); }
    }, 15000, { onPause: () => { moreRequest.current?.abort(); setMoreReading(false); setSessionsReading(false); } });
    return () => { stop(); moreRequest.current?.abort(); };
  }, [base, connection, connected, sessionRevision]);

  async function loadMoreSessions() {
    if (!connected || !sessionPage?.hasMore || moreReading) return;
    const generation = sessionGeneration.current, requestedConnection = connection;
    const controller = new AbortController(); moreRequest.current?.abort(); moreRequest.current = controller;
    const valid = () => !controller.signal.aborted && generation === sessionGeneration.current && currentConnection() === requestedConnection;
    setMoreReading(true);
    try {
      const next = await api<SessionPage>(`${base}/sessions?openOnly=false&limit=50&before=${sessionPage.cursor}`, { signal: controller.signal });
      if (!valid()) return;
      setSessionPage(previous => ({ ...next, sessions: mergeSessions(previous?.sessions || [], next.sessions) })); setSessionError('');
    } catch (failure) { if (valid()) setSessionError(errorMessage(failure)); }
    finally { if (valid()) setMoreReading(false); }
  }

  const selectedUnit = data?.summary.units[unit];
  return <div className="usage-trends">
    <PageTitle eyebrow="PROJECT USAGE" title="用量趋势" description="从持久请求记录追踪用量，让已知与未知都清楚可见。">
      <button className="button secondary" disabled={!connected || reading} onClick={() => { ++filterGeneration.current; setRevision(value => value + 1); }}>
        <RefreshCw size={16} className={reading ? 'spin' : undefined} />刷新用量
      </button>
    </PageTitle>
    <section className="card usage-filters" aria-label="用量筛选">
      <div className="usage-window"><span id="usage-window-label">时间范围 · UTC</span><div className="segmented" role="group" aria-labelledby="usage-window-label">
        {([7, 30, 90] as UsageWindow[]).map(value => <button key={value} aria-pressed={days === value} className={days === value ? 'active' : ''} onClick={() => { if (value !== days) { invalidate(); setDays(value); } }}>近 {value} 天</button>)}
      </div></div>
      <Field label="会话范围" hint="包含已关闭会话；可加载更早记录。"><select disabled={!connected || !sessionPage} value={sessionId} onChange={event => chooseSession(event.target.value)}>
        <option value="">全部会话</option>{sessionPage?.sessions.map(session => <option key={session.id} value={session.id}>{session.title}{session.isOpen ? '' : '（已关闭）'}</option>)}
      </select></Field>
      <Field label="任务范围" hint={sessionId ? '仅列出当前会话的任务。' : '先选择会话，再按任务筛选。'}><select disabled={!connected || !sessionId} value={taskId} onChange={event => chooseTask(event.target.value)}>
        <option value="">{sessionId ? '当前会话全部任务' : '先选择会话'}</option>{sessionTasks.map(task => <option key={task.id} value={task.id}>{task.title}</option>)}
      </select></Field>
      <div className="usage-session-actions">
        {sessionPage?.hasMore && <button className="text-button" disabled={!connected || moreReading || sessionsReading} onClick={() => void loadMoreSessions()}>{moreReading ? <LoaderCircle size={14} className="spin" /> : <ChevronDown size={14} />}加载更多会话</button>}
        {!sessionPage && sessionsReading && <span role="status">正在读取会话…</span>}
        {sessionError && <div role="alert"><span>会话筛选读取失败：{sessionError}</span><button className="text-button" disabled={!connected} onClick={() => setSessionRevision(value => value + 1)}>重新读取会话</button></div>}
      </div>
    </section>
    {error && <div className="usage-read-error" role="alert"><TriangleAlert size={18} /><div><b>用量读取失败</b><p>{error}{data ? ' · 当前保留上次成功快照。' : ''}</p></div><button className="button secondary small" disabled={!connected || reading} onClick={() => { ++filterGeneration.current; setRevision(value => value + 1); }}>重新读取用量</button></div>}
    <div className="usage-snapshot" role="status" aria-live="polite">
      <span><span className={`usage-read-dot ${connected && !error ? 'online' : ''}`} />{!connected ? '连接恢复后继续读取' : reading ? '正在读取用量…' : data ? '每 5 秒自动更新' : '等待用量快照'}</span>
      {data && <span>含 UTC 今天 · {data.from.slice(0, 10)} 至 {data.buckets.at(-1)?.date} · 快照 {utcTime(data.asOf)}</span>}
    </div>
    {!data && !error && <section className="card"><Empty icon={reading ? <LoaderCircle size={28} className="spin" /> : <ChartNoAxesCombined size={28} />} title={reading ? '正在读取用量记录' : '等待连接本地核心'} description="按 UTC 日期读取当前筛选的持久请求用量。" /></section>}
    {data && <>
      {!data.summary.hasRecords && <section className="card usage-empty" aria-label="无用量记录"><Empty icon={<ChartNoAxesCombined size={28} />} title="所选范围暂无请求记录" description="无记录不代表请求用量为零。调整时间、会话或任务范围后可继续查看。" /></section>}
      <div className="usage-unit-grid">{units.map(name => <UnitSummary key={name} name={name} value={data.summary.units[name]} />)}</div>
      <section className="card usage-daily" aria-labelledby="usage-daily-title">
        <header className="usage-section-heading"><div><h2 id="usage-daily-title">按天查看</h2><p>每日记录归入最初创建的 UTC 日期；派发中记录会在原日桶更新状态。</p></div><span className="usage-timezone">UTC · 日桶</span></header>
        <div className="usage-chart-controls"><div className="usage-unit-tabs" role="tablist" aria-label="趋势计量单位">{units.map(name => <button key={name} role="tab" id={`usage-tab-${name}`} aria-selected={unit === name} aria-controls="usage-daily-panel" onClick={() => setUnit(name)}>{unitLabels[name].title}</button>)}</div>
          <Field label="趋势指标"><select value={chartMetric} onChange={event => setChartMetric(event.target.value as ChartMetric)}><option value="recordCount">{unitLabels[unit].count}</option><option value="inputTokens">输入 tokens</option><option value="outputTokens">输出 tokens</option></select></Field>
        </div>
        <div id="usage-daily-panel" role="tabpanel" aria-labelledby={`usage-tab-${unit}`}>
          <div className="usage-chart-summary"><span>{unitLabels[unit].title} · {chartMetric === 'recordCount' ? unitLabels[unit].count : chartMetric === 'inputTokens' ? '输入 tokens' : '输出 tokens'}</span><b>{selectedUnit && (chartMetric === 'recordCount' ? `${number(selectedUnit.recordCount)} 条记录` : metricText(selectedUnit.tokens[chartMetric], selectedUnit.hasRecords))}</b></div>
          <DailyChart data={data} unit={unit} metric={chartMetric} />
          <div className="usage-table-scroll" tabIndex={0} role="region" aria-label={`${unitLabels[unit].title}每日数据表，可横向滚动`}>
            <table className="usage-table"><caption>{unitLabels[unit].title}每日用量 · UTC · {days} 天</caption><thead><tr><th scope="col">UTC 日期</th><th scope="col">{unitLabels[unit].count}</th>{fields.map(field => <th key={field.key} scope="col">{field.label}{field.detail && <small>{field.detail}</small>}</th>)}<th scope="col">请求状态</th></tr></thead>
              <tbody>{data.buckets.map(bucket => { const value = bucket.units[unit]; return <tr key={bucket.date}><th scope="row">{bucket.date}</th><td>{value.hasRecords ? number(value.recordCount) : <span className="usage-muted">无记录</span>}</td>{fields.map(field => <td key={field.key}><MetricValue value={value.tokens[field.key]} hasRecords={value.hasRecords} /></td>)}<td className="usage-table-states">{value.hasRecords ? <StateCounts value={value} /> : '—'}</td></tr>; })}</tbody>
            </table>
          </div>
        </div>
      </section>
      <aside className="usage-notes" aria-label="用量统计说明"><Info size={18} /><div><h2>怎样读这份用量</h2><p>输入已包含缓存输入，输出已包含推理输出；两项明细不再叠加。每个字段单独展示已知记录覆盖率，明确报告的 0 会保留为 0。</p><p>API 请求数、CLI 外层会话数和未归类记录分别统计。CLI 内部请求数：<b>未知</b>；费用：<b>未知</b>。历史记录缺失的用量不会估算，也不会与运行汇总重复累计。</p></div></aside>
    </>}
  </div>;
}

function mergeSessions(first: Session[], second: Session[]) {
  const ids = new Set(first.map(session => session.id));
  return [...first, ...second.filter(session => !ids.has(session.id))];
}

function utcTime(value: string) {
  const parsed = new Date(value);
  return Number.isNaN(parsed.getTime()) ? value : `${parsed.toISOString().slice(11, 19)} UTC`;
}

function metricText(value: UsageMetric, hasRecords: boolean) {
  if (!hasRecords) return '无记录';
  if (value.overflow) return '未知 · 超出安全精度';
  if (typeof value.total === 'number') return number(value.total);
  return typeof value.observedSum === 'number' && value.knownCount > 0 ? `未知 · 已知部分 ${number(value.observedSum)}` : '未知';
}

function MetricValue({ value, hasRecords }: { value: UsageMetric; hasRecords: boolean }) {
  return <span className={`usage-metric-value ${!hasRecords || value.total === 'UNKNOWN' ? 'incomplete' : ''}`}>
    <strong>{!hasRecords ? '—' : typeof value.total === 'number' ? number(value.total) : '未知'}</strong>
    {!hasRecords ? <small>无记录</small> : <>
      {value.overflow ? <small>超出安全精度</small> : value.total === 'UNKNOWN' && value.knownCount > 0 && typeof value.observedSum === 'number' && <small>已知部分 {number(value.observedSum)}</small>}
      <small>已知 {number(value.knownCount)}/{number(value.knownCount + value.unknownCount)}{value.unknownCount > 0 ? ` · 未知 ${number(value.unknownCount)}` : ''}</small>
    </>}
  </span>;
}

function StateCounts({ value }: { value: UsageUnit }) {
  return <span className="usage-state-counts">{(Object.keys(stateLabels) as UsageState[]).map(state => <span key={state} className={state === 'DISPATCHING' && value.states[state] ? 'pending' : undefined}>{stateLabels[state]} <b>{number(value.states[state])}</b></span>)}</span>;
}

function UnitSummary({ name, value }: { name: UsageUnitName; value: UsageUnit }) {
  const Icon = name === 'API_REQUEST' ? Globe2 : name === 'CLI_SESSION' ? Terminal : CircleHelp;
  return <section className={`card usage-unit-card unit-${name.toLowerCase()}`} aria-label={`${unitLabels[name].title}用量`}>
    <header><div className="usage-unit-icon"><Icon size={20} /></div><div><h2>{unitLabels[name].title}</h2><p>{unitLabels[name].description}</p></div></header>
    <div className="usage-record-count"><b>{number(value.recordCount)}</b><span>{unitLabels[name].count}</span>{!value.hasRecords && <small>无记录</small>}</div>
    <dl className="usage-token-grid">{fields.map(field => <div key={field.key}><dt>{field.label}{field.detail && <small>{field.detail}</small>}</dt><dd><MetricValue value={value.tokens[field.key]} hasRecords={value.hasRecords} /></dd></div>)}</dl>
    <footer><StateCounts value={value} /></footer>
  </section>;
}

function DailyChart({ data, unit, metric }: { data: UsageTrendsResponse; unit: UsageUnitName; metric: ChartMetric }) {
  const points = data.buckets.map(bucket => {
    const value = bucket.units[unit], token = metric === 'recordCount' ? null : value.tokens[metric];
    const observed = token ? token.observedSum : value.recordCount;
    const numeric = value.hasRecords && typeof observed === 'number' && (!token || token.knownCount > 0) && !token?.overflow ? observed : null;
    const label = metric === 'recordCount' ? value.hasRecords ? `${number(value.recordCount)} 条记录` : '无记录' : metricText(token!, value.hasRecords);
    const quality = !value.hasRecords ? 'empty' : numeric === null ? 'unknown' : token?.unknownCount ? 'partial' : 'known';
    return { date: bucket.date, numeric, label, quality };
  });
  const maximum = Math.max(1, ...points.map(point => point.numeric ?? 0));
  return <figure className="usage-chart"><figcaption>每日{metric === 'recordCount' ? unitLabels[unit].count : metric === 'inputTokens' ? '输入 tokens' : '输出 tokens'}。条纹表示已知部分；未知和无记录分别标记，完整数值见下方数据表。</figcaption>
    <div className="usage-chart-scroll" tabIndex={0} role="region" aria-label={`${unitLabels[unit].title}每日趋势图，可横向滚动`}>
      <ol className="usage-chart-track" style={{ minWidth: `${Math.max(320, points.length * 44)}px` }}>{points.map((point, index) => <li key={point.date} className={`usage-chart-point usage-quality-${point.quality}`} aria-label={`${point.date} UTC：${point.label}`} title={`${point.date} UTC：${point.label}`}>
        <span className="usage-chart-value" aria-hidden="true">{point.quality === 'empty' ? '—' : point.numeric === null ? '?' : point.numeric === 0 ? '0' : ''}</span>
        <span className="usage-chart-column" aria-hidden="true"><span className="usage-chart-bar" style={{ height: point.numeric === null ? '12px' : `${Math.max(2, point.numeric / maximum * 132)}px` }} /></span>
        <span className="usage-chart-date" aria-hidden="true">{data.days === 7 || index % (data.days === 30 ? 5 : 10) === 0 || index === points.length - 1 ? point.date.slice(5) : '·'}</span>
      </li>)}</ol>
    </div>
    <div className="usage-chart-legend"><span><i className="usage-quality-known" />完整值（含明确 0）</span><span><i className="usage-quality-partial" />已知部分</span><span><i className="usage-quality-unknown" />未知 / 超出精度</span><span><i className="usage-quality-empty" />无记录</span></div>
  </figure>;
}
