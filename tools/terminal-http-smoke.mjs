import assert from 'node:assert/strict';
import { execFile, execFileSync, spawn } from 'node:child_process';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { join, resolve } from 'node:path';
import { DatabaseSync } from 'node:sqlite';
import { zipEntry } from './team-smoke-fixture.mjs';
import { freezeTerminalArtifacts, inspectOriginalTerminalBundle, terminalFixtureRoot, writeTerminalShellFixture } from './terminal-fixture.mjs';

const root = terminalFixtureRoot; process.chdir(root); assert.equal(process.argv.slice(2).length, 0);
mkdirSync('.runtime', { recursive: true }); const directory = mkdtempSync(resolve('.runtime', 'terminal-http-'));
copyFileSync('tools/terminal-http-smoke.mjs', join(directory, 'runner-source.mjs'));
copyFileSync('tools/terminal-fixture.mjs', join(directory, 'artifact-fixture-source.mjs'));
const hash = bytes => createHash('sha256').update(bytes).digest('hex'), save = (name, data) => writeFileSync(join(directory, name), JSON.stringify(data, null, 2) + '\n');
const inputs = freezeTerminalArtifacts(directory), corePackage = inputs.core;
const jar = corePackage.jar, packageDirectory = corePackage.originalJar, windows = process.platform === 'win32';
const { java, probeClasses } = inputs;
const data = resolve(directory, 'data'), source = resolve(directory, '中文 source workspace'); mkdirSync(source);
const coreToken = randomBytes(32).toString('hex'), cores = [], identities = new Map(), requests = [], modelCalls = [], checks = [], tasks = [], archives = [];
let baseUrl, currentCore, phase = 'preparation', error, finalStatus = 'failed';
const scrub = value => Array.isArray(value) ? value.map(scrub) : value && typeof value === 'object' ? Object.fromEntries(Object.entries(value).filter(([key]) => !['controlSecret', 'controllerDigest', 'token', 'apiKey', 'dataBase64'].includes(key)).map(([key, item]) => [key, scrub(item)])) : value;
const pause = ms => new Promise(done => setTimeout(done, ms));
const pass = name => { checks.push({ name, observedAt: new Date().toISOString() }); console.log(JSON.stringify({ passed: name, phase })); };
async function until(name, action, timeout = 45_000) { const end = Date.now() + timeout; while (Date.now() < end) { const value = await action(); if (value) return value; await pause(80); } throw new Error(`${name} exceeded its original ${timeout}ms boundary`); }
function probe(kind, identity) { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', kind, String(identity.pid), identity.processStartedAt || '-'], { windowsHide: true, timeout: 30_000, encoding: 'utf8' }).trim()); }
function stopOriginalCore(identity) {
  return new Promise((done, reject) => execFile(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', 'stop', String(identity.pid), identity.processStartedAt],
    { windowsHide: true, timeout: 30_000, encoding: 'utf8' }, (failure, stdout) => {
      if (failure) { reject(failure); return; }
      try { done(JSON.parse(stdout.trim())); } catch (failure) { reject(failure); }
    }));
}
function identity(pid) { const value = probe('identity', { pid }); identities.set(`${value.pid}:${value.processStartedAt}`, value); return value; }
function remember(value) { if (value?.pid > 0 && value.processStartedAt) identities.set(`${value.pid}:${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt }); }
function git(...args) { return execFileSync('git', ['-C', source, ...args], { windowsHide: true, timeout: 20_000, encoding: 'utf8' }).trim(); }
const shellFixture = writeTerminalShellFixture(source);
git('init', '--initial-branch=main'); git('config', 'user.name', 'Terminal HTTP Fixture'); git('config', 'user.email', 'terminal-http@example.invalid'); git('add', '.'); git('commit', '-m', 'original terminal HTTP fixture');
const originalSource = { head: git('rev-parse', 'HEAD'), index: readFileSync(join(source, '.git/index')), config: readFileSync(join(source, '.git/config')) };
function sourceUnchanged() { assert.equal(git('rev-parse', 'HEAD'), originalSource.head); assert.equal(git('branch', '--show-current'), 'main'); assert.deepEqual(readFileSync(join(source, '.git/index')), originalSource.index); assert.deepEqual(readFileSync(join(source, '.git/config')), originalSource.config); assert.equal(readFileSync(join(source, 'app.txt'), 'utf8'), 'original source\n'); }
const model = createServer(async (request, response) => {
  try {
    if (request.method === 'GET') { response.writeHead(200, { 'Content-Type': 'application/json', Connection: 'close' }); response.end(JSON.stringify({ data: [{ id: 'terminal-http-fixture' }] })); return; }
    let raw = ''; for await (const bytes of request) raw += bytes; const body = JSON.parse(raw);
    assert.equal(body.model, 'terminal-http-fixture'); const prompt = (body.messages || []).filter(message => ['system', 'user'].includes(message.role)).map(message => message.content).join('\n');
    const kind = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1]; modelCalls.push({ number: modelCalls.length + 1, kind, at: new Date().toISOString() });
    assert.ok(modelCalls.length <= 20); const content = kind === 'REVIEW' ? JSON.stringify({ approved: true, issues: [], summary: 'Original local terminal fixture review.' }) : 'Original local fixture stage completed without file or tool effects.';
    response.writeHead(200, { 'Content-Type': 'application/json', Connection: 'close' }); response.end(JSON.stringify({ id: `terminal-http-response-${modelCalls.length}`, model: body.model, choices: [{ message: { role: 'assistant', content }, finish_reason: 'stop' }], usage: { prompt_tokens: 5, completion_tokens: 4 } }));
  } catch (failure) { response.writeHead(500, { 'Content-Type': 'application/json', Connection: 'close' }); response.end(JSON.stringify({ error: { message: failure.message } })); }
});
await new Promise(done => model.listen(0, '127.0.0.1', done));
async function api(path, body, expected = 200) {
  const method = body === undefined ? 'GET' : 'POST', began = Date.now(); let response, value;
  try {
    response = await fetch(`${baseUrl}/api${path}`, { method, headers: { Authorization: `Bearer ${coreToken}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json', Connection: 'close' }) }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(30_000) });
    const bytes = Buffer.from(await response.arrayBuffer()); value = response.headers.get('content-type')?.includes('application/zip') ? bytes : JSON.parse(bytes.toString('utf8'));
    assert.equal(response.status, expected, `${path}: ${JSON.stringify(value).slice(0, 300)}`); return value;
  } finally { requests.push({ path, method, request: scrub(body), expected, status: response?.status, durationMs: Date.now() - began, response: Buffer.isBuffer(value) ? { bytes: value.length, sha256: hash(value) } : scrub(value) }); }
}
async function startCore() {
  const child = spawn(java, ['-Dfile.encoding=UTF-8', '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', `-Djdk.net.unixdomain.tmpdir=${inputs.nioDirectory}`, '-jar', jar], {
    cwd: resolve(directory), windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_TOKEN: coreToken, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', LOGGING_LEVEL_ROOT: 'INFO' }
  });
  const instance = { child, number: cores.length + 1, logs: '' }; cores.push(instance); currentCore = instance;
  for (const stream of [child.stdout, child.stderr]) stream.on('data', bytes => { instance.logs += bytes.toString('utf8').replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]'); });
  await until('original independent core startup', () => { if (child.exitCode !== null || child.signalCode !== null) throw new Error('Original independent core exited during startup'); const port = instance.logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  instance.identity = identity(child.pid); const health = await api('/health'); assert.equal(health.version, corePackage.version); assert.equal(health.schemaVersion, 13); assert.equal(health.terminalProtocolVersion, 1); assert.equal(health.corePid, child.pid); instance.health = health;
  instance.workers = (await api('/workers')).filter(worker => worker.state === 'RUNNING'); instance.workers.forEach(remember); return instance;
}
async function settled(taskId) { return until('original root receipt settlement', async () => { const task = await api(`/tasks/${taskId}`), page = await api(`/tasks/${taskId}/task-executions?limit=1`); return ['PAUSED', 'AWAITING_ACCEPTANCE', 'UNKNOWN'].includes(task.state) && page.executions[0]?.endedAt ? task : null; }); }
function body(control, kind, sequence) { return { requestId: randomUUID(), kind, sequence, controllerId: control.terminal.controllerId, generation: control.generation, controlSecret: control.controlSecret }; }
function input(control, command, sequence) { return { ...body(control, 'INPUT', sequence), dataBase64: Buffer.from(command, 'utf8').toString('base64') }; }
async function output(terminalId, epoch, limit) { const all = []; let cursor = 0; for (let index = 0; index < 100_000; index++) { const page = await api(`/terminals/${terminalId}/output?epoch=${epoch}&after=${cursor}&limit=${limit}`), bytes = Buffer.from(page.dataBase64, 'base64'); assert.equal(page.offset, cursor); assert.equal(page.length, bytes.length); assert.equal(hash(bytes), page.sha256); assert.equal(page.next, cursor + bytes.length); all.push(bytes); cursor = page.next; if (!page.hasMore) return Buffer.concat(all); } throw new Error('Original output exceeded the bounded page traversal'); }
async function stopObserved(original, name) { const observations = []; await until(name, () => { const value = probe('inspect', original); observations.push({ at: new Date().toISOString(), ...value }); return value.originalStopped; }); save(`${name}.json`, observations); }
try {
  phase = 'start-and-authenticate'; await startCore();
  const unauthenticated = await fetch(`${baseUrl}/api/tasks/missing/terminals`, { method: 'POST', headers: { 'Content-Type': 'application/json', Connection: 'close' }, body: '{}' }); assert.equal(unauthenticated.status, 401); pass('Actual production Jar required the original core Bearer header and exposed schema 13');
  const project = await api('/projects', { name: 'Original terminal HTTP source', path: source });
  const route = await api('/routes', { name: 'Only local terminal fixture model', engine: 'API', model: 'terminal-http-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, apiKey: 'public-local-fixture-key', enabled: true });
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `actual-${strategy.toLowerCase()}`;
    let task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `Original ${strategy} terminal HTTP`, goal: `Preserve the original ${strategy} terminal shell, raw bytes and no input replay.`, mode: 'LEAD',
      workspaceSelection: { strategy, ref: 'HEAD' }, contract: { scope: ['**'], acceptance: ['Original terminal HTTP fixture source retained'], commands: [shellFixture.baselineCommand] }, budget: { maxCalls: 12, reserveCalls: 4 } });
    tasks.push(task.id); await api(`/tasks/${task.id}/approve`, {}); task = await until('original prepared task acceptance boundary', async () => { const value = await api(`/tasks/${task.id}`); if (value.state === 'FAILED' || value.state === 'UNKNOWN') throw new Error(`Original fixture task became ${value.state}: ${value.error}`); return value.state === 'AWAITING_ACCEPTANCE' ? value : null; }); await settled(task.id);
    const before = task, callsBefore = modelCalls.length, startRequest = { requestId: randomUUID(), columns: 80, rows: 24 };
    const originalStart = await api(`/tasks/${task.id}/terminals`, startRequest), terminalId = originalStart.id;
    let terminal = await until('original native shell running', async () => { const value = await api(`/terminals/${terminalId}`); return value.state === 'RUNNING' ? value : null; }, 30_000);
    const originalNative = terminal.nativeProcess; save(`${strategy.toLowerCase()}-original-native-bundle.json`, inspectOriginalTerminalBundle(terminal, corePackage)); remember(originalNative); assert.equal((await api(`/tasks/${task.id}/terminals`, startRequest)).id, terminalId);
    await api(`/tasks/${task.id}/terminals`, { ...startRequest, columns: 81 }, 409); await api(`/tasks/${task.id}/resume`, {}, 409);
    const control = await api(`/terminals/${terminalId}/control`, { expectedGeneration: 0, controllerId: randomUUID() });
    const originalInput = input(control, shellFixture.unicodeCommand, 1), action = await api(`/terminals/${terminalId}/actions`, originalInput); assert.equal(action.state, 'ACKNOWLEDGED');
    assert.deepEqual(await api(`/terminals/${terminalId}/actions`, originalInput), action); await api(`/terminals/${terminalId}/actions`, { ...originalInput, dataBase64: Buffer.from(`echo changed${windows ? '\r' : '\n'}`).toString('base64') }, 409);
    await until('original actual native Unicode output', async () => (await output(terminalId, terminal.epoch, 65_536)).includes(Buffer.from('ORIGINAL_PTY_中文_')), 20_000);
    assert.equal(readFileSync(join(task.workspace, 'manual-count.txt'), 'utf8'), '1'); assert.deepEqual(JSON.parse(readFileSync(join(task.workspace, 'shell-environment.json'), 'utf8')), { WORKBENCH_TOKEN: false, WORKBENCH_CORE_TOKEN: false, OPENAI_API_KEY: false, ANTHROPIC_API_KEY: false });
    const resize = { ...body(control, 'RESIZE', 2), columns: 101, rows: 37 }; assert.equal((await api(`/terminals/${terminalId}/actions`, resize)).state, 'ACKNOWLEDGED');
    const sizeCommand = shellFixture.sizeCommand;
    assert.equal((await api(`/terminals/${terminalId}/actions`, input(control, sizeCommand, 3))).state, 'ACKNOWLEDGED');
    await until('original native measured terminal dimensions', () => existsSync(join(task.workspace, 'actual-native-size.txt')), 20_000); assert.equal(readFileSync(join(task.workspace, 'actual-native-size.txt'), 'utf8').trim(), '37 101');
    const observer = await api(`/terminals/${terminalId}`); assert.deepEqual(observer.nativeProcess, originalNative); assert.ok(!('controllerDigest' in observer));
    const replacement = await api(`/terminals/${terminalId}/control`, { expectedGeneration: control.generation, controllerId: randomUUID() });
    await api(`/terminals/${terminalId}/actions`, input(control, `echo stale controller${windows ? '\r' : '\n'}`, 4), 403);
    await api(`/terminals/${terminalId}/actions`, { ...body(replacement, 'STOP', 4), dataBase64: 'eA==' }, 400);
    const stopped = await api(`/terminals/${terminalId}/actions`, body(replacement, 'STOP', replacement.nextSequence)); assert.equal(stopped.state, 'ACKNOWLEDGED');
    task = await settled(task.id); terminal = await api(`/terminals/${terminalId}`); assert.equal(task.state, 'PAUSED'); assert.equal(terminal.state, 'CANCELLED'); assert.equal(terminal.outputIntegrity, 'COMPLETE');
    assert.deepEqual(task.budget, before.budget); assert.equal(modelCalls.length, callsBefore); assert.deepEqual(task.evidence, before.evidence); assert.equal(task.verification.status, 'NOT_RUN'); assert.equal(task.review.status, 'NOT_RUN');
    await stopObserved(originalNative, `original-${strategy.toLowerCase()}-native-stop`);
    const raw = await output(terminalId, terminal.epoch, 65_536); assert.deepEqual(await output(terminalId, terminal.epoch, 3), raw); writeFileSync(join(directory, `${strategy.toLowerCase()}-original-output.bin`), raw);
    const exported = await api(`/tasks/${task.id}/export`); writeFileSync(join(directory, `${strategy.toLowerCase()}-original-task.zip`), exported);
    const manifest = JSON.parse(zipEntry(exported, 'manifest.json')), sessions = JSON.parse(zipEntry(exported, 'terminals.json')), outputSources = JSON.parse(zipEntry(exported, 'terminal-output-sources.json'));
    assert.equal(manifest.terminalProtocolVersion, 1); assert.equal(manifest.terminalInputLedgerIncluded, false); assert.equal(manifest.terminalControllerDigestsIncluded, false); assert.equal(sessions.length, 1); assert.equal(sessions[0].state, 'CANCELLED');
    assert.ok(!JSON.stringify(sessions).includes('controllerDigest')); assert.equal(outputSources[0].ownerMatchesOriginalWorker, true); assert.equal(outputSources[0].inspection.complete, true);
    for (const file of outputSources[0].files) { const bytes = zipEntry(exported, `terminals/${terminalId}/output/${file.file}`); assert.equal(bytes.length, file.bytes); assert.equal(hash(bytes), file.sha256); }
    JSON.parse(zipEntry(exported, 'process-observations.json')).forEach(remember);
    assert.equal((await api(`/terminals/${terminalId}/actions`)).actions.length, 4); archives.push({ strategy, taskId: task.id, terminalId, nativeProcess: originalNative, outputBytes: raw.length, outputSha256: hash(raw), zipSha256: hash(exported), manifest, outputSources });
    sourceUnchanged(); pass(`Actual ${strategy} original shell retained Unicode, dimensions, observer identity, four durable actions and complete output ZIP without new model budget`);
  }
  phase = 'actual-core-pid-crash';
  const taskId = tasks[0], beforeCrashTask = await api(`/tasks/${taskId}`), startRequest = { requestId: randomUUID(), columns: 80, rows: 24 }, crashStart = await api(`/tasks/${taskId}/terminals`, startRequest);
  const terminal = await until('original crash terminal running', async () => { const value = await api(`/terminals/${crashStart.id}`); return value.state === 'RUNNING' ? value : null; }, 30_000); remember(terminal.nativeProcess);
  const control = await api(`/terminals/${terminal.id}/control`, { expectedGeneration: 0, controllerId: randomUUID() }), crashInput = input(control, shellFixture.crashCommand, 1);
  assert.equal((await api(`/terminals/${terminal.id}/actions`, crashInput)).state, 'ACKNOWLEDGED');
  await until('original actual foreground crash child', () => existsSync(join(beforeCrashTask.workspace, 'crash-child-pid.txt')), 20_000);
  const child = identity(Number(readFileSync(join(beforeCrashTask.workspace, 'crash-child-pid.txt'), 'utf8'))); assert.equal(readFileSync(join(beforeCrashTask.workspace, 'crash-count.txt'), 'utf8'), '1');
  await until('original crash child durable observation', () => {
    const sqlite = new DatabaseSync(join(data, 'workbench.sqlite'), { readOnly: true });
    try {
      const sessions = sqlite.prepare("SELECT body FROM entities WHERE kind='processSession' AND json_extract(body,'$.workerId')=?").all(terminal.source.workerId).map(row => JSON.parse(row.body));
      const observed = sqlite.prepare("SELECT body FROM entities WHERE kind='processObservation' AND json_extract(body,'$.pid')=? AND json_extract(body,'$.processStartedAt')=?").all(child.pid, child.processStartedAt).map(row => JSON.parse(row.body));
      const original = observed.find(record => sessions.some(session => session.id === record.sessionId)); if (original) { save('original-crash-child-durable-observation.json', { original, sessions }); return true; }
      return false;
    } finally { sqlite.close(); }
  }, 8_000);
  const oldCore = currentCore, originalWorker = (await api(`/workers`)).find(worker => worker.id === terminal.source.workerId); assert.ok(originalWorker); remember({ pid: originalWorker.pid || originalWorker.executorPid, processStartedAt: originalWorker.processStartedAt });
  const callsBeforeCrash = modelCalls.length; save('original-before-core-pid-crash.json', { core: oldCore.identity, worker: originalWorker, terminal, child, task: await api(`/tasks/${taskId}`), originalAction: await api(`/terminals/${terminal.id}/actions/${crashInput.requestId}`), modelCalls: callsBeforeCrash });
  // The Node parent must keep receiving child exit events while the independent probe waits for original PID removal.
  const forced = await stopOriginalCore(oldCore.identity); save('original-core-forcibly-stopped.json', forced); assert.equal(forced.stopRequested, true); assert.equal(forced.originalStopped, true);
  await stopObserved(terminal.nativeProcess, 'original-crash-native-stop'); await stopObserved(child, 'original-crash-child-stop');
  const workerIdentity = { pid: originalWorker.pid || originalWorker.executorPid, processStartedAt: originalWorker.processStartedAt }; await stopObserved(workerIdentity, 'original-crash-worker-stop');
  phase = 'recover-without-replay'; await startCore(); assert.notEqual(currentCore.identity.pid, oldCore.identity.pid);
  let recoveredTask = await api(`/tasks/${taskId}`), recoveredTerminal = await api(`/terminals/${terminal.id}`); assert.equal(recoveredTask.state, 'UNKNOWN'); assert.equal(recoveredTerminal.state, 'UNKNOWN'); assert.equal(recoveredTerminal.outputIntegrity, 'UNCONFIRMED');
  assert.deepEqual(recoveredTask.budget, beforeCrashTask.budget); assert.equal(modelCalls.length, callsBeforeCrash); assert.equal(readFileSync(join(beforeCrashTask.workspace, 'crash-count.txt'), 'utf8'), '1');
  assert.equal((await api(`/tasks/${taskId}/terminals`, startRequest)).id, terminal.id); await api(`/tasks/${taskId}/terminals`, { requestId: randomUUID(), columns: 80, rows: 24 }, 409); await api(`/tasks/${taskId}/resume`, {}, 409);
  const originalAction = await api(`/terminals/${terminal.id}/actions/${crashInput.requestId}`); assert.equal(originalAction.state, 'ACKNOWLEDGED'); assert.equal(originalAction.dispatchCount, 1); assert.equal(originalAction.nativeAuthorizationCount, 1);
  const unknownZip = await api(`/tasks/${taskId}/export`); writeFileSync(join(directory, 'original-core-crash-unknown-task.zip'), unknownZip);
  const unknownManifest = JSON.parse(zipEntry(unknownZip, 'manifest.json')), unknownSources = JSON.parse(zipEntry(unknownZip, 'terminal-output-sources.json')); assert.equal(unknownManifest.unknownTerminalEffects, true); assert.equal(unknownManifest.workspaceMatchesRecordedVersion, false);
  JSON.parse(zipEntry(unknownZip, 'process-observations.json')).forEach(remember);
  const unknownSource = unknownSources.find(value => value.terminalId === terminal.id); assert.ok(unknownSource); assert.equal(unknownSource.terminalState, 'UNKNOWN'); assert.equal(unknownSource.recordedOutputIntegrity, 'UNCONFIRMED'); assert.equal(unknownSource.uncommittedPrefix.availableInOriginalRecord, false);
  save('original-core-crash-recovered.json', { task: recoveredTask, terminal: recoveredTerminal, originalAction, unknownManifest, unknownSources, modelCalls: modelCalls.length });
  const sessions = await api('/process-recovery');
  for (const session of sessions.sessions) if (!session.reconciled) { assert.equal(session.observedProcessesStopped, true); await api(`/process-recovery/${session.id}/reconcile`, { processesStopped: true, coverageChecked: true, note: 'Original fixture Worker, shell, foreground child and source bytes independently checked.' }); }
  await api(`/tasks/${taskId}/reconcile`, { processesStopped: true, sideEffectsChecked: true, note: 'Actual original crash-count effect and output gap checked; old terminal and action identities retained.' });
  recoveredTask = await api(`/tasks/${taskId}`); recoveredTerminal = await api(`/terminals/${terminal.id}`); assert.equal(recoveredTerminal.state, 'UNKNOWN'); assert.ok(recoveredTerminal.reconciliation); await api(`/tasks/${taskId}/export`, undefined, 409);
  assert.equal((await api(`/tasks/${taskId}/terminals`)).terminals.length, 2); assert.equal(readFileSync(join(beforeCrashTask.workspace, 'crash-count.txt'), 'utf8'), '1'); assert.equal(modelCalls.length, callsBeforeCrash); sourceUnchanged();
  pass('Actual original core PID was forcibly terminated; original Worker, shell and foreground child stopped; UNKNOWN, raw output gaps and one input effect survived restart and explicit reconciliation without replay');
  finalStatus = 'passed';
} catch (failure) { error = { message: failure.message.replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]'), stack: failure.stack?.replaceAll(coreToken, '[LOCAL_CORE_TOKEN_REDACTED]'), phase }; save('original-failure.json', error); console.error(JSON.stringify({ phase, error: error.message, evidenceDirectory: directory })); }
finally {
  const cleanup = [];
  for (const core of cores) {
    try { if (core.child.exitCode === null && core.child.signalCode === null && core === currentCore) { await api('/shutdown', {}); await until('original core controlled exit', () => core.child.exitCode !== null || core.child.signalCode !== null, 45_000); } }
    catch (failure) { cleanup.push({ core: core.identity, cleanupError: failure.message }); if (core.identity) cleanup.push({ core: core.identity, stopped: probe('stop', core.identity) }); }
    writeFileSync(join(directory, `core-${core.number}.log`), core.logs); remember(core.identity);
  }
  let stops = [];
  save('fixture.json', { purpose: 'ORIGINAL_TERMINAL_HTTP_CORE_WORKER_NATIVE_IDENTITIES', root: resolve(directory) });
  const identityFile = resolve(directory, 'original-process-identities.tsv'); writeFileSync(identityFile, [...identities.values()].map(original => `${original.pid}\t${original.processStartedAt}`).join('\n') + '\n');
  try {
    stops = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', 'inspect-all', identityFile], { windowsHide: true, timeout: 60_000, encoding: 'utf8' }));
    if (stops.some(value => !value.originalStopped)) {
      cleanup.push({ beforeExplicitOriginalStop: stops.filter(value => !value.originalStopped) });
      stops = JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', probeClasses, 'ProcessRecoveryProbe', 'stop-all', identityFile], { windowsHide: true, timeout: 60_000, encoding: 'utf8' }));
    }
  } catch (failure) { cleanup.push({ independentStopAuditFailure: failure.message }); }
  save('original-process-stop-after-report.json', stops); model.closeAllConnections(); await new Promise(done => model.close(done));
  const stopped = stops.length === identities.size && stops.every(value => value.originalStopped), sourcesUnchanged = corePackage.sources.every(file => hash(readFileSync(file.file)) === file.sha256), jarUnchanged = hash(readFileSync(jar)) === corePackage.jarSha256;
  const files = [];
  function inventory(path, relative = '') { for (const entry of readdirSync(path, { withFileTypes: true })) { const name = relative ? `${relative}/${entry.name}` : entry.name, full = join(path, entry.name); if (entry.isDirectory()) inventory(full, name); else if (entry.isFile()) { const bytes = readFileSync(full); files.push({ file: name, bytes: bytes.length, sha256: hash(bytes) }); } else files.push({ file: name, unsupportedType: true }); } }
  save('requests.json', requests); inventory(directory); save('report.json', { recordedAt: new Date().toISOString(), status: finalStatus === 'passed' && stopped && sourcesUnchanged && jarUnchanged && cleanup.length === 0 ? 'passed' : 'failed', phase,
    productionJar: packageDirectory, jarSha256: corePackage.jarSha256, version: corePackage.version, schemaVersion: 13, terminalProtocolVersion: 1, sourcesUnchanged, jarUnchanged, checks, modelCalls, tasks, archives,
    cores: cores.map(core => ({ number: core.number, identity: core.identity, health: core.health, exitCode: core.child.exitCode, signalCode: core.child.signalCode })), originalIdentitiesStopped: stopped, originalIdentities: [...identities.values()], originalStops: stops, cleanup, error,
    fixtureFiles: files, originalFailuresAndUnknownsRetained: true, runtimeJava: java, runtimeJavaSha256: corePackage.javaSha256, modelTransport: 'LOCAL_CONNECTION_CLOSE_NO_APPLICATION_RETRY', scope: 'ACTUAL_PRODUCTION_MAIN_JAR_REAL_HTTP_WORKER_NATIVE_AND_FORCED_CORE_PID_CRASH; PRODUCT_UI_PACKAGED_JRE_AND_COMPLETE_HISTORICAL_PROCESS_TREE_ACCEPTANCE_SEPARATE' });
  const report = JSON.parse(readFileSync(join(directory, 'report.json'), 'utf8'));
  mkdirSync('docs/qa', { recursive: true }); const qaReport = `docs/qa/terminals-http-${process.platform}.json`;
  if (existsSync(qaReport)) writeFileSync(join(directory, 'previous-qa-report.json'), readFileSync(qaReport), { flag: 'wx' });
  writeFileSync(qaReport, JSON.stringify(report, null, 2) + '\n'); console.log(JSON.stringify({ directory, status: report.status, checks: checks.length, modelCalls: modelCalls.length, originalIdentitiesStopped: stopped, sourcesUnchanged, jarUnchanged })); process.exitCode = report.status === 'passed' ? 0 : 1;
}
