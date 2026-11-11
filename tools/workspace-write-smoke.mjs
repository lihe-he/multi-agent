import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { delimiter, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { inspectModel, zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), windows = process.platform === 'win32';
const browserModules = createRequire(join(root, 'web/package.json')), { chromium } = browserModules('@playwright/test');
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version, browserVersion = browserModules('@playwright/test/package.json').version;
const javaRoot = javaHome(root), java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java'), jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
if (!existsSync(jar) || !existsSync(join(root, 'web/dist/index.html'))) throw new Error('请先构建当前核心与生产 Web。');
mkdirSync(join(root, '.runtime'), { recursive: true });
const directory = mkdtempSync(join(root, '.runtime/workspace-writes-')), evidence = join(directory, 'reports'), data = join(directory, 'data'), source = join(directory, '中文 write source');
mkdirSync(evidence); const token = randomBytes(32).toString('hex'), checks = [], calls = [], modelErrors = [], pageErrors = [], tasks = new Map(), gates = new Map(), cores = [], identities = new Map();
let nioDirectory = join(directory, 'nio-tmp');
if (windows) while (Buffer.byteLength(nioDirectory, 'utf8') < 120) nioDirectory = join(nioDirectory, 'workbench-jdk-loopback');
mkdirSync(nioDirectory, { recursive: true });
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const jarSha256 = digest(readFileSync(jar)), scriptSource = readFileSync(fileURLToPath(import.meta.url)), scriptSha256 = digest(scriptSource);
const productionWebHashes = readdirSync(join(root, 'web/dist'), { recursive: true }).filter(file => /\.(html|js|css)$/.test(file)).map(file => ({ file: `web/dist/${file.replaceAll('\\', '/')}`, sha256: digest(readFileSync(join(root, 'web/dist', file))) }));
let currentCore, baseUrl, browser, page, report, phase = 'fixture compilation', normalTasks = [], faultTasks = [];
const pause = milliseconds => new Promise(done => setTimeout(done, milliseconds));
const save = (name, value) => writeFileSync(join(evidence, name), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
const ended = core => core.child.exitCode !== null || core.child.signalCode !== null;
function remember(value) { if (value?.pid && value.processStartedAt) identities.set(`${value.pid}@${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt }); }
async function until(name, action, timeout = 120000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await pause(100); }
  throw new Error(`${name} 未在有界期限内达到预期`);
}
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(20000) });
  const text = await response.text(); let result; try { result = JSON.parse(text); } catch { result = text; }
  assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${text.slice(0, 500)}`); return result;
}
async function facts(taskId) {
  const [task, executions, actions, writes, operations, leases, requests] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/task-executions?limit=100`), api(`/tasks/${taskId}/dispatch-actions?limit=100`),
    api(`/tasks/${taskId}/workspace-write-leases?limit=100`), api(`/tasks/${taskId}/operations`), api(`/tasks/${taskId}/worker-leases?limit=100`), api(`/tasks/${taskId}/model-requests?limit=100`)]);
  return { task, executions: executions.executions, actions: actions.actions, writes: writes.leases, operations, leases: leases.leases, requests: requests.requests };
}
function git(...arguments_) { return execFileSync('git', ['--no-optional-locks', '-c', 'core.hooksPath=/dev/null', '-c', 'core.autocrlf=false', '-C', source, ...arguments_], { encoding: 'utf8', windowsHide: true, timeout: 20000 }); }
function sourceFacts() { return { head: git('rev-parse', 'HEAD').trim(), branch: git('branch', '--show-current').trim(), indexSha256: digest(readFileSync(join(source, '.git/index'))), configSha256: digest(readFileSync(join(source, '.git/config'))),
  refs: git('for-each-ref', '--format=%(refname) %(objectname)'), status: git('status', '--porcelain=v1', '-uall'), appSha256: digest(readFileSync(join(source, 'app.txt'))) }; }
function sourceUnchanged(expected) { assert.deepEqual(sourceFacts(), expected, 'The original source, HEAD, branch, index and configuration must be preserved'); }
function classpath() { const boot = join(directory, 'boot'); return [join(directory, 'fixture-classes'), join(boot, 'BOOT-INF/classes'), ...readdirSync(join(boot, 'BOOT-INF/lib')).filter(file => file.endsWith('.jar')).map(file => join(boot, 'BOOT-INF/lib', file))].join(delimiter); }
function compileFixture() {
  const boot = join(directory, 'boot'), classes = join(directory, 'fixture-classes'); mkdirSync(boot); mkdirSync(classes);
  execFileSync(join(javaRoot, 'bin', windows ? 'jar.exe' : 'jar'), ['xf', jar], { cwd: boot, encoding: 'utf8', windowsHide: true, timeout: 30000 });
  const sources = ['WorkspaceWriteDatabaseFixture.java', 'WorkspaceWriteProbe.java', 'ProcessRecoveryProbe.java'].map(file => join(root, 'tools', file));
  execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-cp', classpath(), '-d', classes, ...sources], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  writeFileSync(join(evidence, 'workspace-write-smoke-source.mjs'), scriptSource); for (const file of sources) writeFileSync(join(evidence, file.split(/[\\/]/).at(-1)), readFileSync(file));
  const fixtureClasses = readdirSync(classes, { recursive: true }).filter(file => file.endsWith('.class')).map(file => ({ file: file.replaceAll('\\', '/'), sha256: digest(readFileSync(join(classes, file))) }));
  const productionClasses = ['WorkbenchEngine', 'WorkerProcessMain', 'WorkspaceWriteLedger', 'WindowsProcessSnapshot'].map(name => `dev/agentworkbench/${name}.class`).concat(['dev/agentworkbench/runtime/WorkspaceWriteGuard.class', 'dev/agentworkbench/runtime/WorkspaceFileIdentity.class']);
  const nativeDependencies = ['jna-5.17.0.jar', 'jna-platform-5.17.0.jar'].map(file => { const bytes = readFileSync(join(boot, 'BOOT-INF/lib', file)); return { file, bytes: bytes.length, sha256: digest(bytes) }; });
  assert.equal(nativeDependencies[0].sha256, 'b3a9408e7c51e08ef0e3bfcc08f443f6ec0f6191ba8cd7c18d53d2b22e5bdbc0'); assert.equal(nativeDependencies[1].sha256, 'b7e3d46c87bad2eb409b0e704916bcd81206168e357312dfddd0e253679cd9e0');
  save('fixture.json', { version, fixture: 'ACTUAL_JAR_WORKSPACE_WRITES_WITH_SQLITE_RECEIPT_FAULTS', productionEntry: 'JarLauncher', jar, jarSha256, scriptSha256, productionWebHashes, fixtureClasses,
    productionClasses: productionClasses.map(file => ({ file, sha256: digest(readFileSync(join(boot, 'BOOT-INF/classes', file))) })), nativeDependencies,
    sources: sources.map(file => ({ file, sha256: digest(readFileSync(file)) })), schedulerParallelism: 2, schedulerMaxPoolSize: 2 });
}
function processProbe(pid, birth, mode = 'inspect') { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', join(directory, 'fixture-classes'), 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 })); }
function stopOriginalIdentities(name) {
  const originals = [...identities.values()], file = join(evidence, `${name}-identities.tsv`);
  assert.ok(originals.length > 0); writeFileSync(file, originals.map(value => `${value.pid}\t${value.processStartedAt}\n`).join(''));
  const command = [java, '-Dfile.encoding=UTF-8', '-cp', join(directory, 'fixture-classes'), 'ProcessRecoveryProbe', 'stop-all', file];
  save(`${name}-command.json`, { command, inputSha256: digest(readFileSync(file)), originalIdentities: originals });
  const result = JSON.parse(execFileSync(command[0], command.slice(1), { encoding: 'utf8', windowsHide: true, timeout: 90000, maxBuffer: 8 * 1024 * 1024 }));
  assert.equal(result.length, originals.length);
  for (let index = 0; index < result.length; index++) { assert.equal(result[index].pid, originals[index].pid); assert.equal(Date.parse(result[index].originalStartedAt), Date.parse(originals[index].processStartedAt)); }
  return result;
}
function writeProbe(mode, lease, name) {
  const identity = Object.fromEntries(['schemaVersion', 'id', 'taskId', 'projectId', 'taskExecutionId', 'dispatchActionId', 'attempt', 'scopePath', 'scopeKey', 'workerId', 'executionNodeId', 'workerEpoch', 'executorPid', 'processStartedAt', 'ownerCoreSessionId', 'ownerCorePid', 'ownerCoreStartedAt', 'authorizationVersion', 'workspaceSelectionVersion', 'strategy', 'baseline'].map(field => [field, lease[field]]));
  const identityFile = join(evidence, `${name}-identity.json`); save(`${name}-identity.json`, identity);
  const parameters = mode === 'lock' ? ['lock', join(data, 'workspaces', lease.taskId, 'writer.lock')] : ['contend', data, identityFile];
  const result = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${nioDirectory}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', '-cp', classpath(), 'dev.agentworkbench.WorkspaceWriteProbe', ...parameters], { encoding: 'utf8', windowsHide: true, timeout: 20000 }));
  save(`${name}.json`, result); remember(result); assert.equal(processProbe(result.pid, result.processStartedAt).originalStopped, true); return result;
}
function databaseFault(mode, taskId) { return execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classpath(), 'dev.agentworkbench.WorkspaceWriteDatabaseFixture', data, mode, taskId], { encoding: 'utf8', windowsHide: true, timeout: 15000 }); }
async function startCore() {
  const number = cores.length + 1, child = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${nioDirectory}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', '-jar', jar],
    { cwd: directory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  const core = { number, child, logs: '', error: null }; cores.push(core); currentCore = core;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { core.logs += bytes.toString(); }); child.once('error', error => { core.error = error; });
  core.identity = processProbe(child.pid, '-', 'identity'); remember(core.identity);
  await until('原 Jar 核心启动', () => { if (core.error) throw core.error; if (ended(core)) throw new Error(`原核心 ${number} 提前退出`); const port = core.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.schemaVersion, 8); assert.equal(health.corePid, child.pid);
  core.worker = (await api('/workers')).find(worker => worker.state === 'RUNNING'); assert.ok(core.worker); assert.equal(core.worker.deployment, 'LOCAL_PROCESS'); assert.equal(core.worker.corePid, child.pid); assert.notEqual(core.worker.pid, child.pid);
  remember(core.worker); assert.equal(core.identity.processStartedAt, core.worker.coreStartedAt); save(`core-${number}-started.json`, { health, worker: core.worker, identity: core.identity }); return core;
}
async function stopCore(core) {
  if (ended(core)) return;
  await api('/shutdown', {}); await until('原 Jar 核心退出', () => ended(core), 30000); save(`core-${core.number}-stopped.json`, processProbe(core.identity.pid, core.identity.processStartedAt));
  writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}
async function options(project) { return api(`/repository-bindings/${project.repositoryBindingId}/workspace-options`); }
async function createTask(project, route, marker, strategy, blocked = false) {
  const available = await options(project), task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `写入保护 ${marker}`, goal: marker, mode: 'LEAD', includeChanges: false,
    workspaceSelection: { strategy, ref: 'HEAD', expectedHead: available.source.head, sourceVersion: available.source.version, sourceIndex: available.source.index },
    contract: { scope: ['**'], acceptance: ['Original writer, results and recovery receipts are preserved'], commands: ['node verify.mjs'] }, budget: { maxCalls: 12, reserveCalls: 4 } });
  tasks.set(marker, task.id); if (blocked) { let release; const promise = new Promise(done => { release = done; }); gates.set(marker, { release, promise }); } return task;
}
async function succeeded(taskId) {
  return until('原根成功结束', async () => { const current = await facts(taskId); assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT', 'CANCELLED'].includes(current.task.state), `${current.task.state}: ${current.task.error}`);
    return current.executions[0]?.state === 'SUCCEEDED' && current.task.state === 'AWAITING_ACCEPTANCE' && current; });
}
async function archive(taskId, name) {
  const response = await fetch(`${baseUrl}/api/tasks/${taskId}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20000) }); assert.equal(response.status, 200);
  const bytes = Buffer.from(await response.arrayBuffer()); writeFileSync(join(evidence, name), bytes);
  const entries = Object.fromEntries(['task.json', 'task-executions.json', 'dispatch-actions.json', 'workspace-write-leases.json', 'worker-leases.json', 'model-requests.json', 'agent-runs.json', 'operations.json', 'process-sessions.json', 'process-observations.json'].map(file => [file, JSON.parse(zipEntry(bytes, file))]));
  for (const observation of entries['process-observations.json']) remember(observation);
  assert.ok(!JSON.stringify(entries).includes('claimDigest')); return entries;
}
async function reconcileProcesses(name) {
  const before = await api('/process-recovery'); assert.equal(before.hasMore, false);
  for (const session of before.sessions) {
    remember(session); for (const value of session.recordedProcesses || []) remember(value);
    const stopped = await api(`/process-recovery/${session.id}/stop`, {}); save(`${name}-${session.id}-original-stop.json`, stopped);
    let last = stopped;
    try { await until('原 PID 出生身份停止核验', async () => { last = await api(`/process-recovery/${session.id}`); return last.observedProcessesStopped; }); }
    finally { save(`${name}-${session.id}-last-original-process-detail.json`, last); }
    await api(`/process-recovery/${session.id}/reconcile`, { processesStopped: true, coverageChecked: true, note: 'Dedicated actual Jar workspace-write fixture: original JVM/Worker and recorded descendants independently checked; fixture launches and unrecorded process coverage checked.' });
  }
  const after = await api('/process-recovery'); assert.equal(after.dispatchAllowed, true); save(`${name}-process-reconciliation.json`, { before, after });
}

const model = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const bytes of request) chunks.push(bytes); const input = Buffer.concat(chunks), body = JSON.parse(input.toString('utf8')), { prompt, names, results } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(body.model, 'workspace-write-fixture');
    const marker = [...tasks.keys()].find(value => prompt.includes(value)); assert.ok(marker); const taskId = tasks.get(marker), stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1], current = await facts(taskId);
    const original = current.requests.find(value => value.inputDigest === digest(input) && value.state === 'DISPATCHING'); assert.ok(original, 'Original model request must already be committed');
    const write = current.writes.find(value => value.id === original.workspaceWriteLeaseId); assert.ok(write); assert.equal(write.state, 'HELD'); assert.equal(write.protectionReleased, false);
    assert.equal(original.taskExecutionId, write.taskExecutionId); assert.equal(original.dispatchActionId, write.dispatchActionId); assert.equal(original.executorPid, write.executorPid); assert.equal(write.executorPid, currentCore.worker.pid);
    const number = calls.length + 1, prior = calls.filter(value => value.marker === marker).length; calls.push({ number, marker, taskId, stage, requestId: original.id, taskExecutionId: write.taskExecutionId, dispatchActionId: write.dispatchActionId, workspaceWriteLeaseId: write.id, workerPid: write.executorPid, at: new Date().toISOString() });
    if (prior === 0 && gates.has(marker)) await gates.get(marker).promise;
    for (const result of results) assert.equal(JSON.parse(result.content).error, undefined);
    let message = { role: 'assistant', content: stage === 'REVIEW' ? '{"approved":true,"issues":[]}' : 'Original workspace write stage completed.' };
    if (stage === 'IMPLEMENT' && !results.length) { assert.ok(names.includes('read_file')); message = { role: 'assistant', content: null, tool_calls: [{ id: `original-read-${marker}`, type: 'function', function: { name: 'read_file', arguments: JSON.stringify({ path: 'app.txt' }) } }] }; }
    else if (stage === 'IMPLEMENT' && results.length === 1) {
      const read = JSON.parse(results[0].content); assert.match(read.readVersion, /^file-read-/); assert.ok(names.includes('write_file'));
      message = { role: 'assistant', content: null, tool_calls: [{ id: `original-write-${marker}`, type: 'function', function: { name: 'write_file', arguments: JSON.stringify({ path: 'app.txt', content: `owned ${marker}\n`, expectedVersion: read.readVersion }) } }] };
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: `original-write-response-${number}`, model: body.model, choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { modelErrors.push(error.message); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: error.message } })); }
});
const web = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    if (path.startsWith('/service/api/')) {
      const chunks = []; for await (const bytes of request) chunks.push(bytes); const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method,
        headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' }, ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(20000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (path === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"apiBaseUrl":"/service"}'); return; }
    if (!/^\/(?:index\.html|assets\/[a-zA-Z0-9_.-]+)?$/.test(path)) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(path === '/' ? 'index.html' : path)] || 'application/octet-stream' }); response.end(readFileSync(join(root, 'web/dist', path === '/' ? 'index.html' : path)));
  } catch { response.writeHead(502); response.end(); }
});

try {
  compileFixture(); mkdirSync(source); writeFileSync(join(source, '.gitattributes'), '* text eol=lf\n'); writeFileSync(join(source, 'app.txt'), 'original source\n');
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict'; import {readFileSync} from 'node:fs'; assert.equal(process.env.WORKBENCH_TOKEN,undefined); assert.ok(readFileSync('app.txt','utf8').endsWith('\\n')); console.log('ORIGINAL_NODE_VERIFICATION '+JSON.stringify({pid:process.pid,parentPid:process.ppid}));\n");
  git('init', '--initial-branch=main'); git('config', 'user.name', 'Write fixture'); git('config', 'user.email', 'fixture@example.invalid'); git('config', 'core.autocrlf', 'false'); git('add', '.'); git('commit', '-m', 'original workspace write fixture');
  await new Promise(done => model.listen(0, '127.0.0.1', done)); await startCore();
  const project = await api('/projects', { path: source }), route = await api('/routes', { name: 'Actual workspace write model', engine: 'API', model: 'workspace-write-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1` });
  pass('Ordinary final Jar, exact JNA dependencies and independent Worker were verified');
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `${strategy} real in-flight writer`; const marker = `WRITE_NORMAL_${strategy}`, task = await createTask(project, route, marker, strategy, true), sourceBefore = sourceFacts(); normalTasks.push(task);
    await api(`/tasks/${task.id}/approve`, {}); await until('原模型 HTTP 在途窗口', () => calls.some(call => call.marker === marker));
    const held = await facts(task.id); save(`${strategy}-held-root.json`, held); const original = held.writes[0];
    assert.equal(original.state, 'HELD'); assert.equal(original.protectionReleased, false); assert.equal(original.executorPid, currentCore.worker.pid); assert.equal(original.ownerCorePid, currentCore.child.pid);
    assert.equal(original.grant.fileKey.startsWith(windows ? 'WINDOWS:' : 'NIO:'), true); assert.equal(held.task.workspaceWriteLeaseId, original.id); assert.equal(held.requests[0].workspaceWriteLeaseId, original.id);
    assert.equal(writeProbe('lock', original, `${strategy}-in-flight-lock`).acquired, false); assert.equal(writeProbe('contend', original, `${strategy}-in-flight-contender`).acquired, false);
    pass(`${strategy}: original HTTP request held the actual lock and rejected a second JVM writer`);
    gates.get(marker).release(); gates.delete(marker); const completed = await succeeded(task.id); save(`${strategy}-completed-root.json`, completed); const released = completed.writes[0];
    assert.equal(released.id, original.id); assert.equal(released.state, 'RELEASED'); assert.equal(released.protectionReleased, true); assert.equal(released.release.identity.id, original.id);
    assert.equal(writeProbe('lock', released, `${strategy}-released-lock`).acquired, true); assert.equal(completed.requests.length, 5); assert.equal(completed.task.budget.usedCalls, 5);
    assert.equal(readFileSync(join(completed.task.workspace, 'app.txt'), 'utf8'), `owned ${marker}\n`); assert.ok(completed.task.evidence.some(value => value.output?.includes('ORIGINAL_NODE_VERIFICATION')));
    for (const kind of ['operations', 'leases', 'requests']) assert.ok(completed[kind].every(value => value.workspaceWriteLeaseId === original.id));
    assert.equal(completed.executions[0].workspaceWriteProtectionReleased, true); sourceUnchanged(sourceBefore);
    pass(`${strategy}: original file-tool write, Node verification and root release receipts completed before the lock became available`);
    const count = (await options(project)).worktrees.length; await api(`/tasks/${task.id}/revalidate`, {}); await api(`/tasks/${task.id}/resume`, {}); const second = await succeeded(task.id); save(`${strategy}-revalidated-root.json`, second);
    assert.equal(second.writes.length, 2); assert.notEqual(second.writes[0].id, original.id); assert.equal(second.writes[0].attempt, 2); assert.equal(second.writes[0].state, 'RELEASED');
    assert.equal(second.task.budget.usedCalls, 6); assert.equal(second.operations.filter(operation => operation.kind === 'WORKSPACE_WRITE').length, 1); assert.equal((await options(project)).worktrees.length, count); sourceUnchanged(sourceBefore);
    const exported = await archive(task.id, `${strategy}-normal-and-revalidated.zip`); assert.equal(exported['workspace-write-leases.json'].length, 2); assert.equal(exported['task-executions.json'].length, 2);
    assert.equal(exported['workspace-write-leases.json'][1].id, original.id); assert.ok(exported['model-requests.json'].every(value => exported['workspace-write-leases.json'].some(write => write.id === value.workspaceWriteLeaseId)));
    pass(`${strategy}: revalidation acquired a new writer for the same READY workspace and exported both original roots without another workspace`);
  }
  phase = 'write history API and production browser';
  const selected = normalTasks[1], history = await api(`/tasks/${selected.id}/workspace-write-leases?limit=1`); assert.equal(history.leases.length, 1); assert.equal(history.hasMore, true);
  const older = await api(`/tasks/${selected.id}/workspace-write-leases?limit=1&before=${history.cursor}`); assert.equal(older.leases.length, 1); assert.notEqual(older.leases[0].id, history.leases[0].id); assert.equal(older.hasMore, false);
  assert.equal((await api(`/tasks/${selected.id}/workspace-write-leases?state=UNKNOWN`)).leases.length, 0); await api(`/tasks/${selected.id}/workspace-write-leases?state=FORGED`, undefined, 400);
  await api(`/tasks/${selected.id}/workspace-write-leases?before=-1`, undefined, 400); assert.equal((await fetch(`${baseUrl}/api/tasks/${selected.id}/workspace-write-leases`)).status, 401);
  save('write-history-api-pages.json', { history, older }); pass('Actual writer history required authentication, retained stable cursors and rejected invalid filters');
  await new Promise(done => web.listen(0, '127.0.0.1', done)); browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(error.message)); await page.goto(`http://127.0.0.1:${web.address().port}`);
  await page.getByRole('button', { name: '连接设置', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(token); await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: new RegExp(`^写入保护 ${selected.goal}`) }).click(); await page.getByRole('button', { name: 'Worker 执行', exact: true }).click();
  const panel = page.getByRole('region', { name: '现场写入保护历史' }); await panel.getByText(history.leases[0].id, { exact: true }).waitFor();
  for (const width of [1440, 430]) {
    await page.setViewportSize({ width, height: 1000 }); await page.evaluate(async () => { await document.fonts.ready; await new Promise(done => requestAnimationFrame(() => requestAnimationFrame(done))); });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true); await panel.scrollIntoViewIfNeeded(); await page.screenshot({ path: join(evidence, `workspace-write-real-${width}.png`), fullPage: true, animations: 'disabled' });
  }
  await browser.close(); browser = undefined; pass('Production desktop and narrow browser showed the original root writers and release receipts');
  for (const strategy of ['CLONE', 'WORKTREE']) for (const window of ['grant', 'release']) {
    phase = `${strategy} ${window} SQLite receipt fault`; const marker = `WRITE_FAULT_${strategy}_${window.toUpperCase()}`, task = await createTask(project, route, marker, strategy); faultTasks.push(task); const sourceBefore = sourceFacts();
    databaseFault(`fail-${window}`, task.id); await api(`/tasks/${task.id}/approve`, {}); const parent = join(data, 'workspaces', task.id), manifestPath = join(parent, 'writer.json'), readyPath = join(parent, 'selection.json');
    await until('原实际锁和数据库故障窗口', async () => existsSync(manifestPath) && existsSync(readyPath) && JSON.parse(readFileSync(readyPath)).state === 'READY' && (await api('/health')).status === 'DEGRADED');
    const native = JSON.parse(readFileSync(manifestPath)), readyBytes = readFileSync(readyPath), ready = JSON.parse(readyBytes), original = await facts(task.id), write = original.writes[0];
    const occurredCalls = window === 'grant' ? 0 : 5; assert.equal(calls.filter(call => call.marker === marker).length, occurredCalls); assert.equal(original.task.budget.usedCalls, occurredCalls); assert.equal(original.executions[0].state, 'RUNNING');
    assert.equal(write.state, window === 'grant' ? 'PREPARED' : 'HELD'); assert.equal(write.protectionReleased, false); assert.equal(native.state, window === 'grant' ? 'HELD' : 'RELEASED'); assert.equal(native.identity.id, write.id);
    if (window === 'grant') { assert.equal(original.task.workspace, ''); assert.equal(original.operations[0].state, 'DISPATCHED'); assert.equal(original.leases[0].state, 'ACTIVE'); assert.equal(write.grant, undefined); }
    const beforeProbe = processProbe(currentCore.worker.pid, currentCore.worker.processStartedAt), lockObservation = writeProbe('lock', write, `${strategy}-${window}-before-restart-lock`), afterProbe = processProbe(currentCore.worker.pid, currentCore.worker.processStartedAt);
    if (window === 'release') assert.equal(lockObservation.acquired, true);
    else assert.equal(writeProbe('contend', write, `${strategy}-${window}-unconfirmed-grant-contender`).acquired, false);
    sourceUnchanged(sourceBefore);
    save(`${strategy}-${window}-before-restart.json`, { native, ready, original, lockObservation, originalWorkerBeforeProbe: beforeProbe, originalWorkerAfterProbe: afterProbe }); pass(`${strategy} ${window}: actual native and SQLite receipts retained distinct original states and committed budget`);
    const oldCore = currentCore; await stopCore(oldCore); save(`${strategy}-${window}-original-worker-stop.json`, processProbe(oldCore.worker.pid, oldCore.worker.processStartedAt, 'stop')); databaseFault('clear', task.id);
    assert.equal(writeProbe('lock', write, `${strategy}-${window}-after-original-stop-lock`).acquired, true);
    if (window === 'grant') { assert.equal(writeProbe('contend', write, `${strategy}-${window}-crash-contender`).acquired, false); assert.deepEqual(JSON.parse(readFileSync(manifestPath)), native); }
    if (window === 'grant') { writeFileSync(join(source, `source-after-${strategy.toLowerCase()}-grant.txt`), 'source moved after original READY\n'); git('add', '.'); git('commit', '-m', `source moved after ${strategy} original READY`); }
    const sourceAfter = sourceFacts(); await startCore(); const reopened = await facts(task.id); save(`${strategy}-${window}-reopened.json`, reopened);
    assert.equal(reopened.task.state, 'UNKNOWN'); assert.equal(reopened.writes[0].state, 'UNKNOWN'); assert.equal(reopened.writes[0].id, write.id); assert.equal(reopened.writes[0].protectionReleased, false);
    assert.equal(reopened.executions[0].id, original.executions[0].id); assert.equal(reopened.executions[0].state, 'UNKNOWN'); assert.equal(reopened.task.budget.usedCalls, occurredCalls); assert.deepEqual(readFileSync(readyPath), readyBytes);
    assert.equal(calls.filter(call => call.marker === marker).length, occurredCalls); await api(`/tasks/${task.id}/resume`, {}, 409);
    const oldZip = await archive(task.id, `${strategy}-${window}-unknown-original.zip`); assert.equal(oldZip['workspace-write-leases.json'][0].state, 'UNKNOWN'); assert.equal(oldZip['workspace-write-leases.json'][0].protectionReleased, false);
    pass(`${strategy} ${window}: restart kept the same UNKNOWN writer and root without automatic replay`);
    await reconcileProcesses(`${strategy}-${window}`); await api(`/tasks/${task.id}/reconcile`, { processesStopped: true, sideEffectsChecked: true, note: `Dedicated actual Jar ${strategy} ${window} receipt fixture: original PID/birth and coverage independently checked; actual lock, READY owner, source and recorded model effects inspected.` });
    const checked = await facts(task.id); assert.equal(checked.writes[0].state, 'UNKNOWN'); assert.equal(checked.writes[0].protectionReleased, true); assert.ok(checked.writes[0].reconciliation); const worktreeCount = (await options(project)).worktrees.length;
    await api(`/tasks/${task.id}/resume`, {}); const recovered = await succeeded(task.id); save(`${strategy}-${window}-recovered.json`, recovered);
    assert.equal(recovered.writes.length, 2); assert.notEqual(recovered.writes[0].id, write.id); assert.equal(recovered.writes[0].state, 'RELEASED'); assert.equal(recovered.writes[0].attempt, 2);
    assert.equal(recovered.writes[1].state, 'UNKNOWN'); assert.equal(recovered.writes[1].protectionReleased, true); assert.ok(recovered.writes[1].reconciliation); assert.equal(recovered.executions[1].state, 'UNKNOWN'); assert.ok(recovered.executions[1].reconciliation);
    assert.equal(recovered.task.budget.usedCalls, 5); assert.equal(calls.filter(call => call.marker === marker).length, 5); assert.deepEqual(readFileSync(readyPath), readyBytes); assert.deepEqual(recovered.task.workspaceOwnership, ready.facts.workspaceOwnership);
    assert.equal((await options(project)).worktrees.length, worktreeCount); sourceUnchanged(sourceAfter); if (window === 'grant') assert.equal(recovered.task.recoveredPreparation, true);
    else assert.equal(recovered.operations.filter(operation => operation.kind === 'WORKSPACE_WRITE').length, 1);
    const exported = await archive(task.id, `${strategy}-${window}-unknown-and-recovered.zip`); assert.equal(exported['workspace-write-leases.json'].length, 2); assert.equal(exported['task-executions.json'].length, 2);
    assert.equal(exported['workspace-write-leases.json'][1].state, 'UNKNOWN'); assert.equal(exported['workspace-write-leases.json'][1].id, write.id); assert.equal(exported['task-executions.json'][1].budgetAtEnd.usedCalls, occurredCalls);
    assert.ok(exported['model-requests.json'].every(value => exported['workspace-write-leases.json'].some(lease => lease.id === value.workspaceWriteLeaseId)));
    pass(`${strategy} ${window}: explicit reconciliation kept UNKNOWN history, reused the same READY owner and exported two attempts with unchanged original budget`);
  }
  phase = 'final artifact and process verification'; assert.deepEqual(modelErrors, []); assert.deepEqual(pageErrors, []); assert.equal(digest(readFileSync(jar)), jarSha256); assert.equal(digest(readFileSync(fileURLToPath(import.meta.url))), scriptSha256);
  for (const entry of productionWebHashes) assert.equal(digest(readFileSync(join(root, entry.file))), entry.sha256); await stopCore(currentCore);
  const cleanup = stopOriginalIdentities('independent-original-process-stop'); save('independent-original-process-stop.json', cleanup); assert.ok(cleanup.every(value => value.originalStopped));
  pass('All original core, Worker and probe PID/birth identities stopped and the final Jar/Web bytes remained unchanged');
  report = { recordedAt: new Date().toISOString(), version, platform: process.platform, status: 'passed', fixture: 'ACTUAL_JAR_WORKSPACE_WRITES_WITH_SQLITE_RECEIPT_FAULTS', jarSha256, scriptSha256, productionWebHashes, checks,
    schedulerParallelism: 2, schedulerMaxPoolSize: 2, browserModule: '@playwright/test from web/package.json', browserVersion, normalTaskIds: normalTasks.map(task => task.id), faultTaskIds: faultTasks.map(task => task.id),
    coreIdentities: cores.map(core => core.identity), workerIdentities: cores.map(core => ({ pid: core.worker.pid, processStartedAt: core.worker.processStartedAt })), calls,
    processCoverage: 'OBSERVED_ONLY', originalIdentitiesVerifiedStopped: cleanup.length, originalIdentities: [...identities.values()], automaticReplay: false, modelErrors, pageErrors, evidenceDirectory: evidence };
  save('report.json', report); mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/workspace-writes-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, checks: checks.length, tasks: normalTasks.length + faultTasks.length, coreStarts: cores.length, requests: calls.length, jarSha256, evidenceDirectory: evidence }));
} catch (error) {
  const failure = { recordedAt: new Date().toISOString(), version, status: 'failed', phase, error: error.message.replaceAll(token, '[REDACTED]'), stack: error.stack?.replaceAll(token, '[REDACTED]'), checks, jarSha256, scriptSha256, productionWebHashes, calls, modelErrors, pageErrors, evidenceDirectory: evidence, currentTasks: [] };
  if (page && !page.isClosed()) { try { await page.screenshot({ path: join(evidence, 'browser-before-cleanup.png'), fullPage: true, mask: [page.getByLabel('访问令牌', { exact: true })] }); } catch {} }
  for (const task of [...normalTasks, ...faultTasks]) { try { failure.currentTasks.push(await facts(task.id)); } catch {} }
  save('failure-before-cleanup.json', failure); console.error(`${phase}: ${failure.error}; 清理前事实保留在 ${evidence}`); process.exitCode = 1;
} finally {
  for (const gate of gates.values()) gate.release(); if (browser) await browser.close();
  if (currentCore && !ended(currentCore)) {
    try { await stopCore(currentCore); }
    catch (error) { save('core-stop-failure.json', { error: error.message.replaceAll(token, '[REDACTED]'), identity: currentCore.identity }); if (currentCore.identity) processProbe(currentCore.identity.pid, currentCore.identity.processStartedAt, 'stop'); await until('原夹具核心停止', () => ended(currentCore), 10000).catch(() => {}); }
  }
  model.closeAllConnections(); await new Promise(done => model.close(done)); web.closeAllConnections(); await new Promise(done => web.close(done));
  const cleanup = [];
  try { cleanup.push(...stopOriginalIdentities(report ? 'fixture-stop-after-report' : 'fixture-stop-after-failure')); }
  catch (error) { for (const value of identities.values()) cleanup.push({ ...value, originalStopped: false, error: error.message }); }
  save(report ? 'fixture-stop-after-report.json' : 'fixture-stop-after-failure.json', cleanup); if (cleanup.some(value => !value.originalStopped)) process.exitCode = 1;
  for (const core of cores) writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}
