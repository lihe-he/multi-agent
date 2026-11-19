import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, writeFileSync } from 'node:fs';
import { extname, join, resolve } from 'node:path';
import { zipEntry } from './team-smoke-fixture.mjs';
import { freezeTerminalArtifacts, inspectOriginalTerminalBundle, terminalCoreEnvironment, terminalFixtureRoot, writeTerminalShellFixture } from './terminal-fixture.mjs';

const root = terminalFixtureRoot; process.chdir(root);
const packaged = process.argv.slice(2).includes('--packaged'); assert.ok(process.argv.slice(2).every(value => value === '--packaged'));
mkdirSync('.runtime', { recursive: true }); const directory = mkdtempSync(resolve('.runtime', packaged ? 'terminal-native-ui-packaged-' : 'terminal-native-ui-'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const save = (file, value) => writeFileSync(join(directory, file), JSON.stringify(value, null, 2) + '\n', { flag: 'wx' });
copyFileSync('tools/terminal-native-ui-smoke.mjs', join(directory, 'runner-source.mjs'));
copyFileSync('tools/terminal-fixture.mjs', join(directory, 'artifact-fixture-source.mjs'));
const inputs = freezeTerminalArtifacts(directory, { withWeb: true, packaged }), corePackage = inputs.core, webBuild = inputs.web;
const { java, probeClasses } = inputs, jar = corePackage.jar;
const packageDirectory = corePackage.originalJar, buildDirectory = packaged ? corePackage.resources : resolve('web/dist');
const coreEnvironment = terminalCoreEnvironment(directory, packaged);
const browserChannel = process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium');
const { chromium, expect } = createRequire(resolve('web/package.json'))('@playwright/test');
const coreToken = randomBytes(32).toString('hex'), source = resolve(directory, '中文 original source'), data = resolve(directory, 'data'); mkdirSync(source);
const identities = new Map(), requests = [], uiRequests = [], modelCalls = [], checks = [], tasks = [], pageErrors = [], staticRequests = [];
const scrub = value => Array.isArray(value) ? value.map(scrub) : value && typeof value === 'object'
  ? Object.fromEntries(Object.entries(value).filter(([key]) => !['controlSecret', 'controllerDigest', 'token', 'apiKey', 'dataBase64', 'claimDigest'].includes(key)).map(([key, item]) => [key, scrub(item)])) : value;
const pause = ms => new Promise(done => setTimeout(done, ms));
const pass = name => { checks.push({ name, at: new Date().toISOString() }); console.log(JSON.stringify({ passed: name })); };
async function until(name, operation, timeout = 45000) { const end = Date.now() + timeout; while (Date.now() < end) { const value = await operation(); if (value) return value; await pause(100); } throw new Error(`${name} exceeded ${timeout}ms`); }
function probe(mode, original) { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', mode, String(original.pid), original.processStartedAt || '-'], { windowsHide: true, encoding: 'utf8', timeout: 30000 })); }
function remember(original) { if (original?.pid > 0 && original.processStartedAt) identities.set(`${original.pid}:${original.processStartedAt}`, { pid: original.pid, processStartedAt: original.processStartedAt }); }
function git(...args) { return execFileSync('git', ['-C', source, ...args], { windowsHide: true, encoding: 'utf8', timeout: 20000 }).trim(); }
const shellFixture = writeTerminalShellFixture(source, { ui: true });
git('init', '--initial-branch=main'); git('config', 'user.name', 'Native UI Fixture'); git('config', 'user.email', 'native-ui@example.invalid'); git('add', '.'); git('commit', '-m', 'Original native terminal UI source');
const originalSource = { head: git('rev-parse', 'HEAD'), index: readFileSync(join(source, '.git/index')), config: readFileSync(join(source, '.git/config')) };
function sourceUnchanged() { assert.equal(git('rev-parse', 'HEAD'), originalSource.head); assert.equal(git('branch', '--show-current'), 'main'); assert.deepEqual(readFileSync(join(source, '.git/index')), originalSource.index); assert.deepEqual(readFileSync(join(source, '.git/config')), originalSource.config); assert.equal(readFileSync(join(source, 'app.txt'), 'utf8'), 'original native UI source\n'); }
const model = createServer(async (request, response) => {
  try {
    let raw = ''; for await (const bytes of request) raw += bytes; const body = JSON.parse(raw); assert.equal(body.model, 'native-ui-local-fixture');
    const prompt = body.messages.filter(message => ['system', 'user'].includes(message.role)).map(message => message.content).join('\n'), kind = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1];
    modelCalls.push({ ordinal: modelCalls.length + 1, kind, at: new Date().toISOString() }); assert.ok(modelCalls.length <= 6);
    response.writeHead(200, { 'Content-Type': 'application/json', Connection: 'close' });
    response.end(JSON.stringify({ id: `native-ui-response-${modelCalls.length}`, model: body.model, choices: [{ message: { role: 'assistant', content: kind === 'REVIEW' ? '{"approved":true,"issues":[]}' : 'Original fixture stage completed without tool or file changes.' }, finish_reason: 'stop' }], usage: { prompt_tokens: 5, completion_tokens: 4 } }));
  } catch (failure) { response.writeHead(500, { 'Content-Type': 'application/json', Connection: 'close' }); response.end(JSON.stringify({ error: { message: failure.message } })); }
});
await new Promise(done => model.listen(0, '127.0.0.1', done));
let baseUrl, core, coreIdentity, browser, page, phase = 'independent core startup', status = 'failed', error;
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${coreToken}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) });
  const bytes = Buffer.from(await response.arrayBuffer()), value = response.headers.get('content-type')?.includes('application/zip') ? bytes : JSON.parse(bytes.toString('utf8'));
  requests.push({ path, method: body === undefined ? 'GET' : 'POST', body: scrub(body), status: response.status, response: Buffer.isBuffer(value) ? { bytes: value.length, sha256: hash(value) } : scrub(value) });
  assert.equal(response.status, expected, `${path}: HTTP ${response.status}`); return value;
}
const runtimeConfiguration = Buffer.from('{"apiBaseUrl":"/service"}');
const assets = new Map(webBuild.output.map(file => [`/${file.file}`, file]));
const web = createServer(async (request, response) => {
  try {
    const url = new URL(request.url, 'http://127.0.0.1');
    if (url.pathname.startsWith('/service/api/')) {
      const parts = []; for await (const bytes of request) parts.push(bytes); const raw = Buffer.concat(parts), body = raw.length ? JSON.parse(raw.toString('utf8')) : undefined;
      const observed = { path: url.pathname.slice('/service'.length), query: url.search, method: request.method, bearerHeaderPresent: request.headers.authorization === `Bearer ${coreToken}`, body: scrub(body),
        ...(body?.dataBase64 ? { inputBytes: Buffer.from(body.dataBase64, 'base64').length, inputSha256: hash(Buffer.from(body.dataBase64, 'base64')) } : {}) };
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', ...(raw.length ? { 'Content-Type': 'application/json' } : {}) }, ...(raw.length ? { body: raw } : {}), signal: AbortSignal.timeout(30000) });
      const bytes = Buffer.from(await upstream.arrayBuffer()); observed.status = upstream.status;
      if (upstream.headers.get('content-type')?.includes('application/json')) observed.response = scrub(JSON.parse(bytes.toString('utf8')));
      uiRequests.push(observed); response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(bytes); return;
    }
    if (url.pathname === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' }); response.end(runtimeConfiguration); return; }
    const normalized = url.pathname === '/' ? '/index.html' : url.pathname, asset = assets.get(normalized);
    if (!asset) { response.writeHead(404); response.end(); return; }
    const bytes = readFileSync(join(directory, 'dist', asset.file)); assert.equal(hash(bytes), asset.sha256); staticRequests.push({ path: url.pathname, sha256: asset.sha256 });
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json', '.svg': 'image/svg+xml' }[extname(asset.file)] || 'application/octet-stream', 'Cache-Control': 'no-store' }); response.end(bytes);
  } catch { response.writeHead(502); response.end(); }
});
async function paste(text) { await page.getByRole('textbox', { name: '终端输入', exact: true }).evaluate((element, value) => { const data = new DataTransfer(); data.setData('text/plain', value); element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true })); }, text); }
async function openTask(title) { await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: title }).click(); await page.getByRole('button', { name: '终端', exact: true }).click(); }
try {
  core = { logs: '', child: spawn(java, ['-Dfile.encoding=UTF-8', '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', `-Djdk.net.unixdomain.tmpdir=${inputs.nioDirectory}`, '-jar', jar], { cwd: resolve(directory), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, ...coreEnvironment, WORKBENCH_TOKEN: coreToken, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', LOGGING_LEVEL_ROOT: 'INFO' } }) };
  for (const stream of [core.child.stdout, core.child.stderr]) stream.on('data', bytes => { core.logs += bytes.toString('utf8').replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]'); });
  await until('original core startup', () => { if (core.child.exitCode !== null || core.child.signalCode !== null) throw new Error('Original core exited before startup'); const port = core.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  coreIdentity = probe('identity', { pid: core.child.pid }); remember(coreIdentity);
  const health = await api('/health'); assert.equal(health.version, corePackage.version); assert.equal(health.schemaVersion, 11); assert.equal(health.terminalProtocolVersion, 1); assert.equal(health.corePid, core.child.pid);
  (await api('/workers')).forEach(remember); const project = await api('/projects', { name: 'Original native UI project', path: source });
  const route = await api('/routes', { name: 'Only local native UI model', engine: 'API', model: 'native-ui-local-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, enabled: true });
  await new Promise(done => web.listen(0, '127.0.0.1', done));
  browser = await chromium.launch({ channel: browserChannel, headless: true }); page = await browser.newPage({ viewport: { width: 1440, height: 1000 } });
  page.on('pageerror', failure => pageErrors.push(failure.message)); await page.goto(`http://127.0.0.1:${web.address().port}`);
  await page.getByRole('button', { name: '本地核心未连接', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(coreToken);
  await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  pass('Actual production Web authenticated by core Bearer header against the exact production Jar and its real independent Worker');
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `real-${strategy.toLowerCase()}-native-ui`;
    let task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `Original ${strategy} native UI task`, goal: `Preserve the original ${strategy} shell, measured size, raw Unicode and exactly one manual effect.`, mode: 'LEAD', workspaceSelection: { strategy, ref: 'HEAD' }, contract: { scope: ['**'], acceptance: ['Original native UI source and complete evidence'], commands: [shellFixture.baselineCommand] }, budget: { maxCalls: 12, reserveCalls: 4 } });
    tasks.push({ taskId: task.id, strategy }); await api(`/tasks/${task.id}/approve`, {});
    task = await until('original full plan acceptance boundary', async () => { const value = await api(`/tasks/${task.id}`); if (['UNKNOWN', 'FAILED', 'WAITING_INPUT'].includes(value.state)) throw new Error(`Original task became ${value.state}: ${value.error}`); return value.state === 'AWAITING_ACCEPTANCE' ? value : null; });
    await until('original root settlement', async () => (await api(`/tasks/${task.id}/task-executions?limit=1`)).executions[0]?.endedAt);
    const beforeTask = task, callsBefore = modelCalls.length; await page.reload(); await openTask(task.title); const panel = page.locator('.task-terminals');
    await panel.getByRole('button', { name: '新建终端', exact: true }).click();
    let terminal = await until('original native shell running under the existing HTTP acceptance boundary', async () => { const value = (await api(`/tasks/${task.id}/terminals`)).terminals[0];
      if (value?.state === 'UNKNOWN') throw new Error('Original native shell startup became UNKNOWN'); return value?.state === 'RUNNING' ? value : null; }, 30000);
    remember(terminal.nativeProcess); await expect(panel.locator('.terminal-output-status')).toContainText('正在读取原输出');
    const originalNative = terminal.nativeProcess; save(`${strategy.toLowerCase()}-original-native-bundle.json`, inspectOriginalTerminalBundle(terminal, corePackage)); assert.ok(originalNative.pid > 0); assert.equal(terminal.state, 'RUNNING');
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); assert.equal(terminal.columns, 80); assert.equal(terminal.rows, 24);
    assert.equal(uiRequests.filter(request => request.body?.kind === 'INPUT' && request.path.includes(terminal.id)).length, 0);
    await panel.getByRole('button', { name: '取得控制', exact: true }).click(); await expect(panel.locator('.terminal-mode')).toHaveText('当前窗口控制');
    await until('original measured fit resize acknowledgement', async () => { const actions = (await api(`/terminals/${terminal.id}/actions`)).actions; return actions.some(action => action.kind === 'RESIZE' && action.state === 'ACKNOWLEDGED'); });
    terminal = await api(`/terminals/${terminal.id}`); const geometry = { columns: terminal.columns, rows: terminal.rows };
    const unicodeCommand = shellFixture.unicodeCommand;
    await paste(unicodeCommand); await until('original single Unicode effect', () => existsSync(join(task.workspace, 'manual-count.txt')));
    await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_NATIVE_UI_中文_GREEN'); await expect(panel.locator('.xterm-accessibility')).toContainText('<script>window.__nativeUiMarkupExecuted=true</script>');
    assert.equal(readFileSync(join(task.workspace, 'manual-count.txt'), 'utf8'), '1'); assert.equal(await page.evaluate(() => window.__nativeUiMarkupExecuted), undefined);
    assert.deepEqual(JSON.parse(readFileSync(join(task.workspace, 'actual-environment.json'), 'utf8')), { WORKBENCH_TOKEN: false, WORKBENCH_CORE_TOKEN: false, OPENAI_API_KEY: false, ANTHROPIC_API_KEY: false });
    const sizeCommand = shellFixture.sizeCommand;
    const sizeAcknowledgement = page.waitForResponse(response => {
      const request = response.request();
      return request.method() === 'POST' && new URL(request.url()).pathname === `/service/api/terminals/${terminal.id}/actions`
        && request.postDataJSON()?.kind === 'INPUT' && request.postDataJSON()?.dataBase64 === Buffer.from(sizeCommand).toString('base64');
    }).then(response => ({ response }), failure => ({ failure }));
    await paste(sizeCommand); await until('actual native shell dimensions', () => existsSync(join(task.workspace, 'actual-native-size.txt')));
    const acknowledgement = await sizeAcknowledgement; if (acknowledgement.failure) throw acknowledgement.failure;
    assert.equal(acknowledgement.response.status(), 200); assert.equal(await acknowledgement.response.finished(), null);
    assert.equal(readFileSync(join(task.workspace, 'actual-native-size.txt'), 'utf8').trim(), `${geometry.rows} ${geometry.columns}`);
    await page.screenshot({ path: join(directory, `${strategy.toLowerCase()}-actual-controlled-1440.png`), fullPage: true });
    phase = `${strategy.toLowerCase()}-browser-transport-offline-recovery`;
    const originalDrained = await until('original published output fully drained before network loss', async () => {
      const value = await api(`/terminals/${terminal.id}`), text = await panel.locator('.terminal-output-status').innerText();
      const cursor = Number(text.match(/已排空 ([\d,]+) 字节/)?.[1].replaceAll(',', ''));
      return cursor > 0 && cursor === value.outputEnd ? cursor : null;
    });
    const postsBeforeNetworkLoss = uiRequests.filter(request => request.method === 'POST' && request.path.includes(terminal.id)).length;
    await page.context().setOffline(true);
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
    const coreWhileBrowserOffline = await api(`/terminals/${terminal.id}`);
    assert.deepEqual(coreWhileBrowserOffline.nativeProcess, originalNative); assert.equal(coreWhileBrowserOffline.epoch, terminal.epoch);
    assert.equal(coreWhileBrowserOffline.state, 'RUNNING'); assert.equal(readFileSync(join(task.workspace, 'manual-count.txt'), 'utf8'), '1');
    const resumedRequestIndex = uiRequests.length, resumedAt = Date.now(); await page.context().setOffline(false);
    await expect(panel.locator('.terminal-output-status')).toContainText('正在读取原输出', { timeout: 1500 });
    const resumedReads = uiRequests.slice(resumedRequestIndex).filter(request => request.path.startsWith(`/api/terminals/${terminal.id}`));
    assert.ok(resumedReads.some(request => request.path === `/api/terminals/${terminal.id}` && request.method === 'GET'));
    const resumedOutput = resumedReads.filter(request => request.path.endsWith('/output'));
    assert.ok(resumedOutput.length > 0 && resumedOutput.every(request => request.method === 'GET' && Number(new URLSearchParams(request.query).get('after')) >= originalDrained));
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
    await expect(page.getByRole('textbox', { name: '终端输出（仅查看）', exact: true })).not.toBeEditable();
    assert.equal(uiRequests.filter(request => request.method === 'POST' && request.path.includes(terminal.id)).length, postsBeforeNetworkLoss);
    save(`${strategy.toLowerCase()}-actual-network-read-recovery.json`, { originalNative, epoch: terminal.epoch, originalDrained,
      restoredMs: Date.now() - resumedAt, coreReachableWhileBrowserOffline: true, originalManualEffectCount: 1,
      originalControlNotRestored: true, noAdditionalPosts: true, resumedReads: scrub(resumedReads),
      scope: 'ACTUAL_BROWSER_TRANSPORT_OFFLINE_WITH_REAL_JAVA_CORE_AND_NATIVE_PTY; NO_OS_SLEEP_OR_NETWORK_ADAPTER_CHANGES' });
    await page.screenshot({ path: join(directory, `${strategy.toLowerCase()}-network-restored-read-only.png`), fullPage: true });
    pass(`${strategy}: actual browser transport loss preserves the original native process and drained cursor; recovery reads fresh metadata and output without control takeover or action replay`);
    phase = `${strategy.toLowerCase()}-continued-original-ui-acceptance`;
    await page.getByRole('button', { name: '代码差异', exact: true }).click(); await page.getByRole('button', { name: '终端', exact: true }).click();
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_NATIVE_UI_中文_GREEN');
    await page.setViewportSize({ width: 430, height: 1100 }); await page.reload(); await openTask(task.title);
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_NATIVE_UI_中文_GREEN');
    terminal = await api(`/terminals/${terminal.id}`); assert.deepEqual(terminal.nativeProcess, originalNative); assert.equal(terminal.columns, geometry.columns); assert.equal(terminal.rows, geometry.rows);
    assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true);
    await page.screenshot({ path: join(directory, `${strategy.toLowerCase()}-actual-observer-430.png`), fullPage: true });
    const actionPage = await api(`/terminals/${terminal.id}/actions`), inputs = actionPage.actions.filter(action => action.kind === 'INPUT');
    const manualInputs = [unicodeCommand, sizeCommand].map(command => {
      const bytes = Buffer.from(command), matching = inputs.filter(action => action.length === bytes.length && action.sha256 === hash(bytes));
      assert.equal(matching.length, 1, 'Each exact original manual command must have one original action.'); return matching[0];
    });
    const focusReports = inputs.filter(action => !manualInputs.some(original => original.id === action.id));
    for (const action of focusReports) {
      assert.equal(action.length, 3); assert.ok(['\u001b[I', '\u001b[O'].map(value => hash(Buffer.from(value))).includes(action.sha256), 'Only the exact native terminal focus report bytes may accompany the two manual commands.');
    }
    assert.ok(inputs.every(action => action.state === 'ACKNOWLEDGED' && action.dispatchCount === 1 && action.nativeAuthorizationCount === 1));
    assert.equal(readFileSync(join(task.workspace, 'manual-count.txt'), 'utf8'), '1');
    assert.equal((await api(`/tasks/${task.id}/terminals`)).terminals.length, 1); assert.equal(uiRequests.filter(request => request.method === 'POST' && request.path === `/api/tasks/${task.id}/terminals`).length, 1);
    assert.equal(modelCalls.length, callsBefore); assert.equal((await api(`/tasks/${task.id}`)).budget.usedCalls, beforeTask.budget.usedCalls); sourceUnchanged();
    save(`${strategy.toLowerCase()}-actual-native-ui-before-stop.json`, { taskId: task.id, originalNative, geometry, originalManualInputActions: manualInputs, originalNativeFocusReportActions: focusReports, originalInputActions: inputs, actionPage, modelCalls: callsBefore, beforeTask: scrub(beforeTask), terminal: scrub(terminal) });
    pass(`${strategy}: real native Unicode, actual shell dimensions, explicit control, leave/reopen/reload, original PID and two once-only manual commands matched the frozen Web; native focus reports were separately matched by exact bytes and digest`);
    await panel.getByRole('button', { name: '接管控制', exact: true }).click(); await expect(panel.locator('.terminal-mode')).toHaveText('当前窗口控制');
    if (strategy === 'WORKTREE') {
      phase = 'actual-repository-binding-authorization-revocation';
      const takeover = await api(`/terminals/${terminal.id}`);
      await until('original takeover geometry acknowledgement before revocation', async () => {
        const actions = (await api(`/terminals/${terminal.id}/actions`)).actions;
        return actions.some(action => action.kind === 'RESIZE' && action.controllerGeneration === takeover.controllerGeneration && action.state === 'ACKNOWLEDGED');
      });
      const beforeRevocationActions = (await api(`/terminals/${terminal.id}/actions`)).actions;
      const binding = (await api(`/repository-bindings?projectId=${project.id}`)).find(value => value.id === task.execution.repositoryBindingId);
      const revoked = await api('/repository-bindings', { id: binding.id, expectedVersion: binding.version, projectId: binding.projectId, executionNodeId: binding.executionNodeId, name: binding.name, path: binding.path, enabled: false });
      assert.equal(revoked.enabled, false); assert.equal(revoked.version, binding.version + 1);
      const inputBox = page.getByRole('textbox', { name: '终端输入', exact: true });
      const forbiddenCommand = shellFixture.forbiddenCommand, attemptedFromEditableUi = await inputBox.count() > 0 && await inputBox.isEditable();
      if (attemptedFromEditableUi) await paste(forbiddenCommand);
      await until('original native shell stopped after actual authorization revocation', () => probe('inspect', originalNative).originalStopped);
      await until('original terminal authorization loss fenced as UNKNOWN', async () => { const value = await api(`/terminals/${terminal.id}`); return value.state === 'UNKNOWN' && value.inputFenced ? value : null; });
      const originalRevokedRoot = await until('original revoked terminal root UNKNOWN end receipt', async () => {
        const value = (await api(`/tasks/${task.id}/task-executions?limit=1`)).executions[0]; assert.equal(value.id, terminal.taskExecutionId); return value.state === 'UNKNOWN' && value.endedAt ? value : null;
      });
      const afterTerminal = await api(`/terminals/${terminal.id}`), afterTask = await api(`/tasks/${task.id}`), afterActions = (await api(`/terminals/${terminal.id}/actions`)).actions;
      assert.equal(afterTerminal.state, 'UNKNOWN'); assert.equal(afterTerminal.outputIntegrity, 'UNCONFIRMED'); assert.equal(afterTask.state, 'UNKNOWN');
      assert.equal(existsSync(join(task.workspace, 'must-not-execute.txt')), false);
      const postRevocationActions = afterActions.filter(action => !beforeRevocationActions.some(original => original.id === action.id)), rejectedInput = postRevocationActions.filter(action => action.kind === 'INPUT');
      assert.ok(postRevocationActions.every(action => action.nativeAuthorizationCount === 0 && ['REJECTED', 'WITHDRAWN', 'UNKNOWN'].includes(action.state)));
      assert.ok(rejectedInput.every(action => action.nativeAuthorizationCount === 0 && ['REJECTED', 'WITHDRAWN', 'UNKNOWN'].includes(action.state)));
      assert.equal(afterTask.budget.usedCalls, beforeTask.budget.usedCalls); assert.equal(modelCalls.length, callsBefore);
      await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(page.getByRole('textbox', { name: '终端输出（仅查看）', exact: true })).not.toBeEditable();
      const forbiddenRequests = uiRequests.filter(request => request.inputSha256 === hash(Buffer.from(forbiddenCommand)));
      save('actual-authorization-revocation.json', { originalBinding: binding, revokedBinding: revoked, terminal: scrub(afterTerminal), task: scrub(afterTask), originalRevokedRoot,
        beforeRevocationActions, postRevocationActions, rejectedInput, actions: afterActions, attemptedFromEditableUi, originalForbiddenHttpRequests: forbiddenRequests,
        originalNativeStop: probe('inspect', originalNative), forbiddenEffectAbsent: true, unknownOutputNotReclassifiedComplete: true });
      pass('Actual repository binding disable stopped the original native shell and fenced the UI; original task, terminal, root and unconfirmed output remained UNKNOWN, with no post-revocation native authorization or new model budget');
    } else {
      await panel.getByRole('button', { name: '停止原终端', exact: true }).click(); await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
      task = await until('original native terminal complete settlement', async () => { const value = await api(`/tasks/${task.id}`), observed = await api(`/terminals/${terminal.id}`); return observed.endedAt && value.state === 'PAUSED' ? value : null; });
      terminal = await api(`/terminals/${terminal.id}`); assert.equal(terminal.state, 'CANCELLED'); assert.equal(terminal.outputIntegrity, 'COMPLETE');
      const originalTerminalRoot = await until('original manual terminal root execution end receipt', async () => {
        const value = (await api(`/tasks/${task.id}/task-executions?limit=1`)).executions[0];
        assert.equal(value.id, terminal.taskExecutionId); return value.endedAt ? value : null;
      });
      assert.equal(task.verification.status, 'NOT_RUN'); assert.equal(task.review.status, 'NOT_RUN'); assert.equal(task.budget.usedCalls, beforeTask.budget.usedCalls);
      assert.equal(probe('inspect', originalNative).originalStopped, true);
      const bytes = await api(`/tasks/${task.id}/export`); writeFileSync(join(directory, 'clone-original-native-output-task.zip'), bytes, { flag: 'wx' });
      const sources = JSON.parse(zipEntry(bytes, 'terminal-output-sources.json')), originalOutput = sources.find(value => value.terminalId === terminal.id);
      assert.ok(originalOutput); assert.equal(originalOutput.terminalState, 'CANCELLED'); assert.equal(originalOutput.recordedOutputIntegrity, 'COMPLETE');
      JSON.parse(zipEntry(bytes, 'process-observations.json')).forEach(remember);
      save('clone-original-complete-settlement.json', { task: scrub(task), terminal: scrub(terminal), originalTerminalRoot, originalOutput, zipSha256: hash(bytes), originalNativeStop: probe('inspect', originalNative) });
      pass('Actual UI stop retained complete original terminal output ZIP, ended the original shell, preserved budget and reset current verification/review');
    }
    await page.getByRole('button', { name: '全部任务', exact: true }).click(); await page.setViewportSize({ width: 1440, height: 1000 }); sourceUnchanged();
  }
  assert.deepEqual(pageErrors, []); assert.ok(uiRequests.filter(request => request.path.includes('/terminals')).every(request => request.bearerHeaderPresent && !/token|controlSecret/.test(request.query)));
  status = 'passed';
} catch (failure) { error = { phase, message: failure.message.replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]'), stack: failure.stack?.replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]') }; save('original-failure.json', error); console.error(JSON.stringify({ directory, phase, error: error.message })); }
finally {
  if (page && !page.isClosed() && status !== 'passed') { try { await page.screenshot({ path: join(directory, 'original-ui-before-cleanup.png'), fullPage: true, mask: [page.getByLabel('访问令牌', { exact: true })] }); } catch {} }
  if (browser) await browser.close();
  if (core?.child.exitCode === null && core.child.signalCode === null) { try { await api('/shutdown', {}); await until('original core controlled exit', () => core.child.exitCode !== null || core.child.signalCode !== null); } catch { if (coreIdentity) probe('stop', coreIdentity); } }
  if (core) writeFileSync(join(directory, 'core-original.log'), core.logs, { flag: 'wx' });
  save('fixture.json', { purpose: 'ORIGINAL_NATIVE_UI_CORE_WORKER_AND_NATIVE_IDENTITIES', root: resolve(directory) });
  const identityFile = resolve(directory, 'original-identities.tsv'); writeFileSync(identityFile, [...identities.values()].map(original => `${original.pid}\t${original.processStartedAt}`).join('\n') + '\n', { flag: 'wx' });
  let stopped = [];
  try { stopped = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', 'inspect-all', identityFile], { windowsHide: true, encoding: 'utf8', timeout: 60000 }));
    if (stopped.some(original => !original.originalStopped)) stopped = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', 'stop-all', identityFile], { windowsHide: true, encoding: 'utf8', timeout: 60000 })); } catch (failure) { error ||= { phase: 'independent-original-process-stop', message: failure.message }; }
  save('original-process-stops.json', stopped); model.closeAllConnections(); await new Promise(done => model.close(done)); web.closeAllConnections(); await new Promise(done => web.close(done));
  const jarUnchanged = hash(readFileSync(jar)) === corePackage.jarSha256, sourcesUnchanged = corePackage.sources.every(file => hash(readFileSync(file.file)) === file.sha256) && webBuild.sources.every(file => hash(readFileSync(file.file)) === file.sha256);
  const assetsUnchanged = webBuild.output.every(file => hash(readFileSync(join(directory, 'dist', file.file))) === file.sha256), originalIdentitiesStopped = stopped.length === identities.size && stopped.every(original => original.originalStopped);
  save('http-observations.json', requests); save('actual-browser-http-observations.json', uiRequests); save('static-web-observations.json', staticRequests);
  const report = { recordedAt: new Date().toISOString(), status: status === 'passed' && !error && jarUnchanged && sourcesUnchanged && assetsUnchanged && originalIdentitiesStopped ? 'passed' : 'failed', phase, packageDirectory, buildDirectory, jarSha256: corePackage.jarSha256, jarUnchanged, sourcesUnchanged, assetsUnchanged,
    version: corePackage.version, schemaVersion: 11, terminalProtocolVersion: 1, browserChannel, browsersPath: process.env.PLAYWRIGHT_BROWSERS_PATH,
    serverOwnedByRunner: true, modelTransport: 'LOCAL_FIXTURE_CONNECTION_CLOSE_WITH_NO_APPLICATION_RETRY', runtimeConfiguration: { apiBaseUrl: '/service', sha256: hash(runtimeConfiguration), containsCredentials: false },
    checks, modelCalls, tasks, pageErrors, originalCore: coreIdentity, originalIdentities: [...identities.values()], originalIdentitiesStopped, error,
    packaged, runtimeJava: java, runtimeJavaSha256: corePackage.javaSha256, externalNodeAndJavaUnavailableOnPath: packaged,
    scope: packaged ? 'ACTUAL_PACKAGED_JRE_JAR_ASAR_WEB_REAL_NATIVE_SHELL_WITH_SYSTEM_TOOLS_ONLY; ELECTRON_LIFECYCLE_AND_COMPLETE_HISTORICAL_PROCESS_TREE_ACCEPTANCE_SEPARATE' : 'ACTUAL_PRODUCTION_MAIN_JAR_WEB_REAL_INDEPENDENT_WORKER_AND_NATIVE_SHELL; PACKAGED_JRE_AND_COMPLETE_HISTORICAL_PROCESS_TREE_ACCEPTANCE_SEPARATE' };
  save('report.json', report);
  mkdirSync('docs/qa', { recursive: true }); const qaReport = `docs/qa/terminals-native-ui${packaged ? '-packaged' : ''}-${process.platform}.json`;
  if (existsSync(qaReport)) writeFileSync(join(directory, 'previous-qa-report.json'), readFileSync(qaReport), { flag: 'wx' });
  writeFileSync(qaReport, JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify({ directory, status: report.status, checks: checks.length, modelCalls: modelCalls.length, originalIdentitiesStopped, sourcesUnchanged, jarUnchanged, assetsUnchanged })); process.exitCode = report.status === 'passed' ? 0 : 1;
}
