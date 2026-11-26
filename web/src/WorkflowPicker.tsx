import { useEffect, useState } from 'react';
import { api, currentConnection } from './api';
import type { Connection, Route } from './types';
import { Field } from './ui';
import type { WorkflowDefinition, WorkflowDsl, WorkflowPage, WorkflowReadiness, WorkflowRevision, WorkflowRouteIdentity, WorkflowSelection, WorkflowVersion, WorkflowVersionSelection } from './workflow-types';
import './workflow.css';

export const workflowPath = (id: string) => `/workflows/${encodeURIComponent(id)}`;
export const workflowVersionPath = (id: string, version: number) => `${workflowPath(id)}/versions/${version}`;
const kinds: Record<string, string> = { INVESTIGATE: '调查', DESIGN: '方案', PROPOSAL: '独立提案', CRITIQUE: '交叉质疑', SYNTHESIS: '汇总', IMPLEMENT: '实现' };
const rootInputs: Record<string, string> = { goal: '任务目标', contract: '完成约定', context: '项目上下文', baseline: '基线版本' };
const pendingReadiness = new WeakMap<Connection, Map<string, Promise<WorkflowReadiness>>>();

function readWorkflowReadiness(connection: Connection, path: string, body: string) {
  const key = `${path}\u0000${body}`;
  let pending = pendingReadiness.get(connection);
  if (!pending) { pending = new Map(); pendingReadiness.set(connection, pending); }
  const existing = pending.get(key);
  if (existing) return existing;
  const operation = api<WorkflowReadiness>(path, { method: 'POST', body });
  pending.set(key, operation);
  operation.finally(() => {
    if (pending?.get(key) === operation) pending.delete(key);
  }).catch(() => {});
  return operation;
}

export interface WorkflowReadinessContext {
  projectId: string;
  rootRoute: Route;
  locks: Record<string, string>;
  routes: Route[];
  taskId?: string;
}

export interface ReviewedWorkflowSelection {
  contextKey: string;
  selection: WorkflowSelection;
}

function contextKey({ projectId, rootRoute, locks, routes, taskId }: WorkflowReadinessContext) {
  return JSON.stringify([projectId, taskId, rootRoute.id, rootRoute.version, rootRoute.enabled, Object.entries(locks).sort(),
    routes.map(route => [route.id, route.version, route.enabled]).sort()]);
}

export function currentWorkflowSelection(context: WorkflowReadinessContext | null, reviewed: ReviewedWorkflowSelection | null) {
  return context && reviewed && reviewed.contextKey === contextKey(context) ? reviewed.selection : null;
}

export function WorkflowNodes({ dsl }: { dsl: WorkflowDsl }) {
  return <div className="workflow-node-list" aria-label="流程节点">{dsl.nodes.map(node => <div key={node.id} className="workflow-node-row">
    <div><b>{node.title}</b><span>{kinds[node.kind] || node.kind} · {node.role}</span></div>
    <div><code>{node.id}</code><span>{node.dependencies.length ? `依赖：${node.dependencies.join('、')}` : '起始节点'}</span></div>
    <div><span>{node.maxCalls} 次 · {node.timeoutSeconds} 秒</span><span>声明权限：{node.permissions.join('、')}</span></div>
  </div>)}</div>;
}

export function WorkflowPicker({ context, invalidation, disabled = false, onChange }: {
  context: WorkflowReadinessContext;
  invalidation: number;
  disabled?: boolean;
  onChange: (value: ReviewedWorkflowSelection | null) => void;
}) {
  const { projectId } = context;
  const [definitions, setDefinitions] = useState<WorkflowDefinition[] | null>(null);
  const [definitionId, setDefinitionId] = useState('');
  const [refresh, setRefresh] = useState(0);
  const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    setError(''); setDefinitions(null);
    api<WorkflowDefinition[]>(`/workflows?projectId=${encodeURIComponent(projectId)}`, { signal: controller.signal })
      .then(items => { if (!controller.signal.aborted) setDefinitions(items.filter(item => item.enabled)); })
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [projectId, refresh]);
  const definition = definitions?.find(item => item.id === definitionId);
  return <div className="workflow-picker">
    <div className="workflow-inline"><Field label="流程定义"><select value={definition?.id || ''} disabled={disabled || !definitions?.length} onChange={event => { onChange(null); setDefinitionId(event.target.value); }}>
      <option value="">{definitions ? '选择已发布流程' : '正在读取流程…'}</option>
      {definitions?.map(item => <option key={item.id} value={item.id}>{item.name} · v{item.version}{item.projectId ? ' · 本项目' : ''}</option>)}
    </select></Field><button type="button" className="button secondary small" disabled={disabled} onClick={() => { onChange(null); setDefinitionId(''); setRefresh(value => value + 1); }}>刷新流程</button></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {definitions?.length === 0 && <p className="form-note">没有可用版本。可在「资源与设置 → 流程模板」发布流程。</p>}
    {definition && <WorkflowVersionPicker key={`${projectId}:${definition.id}`} definition={definition} context={context} invalidation={invalidation} disabled={disabled} onChange={onChange} />}
  </div>;
}

function WorkflowVersionPicker({ definition, context, invalidation, disabled, onChange }: {
  definition: WorkflowDefinition;
  context: WorkflowReadinessContext;
  invalidation: number;
  disabled: boolean;
  onChange: (value: ReviewedWorkflowSelection | null) => void;
}) {
  const [number, setNumber] = useState(definition.version);
  const [version, setVersion] = useState<WorkflowVersion | null>(null);
  const [before, setBefore] = useState(0);
  const [revisions, setRevisions] = useState<WorkflowRevision[]>([]);
  const [older, setOlder] = useState(0);
  const [reviewedInvalidation, setReviewedInvalidation] = useState(invalidation);
  const [readinessRefresh, setReadinessRefresh] = useState(0);
  const [error, setError] = useState('');
  const [historyError, setHistoryError] = useState('');
  useEffect(() => {
    const controller = new AbortController(); setError(''); setVersion(null);
    api<WorkflowVersion>(workflowVersionPath(definition.id, number), { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setVersion(value); })
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [definition.id, number]);
  useEffect(() => {
    const controller = new AbortController(); setHistoryError('');
    api<WorkflowPage<'versions', WorkflowRevision>>(`${workflowPath(definition.id)}/versions?before=${before}`, { signal: controller.signal })
      .then(page => { if (!controller.signal.aborted) { setRevisions(items => before ? [...items, ...page.versions] : page.versions); setOlder(page.hasMore ? page.next : 0); } })
      .catch(error => { if (!controller.signal.aborted) setHistoryError(String(error.message)); });
    return () => controller.abort();
  }, [definition.id, before]);
  const current = version?.definitionId === definition.id && version.version === number ? version : null;
  const selection = current && { definitionId: current.definitionId, version: current.version, digest: current.digest };
  const source = contextKey(context);
  function refreshReadiness() {
    onChange(null);
    setReviewedInvalidation(invalidation);
    setReadinessRefresh(value => value + 1);
  }
  return <>
    <div className="workflow-inline"><Field label="流程版本"><select value={number} disabled={disabled} onChange={event => { refreshReadiness(); setNumber(Number(event.target.value)); }}>
      {[definition.version, ...revisions.filter(revision => revision.version !== definition.version).map(revision => revision.version)].map(value => <option key={value} value={value}>v{value}{value === definition.version ? ' · 当前' : ''}</option>)}
    </select></Field>{older > 0 && <button type="button" className="text-button" disabled={disabled || older === before} onClick={() => setBefore(older)}>读取更早版本</button>}</div>
    {(error || historyError) && <p className="form-error" role="alert">{error || historyError}</p>}
    {current && selection && <><WorkflowNodes dsl={current.dsl} />
      <div className="workflow-actions"><b>执行前检查</b><button type="button" className="text-button" disabled={disabled} onClick={refreshReadiness}>刷新执行检查</button></div>
      {reviewedInvalidation !== invalidation ? <p className="form-note" role="status">刷新执行检查后再确认当前路由。</p> :
        <WorkflowReadinessReview key={JSON.stringify([source, selection, readinessRefresh])} context={context} selection={selection} source={source} disabled={disabled} onChange={onChange} />}
    </>}
    {!current && !error && <p className="form-note" role="status">正在读取固定版本…</p>}
  </>;
}

function WorkflowReadinessReview({ context, selection, source, disabled, onChange }: {
  context: WorkflowReadinessContext;
  selection: WorkflowVersionSelection;
  source: string;
  disabled: boolean;
  onChange: (value: ReviewedWorkflowSelection | null) => void;
}) {
  const [result, setResult] = useState<WorkflowReadiness | null>(null);
  const [error, setError] = useState('');
  const [confirmed, setConfirmed] = useState<Map<string, number>>(() => new Map());
  const connection = currentConnection();
  const path = context.taskId ? `/tasks/${encodeURIComponent(context.taskId)}/workflow-readiness` : '/workflows/readiness';
  const body = JSON.stringify(context.taskId ? selection : {
    projectId: context.projectId, routeId: context.rootRoute.id, locks: context.locks, selection,
  });
  const blockers = result ? [...new Set([...result.blockers, ...result.nodes.flatMap(node => node.blockers.map(reason => `${node.title}：${reason}`))])] : [];
  const blocked = blockers.length > 0;
  const { definitionId, version, digest } = selection;

  useEffect(() => {
    let active = true;
    readWorkflowReadiness(connection, path, body).then(value => {
      if (active) setResult(value);
    }).catch(error => { if (active) setError((error as Error).message); });
    return () => { active = false; };
  }, [connection, path, body]);

  useEffect(() => {
    if (!result || blocked || !result.extraRoutes.every(route => confirmed.get(route.id) === route.version)) {
      onChange(null);
      return;
    }
    onChange({ contextKey: source, selection: { definitionId, version, digest, rootRouteVersion: result.rootRoute.version,
      allowedRouteVersions: Object.fromEntries(result.extraRoutes.map(route => [route.id, route.version])) } });
  }, [blocked, result, confirmed, onChange, source, definitionId, version, digest]);

  if (error) return <p className="form-error" role="alert">{error}</p>;
  if (!result) return <p className="form-note" role="status">正在检查流程与路由…</p>;

  return <div className="workflow-readiness">
    <div className="workflow-route-detail"><b>根任务路由 · {result.rootRoute.name} v{result.rootRoute.version}</b><WorkflowRouteDetail route={result.rootRoute} /></div>
    <p className="form-note">每个节点都会收到固定目标、职责说明和适用的项目规则；声明输入控制自动附加的根任务资料与祖先交接。</p>
    {blocked && <ul className="form-error workflow-blockers" aria-label="流程阻塞原因" role="alert">
      {blockers.map(reason => <li key={reason}>{reason}</li>)}
    </ul>}
    {result.extraRoutes.length > 0 && <fieldset className="workflow-consent"><legend>确认额外发送路由</legend>
      <p className="form-note">列出的路由会收到相应节点的目标、交接和授权范围内的资料。</p>
      {result.extraRoutes.map(route => <div className="workflow-route-detail" key={`${route.id}:${route.version}`}>
        <label><input type="checkbox" disabled={disabled || blocked} checked={confirmed.get(route.id) === route.version} onChange={event => {
          const checked = event.target.checked;
          onChange(null);
          setConfirmed(previous => {
            const next = new Map(previous);
            if (checked) next.set(route.id, route.version);
            else next.delete(route.id);
            return next;
          });
        }} />确认 {route.name} v{route.version}</label>
        <WorkflowRouteDetail route={route} />
        <ul className="workflow-route-inputs">
          {result.nodes.filter(node => node.route?.id === route.id && node.route.version === route.version).map(node => <li key={node.id}>
            <b>{node.title}</b> · 声明输入：{node.inputs.map(input => rootInputs[input] || input).join('、') || '无'}
          </li>)}
        </ul>
      </div>)}
    </fieldset>}
  </div>;
}

function WorkflowRouteDetail({ route }: { route: WorkflowRouteIdentity }) {
  return <div className="workflow-meta">
    <span>{route.engine} · 供应商：{route.provider || '未填写'} · 模型：{route.model || '由运行器选择'}</span>
    <span>端点：<code>{route.baseUrl || '由运行器配置'}</code></span>
  </div>;
}
