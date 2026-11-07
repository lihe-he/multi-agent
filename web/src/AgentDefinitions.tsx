import { useState } from 'react';
import { Bot, Plus } from 'lucide-react';
import { post } from './api';
import type { AgentDefinition, Project, Route } from './types';
import { Badge, Field, lines, Modal, SaveButton } from './ui';

const capabilities = { read: '读取', search: '检索', write: '修改', verify: '验证命令', delegate: '继续委派', mcp: '已确认的 MCP' };
export function AgentDefinitions({ definitions, projects, routes, onRefresh }: { definitions: AgentDefinition[]; projects: Project[]; routes: Route[]; onRefresh: () => Promise<void> }) {
  const [editing, setEditing] = useState<AgentDefinition | null | undefined>(undefined);
  return <section className="agent-definitions" aria-label="动态 Agent 定义">
    <div className="agent-run-heading"><div><h2>动态副 Agent 定义</h2><p className="form-note">在任务计划中选择允许的定义；进行中的任务使用固定版本，实际能力受根授权和委派范围约束。</p></div><button className="button primary small" onClick={() => setEditing(null)}><Plus size={15} />新建副 Agent</button></div>
    <div className="definition-grid">{definitions.map(definition => <article className="card definition-card" key={definition.id}>
      <header><Bot size={20} /><div><h3>{definition.name}</h3><p>{definition.role} · v{definition.version}</p></div><Badge state={definition.enabled ? 'ACTIVE' : 'DISABLED'} /></header>
      <p className="definition-instructions">{definition.instructions}</p><div className="definition-capabilities">{definition.capabilities.map(capability => <span className="chip" key={capability}>{capabilities[capability as keyof typeof capabilities] || capability}</span>)}</div>
      <dl><dt>路由</dt><dd>{routes.find(route => route.id === definition.routeId)?.name || '继承根任务路由'}</dd><dt>项目</dt><dd>{projects.find(project => project.id === definition.projectId)?.name || '全局可选'}</dd><dt>范围</dt><dd><code>{definition.scope.join('，')}</code></dd><dt>调用上限</dt><dd>{definition.maxCalls} 次，仍计入根预算</dd></dl>
      <button className="button secondary small" onClick={() => setEditing(definition)}>编辑定义与模型</button>
    </article>)}</div>{!definitions.length && <p className="muted-text">尚未配置动态副 Agent 定义。</p>}
    {editing !== undefined && <DefinitionDialog key={editing?.id || 'new'} definition={editing || undefined} projects={projects} routes={routes} onClose={() => setEditing(undefined)} onSaved={onRefresh} />}
  </section>;
}

function DefinitionDialog({ definition, projects, routes, onClose, onSaved }: { definition?: AgentDefinition; projects: Project[]; routes: Route[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const [name, setName] = useState(definition?.name || ''); const [role, setRole] = useState(definition?.role || 'researcher');
  const [instructions, setInstructions] = useState(definition?.instructions || ''); const [selected, setSelected] = useState(definition?.capabilities || ['read', 'search']);
  const [routeId, setRouteId] = useState(definition?.routeId || ''); const [projectId, setProjectId] = useState(definition?.projectId || '');
  const [scope, setScope] = useState(definition?.scope.join('\n') || '**'); const [maxCalls, setMaxCalls] = useState(definition?.maxCalls || 8); const [enabled, setEnabled] = useState(definition?.enabled ?? true);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  return <Modal title={definition ? '编辑副 Agent 定义' : '新建副 Agent 定义'} description="定义提供职责和能力需求，任务确认后固定版本。停用会阻止当前副 Agent 新增调用。" onClose={onClose}><form onSubmit={async event => {
    event.preventDefault(); setBusy(true); setError('');
    try { await post('/agent-definitions', { ...(definition ? { id: definition.id, expectedVersion: definition.version } : {}), name, role, instructions, capabilities: selected, scope: lines(scope), routeId, projectId, maxCalls, enabled }); await onSaved(); onClose(); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }}><div className="form-body">
    <div className="form-grid"><Field label="Agent 名称"><input required maxLength={160} value={name} onChange={event => setName(event.target.value)} /></Field><Field label="角色标识"><input required maxLength={64} value={role} onChange={event => setRole(event.target.value)} /></Field></div>
    <Field label="职责与交付要求"><textarea required rows={5} maxLength={24000} value={instructions} onChange={event => setInstructions(event.target.value)} /></Field>
    <div className="field" role="group" aria-label="能力需求"><span>能力需求</span><div className="checkbox-row definition-permission-options">{Object.entries(capabilities).map(([capability, label]) => <label key={capability}><input type="checkbox" checked={selected.includes(capability)} onChange={event => setSelected(current => event.target.checked ? [...current, capability] : current.filter(value => value !== capability))} />{label}</label>)}</div><small>未被根任务或父阶段授权的能力会被移除。窄文件范围无法约束命令与外部 MCP 的访问，因此交回根授权阶段执行。</small></div>
    <div className="form-grid"><Field label="模型运行路由"><select value={routeId} onChange={event => setRouteId(event.target.value)}><option value="">继承根任务路由</option>{routes.filter(route => route.engine === 'API').map(route => <option key={route.id} value={route.id}>{route.name} · {route.model}</option>)}</select></Field><Field label="可选项目范围"><select value={projectId} onChange={event => setProjectId(event.target.value)}><option value="">全局可选</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></Field></div>
    <Field label="文件范围" hint="每行一项相对路径或 glob，与父执行和根授权逐层相交。"><textarea required rows={2} className="mono" value={scope} onChange={event => setScope(event.target.value)} /></Field>
    <Field label="单次调用上限"><input type="number" min={1} max={32} value={maxCalls} onChange={event => setMaxCalls(Number(event.target.value))} /></Field>
    <label className="checkbox-row"><input type="checkbox" checked={enabled} onChange={event => setEnabled(event.target.checked)} />启用此定义</label>{error && <p role="alert" className="form-error">{error}</p>}
  </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>保存定义版本</SaveButton></footer></form></Modal>;
}
