import { useEffect, useState } from 'react';
import { api } from './api';
import { Badge, Field, formatTime } from './ui';
import './workspace-writes.css';
import './file-tool-operations.css';

type Version = { exists?: boolean; sha256?: string; bytes?: number; fileKey?: string; modifiedAt?: string; executable?: boolean };
type Receipt = { fileVersion?: Version; beforeVersion?: Version; readVersion?: string; expectedReadVersion?: string; sourceReadOperationId?: string; created?: boolean };
type Operation = { id: string; tool: string; path?: string; state: string; createdAt?: string; completedAt?: string; modelRequestId?: string; toolCallId?: string; toolOrdinal?: number; expectedReadVersion?: string; errorCode?: string; error?: string; failedReceiptPhase?: string; sourceStatus?: string; fileReceipt?: Receipt; source?: { stage?: string; agentRunId?: string; taskExecutionId?: string; workspaceWriteLeaseId?: string; workerId?: string; workerEpoch?: number; executorPid?: number; processStartedAt?: string } };
type Page = { operations: Operation[]; cursor: string; hasMore: boolean; total?: number; unattributedCount?: number; journalSources?: { workspace: string; journalPresent: boolean; operationCount: number }[] };
const labels: Record<string, string> = { PREPARED: '准备调用', SENT: '已开始调用', CONFIRMED: '回执已保存', REJECTED: '调用被拒绝', UNKNOWN: '原结果未知', CANCELLED: '本地已取消' };
const tools: Record<string, string> = { read_file: '读取文本', inspect_file: '读取文件版本', write_file: '写入文件', delete_file: '删除文件' };
const text = (value: unknown) => typeof value === 'string' ? value : '未提供';
const object = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const optionalStrings = (value: Record<string, unknown>, fields: string[]) => fields.every(field => value[field] == null || typeof value[field] === 'string');
const optionalIntegers = (value: Record<string, unknown>, fields: string[]) => fields.every(field => value[field] == null || typeof value[field] === 'number' && Number.isSafeInteger(value[field]) && Number(value[field]) >= 0);
function validVersion(value: unknown) {
  return value == null || object(value) && optionalStrings(value, ['sha256', 'fileKey', 'modifiedAt']) && optionalIntegers(value, ['bytes'])
    && (value.exists == null || typeof value.exists === 'boolean') && (value.executable == null || typeof value.executable === 'boolean');
}
function validOperation(value: unknown) {
  if (!object(value) || typeof value.id !== 'string' || !value.id || typeof value.state !== 'string' || !value.state || typeof value.tool !== 'string' || !tools[value.tool]) return false;
  if (!optionalStrings(value, ['path', 'createdAt', 'completedAt', 'modelRequestId', 'toolCallId', 'expectedReadVersion', 'errorCode', 'error', 'failedReceiptPhase', 'sourceStatus']) || !optionalIntegers(value, ['toolOrdinal'])) return false;
  if (value.sourceStatus === 'REQUEST_BOUND' && (typeof value.modelRequestId !== 'string' || !value.modelRequestId)) return false;
  if (value.source != null && (!object(value.source) || !optionalStrings(value.source, ['stage', 'agentRunId', 'taskExecutionId', 'workspaceWriteLeaseId', 'workerId', 'processStartedAt']) || !optionalIntegers(value.source, ['workerEpoch', 'executorPid']))) return false;
  const receipt = value.fileReceipt;
  return receipt == null || object(receipt) && optionalStrings(receipt, ['readVersion', 'expectedReadVersion', 'sourceReadOperationId']) && validVersion(receipt.fileVersion) && validVersion(receipt.beforeVersion);
}
function validPage(value: unknown): value is Page {
  if (!object(value) || !Array.isArray(value.operations) || !value.operations.every(validOperation) || typeof value.cursor !== 'string' || typeof value.hasMore !== 'boolean') return false;
  if (value.cursor && !/^[0-9]{1,19}:[0-9]{1,2}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/.test(value.cursor)) return false;
  if (value.hasMore && (!value.cursor || !value.operations.length) || !optionalIntegers(value, ['total', 'unattributedCount'])) return false;
  return value.journalSources == null || Array.isArray(value.journalSources) && value.journalSources.every(source => object(source) && typeof source.workspace === 'string' && typeof source.journalPresent === 'boolean' && optionalIntegers(source, ['operationCount']));
}
function version(value?: Version) {
  if (!value) return '回执未提供';
  if (value.exists === false) return '观察时文件不存在';
  return `${typeof value.sha256 === 'string' ? value.sha256.slice(0, 12) : '摘要未提供'} · ${typeof value.bytes === 'number' ? value.bytes.toLocaleString() + ' 字节' : '大小未知'}`;
}

export function FileToolOperations({ taskId, onRequest }: { taskId: string; onRequest: (id: string) => void }) {
  const [page, setPage] = useState<Page | null>(null), [state, setState] = useState(''), [before, setBefore] = useState(''), [error, setError] = useState('');
  useEffect(() => {
    let active = true, timer: ReturnType<typeof setTimeout>; const controller = new AbortController(); setPage(null); setError('');
    async function poll() {
      try {
        const next = await api<unknown>(`/tasks/${taskId}/file-tool-operations?limit=30&before=${encodeURIComponent(before)}&state=${encodeURIComponent(state)}`, { signal: controller.signal });
        if (!validPage(next)) throw new Error('文件工具回执响应格式无效');
        if (active) { setPage(next); setError(''); }
      } catch (failure) { if (active) setError((failure as Error).message); }
      if (active) timer = setTimeout(poll, 3000);
    }
    void poll(); return () => { active = false; controller.abort(); clearTimeout(timer); };
  }, [taskId, state, before]);
  const missingJournals = page?.journalSources?.filter(source => !source.journalPresent) || [];
  return <section className="file-tool-operations workspace-writes worker-activity" aria-label="文件工具回执">
    <div className="agent-run-heading"><div><h2>文件操作与读回执</h2><p className="form-note">这里展示 API 文件工具的实际回执。读取后文件变化会保留当前文件，重新读取后再决定修改。</p></div>
      <Field label="文件工具状态"><select value={state} onChange={event => { setState(event.target.value); setBefore(''); }}><option value="">全部文件回执</option>{Object.entries(labels).map(([value, label]) => <option key={value} value={value}>{label}</option>)}</select></Field></div>
    {error && <p role="alert" className="form-error">{error}</p>}{!page && !error && <p className="muted-text">正在读取文件工具回执…</p>}{page && !page.operations.length && <p className="muted-text">尚无匹配的文件工具回执。</p>}
    {!!page?.unattributedCount && <p className="form-note">{page.unattributedCount} 条回执缺少完整的原请求来源，详情保留已有编号和已知范围。</p>}
    {!!missingJournals.length && <p className="form-note">{missingJournals.length} 份现场当前没有文件工具清单；已有执行记录继续保留，不能据此推断曾经的文件操作。</p>}
    <div className="worker-lease-list">{page?.operations.map(record => {
      const source = record.sourceStatus === 'REQUEST_BOUND' ? record.source : undefined;
      return <article className="card worker-lease file-tool-operation" key={record.id}>
      <header><div><h3>{tools[record.tool] || text(record.tool)} · {text(record.path)}</h3><p>{formatTime(record.createdAt)}{source?.stage && ` · ${source.stage}`}</p></div><Badge state={record.state === 'REJECTED' ? 'FAILED' : record.state === 'CONFIRMED' ? 'COMPLETED' : record.state}>{record.errorCode === 'FILE_VERSION_CONFLICT' ? '文件版本冲突' : labels[record.state] || '原状态未识别'}</Badge></header>
      {record.error && <p className="form-error">{text(record.error)}</p>}
      <dl><dt>{record.tool === 'read_file' || record.tool === 'inspect_file' ? '观察到的版本' : '修改前版本'}</dt><dd className="mono">{version(record.fileReceipt?.beforeVersion || (record.tool === 'read_file' || record.tool === 'inspect_file' ? record.fileReceipt?.fileVersion : undefined))}</dd>
        {(record.tool === 'write_file' || record.tool === 'delete_file') && <><dt>修改后观察</dt><dd className="mono">{version(record.fileReceipt?.fileVersion)}</dd></>}
        <dt>请求来源</dt><dd>{record.sourceStatus === 'REQUEST_BOUND' ? <button className="button secondary small" onClick={() => onRequest(text(record.modelRequestId))}>查看对应模型请求</button> : '原请求来源尚未核验'}</dd>
      </dl><details><summary>查看读回执与执行来源</summary><dl>
        <dt>工具操作编号</dt><dd className="mono">{record.id}</dd><dt>原模型 / 工具</dt><dd className="mono">{text(record.modelRequestId)}<br />{text(record.toolCallId)}</dd>
        <dt>使用的读回执</dt><dd className="mono">{record.expectedReadVersion || record.fileReceipt?.readVersion || '原记录未提供'}</dd>
        {record.expectedReadVersion && record.fileReceipt?.readVersion && <><dt>返回的读回执</dt><dd className="mono">{record.fileReceipt.readVersion}</dd></>}
        {record.fileReceipt?.sourceReadOperationId && <><dt>原读取操作</dt><dd className="mono">{record.fileReceipt.sourceReadOperationId}</dd></>}
        {source?.taskExecutionId && <><dt>根执行 / 写入保护</dt><dd className="mono">{source.taskExecutionId}<br />{text(source.workspaceWriteLeaseId)}</dd></>}
        {source?.workerId && <><dt>原 Worker</dt><dd className="mono">{source.workerId} · 代次 {source.workerEpoch ?? '未提供'}<br />PID {source.executorPid ?? '未提供'} · {formatTime(source.processStartedAt)}</dd></>}
        {record.fileReceipt?.fileVersion?.fileKey && <><dt>观察的文件身份</dt><dd className="mono">{record.fileReceipt.fileVersion.fileKey}</dd></>}
        {record.failedReceiptPhase && <><dt>回执缺失位置</dt><dd>{record.failedReceiptPhase}</dd></>}
      </dl></details>
    </article>; })}</div>
    <div className="agent-run-pagination">{before && <button className="button secondary small" onClick={() => setBefore('')}>返回最新文件回执</button>}{page?.hasMore && <button className="button secondary small" onClick={() => setBefore(page.cursor)}>加载更多历史回执</button>}</div>
  </section>;
}
