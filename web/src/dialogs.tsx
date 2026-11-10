import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Bot, Check, FolderOpen, GitBranch, Layers3, LockKeyhole, Network, ShieldCheck, Sparkles, TriangleAlert } from 'lucide-react';
import { api, currentConnection, post, setConnection } from './api';
import type { AgentDefinition, ExecutionNode, Memory, Profile, Project, RepositoryBinding, Route, Task } from './types';
import { Field, lines, Modal, SaveButton } from './ui';
import { WorkspacePicker } from './WorkspacePicker';
import type { WorkspaceChoice } from './types';

type DialogProps = { onClose: () => void; onSaved: () => void };
function ErrorMessage({ error }: { error: string }) { return error ? <div role="alert" className="form-error"><TriangleAlert size={16} />{error}</div> : null; }

export function ProjectDialog({ onClose, onSaved }: DialogProps) {
  const [name, setName] = useState(''); const [path, setPath] = useState('');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try { await post('/projects', { name: name.trim(), path: path.trim() }); onSaved(); onClose(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal title="导入本地项目" description="连接一个可信的 Git 项目，建立独立的研发空间。" onClose={onClose}><form onSubmit={submit}>
    <div className="form-body"><div className="info-box"><GitBranch size={19} /><div><b>从现有代码开始</b><p>平台读取项目结构与版本；任务执行时建立独立工作目录。</p></div></div>
      <Field label="项目名称"><input required value={name} onChange={e => setName(e.target.value)} placeholder="例如：客户服务平台" maxLength={100} /></Field>
      <Field label="本地仓库路径" hint="填写已存在、至少有一次提交的 Git 仓库绝对路径。"><div className="input-with-button"><input required value={path} onChange={e => setPath(e.target.value)} placeholder="D:\Projects\my-project" autoComplete="off" />{window.workbench?.selectDirectory && <button type="button" className="button secondary" onClick={async () => { const selected = await window.workbench?.selectDirectory?.(); if (selected) setPath(selected); }}><FolderOpen size={16} />选择</button>}</div></Field>
      <ErrorMessage error={error} />
    </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>导入项目</SaveButton></footer>
  </form></Modal>;
}

export function TaskDialog({ projects, routes, bindings = [], nodes = [], selectedProject, sessionId, onClose, onCreated }: { projects: Project[]; routes: Route[]; bindings?: RepositoryBinding[]; nodes?: ExecutionNode[]; selectedProject: string; sessionId?: string; onClose: () => void; onCreated: (task: Task) => void }) {
  const [projectId, setProjectId] = useState(selectedProject || projects[0]?.id || '');
  const [title, setTitle] = useState(''); const [goal, setGoal] = useState('');
  const [mode, setMode] = useState('AUTO'); const [routeId, setRouteId] = useState(routes.find(r => r.enabled !== false)?.id || '');
  const [scope, setScope] = useState('.'); const [acceptance, setAcceptance] = useState(''); const [commands, setCommands] = useState('');
  const [maxCalls, setMaxCalls] = useState(24); const [reserveCalls, setReserveCalls] = useState(4);
  const [lockRoute, setLockRoute] = useState(false); const [lockModel, setLockModel] = useState(false);
  const [lockEngine, setLockEngine] = useState(false); const [lockProvider, setLockProvider] = useState(false);
  const [includeChanges, setIncludeChanges] = useState(false);
  const [workspaceChoice, setWorkspaceChoice] = useState<WorkspaceChoice | null>(null);
  const [definitions, setDefinitions] = useState<AgentDefinition[]>([]); const [selectedDefinitions, setSelectedDefinitions] = useState<string[]>([]);
  const [definitionRefresh, setDefinitionRefresh] = useState(0);
  const [delegation, setDelegation] = useState(true); const [maxActive, setMaxActive] = useState(6); const [maxDepth, setMaxDepth] = useState(3); const [maxTotal, setMaxTotal] = useState(24);
  const [mcpTools, setMcpTools] = useState<{ serverId: string; serverName: string; name: string; description?: string; configVersion: number; definitionVersion: string; inputSchema: Record<string, unknown>; available: boolean; disabledReason?: string }[]>([]);
  const [mcpSelections, setMcpSelections] = useState<Record<string, { argumentsText: string; timeoutMs: number }>>({});
  const [mcpMaxCalls, setMcpMaxCalls] = useState(8); const [mcpMaxDurationMs, setMcpMaxDurationMs] = useState(240000);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  const route = routes.find(r => r.id === routeId);
  const project = projects.find(p => p.id === projectId);
  const [bindingId, setBindingId] = useState('');
  const availableBindings = bindings.filter(binding => binding.projectId === projectId && binding.enabled);
  const binding = availableBindings.find(binding => binding.id === bindingId) || availableBindings.find(binding => binding.id === project?.repositoryBindingId) || availableBindings[0];
  const executionNode = nodes.find(node => node.id === binding?.executionNodeId);
  useEffect(() => {
    let active = true;
    api<AgentDefinition[]>(`/agent-definitions?projectId=${encodeURIComponent(projectId)}`).then(value => {
      if (active) { const available = Array.isArray(value) ? value.filter(definition => definition.enabled) : []; setDefinitions(available); setSelectedDefinitions(available.filter(definition => !definition.routeId || definition.routeId === routeId).map(definition => definition.id)); }
    }).catch(() => { if (active) { setDefinitions([]); setSelectedDefinitions([]); } });
    return () => { active = false; };
  }, [projectId, routeId, definitionRefresh]);
  useEffect(() => {
    if (!projectId) { setMcpTools([]); return; }
    let active = true;
    api<{ tools: typeof mcpTools }>(`/mcp/task-tools?projectId=${encodeURIComponent(projectId)}`).then(result => { if (active) setMcpTools(result.tools || []); }).catch(() => { if (active) setMcpTools([]); });
    return () => { active = false; };
  }, [projectId]);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try {
      if (binding && (!workspaceChoice || workspaceChoice.bindingId !== binding.id || workspaceChoice.bindingVersion !== binding.version)) throw new Error('请先读取并确认当前仓库的有效启动提交与现场选择。');
      if (binding && !executionNode?.enabled) throw new Error('当前执行节点尚未启用，请刷新配置后再创建任务。');
      if (reserveCalls >= maxCalls) throw new Error('验证预留需要小于总调用预算，给开发阶段留出可用额度。');
      const mcpBindings = Object.entries(mcpSelections).map(([key, selected]) => {
        const tool = mcpTools.find(item => `${item.serverId}:${item.name}` === key);
        if (!tool) throw new Error('已选 MCP 工具列表已变化，请重新选择。');
        let fixedArguments: unknown;
        try { fixedArguments = JSON.parse(selected.argumentsText); } catch { throw new Error(`${tool.serverName}/${tool.name} 的固定参数不是有效 JSON。`); }
        if (!fixedArguments || typeof fixedArguments !== 'object' || Array.isArray(fixedArguments)) throw new Error(`${tool.serverName}/${tool.name} 的固定参数必须是 JSON 对象。`);
        return { serverId: tool.serverId, name: tool.name, configVersion: tool.configVersion, definitionVersion: tool.definitionVersion, fixedArguments, timeoutMs: selected.timeoutMs };
      });
      if (mcpBindings.length && route?.engine !== 'API') throw new Error('MCP 固定动作目前只支持 API 运行路由。');
      const collaboration = { enabled: delegation && route?.engine === 'API' && selectedDefinitions.length > 0, definitionIds: selectedDefinitions, definitionVersions: Object.fromEntries(definitions.filter(definition => selectedDefinitions.includes(definition.id)).map(definition => [definition.id, definition.version])), maxActive, maxDepth, maxTotal, allowedRouteIds: definitions.filter(definition => selectedDefinitions.includes(definition.id) && definition.routeId).map(definition => definition.routeId!) };
      const task = await post<Task>('/tasks', { projectId, ...(binding && executionNode ? { repositoryBindingId: binding.id, bindingVersion: binding.version, nodeVersion: executionNode.version } : {}), ...(workspaceChoice ? { workspaceSelection: workspaceChoice.input } : {}), ...(sessionId ? { sessionId } : {}), title, goal, mode, routeId, contract: { scope: lines(scope), acceptance: lines(acceptance), commands: lines(commands) }, budget: { maxCalls, reserveCalls }, collaboration, mcpBindings, mcpBudget: { maxCalls: mcpMaxCalls, maxDurationMs: mcpMaxDurationMs }, locks: { ...(lockRoute ? { routeId } : {}), ...(lockModel && route?.model ? { model: route.model } : {}), ...(lockEngine && route ? { engine: route.engine } : {}), ...(lockProvider && route?.provider ? { provider: route.provider } : {}) }, includeChanges: workspaceChoice?.includeChanges ?? includeChanges });
      onCreated(task); onClose();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal wide title="创建研发任务" description="先明确目标和完成约定，再确认平台生成的协作计划。" onClose={onClose}><form onSubmit={submit}><div className="form-body">
    <div className="form-grid"><Field label="所属项目"><select disabled={!!sessionId} value={projectId} onChange={e => setProjectId(e.target.value)} required>{projects.map(p => <option value={p.id} key={p.id}>{p.name}</option>)}</select></Field><Field label="任务名称"><input required value={title} onChange={e => setTitle(e.target.value)} placeholder="给这次工作一个清晰的名称" maxLength={180} /></Field></div>
    {binding && <><Field label="执行仓库绑定"><select value={binding.id} onChange={event => setBindingId(event.target.value)}>{availableBindings.map(binding => <option key={binding.id} value={binding.id}>{binding.name} · v{binding.version}</option>)}</select></Field><p className="form-note execution-source">{executionNode?.name || binding.executionNodeId} · 节点 v{executionNode?.version} · <code>{binding.path}</code></p></>}
    {binding && <WorkspacePicker key={binding.id} bindingId={binding.id} bindingVersion={binding.version} onChange={setWorkspaceChoice} />}
    <Field label="你希望完成什么？"><textarea className="goal-input" required rows={3} value={goal} onChange={e => setGoal(e.target.value)} placeholder="描述用户行为、现有问题、预期结果，以及需要遵守的约束…" /></Field>
    <Field label="协作方式"><div className="mode-picker">{[
      { id: 'AUTO', title: '自动选择', sub: '根据任务推荐', icon: Sparkles }, { id: 'LEAD', title: '主副协作', sub: '调查、拆分与实现', icon: Network },
      { id: 'ROUNDTABLE', title: '圆桌讨论', sub: '独立提案与质疑', icon: Bot }, { id: 'PIPELINE', title: '研发流水线', sub: '明确阶段与门禁', icon: Layers3 },
    ].map(m => <button type="button" key={m.id} className={`mode-choice ${mode === m.id ? 'selected' : ''}`} onClick={() => setMode(m.id)}><m.icon size={20} /><strong>{m.title}</strong><small>{m.sub}</small>{mode === m.id && <Check size={13} className="mode-check" />}</button>)}</div></Field>
    <div className="form-divider"><ShieldCheck size={16} /><span>完成约定</span><small>在执行前固定，作为验证与审查依据</small></div>
    <div className="form-grid"><Field label="验收条件" hint="每行一项，描述可以验证的结果。"><textarea required rows={4} value={acceptance} onChange={e => setAcceptance(e.target.value)} placeholder={'目标功能在正常输入下可用\n边界与错误场景有明确处理\n通过现有回归检查'} /></Field><Field label="必要验证命令" hint="每行一条，在任务工作目录内执行。"><textarea required rows={4} value={commands} onChange={e => setCommands(e.target.value)} placeholder={'npm test\nnpm run build'} className="mono" /></Field></div>
    <Field label="允许修改的范围" hint="项目内相对路径，每行一项；“.” 表示整个项目。具体运行器的执行边界以能力探测为准。"><textarea required rows={2} className="mono" value={scope} onChange={e => setScope(e.target.value)} /></Field>
    <div className="form-divider"><Network size={16} /><span>MCP 固定动作</span><small>只在实现与修复阶段调用，动作可能产生外部副作用</small></div>
    {mcpTools.length === 0 ? <div className="info-box"><TriangleAlert size={18} /><div><b>当前项目没有可用的已探测工具</b><p>在 MCP 设置中授权服务与工具并完成探测后，可返回此处选择。</p></div></div> : <div className="form-stack">
      {mcpTools.map(tool => {
        const key = `${tool.serverId}:${tool.name}`; const selected = mcpSelections[key];
        return <div className="checkbox-card" key={key} style={{ display: 'block' }}>
          <label><input type="checkbox" disabled={!tool.available || route?.engine !== 'API'} checked={Boolean(selected)} onChange={event => setMcpSelections(current => { const next = { ...current }; if (event.target.checked) next[key] = { argumentsText: '{}', timeoutMs: 30000 }; else delete next[key]; return next; })} />
            <span><b>{tool.serverName} / {tool.name}</b><small>{tool.available ? tool.description || '无工具描述' : tool.disabledReason || '不可用'}{route?.engine !== 'API' ? ' · 当前路由不支持' : ''}</small></span></label>
          {selected && <div className="form-grid" style={{ marginTop: 12 }}><Field label="工具参数定义"><pre className="evidence-output">{JSON.stringify(tool.inputSchema, null, 2)}</pre></Field><Field label="固定 JSON 参数"><textarea className="mono" rows={3} value={selected.argumentsText} onChange={event => setMcpSelections(current => ({ ...current, [key]: { ...current[key], argumentsText: event.target.value } }))} /></Field><Field label="单次超时（毫秒）"><input type="number" min={1000} max={60000} step={1000} value={selected.timeoutMs} onChange={event => setMcpSelections(current => ({ ...current, [key]: { ...current[key], timeoutMs: Number(event.target.value) } }))} /></Field></div>}
        </div>;
      })}
      <div className="form-grid"><Field label="MCP 最大调用数"><input type="number" min={1} max={64} value={mcpMaxCalls} onChange={event => setMcpMaxCalls(Number(event.target.value))} /></Field><Field label="MCP 总超时承诺（毫秒）"><input type="number" min={1000} max={3600000} step={1000} value={mcpMaxDurationMs} onChange={event => setMcpMaxDurationMs(Number(event.target.value))} /></Field></div>
    </div>}
    <div className="form-divider"><LockKeyhole size={16} /><span>执行资源与预算</span></div>
    <div className="form-grid three"><Field label="首选运行路由"><select required value={routeId} onChange={e => setRouteId(e.target.value)}><option value="">请选择运行器</option>{routes.filter(r => r.enabled !== false).map(r => <option key={r.id} value={r.id}>{r.name} · {r.engine}</option>)}</select></Field><Field label="总调用预算"><input type="number" min={2} max={1000} value={maxCalls} onChange={e => setMaxCalls(Number(e.target.value))} /></Field><Field label="验证与审查预留"><input type="number" min={1} max={999} value={reserveCalls} onChange={e => setReserveCalls(Number(e.target.value))} /></Field></div>
    <div className="checkbox-row"><label><input type="checkbox" checked={lockRoute} onChange={e => setLockRoute(e.target.checked)} />锁定此路由</label><label><input type="checkbox" checked={lockModel} disabled={!route?.model} onChange={e => setLockModel(e.target.checked)} />锁定此模型</label><label><input type="checkbox" checked={lockEngine} disabled={!route} onChange={e => setLockEngine(e.target.checked)} />锁定此引擎</label><label><input type="checkbox" checked={lockProvider} disabled={!route?.provider} onChange={e => setLockProvider(e.target.checked)} />锁定此供应商</label></div>
    <p className="form-note">CLI 按会话记录软预算；API 按实际请求累计。开发不能占用验证预留，故障切换遵守硬锁定。</p>
    <div className="form-divider"><Bot size={16} /><span>动态副 Agent 与团队</span><small>继承同一根预算、范围与硬锁定</small></div>
    {route?.engine === 'API' && <button type="button" className="text-button" onClick={() => { setDefinitionRefresh(current => current + 1); setError(''); }}>刷新副 Agent 定义</button>}
    <label className="checkbox-card"><input type="checkbox" disabled={route?.engine !== 'API' || !definitions.length} checked={delegation && route?.engine === 'API' && definitions.length > 0} onChange={event => setDelegation(event.target.checked)} /><span><b>允许在执行中创建副 Agent</b><small>{route?.engine === 'API' ? '副任务使用独立现场，父 Agent 等待时释放模型容量。以下选择同时确认可使用的定义和运行路由。' : '此运行器不能确认挂起，请使用 API 路由进行动态委派。'}</small></span></label>
    {delegation && route?.engine === 'API' && <><div className="checkbox-row">{definitions.map(definition => <label key={definition.id}><input type="checkbox" checked={selectedDefinitions.includes(definition.id)} onChange={event => setSelectedDefinitions(current => event.target.checked ? [...current, definition.id] : current.filter(id => id !== definition.id))} />{definition.name} v{definition.version} · {routes.find(value => value.id === definition.routeId)?.name || '根任务路由'}</label>)}</div><div className="form-grid three"><Field label="活动或排队 Agent 上限"><input type="number" min={2} max={6} value={maxActive} onChange={event => setMaxActive(Number(event.target.value))} /></Field><Field label="最大委派深度"><input type="number" min={1} max={4} value={maxDepth} onChange={event => setMaxDepth(Number(event.target.value))} /></Field><Field label="根累计委派上限"><input type="number" min={1} max={64} value={maxTotal} onChange={event => setMaxTotal(Number(event.target.value))} /></Field></div></>}
    {!binding && project?.dirty && <label className="checkbox-card"><input type="checkbox" checked={includeChanges} onChange={e => setIncludeChanges(e.target.checked)} /><span><b>带入当前未提交修改</b><small>在隔离副本中执行，保留原项目与暂存状态。实际支持范围由核心检查。</small></span></label>}
    <ErrorMessage error={error} />
    {!routes.some(r => r.enabled !== false) && <div className="form-error">请先在「资源与设置」中配置一个运行器。</div>}
  </div><footer><span className="footer-hint">创建后仍需确认计划才会执行</span><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>生成任务计划</SaveButton></footer></form></Modal>;
}

export function RouteDialog({ route, onClose, onSaved }: DialogProps & { route?: Route }) {
  const [name, setName] = useState(route?.name || ''); const [engine, setEngine] = useState<Route['engine']>(route?.engine || 'API');
  const [model, setModel] = useState(route?.model || ''); const [provider, setProvider] = useState(route?.provider || '');
  const [baseUrl, setBaseUrl] = useState(route?.baseUrl || 'https://api.openai.com/v1');
  const [executable, setExecutable] = useState(route?.executable || ''); const [apiKey, setApiKey] = useState('');
  const [enabled, setEnabled] = useState(route?.enabled !== false); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try { await post('/routes', { ...(route ? { id: route.id } : {}), name, engine, model, provider, baseUrl: engine === 'API' ? baseUrl : '', executable: engine === 'API' ? '' : executable || (engine === 'CODEX' ? 'codex' : 'claude'), enabled, ...(apiKey ? { apiKey } : {}) }); onSaved(); onClose(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal title={route ? '编辑运行路由' : '连接一个运行器'} description="配置已授权的模型 API 或本地编程工具。" onClose={onClose}><form onSubmit={submit}><div className="form-body">
    <div className="form-grid"><Field label="显示名称"><input required value={name} onChange={e => setName(e.target.value)} placeholder="例如：本地 Codex" /></Field><Field label="运行引擎"><select value={engine} onChange={e => setEngine(e.target.value as Route['engine'])}><option value="API">自研 Agent · API</option><option value="CODEX">Codex CLI</option><option value="CLAUDE">Claude Code</option></select></Field></div>
    <div className="form-grid"><Field label="模型" hint={engine === 'API' ? '填写端点支持的准确模型 ID。' : '留空使用工具自身配置。'}><input required={engine === 'API'} value={model} onChange={e => setModel(e.target.value)} placeholder="模型 ID" /></Field><Field label="供应商 / 资源组"><input value={provider} onChange={e => setProvider(e.target.value)} placeholder="例如：OpenAI / 本地账户" /></Field></div>
    {engine === 'API' ? <><Field label="API 地址"><input required type="url" value={baseUrl} onChange={e => setBaseUrl(e.target.value)} className="mono" /></Field><Field label="API 密钥" hint={route ? '留空保留本次会话已有密钥；重启后需重新提供。' : '仅在核心本次会话内保存，不写入普通配置。'}><input type="password" value={apiKey} onChange={e => setApiKey(e.target.value)} autoComplete="new-password" placeholder="sk-…" /></Field></> : <><Field label="工具可执行文件" hint="可填系统命令或绝对路径；需要事先安装并完成工具登录。"><input value={executable} onChange={e => setExecutable(e.target.value)} placeholder={engine === 'CODEX' ? 'codex' : 'claude'} className="mono" /></Field><div className="info-box"><ShieldCheck size={18} /><div><b>使用现有本地登录</b><p>连接探测会报告可观察能力。CLI 内部的模型次数与费用可能无法精确核算。</p></div></div></>}
    <label className="checkbox-row"><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />启用此路由</label><ErrorMessage error={error} />
  </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>保存运行器</SaveButton></footer></form></Modal>;
}

export function MemoryDialog({ memory, projectId, onClose, onSaved }: DialogProps & { memory?: Memory; projectId: string }) {
  const [kind, setKind] = useState(memory?.kind || 'FACT'); const [content, setContent] = useState(memory?.content || ''); const [source, setSource] = useState(memory?.source || '用户确认');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try { if (memory) await api(`/memories/${memory.id}`, { method: 'PATCH', body: JSON.stringify({ content }) }); else await post('/memories', { projectId, kind, content, source }); onSaved(); onClose(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal title={memory ? '修订项目记忆' : '添加项目记忆'} description="明确资料来源，让后续任务可以核验和复用。" onClose={onClose}><form onSubmit={submit}><div className="form-body">
    <Field label="知识类型"><select value={kind} disabled={!!memory} onChange={e => setKind(e.target.value)}><option value="FACT">事实</option><option value="DECISION">决策</option><option value="EXPERIENCE">经验</option><option value="RULE">项目规则</option></select></Field>
    <Field label="内容"><textarea required rows={6} value={content} onChange={e => setContent(e.target.value)} placeholder="记录具体结论、适用条件与必要的限制…" /></Field>
    <Field label="来源 / 证据" hint="例如：文档路径、测试记录或用户决策。"><input required disabled={!!memory} value={source} onChange={e => setSource(e.target.value)} /></Field><ErrorMessage error={error} />
  </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>保存记忆</SaveButton></footer></form></Modal>;
}

export function ProfileDialog({ profile, onClose, onSaved }: DialogProps & { profile?: Profile }) {
  const [name, setName] = useState(profile?.name || ''); const [role, setRole] = useState(profile?.role || '');
  const [instructions, setInstructions] = useState(profile?.instructions || ''); const [capabilities, setCapabilities] = useState(profile?.capabilities?.join('\n') || 'read');
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try { await post('/profiles', { ...(profile ? { id: profile.id } : {}), name, role, instructions, capabilities: lines(capabilities) }); onSaved(); onClose(); }
    catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal title={profile ? '创建职责模板新版本' : '新建职责模板'} description="职责与模型分开配置，进行中的任务保持原模板版本。" onClose={onClose}><form onSubmit={submit}><div className="form-body">
    <div className="form-grid"><Field label="模板名称"><input required value={name} onChange={e => setName(e.target.value)} placeholder="例如：接口审查者" /></Field><Field label="角色标识"><input required value={role} onChange={e => setRole(e.target.value)} placeholder="例如：reviewer" /></Field></div>
    <Field label="职责与交付要求"><textarea required rows={7} value={instructions} onChange={e => setInstructions(e.target.value)} placeholder="说明责任、输入输出、自检要求及需要升级给用户的情况。" /></Field>
    <Field label="所需能力" hint="每行一项。能力需求不会增加任务的实际授权。"><textarea required rows={3} value={capabilities} onChange={e => setCapabilities(e.target.value)} className="mono" /></Field><ErrorMessage error={error} />
  </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>保存模板版本</SaveButton></footer></form></Modal>;
}

export function ConnectionDialog({ onClose, onSaved }: DialogProps) {
  const current = currentConnection(); const [baseUrl, setBaseUrl] = useState(current.baseUrl); const [token, setToken] = useState(current.token);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function submit(e: FormEvent) {
    e.preventDefault(); setBusy(true); setError('');
    try {
      setConnection({ baseUrl, token }); await api('/state'); onSaved(); onClose();
    } catch (e) { setError((e as Error).message); } finally { setBusy(false); }
  }
  return <Modal title="连接本地核心" description="桌面应用自动建立连接，独立浏览器可连接本机核心或同源代理。" onClose={onClose}><form onSubmit={submit}><div className="form-body">
    <Field label="本机核心地址" hint="同源 /api 代理可留空，也可填写代理前缀（例如 /workbench）。"><input value={baseUrl} onChange={e => setBaseUrl(e.target.value)} placeholder="http://127.0.0.1:18765" className="mono" /></Field>
    <Field label="访问令牌" hint="使用核心启动时生成的令牌；仅存于当前浏览器会话。"><input type="password" value={token} onChange={e => setToken(e.target.value)} autoComplete="off" /></Field><ErrorMessage error={error} />
  </div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy}>连接并验证</SaveButton></footer></form></Modal>;
}
