import { useState } from 'react';
import { post } from './api';
import type { Task, WorkspaceChoice } from './types';
import { Modal, SaveButton, SectionHeading, shortHash } from './ui';
import { WorkspacePicker } from './WorkspacePicker';

const strategyName = (strategy: string) => strategy === 'WORKTREE' ? 'Git worktree' : '独立 clone';
const refName = (ref: string) => ref === 'HEAD' ? '创建时的 HEAD' : ref.replace(/^refs\/(heads|remotes)\//, '');

export function WorkspaceContract({ task, onSaved }: { task: Task; onSaved: () => Promise<void> }) {
  const [editing, setEditing] = useState(false);
  const selection = task.workspaceSelection, ownership = task.workspaceOwnership;
  if (!selection) return null;
  const canChoose = !task.workspace && !task.workspacePreparationPresent && ['DRAFT', 'PAUSED', 'WAITING_INPUT', 'FAILED'].includes(task.state);
  return <section className="card execution-contract workspace-contract" aria-label="固定任务现场"><SectionHeading title="任务现场" subtitle="所选提交和源内容在确认时固定；已有现场保留原策略。" action={canChoose ? <button className="button secondary small" onClick={() => setEditing(true)}>重新确认启动选择</button> : undefined} />
    <dl><dt>现场策略</dt><dd>{strategyName(selection.strategy)}</dd><dt>启动分支</dt><dd>{refName(selection.ref)}</dd><dt>固定提交</dt><dd className="mono">{selection.baseline}</dd><dt>选择版本</dt><dd>v{selection.version} · {selection.includeChanges ? '带入确认时的未提交修改' : '从已提交代码开始'}</dd>
      {task.workspace && <><dt>任务目录</dt><dd className="mono">{task.workspace}</dd></>}{ownership && <><dt>归属编号</dt><dd className="mono">{ownership.owner}</dd><dt>Git 仓库</dt><dd className="mono">{ownership.commonDirectory}</dd></>}
    </dl>{task.workspacePreparationPresent && !task.workspace && <p className="form-note">已有现场准备记录，原选择保持固定；继续前请核对原执行和现场。</p>}{task.workspaceSelectionHistory?.length ? <details><summary>之前的启动选择（{task.workspaceSelectionHistory.length}）</summary>{task.workspaceSelectionHistory.map((entry, index) => <p className="form-note" key={`${entry.selection.version}-${index}`}>v{entry.selection.version} · {strategyName(entry.selection.strategy)} · {refName(entry.selection.ref)} · <code>{shortHash(entry.selection.baseline)}</code></p>)}</details> : null}
    {editing && <SelectionDialog task={task} onClose={() => setEditing(false)} onSaved={onSaved} />}
  </section>;
}

function SelectionDialog({ task, onClose, onSaved }: { task: Task; onClose: () => void; onSaved: () => Promise<void> }) {
  const [expectedVersion] = useState(task.workspaceSelection?.version || 0);
  const [choice, setChoice] = useState<WorkspaceChoice | null>(null), [error, setError] = useState(''), [busy, setBusy] = useState(false);
  const bindingId = task.execution?.repositoryBindingId || '';
  return <Modal title="重新确认启动选择" description="仅尚未准备的任务可以换版。保存后保留历史尝试与预算，等待你继续执行。" onClose={onClose}><form onSubmit={async event => {
    event.preventDefault(); if (!choice || choice.bindingId !== bindingId) return; setBusy(true); setError('');
    try { await post(`/tasks/${task.id}/workspace-selection`, { expectedVersion, workspaceSelection: choice.input, includeChanges: choice.includeChanges }); await onSaved(); onClose(); }
    catch (error) { setError((error as Error).message); } finally { setBusy(false); }
  }}><div className="form-body"><WorkspacePicker bindingId={bindingId} bindingVersion={task.execution?.bindingVersion} initial={task.workspaceSelection} onChange={setChoice} />{error && <p role="alert" className="form-error">{error}</p>}</div><footer><button type="button" className="button secondary" onClick={onClose}>取消</button><SaveButton busy={busy} disabled={!choice || choice.bindingId !== bindingId}>确认新的启动选择</SaveButton></footer></form></Modal>;
}
