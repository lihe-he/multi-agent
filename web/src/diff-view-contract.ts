export interface DiffFile {
  path: string; status: 'ADDED' | 'MODIFIED' | 'DELETED'; modeOnly: boolean; excluded: boolean;
  oldMode: string; newMode: string; bytes: number; sha256: string; gitBlob: string; baseBlob: string;
}
export interface DiffLine { kind: 'ADD' | 'REMOVE' | 'CONTEXT'; content: string; oldLine: number | null; newLine: number | null; noNewline: boolean }
export interface DiffHunk { oldStart: number; oldCount: number; newStart: number; newCount: number; lines: DiffLine[] }
export interface DiffPreview {
  path: string; version: string; baseBlob: string; sha256: string; beforeBytes: number | null; afterBytes: number;
  type: 'TEXT' | 'BINARY' | 'EXCLUDED' | 'LIMIT' | 'MODE_ONLY' | 'EMPTY' | 'UNSUPPORTED'; message: string; limit: string; complete: boolean;
  hunks: DiffHunk[]; additions?: number; deletions?: number; rows?: number;
}
export interface DiffResponse {
  protocolVersion: 1; workspace: string; version: string; baseline: string; files: DiffFile[]; selectedPath: string; preview: DiffPreview | null;
  complete: true; consistency: 'CAPTURED_FILE_DIGESTS'; limits: { fileBytes: number; changedFiles: number; rows: number; parallelReaders: number };
}

const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value);
const integer = (value: unknown, maximum = Number.MAX_SAFE_INTEGER): value is number => Number.isSafeInteger(value) && (value as number) >= 0 && (value as number) <= maximum;
const text = (value: unknown): value is string => typeof value === 'string';
const digest = (value: unknown) => text(value) && /^[0-9a-f]{64}$/.test(value);
const objectId = (value: unknown, length: number) => text(value) && new RegExp(`^[0-9a-f]{${length}}$`).test(value);
const mode = (value: unknown) => ['000000', '100644', '100755'].includes(value as string);
const path = (value: unknown): value is string => text(value) && !!value && !value.includes('\0') && !value.startsWith('/') && !value.split('/').some(part => !part || part === '.' || part === '..');
const invalid = () => { throw new Error('差异视图响应格式无效'); };

// Bind the displayed path, version and digests before accepting any server rows.
export function readDiffResponse(raw: unknown, expected?: { version: string; baseline: string; path: string }): DiffResponse {
  if (!record(raw) || raw.protocolVersion !== 1 || raw.complete !== true || raw.consistency !== 'CAPTURED_FILE_DIGESTS'
      || !text(raw.workspace) || !text(raw.version) || !text(raw.baseline) || !text(raw.selectedPath) || !Array.isArray(raw.files)
      || !record(raw.limits) || !integer(raw.limits.fileBytes, 256_000) || !integer(raw.limits.changedFiles, 2_000)
      || !integer(raw.limits.rows, 5_000) || !integer(raw.limits.parallelReaders, 2) || raw.files.length > raw.limits.changedFiles) return invalid();
  const empty = raw.workspace === '';
  if (empty ? raw.version !== '' || raw.baseline !== '' || raw.files.length !== 0 : !digest(raw.version) || !/^(?:[0-9a-f]{40}|[0-9a-f]{64})$/.test(raw.baseline)) return invalid();
  if (expected && (raw.version !== expected.version || raw.baseline !== expected.baseline || raw.selectedPath !== expected.path)) return invalid();
  const ids = new Set<string>(); let pathCharacters = 0;
  for (const file of raw.files) {
    if (!record(file) || !path(file.path) || ids.has(file.path) || !['ADDED', 'MODIFIED', 'DELETED'].includes(file.status as string)
        || typeof file.modeOnly !== 'boolean' || typeof file.excluded !== 'boolean' || !mode(file.oldMode) || !mode(file.newMode) || !integer(file.bytes)
        || (file.status === 'DELETED' ? file.sha256 !== '' || file.gitBlob !== '' || file.bytes !== 0 || file.newMode !== '000000' : !digest(file.sha256) || !objectId(file.gitBlob, raw.baseline.length) || file.newMode === '000000')
        || (file.status === 'ADDED' ? file.baseBlob !== '' || file.oldMode !== '000000' : !objectId(file.baseBlob, raw.baseline.length) || file.oldMode === '000000')) return invalid();
    ids.add(file.path); pathCharacters += file.path.length;
  }
  if (pathCharacters > 512_000) return invalid();
  if (raw.files.length === 0) {
    if (raw.selectedPath !== '' || raw.preview !== null) return invalid();
    return raw as unknown as DiffResponse;
  }
  const selected = raw.files.find(file => record(file) && file.path === raw.selectedPath), preview = raw.preview;
  if (!record(selected) || !record(preview) || preview.path !== raw.selectedPath || preview.version !== raw.version
      || preview.baseBlob !== selected.baseBlob || preview.sha256 !== selected.sha256 || preview.afterBytes !== selected.bytes
      || (preview.beforeBytes === null ? preview.type !== 'EXCLUDED' : !integer(preview.beforeBytes)) || !Array.isArray(preview.hunks)
      || !text(preview.message) || !text(preview.limit) || !['TEXT', 'BINARY', 'EXCLUDED', 'LIMIT', 'MODE_ONLY', 'EMPTY', 'UNSUPPORTED'].includes(preview.type as string)
      || preview.complete !== ['TEXT', 'MODE_ONLY', 'EMPTY'].includes(preview.type as string)) return invalid();
  if (preview.type !== 'TEXT') {
    if (preview.hunks.length !== 0) return invalid();
  } else {
    let rows = 0, additions = 0, deletions = 0, characters = 0;
    if (preview.hunks.length === 0) return invalid();
    for (const hunk of preview.hunks) {
      if (!record(hunk) || !integer(hunk.oldStart, 256_001) || !integer(hunk.newStart, 256_001) || !integer(hunk.oldCount, 5_000) || !integer(hunk.newCount, 5_000)
          || !Array.isArray(hunk.lines) || hunk.lines.length === 0) return invalid();
      let oldLine = hunk.oldStart, newLine = hunk.newStart, oldCount = 0, newCount = 0;
      for (const line of hunk.lines) {
        if (!record(line) || !['ADD', 'REMOVE', 'CONTEXT'].includes(line.kind as string) || !text(line.content) || typeof line.noNewline !== 'boolean') return invalid();
        if (line.kind === 'ADD' ? line.oldLine !== null : !integer(line.oldLine, 256_001) || line.oldLine === 0 || line.oldLine !== oldLine++) return invalid();
        if (line.kind === 'REMOVE' ? line.newLine !== null : !integer(line.newLine, 256_001) || line.newLine === 0 || line.newLine !== newLine++) return invalid();
        if (line.kind !== 'ADD') oldCount++; if (line.kind !== 'REMOVE') newCount++;
        if (line.kind === 'ADD') additions++; if (line.kind === 'REMOVE') deletions++;
        characters += line.content.length; rows++;
      }
      if (oldCount !== hunk.oldCount || newCount !== hunk.newCount) return invalid();
    }
    if (rows > raw.limits.rows || characters > 512_000 || rows !== preview.rows || additions !== preview.additions || deletions !== preview.deletions || additions + deletions === 0) return invalid();
  }
  return raw as unknown as DiffResponse;
}
