import { useCallback, useEffect, useRef, useState } from 'react';
import { Copy, Eye, EyeOff, KeyRound, Link2, LoaderCircle, RefreshCw, ShieldCheck, Trash2 } from 'lucide-react';
import { api, currentConnection, post } from './api';
import type { Project } from './types';
import { Badge, formatTime, SectionHeading, shortHash } from './ui';

interface AccessGrant {
  id: string;
  projectId: string;
  endpoint: string;
  createdAt?: string;
  scope: 'PROJECT_READ_ONLY';
  credentialStorage: 'SESSION_ONLY';
}

export function McpProjectAccess({ projects, connected, onNotice }: { projects: Project[]; connected: boolean; onNotice: (message: string, error?: boolean) => void }) {
  const [grants, setGrants] = useState<AccessGrant[]>([]);
  const [tokens, setTokens] = useState<Record<string, string>>({});
  const [revealed, setRevealed] = useState<Record<string, boolean>>({});
  const [busy, setBusy] = useState('');
  const [error, setError] = useState('');
  const mutationVersion = useRef(0);
  const origin = currentConnection().baseUrl || 'http://127.0.0.1:18765';
  const refresh = useCallback(async () => {
    const before = mutationVersion.current;
    try {
      const data = await api<AccessGrant[]>('/mcp/access');
      if (before !== mutationVersion.current) return;
      const active = Array.isArray(data) ? data : [];
      setGrants(active);
      setTokens(current => Object.fromEntries(Object.entries(current).filter(([id]) => active.some(grant => grant.id === id))));
      setError('');
    } catch (e) { setError((e as Error).message); }
  }, []);
  useEffect(() => {
    void refresh();
    const timer = setInterval(() => void refresh(), 15000);
    return () => clearInterval(timer);
  }, [refresh]);

  async function copy(text: string, message: string) {
    try { await navigator.clipboard.writeText(text); onNotice(message); }
    catch { onNotice('当前环境不能写入剪贴板，请显示后手动复制', true); }
  }
  async function create(project: Project) {
    mutationVersion.current += 1;
    setBusy(`create:${project.id}`);
    try {
      const response = await post<AccessGrant & { token: string }>('/mcp/access', { projectId: project.id });
      if (!response.token || response.scope !== 'PROJECT_READ_ONLY') throw new Error('核心未返回有效的项目只读令牌');
      mutationVersion.current += 1;
      const { token, ...metadata } = response;
      setGrants(old => [...old.filter(grant => grant.id !== metadata.id), metadata]);
      setTokens(old => ({ ...old, [metadata.id]: token }));
      onNotice('项目只读令牌已创建。仅本页面内可复制，核心重启后失效。');
    } catch (e) { onNotice((e as Error).message, true); }
    finally { setBusy(''); }
  }
  async function revoke(grant: AccessGrant) {
    mutationVersion.current += 1;
    setBusy(`revoke:${grant.id}`);
    try {
      await api(`/mcp/access/${encodeURIComponent(grant.id)}`, { method: 'DELETE' });
      mutationVersion.current += 1;
      setGrants(old => old.filter(item => item.id !== grant.id));
      setTokens(old => { const next = { ...old }; delete next[grant.id]; return next; });
      setRevealed(old => { const next = { ...old }; delete next[grant.id]; return next; });
      onNotice('该项目只读令牌已撤销');
    } catch (e) { onNotice((e as Error).message, true); }
    finally { setBusy(''); }
  }

  return <section className="card mcp-publish"><SectionHeading title="向外部工具提供项目上下文" subtitle="为每个项目创建独立的只读访问令牌，用于项目记忆、任务与成果查询。" action={<button className="icon-button" aria-label="刷新项目只读令牌" title="刷新令牌状态" onClick={() => void refresh()}><RefreshCw size={15} /></button>} />
    <div className="mcp-access-note"><ShieldCheck size={17} /><p>令牌仅限对应项目的只读 MCP 服务，不能访问其他项目或平台管理接口。令牌不写入浏览器存储，离开此页面后无法再次读取；核心重启后全部失效。</p></div>
    {error && <div className="form-error mcp-access-error" role="alert">{error}</div>}
    {projects.length ? <div className="mcp-project-access-list">{projects.map(project => {
      const projectGrants = grants.filter(grant => grant.projectId === project.id);
      const endpoint = `${origin}/api/mcp/rpc/${project.id}`;
      return <article className="mcp-project-access" key={project.id}><div className="mcp-access-heading"><div><Link2 size={15} /><h3>{project.name}</h3><Badge state="ACTIVE">仅此项目 · 只读</Badge></div><button className="button secondary small" disabled={!connected || !!busy} onClick={() => void create(project)}>{busy === `create:${project.id}` ? <LoaderCircle className="spin" size={14} /> : <KeyRound size={14} />}创建项目只读令牌</button></div><div className="mcp-access-endpoint"><code>{endpoint}</code><button className="icon-button" aria-label={`复制 ${project.name} 的 MCP 地址`} title="复制服务地址" onClick={() => void copy(endpoint, '项目只读 MCP 地址已复制')}><Copy size={14} /></button></div>
        {projectGrants.length ? <div className="mcp-token-list">{projectGrants.map(grant => <div className="mcp-token-entry" key={grant.id}><div className="mcp-token-meta"><span><KeyRound size={13} />令牌 {shortHash(grant.id)}</span><small>{formatTime(grant.createdAt)} · 核心重启后失效</small><button className="text-button danger-icon" aria-label={`撤销令牌 ${shortHash(grant.id)}`} disabled={!!busy} onClick={() => void revoke(grant)}>{busy === `revoke:${grant.id}` ? <LoaderCircle className="spin" size={13} /> : <Trash2 size={13} />}撤销</button></div>{tokens[grant.id] ? <div className="mcp-token-value"><input aria-label={`项目只读令牌 ${shortHash(grant.id)}`} readOnly type={revealed[grant.id] ? 'text' : 'password'} value={tokens[grant.id]} autoComplete="off" spellCheck={false} /><button className="icon-button" title={revealed[grant.id] ? '隐藏令牌' : '显示令牌'} aria-label={revealed[grant.id] ? '隐藏项目只读令牌' : '显示项目只读令牌'} onClick={() => setRevealed(old => ({ ...old, [grant.id]: !old[grant.id] }))}>{revealed[grant.id] ? <EyeOff size={14} /> : <Eye size={14} />}</button><button className="button secondary small" onClick={() => void copy(tokens[grant.id], '项目只读令牌已复制')}><Copy size={13} />复制只读令牌</button></div> : <p className="mcp-token-unavailable">令牌仍有效，值只在创建时返回。需要再次取得令牌时，可撤销此项并重新创建。</p>}</div>)}</div> : <p className="mcp-token-unavailable">尚未创建项目只读令牌。</p>}</article>;
    })}</div> : <p className="muted-text">导入项目后，可创建项目限定的只读访问令牌。</p>}
  </section>;
}
