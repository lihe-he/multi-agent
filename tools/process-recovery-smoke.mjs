import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createRequire } from 'node:module';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { join, resolve, extname } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url)));
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
const windows = process.platform === 'win32';
const java = join(javaHome(root), 'bin', windows ? 'java.exe' : 'java');
const jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
if (!existsSync(jar) || !existsSync(join(root, 'web/dist/index.html'))) throw new Error('请先构建最新核心与 Web。');
mkdirSync(join(root, '.runtime'), { recursive: true });
const dir = mkdtempSync(join(root, '.runtime', 'process-recovery-'));
const token = randomBytes(32).toString('hex');
const cores = [], modelCalls = [], heldResponses = [], pageErrors = [], modelErrors = [], checks = [];
const fixtureIdentities = new Map();
const paths = Object.fromEntries(['pid', 'initialized', 'eof', 'mode', 'calls', 'events'].map(name => [name, join(dir, `mcp-${name}.txt`)]));
const fixtureArgs = [join(root, 'tools/recovery-mcp-fixture.mjs'), ...Object.values(paths)];
writeFileSync(paths.mode, 'normal');
let baseUrl, currentCore, browser, phase = 'startup', mcpTask, heldTask, mcpSession, oldWorker, report;
const save = (name, data) => writeFileSync(join(dir, name), JSON.stringify(data, null, 2) + '\n');
const wait = ms => new Promise(done => setTimeout(done, ms));
const lines = file => existsSync(file) ? readFileSync(file, 'utf8').trim().split('\n').filter(Boolean).map(line => JSON.parse(line)) : [];
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };

const model = createServer(async (request, response) => {
  try {
    if (request.method === 'GET') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ data: [{ id: 'recovery-fixture' }] })); return; }
    let raw = ''; for await (const chunk of request) raw += chunk;
    const body = JSON.parse(raw), prompt = body.messages.filter(message => ['system', 'user'].includes(message.role)).map(message => message.content).join('\n');
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(body.model, 'recovery-fixture');
    const goal = prompt.includes('RECOVERY_HOLD_MODEL') ? 'RECOVERY_HOLD_MODEL' : prompt.includes('RECOVERY_HOLD_MCP') ? 'RECOVERY_HOLD_MCP' : 'RECOVERY_NEW_TASK';
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1];
    modelCalls.push({ number: modelCalls.length + 1, goal, stage, at: new Date().toISOString() });
    if (goal === 'RECOVERY_HOLD_MODEL') { heldResponses.push(response); return; }
    const alias = (body.tools || []).find(tool => tool.function.name.startsWith('mcp_'))?.function.name;
    let message = { role: 'assistant', content: stage === 'REVIEW' ? JSON.stringify({ approved: true, issues: [], summary: 'Recovery fixture review' }) : 'Recovery fixture stage completed.' };
    if (goal === 'RECOVERY_HOLD_MCP' && stage === 'IMPLEMENT') {
      assert.ok(alias, 'MCP task must receive its actual frozen tool alias');
      message = { role: 'assistant', content: null, tool_calls: [{ id: 'recovery-original-tool', type: 'function', function: { name: alias, arguments: '{}' } }] };
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ id: `recovery-response-${modelCalls.length}`, model: body.model, choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { modelErrors.push(error.message); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: error.message } })); }
});

const web = createServer(async (request, response) => {
  try {
    const pathname = new URL(request.url, 'http://localhost').pathname;
    if (pathname.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' }, ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(10000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (pathname === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ apiBaseUrl: '/service' })); return; }
    if (!/^\/(?:index\.html|assets\/[a-zA-Z0-9_.-]+)?$/.test(pathname)) { response.writeHead(404); response.end(); return; }
    const file = join(root, 'web/dist', pathname === '/' ? 'index.html' : pathname);
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(file)] || 'application/octet-stream' }); response.end(readFileSync(file));
  } catch { response.writeHead(502); response.end(); }
});

async function api(path, body, expected = 200) {
  let response;
  try { response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000) }); }
  catch (error) { throw new Error(`${path}: ${error.message}`, { cause: error }); }
  const raw = await response.text(); let data; try { data = JSON.parse(raw); } catch { data = raw; }
  assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${raw.slice(0, 500)}`); return data;
}

async function until(name, action, timeout = 45000) {
  const deadline = Date.now() + timeout; let value;
  while (Date.now() < deadline) { value = await action(); if (value) return value; await wait(100); }
  throw new Error(`${name} 未在有界期限内达到预期`);
}

async function waitForObservedStop(sessionId, evidenceName) {
  const deadline = Date.now() + 45000, observations = [];
  while (Date.now() < deadline) {
    const session = await api(`/process-recovery/${sessionId}`);
    observations.push({ at: new Date().toISOString(), session });
    save(evidenceName, observations);
    if (session.observedProcessesStopped) return session;
    await wait(500);
  }
  throw new Error(`原会话 ${sessionId} 的已记录进程未在有界期限内确认退出；观察事实保留在 ${evidenceName}`);
}

async function startCore() {
  const number = cores.length + 1;
  const core = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${dir}`, '-jar', jar], { cwd: dir, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: join(dir, 'data'), LOGGING_LEVEL_ROOT: 'INFO' } });
  const instance = { core, logs: '', number, error: null }; cores.push(instance); currentCore = instance;
  for (const stream of [core.stdout, core.stderr]) stream.on('data', chunk => { instance.logs += chunk.toString(); });
  core.once('error', error => { instance.error = error; });
  await until('独立核心启动', async () => {
    if (instance.error) throw instance.error;
    if (core.exitCode !== null || core.signalCode !== null) throw new Error(`第 ${number} 个核心提前退出；完整日志保留在 ${dir}`);
    const port = instance.logs.match(/Tomcat started on port (\d+)/)?.[1];
    if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; }
  });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.schemaVersion, 7); assert.equal(health.corePid, core.pid);
  return instance;
}

const exited = instance => instance.core.exitCode !== null || instance.core.signalCode !== null;
async function stopCore(instance, crash = false) {
  if (exited(instance)) return;
  if (crash) instance.core.kill('SIGKILL');
  else { try { await api('/shutdown', {}); } catch {} }
  await until('原核心退出', () => exited(instance), crash ? 10000 : 30000);
  writeFileSync(join(dir, `core-${instance.number}.log`), instance.logs.replaceAll(token, '[REDACTED]'));
}

function inspectProcess(pid, birth, mode = 'inspect') {
  return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', join(root, 'tools/ProcessRecoveryProbe.java'), mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 }));
}

function rememberFixture(session) {
  if (!session) return;
  for (const fact of [{ pid: session.pid, processStartedAt: session.processStartedAt }, ...(session.recordedProcesses || [])]) {
    if (fact.pid && fact.processStartedAt) fixtureIdentities.set(`${fact.pid}@${fact.processStartedAt}`, { pid: fact.pid, processStartedAt: fact.processStartedAt });
  }
}

function cleanupFixture(suffix = '') {
  const identities = [...fixtureIdentities.values()].sort((a, b) => Number(a.pid === mcpSession.pid) - Number(b.pid === mcpSession.pid));
  const observations = [];
  for (const fact of identities) {
    try { observations.push(inspectProcess(fact.pid, fact.processStartedAt, 'stop')); }
    catch (error) { observations.push({ pid: fact.pid, originalStartedAt: fact.processStartedAt, originalStopped: false, error: error.message }); }
  }
  save(`fixture-cleanup-processes${suffix}.json`, observations);
  const original = observations.find(fact => fact.pid === mcpSession.pid && fact.originalStartedAt === mcpSession.processStartedAt);
  assert.ok(original); save(`fixture-cleanup${suffix}.json`, original);
  assert.ok(observations.every(fact => fact.originalStopped), 'All recorded original fixture identities must independently confirm stop');
  return original;
}

async function taskFacts(taskId) {
  const task = await api(`/tasks/${taskId}`);
  return { taskId, state: task.state, budget: task.budget, mcpBudget: task.mcpBudget, taskExecutionId: task.taskExecutionId, dispatchActionId: task.dispatchActionId,
    taskExecutions: (await api(`/tasks/${taskId}/task-executions?limit=100`)).executions, dispatchActions: (await api(`/tasks/${taskId}/dispatch-actions?limit=100`)).actions,
    requests: (await api(`/tasks/${taskId}/model-requests?limit=100`)).requests,
    leases: (await api(`/tasks/${taskId}/worker-leases?limit=100`)).leases, operations: await api(`/tasks/${taskId}/operations`), sessions: await api(`/tasks/${taskId}/process-sessions`) };
}

const processNote = 'Test fixture: original PID/birth, startup/EOF/stop receipts and all fixture launches checked; unlisted process coverage reviewed separately; unknown external effects were not replayed.';
const confirmProcesses = { processesStopped: true, coverageChecked: true, note: processNote };
const effectNote = { processesStopped: true, sideEffectsChecked: true, note: 'Recovery fixture files and the one original external call checked separately; preserved original requests and budget; no replay.' };

try {
  await new Promise(done => model.listen(0, '127.0.0.1', done));
  const first = await startCore();
  const projectDir = join(dir, '中文 recovery project'); mkdirSync(projectDir);
  writeFileSync(join(projectDir, 'README.md'), 'Core restart fixture\n');
  writeFileSync(join(projectDir, '.gitattributes'), '* text eol=lf\n');
  writeFileSync(join(projectDir, 'verify.mjs'), "import assert from 'node:assert/strict'; assert.equal(process.env.WORKBENCH_TOKEN,undefined); console.log('recovery fixture verified');\n");
  for (const args of [['init'], ['config', 'user.name', 'Recovery fixture'], ['config', 'user.email', 'recovery@example.invalid'], ['add', '.'], ['commit', '-m', 'Create recovery fixture']]) execFileSync('git', ['-C', projectDir, ...args], { stdio: 'ignore', windowsHide: true });
  const project = await api('/projects', { path: projectDir });
  const route = await api('/routes', { name: 'Recovery model fixture', engine: 'API', model: 'recovery-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1` });
  const server = await api('/mcp/servers', { name: 'Recovery stdio fixture', transport: 'STDIO', command: process.execPath, args: fixtureArgs, enabled: true, projectIds: [project.id], allowedTools: ['echo'] });
  const discovery = await api(`/mcp/servers/${server.id}/probe`, {}); assert.equal(discovery.available, true);
  const binding = { serverId: server.id, name: 'echo', configVersion: server.version, definitionVersion: discovery.definitionVersion, fixedArguments: { text: 'RECOVERY_ORIGINAL_EXTERNAL_CALL' }, timeoutMs: 60000 };
  const createTask = (title, goal, mcpBindings = []) => api('/tasks', { projectId: project.id, routeId: route.id, title, goal, mode: 'LEAD', locks: { engine: 'API', model: route.model }, contract: { scope: ['**'], acceptance: ['Preserve restart evidence and original external effects'], commands: ['node verify.mjs'] }, budget: { maxCalls: 12, reserveCalls: 3 }, mcpBindings, mcpBudget: { maxCalls: 2, maxDurationMs: 120000 } });
  mcpTask = await createTask('原 MCP 等待任务', 'RECOVERY_HOLD_MCP', [binding]);
  writeFileSync(paths.mode, 'hold-call'); await api(`/tasks/${mcpTask.id}/approve`, {});
  phase = 'actual MCP dispatch';
  await until('实际 stdio 工具请求', async () => { const task = await api(`/tasks/${mcpTask.id}`); assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT'].includes(task.state), `${task.state}: ${task.error}`); return lines(paths.calls).length === 1; });
  mcpSession = (await api(`/mcp/servers/${server.id}/process-sessions`)).find(session => session.state === 'RUNNING'); assert.ok(mcpSession);
  rememberFixture(mcpSession);
  assert.equal(mcpSession.pid, Number(readFileSync(paths.pid, 'utf8'))); assert.equal(mcpSession.taskId, mcpTask.id);
  assert.equal(inspectProcess(mcpSession.pid, mcpSession.processStartedAt).matchesOriginal, true);
  pass('Actual stdio tool dispatch recorded before core crash');

  heldTask = await createTask('原模型等待任务', 'RECOVERY_HOLD_MODEL'); await api(`/tasks/${heldTask.id}/approve`, {});
  phase = 'actual model dispatch';
  await until('实际在途模型请求', async () => { const requests = (await api(`/tasks/${heldTask.id}/model-requests`)).requests; return heldResponses.length === 1 && requests.some(request => request.state === 'DISPATCHING'); });
  const before = { mcp: await taskFacts(mcpTask.id), model: await taskFacts(heldTask.id), mcpOperations: await api(`/mcp/servers/${server.id}/operations`), modelCalls: [...modelCalls], externalCalls: lines(paths.calls), recovery: await api('/process-recovery') };
  oldWorker = (await api('/workers')).find(worker => worker.state === 'RUNNING'); assert.ok(oldWorker);
  assert.equal(oldWorker.deployment, 'LOCAL_PROCESS'); assert.notEqual(oldWorker.pid, first.core.pid); assert.equal(oldWorker.corePid, first.core.pid);
  assert.ok(before.model.requests.every(request => request.workerId === oldWorker.id && request.processSessionId === oldWorker.processSessionId));
  assert.equal(before.mcpOperations.length, 1); assert.equal(before.mcpOperations[0].state, 'DISPATCHED'); assert.equal(before.mcpOperations[0].processSessionId, mcpSession.id);
  assert.equal(before.mcpOperations[0].workerId, oldWorker.id); assert.equal(before.mcpOperations[0].toolCallId, 'recovery-original-tool');
  save('before-crash.json', { ...before, oldWorker, corePid: first.core.pid, mcpSession });
  pass('Real Worker source and in-flight HTTP request captured');

  phase = 'core crash'; await stopCore(first, true);
  await until('stdio 在核心退出后收到 EOF', () => existsSync(paths.eof) && Number(readFileSync(paths.eof, 'utf8')) === mcpSession.pid, 10000);
  const orphan = inspectProcess(mcpSession.pid, mcpSession.processStartedAt); assert.equal(orphan.alive, true); assert.equal(orphan.matchesOriginal, true); save('after-core-crash-process.json', orphan);
  pass('Only original core stopped; original MCP survived EOF');

  phase = 'reopened core'; const second = await startCore(); assert.notEqual(second.core.pid, first.core.pid);
  const after = { mcp: await taskFacts(mcpTask.id), model: await taskFacts(heldTask.id), mcpOperations: await api(`/mcp/servers/${server.id}/operations`), recovery: await api('/process-recovery'), workers: await api('/workers') };
  rememberFixture(after.recovery.sessions.find(session => session.id === mcpSession.id));
  assert.equal(after.mcp.state, 'UNKNOWN'); assert.equal(after.model.state, 'UNKNOWN');
  assert.deepEqual(after.mcp.budget, before.mcp.budget); assert.deepEqual(after.mcp.mcpBudget, before.mcp.mcpBudget); assert.deepEqual(after.model.budget, before.model.budget);
  assert.equal(after.model.requests.length, before.model.requests.length); assert.equal(after.model.requests[0].id, before.model.requests[0].id); assert.equal(after.model.requests[0].state, 'UNKNOWN');
  for (const name of ['mcp', 'model']) {
    assert.equal(after[name].taskExecutionId, before[name].taskExecutionId); assert.equal(after[name].dispatchActionId, before[name].dispatchActionId);
    assert.equal(after[name].taskExecutions.length, 1); assert.equal(after[name].taskExecutions[0].state, 'UNKNOWN');
    assert.equal(after[name].dispatchActions[0].state, 'UNKNOWN'); assert.equal(after[name].dispatchActions[0].dispatchCount, 1);
    assert.equal(after[name].dispatchActions[0].ownerCorePid, first.core.pid);
    assert.ok(after[name].requests.every(request => request.taskExecutionId === before[name].taskExecutionId && request.dispatchActionId === before[name].dispatchActionId));
  }
  assert.equal(after.model.requests[0].workerLeaseId, before.model.requests[0].workerLeaseId); assert.equal(after.model.requests[0].executorPid, oldWorker.pid);
  assert.ok(after.model.leases.some(lease => lease.id === before.model.requests[0].workerLeaseId && lease.state === 'UNKNOWN'));
  assert.equal(after.mcpOperations[0].id, before.mcpOperations[0].id); assert.equal(after.mcpOperations[0].state, 'UNKNOWN'); assert.equal(after.mcpOperations[0].dispatchState, 'UNKNOWN');
  assert.equal(after.mcpOperations[0].processSessionId, mcpSession.id); assert.equal(after.mcpOperations[0].workerId, oldWorker.id);
  assert.equal(after.recovery.dispatchAllowed, false); assert.ok(after.recovery.sessions.some(session => session.id === mcpSession.id && session.processes.some(fact => fact.pid === mcpSession.pid && fact.observation === 'RUNNING')));
  const newWorker = after.workers.find(worker => worker.state === 'RUNNING'); assert.ok(newWorker); assert.notEqual(newWorker.id, oldWorker.id); assert.notEqual(newWorker.pid, oldWorker.pid);
  save('after-reopen.json', after); pass('Reopen preserved UNKNOWN, original identities and debited budgets');

  const newTask = await createTask('核对后的新任务', 'RECOVERY_NEW_TASK');
  await api(`/tasks/${newTask.id}/approve`, {}, 409); assert.equal((await api(`/tasks/${newTask.id}`)).contract.confirmed, false);
  await api(`/tasks/${mcpTask.id}/reconcile`, effectNote, 409); await api(`/tasks/${heldTask.id}/reconcile`, effectNote, 409);
  await api(`/process-recovery/${mcpSession.id}/reconcile`, confirmProcesses, 409);
  const otherServer = await api('/mcp/servers', { ...server, id: undefined, name: 'Recovery gated second server' });
  await api(`/mcp/servers/${otherServer.id}/probe`, {}, 409); assert.equal((await api(`/mcp/servers/${otherServer.id}/process-sessions`)).length, 0);
  assert.equal(modelCalls.length, before.modelCalls.length); assert.equal(lines(paths.calls).length, 1);
  pass('Live original process blocked task assertions and every new dispatch');

  phase = 'real browser recovery';
  await new Promise(done => web.listen(0, '127.0.0.1', done));
  const { chromium } = createRequire(join(root, 'web/package.json'))('@playwright/test');
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || (windows ? 'msedge' : undefined) });
  const page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(error.message));
  await page.goto(`http://127.0.0.1:${web.address().port}`); await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('访问令牌', { exact: true }).fill(token); await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '执行节点与仓库', exact: true }).click();
  const panel = page.getByRole('region', { name: '旧进程核验' }); const card = panel.locator('.process-recovery-card').filter({ hasText: mcpSession.id });
  const rootFact = card.locator('.process-recovery-observation').filter({ has: page.getByText(`PID ${mcpSession.pid}`, { exact: true }) });
  await rootFact.getByText('原进程仍在运行', { exact: true }).waitFor(); assert.equal(await rootFact.count(), 1); assert.ok((await rootFact.innerText()).includes(mcpSession.processStartedAt));
  mkdirSync(join(root, 'web/qa'), { recursive: true });
  await page.screenshot({ path: join(root, `web/qa/process-recovery-real-${process.platform}.png`), fullPage: true });
  await page.setViewportSize({ width: 430, height: 1000 }); await card.getByRole('button', { name: '记录核对依据', exact: true }).click();
  let dialog = page.getByRole('dialog'); await dialog.getByLabel('我已核对旧执行和已记录进程停止').check(); await dialog.getByLabel('我已核对清单未列出的相关进程').check();
  await dialog.getByLabel('旧进程核对依据', { exact: true }).fill(processNote); assert.equal(await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).isDisabled(), true);
  assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
  await page.screenshot({ path: join(root, `web/qa/process-recovery-real-narrow-${process.platform}.png`), fullPage: true });
  await dialog.getByRole('button', { name: '取消', exact: true }).click(); pass('Real browser showed original birth and rejected a live-process assertion');

  phase = 'original process stop'; await card.getByRole('button', { name: '请求停止原进程', exact: true }).click();
  await waitForObservedStop(mcpSession.id, 'original-mcp-stop-observations.json');
  const stopped = inspectProcess(mcpSession.pid, mcpSession.processStartedAt); assert.equal(stopped.alive, false); save('original-mcp-stopped.json', stopped);
  await api(`/tasks/${mcpTask.id}/reconcile`, effectNote, 409); await api(`/tasks/${newTask.id}/approve`, {}, 409);
  await card.getByRole('button', { name: '记录核对依据', exact: true }).click(); dialog = page.getByRole('dialog');
  await dialog.getByLabel('我已核对旧执行和已记录进程停止').check(); await dialog.getByLabel('我已核对清单未列出的相关进程').check(); await dialog.getByLabel('旧进程核对依据', { exact: true }).fill(processNote);
  await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).click(); await dialog.waitFor({ state: 'hidden' });
  const oldWorkerSession = await api(`/process-recovery/${oldWorker.processSessionId}`); assert.equal(oldWorkerSession.pid, oldWorker.pid); assert.equal(oldWorkerSession.processStartedAt, oldWorker.processStartedAt);
  save('original-worker-stop-receipt.json', await api(`/process-recovery/${oldWorkerSession.id}/stop`, {}));
  await waitForObservedStop(oldWorkerSession.id, 'original-worker-stop-observations.json');
  await api(`/process-recovery/${oldWorkerSession.id}/reconcile`, confirmProcesses);
  assert.equal((await api('/process-recovery')).dispatchAllowed, true); assert.equal((await api(`/tasks/${mcpTask.id}`)).state, 'UNKNOWN'); assert.equal((await api(`/tasks/${heldTask.id}`)).state, 'UNKNOWN');
  const savedSession = await api(`/process-recovery/${mcpSession.id}`); assert.equal(savedSession.reconciliations.length, 1); assert.equal(savedSession.coverage, 'OBSERVED_ONLY');
  await api(`/process-recovery/${mcpSession.id}/reconcile`, confirmProcesses); await api(`/process-recovery/${mcpSession.id}/reconcile`, { ...confirmProcesses, note: 'Cannot replace original note' }, 409);
  pass('Original process exit and historical coverage recorded separately');

  phase = 'original external effects';
  assert.equal((await api(`/tasks/${mcpTask.id}/reconcile`, effectNote)).state, 'PAUSED'); assert.equal((await api(`/tasks/${heldTask.id}/reconcile`, effectNote)).state, 'PAUSED');
  const reconciled = { mcp: await taskFacts(mcpTask.id), model: await taskFacts(heldTask.id), mcpOperations: await api(`/mcp/servers/${server.id}/operations`), mcpSession: await api(`/process-recovery/${mcpSession.id}`) };
  assert.deepEqual(reconciled.mcp.budget, before.mcp.budget); assert.deepEqual(reconciled.mcp.mcpBudget, before.mcp.mcpBudget); assert.deepEqual(reconciled.model.budget, before.model.budget);
  assert.equal(reconciled.model.requests[0].state, 'UNKNOWN'); assert.equal(reconciled.model.requests[0].id, before.model.requests[0].id);
  for (const name of ['mcp', 'model']) {
    assert.equal(reconciled[name].taskExecutions[0].state, 'UNKNOWN'); assert.ok(reconciled[name].taskExecutions[0].reconciliation);
    assert.equal(reconciled[name].dispatchActions[0].state, 'UNKNOWN'); assert.ok(reconciled[name].dispatchActions[0].reconciliation);
  }
  assert.equal(reconciled.mcpOperations[0].state, 'RECONCILED'); assert.equal(reconciled.mcpOperations[0].dispatchState, 'UNKNOWN');
  assert.equal(modelCalls.length, before.modelCalls.length); assert.equal(lines(paths.calls).length, 1); save('after-separate-reconciliation.json', reconciled);
  assert.deepEqual(pageErrors, []); await browser.close(); browser = undefined; await stopCore(second);
  pass('Task effects reconciled without changing UNKNOWN requests or refunding budget');

  phase = 'durable reconciliation restart'; const third = await startCore();
  assert.equal((await api('/process-recovery')).dispatchAllowed, true); assert.equal((await api(`/process-recovery/${mcpSession.id}`)).reconciliations.length, 1);
  assert.equal((await api(`/tasks/${heldTask.id}/model-requests`)).requests[0].id, before.model.requests[0].id); assert.equal((await api(`/tasks/${heldTask.id}/model-requests`)).requests[0].state, 'UNKNOWN');
  assert.equal(modelCalls.length, before.modelCalls.length); assert.equal(lines(paths.calls).length, 1); pass('Saved reconciliation survived another complete core restart');

  phase = 'new execution after recovery'; writeFileSync(paths.mode, 'normal'); await api(`/tasks/${newTask.id}/approve`, {});
  const finished = await until('新任务实际完成', async () => { const task = await api(`/tasks/${newTask.id}`); assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT'].includes(task.state), `${task.state}: ${task.error}`); return task.state === 'AWAITING_ACCEPTANCE' && task.taskExecution?.state === 'SUCCEEDED' && task; });
  await api(`/tasks/${newTask.id}/accept`, {}); assert.equal(modelCalls.filter(call => call.goal === 'RECOVERY_NEW_TASK').length, finished.budget.usedCalls);
  assert.equal(modelCalls.filter(call => call.goal !== 'RECOVERY_NEW_TASK').length, before.modelCalls.length); assert.equal(lines(paths.calls).length, 1);
  const archiveResponse = await fetch(`${baseUrl}/api/tasks/${mcpTask.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(15000) }); assert.equal(archiveResponse.status, 200);
  const archive = Buffer.from(await archiveResponse.arrayBuffer()); writeFileSync(join(dir, 'original-task.zip'), archive);
  const exportedSessions = JSON.parse(zipEntry(archive, 'process-sessions.json')); assert.ok(exportedSessions.some(session => session.id === mcpSession.id && session.processStartedAt === mcpSession.processStartedAt && session.reconciliations.length === 1));
  const exportedObservations = JSON.parse(zipEntry(archive, 'process-observations.json')); assert.ok(exportedObservations.some(observation => observation.sessionId === mcpSession.id && observation.pid === mcpSession.pid && observation.processStartedAt === mcpSession.processStartedAt && observation.role === 'ROOT'));
  const exportedRequests = JSON.parse(zipEntry(archive, 'model-requests.json')); assert.deepEqual(exportedRequests.map(request => [request.id, request.state]), before.mcp.requests.map(request => [request.id, request.state]));
  const exportedExecutions = JSON.parse(zipEntry(archive, 'task-executions.json')), exportedActions = JSON.parse(zipEntry(archive, 'dispatch-actions.json'));
  assert.equal(exportedExecutions[0].id, before.mcp.taskExecutionId); assert.equal(exportedExecutions[0].state, 'UNKNOWN'); assert.ok(exportedExecutions[0].reconciliation);
  assert.equal(exportedActions[0].id, before.mcp.dispatchActionId); assert.equal(exportedActions[0].state, 'UNKNOWN'); assert.equal(exportedActions[0].dispatchCount, 1);
  pass('New work completed while original process and request evidence remained exportable');
  assert.deepEqual(modelErrors, []);
  await stopCore(third);
  const cleanup = cleanupFixture();
  assert.equal(cleanup.originalStopped, true, 'Original fixture stop must be independently confirmed before reporting success');
  pass('Final core exited and original fixture stop was independently confirmed');
  report = { date: new Date().toISOString(), version, platform: process.platform, status: 'passed', jarSha256: createHash('sha256').update(readFileSync(jar)).digest('hex'), checks,
    originalCorePid: first.core.pid, restartedCorePids: [second.core.pid, third.core.pid], originalWorkerId: oldWorker.id, originalWorkerPid: oldWorker.pid, originalWorkerStartedAt: oldWorker.processStartedAt,
    originalWorkerSessionId: oldWorker.processSessionId, originalMcpPid: mcpSession.pid, originalMcpStartedAt: mcpSession.processStartedAt, originalMcpSessionId: mcpSession.id,
    originalModelRequestId: before.model.requests[0].id, originalMcpOperationId: before.mcpOperations[0].id, originalModelCalls: before.modelCalls.length, originalExternalCalls: lines(paths.calls).length,
    originalModelTaskExecutionId: before.model.taskExecutionId, originalModelDispatchActionId: before.model.dispatchActionId, originalMcpTaskExecutionId: before.mcp.taskExecutionId, originalMcpDispatchActionId: before.mcp.dispatchActionId,
    newTaskId: newTask.id, newTaskCalls: finished.budget.usedCalls, processCoverage: 'OBSERVED_ONLY', processesAndEffectsReconciledSeparately: true, automaticReplay: false, pageErrors, modelErrors, evidenceDirectory: dir };
  save('report.json', report); mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/process-recovery-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} catch (error) {
  const failure = { date: new Date().toISOString(), status: 'failed', version, platform: process.platform, phase, error: error.message, checks, modelCalls, externalCalls: lines(paths.calls), pageErrors, modelErrors, evidenceDirectory: dir };
  try { if (baseUrl) { failure.recoveryBeforeCleanup = await api('/process-recovery'); rememberFixture(failure.recoveryBeforeCleanup.sessions.find(session => session.id === mcpSession?.id)); } } catch {}
  save('failure-before-cleanup.json', failure); console.error(`${phase}: ${error.message}; 清理前证据保留在 ${dir}`); process.exitCode = 1;
} finally {
  if (browser) await browser.close();
  for (const response of heldResponses) response.destroy();
  model.closeAllConnections(); await new Promise(done => model.close(done));
  web.closeAllConnections(); await new Promise(done => web.close(done));
  if (currentCore && !exited(currentCore)) { try { await stopCore(currentCore); } catch { currentCore.core.kill('SIGKILL'); } }
  if (mcpSession?.processStartedAt) {
    try { cleanupFixture(report ? '-after-report' : ''); }
    catch (error) { console.error(`原夹具清理无法核验：${error.message}`); process.exitCode = 1; }
  }
  for (const instance of cores) writeFileSync(join(dir, `core-${instance.number}.log`), instance.logs.replaceAll(token, '[REDACTED]'));
}
