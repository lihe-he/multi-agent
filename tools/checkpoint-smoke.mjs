import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, statSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { delimiter, dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { inspectModel, zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), windows = process.platform === 'win32';
const modules = createRequire(join(root, 'web/package.json')), { chromium, expect } = modules('@playwright/test');
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version, javaRoot = javaHome(root);
const java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java'), jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
assert.ok(existsSync(jar) && existsSync(join(root, 'web/dist/index.html')), 'Build the current ordinary final Jar and production Web before acceptance');
mkdirSync(join(root, '.runtime'), { recursive: true });
const directory = mkdtempSync(join(root, '.runtime/checkpoints-')), evidence = join(directory, 'reports'), data = join(directory, 'data'), classes = join(directory, 'fixture-classes'), boot = join(directory, 'boot');
mkdirSync(evidence); mkdirSync(classes); mkdirSync(boot);
const source = join(directory, '中文 checkpoint source'), nioDirectory = windows ? directory : mkdtempSync(join(realpathSync(tmpdir()), 'checkpoint-nio-'));
const token = randomBytes(32).toString('hex'), credential = 'checkpoint-fixture-key', checks = [], calls = [], modelErrors = [], pageErrors = [], tasks = new Map(), identities = new Map(), cores = [], served = new Map(), wireRestores = [];
const digest = bytes => createHash('sha256').update(bytes).digest('hex'), jarSha256 = digest(readFileSync(jar)), scriptSource = readFileSync(fileURLToPath(import.meta.url)), scriptSha256 = digest(scriptSource);
const webFiles = readdirSync(join(root, 'web/dist'), { recursive: true }).filter(file => statSync(join(root, 'web/dist', file)).isFile()).sort().map(file => ({ file: `web/dist/${file.replaceAll('\\', '/')}`, sha256: digest(readFileSync(join(root, 'web/dist', file))) }));
const originalText = '用户原始内容\n第二行\n', changedText = '实现后的内容\n第二行\n', binary = Buffer.from([0, -1, 13, 10, 0]);
const literalPath = 'new/literal.tsx', literalText = '+literal <img src=x onerror="window.checkpointInjected=1"><script>literal</script>\n末行没有换行';
const literalLabel = '已保存 <script>literal</script>';
let currentCore, baseUrl, browser, page, report, phase = 'fixture compilation';
const redact = value => String(value).replaceAll(token, '[REDACTED]').replaceAll(credential, '[REDACTED]');
const save = (name, value) => writeFileSync(join(evidence, name), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds));
async function until(name, action, timeout = 120000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await pause(100); }
  throw new Error(`${name} did not arrive within its bounded deadline`);
}
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) });
  const text = await response.text(); assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${redact(text.slice(0, 400))}`); return JSON.parse(text);
}
function git(workspace, ...args) { return execFileSync('git', ['--no-optional-locks', '-c', 'core.hooksPath=/dev/null', '-c', 'core.autocrlf=false', '-C', workspace, ...args], { encoding: 'utf8', windowsHide: true, timeout: 20000 }); }
function treeFacts(workspace) {
  const paths = [...new Set(git(workspace, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').split('\0').filter(Boolean))].sort();
  const metadata = name => resolve(workspace, git(workspace, 'rev-parse', '--git-path', name).trim());
  return { head: git(workspace, 'rev-parse', 'HEAD').trim(), branch: git(workspace, 'branch', '--show-current').trim(), index: digest(readFileSync(metadata('index'))), config: digest(readFileSync(metadata('config'))), refs: git(workspace, 'for-each-ref', '--format=%(refname) %(objectname)'),
    files: paths.map(path => ({ path, exists: existsSync(join(workspace, path)), ...(existsSync(join(workspace, path)) ? { bytes: statSync(join(workspace, path)).size, sha256: digest(readFileSync(join(workspace, path))) } : {}) })) };
}
function remember(value) { if (value?.pid && value.processStartedAt) identities.set(`${value.pid}@${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt }); }
const classpath = () => [classes, join(boot, 'BOOT-INF/classes'), ...readdirSync(join(boot, 'BOOT-INF/lib')).filter(file => file.endsWith('.jar')).map(file => join(boot, 'BOOT-INF/lib', file))].join(delimiter);
function probe(mode, pid, birth) { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 })); }
function inspectIdentities(name, mode = 'inspect-all') {
  const values = [...identities.values()], file = join(evidence, `${name}-identities.tsv`); assert.ok(values.length);
  writeFileSync(file, values.map(value => `${value.pid}\t${value.processStartedAt}\n`).join(''));
  const args = ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, file]; save(`${name}-command.json`, { command: [java, ...args], inputSha256: digest(readFileSync(file)), originalIdentities: values });
  const results = JSON.parse(execFileSync(java, args, { encoding: 'utf8', windowsHide: true, timeout: 60000, maxBuffer: 8 * 1024 * 1024 })); assert.equal(results.length, values.length);
  results.forEach((result, index) => { assert.equal(result.pid, values[index].pid); assert.equal(Date.parse(result.originalStartedAt), Date.parse(values[index].processStartedAt)); }); save(`${name}.json`, results); return results;
}
async function facts(taskId) {
  const [task, requests, executions, sessions, operations, writes] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/model-requests?limit=100`), api(`/tasks/${taskId}/task-executions?limit=100`), api(`/tasks/${taskId}/process-sessions`), api(`/tasks/${taskId}/operations`), api(`/tasks/${taskId}/workspace-write-leases?limit=100`)]);
  for (const session of sessions) { remember(session); session.recordedProcesses.forEach(remember); }
  assert.equal(requests.hasMore, false); assert.equal(executions.hasMore, false); return { task, requests: requests.requests, executions: executions.executions, sessions, operations, writes: writes.leases };
}
async function settled(taskId, state) {
  return until(`Original ${state} root`, async () => {
    const current = await facts(taskId);
    if (['FAILED', 'UNKNOWN', 'CANCELLED', 'WAITING_INPUT'].includes(current.task.state)) throw new Error(`Unexpected task ${current.task.state}: ${redact(current.task.error || '')}`);
    return current.task.state === state && current.executions[0]?.endedAt ? current : null;
  });
}
const checkpointPage = (taskId, limit = 100, before = 0) => api(`/tasks/${taskId}/checkpoints?limit=${limit}&before=${before}`);
const preview = (taskId, checkpointId, expected = 200) => api(`/tasks/${taskId}/checkpoint-restores/preview`, { checkpointId }, expected);
const restore = (taskId, previewId, expected = 200) => api(`/tasks/${taskId}/checkpoint-restores`, { previewId }, expected);
function databaseFault(mode, taskId) { execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classpath(), 'dev.agentworkbench.CheckpointDatabaseFixture', data, mode, taskId], { encoding: 'utf8', windowsHide: true, timeout: 15000 }); }
function lockObservation(taskId, name) {
  const result = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classpath(), 'dev.agentworkbench.WorkspaceWriteProbe', 'lock', join(data, 'workspaces', taskId, 'writer.lock')], { encoding: 'utf8', windowsHide: true, timeout: 15000 }));
  save(`${name}-lock.json`, result); remember(result); assert.equal(probe('inspect', result.pid, result.processStartedAt).originalStopped, true); return result;
}
async function startCore() {
  const number = cores.length + 1, child = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${nioDirectory}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', '-jar', jar],
    { cwd: directory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  const core = { number, child, logs: '', error: null }; cores.push(core); currentCore = core;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => core.logs += bytes.toString()); child.once('error', error => core.error = error);
  core.identity = probe('identity', child.pid, '-'); remember(core.identity);
  await until('Original ordinary Jar startup', () => { if (core.error) throw core.error; if (child.exitCode !== null || child.signalCode !== null) throw new Error('Original core exited during startup'); const port = core.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.checkpointProtocolVersion, 1); assert.equal(health.schemaVersion, 13); assert.equal(health.apiVersion, 1); assert.equal(health.fileToolProtocolVersion, 2);
  core.worker = (await api('/workers')).find(value => value.state === 'RUNNING'); assert.ok(core.worker); remember(core.worker); assert.notEqual(core.worker.pid, core.identity.pid); assert.equal(core.worker.deployment, 'LOCAL_PROCESS');
  assert.equal(probe('inspect', core.worker.pid, core.worker.processStartedAt).matchesOriginal, true); save(`core-${number}-started.json`, { health, identity: core.identity, worker: core.worker }); return core;
}
async function stopCore(core) {
  if (core.child.exitCode !== null || core.child.signalCode !== null) return;
  await api('/shutdown', {}); await until('Original core shutdown', () => core.child.exitCode !== null || core.child.signalCode !== null, 30000);
  await until('Original Worker shutdown', () => probe('inspect', core.worker.pid, core.worker.processStartedAt).originalStopped, 15000);
  save(`core-${core.number}-stopped.json`, { core: probe('inspect', core.identity.pid, core.identity.processStartedAt), worker: probe('inspect', core.worker.pid, core.worker.processStartedAt) });
}
function zipFiles(bytes) {
  let end = bytes.length - 22; while (end >= Math.max(0, bytes.length - 65557) && bytes.readUInt32LE(end) !== 0x06054b50) end--;
  assert.ok(end >= 0); const count = bytes.readUInt16LE(end + 10), files = new Map(); let cursor = bytes.readUInt32LE(end + 16);
  for (let index = 0; index < count; index++) {
    assert.equal(bytes.readUInt32LE(cursor), 0x02014b50); const length = bytes.readUInt16LE(cursor + 28), extra = bytes.readUInt16LE(cursor + 30), comment = bytes.readUInt16LE(cursor + 32), name = bytes.subarray(cursor + 46, cursor + 46 + length).toString('utf8');
    assert.ok(!files.has(name) && !name.startsWith('/') && !name.split('/').includes('..')); files.set(name, zipEntry(bytes, name)); cursor += 46 + length + extra + comment;
  }
  return files;
}
async function archive(taskId, name, expected = 200) {
  const response = await fetch(`${baseUrl}/api/tasks/${taskId}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) }); assert.equal(response.status, expected);
  if (expected !== 200) { save(`${name}-rejected.json`, { httpStatus: response.status, result: JSON.parse(await response.text()) }); return null; }
  const bytes = Buffer.from(await response.arrayBuffer()); writeFileSync(join(evidence, `${name}.zip`), bytes); const files = zipFiles(bytes), decode = path => JSON.parse(files.get(path));
  const checkpoints = decode('checkpoints.json'), restores = decode('checkpoint-restores.json'), references = new Map(checkpoints.map(value => [value.id, value]));
  for (const value of restores) for (const record of [value.target, value.source, value.current, value.result?.resultCheckpoint, value.observedReceipt?.result?.resultCheckpoint]) if (record) references.set(record.id, record);
  for (const [path, content] of files) if (path.startsWith('checkpoints/restores/')) { const journal = JSON.parse(content); for (const record of [journal.target, journal.source, journal.current, journal.result]) if (record) references.set(record.id, record); }
  const required = new Set();
  for (const record of references.values()) {
    const manifestBytes = files.get(`checkpoints/tasks/${record.id}/manifest.json`); assert.ok(manifestBytes); assert.equal(digest(manifestBytes), record.manifestSha256);
    const manifest = JSON.parse(manifestBytes); assert.equal(manifest.id, record.id); assert.equal(manifest.taskId, taskId);
    for (const file of manifest.files) if (file.state === 'STORED') { const object = files.get(`checkpoints/objects/${file.blob}`); assert.ok(object); assert.equal(digest(object), file.sha256); assert.equal(object.length, file.bytes); required.add(file.blob); }
    if (manifest.index.present) { const object = files.get(`checkpoints/objects/${manifest.index.blob}`); assert.ok(object); assert.equal(digest(object), manifest.index.sha256); required.add(manifest.index.blob); }
    for (const staged of manifest.index.stagedObjects) { const object = files.get(`checkpoints/objects/${staged.blob}`); assert.ok(object); assert.equal(digest(object), staged.blob); assert.equal(object.length, staged.bytes); assert.equal(createHash(manifest.objectFormat === 'sha1' ? 'sha1' : 'sha256').update(Buffer.from(`blob ${object.length}\0`)).update(object).digest('hex'), staged.objectId); required.add(staged.blob); }
  }
  const actual = [...files.keys()].filter(path => path.startsWith('checkpoints/objects/')).map(path => path.slice('checkpoints/objects/'.length)); assert.deepEqual(actual.sort(), [...required].sort());
  const manifest = decode('manifest.json'); assert.equal(manifest.checkpointArchiveBytes, [...files].filter(([path]) => path.startsWith('checkpoints/')).reduce((sum, [, value]) => sum + value.length, 0));
  for (const session of decode('process-sessions.json')) { remember(session); session.recordedProcesses.forEach(remember); }
  save(`${name}-zip-verification.json`, { bytes: bytes.length, sha256: digest(bytes), entries: files.size, checkpointReferences: references.size, deduplicatedObjects: actual.length, manifest });
  return { files, decode, checkpoints, restores, manifest };
}
const model = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const chunk of request) chunks.push(chunk); const bytes = Buffer.concat(chunks), body = JSON.parse(bytes), { prompt, results, names } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(request.headers.authorization, `Bearer ${credential}`);
    const marker = [...tasks.keys()].find(value => prompt.includes(value)); assert.ok(marker); const current = await facts(tasks.get(marker)), original = current.requests.find(value => value.state === 'DISPATCHING' && value.inputDigest === digest(bytes)); assert.ok(original);
    assert.equal(original.executorPid, currentCore.worker.pid); assert.equal(original.processStartedAt, currentCore.worker.processStartedAt);
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1]; calls.push({ number: calls.length + 1, marker, requestId: original.id, agentRunId: original.agentRunId, taskExecutionId: original.taskExecutionId, workspaceWriteLeaseId: original.workspaceWriteLeaseId, executorPid: original.executorPid, processStartedAt: original.processStartedAt, stage });
    const tool = (name, args) => { assert.ok(names.includes(name)); return { role: 'assistant', content: null, tool_calls: [{ id: `checkpoint-${marker}-${results.length}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] }; };
    const result = index => JSON.parse(results[index].content); results.forEach((_, index) => assert.equal(result(index).error, undefined));
    let message = { role: 'assistant', content: 'Original stage completed.' };
    if (stage === 'IMPLEMENT') {
      if (!results.length) message = tool('read_file', { path: 'app.txt' });
      else if (results.length === 1) { assert.equal(result(0).content, originalText); message = tool('write_file', { path: 'app.txt', content: changedText, expectedVersion: result(0).readVersion }); }
      else if (results.length === 2) message = tool('inspect_file', { path: 'binary.dat' });
      else if (results.length === 3) message = tool('delete_file', { path: 'binary.dat', expectedVersion: result(2).readVersion });
      else if (results.length === 4) message = tool('write_file', { path: literalPath, content: literalText, expectedVersion: 'ABSENT' });
      else { assert.equal(results.length, 5, 'Original file tools must not replay'); assert.equal(readFileSync(join(current.task.workspace, 'app.txt'), 'utf8'), changedText); }
    } else if (stage === 'REVIEW') message = { role: 'assistant', content: '{"approved":true,"issues":[{"title":"original review suggestion","severity":"suggestion","evidence":"original app.txt reviewed version"}]}' };
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: `checkpoint-response-${calls.length}`, model: body.model, choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { modelErrors.push(redact(error.message)); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end('{"error":{"message":"Local checkpoint fixture failed"}}'); }
});
const web = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    if (path.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk); const body = Buffer.concat(chunks);
      if (request.method === 'POST' && /\/checkpoint-restores$/.test(path)) wireRestores.push({ path, body: JSON.parse(body), at: new Date().toISOString() });
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' }, ...(!['GET', 'HEAD'].includes(request.method) ? { body } : {}), signal: AbortSignal.timeout(30000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (path === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"apiBaseUrl":"/service"}'); return; }
    if (!/^\/(?:index\.html|theme-init\.js|assets\/[a-zA-Z0-9_.-]+)?$/.test(path)) { response.writeHead(404); response.end(); return; }
    const file = path === '/' ? '/index.html' : path, bytes = readFileSync(join(root, 'web/dist', file)); served.set(file, digest(bytes)); response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(file)] || 'application/octet-stream' }); response.end(bytes);
  } catch { response.writeHead(502); response.end(); }
});

try {
  execFileSync(join(javaRoot, 'bin', windows ? 'jar.exe' : 'jar'), ['xf', jar], { cwd: boot, windowsHide: true, timeout: 30000 });
  const sources = ['CheckpointDatabaseFixture.java', 'ProcessRecoveryProbe.java', 'WorkspaceWriteProbe.java'].map(file => join(root, 'tools', file));
  execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-cp', classpath(), '-d', classes, ...sources], { windowsHide: true, timeout: 30000 });
  sources.forEach(file => writeFileSync(join(evidence, file.split(/[\\/]/).at(-1)), readFileSync(file))); writeFileSync(join(evidence, 'checkpoint-smoke-source.mjs'), scriptSource);
  save('fixture.json', { version, fixture: 'ACTUAL_JAR_CHECKPOINT_RESTORE_WITH_SQLITE_RECEIPT_FAULTS', productionEntry: 'JarLauncher', jarSha256, scriptSha256, webFiles, schedulerParallelism: 2, schedulerMaxPoolSize: 2,
    fixtureClasses: readdirSync(classes, { recursive: true }).filter(file => file.endsWith('.class')).map(file => ({ file: file.replaceAll('\\', '/'), sha256: digest(readFileSync(join(classes, file))) })),
    productionClasses: ['dev/agentworkbench/CheckpointLedger.class', 'dev/agentworkbench/CheckpointExportHistory.class', 'dev/agentworkbench/WorkerProcessMain.class', 'dev/agentworkbench/runtime/WorkspaceRuntime.class', 'dev/agentworkbench/runtime/WorkspaceCheckpointArchive.class', 'dev/agentworkbench/runtime/GitBlobBatch.class'].map(file => ({ file, sha256: digest(zipEntry(readFileSync(jar), `BOOT-INF/classes/${file}`)) })), sources: sources.map(file => ({ file, sha256: digest(readFileSync(file)) })), processCoverage: 'OBSERVED_ONLY; CORE_CHECKPOINT_READS_NOT_FULL_HISTORICAL_PROCESS_COVERAGE' });
  mkdirSync(source); writeFileSync(join(source, '.gitattributes'), '* -text\n'); writeFileSync(join(source, 'app.txt'), originalText); writeFileSync(join(source, 'binary.dat'), binary); writeFileSync(join(source, 'empty.txt'), ''); writeFileSync(join(source, 'later.txt'), 'original unrelated\n');
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict';import{readFileSync}from'node:fs';assert.equal(process.env.WORKBENCH_TOKEN,undefined);assert.ok(['用户原始内容\\n第二行\\n','实现后的内容\\n第二行\\n'].includes(readFileSync('app.txt','utf8')));console.log('ORIGINAL_CHECKPOINT_VERIFIED '+process.pid);\n");
  git(source, 'init', '--initial-branch=main'); git(source, 'config', 'user.name', 'Checkpoint fixture'); git(source, 'config', 'user.email', 'checkpoint@example.invalid'); git(source, 'add', '.'); git(source, 'commit', '-m', 'original checkpoint fixture');
  const sourceBefore = treeFacts(source); save('original-source.json', sourceBefore); await new Promise(done => model.listen(0, '127.0.0.1', done)); await startCore();
  pass('Ordinary final Jar exposed checkpoint protocol 1 with original independent Worker and schema 13');
  const project = await api('/projects', { path: source }); let route = await api('/routes', { name: 'Actual checkpoint fixture', engine: 'API', model: 'checkpoint-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, apiKey: credential });
  async function completedTask(marker, strategy) {
    const task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `真实检查点 ${marker}`, goal: marker, mode: 'LEAD', workspaceSelection: { strategy, ref: 'HEAD' }, contract: { scope: ['**'], acceptance: ['Original byte and checkpoint receipts remain reviewable'], commands: ['node verify.mjs'] }, budget: { maxCalls: 24, reserveCalls: 4 } });
    tasks.set(marker, task.id); const empty = await checkpointPage(task.id); assert.deepEqual(empty.checkpoints, []); assert.equal(empty.undoAllowed, false);
    assert.equal((await fetch(`${baseUrl}/api/tasks/${task.id}/checkpoints`, { signal: AbortSignal.timeout(15000) })).status, 401);
    await api(`/tasks/${task.id}/approve`, {}); const current = await settled(task.id, 'AWAITING_ACCEPTANCE'), saved = await checkpointPage(task.id); assert.equal(saved.hasMore, false); assert.equal(saved.undoAllowed, true);
    const tools = saved.checkpoints.filter(value => value.metadata.kind === 'TOOL_COMPLETED'); assert.equal(tools.length, 5); assert.equal(current.task.budget.usedCalls, 8);
    for (const checkpoint of tools) { const request = current.requests.find(value => value.id === checkpoint.metadata.modelRequestId); assert.equal(request.state, 'SUCCEEDED'); assert.equal(checkpoint.captureExecutorPid, currentCore.worker.pid); assert.equal(checkpoint.captureProcessStartedAt, currentCore.worker.processStartedAt); assert.equal(checkpoint.metadata.source.workspaceWriteLeaseId, request.workspaceWriteLeaseId); }
    const target = saved.checkpoints.find(value => value.metadata.kind === 'STAGE_START' && value.metadata.stage === 'implement'); assert.ok(target); assert.deepEqual(treeFacts(source), sourceBefore); save(`${marker}-original-completed.json`, { current, saved, target });
    return { taskId: task.id, current, saved, target };
  }
  await new Promise(done => web.listen(0, '127.0.0.1', done)); browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(redact(error.message))); await page.goto(`http://127.0.0.1:${web.address().port}`);
  await page.getByRole('button', { name: '连接设置', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(token); await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  const panel = page.getByRole('region', { name: '检查点与撤销', exact: true });
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `${strategy} actual full plan and checkpoint history`; const marker = `CHECKPOINT_${strategy}`, original = await completedTask(marker, strategy), work = original.current.task.workspace;
    pass(`${strategy} actual original Worker published five tool checkpoints bound to successful requests and unchanged cumulative budget`);
    const targetManifest = JSON.parse(readFileSync(join(data, 'checkpoints/tasks', original.taskId, original.target.id, 'manifest.json'))), targetIndex = readFileSync(join(data, 'checkpoints/objects', targetManifest.index.blob.slice(0, 2), targetManifest.index.blob));
    git(work, 'update-index', '--assume-unchanged', 'app.txt'); const manual = await api(`/tasks/${original.taskId}/checkpoints`, { label: literalLabel }); writeFileSync(join(work, 'later.txt'), 'unrelated user edit after saved boundary\n');
    const first = await checkpointPage(original.taskId, 1), older = await checkpointPage(original.taskId, 1, first.cursor); assert.equal(first.checkpoints[0].id, manual.id); assert.equal(first.hasMore, true); assert.notEqual(older.checkpoints[0].id, manual.id); assert.ok(older.cursor < first.cursor);
    await api(`/tasks/${original.taskId}/checkpoints`, { workspace: source }, 400); await api(`/tasks/${original.taskId}/checkpoint-restores/preview`, { checkpointId: original.target.id, version: original.target.version }, 400); save(`${strategy}-stable-pages.json`, { first, older });
    pass(`${strategy} real manual checkpoint, authentication and stable cursors preserved original archive boundaries`);
    const stale = await preview(original.taskId, original.target.id); assert.equal(stale.canRestore, true); assert.equal(stale.indexChanges, true); assert.deepEqual(stale.changes.map(value => value.path).sort(), ['app.txt', 'binary.dat', literalPath].sort());
    await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: new RegExp(`^真实检查点 ${marker}`) }).click(); await page.getByRole('button', { name: '检查点与撤销', exact: true }).click();
    await expect(panel.getByText(literalLabel, { exact: true })).toBeVisible(); assert.equal(await panel.locator('script, img').count(), 0); assert.equal(await page.evaluate(() => window.checkpointInjected), undefined);
    const targetLabel = `阶段开始 · ${original.target.metadata.title || original.target.metadata.stage}`;
    await panel.locator('.checkpoint-choice').filter({ hasText: targetLabel }).click(); await panel.getByRole('button', { name: '预览撤销', exact: true }).click(); await expect(panel.getByText(literalPath, { exact: true })).toBeVisible();
    const beforeCancel = wireRestores.length; page.once('dialog', dialog => dialog.dismiss()); await panel.getByRole('button', { name: '确认撤销到此检查点', exact: true }).click(); assert.equal(wireRestores.length, beforeCancel); await expect(panel.getByRole('button', { name: '确认撤销到此检查点', exact: true })).toBeEnabled();
    for (const width of [1440, 430]) { await page.setViewportSize({ width, height: 1000 }); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true); await page.screenshot({ path: join(evidence, `${strategy}-production-preview-${width}.png`), fullPage: true, animations: 'disabled' }); }
    pass(`${strategy} production desktop and narrow Web displayed real affected files and preserved cancelled confirmation without a restore request`);
    writeFileSync(join(work, 'app.txt'), 'later affected user content\n'); const beforeStale = await facts(original.taskId); page.once('dialog', dialog => dialog.accept()); await panel.getByRole('button', { name: '确认撤销到此检查点', exact: true }).click();
    await expect(panel.getByRole('alert')).toBeVisible(); await expect(panel.getByRole('button', { name: '确认撤销到此检查点', exact: true })).toHaveCount(0); const afterStale = await facts(original.taskId);
    assert.equal(afterStale.executions.length, beforeStale.executions.length); assert.equal(afterStale.operations.length, beforeStale.operations.length); assert.equal(readFileSync(join(work, 'app.txt'), 'utf8'), 'later affected user content\n'); await restore(original.taskId, stale.id, 409); save(`${strategy}-stale-zero-writes.json`, { beforeStale, afterStale, stale });
    pass(`${strategy} actual stale confirmation returned 409 with zero new root operations and retained the affected user bytes`);
    writeFileSync(join(work, 'app.txt'), changedText); await panel.getByRole('button', { name: '刷新', exact: true }).click(); await panel.locator('.checkpoint-choice').filter({ hasText: targetLabel }).click(); await panel.getByRole('button', { name: '预览撤销', exact: true }).click(); await expect(panel.getByRole('button', { name: '确认撤销到此检查点', exact: true })).toBeEnabled();
    page.once('dialog', dialog => dialog.accept()); await panel.getByRole('button', { name: '确认撤销到此检查点', exact: true }).click(); const restored = await settled(original.taskId, 'PAUSED');
    assert.equal(restored.task.budget.usedCalls, original.current.task.budget.usedCalls); assert.deepEqual(restored.task.budget, original.current.task.budget); assert.equal(restored.executions.length, 2); assert.equal(restored.executions[1].id, original.current.executions[0].id); assert.equal(restored.executions[1].state, 'SUCCEEDED');
    assert.equal(restored.task.verification.status, 'NOT_RUN'); assert.equal(restored.task.review.status, 'NOT_RUN'); assert.deepEqual(restored.task.issues, []); assert.ok(restored.task.invalidatedEvidence.length); assert.deepEqual(restored.task.evidence, original.current.task.evidence);
    assert.equal(readFileSync(join(work, 'app.txt'), 'utf8'), originalText); assert.deepEqual(readFileSync(join(work, 'binary.dat')), binary); assert.equal(existsSync(join(work, literalPath)), false); assert.equal(readFileSync(join(work, 'later.txt'), 'utf8'), 'unrelated user edit after saved boundary\n');
    assert.deepEqual(readFileSync(resolve(work, git(work, 'rev-parse', '--git-path', 'index').trim())), targetIndex); assert.equal(git(work, 'rev-parse', 'HEAD').trim(), sourceBefore.head); assert.equal(lockObservation(original.taskId, `${strategy}-after-undo`).acquired, true); assert.deepEqual(treeFacts(source), sourceBefore); save(`${strategy}-restored.json`, restored);
    const exported = await archive(original.taskId, `${strategy}-actual-undo`), applied = exported.restores.find(value => value.id === restored.task.lastCheckpointRestoreId); assert.equal(applied.state, 'APPLIED'); assert.equal(applied.result.state, 'ACKNOWLEDGED'); assert.equal(exported.manifest.workspaceMatchesRecordedVersion, true);
    const afterExportIndex = readFileSync(resolve(work, git(work, 'rev-parse', '--git-path', 'index').trim())); assert.deepEqual(afterExportIndex, targetIndex); save(`${strategy}-index-after-export.json`, { taskId: original.taskId, targetCheckpointId: original.target.id, targetIndexSha256: digest(targetIndex), afterExportIndexSha256: digest(afterExportIndex), indexBytesRetainedAfterExport: true });
    assert.equal(exported.decode('operations.json').find(value => value.kind === 'CHECKPOINT_RESTORE').executorPid, currentCore.worker.pid); assert.equal(JSON.parse(exported.files.get(`checkpoints/restores/${applied.operationId}/manifest.json`)).state, 'APPLIED'); await restore(original.taskId, applied.id, 409);
    pass(`${strategy} original Worker restored exact file/index bytes, preserved unrelated edits and evidence, released the real root lock and exported deduplicated original references without model replay`);
  }
  await browser.close(); browser = undefined;
  for (const fault of ['ack', 'publish']) {
    phase = `${fault} ordinary Jar receipt fault`; const marker = `CHECKPOINT_FAULT_${fault.toUpperCase()}`, original = await completedTask(marker, 'CLONE'), work = original.current.task.workspace, request = await preview(original.taskId, original.target.id); databaseFault(`fail-${fault}`, original.taskId); await restore(original.taskId, request.id);
    await until('Original SQLite receipt fault', async () => (await api('/health')).status === 'DEGRADED'); const occurred = await facts(original.taskId), operation = occurred.operations.find(value => value.kind === 'CHECKPOINT_RESTORE'); assert.ok(operation);
    const journalPath = join(data, 'checkpoints/restores', original.taskId, operation.id, 'manifest.json'), journal = JSON.parse(readFileSync(journalPath)); assert.equal(journal.state, 'APPLIED'); assert.equal(readFileSync(join(work, 'app.txt'), 'utf8'), originalText); assert.deepEqual(readFileSync(join(work, 'binary.dat')), binary); assert.equal(existsSync(join(work, literalPath)), false);
    assert.equal(occurred.task.budget.usedCalls, 8);
    const write = occurred.writes.find(value => value.taskExecutionId === occurred.executions[0].id); assert.ok(write); assert.equal(write.state, 'HELD'); assert.equal(write.protectionReleased, false);
    assert.equal(write.executorPid, currentCore.worker.pid); assert.equal(write.processStartedAt, currentCore.worker.processStartedAt);
    const ownerObservationStartedAt = new Date().toISOString(), ownerBefore = probe('inspect', currentCore.worker.pid, currentCore.worker.processStartedAt);
    const originalLock = lockObservation(original.taskId, `${fault}-unconfirmed`), ownerAfter = probe('inspect', currentCore.worker.pid, currentCore.worker.processStartedAt);
    save(`${fault}-original-owner-at-lock.json`, { startedAt: ownerObservationStartedAt, completedAt: new Date().toISOString(), worker: currentCore.worker, ownerBefore, originalLock, ownerAfter, gate: 'LOCK_HELD_OR_ORIGINAL_WORKER_STOPPED', jarSha256 });
    save(`${fault}-original-returned.json`, { original, occurred, journal });
    if (originalLock.acquired) assert.equal(ownerAfter.originalStopped, true, 'A free unconfirmed root lock requires independent proof that its original Worker stopped');
    const oldCore = currentCore; await stopCore(oldCore); databaseFault('clear', original.taskId); await startCore(); const reopened = await facts(original.taskId); assert.equal(reopened.task.state, 'UNKNOWN'); assert.equal(reopened.executions.length, 2); assert.equal(reopened.executions[0].id, occurred.executions[0].id); assert.deepEqual(reopened.task.budget, original.current.task.budget); assert.deepEqual(JSON.parse(readFileSync(journalPath)), journal); await api(`/tasks/${original.taskId}/resume`, {}, 409);
    const exported = await archive(original.taskId, `${fault}-unknown-original`), unknown = exported.restores.find(value => value.id === request.id); assert.equal(unknown.state, 'UNKNOWN'); assert.equal(unknown.observedReceipt.result.state, 'ACKNOWLEDGED'); assert.equal(exported.manifest.unknownRestoreEffects, true); assert.equal(exported.manifest.workspaceMatchesRecordedVersion, false); assert.equal(exported.manifest.version, original.current.task.version); assert.notEqual(exported.manifest.version, exported.manifest.workspaceVersion);
    assert.equal(exported.files.has(`checkpoints/tasks/${journal.result.id}/manifest.json`), true); assert.equal(reopened.operations.find(value => value.id === operation.id).state, fault === 'publish' ? 'ACKNOWLEDGED' : 'UNKNOWN'); assert.equal(calls.filter(value => value.marker === marker).length, 8); save(`${fault}-reopened-unknown.json`, reopened);
    pass(`${fault} real SQLite fault retained applied files, original observed receipt and unpublished result checkpoint as UNKNOWN across restart without replay or budget rollback`);
    const recovery = await api('/process-recovery'); assert.equal(recovery.hasMore, false);
    for (const session of recovery.sessions) { remember(session); session.recordedProcesses.forEach(remember); const detail = await api(`/process-recovery/${session.id}`); assert.equal(detail.observedProcessesStopped, true); await api(`/process-recovery/${session.id}/reconcile`, { processesStopped: true, coverageChecked: true, note: 'Dedicated ordinary Jar checkpoint fixture: original core/Worker birth identities and observed descendants checked; no independent fixture commands remain in the task.' }); }
    await api(`/tasks/${original.taskId}/reconcile`, { processesStopped: true, sideEffectsChecked: true, note: 'Original applied journal, exact app/binary bytes, absent new file and fixed HEAD inspected; old UNKNOWN receipt is retained.' });
    const checked = await facts(original.taskId); assert.equal(checked.task.state, 'PAUSED'); await restore(original.taskId, request.id, 409); assert.equal(calls.filter(value => value.marker === marker).length, 8); const stillUnknown = await archive(original.taskId, `${fault}-reconciled-export`, 409);
    assert.equal(stillUnknown, null); pass(`${fault} explicit process/effect reconciliation preserved UNKNOWN history and refused the already consumed original preview`);
    route = await api('/routes', { id: route.id, name: 'Actual checkpoint fixture', engine: 'API', model: 'checkpoint-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, apiKey: credential });
  }
  assert.deepEqual(modelErrors, []); assert.deepEqual(pageErrors, []); assert.equal(calls.length, 32); assert.equal(served.get('/theme-init.js'), digest(readFileSync(join(root, 'web/dist/theme-init.js')))); save('production-served-files.json', [...served].map(([file, sha256]) => ({ file, sha256 })));
  phase = 'original process stop verification'; await stopCore(currentCore); const stopped = inspectIdentities('after-shutdown'); assert.ok(stopped.every(value => value.originalStopped)); assert.deepEqual(treeFacts(source), sourceBefore);
  for (const strategy of ['CLONE', 'WORKTREE']) { const original = JSON.parse(readFileSync(join(evidence, `CHECKPOINT_${strategy}-original-completed.json`))), work = original.current.task.workspace, targetManifest = JSON.parse(readFileSync(join(data, 'checkpoints/tasks', original.current.task.id, original.target.id, 'manifest.json'))); assert.equal(digest(readFileSync(resolve(work, git(work, 'rev-parse', '--git-path', 'index').trim()))), targetManifest.index.sha256); }
  assert.equal(digest(readFileSync(jar)), jarSha256); for (const file of webFiles) assert.equal(digest(readFileSync(join(root, file.file))), file.sha256);
  pass('Every recorded original PID/birth stopped independently and final Jar, production Web and original source bytes remained fixed');
  report = { platform: process.platform, version, status: 'passed', checks, completedAt: new Date().toISOString(), directory, jarSha256, scriptSha256, webFiles, modelCalls: calls.length, originalProcessIdentities: identities.size, coreStarts: cores.length, browserVersion: modules('@playwright/test/package.json').version,
    evidenceScope: 'ACTUAL_ORDINARY_FINAL_JAR_ORIGINAL_WORKER_REAL_GIT_CLONE_WORKTREE_AND_PRODUCTION_WEB', processCoverage: 'OBSERVED_ONLY; CORE_CHECKPOINT_READS_NOT_FULL_HISTORICAL_PROCESS_COVERAGE', modelErrors, pageErrors };
} catch (error) {
  report = { platform: process.platform, version, status: 'failed', phase, error: redact(error.message), checks, completedAt: new Date().toISOString(), directory, jarSha256, scriptSha256, webFiles, modelErrors, pageErrors }; process.exitCode = 1; console.error(`FAIL ${phase}: ${redact(error.message)}; original evidence ${directory}`);
} finally {
  try { await browser?.close(); } catch (error) { save('browser-cleanup-error.json', { error: redact(error.message) }); }
  if (identities.size && existsSync(join(evidence, 'fixture.json'))) try { const stopped = inspectIdentities('failure-or-final-cleanup', 'stop-all'); assert.ok(stopped.every(value => value.originalStopped)); } catch (error) { report = { ...report, status: 'failed', cleanupError: redact(error.message) }; process.exitCode = 1; }
  for (const core of cores) writeFileSync(join(evidence, `core-${core.number}.log`), redact(core.logs)); save('model-calls.json', calls); save('wire-restores.json', wireRestores); save('result.json', report);
  await new Promise(done => model.close(done)); await new Promise(done => web.close(done)); mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/checkpoints-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, checks: checks.length, directory }));
}
