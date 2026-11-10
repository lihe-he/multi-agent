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
const javaRoot = javaHome(root), java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java');
const jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
if (!existsSync(jar) || !existsSync(join(root, 'web/dist/index.html'))) throw new Error('请先构建当前核心与生产 Web。');
mkdirSync(join(root, '.runtime'), { recursive: true });
const dir = mkdtempSync(join(root, '.runtime/workspace-selection-')), evidence = join(dir, 'reports'), data = join(dir, 'data'); mkdirSync(evidence);
const source = join(dir, '中文 空格 source'), token = randomBytes(32).toString('hex'), cores = [], identities = new Map();
const jarSha256 = hash(readFileSync(jar)), checks = [], modelCalls = [], modelErrors = [], pageErrors = [], tasks = new Map();
const scriptSource = readFileSync(fileURLToPath(import.meta.url)), scriptSha256 = hash(scriptSource);
const productionWebHashes = readdirSync(join(root, 'web/dist'), { recursive: true }).filter(file => /\.(html|js|css)$/.test(file)).map(file => ({ file: `web/dist/${file.replaceAll('\\', '/')}`, sha256: hash(readFileSync(join(root, 'web/dist', file))) }));
let phase = 'fixture compilation', baseUrl, currentCore, browser, page, readyTask, readyManifest, report;
const wait = ms => new Promise(done => setTimeout(done, ms));
const save = (name, value) => writeFileSync(join(evidence, name), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
function hash(bytes) { return createHash('sha256').update(bytes).digest('hex'); }
function git(directory, ...args) {
  return execFileSync('git', ['--no-optional-locks', '-c', 'core.hooksPath=/dev/null', '-c', 'core.autocrlf=false', '-C', directory, ...args], { encoding: 'utf8', windowsHide: true, timeout: 20000 });
}
async function until(name, action, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await wait(100); }
  throw new Error(`${name} 未在有界期限内达到预期`);
}
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000) });
  const text = await response.text(); let result; try { result = JSON.parse(text); } catch { result = text; }
  assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${text.slice(0, 500)}`); return result;
}
async function facts(taskId) {
  return { task: await api(`/tasks/${taskId}`), executions: (await api(`/tasks/${taskId}/task-executions?limit=100`)).executions,
    actions: (await api(`/tasks/${taskId}/dispatch-actions?limit=100`)).actions, operations: await api(`/tasks/${taskId}/operations`),
    leases: (await api(`/tasks/${taskId}/worker-leases?limit=100`)).leases, requests: (await api(`/tasks/${taskId}/model-requests?limit=100`)).requests };
}
function remember(value) {
  if (value?.pid && value.processStartedAt) identities.set(`${value.pid}@${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt });
}
function inspectProcess(pid, birth, mode = 'inspect') {
  return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', join(dir, 'fixture-classes'), 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 }));
}
function sourceFacts() {
  return { head: git(source, 'rev-parse', 'HEAD').trim(), branch: git(source, 'branch', '--show-current').trim(),
    indexSha256: hash(readFileSync(join(source, '.git/index'))), configSha256: hash(readFileSync(join(source, '.git/config'))),
    refs: git(source, 'for-each-ref', '--format=%(refname) %(objectname)'), status: git(source, 'status', '--porcelain=v1', '-uall'),
    appSha256: hash(readFileSync(join(source, 'app.txt'))) };
}
function sourceUnchanged(expected) { assert.deepEqual(sourceFacts(), expected, 'Original source HEAD, branch, index, config, refs and files must remain unchanged'); }
const exited = core => core.child.exitCode !== null || core.child.signalCode !== null;
async function startCore(controlled = false) {
  const number = cores.length + 1, marker = join(dir, `release-${number}.marker`);
  const args = ['-Dfile.encoding=UTF-8', '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', `-Djdk.net.unixdomain.tmpdir=${dir}`];
  if (controlled) args.push(`-Dloader.path=${join(dir, 'fixture-classes')}`, '-Dloader.main=dev.agentworkbench.ControlledDispatchLauncher', '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', marker);
  else args.push('-jar', jar);
  const child = spawn(java, args, { cwd: dir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  const core = { child, number, marker, controlled, logs: '', error: null }; cores.push(core); currentCore = core;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { core.logs += bytes.toString(); }); child.once('error', error => { core.error = error; });
  await until('实际核心启动', () => {
    if (core.error) throw core.error; if (exited(core)) throw new Error(`第 ${number} 次核心启动提前退出`);
    const port = core.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; }
  });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.schemaVersion, 7); assert.equal(health.workspaceSelectionVersion, 1); assert.equal(health.corePid, child.pid);
  core.worker = (await api('/workers')).find(worker => worker.state === 'RUNNING'); assert.ok(core.worker); remember(core.worker);
  assert.equal(core.worker.deployment, 'LOCAL_PROCESS'); assert.notEqual(core.worker.pid, child.pid);
  if (controlled) { assert.ok(core.logs.includes('CONTROLLED_DISPATCH_FIXTURE engine=jar:')); assert.ok(core.logs.includes('BOOT-INF/classes/')); }
  return core;
}
async function stopCore(core) {
  if (exited(core)) return; try { await api('/shutdown', {}); } catch {}
  await until('原核心实际退出', () => exited(core), 30000); writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}
function databaseFixture(mode, taskId) {
  return execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', fixtureClasspath(), 'dev.agentworkbench.WorkspaceDatabaseFixture', data, mode, taskId], { encoding: 'utf8', windowsHide: true, timeout: 15000 });
}
function fixtureClasspath() {
  const boot = join(dir, 'boot'); return [join(dir, 'fixture-classes'), join(boot, 'BOOT-INF/classes'), ...readdirSync(join(boot, 'BOOT-INF/lib')).filter(file => file.endsWith('.jar')).map(file => join(boot, 'BOOT-INF/lib', file))].join(delimiter);
}
function compileFixture() {
  const boot = join(dir, 'boot'), classes = join(dir, 'fixture-classes'); mkdirSync(boot); mkdirSync(classes);
  execFileSync(join(javaRoot, 'bin', windows ? 'jar.exe' : 'jar'), ['xf', jar], { cwd: boot, encoding: 'utf8', windowsHide: true, timeout: 30000 });
  const sources = ['ControlledDispatchLauncher.java', 'WorkspaceDatabaseFixture.java', 'ProcessRecoveryProbe.java'].map(file => join(root, 'tools', file));
  execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-cp', fixtureClasspath(), '-d', classes, ...sources], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  writeFileSync(join(evidence, 'workspace-selection-smoke-source.mjs'), scriptSource);
  const fixtureClasses = readdirSync(classes, { recursive: true }).filter(file => file.endsWith('.class'))
    .map(file => ({ file: file.replaceAll('\\', '/'), sha256: hash(readFileSync(join(classes, file))) }));
  save('fixture.json', { version, jar, jarSha256, scriptSha256, productionWebHashes, fixture: 'ACTUAL_JAR_WITH_EXTERNAL_QUEUE_GATE_AND_RECEIPT_FAULT', productionEntry: 'JarLauncher', controlledEntry: 'PropertiesLauncher',
    engineClassSha256: hash(readFileSync(join(boot, 'BOOT-INF/classes/dev/agentworkbench/WorkbenchEngine.class'))),
    workerClassSha256: hash(readFileSync(join(boot, 'BOOT-INF/classes/dev/agentworkbench/WorkerProcessMain.class'))), fixtureClasses,
    processProbe: 'ONCE_COMPILED_ORIGINAL_PID_AND_BIRTH_PROBE', sources: sources.map(file => ({ file, sha256: hash(readFileSync(file)) })) });
}
async function options(project) { return api(`/repository-bindings/${project.repositoryBindingId}/workspace-options`); }
function choice(available, strategy, ref = 'HEAD') {
  const baseline = ref === 'HEAD' ? available.source.head : available.refs.find(item => item.ref === ref)?.head; assert.ok(baseline);
  return { strategy, ref, expectedHead: baseline, sourceVersion: available.source.version, sourceIndex: available.source.index };
}
async function createTask(project, route, title, marker, available, strategy, ref = 'HEAD') {
  const task = await api('/tasks', { projectId: project.id, routeId: route.id, title, goal: marker, mode: 'LEAD', workspaceSelection: choice(available, strategy, ref), includeChanges: false,
    contract: { scope: ['**'], acceptance: ['Fixed commit and original ownership'], commands: ['node verify.mjs'] }, budget: { maxCalls: 12, reserveCalls: 3 } });
  tasks.set(marker, task.id); return task;
}
async function succeeded(taskId) {
  return until('实际根执行结束', async () => {
    const current = await facts(taskId); assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT', 'CANCELLED'].includes(current.task.state), `${current.task.state}: ${current.task.error}`);
    return current.executions[0]?.state === 'SUCCEEDED' && current.task.state === 'AWAITING_ACCEPTANCE' && current;
  });
}
async function archive(taskId, filename) {
  const response = await fetch(`${baseUrl}/api/tasks/${taskId}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000) }); assert.equal(response.status, 200);
  const bytes = Buffer.from(await response.arrayBuffer()); writeFileSync(join(evidence, filename), bytes);
  return { task: JSON.parse(zipEntry(bytes, 'task.json')), executions: JSON.parse(zipEntry(bytes, 'task-executions.json')),
    actions: JSON.parse(zipEntry(bytes, 'dispatch-actions.json')), leases: JSON.parse(zipEntry(bytes, 'worker-leases.json')) };
}
async function reconcileProcesses() {
  const before = await api('/process-recovery'); assert.equal(before.hasMore, false);
  for (const session of before.sessions) {
    remember(session); for (const process of session.recordedProcesses || []) remember(process);
    await api(`/process-recovery/${session.id}/stop`, {});
    await until('原夹具进程核验', async () => (await api(`/process-recovery/${session.id}`)).observedProcessesStopped);
    await api(`/process-recovery/${session.id}/reconcile`, { processesStopped: true, coverageChecked: true, note: 'Dedicated workspace Jar fixture: launched core/Worker and recorded descendants checked against original PID/birth; fixture launches independently reviewed.' });
  }
  const after = await api('/process-recovery'); assert.equal(after.dispatchAllowed, true); save('process-reconciliation.json', { before, after });
}
const model = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const bytes of request) chunks.push(bytes); const input = Buffer.concat(chunks);
    const body = JSON.parse(input.toString('utf8')), { prompt, names, results } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(body.model, 'workspace-jar-fixture');
    const marker = [...tasks.keys()].find(value => prompt.includes(value)); assert.ok(marker); const taskId = tasks.get(marker);
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1], current = await facts(taskId), requestDigest = hash(input);
    const original = current.requests.find(item => item.inputDigest === requestDigest && item.state === 'DISPATCHING'); assert.ok(original, 'Original request must be committed before HTTP');
    assert.equal(original.taskExecutionId, current.task.taskExecutionId); assert.equal(original.dispatchActionId, current.task.dispatchActionId); assert.ok(original.workerLeaseId);
    const lease = current.leases.find(item => item.id === original.workerLeaseId); assert.ok(lease); assert.equal(lease.executorPid, currentCore.worker.pid);
    modelCalls.push({ number: modelCalls.length + 1, marker, taskId, stage, requestId: original.id, workerLeaseId: lease.id, workerPid: lease.executorPid, at: new Date().toISOString() });
    for (const result of results) assert.equal(JSON.parse(result.content).error, undefined);
    let message = { role: 'assistant', content: stage === 'REVIEW' ? '{"approved":true,"issues":[]}' : 'Original workspace fixture stage completed.' };
    if (stage === 'IMPLEMENT' && !results.length) {
      assert.ok(names.includes('write_file')); message = { role: 'assistant', content: null, tool_calls: [{ id: `workspace-write-${modelCalls.length}`, type: 'function',
        function: { name: 'write_file', arguments: JSON.stringify({ path: 'app.txt', content: `owned ${marker}\n` }) } }] };
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: `workspace-response-${modelCalls.length}`, model: body.model,
      choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { modelErrors.push(error.message); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: error.message } })); }
});
const web = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    if (path.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' },
        ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(15000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (path === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"apiBaseUrl":"/service"}'); return; }
    if (!/^\/(?:index\.html|assets\/[a-zA-Z0-9_.-]+)?$/.test(path)) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(path === '/' ? 'index.html' : path)] || 'application/octet-stream' });
    response.end(readFileSync(join(root, 'web/dist', path === '/' ? 'index.html' : path)));
  } catch { response.writeHead(502); response.end(); }
});

try {
  compileFixture(); mkdirSync(source); writeFileSync(join(source, '.gitattributes'), '* text eol=lf\n'); writeFileSync(join(source, 'app.txt'), 'main content\n'); writeFileSync(join(source, 'AGENTS.md'), 'main rules\n');
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict'; assert.equal(process.env.WORKBENCH_TOKEN,undefined); console.log('workspace fixture verified');\n");
  git(source, 'init', '--initial-branch=main'); git(source, 'config', 'user.name', 'Workspace fixture'); git(source, 'config', 'user.email', 'fixture@example.invalid'); git(source, 'config', 'core.autocrlf', 'false'); git(source, 'add', '.'); git(source, 'commit', '-m', 'main fixture');
  git(source, 'checkout', '-b', 'feature'); writeFileSync(join(source, 'app.txt'), 'feature content\n'); writeFileSync(join(source, 'AGENTS.md'), 'feature rules\n'); git(source, 'add', '.'); git(source, 'commit', '-m', 'feature fixture'); const featureHead = git(source, 'rev-parse', 'HEAD').trim();
  git(source, 'checkout', '--detach'); writeFileSync(join(source, 'remote-only.txt'), 'remote-only fixture\n'); git(source, 'add', '.'); git(source, 'commit', '-m', 'remote-only fixture'); const remoteHead = git(source, 'rev-parse', 'HEAD').trim();
  git(source, 'update-ref', 'refs/remotes/origin/remote-only', remoteHead); git(source, 'symbolic-ref', 'refs/remotes/origin/HEAD', 'refs/remotes/origin/remote-only'); git(source, 'checkout', 'main');
  await new Promise(done => model.listen(0, '127.0.0.1', done)); const first = await startCore(true);
  pass('Current Jar schema and independent Worker were verified with the external queue gate');
  phase = 'real branch options and stale creation'; const project = await api('/projects', { path: source }), route = await api('/routes', { name: 'Actual workspace Jar model', engine: 'API', model: 'workspace-jar-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1` });
  const sourceBeforeOptions = sourceFacts(), originalOptions = await options(project); sourceUnchanged(sourceBeforeOptions);
  assert.deepEqual(originalOptions.refs.map(item => item.ref), ['refs/heads/feature', 'refs/heads/main', 'refs/remotes/origin/remote-only']); assert.equal(originalOptions.refs.find(item => item.kind === 'REMOTE').head, remoteHead);
  const unauthorized = await fetch(`${baseUrl}/api/repository-bindings/${project.repositoryBindingId}/workspace-options`); assert.equal(unauthorized.status, 401);
  const staleCreation = await api('/tasks', { projectId: project.id, title: 'stale creation', goal: 'WORKSPACE_STALE_CREATION', routeId: route.id, workspaceSelection: { ...choice(originalOptions, 'WORKTREE'), expectedHead: '0'.repeat(40) } }, 409);
  assert.match(staleCreation.error, /刷新启动选择/);
  assert.equal((await api('/state')).tasks.length, 0); assert.equal(modelCalls.length, 0); save('actual-workspace-options.json', originalOptions);
  pass('Actual local and remote refs excluded aliases and rejected stale creation before any task or model call');
  const worktreeTask = await createTask(project, route, '固定 worktree 选择', 'WORKSPACE_WORKTREE_ROOT', originalOptions, 'WORKTREE');
  const cloneTask = await createTask(project, route, '固定 clone 选择', 'WORKSPACE_CLONE_ROOT', originalOptions, 'CLONE');
  for (const task of [worktreeTask, cloneTask]) await api(`/tasks/${task.id}/approve`, {});
  const originalWorktree = await facts(worktreeTask.id), originalClone = await facts(cloneTask.id); save('original-unclaimed-worktree.json', originalWorktree); save('original-unclaimed-clone.json', originalClone);
  phase = 'source moves while queued'; writeFileSync(join(source, 'source-moved.txt'), 'source commit after confirmation\n'); git(source, 'add', '.'); git(source, 'commit', '-m', 'source moved after confirmation'); writeFileSync(first.marker, 'release dedicated queue');
  for (const task of [worktreeTask, cloneTask]) {
    const rejected = await until('源过期零准备', async () => { const value = await facts(task.id); return value.executions[0]?.state === 'WAITING_INPUT' && value; });
    for (const field of ['operations', 'leases', 'requests']) assert.equal(rejected[field].length, 0); assert.equal(rejected.task.budget.usedCalls, 0); assert.equal(rejected.task.workspace, '');
    assert.equal(rejected.actions[0].dispatchCount, 1); assert.equal(existsSync(join(data, 'workspaces', task.id, 'selection.json')), false); save(`stale-${task.workspaceSelection.strategy}.json`, rejected);
  }
  assert.equal(modelCalls.length, 0); pass('Both strategies settled original stale attempts without preparation, Worker operations or budget use');
  const available = await options(project), stableSource = sourceFacts(); save('source-before-task-execution.json', stableSource);
  await api(`/tasks/${worktreeTask.id}/workspace-selection`, { expectedVersion: 1, workspaceSelection: choice(available, 'WORKTREE', 'refs/heads/feature'), includeChanges: false });
  await api(`/tasks/${cloneTask.id}/workspace-selection`, { expectedVersion: 1, workspaceSelection: choice(available, 'CLONE', 'refs/remotes/origin/remote-only'), includeChanges: false });
  phase = 'actual selected worktree execution'; await api(`/tasks/${worktreeTask.id}/resume`, {}); const worktree = await succeeded(worktreeTask.id); save('actual-worktree-completed.json', worktree);
  const ownership = worktree.task.workspaceOwnership; assert.equal(ownership.strategy, 'WORKTREE'); assert.equal(ownership.commonDirectory, resolve(source, '.git'));
  assert.equal(git(worktree.task.workspace, 'rev-parse', 'HEAD').trim(), featureHead); assert.equal(git(worktree.task.workspace, 'branch', '--show-current').trim(), '');
  assert.ok(git(source, 'worktree', 'list', '--porcelain').includes(`locked ${ownership.lockReason}`)); assert.equal(readFileSync(join(worktree.task.workspace, 'app.txt'), 'utf8'), 'owned WORKSPACE_WORKTREE_ROOT\n');
  assert.equal(readFileSync(join(worktree.task.workspace, 'AGENTS.md'), 'utf8'), 'feature rules\n'); sourceUnchanged(stableSource);
  pass('Actual Worker created a locked detached worktree at the fixed branch commit and kept source metadata unchanged');
  phase = 'actual remote-only clone execution'; await api(`/tasks/${cloneTask.id}/resume`, {}); const clone = await succeeded(cloneTask.id); save('actual-clone-completed.json', clone);
  assert.equal(clone.task.workspaceOwnership.strategy, 'CLONE'); assert.notEqual(clone.task.workspaceOwnership.commonDirectory, resolve(source, '.git'));
  assert.equal(git(clone.task.workspace, 'rev-parse', 'HEAD').trim(), remoteHead); assert.equal(git(clone.task.workspace, 'remote').trim(), ''); assert.equal(readFileSync(join(clone.task.workspace, 'remote-only.txt'), 'utf8'), 'remote-only fixture\n'); sourceUnchanged(stableSource);
  pass('Actual independent clone fetched the remote-only selected commit without borrowing shared Git metadata');
  for (const current of [worktree, clone]) {
    assert.equal(current.executions.length, 2); assert.equal(current.executions[1].state, 'WAITING_INPUT'); assert.equal(current.task.workspaceSelectionHistory[0].selection.version, 1);
    assert.equal(current.task.workspaceSelection.version, 2); assert.equal(current.task.budget.usedCalls, 4); assert.equal(current.executions[0].budgetAtStart.usedCalls, 0);
    assert.equal(current.requests.length, 4); assert.ok(current.leases.every(lease => lease.executorPid === first.worker.pid));
    const preparation = current.operations.find(operation => operation.kind === 'WORKSPACE'); assert.deepEqual(preparation.workspaceSelection, current.task.workspaceSelection);
    assert.deepEqual(current.leases.find(lease => lease.id === preparation.workerLeaseId).workspaceSelection, current.task.workspaceSelection);
    assert.deepEqual(current.executions[0].workspaceOwnership, current.task.workspaceOwnership);
  }
  pass('Selections, root attempts, preparation leases and real model requests retained their original identities and cumulative budget');
  for (const current of [worktree, clone]) {
    const exported = await archive(current.task.id, `${current.task.workspaceSelection.strategy.toLowerCase()}-task.zip`);
    assert.deepEqual(exported.task.workspaceSelection, current.task.workspaceSelection); assert.deepEqual(exported.task.workspaceOwnership, current.task.workspaceOwnership);
    assert.equal(exported.executions.length, 2); assert.deepEqual(exported.executions[1].workspaceSelection, current.task.workspaceSelectionHistory[0].selection);
    assert.ok(exported.leases.every(lease => !Object.hasOwn(lease, 'claimDigest')));
  }
  pass('Full task ZIPs retained fixed selections, previous attempts and actual ownership while removing claim digests');
  phase = 'production browser stale creation'; await new Promise(done => web.listen(0, '127.0.0.1', done));
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(`http://127.0.0.1:${web.address().port}`); await page.getByRole('button', { name: '连接设置', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(token);
  await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '新建任务', exact: true }).click(); const creation = page.getByRole('dialog');
  await creation.getByLabel('任务名称').fill('真实浏览器启动选择'); await creation.getByLabel('你希望完成什么？').fill('WORKSPACE_UI_DRAFT_ROOT');
  await creation.getByLabel('验收条件', { exact: true }).fill('固定实际启动提交'); await creation.getByLabel('必要验证命令').fill('node verify.mjs');
  await creation.getByLabel('首选运行路由').selectOption(route.id); await creation.getByLabel('启动提交').waitFor({ state: 'visible' });
  await until('生产页面实际分支列表', async () => creation.getByLabel('启动提交').isEnabled());
  await creation.getByLabel('任务现场', { exact: true }).selectOption('WORKTREE'); await creation.getByLabel('启动提交').selectOption('refs/heads/feature');
  assert.equal(await creation.getByText(featureHead, { exact: true }).count(), 1);
  const originalIndex = readFileSync(join(source, '.git/index')), callsBeforeUiCreation = modelCalls.length;
  git(source, 'update-index', '--assume-unchanged', 'app.txt'); const changedIndexSha256 = hash(readFileSync(join(source, '.git/index'))); assert.notEqual(changedIndexSha256, hash(originalIndex));
  await creation.getByRole('button', { name: '生成任务计划' }).click(); await creation.getByRole('alert').filter({ hasText: '刷新启动选择' }).waitFor();
  assert.equal(await creation.getByLabel('任务名称').inputValue(), '真实浏览器启动选择');
  const refreshedOptionsResponse = page.waitForResponse(response => response.url().endsWith(`/repository-bindings/${project.repositoryBindingId}/workspace-options`) && response.request().method() === 'GET');
  await creation.getByRole('button', { name: '刷新启动选择' }).click(); const refreshedOptions = await refreshedOptionsResponse;
  assert.equal(refreshedOptions.status(), 200); assert.equal((await refreshedOptions.json()).source.index, changedIndexSha256);
  await until('刷新生产页面启动选择', async () => await creation.getByLabel('启动提交').isEnabled() && await creation.getByLabel('启动提交').inputValue() === 'refs/heads/feature');
  assert.equal(await creation.getByLabel('任务现场', { exact: true }).inputValue(), 'WORKTREE'); assert.equal(await creation.getByLabel('启动提交').inputValue(), 'refs/heads/feature');
  await creation.getByRole('button', { name: '生成任务计划' }).click(); await creation.waitFor({ state: 'hidden' });
  const browserTask = (await api('/state')).tasks.find(task => task.goal === 'WORKSPACE_UI_DRAFT_ROOT'); assert.ok(browserTask); assert.equal(browserTask.state, 'DRAFT');
  assert.equal(browserTask.workspaceSelection.baseline, featureHead); assert.equal(browserTask.workspaceSelection.source.index, changedIndexSha256); assert.equal(browserTask.workspaceSelection.strategy, 'WORKTREE');
  assert.equal(browserTask.budget.usedCalls, 0); assert.equal(modelCalls.length, callsBeforeUiCreation); save('production-browser-stale-and-refreshed-choice.json', { task: await api(`/tasks/${browserTask.id}`), originalIndexSha256: hash(originalIndex), changedIndexSha256 });
  writeFileSync(join(source, '.git/index'), originalIndex); sourceUnchanged(stableSource);
  pass('Production task creation exposed a stale-source 409, kept inputs and submitted refreshed actual Git metadata without execution');
  phase = 'production browser ownership';
  await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: /^固定 worktree 选择/ }).click();
  const contract = page.getByRole('region', { name: '固定任务现场' }); await contract.getByText(ownership.owner, { exact: true }).waitFor(); await contract.getByText('之前的启动选择（1）').click();
  for (const width of [1440, 430]) { await page.setViewportSize({ width, height: 1000 });
    await page.evaluate(async () => { await document.fonts.ready; await new Promise(done => requestAnimationFrame(() => requestAnimationFrame(() => done()))); });
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: join(evidence, `workspace-real-${width}.png`), fullPage: true, animations: 'disabled' }); }
  assert.equal(await contract.getByRole('button', { name: '重新确认启动选择' }).count(), 0); assert.deepEqual(pageErrors, []); await browser.close(); browser = undefined;
  pass('Production browser showed actual ownership and fixed selection history on desktop and narrow screens');
  phase = 'changed worktree lock'; const beforeLockChange = await facts(worktreeTask.id), callsBeforeLockChange = modelCalls.length;
  git(source, 'worktree', 'unlock', worktree.task.workspace); await api(`/tasks/${worktreeTask.id}/revalidate`, {}); await api(`/tasks/${worktreeTask.id}/resume`, {}, 400);
  const blocked = await facts(worktreeTask.id); assert.equal(blocked.executions.length, beforeLockChange.executions.length); assert.equal(blocked.operations.length, beforeLockChange.operations.length); assert.equal(modelCalls.length, callsBeforeLockChange);
  git(source, 'worktree', 'lock', '--reason', ownership.lockReason, worktree.task.workspace); save('changed-lock-blocked.json', blocked); sourceUnchanged(stableSource);
  pass('A changed worktree lock blocked a new root attempt and all Worker execution');
  phase = 'actual READY task receipt persistence fault'; readyTask = await createTask(project, route, 'READY 原归属恢复', 'WORKSPACE_READY_ROOT', available, 'WORKTREE');
  databaseFixture('fail-ready', readyTask.id); await api(`/tasks/${readyTask.id}/approve`, {}); const readyFile = join(data, 'workspaces', readyTask.id, 'selection.json');
  await until('原 READY 现场和任务保存故障', async () => existsSync(readyFile) && JSON.parse(readFileSync(readyFile)).state === 'READY' && (await api('/health')).status === 'DEGRADED');
  readyManifest = JSON.parse(readFileSync(readyFile)); const beforeRestart = await facts(readyTask.id); save('ready-manifest-before-task-receipt.json', readyManifest); save('ready-task-receipt-fault.json', beforeRestart);
  assert.equal(beforeRestart.task.workspace, ''); assert.equal(beforeRestart.task.workspacePreparationPresent, true); assert.equal(beforeRestart.task.budget.usedCalls, 0); assert.equal(beforeRestart.operations[0].state, 'ACKNOWLEDGED'); assert.equal(beforeRestart.leases[0].state, 'RELEASED');
  assert.equal(modelCalls.filter(call => call.marker === 'WORKSPACE_READY_ROOT').length, 0); const worktreeCount = (await options(project)).worktrees.length;
  pass('Real Worker READY metadata and ACK survived the injected failure saving the task receipt without a model call');
  await stopCore(first); save('original-worker-stop.json', inspectProcess(first.worker.pid, first.worker.processStartedAt, 'stop')); databaseFixture('clear', readyTask.id);
  writeFileSync(join(source, 'AGENTS.md'), 'source rules changed after READY\n'); git(source, 'add', '.'); git(source, 'commit', '-m', 'source rules changed after READY'); const movedSource = sourceFacts();
  phase = 'ordinary Jar restart and READY reuse'; const second = await startCore(); const reopened = await facts(readyTask.id); save('ready-after-reopen.json', reopened);
  assert.equal(reopened.task.state, 'UNKNOWN'); assert.equal(reopened.task.workspace, ''); assert.equal(reopened.executions[0].id, beforeRestart.executions[0].id); assert.equal(reopened.executions[0].state, 'UNKNOWN');
  assert.equal(reopened.task.budget.usedCalls, 0); assert.equal(modelCalls.filter(call => call.marker === 'WORKSPACE_READY_ROOT').length, 0); assert.deepEqual(JSON.parse(readFileSync(readyFile)), readyManifest);
  await api(`/tasks/${readyTask.id}/resume`, {}, 409); await reconcileProcesses();
  await api(`/tasks/${readyTask.id}/reconcile`, { processesStopped: true, sideEffectsChecked: true, note: 'Dedicated Jar fixture: original READY file, task save failure and ACK inspected; no model request or external tool was dispatched. Original PID/birth and recorded descendants independently stopped.' });
  await api(`/tasks/${readyTask.id}/workspace-selection`, { expectedVersion: 1, workspaceSelection: choice(await options(project), 'CLONE') }, 409);
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 430, height: 1000 } }); page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(`http://127.0.0.1:${web.address().port}`); await page.getByRole('button', { name: '连接设置', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(token);
  await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: /^READY 原归属恢复/ }).click();
  const readyContract = page.getByRole('region', { name: '固定任务现场' });
  await readyContract.getByText('已有现场准备记录，原选择保持固定；继续前请核对原执行和现场。').waitFor();
  assert.equal(await readyContract.getByRole('button', { name: '重新确认启动选择' }).count(), 0);
  await page.getByRole('button', { name: '更新执行授权' }).click(); const readyAuthorization = page.getByRole('dialog');
  assert.equal(await readyAuthorization.getByLabel('启动提交').count(), 0);
  const authorizationResponse = page.waitForResponse(response => response.url().endsWith(`/tasks/${readyTask.id}/execution-authorization`) && response.request().method() === 'POST');
  await readyAuthorization.getByRole('button', { name: '确认新的执行版本' }).click(); assert.equal((await authorizationResponse).status(), 200); await readyAuthorization.waitFor({ state: 'hidden' });
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true); await page.screenshot({ path: join(evidence, 'ready-preserved-browser-430.png'), fullPage: true });
  await browser.close(); browser = undefined;
  const renewed = await facts(readyTask.id); assert.deepEqual(renewed.task.workspaceSelection, readyTask.workspaceSelection); assert.equal(renewed.task.context.find(item => item.id === 'project-rules').content, 'main rules\n');
  pass('Restart and production authorization kept the original UNKNOWN attempt, READY choice and old rules without requesting a replacement selection');
  await api(`/tasks/${readyTask.id}/resume`, {}); const recovered = await succeeded(readyTask.id); save('ready-recovered-completed.json', recovered);
  assert.equal(recovered.task.recoveredPreparation, true); assert.deepEqual(recovered.task.workspaceOwnership, readyManifest.facts.workspaceOwnership); assert.equal(recovered.task.baseline, readyTask.workspaceSelection.baseline);
  assert.equal((await options(project)).worktrees.length, worktreeCount); assert.equal(recovered.executions.length, 2); assert.equal(recovered.executions[1].state, 'UNKNOWN'); assert.ok(recovered.executions[1].reconciliation);
  assert.equal(recovered.task.budget.usedCalls, 4); assert.equal(recovered.executions[0].budgetAtStart.usedCalls, 0); assert.ok(recovered.requests.every(request => request.workerId === second.worker.id)); sourceUnchanged(movedSource);
  const recoveredArchive = await archive(readyTask.id, 'ready-unknown-and-recovered.zip'); assert.deepEqual(recoveredArchive.task.workspaceOwnership, readyManifest.facts.workspaceOwnership); assert.equal(recoveredArchive.executions[1].state, 'UNKNOWN');
  pass('Ordinary Jar reused the same READY owner after source moved and exported both attempts without another worktree');
  phase = 'partial preparation preservation'; const latest = await options(project), partial = await createTask(project, route, '保留部分准备意图', 'WORKSPACE_PARTIAL_ROOT', latest, 'WORKTREE');
  const partialParent = join(data, 'workspaces', partial.id); mkdirSync(partialParent, { recursive: true });
  const partialBytes = Buffer.from(JSON.stringify({ schemaVersion: 1, taskId: partial.id, owner: 'dedicated-partial-fixture', state: 'PREPARED', selection: partial.workspaceSelection })); writeFileSync(join(partialParent, 'selection.json'), partialBytes);
  await api(`/tasks/${partial.id}/approve`, {}); const partialFacts = await until('部分准备拒绝覆盖', async () => { const value = await facts(partial.id); return value.executions[0]?.state === 'WAITING_INPUT' && value; });
  assert.deepEqual(readFileSync(join(partialParent, 'selection.json')), partialBytes); assert.equal(existsSync(join(partialParent, 'work')), false); assert.equal(partialFacts.operations[0].state, 'REJECTED');
  assert.equal(partialFacts.task.budget.usedCalls, 0); assert.equal(modelCalls.filter(call => call.marker === 'WORKSPACE_PARTIAL_ROOT').length, 0);
  await api(`/tasks/${partial.id}/workspace-selection`, { expectedVersion: 1, workspaceSelection: choice(latest, 'CLONE') }, 409); save('partial-intent-preserved.json', { fixture: 'EXPLICIT_PREPARED_METADATA_FAULT_WINDOW', intent: JSON.parse(partialBytes), facts: partialFacts });
  assert.equal((await options(project)).worktrees.length, worktreeCount); sourceUnchanged(movedSource);
  pass('The explicit partial-intent fault window remained unchanged and could not be overwritten or reselected');
  assert.deepEqual(modelErrors, []); assert.deepEqual(pageErrors, []); assert.equal(hash(readFileSync(jar)), jarSha256);
  assert.equal(hash(readFileSync(fileURLToPath(import.meta.url))), scriptSha256);
  for (const entry of productionWebHashes) assert.equal(hash(readFileSync(join(root, entry.file))), entry.sha256); await stopCore(second);
  const cleanup = [...identities.values()].map(value => inspectProcess(value.pid, value.processStartedAt, 'stop')); save('independent-fixture-process-stop.json', cleanup); assert.ok(cleanup.every(value => value.originalStopped));
  pass('Both launched cores exited and all original recorded Worker identities were independently confirmed stopped');
  report = { recordedAt: new Date().toISOString(), version, platform: process.platform, status: 'passed', fixture: 'ACTUAL_JAR_WITH_EXTERNAL_QUEUE_GATE_AND_RECEIPT_FAULT', jarSha256, scriptSha256, productionWebHashes,
    browserModule: '@playwright/test from web/package.json', browserVersion, checks, schedulerParallelism: 2, schedulerMaxPoolSize: 2,
    worktreeTaskId: worktreeTask.id, cloneTaskId: cloneTask.id, readyTaskId: readyTask.id, partialTaskId: partial.id, featureHead, remoteHead,
    worktreeOwner: ownership.owner, originalReadyOwner: readyManifest.owner, recoveredReadyOwner: recovered.task.workspaceOwnership.owner,
    readyOriginalTaskExecutionId: beforeRestart.executions[0].id, readyRecoveredTaskExecutionId: recovered.executions[0].id, automaticReplay: false,
    corePids: cores.map(core => core.child.pid), workerPids: cores.map(core => core.worker.pid), modelCalls, pageErrors, modelErrors, evidenceDirectory: evidence };
  save('report.json', report); mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/workspace-selection-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify(report, null, 2));
} catch (error) {
  const failure = { recordedAt: new Date().toISOString(), version, status: 'failed', phase, error: error.message.replaceAll(token, '[REDACTED]'), stack: error.stack?.replaceAll(token, '[REDACTED]'), checks, jarSha256, scriptSha256, productionWebHashes, modelCalls, modelErrors, pageErrors, evidenceDirectory: evidence };
  if (page && !page.isClosed()) { try { await page.screenshot({ path: join(evidence, 'browser-before-cleanup.png'), fullPage: true, mask: [page.getByLabel('访问令牌', { exact: true })] }); } catch {} }
  if (readyTask && baseUrl) { try { failure.readyTaskFacts = await facts(readyTask.id); } catch {} }
  save('failure-before-cleanup.json', failure); console.error(`${phase}: ${error.message}; 清理前事实保留在 ${evidence}`); process.exitCode = 1;
} finally {
  if (browser) await browser.close(); model.closeAllConnections(); await new Promise(done => model.close(done)); web.closeAllConnections(); await new Promise(done => web.close(done));
  if (currentCore && !exited(currentCore)) { try { await stopCore(currentCore); } catch { currentCore.child.kill('SIGKILL'); await until('本夹具核心强制退出', () => exited(currentCore), 10000).catch(() => {}); } }
  const cleanup = []; for (const value of identities.values()) { try { cleanup.push(inspectProcess(value.pid, value.processStartedAt, 'stop')); } catch (error) { cleanup.push({ ...value, originalStopped: false, error: error.message }); } }
  save(report ? 'fixture-stop-after-report.json' : 'fixture-stop-after-failure.json', cleanup); if (cleanup.some(value => !value.originalStopped)) process.exitCode = 1;
  for (const core of cores) writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}
