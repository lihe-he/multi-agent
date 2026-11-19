import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { ArrowDownToLine, GitBranch, Plus, Upload } from 'lucide-react';
import { api, post } from './api';
import type { AgentDefinition, Project } from './types';
import { Badge, Empty, Field, formatTime, Modal, SaveButton, SectionHeading, shortHash } from './ui';
import { WorkflowNodes, workflowPath, workflowVersionPath } from './WorkflowPicker';
import type { WorkflowDefinition, WorkflowDsl, WorkflowPage, WorkflowRevision, WorkflowVersion } from './workflow-types';

export type WorkflowSaved = { definition: WorkflowDefinition; version: WorkflowVersion };
type Validation = { valid: boolean; errors: string[]; order?: string[]; dsl?: WorkflowDsl };
type EditValues = { name: string; projectId: string; enabled: boolean; dsl: unknown };
const sourceLabels: Record<string, string> = { BUILTIN: '内置模板', USER: '本地编辑', IMPORT: '导入', ROLLBACK: '历史回退', GENERATED: '模型候选', GENERATED_EDIT: '编辑模型候选' };
const json = (value: unknown) => JSON.stringify(value, null, 2);
const maxJsonBytes = 1024 * 1024;

export function WorkflowLibrary({ projects, definitions: actors, connected, onNotice }: {
  projects: Project[]; definitions: AgentDefinition[]; connected: boolean; onNotice: (message: string) => void;
}) {
  const [projectId, setProjectId] = useState('');
  const [items, setItems] = useState<WorkflowDefinition[]>([]);
  const [selected, setSelected] = useState<WorkflowDefinition | null>(null);
  const [editor, setEditor] = useState<{ definition?: WorkflowDefinition; version?: WorkflowVersion; projectId: string } | null>(null);
  const [importing, setImporting] = useState(false);
  const [refresh, setRefresh] = useState(0);
  const [loaded, setLoaded] = useState(false);
  const [error, setError] = useState('');
  useEffect(() => {
    if (!connected) return;
    const controller = new AbortController(); setError(''); setLoaded(false);
    api<WorkflowDefinition[]>(`/workflows?projectId=${encodeURIComponent(projectId)}`, { signal: controller.signal })
      .then(data => { if (!controller.signal.aborted) { setItems(data); setLoaded(true); } })
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [connected, projectId, refresh]);
  function saved(result: WorkflowSaved, message: string) {
    setEditor(null); setImporting(false); setSelected(null);
    if (projectId && result.definition.projectId !== projectId) setProjectId(result.definition.projectId);
    setRefresh(value => value + 1); onNotice(`${message}：${result.definition.name} v${result.version.version}`);
  }
  return <section className="workflow-stack" aria-label="流程模板">
    <div className="agent-run-heading"><div><h2>流程模板</h2><p className="form-note">编辑节点依赖、职责和调用上限。每次发布保留原版本，已有任务继续使用固定版本。</p></div></div>
    <div className="workflow-inline"><Field label="流程项目范围"><select value={projectId} onChange={event => { setItems([]); setProjectId(event.target.value); }}>
      <option value="">所有项目与全局模板</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
    </select></Field><div className="workflow-actions">
      <button className="button primary small" disabled={!connected} onClick={() => setEditor({ projectId })}><Plus size={15} />新建流程</button>
      <button className="button secondary small" disabled={!connected} onClick={() => setImporting(true)}><Upload size={15} />导入流程</button>
      <button className="button secondary small" disabled={!connected} onClick={() => setRefresh(value => value + 1)}>刷新流程</button>
    </div></div>
    {error && <p className="form-error" role="alert">{error}</p>}
    {connected && !error && !loaded && <p className="form-note" role="status">正在读取流程模板…</p>}
    {loaded && !items.length && <div className="card"><Empty icon={<GitBranch size={26} />} title="当前范围没有流程" description="新建流程或导入已导出的 JSON 文件。" /></div>}
    <div className="workflow-card-list">{items.map(definition => <article className="card workflow-card" key={definition.id}>
      <div className="workflow-actions"><span className="chip">v{definition.version}</span><Badge state={definition.enabled ? 'ACTIVE' : 'DISABLED'} />{definition.builtin && <span className="chip">内置</span>}</div>
      <h3>{definition.name}</h3><p className="workflow-meta">{definition.projectId ? projects.find(project => project.id === definition.projectId)?.name || definition.projectId : '全局可选'}</p>
      <button className="button secondary small" disabled={!connected} onClick={() => setSelected(definition)}>查看版本与编辑</button>
    </article>)}</div>
    {selected && <VersionDialog definition={selected} onClose={() => setSelected(null)} onEdit={(version, copy) => {
      setEditor({ definition: copy ? undefined : selected, version, projectId: selected.projectId }); setSelected(null);
    }} onSaved={value => saved(value, '已用历史内容发布新版本')} />}
    {editor && <WorkflowEditor title={editor.definition ? '编辑流程新版本' : '新建流程'} projects={projects} actors={actors} projectId={editor.projectId}
      version={editor.version} definition={editor.definition} onClose={() => setEditor(null)} onSaved={value => saved(value, '已发布流程')} />}
    {importing && <ImportWorkflow projects={projects} projectId={projectId} onClose={() => setImporting(false)} onSaved={value => saved(value, '已导入流程')} />}
  </section>;
}

function VersionDialog({ definition, onClose, onEdit, onSaved }: {
  definition: WorkflowDefinition; onClose: () => void; onEdit: (version: WorkflowVersion, copy: boolean) => void; onSaved: (result: WorkflowSaved) => void;
}) {
  const [number, setNumber] = useState(definition.version);
  const [before, setBefore] = useState(0);
  const [revisions, setRevisions] = useState<WorkflowRevision[]>([]);
  const [older, setOlder] = useState(0);
  const [version, setVersion] = useState<WorkflowVersion | null>(null);
  const [revisionError, setRevisionError] = useState('');
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    const controller = new AbortController(); setRevisionError('');
    api<WorkflowPage<'versions', WorkflowRevision>>(`${workflowPath(definition.id)}/versions?before=${before}`, { signal: controller.signal })
      .then(page => { if (!controller.signal.aborted) { setRevisions(items => before ? [...items, ...page.versions] : page.versions); setOlder(page.hasMore ? page.next : 0); } })
      .catch(error => { if (!controller.signal.aborted) setRevisionError(String(error.message)); });
    return () => controller.abort();
  }, [definition.id, before]);
  useEffect(() => {
    const controller = new AbortController(); setError(''); setVersion(null);
    api<WorkflowVersion>(workflowVersionPath(definition.id, number), { signal: controller.signal })
      .then(value => { if (!controller.signal.aborted) setVersion(value); })
      .catch(error => { if (!controller.signal.aborted) setError(String(error.message)); });
    return () => controller.abort();
  }, [definition.id, number]);
  async function exportVersion() {
    setBusy(true); setError('');
    try {
      const bundle = await api<unknown>(`${workflowVersionPath(definition.id, number)}/export`);
      const url = URL.createObjectURL(new Blob([json(bundle)], { type: 'application/json' }));
      const anchor = document.createElement('a'); anchor.href = url; anchor.download = `workflow-${definition.id}-v${number}.json`; anchor.click();
      window.setTimeout(() => URL.revokeObjectURL(url), 1000);
    } catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  async function rollback() {
    if (!window.confirm(`以 v${number} 的内容发布为新版本？已运行任务继续保留原版本。`)) return;
    setBusy(true); setError('');
    try { onSaved(await post<WorkflowSaved>(`${workflowPath(definition.id)}/rollback`, { expectedVersion: definition.version, version: number })); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  return <Modal wide title={`流程版本 · ${definition.name}`} onClose={() => { if (!busy) onClose(); }}><div className="form-body workflow-stack">
    <div className="workflow-inline"><Field label="历史版本"><select disabled={busy} value={number} onChange={event => { setVersion(null); setNumber(Number(event.target.value)); }}>
      {[definition.version, ...revisions.filter(item => item.version !== definition.version).map(item => item.version)].map(value => {
        const revision = revisions.find(item => item.version === value);
        return <option key={value} value={value}>v{value}{value === definition.version ? ' · 当前' : ''}{revision ? ` · ${sourceLabels[revision.source] || revision.source} · ${formatTime(revision.createdAt)}` : ''}</option>;
      })}
    </select></Field>{older > 0 && <button className="text-button" disabled={older === before} onClick={() => setBefore(older)}>读取更早版本</button>}</div>
    {!version && !error && <p className="form-note" role="status">正在核对流程版本…</p>}
    {version && <><p className="workflow-meta">{sourceLabels[version.source] || version.source} · {formatTime(version.createdAt)} · 摘要 <code title={version.digest}>{shortHash(version.digest)}</code></p>
      <WorkflowNodes dsl={version.dsl} /><details className="workflow-record"><summary>查看完整 DSL 与职责版本</summary><div className="workflow-stack"><pre className="evidence-output workflow-json">{json(version.dsl)}</pre>
        <p className="workflow-meta">{version.agentDefinitions.map(actor => `${actor.name} v${actor.version}`).join('、')}</p></div></details></>}
    {(error || revisionError) && <p className="form-error" role="alert">{error || revisionError}</p>}
    <div className="workflow-actions">
      <button className="button primary small" disabled={!version || busy} onClick={() => version && onEdit(version, false)}>编辑为新版本</button>
      <button className="button secondary small" disabled={!version || busy} onClick={() => version && onEdit(version, true)}>复制为新流程</button>
      <button className="button secondary small" disabled={!version || busy} onClick={exportVersion}><ArrowDownToLine size={14} />导出此版本</button>
      {number !== definition.version && <button className="button secondary small" disabled={!version || busy} onClick={rollback}>按此版本回退</button>}
    </div>
  </div></Modal>;
}

function starter(actor: AgentDefinition) {
  return { dslVersion: 1, inputs: ['goal', 'contract', 'context', 'baseline'], maxParallel: 1, nodes: [{
    id: 'investigate', kind: 'INVESTIGATE', title: '调查与交接', role: actor.role, goal: '调查任务目标，检索证据并交接可验证的发现。',
    definitionId: actor.id, definitionVersion: actor.version, dependencies: [], inputs: ['goal', 'contract', 'context', 'baseline'],
    outputs: ['investigate:result'], scope: actor.scope, permissions: ['read', ...(actor.capabilities.includes('search') ? ['search'] : [])],
    maxCalls: Math.min(actor.maxCalls, 4), timeoutSeconds: 300, acceptance: ['记录事实、证据和待验证内容'], termination: '完成、取消或到达时限',
  }] };
}

export function WorkflowEditor({ title, projects, actors, projectId: initialProject, definition, version, proposal, onClose, onSaved, publish }: {
  title: string; projects: Project[]; actors: AgentDefinition[]; projectId: string; definition?: WorkflowDefinition; version?: WorkflowVersion;
  proposal?: { name: string; dsl: WorkflowDsl }; onClose: () => void; onSaved: (result: WorkflowSaved) => void;
  publish?: (values: EditValues) => Promise<WorkflowSaved>;
}) {
  const [name, setName] = useState(proposal?.name || (definition ? version?.name || definition.name : version ? `${version.name} 副本`.slice(0, 160) : ''));
  const [projectId, setProjectId] = useState(initialProject);
  const [enabled, setEnabled] = useState(definition?.enabled ?? true);
  const [script, setScript] = useState(() => proposal || version ? json((proposal || version)?.dsl) : '');
  const [actorId, setActorId] = useState('');
  const [checked, setChecked] = useState<Validation | null>(null);
  const [error, setError] = useState('');
  const [busy, setBusy] = useState(false);
  const availableActors = actors.filter(actor => actor.enabled && actor.capabilities.includes('read') && (!actor.projectId || actor.projectId === projectId));
  function values(): EditValues {
    if (new Blob([script]).size > maxJsonBytes) throw new Error('流程 DSL 不能超过 1 MB。');
    let dsl: unknown;
    try { dsl = JSON.parse(script); } catch { throw new Error('流程 DSL 需要完整的 JSON 对象。'); }
    if (!dsl || typeof dsl !== 'object' || Array.isArray(dsl)) throw new Error('流程 DSL 需要完整的 JSON 对象。');
    return { name: name.trim(), projectId, enabled, dsl };
  }
  async function validate() {
    setBusy(true); setChecked(null); setError('');
    try { const { dsl, projectId } = values(); setChecked(await post<Validation>('/workflows/validate', { dsl, projectId })); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy || !checked?.valid || !name.trim()) return;
    setBusy(true); setError('');
    try {
      const value = values();
      const result = publish ? await publish(value) : await post<WorkflowSaved>('/workflows', { ...value, ...(definition ? { id: definition.id, expectedVersion: definition.version } : {}) });
      onSaved(result);
    } catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  return <Modal wide title={title} description="用 JSON 声明节点、依赖、范围和调用上限。发布后可为任务选择此版本。" onClose={() => { if (!busy) onClose(); }}>
    <form onSubmit={submit}><div className="form-body workflow-stack">
      <div className="form-grid"><Field label="流程名称"><input required maxLength={160} disabled={busy} value={name} onChange={event => setName(event.target.value)} /></Field>
        <Field label="流程归属"><select disabled={busy || !!definition || !!proposal} value={projectId} onChange={event => { setProjectId(event.target.value); setActorId(''); setChecked(null); }}>
          <option value="">全局可选</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}
        </select></Field></div>
      {!definition && !version && !proposal && <div className="workflow-inline"><Field label="起步节点职责"><select disabled={busy} value={actorId} onChange={event => setActorId(event.target.value)}>
        <option value="">选择一个可读职责</option>{availableActors.map(actor => <option key={actor.id} value={actor.id}>{actor.name} · {actor.id} v{actor.version}</option>)}
      </select></Field><button type="button" className="button secondary small" disabled={!actorId || !!script || busy} onClick={() => { const actor = availableActors.find(item => item.id === actorId); if (actor) { setScript(json(starter(actor))); setChecked(null); } }}>插入调查节点</button></div>}
      <Field label="流程 DSL（JSON）" hint="可从内置模板复制。校验会检查依赖环、职责版本、范围、能力和逻辑输入输出。"><textarea className="mono" rows={16} spellCheck={false} required disabled={busy} value={script} onChange={event => { setScript(event.target.value); setChecked(null); }} /></Field>
      {!proposal && <label className="checkbox-row"><input type="checkbox" disabled={busy} checked={enabled} onChange={event => setEnabled(event.target.checked)} />允许任务选择此流程</label>}
      {checked?.valid && <div className="workflow-stack" role="status"><p className="form-note">流程校验通过 · 执行顺序：{checked.order?.join(' → ')}</p>{checked.dsl && <WorkflowNodes dsl={checked.dsl} />}</div>}
      {(error || checked?.errors.length) ? <p className="form-error" role="alert">{error || checked?.errors.join('；')}</p> : null}
    </div><footer><button type="button" className="button secondary" disabled={busy} onClick={onClose}>取消</button><button type="button" className="button secondary" disabled={busy || !script.trim()} onClick={validate}>校验流程</button><SaveButton busy={busy} disabled={!checked?.valid || !name.trim()}>发布流程</SaveButton></footer></form>
  </Modal>;
}

function ImportWorkflow({ projects, projectId: initialProject, onClose, onSaved }: {
  projects: Project[]; projectId: string; onClose: () => void; onSaved: (result: WorkflowSaved) => void;
}) {
  const [projectId, setProjectId] = useState(initialProject);
  const [bundle, setBundle] = useState<{ name: string; dsl: unknown; digest?: string } | null>(null);
  const [error, setError] = useState(''); const [busy, setBusy] = useState(false);
  async function importFile(file?: File) {
    setError(''); setBundle(null);
    if (!file) return;
    setBusy(true);
    try {
      if (file.size > maxJsonBytes) throw new Error('流程文件不能超过 1 MB。');
      const value = JSON.parse(await file.text());
      if (!value || value.formatVersion !== 1 || typeof value.name !== 'string' || !value.dsl || typeof value.dsl !== 'object') throw new Error('请选择工作台导出的流程 JSON 文件。');
      setBundle(value);
    } catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!bundle || busy) return;
    setBusy(true); setError('');
    try { onSaved(await post<WorkflowSaved>('/workflows/import', { bundle, projectId })); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  return <Modal wide title="导入流程" description="导入使用本机已有且内容一致的职责版本，发布后仍需在任务中确认授权。" onClose={() => { if (!busy) onClose(); }}><form onSubmit={submit}>
    <div className="form-body workflow-stack"><Field label="流程 JSON 文件"><input type="file" accept=".json,application/json" disabled={busy} onChange={event => void importFile(event.target.files?.[0])} /></Field>
      <Field label="导入目标"><select disabled={busy} value={projectId} onChange={event => setProjectId(event.target.value)}><option value="">全局可选</option>{projects.map(project => <option key={project.id} value={project.id}>{project.name}</option>)}</select></Field>
      {bundle && <><b>{bundle.name}</b><p className="workflow-meta">摘要 <code>{shortHash(bundle.digest)}</code></p><pre className="evidence-output workflow-json">{json(bundle.dsl)}</pre></>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </div><footer><button type="button" className="button secondary" disabled={busy} onClick={onClose}>取消</button><SaveButton busy={busy} disabled={!bundle}>校验并导入</SaveButton></footer>
  </form></Modal>;
}
