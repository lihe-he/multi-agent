import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { delimiter, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
const javaRoot = javaHome(root), windows = process.platform === 'win32';
const java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java');
const jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
if (!existsSync(jar) || !existsSync(join(root, 'web/dist/index.html'))) throw new Error('请先构建当前核心与生产 Web。');
mkdirSync(join(root, '.runtime'), { recursive: true });
const dir = mkdtempSync(join(root, '.runtime/dispatch-queue-')), evidence = join(dir, 'reports'); mkdirSync(evidence);
const token = randomBytes(32).toString('hex'), jarSha256 = createHash('sha256').update(readFileSync(jar)).digest('hex');
const cores = [], checks = [], modelCalls = [], heldResponses = [], pageErrors = [], identities = new Map();
const wait = ms => new Promise(done => setTimeout(done, ms));
const save = (name, data) => writeFileSync(join(evidence, name), JSON.stringify(data, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
let baseUrl, currentCore, browser, page, hold = true, phase = 'fixture compilation', report, pending, heldTask;

async function until(name, action, timeout = 45000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await wait(100); }
  throw new Error(`${name} 未在有界期限内达到预期`);
}

const model = createServer(async (request, response) => {
  try {
    let text = ''; for await (const chunk of request) text += chunk;
    const body = JSON.parse(text), prompt = body.messages.filter(message => ['system', 'user'].includes(message.role)).map(message => message.content).join('\n');
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(body.model, 'dispatch-jar-fixture');
    const goal = prompt.includes('DISPATCH_HOLD_ROOT') ? 'held' : 'pending', stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1];
    modelCalls.push({ number: modelCalls.length + 1, goal, stage, at: new Date().toISOString() });
    if (goal === 'held' && hold) { heldResponses.push(response); return; }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ id: `dispatch-response-${modelCalls.length}`, model: body.model,
      choices: [{ message: { role: 'assistant', content: stage === 'REVIEW' ? '{"approved":true,"issues":[]}' : 'Dedicated queue fixture stage completed.' }, finish_reason: 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: error.message } })); }
});

const web = createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    if (pathname.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' },
        ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(15000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (pathname === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"apiBaseUrl":"/service"}'); return; }
    if (!/^\/(?:index\.html|assets\/[a-zA-Z0-9_.-]+)?$/.test(pathname)) { response.writeHead(404); response.end(); return; }
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(pathname === '/' ? 'index.html' : pathname)] || 'application/octet-stream' });
    response.end(readFileSync(join(root, 'web/dist', pathname === '/' ? 'index.html' : pathname)));
  } catch { response.writeHead(502); response.end(); }
});

async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000) });
  const text = await response.text(); let data; try { data = JSON.parse(text); } catch { data = text; }
  assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${text.slice(0, 500)}`); return data;
}

function remember(worker) {
  if (worker?.pid && worker.processStartedAt) identities.set(`${worker.pid}@${worker.processStartedAt}`, { pid: worker.pid, processStartedAt: worker.processStartedAt });
}

function inspectProcess(pid, birth, mode = 'inspect') {
  return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', join(root, 'tools/ProcessRecoveryProbe.java'), mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 }));
}

async function startCore(data, controlled = false) {
  const number = cores.length + 1, marker = join(dir, `release-${number}.marker`);
  const args = ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${dir}`];
  if (controlled) args.push(`-Dloader.path=${join(dir, 'fixture-classes')}`, '-Dloader.main=dev.agentworkbench.ControlledDispatchLauncher', '-cp', jar, 'org.springframework.boot.loader.launch.PropertiesLauncher', marker);
  else args.push('-jar', jar);
  const child = spawn(java, args, { cwd: dir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  const core = { child, number, data, controlled, marker, logs: '', error: null }; cores.push(core); currentCore = core;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { core.logs += bytes.toString(); }); child.once('error', error => { core.error = error; });
  await until('实际 Jar 核心启动', async () => {
    if (core.error) throw core.error;
    if (child.exitCode !== null || child.signalCode !== null) throw new Error(`第 ${number} 个 Jar 核心提前退出`);
    const port = core.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; }
  });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.schemaVersion, 6); assert.equal(health.corePid, child.pid);
  if (controlled) { assert.ok(core.logs.includes('CONTROLLED_DISPATCH_FIXTURE engine=jar:')); assert.ok(core.logs.includes('BOOT-INF/classes/')); }
  core.worker = (await api('/workers')).find(worker => worker.state === 'RUNNING'); assert.ok(core.worker); remember(core.worker);
  assert.notEqual(core.worker.pid, child.pid); assert.equal(core.worker.deployment, 'LOCAL_PROCESS');
  return core;
}

const exited = core => core.child.exitCode !== null || core.child.signalCode !== null;
async function stopCore(core, crash = false) {
  if (exited(core)) return;
  if (crash) core.child.kill('SIGKILL'); else { try { await api('/shutdown', {}); } catch {} }
  await until('本次 Jar 核心退出', () => exited(core), crash ? 10000 : 30000);
  writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}

async function taskFacts(taskId) {
  const task = await api(`/tasks/${taskId}`);
  return { task, executions: (await api(`/tasks/${taskId}/task-executions?limit=100`)).executions, actions: (await api(`/tasks/${taskId}/dispatch-actions?limit=100`)).actions,
    operations: await api(`/tasks/${taskId}/operations`), leases: (await api(`/tasks/${taskId}/worker-leases?limit=100`)).leases, runs: (await api(`/tasks/${taskId}/agent-runs?limit=100`)).runs,
    requests: (await api(`/tasks/${taskId}/model-requests?limit=100`)).requests };
}

function zeroExecution(facts) {
  for (const kind of ['operations', 'leases', 'runs', 'requests']) assert.equal(facts[kind].length, 0, kind);
  assert.equal(facts.task.budget.usedCalls, 0); assert.equal(facts.task.workspace, '');
}

async function reconcileOldProcesses() {
  const before = await api('/process-recovery');
  assert.equal(before.hasMore, false, 'Dedicated fixture must inspect every pending process session');
  for (const session of before.sessions) {
    remember(session); for (const process of session.recordedProcesses || []) remember(process);
    await api(`/process-recovery/${session.id}/stop`, {});
    await until('原夹具进程清单实际停止', async () => { const current = await api(`/process-recovery/${session.id}`); return current.observedProcessesStopped && current; });
    await api(`/process-recovery/${session.id}/reconcile`, { processesStopped: true, coverageChecked: true, note: 'Dedicated Jar fixture: every launched core/Worker and recorded descendant checked with original PID/birth; unlisted fixture launches separately reviewed.' });
  }
  const after = await api('/process-recovery'); save(`process-reconciliation-${currentCore.number}.json`, { before, after }); assert.equal(after.dispatchAllowed, true);
}

async function createTask(project, route, title, goal = 'DISPATCH_PENDING_ROOT') {
  return api('/tasks', { projectId: project.id, routeId: route.id, title, goal, mode: 'LEAD', contract: { scope: ['**'], acceptance: ['Preserve original durable dispatch identity and budget'], commands: ['node verify.mjs'] }, budget: { maxCalls: 12, reserveCalls: 3 } });
}

async function waitSucceeded(taskId) {
  return until('实际根执行结束回执', async () => {
    const task = await api(`/tasks/${taskId}`); assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT', 'CANCELLED'].includes(task.state), `${task.state}: ${task.error}`);
    return task.state === 'AWAITING_ACCEPTANCE' && task.taskExecution?.state === 'SUCCEEDED' && task;
  });
}

async function archive(taskId, filename) {
  const response = await fetch(`${baseUrl}/api/tasks/${taskId}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000) });
  assert.equal(response.status, 200); const bytes = Buffer.from(await response.arrayBuffer()); writeFileSync(join(evidence, filename), bytes);
  return { task: JSON.parse(zipEntry(bytes, 'task.json')), executions: JSON.parse(zipEntry(bytes, 'task-executions.json')), actions: JSON.parse(zipEntry(bytes, 'dispatch-actions.json')) };
}

try {
  const boot = join(dir, 'boot'), classes = join(dir, 'fixture-classes'); mkdirSync(boot); mkdirSync(classes);
  // Compile the external gate against classes and libraries extracted from this
  // exact Jar. Runtime loading uses PropertiesLauncher and the original Jar.
  execFileSync(join(javaRoot, 'bin', windows ? 'jar.exe' : 'jar'), ['xf', jar, 'BOOT-INF/classes', 'BOOT-INF/lib'], { cwd: boot, windowsHide: true, timeout: 30000 });
  const classpath = [join(boot, 'BOOT-INF/classes'), join(boot, 'BOOT-INF/lib/*')].join(delimiter);
  const fixtureSources = ['ControlledDispatchLauncher.java', 'DispatchDatabaseFixture.java'].map(file => join(root, 'tools', file));
  execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-cp', classpath, '-d', classes, ...fixtureSources], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  save('fixture.json', { version, jar, jarSha256, fixture: 'ACTUAL_JAR_WITH_EXTERNAL_CONTROLLED_EXECUTOR', productionEntry: 'JarLauncher', controlledEntry: 'PropertiesLauncher',
    productionClassesFromTargetDirectory: false, fixtureSources: fixtureSources.map(path => ({ path, sha256: createHash('sha256').update(readFileSync(path)).digest('hex') })) });
  pass('Exact final Jar supplied production classes and the original Worker executable');
  await new Promise(done => model.listen(0, '127.0.0.1', done));
  const source = join(dir, '中文 空格 queue project'); mkdirSync(source);
  writeFileSync(join(source, '.gitattributes'), '* text eol=lf\n');
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict'; assert.equal(process.env.WORKBENCH_TOKEN,undefined); console.log('dedicated queue fixture verified');\n");
  for (const args of [['init', '-b', 'main'], ['config', 'user.name', 'Queue fixture'], ['config', 'user.email', 'fixture@example.invalid'], ['add', '.'], ['commit', '-m', 'Dedicated queue fixture']]) execFileSync('git', ['-C', source, ...args], { stdio: 'ignore', windowsHide: true });
  const routeBody = { name: 'Actual Jar queue model', engine: 'API', model: 'dispatch-jar-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1` };

  phase = 'actual approval transaction fault';
  const fault = await startCore(join(dir, 'fault-data'), true), faultProject = await api('/projects', { path: source }), faultRoute = await api('/routes', routeBody);
  const rejected = await createTask(faultProject, faultRoute, '事务失败零执行');
  execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', [classes, classpath].join(delimiter), 'dev.agentworkbench.DispatchDatabaseFixture', fault.data, 'dispatchAction', rejected.id], { encoding: 'utf8', windowsHide: true, timeout: 15000 });
  await api(`/tasks/${rejected.id}/approve`, {}, 503); const rejectedFacts = await taskFacts(rejected.id); save('approval-fault.json', rejectedFacts);
  zeroExecution(rejectedFacts); assert.equal(rejectedFacts.task.state, 'DRAFT'); assert.equal(rejectedFacts.task.contract.confirmed, false); assert.equal(rejectedFacts.executions.length, 0); assert.equal(rejectedFacts.actions.length, 0); assert.equal(modelCalls.length, 0);
  await stopCore(fault); assert.equal(inspectProcess(fault.worker.pid, fault.worker.processStartedAt, 'stop').originalStopped, true);
  pass('Actual approval transaction failure rolled back confirmation and ran no Worker operation');

  phase = 'controlled pending approval and withdrawal';
  const queueData = join(dir, 'queue-data'), first = await startCore(queueData, true);
  const project = await api('/projects', { path: source }), route = await api('/routes', routeBody);
  pending = await createTask(project, route, '原编号持久排队'); await api(`/tasks/${pending.id}/approve`, {});
  const original = await taskFacts(pending.id); save('pending-before-crash.json', original); zeroExecution(original);
  assert.equal(original.task.state, 'QUEUED'); assert.equal(original.actions[0].state, 'PENDING'); assert.equal(original.actions[0].dispatchCount, 0);
  for (const action of ['pause', 'cancel']) {
    const task = await createTask(project, route, `未领取${action}`, `DISPATCH_UNCLAIMED_${action.toUpperCase()}`); await api(`/tasks/${task.id}/approve`, {}); await api(`/tasks/${task.id}/${action}`, {});
    const facts = await taskFacts(task.id); zeroExecution(facts); assert.equal(facts.actions[0].state, 'WITHDRAWN'); assert.equal(facts.actions[0].dispatchCount, 0);
    assert.equal(facts.executions[0].state, action === 'pause' ? 'PAUSED' : 'CANCELLED'); save(`unclaimed-${action}.json`, facts);
  }
  assert.equal(modelCalls.length, 0); pass('Unclaimed pause and cancellation withdrew original actions without task execution');
  await stopCore(first, true); save('original-pending-worker-stop.json', inspectProcess(first.worker.pid, first.worker.processStartedAt, 'stop'));
  pass('Core exited with the original PENDING action and unchanged budget durably saved');

  phase = 'production Jar pending recovery';
  const second = await startCore(queueData), reopened = await taskFacts(pending.id); zeroExecution(reopened);
  assert.equal(reopened.task.state, 'QUEUED'); assert.equal(reopened.task.taskExecutionId, original.task.taskExecutionId); assert.equal(reopened.actions[0].id, original.actions[0].id); assert.equal(reopened.actions[0].dispatchCount, 0);
  await until('原未领取动作等待进程核验', async () => (await api(`/tasks/${pending.id}`)).dispatch.waitReason);
  save('pending-after-reopen.json', await taskFacts(pending.id)); assert.equal(modelCalls.length, 0);
  pass('Original unclaimed identity survived reopening and waited for old process coverage');

  phase = 'production browser pending queue';
  await new Promise(done => web.listen(0, '127.0.0.1', done));
  const { chromium } = await import('playwright'); browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(`http://127.0.0.1:${web.address().port}`); await page.getByRole('button', { name: '连接设置', exact: true }).click();
  assert.equal(await page.getByLabel('本机核心地址', { exact: true }).inputValue(), '/service');
  await page.getByLabel('访问令牌', { exact: true }).fill(token); await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '原编号持久排队' }).click(); await page.getByRole('button', { name: '任务派发', exact: true }).click();
  const history = page.getByRole('region', { name: '任务派发历史' }); await history.getByText('尚未领取 · 领取 0 次', { exact: true }).waitFor();
  assert.ok((await history.innerText()).includes(original.task.taskExecutionId)); mkdirSync(join(root, 'web/qa'), { recursive: true });
  for (const width of [1440, 430]) { await page.setViewportSize({ width, height: 1000 }); assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true); await page.screenshot({ path: join(root, `web/qa/dispatch-queue-real-${width}-${process.platform}.png`), fullPage: true }); }
  pass('Production browser showed the real unclaimed identity on desktop and narrow screens');

  phase = 'same pending action actual execution'; await reconcileOldProcesses();
  const finished = await waitSucceeded(pending.id), completed = await taskFacts(pending.id); save('pending-executed-once.json', completed);
  assert.equal(completed.executions.length, 1); assert.equal(completed.actions[0].id, original.actions[0].id); assert.equal(completed.actions[0].state, 'SETTLED'); assert.equal(completed.actions[0].dispatchCount, 1); assert.equal(completed.actions[0].ownerCorePid, second.child.pid);
  assert.equal(finished.budget.usedCalls, 3); assert.equal(modelCalls.filter(call => call.goal === 'pending').length, 3);
  for (const records of [completed.operations, completed.leases, completed.runs, completed.requests, completed.task.evidence]) { assert.ok(records.length); assert.ok(records.every(record => record.taskExecutionId === original.task.taskExecutionId && record.dispatchActionId === original.task.dispatchActionId)); }
  pass('New core executed the original pending action once with original root sources');
  await api(`/tasks/${pending.id}/accept`, {}); const firstArchive = await archive(pending.id, 'original-pending-task.zip');
  assert.equal(firstArchive.task.state, 'COMPLETED'); assert.equal(firstArchive.executions[0].taskStateAtEnd, 'AWAITING_ACCEPTANCE'); assert.equal(firstArchive.executions[0].state, 'SUCCEEDED'); assert.equal(firstArchive.actions[0].dispatchCount, 1);
  pass('Full task ZIP kept execution completion separate from human acceptance');

  phase = 'actual claimed root crash'; heldTask = await createTask(project, route, '领取后显式继续', 'DISPATCH_HOLD_ROOT'); await api(`/tasks/${heldTask.id}/approve`, {});
  await until('原根已领取且模型请求在途', async () => { const facts = await taskFacts(heldTask.id); return facts.actions[0]?.state === 'CLAIMED' && facts.requests[0]?.state === 'DISPATCHING' && heldResponses.length === 1 && facts; });
  const claimed = await taskFacts(heldTask.id); save('claimed-before-crash.json', claimed); assert.equal(claimed.actions[0].dispatchCount, 1); assert.equal(claimed.task.budget.usedCalls, 1);
  await stopCore(second, true); save('original-claimed-worker-stop.json', inspectProcess(second.worker.pid, second.worker.processStartedAt, 'stop'));
  pass('Core exited after real claim and in-flight HTTP dispatch');

  phase = 'claimed UNKNOWN recovery'; const third = await startCore(queueData), unknown = await taskFacts(heldTask.id); save('claimed-after-reopen.json', unknown);
  assert.equal(unknown.task.state, 'UNKNOWN'); assert.equal(unknown.executions[0].id, claimed.executions[0].id); assert.equal(unknown.executions[0].state, 'UNKNOWN'); assert.equal(unknown.actions[0].id, claimed.actions[0].id); assert.equal(unknown.actions[0].state, 'UNKNOWN'); assert.equal(unknown.actions[0].dispatchCount, 1);
  assert.deepEqual(unknown.task.budget, claimed.task.budget); assert.equal(unknown.requests[0].id, claimed.requests[0].id); assert.equal(unknown.requests[0].state, 'UNKNOWN'); assert.equal(modelCalls.filter(call => call.goal === 'held').length, 1);
  await api(`/tasks/${heldTask.id}/resume`, {}, 409); await reconcileOldProcesses(); await api(`/tasks/${heldTask.id}/resume`, {}, 409);
  pass('Claimed root stayed UNKNOWN with original request and budget, without replay');

  phase = 'explicit root reconciliation and new attempt';
  await api(`/tasks/${heldTask.id}/reconcile`, { processesStopped: true, sideEffectsChecked: true, note: 'Dedicated HTTP fixture: old request deliberately held, no file or external tool mutation; original processes and workspace checked independently.' });
  const reconciled = await taskFacts(heldTask.id); assert.equal(reconciled.task.state, 'PAUSED'); assert.equal(reconciled.executions[0].state, 'UNKNOWN'); assert.ok(reconciled.executions[0].reconciliation); assert.equal(reconciled.actions[0].state, 'UNKNOWN'); assert.deepEqual(reconciled.task.budget, claimed.task.budget); save('claimed-reconciled.json', reconciled);
  pass('Human reconciliation kept original UNKNOWN history and cumulative budget');
  hold = false; for (const response of heldResponses) response.destroy(); await api(`/tasks/${heldTask.id}/resume`, {});
  const resumed = await waitSucceeded(heldTask.id), final = await taskFacts(heldTask.id); save('explicit-new-attempt.json', final);
  assert.equal(final.executions.length, 2); assert.equal(final.executions[0].attempt, 2); assert.equal(final.executions[0].state, 'SUCCEEDED'); assert.notEqual(final.executions[0].id, claimed.executions[0].id); assert.equal(final.executions[1].state, 'UNKNOWN');
  assert.equal(final.actions[0].state, 'SETTLED'); assert.equal(final.actions[0].dispatchCount, 1); assert.equal(final.actions[1].state, 'UNKNOWN'); assert.equal(final.actions[1].dispatchCount, 1);
  assert.equal(resumed.budget.usedCalls, 4); assert.equal(final.executions[0].budgetAtStart.usedCalls, 1); assert.equal(final.requests.find(request => request.id === claimed.requests[0].id).state, 'UNKNOWN'); assert.equal(modelCalls.filter(call => call.goal === 'held').length, 4);
  const finalArchive = await archive(heldTask.id, 'unknown-and-resumed-task.zip'); assert.equal(finalArchive.executions.length, 2); assert.equal(finalArchive.actions.length, 2); assert.equal(finalArchive.executions[1].state, 'UNKNOWN'); assert.ok(finalArchive.executions[1].reconciliation);
  pass('Explicit resume created a fresh attempt and exported both histories without budget reset');
  assert.deepEqual(pageErrors, []); await browser.close(); browser = undefined; await stopCore(third);
  const cleanup = [...identities.values()].map(fact => inspectProcess(fact.pid, fact.processStartedAt, 'stop')); save('independent-fixture-process-stop.json', cleanup); assert.ok(cleanup.every(fact => fact.originalStopped));
  pass('All launched cores exited and original Worker identities independently confirmed stop');
  assert.equal(createHash('sha256').update(readFileSync(jar)).digest('hex'), jarSha256);
  report = { recordedAt: new Date().toISOString(), version, platform: process.platform, status: 'passed', jarSha256, fixture: 'ACTUAL_JAR_WITH_EXTERNAL_CONTROLLED_EXECUTOR', checks,
    pendingTaskId: pending.id, originalPendingTaskExecutionId: original.task.taskExecutionId, originalPendingDispatchActionId: original.actions[0].id, pendingDispatchCount: 1,
    heldTaskId: heldTask.id, originalClaimedTaskExecutionId: claimed.executions[0].id, originalClaimedDispatchActionId: claimed.actions[0].id, originalModelRequestId: claimed.requests[0].id,
    resumedTaskExecutionId: final.executions[0].id, originalBudget: claimed.task.budget.usedCalls, finalBudget: resumed.budget.usedCalls, automaticReplay: false, originalUnknownPreserved: true,
    corePids: cores.map(core => core.child.pid), workerPids: cores.map(core => core.worker.pid), pageErrors, modelCalls, evidenceDirectory: evidence };
  save('report.json', report); mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/dispatch-queue-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  const failure = { recordedAt: new Date().toISOString(), version, status: 'failed', phase, error: error.message.replaceAll(token, '[REDACTED]'), stack: error.stack?.replaceAll(token, '[REDACTED]'), checks, modelCalls, pageErrors, jarSha256, evidenceDirectory: evidence };
  if (page && !page.isClosed()) { try { await page.screenshot({ path: join(evidence, 'browser-before-cleanup.png'), fullPage: true, mask: [page.getByLabel('访问令牌', { exact: true })] }); } catch {} }
  try { if (baseUrl) failure.processRecoveryBeforeCleanup = await api('/process-recovery'); } catch {}
  save('failure-before-cleanup.json', failure); console.error(`${phase}: ${error.message}; 清理前事实保留在 ${evidence}`); process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  for (const response of heldResponses) response.destroy(); model.closeAllConnections(); await new Promise(done => model.close(done));
  web.closeAllConnections(); await new Promise(done => web.close(done));
  if (currentCore && !exited(currentCore)) { try { await stopCore(currentCore); } catch { currentCore.child.kill('SIGKILL'); } }
  const cleanup = []; for (const fact of identities.values()) { try { cleanup.push(inspectProcess(fact.pid, fact.processStartedAt, 'stop')); } catch (error) { cleanup.push({ ...fact, originalStopped: false, error: error.message }); } }
  save(report ? 'fixture-stop-after-report.json' : 'fixture-stop-after-failure.json', cleanup); if (cleanup.some(fact => !fact.originalStopped)) process.exitCode = 1;
  for (const core of cores) writeFileSync(join(evidence, `core-${core.number}.log`), core.logs.replaceAll(token, '[REDACTED]'));
}
