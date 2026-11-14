import assert from 'node:assert/strict';
import { spawn, execFileSync } from 'node:child_process';
import { createHash, randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, statSync, unlinkSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { tmpdir } from 'node:os';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { inspectModel, zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), windows = process.platform === 'win32';
const modules = createRequire(join(root, 'web/package.json')), { chromium, expect } = modules('@playwright/test');
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version, javaRoot = javaHome(root);
const java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java'), jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
assert.ok(existsSync(jar) && existsSync(join(root, 'web/dist/index.html')), 'Build the current final Jar and production Web before acceptance');
mkdirSync(join(root, '.runtime'), { recursive: true });
const directory = mkdtempSync(join(root, '.runtime/diff-view-')), evidence = join(directory, 'reports'), data = join(directory, 'data'), classes = join(directory, 'fixture-classes');
mkdirSync(evidence); mkdirSync(classes);
const source = join(directory, '中文 diff source'), nioDirectory = windows ? directory : mkdtempSync(join(realpathSync(tmpdir()), 'diff-nio-'));
const token = randomBytes(32).toString('hex'), credential = 'diff-fixture-key', checks = [], calls = [], modelErrors = [], pageErrors = [], tasks = new Map(), identities = new Map(), served = new Map();
const digest = bytes => createHash('sha256').update(bytes).digest('hex'), jarSha256 = digest(readFileSync(jar));
const scriptSource = readFileSync(fileURLToPath(import.meta.url)), scriptSha256 = digest(scriptSource);
const webFiles = readdirSync(join(root, 'web/dist'), { recursive: true }).filter(file => statSync(join(root, 'web/dist', file)).isFile()).sort()
  .map(file => ({ file: `web/dist/${file.replaceAll('\\', '/')}`, sha256: digest(readFileSync(join(root, 'web/dist', file))) }));
const textPath = 'src/中文 view.tsx', literalPath = 'src/literal.tsx';
const originalText = Array.from({ length: 24 }, (_, index) => `原内容行 ${index + 1}`).join('\n') + '\n';
const changedText = originalText.replace('原内容行 2\n', '实现内容行 2\n').replace('原内容行 20\n', '实现内容行 20\n');
const literalText = '+literal <img src=x onerror="window.diffInjected=1"><script>literal</script>\n末行没有换行';
let core, coreIdentity, worker, baseUrl, browser, page, logs = '', processError, report, phase = 'fixture compilation';
const redact = value => String(value).replaceAll(token, '[REDACTED]').replaceAll(credential, '[REDACTED]');
const save = (name, value) => writeFileSync(join(evidence, name), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
const wait = milliseconds => new Promise(done => setTimeout(done, milliseconds));
async function until(name, action, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await wait(100); }
  throw new Error(`${name} did not complete within its bounded deadline`);
}
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30000) });
  const text = await response.text(); assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${redact(text.slice(0, 300))}`); return JSON.parse(text);
}
function git(workspace, ...args) { return execFileSync('git', ['--no-optional-locks', '-c', 'core.hooksPath=/dev/null', '-c', 'core.autocrlf=false', '-C', workspace, ...args], { encoding: 'utf8', windowsHide: true, timeout: 20000 }); }
function treeFacts(workspace) {
  const paths = [...new Set(git(workspace, 'ls-files', '-z', '--cached', '--others', '--exclude-standard').split('\0').filter(Boolean))].sort();
  const metadata = name => resolve(workspace, git(workspace, 'rev-parse', '--git-path', name).trim());
  return { head: git(workspace, 'rev-parse', 'HEAD').trim(), branch: git(workspace, 'branch', '--show-current').trim(), status: git(workspace, 'status', '--porcelain=v1', '-uall'),
    index: digest(readFileSync(metadata('index'))), config: digest(readFileSync(metadata('config'))), refs: git(workspace, 'for-each-ref', '--format=%(refname) %(objectname)'),
    files: paths.map(path => { const file = join(workspace, path); return { path, exists: existsSync(file), ...(existsSync(file) ? { bytes: statSync(file).size, sha256: digest(readFileSync(file)) } : {}) }; }) };
}
function remember(value) { if (value?.pid && value.processStartedAt) identities.set(`${value.pid}@${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt }); }
function probe(mode, pid, birth) { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 })); }
function inspectIdentities(name, mode = 'inspect-all') {
  const values = [...identities.values()], file = join(evidence, `${name}-identities.tsv`); assert.ok(values.length);
  writeFileSync(file, values.map(value => `${value.pid}\t${value.processStartedAt}\n`).join(''));
  const args = ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, file];
  save(`${name}-command.json`, { command: [java, ...args], inputSha256: digest(readFileSync(file)), originalIdentities: values });
  const results = JSON.parse(execFileSync(java, args, { encoding: 'utf8', windowsHide: true, timeout: 60000, maxBuffer: 8 * 1024 * 1024 }));
  assert.equal(results.length, values.length);
  for (let index = 0; index < results.length; index++) { assert.equal(results[index].pid, values[index].pid); assert.equal(Date.parse(results[index].originalStartedAt), Date.parse(values[index].processStartedAt)); }
  save(`${name}.json`, results); return results;
}
async function facts(taskId) {
  const [task, requests, executions, sessions, runs] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/model-requests?limit=100`), api(`/tasks/${taskId}/task-executions?limit=100`), api(`/tasks/${taskId}/process-sessions`), api(`/tasks/${taskId}/agent-runs?limit=100`)]);
  for (const session of sessions) { remember(session); session.recordedProcesses.forEach(remember); }
  assert.equal(requests.hasMore, false); assert.equal(runs.hasMore, false); return { task, requests: requests.requests, executions: executions.executions, sessions, runs: runs.runs };
}
async function settled(taskId) {
  return until('Original completed root and acceptance state', async () => {
    const [task, executions] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/task-executions?limit=100`)]);
    if (['FAILED', 'UNKNOWN', 'CANCELLED', 'WAITING_INPUT'].includes(task.state)) throw new Error(`Unexpected original task state ${task.state}: ${redact(task.error || '')}`);
    return task.state === 'AWAITING_ACCEPTANCE' && executions.executions[0]?.endedAt ? facts(taskId) : null;
  });
}
const query = (path = '', view) => new URLSearchParams({ path, expectedVersion: view?.version || '', expectedBase: view?.baseline || '' });
const view = (taskId, path = '', expected, status = 200) => api(`/tasks/${taskId}/diff-view?${query(path, expected)}`, undefined, status);
function assertText(value, expectedBytes) {
  assert.equal(value.preview.type, 'TEXT'); assert.equal(value.preview.version, value.version); assert.equal(value.preview.path, value.selectedPath);
  assert.equal(value.preview.sha256, digest(expectedBytes)); assert.equal(value.preview.complete, true);
  assert.equal(value.preview.rows, value.preview.hunks.reduce((sum, hunk) => sum + hunk.lines.length, 0));
}
const model = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const chunk of request) chunks.push(chunk); const bytes = Buffer.concat(chunks), body = JSON.parse(bytes), { prompt, results, names } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(request.headers.authorization, `Bearer ${credential}`);
    const marker = [...tasks.keys()].find(value => prompt.includes(value)); assert.ok(marker);
    const current = await facts(tasks.get(marker)), original = current.requests.find(value => value.state === 'DISPATCHING' && value.inputDigest === digest(bytes));
    assert.ok(original, 'Original request and budget must commit before HTTP dispatch'); assert.equal(original.executorPid, worker.pid); assert.equal(original.processStartedAt, worker.processStartedAt);
    calls.push({ number: calls.length + 1, marker, requestId: original.id, agentRunId: original.agentRunId, taskExecutionId: original.taskExecutionId, executorPid: original.executorPid, processStartedAt: original.processStartedAt });
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1], tool = (name, suffix, args) => {
      assert.ok(names.includes(name)); return { role: 'assistant', content: null, tool_calls: [{ id: `diff-${marker}-${suffix}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] };
    };
    let message = { role: 'assistant', content: 'Original stage completed.' };
    if (stage === 'IMPLEMENT') {
      if (results.length === 0) message = tool('read_file', 'read', { path: textPath });
      else if (results.length === 1) { const read = JSON.parse(results[0].content); assert.equal(read.content, originalText); assert.match(read.readVersion, /^file-read-/); message = tool('write_file', 'write', { path: textPath, content: changedText, expectedVersion: read.readVersion }); }
      else { assert.equal(results.length, 2, 'No tool replay'); assert.equal(JSON.parse(results[1].content).error, undefined); assert.equal(readFileSync(join(current.task.workspace, textPath), 'utf8'), changedText); }
    } else if (stage === 'REVIEW') {
      const review = current.runs.find(run => run.id === original.agentRunId); assert.ok(review); assert.notEqual(dirname(review.workspace), dirname(current.task.workspace));
      assert.equal(readFileSync(join(review.workspace, textPath), 'utf8'), changedText); message = { role: 'assistant', content: '{"approved":true,"issues":[]}' };
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: `diff-response-${calls.length}`, model: body.model, choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { modelErrors.push(redact(error.message)); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end('{"error":{"message":"Local diff protocol fixture failed"}}'); }
});
const web = createServer(async (request, response) => {
  try {
    const path = new URL(request.url, 'http://localhost').pathname;
    if (path.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, { method: request.method, headers: { Authorization: request.headers.authorization || '', 'Content-Type': 'application/json' },
        ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}), signal: AbortSignal.timeout(30000) });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' }); response.end(Buffer.from(await upstream.arrayBuffer())); return;
    }
    if (path === '/workbench-config.json') { response.writeHead(200, { 'Content-Type': 'application/json' }); response.end('{"apiBaseUrl":"/service"}'); return; }
    if (!/^\/(?:index\.html|theme-init\.js|assets\/[a-zA-Z0-9_.-]+)?$/.test(path)) { response.writeHead(404); response.end(); return; }
    const file = path === '/' ? '/index.html' : path, bytes = readFileSync(join(root, 'web/dist', file)); served.set(file, digest(bytes));
    response.writeHead(200, { 'Content-Type': { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css' }[extname(file)] || 'application/octet-stream' }); response.end(bytes);
  } catch (error) { response.writeHead(502); response.end(); }
});

try {
  execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', classes, join(root, 'tools/ProcessRecoveryProbe.java')], { windowsHide: true, timeout: 30000 });
  writeFileSync(join(evidence, 'ProcessRecoveryProbe.java'), readFileSync(join(root, 'tools/ProcessRecoveryProbe.java'))); writeFileSync(join(evidence, 'diff-view-smoke-source.mjs'), scriptSource);
  save('fixture.json', { version, productionEntry: 'JarLauncher', jarSha256, scriptSha256, webFiles, schedulerParallelism: 2, schedulerMaxPoolSize: 2,
    fixtureClassesCompiledOnce: true, fixtureClasses: readdirSync(classes, { recursive: true }).filter(file => file.endsWith('.class')).map(file => ({ file: file.replaceAll('\\', '/'), sha256: digest(readFileSync(join(classes, file))) })),
    productionClasses: ['dev/agentworkbench/WorkbenchController.class', 'dev/agentworkbench/WorkerProcessMain.class', 'dev/agentworkbench/runtime/WorkspaceRuntime.class', 'dev/agentworkbench/runtime/GitDiffView.class']
      .map(file => ({ file, sha256: digest(zipEntry(readFileSync(jar), `BOOT-INF/classes/${file}`)) })), previewProcessCoverage: 'CORE_PREVIEW_NOT_PERSISTED_IN_WORKER_LEDGER; TASK_EXECUTION_OBSERVED_ONLY' });
  mkdirSync(join(source, 'src'), { recursive: true }); mkdirSync(join(source, 'assets'));
  writeFileSync(join(source, '.gitattributes'), '* -text\n'); writeFileSync(join(source, '.env'), 'original excluded fixture\n'); writeFileSync(join(source, textPath), originalText);
  writeFileSync(join(source, 'src/removed.txt'), 'removed original file\n'); writeFileSync(join(source, 'assets/image.bin'), Buffer.from([0, 1, 2, 3]));
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict';import{readFileSync}from'node:fs';assert.equal(process.env.WORKBENCH_TOKEN,undefined);assert.equal(readFileSync('src/中文 view.tsx','utf8').split('\\n').length,25);console.log('DIFF_NODE_VERIFIED '+JSON.stringify({pid:process.pid,parentPid:process.ppid}));\n");
  git(source, 'init', '--initial-branch=main'); git(source, 'config', 'user.name', 'Diff fixture'); git(source, 'config', 'user.email', 'diff@example.invalid'); git(source, 'add', '.'); git(source, 'commit', '-m', 'original diff fixture');
  const sourceBefore = treeFacts(source); save('original-source.json', sourceBefore);
  await new Promise(done => model.listen(0, '127.0.0.1', done)); phase = 'ordinary final Jar startup';
  core = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${nioDirectory}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', '-jar', jar],
    { cwd: directory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  for (const stream of [core.stdout, core.stderr]) stream.on('data', bytes => logs += bytes.toString()); core.once('error', error => { processError = error; });
  coreIdentity = probe('identity', core.pid, '-'); remember(coreIdentity);
  await until('Original Jar startup', () => { if (processError) throw processError; if (core.exitCode !== null || core.signalCode !== null) throw new Error('Original core exited during startup'); const port = logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.diffViewProtocolVersion, 1); assert.equal(health.apiVersion, 1); assert.equal(health.schemaVersion, 8); assert.equal(health.fileToolProtocolVersion, 2); save('health.json', health);
  worker = (await api('/workers')).find(value => value.state === 'RUNNING'); assert.ok(worker); remember(worker); assert.notEqual(worker.pid, core.pid); assert.equal(worker.deployment, 'LOCAL_PROCESS');
  assert.equal(probe('inspect', worker.pid, worker.processStartedAt).matchesOriginal, true);
  pass('Ordinary final Jar and original independent Worker exposed diff protocol 1 with unchanged API/schema/file-tool versions');
  const project = await api('/projects', { path: source }), route = await api('/routes', { name: 'Actual diff Jar fixture', engine: 'API', model: 'diff-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, apiKey: credential });
  const completed = [];
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `${strategy} original Worker execution`; const marker = `DIFF_VIEW_${strategy}`;
    const task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `真实差异 ${strategy}`, goal: marker, mode: 'LEAD', workspaceSelection: { strategy, ref: 'HEAD' },
      contract: { scope: ['**'], acceptance: ['Keep exact file bytes and fixed Git baseline'], commands: ['node verify.mjs'] }, budget: { maxCalls: 16, reserveCalls: 4 } });
    tasks.set(marker, task.id); const empty = await view(task.id); assert.equal(empty.workspace, ''); assert.deepEqual(empty.files, []); assert.equal(empty.preview, null); assert.equal((await api(`/tasks/${task.id}`)).diffViewProtocolVersion, 1);
    const unauthorized = await fetch(`${baseUrl}/api/tasks/${task.id}/diff-view`, { signal: AbortSignal.timeout(15000) }); assert.equal(unauthorized.status, 401);
    await api(`/tasks/${task.id}/approve`, {}); const current = await settled(task.id); completed.push(current); save(`${strategy}-original-completed.json`, current);
    assert.equal(current.task.workspaceOwnership.strategy, strategy); assert.equal(current.executions[0].state, 'SUCCEEDED'); assert.ok(current.requests.every(request => request.executorPid === worker.pid && request.processStartedAt === worker.processStartedAt));
    assert.equal(readFileSync(join(current.task.workspace, textPath), 'utf8'), changedText); assert.deepEqual(treeFacts(source), sourceBefore);
    pass(`${strategy} actual Worker read and version-bound write completed with independent review and original request identities`);
    const before = treeFacts(current.task.workspace), first = await view(task.id, textPath); assertText(first, changedText); assert.equal(first.baseline, sourceBefore.head);
    assert.equal(first.files.length, 1); assert.equal(first.preview.hunks.length, 2); assert.equal(first.preview.additions, 2); assert.equal(first.preview.deletions, 2);
    assert.deepEqual(first.preview.hunks.flatMap(hunk => hunk.lines.filter(line => line.kind === 'REMOVE').map(line => line.oldLine)), [2, 20]);
    assert.deepEqual(first.preview.hunks.flatMap(hunk => hunk.lines.filter(line => line.kind === 'ADD').map(line => line.newLine)), [2, 20]);
    const legacy = await api(`/tasks/${task.id}/diff`); assert.equal(legacy.version, first.version); assert.equal(legacy.baseline, first.baseline); assert.equal(legacy.truncated, false); assert.ok(legacy.diff.includes('+实现内容行 20'));
    const exported = await fetch(`${baseUrl}/api/tasks/${task.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) }); assert.equal(exported.status, 200);
    const archive = Buffer.from(await exported.arrayBuffer()); writeFileSync(join(evidence, `${strategy}-original-delivery.zip`), archive); assert.ok(zipEntry(archive, 'changes.patch').toString('utf8').includes('+实现内容行 20'));
    JSON.parse(zipEntry(archive, 'process-observations.json')).forEach(remember);
    assert.deepEqual(treeFacts(current.task.workspace), before); assert.deepEqual(treeFacts(source), sourceBefore); save(`${strategy}-initial-view.json`, first); save(`${strategy}-legacy-view.json`, legacy);
    pass(`${strategy} actual old/new coordinates, verified legacy text and original delivery patch kept workspace/source bytes and Git metadata`);
    phase = `${strategy} actual preview states`;
    mkdirSync(join(current.task.workspace, 'notes')); writeFileSync(join(current.task.workspace, literalPath), literalText); writeFileSync(join(current.task.workspace, 'notes/empty.txt'), '');
    writeFileSync(join(current.task.workspace, 'assets/image.bin'), Buffer.from([0, 1, 9, 3])); unlinkSync(join(current.task.workspace, 'src/removed.txt'));
    writeFileSync(join(current.task.workspace, '.env'), 'EXCLUDED_TEXT_MUST_NOT_BE_RENDERED\n'); writeFileSync(join(current.task.workspace, 'large.txt'), 'x'.repeat(256001)); writeFileSync(join(current.task.workspace, 'row-limit.txt'), 'changed line\n'.repeat(5001));
    const stale = await view(task.id, textPath, first, 409); assert.match(stale.error, /刷新/);
    const captured = await view(task.id, textPath); assert.notEqual(captured.version, first.version); assert.equal(captured.files.length, 8);
    const expectedStates = { 'assets/image.bin': 'BINARY', '.env': 'EXCLUDED', 'large.txt': 'LIMIT', 'row-limit.txt': 'LIMIT', 'notes/empty.txt': 'EMPTY', 'src/removed.txt': 'TEXT', [literalPath]: 'TEXT' }, stateViews = [];
    const frozenWorkspace = treeFacts(current.task.workspace), callsBefore = calls.length;
    for (const [path, type] of Object.entries(expectedStates)) {
      const selected = await view(task.id, path, captured); assert.equal(selected.selectedPath, path); assert.equal(selected.preview.type, type); assert.equal(selected.version, captured.version);
      if (type === 'LIMIT') { assert.equal(selected.preview.complete, false); assert.deepEqual(selected.preview.hunks, []); assert.equal(selected.preview.limit, path === 'large.txt' ? 'FILE_BYTES' : 'ROWS'); }
      if (path === '.env') { assert.equal(JSON.stringify(selected).includes('EXCLUDED_TEXT_MUST_NOT_BE_RENDERED'), false); assert.equal(selected.preview.beforeBytes, null); }
      if (path === literalPath) { assertText(selected, literalText); assert.equal(selected.preview.hunks[0].lines[0].content, literalText.split('\n')[0]); assert.equal(selected.preview.hunks[0].lines.at(-1).noNewline, true); }
      stateViews.push(selected);
    }
    const boundedLegacy = await api(`/tasks/${task.id}/diff`); assert.equal(boundedLegacy.version, captured.version); assert.equal(boundedLegacy.truncated, true); assert.equal(boundedLegacy.changedFiles, 8);
    assert.equal(boundedLegacy.diff.includes('EXCLUDED_TEXT_MUST_NOT_BE_RENDERED'), false); await view(task.id, '../escape', captured, 400); await view(task.id, 'removed-from-list.txt', captured, 404);
    assert.deepEqual(treeFacts(current.task.workspace), frozenWorkspace); assert.deepEqual(treeFacts(source), sourceBefore); assert.equal(calls.length, callsBefore); assert.equal((await api(`/tasks/${task.id}`)).budget.usedCalls, current.task.budget.usedCalls);
    save(`${strategy}-state-views.json`, stateViews); save(`${strategy}-captured-view.json`, captured); save(`${strategy}-bounded-legacy.json`, boundedLegacy); save(`${strategy}-preview-read-only.json`, { before: frozenWorkspace, after: treeFacts(current.task.workspace), sourceBefore, sourceAfter: treeFacts(source), callsBefore, callsAfter: calls.length });
    pass(`${strategy} real binary/excluded/empty/deleted/literal/no-newline/file-size/row limits and stale version rejected partial or secret text without writes or model charges`);
  }
  phase = 'production browser'; await new Promise(done => web.listen(0, '127.0.0.1', done));
  browser = await chromium.launch({ headless: true, ...(process.env.PLAYWRIGHT_CHANNEL ? { channel: process.env.PLAYWRIGHT_CHANNEL } : windows ? { channel: 'msedge' } : {}) });
  page = await browser.newPage({ viewport: { width: 1440, height: 1000 } }); page.on('pageerror', error => pageErrors.push(redact(error.message)));
  await page.goto(`http://127.0.0.1:${web.address().port}`); await page.getByRole('button', { name: '连接设置', exact: true }).click(); await page.getByLabel('访问令牌', { exact: true }).fill(token);
  await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await page.getByRole('dialog').waitFor({ state: 'hidden' });
  const panel = page.getByRole('region', { name: '工作目录差异', exact: true });
  for (const current of completed) {
    const strategy = current.task.workspaceOwnership.strategy; phase = `${strategy} actual production rows and state rendering`;
    await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: new RegExp(`^真实差异 ${strategy}`) }).click(); await page.getByRole('button', { name: '代码差异', exact: true }).click();
    await panel.getByRole('treeitem', { name: `${textPath} · 修改`, exact: true }).click(); await expect(panel.locator('.diff-code-row.remove .diff-line-old')).toHaveText(['2', '20']); await expect(panel.locator('.diff-code-row.add .diff-line-new')).toHaveText(['2', '20']);
    for (const width of [1440, 430]) {
      await page.setViewportSize({ width, height: 1000 }); await panel.getByRole('treeitem', { name: `${literalPath} · 新增`, exact: true }).click(); await expect(panel.locator('.diff-line-content code').first()).toHaveText(literalText.split('\n')[0]);
      await expect(panel.getByText('末行无换行符 · 新文件', { exact: true })).toBeVisible(); assert.equal(await page.evaluate(() => window.diffInjected), undefined); assert.equal(await panel.locator('img, script').count(), 0);
      assert.equal(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), true); await page.screenshot({ path: join(evidence, `${strategy}-production-diff-${width}.png`), fullPage: true, animations: 'disabled' });
    }
    await panel.getByRole('treeitem', { name: 'assets/image.bin · 修改', exact: true }).click(); await expect(panel.getByRole('heading', { name: '二进制或非 UTF-8 文件' })).toBeVisible(); await expect(panel.locator('.diff-code-row')).toHaveCount(0);
    await panel.getByRole('treeitem', { name: '.env · 修改', exact: true }).click(); await expect(panel.getByRole('heading', { name: '该路径不提供文本预览' })).toBeVisible(); await expect(panel.getByText('EXCLUDED_TEXT_MUST_NOT_BE_RENDERED')).toHaveCount(0);
    await panel.getByRole('treeitem', { name: 'large.txt · 新增', exact: true }).click(); await expect(panel.getByRole('heading', { name: '差异超出预览上限' })).toBeVisible(); await expect(panel.locator('.diff-code-row')).toHaveCount(0);
    pass(`${strategy} production Web rendered real Jar rows and explicit states on desktop/narrow screens with literal text and bounded horizontal scrolling`);
    phase = `${strategy} production stale selection and refresh`; await panel.getByRole('treeitem', { name: `${textPath} · 修改`, exact: true }).click(); await expect(panel.locator('.diff-code-row.add')).toHaveCount(2);
    writeFileSync(join(current.task.workspace, textPath), changedText + '手动改写后新行\n'); await panel.getByRole('treeitem', { name: 'assets/image.bin · 修改', exact: true }).click();
    await expect(panel.getByRole('alert')).toContainText('刷新'); await expect(panel.locator('.diff-code-row')).toHaveCount(0); await panel.getByRole('button', { name: '刷新代码差异', exact: true }).click();
    await expect(panel.getByRole('heading', { name: '二进制或非 UTF-8 文件' })).toBeVisible(); await expect(panel.getByRole('treeitem', { name: 'assets/image.bin · 修改', exact: true })).toHaveAttribute('aria-selected', 'true');
    await panel.getByRole('treeitem', { name: `${textPath} · 修改`, exact: true }).click(); await expect(panel.locator('.diff-line-content code').filter({ hasText: '手动改写后新行' })).toBeVisible();
    await page.screenshot({ path: join(evidence, `${strategy}-production-refreshed.png`), fullPage: true, animations: 'disabled' });
    assert.equal((await api(`/tasks/${current.task.id}`)).budget.usedCalls, current.task.budget.usedCalls); assert.deepEqual(treeFacts(source), sourceBefore);
    pass(`${strategy} actual 409 cleared old rows and explicit refresh kept selected file before rendering the new content version`);
    const beforeHead = await view(current.task.id, textPath), workspaceBeforeHead = treeFacts(current.task.workspace); git(current.task.workspace, '-c', 'user.name=Diff fixture', '-c', 'user.email=diff@example.invalid', 'commit', '--allow-empty', '-m', 'original preview HEAD movement');
    const afterHead = await view(current.task.id, textPath); assert.equal(beforeHead.version, afterHead.version); assert.notEqual(beforeHead.baseline, afterHead.baseline); await view(current.task.id, textPath, beforeHead, 409);
    save(`${strategy}-fixed-head-conflict.json`, { before: beforeHead, after: afterHead, workspaceBefore: workspaceBeforeHead, workspaceAfter: treeFacts(current.task.workspace) }); assert.deepEqual(treeFacts(source), sourceBefore);
    pass(`${strategy} fixed Git baseline rejected HEAD movement even when the content fingerprint stayed identical`);
  }
  assert.deepEqual(pageErrors, []); assert.deepEqual(modelErrors, []); assert.ok(served.has('/theme-init.js')); assert.equal(served.get('/theme-init.js'), digest(readFileSync(join(root, 'web/dist/theme-init.js'))));
  save('production-served-files.json', [...served].map(([file, sha256]) => ({ file, sha256 }))); await browser.close(); browser = undefined;
  phase = 'original identity stop verification'; const live = inspectIdentities('before-shutdown');
  for (const original of [coreIdentity, worker]) { const actual = live.find(item => item.pid === original.pid && Date.parse(item.originalStartedAt) === Date.parse(original.processStartedAt)); assert.ok(actual?.alive && actual.matchesOriginal); }
  await api('/shutdown', {}); await until('Original core shutdown', () => core.exitCode !== null || core.signalCode !== null);
  await until('Original Worker stopped', () => probe('inspect', worker.pid, worker.processStartedAt).originalStopped, 15000);
  const stopped = inspectIdentities('after-shutdown'); assert.ok(stopped.every(value => value.originalStopped));
  pass('Original core/Worker and every recorded execution PID/birth were independently confirmed stopped after normal shutdown');
  assert.equal(digest(readFileSync(jar)), jarSha256); for (const file of webFiles) assert.equal(digest(readFileSync(join(root, file.file))), file.sha256);
  report = { platform: process.platform, version, status: 'passed', checks, completedAt: new Date().toISOString(), directory, jarSha256, scriptSha256, webFiles, modelCalls: calls.length, originalProcessIdentities: identities.size,
    browserVersion: modules('@playwright/test/package.json').version, evidenceScope: 'ACTUAL_ORDINARY_FINAL_JAR_ORIGINAL_WORKER_REAL_GIT_CLONE_WORKTREE_AND_PRODUCTION_WEB', previewProcessCoverage: 'CORE_PREVIEW_NOT_PERSISTED_IN_WORKER_LEDGER; TASK_EXECUTION_OBSERVED_ONLY', modelErrors, pageErrors };
} catch (error) {
  report = { platform: process.platform, version, status: 'failed', phase, error: redact(error.message), checks, completedAt: new Date().toISOString(), directory, jarSha256, scriptSha256, webFiles, modelErrors, pageErrors };
  process.exitCode = 1; console.error(`FAIL ${phase}: ${redact(error.message)}; original evidence ${directory}`);
} finally {
  try { await browser?.close(); } catch (error) { save('browser-cleanup-error.json', { error: redact(error.message) }); }
  if (identities.size && existsSync(join(evidence, 'fixture.json'))) try { const stopped = inspectIdentities('failure-or-final-cleanup', 'stop-all'); assert.ok(stopped.every(value => value.originalStopped)); }
  catch (error) { report = { ...report, status: 'failed', cleanupError: redact(error.message) }; process.exitCode = 1; }
  writeFileSync(join(evidence, 'core.log'), redact(logs)); save('model-calls.json', calls); save('result.json', report);
  await new Promise(done => model.close(done)); await new Promise(done => web.close(done));
  mkdirSync(join(root, 'docs/qa'), { recursive: true }); writeFileSync(join(root, `docs/qa/diff-view-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, checks: checks.length, directory }));
}
