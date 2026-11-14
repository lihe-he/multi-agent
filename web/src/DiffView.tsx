import { Fragment, useEffect, useMemo, useRef, useState, type KeyboardEvent } from 'react';
import { ChevronDown, ChevronRight, FileCode2, FileWarning, Folder, RefreshCw } from 'lucide-react';
import { api } from './api';
import { readDiffResponse, type DiffFile, type DiffResponse } from './diff-view-contract';
import { Empty, SectionHeading, shortHash } from './ui';
import './diff-view.css';

const statusLabel = { ADDED: '新增', MODIFIED: '修改', DELETED: '删除' };
const statusMark = { ADDED: '+', MODIFIED: 'M', DELETED: '−' };
type TreeNode = { key: string; path: string; label: string; parent: string; children: TreeNode[]; file?: DiffFile };
type VisibleNode = { node: TreeNode; depth: number };

function fileTree(files: DiffFile[], collapsed: Set<string>): VisibleNode[] {
  const root: TreeNode = { key: '', path: '', label: '', parent: '', children: [] }, folders = new Map<string, TreeNode>([['', root]]);
  for (const file of files) {
    const parts = file.path.split('/'); let parent = root;
    for (let index = 0; index < parts.length - 1; index++) {
      const path = parts.slice(0, index + 1).join('/'); let folder = folders.get(path);
      if (!folder) { folder = { key: `folder:${path}`, path, label: parts[index], parent: parent.key, children: [] }; folders.set(path, folder); parent.children.push(folder); }
      parent = folder;
    }
    parent.children.push({ key: `file:${file.path}`, path: file.path, label: parts.at(-1)!, parent: parent.key, children: [], file });
  }
  for (const folder of folders.values()) folder.children.sort((left, right) => Number(!!left.file) - Number(!!right.file) || left.label.localeCompare(right.label));
  const stack = [...root.children].reverse().map(node => ({ node, depth: 0 })), visible: VisibleNode[] = [];
  while (stack.length) {
    const entry = stack.pop()!; visible.push(entry);
    if (!entry.node.file && !collapsed.has(entry.node.key)) stack.push(...[...entry.node.children].reverse().map(node => ({ node, depth: entry.depth + 1 })));
  }
  return visible;
}

export function DiffView({ taskId, taskVersion, workspace }: { taskId: string; taskVersion?: string; workspace?: string }) {
  const [view, setView] = useState<DiffResponse | null>(null), viewRef = useRef<DiffResponse | null>(null);
  const [selected, setSelected] = useState(''), selectedRef = useRef('');
  const [loading, setLoading] = useState<'refresh' | 'file' | ''>('refresh'), [error, setError] = useState('');
  const [collapsed, setCollapsed] = useState(new Set<string>()), [focused, setFocused] = useState('');
  const buttons = useRef(new Map<string, HTMLButtonElement>()), sequence = useRef(0), pending = useRef<AbortController | null>(null);
  const visible = useMemo(() => fileTree(view?.files || [], collapsed), [view?.files, collapsed]);
  const tabStop = visible.some(entry => entry.node.key === focused) ? focused : visible.find(entry => entry.node.path === selected && !!entry.node.file)?.node.key || visible[0]?.node.key;

  function begin(kind: 'refresh' | 'file') {
    pending.current?.abort(); const controller = new AbortController(); pending.current = controller;
    const request = ++sequence.current; setLoading(kind); setError('');
    return { request, signal: AbortSignal.any([controller.signal, AbortSignal.timeout(30_000)]) };
  }
  function publish(value: DiffResponse) {
    viewRef.current = value; setView(value); selectedRef.current = value.selectedPath; setSelected(value.selectedPath); setLoading('');
  }
  async function fetchView(signal: AbortSignal, path = '', base?: DiffResponse) {
    const query = new URLSearchParams({ path, expectedVersion: base?.version || '', expectedBase: base?.baseline || '' });
    return readDiffResponse(await api<unknown>(`/tasks/${encodeURIComponent(taskId)}/diff-view?${query}`, { signal }), base ? { version: base.version, baseline: base.baseline, path } : undefined);
  }
  async function refresh() {
    const wanted = selectedRef.current, { request, signal } = begin('refresh'); viewRef.current = null; setView(null);
    try {
      let value = await fetchView(signal);
      if (request !== sequence.current || signal.aborted) return;
      if (wanted && wanted !== value.selectedPath && value.files.some(file => file.path === wanted)) value = await fetchView(signal, wanted, value);
      if (request === sequence.current && !signal.aborted) publish(value);
    } catch (failure) { if (request === sequence.current) { setError((failure as Error).name === 'TimeoutError' ? '读取代码差异超时，请刷新后重试。' : (failure as Error).message); setLoading(''); } }
  }
  async function select(path: string) {
    const base = viewRef.current; if (!base) return;
    const { request, signal } = begin('file'); selectedRef.current = path; setSelected(path); setView({ ...base, preview: null });
    try { const value = await fetchView(signal, path, base); if (request === sequence.current && !signal.aborted) publish(value); }
    catch (failure) { if (request === sequence.current) { setError((failure as Error).name === 'TimeoutError' ? '读取代码差异超时，请刷新后重试。' : (failure as Error).message); setLoading(''); } }
  }
  useEffect(() => {
    void refresh();
    return () => { ++sequence.current; pending.current?.abort(); };
  }, [taskId, taskVersion, workspace]);

  function toggle(key: string) { setCollapsed(old => { const next = new Set(old); if (next.has(key)) next.delete(key); else next.add(key); return next; }); }
  function focus(key?: string) { if (key) { setFocused(key); buttons.current.get(key)?.focus(); } }
  function treeKey(event: KeyboardEvent<HTMLButtonElement>, entry: VisibleNode, index: number) {
    const { node } = entry; let handled = true;
    switch (event.key) {
      case 'ArrowDown': focus(visible[Math.min(index + 1, visible.length - 1)]?.node.key); break;
      case 'ArrowUp': focus(visible[Math.max(index - 1, 0)]?.node.key); break;
      case 'Home': focus(visible[0]?.node.key); break;
      case 'End': focus(visible.at(-1)?.node.key); break;
      case 'ArrowRight': if (!node.file) { if (collapsed.has(node.key)) toggle(node.key); else focus(visible[index + 1]?.node.key); } break;
      case 'ArrowLeft': if (!node.file && !collapsed.has(node.key)) toggle(node.key); else focus(node.parent); break;
      case 'Enter': case ' ': if (node.file) void select(node.path); else toggle(node.key); break;
      default: handled = false;
    }
    if (handled) event.preventDefault();
  }
  const file = view?.files.find(value => value.path === selected), preview = view?.preview?.path === selected && view.preview.version === view.version ? view.preview : null;
  return <section className="card diff-review" aria-label="工作目录差异" aria-busy={!!loading}>
    <SectionHeading title="工作目录差异" subtitle={view ? `内容版本 ${shortHash(view.version)} · Git 基线 ${shortHash(view.baseline)} · ${view.workspace || '尚未创建工作目录'}` : loading ? '正在读取当前文件清单与内容版本' : '请刷新后重新读取文件清单与内容版本'}
      action={<button className="button secondary small" disabled={loading === 'refresh'} onClick={() => void refresh()}><RefreshCw size={14} className={loading === 'refresh' ? 'spin' : ''} />刷新代码差异</button>} />
    {error && <div className="form-error diff-view-error" role="alert">{error}</div>}
    {loading === 'refresh' ? <div className="loading-content" role="status">正在读取代码差异…</div> : view && view.files.length === 0 ? <Empty icon={<FileCode2 size={27} />} title="当前没有代码差异" description="Agent 对任务工作目录的修改会出现在这里。" /> : view && <>
      <div className="diff-review-summary"><span>{view.files.length} 个变更文件</span><span>新增 {view.files.filter(file => file.status === 'ADDED').length}</span><span>修改 {view.files.filter(file => file.status === 'MODIFIED').length}</span><span>删除 {view.files.filter(file => file.status === 'DELETED').length}</span></div>
      <div className="diff-view-layout">
        <nav className="diff-file-tree" aria-label="变更文件导航"><div className="diff-tree-heading">变更文件<span>方向键浏览 · Enter 查看</span></div><div role="tree" aria-label="变更文件">{visible.map((entry, index) => {
          const { node, depth } = entry, expanded = !collapsed.has(node.key);
          return <button key={node.key} type="button" role="treeitem" aria-level={depth + 1} aria-expanded={node.file ? undefined : expanded} aria-selected={node.file ? node.path === selected : undefined}
            aria-label={node.file ? `${node.path} · ${statusLabel[node.file.status]}` : `文件夹 ${node.path}`} tabIndex={tabStop === node.key ? 0 : -1}
            className={`diff-tree-item${node.file && node.path === selected ? ' selected' : ''}`} style={{ paddingInlineStart: 10 + Math.min(depth, 8) * 12 }}
            ref={button => { if (button) buttons.current.set(node.key, button); else buttons.current.delete(node.key); }} onFocus={() => setFocused(node.key)}
            onKeyDown={event => treeKey(event, entry, index)} onClick={() => { if (node.file) void select(node.path); else toggle(node.key); }} title={node.path}>
            {node.file ? <FileCode2 size={14} /> : <>{expanded ? <ChevronDown size={12} /> : <ChevronRight size={12} />}<Folder size={14} /></>}
            <span className="diff-tree-label">{node.label}</span>{node.file && <span className={`diff-file-status ${node.file.status.toLowerCase()}`} aria-hidden="true">{statusMark[node.file.status]}</span>}
          </button>;
        })}</div></nav>
        <section className="diff-file-preview" aria-label="文件差异" aria-busy={loading === 'file'}>
          <header className="diff-preview-heading"><div><h3>{selected || '选择文件'}</h3><p>{file && statusLabel[file.status]}{file && file.oldMode !== file.newMode && <> · 文件模式 {file.oldMode} → {file.newMode}</>}</p></div>
            {preview?.type === 'TEXT' && <div className="diff-line-counts"><span className="added">+{preview.additions}</span><span className="removed">−{preview.deletions}</span></div>}</header>
          {loading === 'file' ? <div className="loading-content" role="status">正在读取所选文件…</div> : preview && <>
            <div className="diff-preview-facts"><span>原文件 {preview.beforeBytes === null ? '大小未读取' : `${preview.beforeBytes.toLocaleString()} 字节`}</span><span>新文件 {preview.afterBytes.toLocaleString()} 字节</span></div>
            {preview.type === 'TEXT' ? <div className="diff-code-scroll" role="region" aria-label="差异代码，可横向滚动" tabIndex={0}><table className="diff-code-table" aria-label={`${selected} 的逐行差异`}>
              <thead><tr><th scope="col">原行</th><th scope="col">新行</th><th scope="col">变更</th><th scope="col">代码</th></tr></thead>
              <tbody>{preview.hunks.map((hunk, hunkIndex) => <Fragment key={hunkIndex}><tr className="diff-hunk"><td colSpan={4}><code>@@ -{hunk.oldStart},{hunk.oldCount} +{hunk.newStart},{hunk.newCount} @@</code></td></tr>
                {hunk.lines.map((line, index) => <Fragment key={index}><tr className={`diff-code-row ${line.kind.toLowerCase()}`}><td className="diff-line-old">{line.oldLine}</td><td className="diff-line-new">{line.newLine}</td>
                  <td className="diff-line-marker" aria-label={line.kind === 'ADD' ? '新增行' : line.kind === 'REMOVE' ? '删除行' : '上下文行'}>{line.kind === 'ADD' ? '+' : line.kind === 'REMOVE' ? '−' : ' '}</td><td className="diff-line-content"><code>{line.content}</code></td></tr>
                  {line.noNewline && <tr className="diff-no-newline"><td colSpan={4}>末行无换行符 · {line.kind === 'REMOVE' ? '原文件' : line.kind === 'ADD' ? '新文件' : '原文件与新文件'}</td></tr>}</Fragment>)}</Fragment>)}</tbody>
            </table></div> : <div className={`diff-preview-state ${preview.type.toLowerCase()}`} role="status"><FileWarning size={24} /><div><h4>{({ BINARY: '二进制或非 UTF-8 文件', EXCLUDED: '该路径不提供文本预览', LIMIT: '差异超出预览上限', MODE_ONLY: '只修改文件执行位', EMPTY: '空文件变更', UNSUPPORTED: '无法完整显示文本差异' })[preview.type]}</h4><p>{preview.message}</p></div></div>}
          </>}
        </section>
      </div>
    </>}
  </section>;
}
