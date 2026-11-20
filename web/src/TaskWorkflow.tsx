import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { api, post } from './api';
import { startPagePolling } from './page-polling';
import type { AgentDefinition, Project, Route, Task } from './types';
import { Badge, Field, formatTime, Modal, SaveButton, SectionHeading, shortHash } from './ui';
import { WorkflowEditor } from './WorkflowLibrary';
import type { WorkflowSaved } from './WorkflowLibrary';
import { currentWorkflowSelection, WorkflowNodes, WorkflowPicker } from './WorkflowPicker';
import type { ReviewedWorkflowSelection } from './WorkflowPicker';
import type { WorkflowAttempt, WorkflowGeneration, WorkflowPage, WorkflowProposal, WorkflowRun } from './workflow-types';

const editableStates = new Set(['DRAFT', 'PAUSED', 'WAITING_INPUT', 'FAILED', 'AWAITING_ACCEPTANCE']);
const states: Record<string, string> = { VALIDATED: '待人工发布', INVALID: '校验未通过', PUBLISHED: '已发布', PREPARING: '准备基线', INTEGRATED: '已整合', NOT_INTEGRATED: '未整合' };
const rootOf = (id: string) => `/tasks/${encodeURIComponent(id)}`;
const fields = (task: Task) => ({ expectedVersion: task.version || '', expectedSnapshotDigest: task.workflow?.snapshotDigest || '', expectedRunId: task.workflowRunId || '' });

function WorkflowBadge({ state }: { state: string }) {
  return <Badge state={state === 'INVALID' ? 'FAILED' : state === 'PUBLISHED' || state === 'INTEGRATED' ? 'SUCCEEDED' : state}>{states[state]}</Badge>;
}

function useWorkflowPage<K extends string, T extends { id: string }>(path: string, key: K, revision: number) {
  const [latest, setLatest] = useState<WorkflowPage<K, T> | null>(null);
  const [previous, setPrevious] = useState<WorkflowPage<K, T> | null>(null);
  const [items, setItems] = useState<T[]>([]);
  const [error, setError] = useState(''); const [moreError, setMoreError] = useState('');
  const [before, setBefore] = useState(0); const [loading, setLoading] = useState(false);
  useEffect(() => startPagePolling(async signal => {
    const result = await api<WorkflowPage<K, T>>(`${path}?limit=15`, { signal });
    if (!signal.aborted) { setLatest(result); setError(''); }
  }, 3000, { onError: error => setError((error as Error).message) }), [path, revision]);
  useEffect(() => {
    if (!before) return;
    const controller = new AbortController(); setLoading(true); setMoreError('');
    api<WorkflowPage<K, T>>(`${path}?before=${before}&limit=15`, { signal: controller.signal })
      .then(page => { if (!controller.signal.aborted) { setPrevious(page); setItems(current => [...current, ...page[key]]); } })
      .catch(error => { if (!controller.signal.aborted) { setMoreError(error.message); setBefore(0); } })
      .finally(() => { if (!controller.signal.aborted) setLoading(false); });
    return () => controller.abort();
  }, [path, before]);
  const page = previous || latest;
  const current = latest?.[key] || [];
  const currentIds = new Set(current.map(item => item.id));
  return { items: [...current, ...new Map(items.filter(item => !currentIds.has(item.id)).map(item => [item.id, item])).values()], loading, error: error || moreError, ready: !!latest, more: page?.hasMore ? () => setBefore(page.next) : undefined };
}

export function TaskWorkflow({ task, project, routes, onChanged, onSelectionSaved, onNotice, onEvidence, onRequest }: {
  task: Task; project?: Project; routes: Route[]; onChanged: (task: Task) => void; onNotice: (message: string) => void;
  onSelectionSaved: () => void; onEvidence: (id: string) => void; onRequest: (id: string) => void;
}) {
  const [dialog, setDialog] = useState<'select' | 'generate' | null>(null);
  const [proposalId, setProposalId] = useState(''); const [runId, setRunId] = useState('');
  const [generations, setGenerations] = useState<WorkflowGeneration[]>([]);
  const [error, setError] = useState(''); const [refresh, setRefresh] = useState(0);
  const root = rootOf(task.id);
  const runs = useWorkflowPage<'runs', WorkflowRun>(`${root}/workflow-runs`, 'runs', refresh);
  const proposals = useWorkflowPage<'proposals', WorkflowProposal>(`${root}/workflow-proposals`, 'proposals', refresh);
  const route = task.routeSnapshot || routes.find(route => route.id === task.routeId);
  const settled = !task.pendingWorkflowGenerationId && !['QUEUED', 'RUNNING'].includes(task.taskExecution?.state || '') && !['PENDING', 'CLAIMED'].includes(task.dispatch?.state || '');
  const restart = !!(task.workspace || task.workflowRunId);
  const selectedRun = runs.items.find(run => run.id === (runId || task.workflowRunId)) || runs.items[0];
  useEffect(() => startPagePolling(async signal => {
    const items = await api<WorkflowGeneration[]>(`${root}/workflow-generations`, { signal });
    if (!signal.aborted) { setGenerations(items); setError(''); }
  }, 3000, { onError: error => setError((error as Error).message) }), [root, refresh]);
  function changed(value: Task) { onChanged(value); setDialog(null); setRefresh(x => x + 1); }
  return <section className="workflow-stack" aria-label="流程协作">
    <section className="card"><SectionHeading title="任务固定流程" subtitle={task.workflow ? `${task.workflow.name} · v${task.workflow.version} · 摘要 ${shortHash(task.workflow.digest)}` : '此任务当前使用默认协作计划。'} />
      <div className="workflow-stack workflow-list">
        {task.workflow?.dsl && <WorkflowNodes dsl={task.workflow.dsl} />}
        {!!task.workflow?.bindings?.length && <details className="workflow-record"><summary>实际节点授权与路由</summary><div className="workflow-stack">{task.workflow.bindings.map(binding => <p className="workflow-meta" key={binding.nodeId}>
          <b>{binding.nodeId}</b> · {binding.actor.name} v{binding.actor.version} · {binding.routeSnapshot.name} · {binding.permissions.capabilities.join('、')}
          <br />读取范围：{binding.permissions.readScopeLayers.map(layer => layer.join('、')).join(' ∩ ')} · 写入范围：{binding.permissions.writeScopeLayers.map(layer => layer.join('、')).join(' ∩ ') || '无'}
        </p>)}</div></details>}
        {!!task.workflowHistory?.length && <details className="workflow-record"><summary>流程换版记录</summary><div className="workflow-stack">{task.workflowHistory.map((item, index) => <p className="workflow-meta" key={index}>{item.snapshot.name} v{item.snapshot.version} · {formatTime(item.createdAt)} {item.note && `· ${item.note}`}</p>)}</div></details>}
        {route?.engine === 'API' ? <div className="workflow-actions">
          <button className="button secondary small" disabled={!settled || !editableStates.has(task.state)} onClick={() => setDialog('select')}>{restart ? '选择版本并重新开始' : '选择任务流程'}</button>
          <button className="button secondary small" disabled={!settled || !(editableStates.has(task.state) || task.state === 'COMPLETED')} onClick={() => setDialog('generate')}>生成流程候选</button>
        </div> : <p className="form-note">使用 API 运行路由可运行版本化流程或生成候选。</p>}
        {task.pendingWorkflowGenerationId && <p className="form-note" role="status">只读生成已排队或正在执行。可使用任务顶部的暂停按钮停止生成。</p>}
      </div>
    </section>
    <section className="card"><SectionHeading title="流程运行与节点尝试" subtitle="按运行代次查看前置输入、原节点结果与整合记录。" />
      <div className="workflow-list workflow-stack">
        {selectedRun ? <><Field label="流程运行代次"><select value={selectedRun.id} onChange={event => setRunId(event.target.value)}>{runs.items.map(run => <option value={run.id} key={run.id}>第 {run.generation} 代 · 流程 v{run.definitionVersion} · {formatTime(run.createdAt)}</option>)}</select></Field>
          <div className="workflow-actions"><WorkflowBadge state={selectedRun.state} /><p className="workflow-meta">基线 <code>{shortHash(selectedRun.baselineVersion)}</code> · 现场 <code>{shortHash(selectedRun.rootVersion)}</code> {selectedRun.reason && `· ${selectedRun.reason}`}</p></div>
          <div className="workflow-actions" aria-label="节点状态">{Object.entries(selectedRun.nodes).map(([id, value]) => <span className="workflow-actions" key={id}><code>{id}</code><WorkflowBadge state={value.state} /></span>)}</div>
          <WorkflowAttempts key={selectedRun.id} path={`${root}/workflow-runs/${encodeURIComponent(selectedRun.id)}/attempts`} onEvidence={onEvidence} />
        </> : <p className="form-note">{runs.ready ? '尚无流程运行。' : '正在读取流程运行…'}</p>}
        {runs.more && <button className="text-button" disabled={runs.loading} onClick={runs.more}>读取更早运行</button>}
        {runs.error && <p className="form-error" role="alert">{runs.error}</p>}
      </div>
    </section>
    <section className="card"><SectionHeading title="生成与候选历史" subtitle="查看生成结果和来源。发布后，可在任务中另行选用该版本。" />
      <div className="workflow-list workflow-stack">
        {generations.length > 0 && <div className="workflow-stack" aria-label="生成记录">{generations.map(item => <div className="workflow-actions workflow-meta" key={item.id}>
          <b>生成 #{item.sequence}</b><WorkflowBadge state={item.state} /><span>最多 {item.maxCalls} 次 · {formatTime(item.createdAt)}{item.reason && ` · ${item.reason}`}</span>
        </div>)}</div>}
        {proposals.items.map(proposal => <div className="workflow-inline" key={proposal.id}><div className="workflow-actions"><b>{proposal.name}</b><WorkflowBadge state={proposal.state} /><span className="workflow-meta">生成 #{proposal.sequence}</span></div>
          <button className="text-button" onClick={() => setProposalId(proposal.id)}>查看候选</button></div>)}
        {proposals.ready && (!generations.length && !proposals.items.length) && <p className="form-note">尚无生成记录。</p>}
        {proposals.more && <button className="text-button" disabled={proposals.loading} onClick={proposals.more}>读取更早候选</button>}
        {(error || proposals.error) && <p className="form-error" role="alert">{error || proposals.error}</p>}
      </div>
    </section>
    {dialog === 'select' && route && <SelectWorkflow task={task} route={route} routes={routes} restart={restart} onClose={() => setDialog(null)} onSaved={value => { changed(value); onSelectionSaved(); onNotice('已固定流程，请检查并确认执行'); }} />}
    {dialog === 'generate' && route && <GenerateWorkflow task={task} route={route} onClose={() => setDialog(null)} onSaved={value => { changed(value); onNotice('已提交只读生成，状态见生成记录'); }} />}
    {proposalId && <ProposalWorkflow key={proposalId} path={`${root}/workflow-proposals/${encodeURIComponent(proposalId)}`} project={project} settled={settled}
      onClose={() => setProposalId('')} onEvidence={onEvidence} onRequest={onRequest} onSaved={result => {
        setProposalId(''); setRefresh(x => x + 1); onNotice(`已发布 ${result.version.name} v${result.version.version}，可在任务中选择`);
      }} />}
  </section>;
}

function WorkflowAttempts({ path, onEvidence }: { path: string; onEvidence: (id: string) => void }) {
  const attempts = useWorkflowPage<'attempts', WorkflowAttempt>(path, 'attempts', 0);
  return <><div className="workflow-stack">{attempts.items.map(attempt => <details className="workflow-record" key={attempt.id}>
    <summary>{attempt.node.title} · 第 {attempt.attempt} 次 <WorkflowBadge state={attempt.state} /></summary>
    <div className="workflow-stack"><p className="workflow-meta">{attempt.node.goal}</p>{attempt.reason && <p className="workflow-meta">{attempt.reason}</p>}
      <div className="workflow-actions"><WorkflowBadge state={attempt.integrationState} /><span className="workflow-meta">输入 <code>{shortHash(attempt.inputVersion)}</code> · 输出 <code>{shortHash(attempt.resultVersion)}</code></span>
        {attempt.evidenceId && <button className="text-button" onClick={() => onEvidence(attempt.evidenceId!)}>查看节点证据</button>}</div>
      {Object.entries(attempt.inputs || {}).map(([name, input]) => <div className="workflow-actions workflow-meta" key={name}><code>{name}</code> · {input.sourceNodeId || '根任务固定输入'}
        {input.evidenceId && <button className="text-button" onClick={() => onEvidence(input.evidenceId)}>查看交接证据</button>}</div>)}
    </div>
  </details>)}</div>{attempts.more && <button className="text-button" disabled={attempts.loading} onClick={attempts.more}>读取更早尝试</button>}
    {attempts.error && <p className="form-error" role="alert">{attempts.error}</p>}
  </>;
}

function SelectWorkflow({ task, route, routes, restart, onClose, onSaved }: {
  task: Task; route: Route; routes: Route[]; restart: boolean; onClose: () => void; onSaved: (value: Task) => void;
}) {
  const [expected] = useState(() => fields(task));
  const [reviewed, setReviewed] = useState<ReviewedWorkflowSelection | null>(null);
  const [invalidation, setInvalidation] = useState(0);
  const [note, setNote] = useState(''); const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const context = { projectId: task.projectId, rootRoute: route, locks: task.locks || {}, routes, taskId: task.id };
  const selection = currentWorkflowSelection(context, reviewed);
  async function submit(event: FormEvent) {
    event.preventDefault(); if (!selection || (restart && !note.trim()) || busy) return;
    setBusy(true); setError('');
    try { onSaved(await post<Task>(`${rootOf(task.id)}/${restart ? 'workflow-restart' : 'workflow-selection'}`, { ...expected, selection, ...(restart ? { note: note.trim() } : {}) })); }
    catch (error) {
      setReviewed(null); setInvalidation(value => value + 1); setError((error as Error).message);
    } finally { setBusy(false); }
  }
  return <Modal wide title={restart ? '用当前现场开始新流程' : '选择任务流程'} description={restart ? '以当前任务现场作为新一代基线，保留原流程、节点证据和已用预算。应用后仍需确认执行。' : '选择明确版本后，回到协作计划确认执行。'} onClose={() => { if (!busy) onClose(); }}>
    <form onSubmit={submit}><div className="form-body workflow-stack">
      {restart && <p className="workflow-meta">当前现场 <code title={expected.expectedVersion}>{shortHash(expected.expectedVersion)}</code></p>}
      <WorkflowPicker context={context} invalidation={invalidation} disabled={busy} onChange={setReviewed} />
      {restart && <Field label="重新开始的原因"><textarea rows={3} maxLength={3000} required disabled={busy} value={note} onChange={event => setNote(event.target.value)} /></Field>}
      {error && <p className="form-error" role="alert">{error}</p>}
    </div><footer><button type="button" className="button secondary" disabled={busy} onClick={onClose}>取消</button><SaveButton busy={busy} disabled={!selection || (restart && !note.trim())}>应用并返回计划</SaveButton></footer></form>
  </Modal>;
}

type GenerationRequest = ReturnType<typeof fields> & { requestId: string; maxCalls: number; instructions: string; definitionIds: string[] };
function GenerateWorkflow({ task, route, onClose, onSaved }: { task: Task; route: Route; onClose: () => void; onSaved: (value: Task) => void }) {
  const [expected] = useState(() => fields(task));
  const [actors, setActors] = useState<AgentDefinition[]>([]); const [selected, setSelected] = useState<string[]>([]);
  const [instructions, setInstructions] = useState(''); const [calls, setCalls] = useState(1);
  const [pending, setPending] = useState<GenerationRequest | null>(null);
  const [busy, setBusy] = useState(false); const [error, setError] = useState('');
  const available = task.budget?.developmentAvailable;
  const limit = Math.max(0, Math.min(8, available ?? 8));
  useEffect(() => {
    const controller = new AbortController();
    api<AgentDefinition[]>(`/agent-definitions?projectId=${encodeURIComponent(task.projectId)}`, { signal: controller.signal })
      .then(items => { if (!controller.signal.aborted) setActors(items.filter(actor => actor.enabled && actor.capabilities.includes('read'))); })
      .catch(error => { if (!controller.signal.aborted) setError(error.message); });
    return () => controller.abort();
  }, [task.projectId]);
  const valid = selected.length > 0 && selected.length <= 64 && Number.isInteger(calls) && calls >= 1 && calls <= limit;
  async function submit(event: FormEvent) {
    event.preventDefault(); if (busy || (!pending && !valid)) return;
    setBusy(true); setError('');
    try {
      const original = pending || { ...expected, requestId: crypto.randomUUID(), maxCalls: calls, instructions, definitionIds: selected };
      setPending(original);
      onSaved(await post<Task>(`${rootOf(task.id)}/workflow-generations`, original));
    } catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }
  return <Modal wide title="生成流程候选" description={`使用 ${route.name} 只读调查任务，实际请求计入根开发预算。`} onClose={() => { if (!busy) onClose(); }}>
    <form onSubmit={submit}><div className="form-body workflow-stack">
      <Field label="希望流程如何协作"><textarea rows={4} maxLength={6000} value={instructions} disabled={busy || !!pending} onChange={event => setInstructions(event.target.value)} /></Field>
      <Field label="本次生成最多调用"><input type="number" min={1} max={Math.max(1, limit)} required value={calls} disabled={busy || !!pending || limit === 0} onChange={event => setCalls(Number(event.target.value))} /></Field>
      {limit === 0 && <p className="form-note">当前没有可用开发额度。</p>}
      <fieldset className="workflow-consent"><legend>允许引用的职责</legend>{actors.map(actor => <label key={actor.id}><input type="checkbox" checked={selected.includes(actor.id)} disabled={busy || !!pending || selected.length === 64 && !selected.includes(actor.id)} onChange={event => {
        const checked = event.target.checked; setSelected(items => checked ? [...items, actor.id] : items.filter(id => id !== actor.id));
      }} />{actor.name} v{actor.version}</label>)}</fieldset>
      {error && <p className="form-error" role="alert">{error}</p>}
      {pending && !busy && <p className="form-note">重试使用原请求编号。需要修改内容时可关闭，并先查看生成历史。</p>}
    </div><footer><button type="button" className="button secondary" disabled={busy} onClick={onClose}>关闭</button><SaveButton busy={busy} disabled={!pending && !valid}>{pending ? '重试原生成请求' : '确认并生成候选'}</SaveButton></footer></form>
  </Modal>;
}

function ProposalWorkflow({ path, project, settled, onClose, onSaved, onEvidence, onRequest }: {
  path: string; project?: Project; settled: boolean; onClose: () => void; onSaved: (result: WorkflowSaved) => void; onEvidence: (id: string) => void; onRequest: (id: string) => void;
}) {
  const [proposal, setProposal] = useState<WorkflowProposal | null>(null);
  const [editing, setEditing] = useState(false); const [error, setError] = useState('');
  useEffect(() => {
    const controller = new AbortController();
    api<WorkflowProposal>(path, { signal: controller.signal }).then(value => { if (!controller.signal.aborted) setProposal(value); })
      .catch(error => { if (!controller.signal.aborted) setError(error.message); });
    return () => controller.abort();
  }, [path]);
  if (editing && proposal?.dsl) return <WorkflowEditor title="编辑并发布流程候选" projectId={proposal.projectId} projects={project ? [project] : []} actors={proposal.agentDefinitions || []}
    proposal={{ name: proposal.name, dsl: proposal.dsl }} onClose={onClose} onSaved={onSaved}
    publish={({ name, dsl }) => post<WorkflowSaved>(`${path}/publish`, { name, dsl, expectedSourceDigest: proposal.sourceDigest })} />;
  return <Modal wide title="流程候选" onClose={onClose}><div className="form-body workflow-stack">
    {error && <p className="form-error" role="alert">{error}</p>}
    {!proposal && !error && <p className="form-note" role="status">正在读取候选与来源…</p>}
    {proposal && <><div className="workflow-actions"><b>{proposal.name}</b><WorkflowBadge state={proposal.state} /><span className="workflow-meta">来源摘要 <code title={proposal.sourceDigest}>{shortHash(proposal.sourceDigest)}</code></span></div>
      <div className="workflow-actions"><button className="text-button" onClick={() => onEvidence(proposal.evidenceId)}>查看原始证据</button><button className="text-button" onClick={() => onRequest(proposal.modelRequestId)}>查看原模型请求</button></div>
      {proposal.errors.length > 0 && <p className="form-error" role="alert">{proposal.errors.join('；')}</p>}
      {proposal.dsl && <><WorkflowNodes dsl={proposal.dsl} /><details className="workflow-record"><summary>查看原始候选 DSL</summary><div className="workflow-stack"><pre className="evidence-output workflow-json">{JSON.stringify(proposal.dsl, null, 2)}</pre></div></details></>}
      {proposal.state === 'VALIDATED' && <div className="workflow-actions"><button className="button primary small" disabled={!settled} onClick={() => setEditing(true)}>编辑并发布</button></div>}
      {proposal.state === 'PUBLISHED' && <p className="form-note">此候选已发布。可在「流程模板」查看或编辑新版本。</p>}
    </>}
  </div></Modal>;
}
