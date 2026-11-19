import { useEffect, useMemo, useState } from 'react';
import { api } from './api';
import type { Route } from './types';
import { Field } from './ui';
import type { WorkflowDefinition, WorkflowDsl, WorkflowPage, WorkflowRevision, WorkflowSelection, WorkflowVersion } from './workflow-types';
import './workflow.css';

export const workflowPath = (id: string) => `/workflows/${encodeURIComponent(id)}`;
export const workflowVersionPath = (id: string, version: number) => `${workflowPath(id)}/versions/${version}`;
const kinds: Record<string, string> = { INVESTIGATE: '调查', DESIGN: '方案', PROPOSAL: '独立提案', CRITIQUE: '交叉质疑', SYNTHESIS: '汇总', IMPLEMENT: '实现' };

export function WorkflowNodes({ dsl }: { dsl: WorkflowDsl }) {
  return <div className="workflow-node-list" aria-label="流程节点">{dsl.nodes.map(node => <div key={node.id} className="workflow-node-row">
    <div><b>{node.title}</b><span>{kinds[node.kind] || node.kind} · {node.role}</span></div>
    <div><code>{node.id}</code><span>{node.dependencies.length ? `依赖：${node.dependencies.join('、')}` : '起始节点'}</span></div>
    <div><span>{node.maxCalls} 次 · {node.timeoutSeconds} 秒</span><span>{node.permissions.join('、')}</span></div>
  </div>)}</div>;
}

export function WorkflowPicker({ projectId, rootRoute, routes, onChange }: {
  projectId: string; rootRoute: Route; routes: Route[]; onChange: (value: WorkflowSelection | null) => void;
}) {
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
    <div className="workflow-inline"><Field label="流程定义"><select value={definition?.id || ''} disabled={!definitions?.length} onChange={event => { onChange(null); setDefinitionId(event.target.value); }}>
      <option value="">{definitions ? '选择已发布流程' : '正在读取流程…'}</option>
      {definitions?.map(item => <option key={item.id} value={item.id}>{item.name} · v{item.version}{item.projectId ? ' · 本项目' : ''}</option>)}
    </select></Field><button type="button" className="button secondary small" onClick={() => { onChange(null); setDefinitionId(''); setRefresh(value => value + 1); }}>刷新流程</button></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {definitions?.length === 0 && <p className="form-note">没有可用版本。可在「资源与设置 → 流程模板」发布流程。</p>}
    {definition && <WorkflowVersionPicker key={definition.id} definition={definition} rootRoute={rootRoute} routes={routes} onChange={onChange} />}
  </div>;
}

function WorkflowVersionPicker({ definition, rootRoute, routes, onChange }: {
  definition: WorkflowDefinition; rootRoute: Route; routes: Route[]; onChange: (value: WorkflowSelection | null) => void;
}) {
  const [number, setNumber] = useState(definition.version);
  const [version, setVersion] = useState<WorkflowVersion | null>(null);
  const [before, setBefore] = useState(0);
  const [revisions, setRevisions] = useState<WorkflowRevision[]>([]);
  const [older, setOlder] = useState(0);
  const [confirm, setConfirm] = useState<string[]>([]);
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
  const extraRouteIds = useMemo(() => version ? [...new Set(version.agentDefinitions.map(actor => actor.routeId).filter((id): id is string => !!id && id !== rootRoute.id))] : [], [rootRoute.id, version]);
  const eligible = extraRouteIds.every(id => routes.some(route => route.id === id && route.engine === 'API' && route.enabled !== false));
  const canSelect = !!version && version.version === number && eligible && extraRouteIds.every(id => confirm.includes(id));
  useEffect(() => {
    onChange(canSelect && version ? { definitionId: definition.id, version: number, digest: version.digest, allowedRouteIds: extraRouteIds } : null);
  }, [canSelect, definition.id, extraRouteIds, number, onChange, version]);
  return <>
    <div className="workflow-inline"><Field label="流程版本"><select value={number} onChange={event => { onChange(null); setConfirm([]); setNumber(Number(event.target.value)); }}>
      {[definition.version, ...revisions.filter(revision => revision.version !== definition.version).map(revision => revision.version)].map(value => <option key={value} value={value}>v{value}{value === definition.version ? ' · 当前' : ''}</option>)}
    </select></Field>{older > 0 && <button type="button" className="text-button" disabled={older === before} onClick={() => setBefore(older)}>读取更早版本</button>}</div>
    {(error || historyError) && <p className="form-error" role="alert">{error || historyError}</p>}
    {version && <><WorkflowNodes dsl={version.dsl} />
      <p className="form-note">流程版本与职责固定到任务。基线、必要验证、独立审查和人工验收继续由任务负责。</p>
      {extraRouteIds.length > 0 && <fieldset className="workflow-consent"><legend>确认额外发送路由</legend><p className="form-note">这些运行器会收到节点目标、交接和授权范围内的项目资料。</p>{extraRouteIds.map(id => {
        const route = routes.find(item => item.id === id);
        const available = route?.engine === 'API' && route.enabled !== false;
        return <label key={id}><input type="checkbox" disabled={!available} checked={confirm.includes(id)} onChange={event => { const checked = event.target.checked; if (!checked) onChange(null); setConfirm(items => checked ? [...items, id] : items.filter(item => item !== id)); }} />{route?.name || id}{available ? ` · ${route.model || 'API'}` : '（当前不可用）'}</label>;
      })}</fieldset>}
    </>}
    {!version && !error && <p className="form-note" role="status">正在读取固定版本…</p>}
  </>;
}
