import { useEffect, useState } from 'react';
import { api } from './api';
import type { WorkspaceChoice, WorkspaceOptions, WorkspaceSelection } from './types';
import { Field, shortHash } from './ui';

export function WorkspacePicker({ bindingId, bindingVersion, initial, onChange }: {
  bindingId: string; bindingVersion?: number; initial?: WorkspaceSelection; onChange: (choice: WorkspaceChoice | null) => void;
}) {
  const [options, setOptions] = useState<WorkspaceOptions | null>(null);
  const [strategy, setStrategy] = useState<'CLONE' | 'WORKTREE'>(initial?.strategy || 'CLONE');
  const [ref, setRef] = useState(initial?.ref || 'HEAD');
  const [includeChanges, setIncludeChanges] = useState(initial?.includeChanges || false);
  const [error, setError] = useState(''), [refresh, setRefresh] = useState(0);
  useEffect(() => {
    let active = true; const controller = new AbortController(); setOptions(null); setError('');
    api<WorkspaceOptions>(`/repository-bindings/${encodeURIComponent(bindingId)}/workspace-options`, { signal: controller.signal }).then(value => {
      if (value.schemaVersion !== 1 || value.repositoryBindingId !== bindingId || typeof value.source?.head !== 'string' || typeof value.source?.version !== 'string' || typeof value.source?.index !== 'string' || !Array.isArray(value.refs)) throw new Error('核心没有返回当前绑定的启动选择，请检查版本后刷新。');
      if (bindingVersion !== undefined && value.bindingVersion !== bindingVersion) throw new Error('仓库绑定版本已变化，请先刷新配置。');
      if (active) setOptions(value);
    }).catch(error => { if (active) setError((error as Error).message); });
    return () => { active = false; controller.abort(); };
  }, [bindingId, bindingVersion, refresh]);
  const head = ref === 'HEAD' ? options?.source.head : options?.refs.find(item => item.ref === ref)?.head;
  const canInclude = Boolean(head && head === options?.source.head);
  useEffect(() => { if (options && (!options.source.dirty || !canInclude)) setIncludeChanges(false); }, [options, canInclude]);
  useEffect(() => {
    if (!options || !head || (options.source.dirty && !includeChanges) || (includeChanges && !canInclude)) { onChange(null); return; }
    onChange({ bindingId, bindingVersion: options.bindingVersion, input: { strategy, ref, expectedHead: head, sourceVersion: options.source.version, sourceIndex: options.source.index }, includeChanges });
  }, [options, head, strategy, ref, includeChanges, canInclude, bindingId, onChange]);
  return <div className="workspace-picker" aria-label="启动现场选择">
    <div className="form-grid"><Field label="任务现场"><select value={strategy} onChange={event => setStrategy(event.target.value as 'CLONE' | 'WORKTREE')}>
      <option value="CLONE">独立 clone</option><option value="WORKTREE">Git worktree</option>
    </select></Field><Field label="启动提交"><select value={ref} disabled={!options} onChange={event => { setRef(event.target.value); if (event.target.value !== 'HEAD' && options?.refs.find(item => item.ref === event.target.value)?.head !== options?.source.head) setIncludeChanges(false); }}>
      <option value="HEAD">当前 HEAD {options ? `· ${shortHash(options.source.head)}` : ''}</option>
      {options?.refs.map(item => <option key={item.ref} value={item.ref}>{item.kind === 'REMOTE' ? '远程' : '本地'} · {item.name} · {shortHash(item.head)}</option>)}
      {options && ref !== 'HEAD' && !head && <option value={ref}>原引用已变化，请重新选择</option>}
    </select></Field></div>
    <p className="form-note">{strategy === 'WORKTREE' ? '共享 Git 历史，文件与暂存区独立；从所选提交建立 detached worktree。' : '在独立仓库副本中执行，从所选提交建立任务目录。'}</p>
    {options && head && <p className="form-note execution-source">固定提交 <code>{head}</code> · 源分支 {options.source.branch || 'detached HEAD'}</p>}
    {options?.source.dirty && <label className="checkbox-card"><input type="checkbox" disabled={!canInclude} checked={includeChanges} onChange={event => setIncludeChanges(event.target.checked)} /><span><b>带入当前未提交修改</b><small>{canInclude ? '保留原项目与暂存状态；实际准备时再次核对源内容。' : '带入改动需要选择与当前 HEAD 相同的提交。'}</small></span></label>}
    {!options && !error && <p className="muted-text">正在读取实际分支与源内容…</p>}
    {options && !head && <p role="alert" className="form-error">原分支引用已变化，请重新选择启动提交。</p>}
    {error && <p role="alert" className="form-error">{error}</p>}
    <button type="button" className="text-button" onClick={() => setRefresh(value => value + 1)}>刷新启动选择</button>
  </div>;
}
