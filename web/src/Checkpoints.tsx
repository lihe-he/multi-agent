import { useEffect, useRef, useState } from 'react';
import { Archive, FileCheck2, RefreshCw, RotateCcw, Save, TriangleAlert } from 'lucide-react';
import { api } from './api';
import { Empty, formatTime, SectionHeading, shortHash } from './ui';
import './checkpoints.css';

type Checkpoint = {
  id: string; sequence: number; createdAt: string; version: string; head: string; status: 'READY' | 'PARTIAL';
  fileCount: number; excludedCount: number; limitedCount: number;
  metadata: { kind?: string; label?: string; stage?: string; title?: string; tool?: string; taskState?: string };
};
type CheckpointPage = { checkpoints: Checkpoint[]; cursor: number; hasMore: boolean; undoAllowed: boolean; sourceCheckpointId: string; blockedReason?: string };
type RestorePreview = {
  id: string; taskId: string; canRestore: boolean; indexChanges: boolean; createdAt: string;
  changes: { path: string; action: 'CREATE' | 'DELETE' | 'MODIFY' | 'INDEX'; bytes: number }[];
  conflicts: { path: string; reason: string }[]; targetCheckpointId: string; sourceCheckpointId: string;
};

const kinds: Record<string, string> = { WORKSPACE_READY: '现场就绪', STAGE_START: '阶段开始', STAGE_END: '阶段完成', EXECUTION_END: '执行结束', TOOL_COMPLETED: '工具完成', MANUAL: '手动保存', UNDO_RESULT: '撤销完成', UNDO_PREFLIGHT: '撤销前现场' };
const actions: Record<string, string> = { CREATE: '恢复文件', DELETE: '移除新增文件', MODIFY: '恢复内容或权限', INDEX: '恢复暂存状态' };

function checkpointLabel(checkpoint: Checkpoint) {
  return checkpoint.metadata.label || [kinds[checkpoint.metadata.kind || ''] || '已保存现场', checkpoint.metadata.title || checkpoint.metadata.stage].filter(Boolean).join(' · ');
}

function validatePage(value: CheckpointPage): CheckpointPage {
  if (!value || !Array.isArray(value.checkpoints) || typeof value.undoAllowed !== 'boolean' || typeof value.hasMore !== 'boolean'
    || !Number.isSafeInteger(value.cursor) || value.cursor < 0 || typeof value.sourceCheckpointId !== 'string' || value.checkpoints.length > 100
    || value.checkpoints.some(item => !item || typeof item.id !== 'string' || !item.id || typeof item.version !== 'string' || !item.metadata
      || !Number.isSafeInteger(item.sequence) || item.sequence < 1 || typeof item.createdAt !== 'string'
      || !['READY', 'PARTIAL'].includes(item.status) || !Number.isSafeInteger(item.fileCount) || item.fileCount < 0
      || !Number.isSafeInteger(item.excludedCount) || item.excludedCount < 0 || !Number.isSafeInteger(item.limitedCount) || item.limitedCount < 0)
    || new Set(value.checkpoints.map(item => item.id)).size !== value.checkpoints.length) throw new Error('检查点响应格式无效，请刷新后重试。');
  return value;
}

export function Checkpoints({ taskId, taskVersion, taskState, revision, onSaved, onNotice }: {
  taskId: string; taskVersion?: string; taskState: string; revision?: string;
  onSaved: () => Promise<void>; onNotice: (message: string, error?: boolean) => void;
}) {
  const [page, setPage] = useState<CheckpointPage | null>(null);
  const [selection, setSelection] = useState('');
  const [preview, setPreview] = useState<RestorePreview | null>(null);
  const [error, setError] = useState(''); const [busy, setBusy] = useState('');
  const epoch = useRef(0); const controllers = useRef(new Set<AbortController>());
  const canChange = !!page?.undoAllowed && ['PAUSED', 'WAITING_INPUT', 'FAILED', 'AWAITING_ACCEPTANCE'].includes(taskState);

  async function request<T>(path: string, options: RequestInit = {}) {
    const controller = new AbortController(); controllers.current.add(controller);
    try { return await api<T>(path, { ...options, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(30000)]) }); }
    finally { controllers.current.delete(controller); }
  }

  useEffect(() => {
    const current = ++epoch.current;
    for (const controller of controllers.current) controller.abort();
    setPreview(null); setPage(null); setError(''); setBusy('loading');
    request<CheckpointPage>(`/tasks/${taskId}/checkpoints?limit=30`).then(value => {
      if (current !== epoch.current) return;
      setPage(validatePage(value));
      setSelection(old => value.checkpoints.some(item => item.id === old) ? old : '');
    }).catch(failure => { if (current === epoch.current) setError((failure as Error).message); })
      .finally(() => { if (current === epoch.current) setBusy(''); });
    return () => { ++epoch.current; for (const controller of controllers.current) controller.abort(); };
  }, [taskId, taskVersion, taskState, revision]);

  async function reload(more = false, current = epoch.current) {
    if (current !== epoch.current) return;
    setBusy(more ? 'more' : 'loading'); setError('');
    if (!more) setPreview(null);
    try {
      const value = validatePage(await request<CheckpointPage>(`/tasks/${taskId}/checkpoints?limit=30${more && page ? `&before=${page.cursor}` : ''}`));
      if (current !== epoch.current) return;
      if (page && value.sourceCheckpointId !== page.sourceCheckpointId) setPreview(null);
      setPage(old => more && old ? { ...value, checkpoints: [...old.checkpoints, ...value.checkpoints.filter(item => !old.checkpoints.some(existing => existing.id === item.id))] } : value);
      if (!more) setSelection(old => value.checkpoints.some(item => item.id === old) ? old : '');
    } catch (failure) { if (current === epoch.current) setError((failure as Error).message); }
    finally { if (current === epoch.current) setBusy(''); }
  }

  async function save() {
    const current = epoch.current; setBusy('save'); setError(''); setPreview(null);
    try {
      await request(`/tasks/${taskId}/checkpoints`, { method: 'POST', body: '{}' });
      if (current !== epoch.current) return;
      onNotice('当前文件与暂存状态已保存为新的检查点');
      await reload(false, current);
      if (current === epoch.current) await onSaved();
    } catch (failure) { if (current === epoch.current) { setError((failure as Error).message); onNotice((failure as Error).message, true); } }
    finally { if (current === epoch.current) setBusy(''); }
  }

  async function inspect() {
    const current = epoch.current, selected = selection; setBusy('preview'); setError(''); setPreview(null);
    try {
      const value = await request<RestorePreview>(`/tasks/${taskId}/checkpoint-restores/preview`, { method: 'POST', body: JSON.stringify({ checkpointId: selected }) });
      if (current !== epoch.current) return;
      if (!value || value.taskId !== taskId || value.targetCheckpointId !== selected || typeof value.id !== 'string' || typeof value.canRestore !== 'boolean'
        || value.sourceCheckpointId !== page?.sourceCheckpointId || !value.id || typeof value.indexChanges !== 'boolean'
        || !Array.isArray(value.changes) || !Array.isArray(value.conflicts) || value.changes.length > 100
        || value.changes.some(item => !item || typeof item.path !== 'string' || !item.path || !Object.hasOwn(actions, item.action))
        || new Set(value.changes.map(item => item.path)).size !== value.changes.length
        || value.conflicts.some(item => !item || typeof item.path !== 'string' || typeof item.reason !== 'string')
        || (value.canRestore && value.conflicts.length > 0)) throw new Error('撤销预览与所选任务或检查点不一致，请重新读取。');
      setPreview(value);
    } catch (failure) { if (current === epoch.current) { setPreview(null); setError((failure as Error).message); } }
    finally { if (current === epoch.current) setBusy(''); }
  }

  async function restore() {
    if (!preview || !preview.canRestore || (!preview.changes.length && !preview.indexChanges) || !canChange) return;
    if (!window.confirm(`将恢复所列的 ${preview.changes.length} 个文件${preview.indexChanges ? '及暂存状态' : ''}到选中的检查点。完成后任务会暂停并需要重新验证，原执行记录与预算保留。确认撤销？`)) return;
    const current = epoch.current, original = preview; setBusy('restore'); setError('');
    try {
      await request(`/tasks/${taskId}/checkpoint-restores`, { method: 'POST', body: JSON.stringify({ previewId: original.id }) });
      if (current !== epoch.current) return;
      setPreview(null); onNotice('撤销已排队，完成后任务会暂停并需要重新验证'); await onSaved(); await reload(false, current);
    } catch (failure) { if (current === epoch.current) { setPreview(null); setError((failure as Error).message); await onSaved(); } }
    finally { if (current === epoch.current) setBusy(''); }
  }

  const selected = page?.checkpoints.find(item => item.id === selection);
  return <section className="checkpoint-panel" aria-label="检查点与撤销">
    <div className="checkpoint-heading"><SectionHeading title="检查点与撤销" subtitle="每次保存保留独立的内容版本。恢复前先核对受影响的文件。" /><div className="checkpoint-toolbar">
      <button className="button secondary small" disabled={!!busy} onClick={() => reload()}><RefreshCw size={15} />刷新</button>
      <button className="button secondary small" disabled={!!busy || !canChange} onClick={save}><Save size={15} />保存当前检查点</button>
    </div></div>
    {error && <div className="form-error" role="alert">{error}</div>}
    {page && !page.undoAllowed && <div className="info-banner"><TriangleAlert size={17} /><span>{page.blockedReason || '请先暂停任务，并等待执行和文件写入结束后再保存或撤销。'}</span></div>}
    {!page ? (!error && <div className="loading-content" role="status">正在读取检查点…</div>) : !page.checkpoints.length ? <section className="card"><Empty icon={<Archive size={27} />} title="尚无文件检查点" description="任务开始执行后会保存现场；已有任务也可在暂停后手动保存。" /></section> : <div className="checkpoint-grid">
      <section className="card checkpoint-list" aria-label="已保存检查点">
        {page.checkpoints.map(item => <button key={item.id} className={`checkpoint-choice${selection === item.id ? ' selected' : ''}`} aria-pressed={selection === item.id} disabled={!!busy} onClick={() => { setSelection(item.id); setPreview(null); setError(''); }}>
          <span className="checkpoint-choice-title"><Archive size={16} /><b>{checkpointLabel(item)}</b>{item.id === page.sourceCheckpointId && <span className="checkpoint-current">最新</span>}</span>
          <span className="checkpoint-choice-details"><time>{formatTime(item.createdAt)}</time><code>{shortHash(item.version)}</code></span>
          <span className="checkpoint-choice-details">{item.fileCount} 个文件{item.status === 'PARTIAL' && <span> · 部分内容不可恢复</span>}</span>
        </button>)}
        {page.hasMore && <button className="button secondary small checkpoint-more" disabled={!!busy} onClick={() => reload(true)}>读取更早的检查点</button>}
      </section>
      <section className="card checkpoint-preview" aria-label="撤销预览">
        {!selected ? <Empty icon={<RotateCcw size={27} />} title="选择要恢复的检查点" description="预览会列出撤销的文件，并核对后续修改是否冲突。" /> : <>
          <h3>{checkpointLabel(selected)}</h3><p className="muted-text">保存于 {formatTime(selected.createdAt)} · 内容 {shortHash(selected.version)}</p>
          {selected.status === 'PARTIAL' && <p className="checkpoint-limits">{selected.excludedCount} 项被排除，{selected.limitedCount} 项超过保存上限。受影响内容不可用时会阻止撤销。</p>}
          <button className="button secondary small" disabled={!!busy || !canChange || selected.id === page.sourceCheckpointId} onClick={inspect}><FileCheck2 size={15} />预览撤销</button>
          {selected.id === page.sourceCheckpointId && <p className="muted-text">这里是最新保存的边界，可选择更早的检查点。</p>}
          {preview && <div className="checkpoint-plan">
            <h4>受影响文件 · {preview.changes.length}</h4>
            {preview.changes.length ? <ul className="checkpoint-paths">{preview.changes.map(item => <li key={item.path}><code>{item.path}</code><span>{actions[item.action]}</span></li>)}</ul> : <p className="muted-text">两个检查点之间没有需要撤销的文件改动。</p>}
            {preview.indexChanges && <p className="muted-text">本次也会恢复检查点保存的暂存状态。</p>}
            {!!preview.conflicts.length && <div className="checkpoint-conflicts" role="alert"><h4>当前需要处理</h4>{preview.conflicts.map((item, index) => <p key={index}><code>{item.path}</code><span>{item.reason}</span></p>)}</div>}
            <p className="muted-text">原验证、审查和预算记录保留。撤销完成后需要对新现场重新验证。</p>
            <button className="button primary small" disabled={!!busy || !canChange || !preview.canRestore || !!preview.conflicts.length || (!preview.changes.length && !preview.indexChanges)} onClick={restore}><RotateCcw size={15} />确认撤销到此检查点</button>
          </div>}
        </>}
      </section>
    </div>}
  </section>;
}
