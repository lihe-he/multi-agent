import { useCallback, useEffect, useState } from 'react';
import { Activity, LoaderCircle, Pencil, Plus, Plug, ShieldCheck, Terminal, TriangleAlert } from 'lucide-react';
import { api, post } from './api';
import type { Project } from './types';
import { Badge, Empty, Field, lines, Modal, SaveButton, SectionHeading, shortHash } from './ui';
import { McpProjectAccess } from './McpProjectAccess';

interface McpServer {
  id: string; name: string; transport: 'HTTP' | 'STDIO'; url?: string; command?: string; args?: string[];
  projectIds?: string[]; allowedTools?: string[]; enabled?: boolean; identity?: string; version?: number;
  health?: string; error?: string; hasToken?: boolean;
  discovery?: Discovery;
}
interface Discovery {
  available?: boolean; identity?: string; definitionVersion?: string; message?: string;
  tools?: { name: string; description?: string; binding?: string; authorized?: boolean; inputSchema?: unknown }[];
}

export function McpPanel({ projects, connected, onNotice }: { projects: Project[]; connected: boolean; onNotice: (message: string, error?: boolean) => void }) {
  const [servers, setServers] = useState<McpServer[]>([]); const [error, setError] = useState('');
  const [editing, setEditing] = useState<McpServer | 'new' | null>(null); const [probing, setProbing] = useState('');
  const refresh = useCallback(async () => {
    try { const data = await api<McpServer[]>('/mcp/servers'); setServers(Array.isArray(data) ? data : []); setError(''); }
    catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => { void refresh(); }, [refresh]);
  async function probe(server: McpServer) {
    setProbing(server.id);
    try { const discovery = await post<Discovery>(`/mcp/servers/${server.id}/probe`); await refresh(); onNotice(discovery.available ? '已完成服务初始化与工具发现' : discovery.message || 'MCP 服务不可用', !discovery.available); }
    catch (e) { onNotice((e as Error).message, true); } finally { setProbing(''); }
  }
  return <><div className="info-banner"><ShieldCheck size={19} /><span>服务身份、工具定义和项目授权分别记录。此预览版提供管理与真实探测，外部 MCP 尚未自动注入 Agent 工具循环。</span></div><SectionHeading title="已连接的 MCP 服务" subtitle="支持本地 stdio 和远程 HTTP，工具权限按项目显式配置。" action={<button className="button primary" disabled={!connected} onClick={() => setEditing('new')}><Plus size={16} />添加服务</button>} />
    {error && <div className="form-error"><TriangleAlert size={17} />{error}</div>}
    {servers.length ? <div className="route-grid">{servers.map(server => <article className="card route-card" key={server.id}><header><div className="engine-icon">{server.transport === 'STDIO' ? <Terminal size={23} /> : <Plug size={23} />}</div><div><h3>{server.name}</h3><span>{server.transport === 'STDIO' ? '本地 stdio' : '远程 Streamable HTTP'} · v{server.version || 1}</span></div><Badge state={server.enabled === false ? 'DISABLED' : server.health === 'AVAILABLE' ? 'ACTIVE' : server.health === 'UNAVAILABLE' ? 'FAILED' : 'PENDING'} /></header><dl className="route-metadata"><dt>服务位置</dt><dd className="mono">{server.transport === 'STDIO' ? server.command : server.url}</dd><dt>身份版本</dt><dd className="mono">{shortHash(server.identity)}</dd><dt>授权项目</dt><dd>{server.projectIds?.length ? server.projectIds.map(id => projects.find(p => p.id === id)?.name || id).join('、') : '尚未授权'}</dd><dt>授权工具</dt><dd>{server.allowedTools?.length ? server.allowedTools.join('、') : '尚未授权任何工具'}</dd></dl>{server.error && <div className="probe-result">{server.error}</div>}{server.discovery && <div className="mcp-discovery"><div><strong>已发现 {server.discovery.tools?.length || 0} 个工具</strong><code>{shortHash(server.discovery.definitionVersion)}</code></div>{server.discovery.tools?.map(tool => <details key={tool.name}><summary><span>{tool.name}</span><Badge state={tool.authorized ? 'ACTIVE' : 'DISABLED'}>{tool.authorized ? '已授权' : '未授权'}</Badge></summary><p>{tool.description || '服务未提供描述'}</p><code>{tool.binding}</code><pre>{JSON.stringify(tool.inputSchema || {}, null, 2)}</pre></details>)}</div>}<footer><button className="button secondary small" disabled={!!probing || server.enabled === false} onClick={() => probe(server)}>{probing === server.id ? <LoaderCircle className="spin" size={14} /> : <Activity size={14} />}发现工具</button><button className="text-button" onClick={() => setEditing(server)}>编辑授权<Pencil size={13} /></button></footer></article>)}</div> : !error && <section className="card"><Empty icon={<Plug size={31} />} title="把已有工具连接到工作台" description="先保存服务位置，再发现工具并为具体项目授权。" /></section>}
    <McpProjectAccess projects={projects} connected={connected} onNotice={onNotice} />
    {editing && <McpDialog server={editing === 'new' ? undefined : editing} projects={projects} onClose={() => setEditing(null)} onSaved={async () => { await refresh(); onNotice('MCP 服务配置已保存'); }} />}
  </>;
}

function McpDialog({ server, projects, onClose, onSaved }: { server?: McpServer; projects: Project[]; onClose: () => void; onSaved: () => Promise<void> }) {
  const [name, setName] = useState(server?.name || ''); const [transport, setTransport] = useState(server?.transport || 'HTTP');
  const [url, setUrl] = useState(server?.url || ''); const [command, setCommand] = useState(server?.command || '');
  const [args, setArgs] = useState(server?.args?.join('\n') || ''); const [allowedTools, setAllowedTools] = useState(server?.allowedTools?.join('\n') || '');
  const [projectIds, setProjectIds] = useState(server?.projectIds || []); const [token, setToken] = useState('');
  const [enabled, setEnabled] = useState(server?.enabled !== false); const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  return <Modal title={server ? '编辑 MCP 服务与授权' : '添加 MCP 服务'} description="探测不会调用业务工具，发现工具后可再次编辑授权清单。" onClose={onClose}><form onSubmit={async event => { event.preventDefault(); setBusy(true); setError(''); try { await post('/mcp/servers', { ...(server ? { id: server.id } : {}), name, transport, url: transport === 'HTTP' ? url : '', command: transport === 'STDIO' ? command : '', args: transport === 'STDIO' ? lines(args) : [], projectIds, allowedTools: lines(allowedTools), enabled, ...(token ? { token } : {}) }); await onSaved(); onClose(); } catch (e) { setError((e as Error).message); } finally { setBusy(false); } }}><div className="form-body"><div className="form-grid"><Field label="服务名称"><input required value={name} onChange={e => setName(e.target.value)} placeholder="例如：项目资料库" /></Field><Field label="传输方式"><select value={transport} onChange={e => setTransport(e.target.value as 'HTTP' | 'STDIO')}><option value="HTTP">远程 HTTP</option><option value="STDIO">本地 stdio</option></select></Field></div>
      {transport === 'HTTP' ? <><Field label="MCP 服务地址"><input required type="url" value={url} onChange={e => setUrl(e.target.value)} placeholder="https://example.com/mcp" className="mono" /></Field><Field label="服务访问令牌" hint="仅本次会话保存；留空保留已有令牌。"><input type="password" value={token} onChange={e => setToken(e.target.value)} autoComplete="new-password" /></Field></> : <><Field label="可执行命令"><input required value={command} onChange={e => setCommand(e.target.value)} className="mono" placeholder="例如：node" /></Field><Field label="启动参数" hint="每行一个参数，无需额外添加引号。"><textarea rows={3} value={args} onChange={e => setArgs(e.target.value)} className="mono" placeholder={'D:\\tools\\mcp-server.mjs'} /></Field></>}
      <div className="field"><span>允许使用的项目</span><div className="mcp-project-options">{projects.length ? projects.map(project => <label key={project.id}><input type="checkbox" checked={projectIds.includes(project.id)} onChange={e => setProjectIds(old => e.target.checked ? [...old, project.id] : old.filter(id => id !== project.id))} />{project.name}</label>) : <p className="muted-text">请先导入项目；当前可仅保存与探测服务。</p>}</div></div>
      <Field label="允许调用的工具名称" hint="每行一个准确名称。留空表示未授权业务工具调用。"><textarea rows={3} className="mono" value={allowedTools} onChange={e => setAllowedTools(e.target.value)} placeholder="先发现工具，再填写需要授权的名称" /></Field><label className="checkbox-row"><input type="checkbox" checked={enabled} onChange={e => setEnabled(e.target.checked)} />启用服务</label>{error && <div className="form-error" role="alert">{error}</div>}
    </div><footer><button className="button secondary" type="button" onClick={onClose}>取消</button><SaveButton busy={busy}>保存服务配置</SaveButton></footer></form></Modal>;
}
