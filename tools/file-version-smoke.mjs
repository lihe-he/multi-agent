import assert from 'node:assert/strict';
import { execFileSync, spawn } from 'node:child_process';
import { createHash, randomBytes, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { chmodSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, realpathSync, statSync, utimesSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import { javaHome } from './java-runtime.mjs';
import { inspectModel, zipEntry } from './team-smoke-fixture.mjs';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), windows = process.platform === 'win32';
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version, javaRoot = javaHome(root);
const java = join(javaRoot, 'bin', windows ? 'java.exe' : 'java'), jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
assert.ok(existsSync(jar), 'Build the current final Jar before acceptance');
mkdirSync(join(root, '.runtime'), { recursive: true });
const directory = mkdtempSync(join(root, '.runtime/file-versions-')), evidence = join(directory, 'reports'), data = join(directory, 'data'); mkdirSync(evidence);
const source = join(directory, '中文 file version source'), classes = join(directory, 'fixture-classes'); mkdirSync(classes);
const nioDirectory = windows ? directory : mkdtempSync(join(realpathSync(tmpdir()), 'file-nio-'));
const token = randomBytes(32).toString('hex'), credential = 'fixture-key-"quoted\\path', fixedTime = new Date('2026-11-11T00:00:00Z');
const originalText = '用户原始文本\n', userText = '用户后改文本\n', finalText = userText + 'agent addition\n', originalBinary = Buffer.from([0, 1, 2, 3]), userBinary = Buffer.from([0, 1, 9, 3]);
const digest = bytes => createHash('sha256').update(bytes).digest('hex'), jarSha256 = digest(readFileSync(jar));
const scriptSource = readFileSync(fileURLToPath(import.meta.url)), scriptSha256 = digest(scriptSource), checks = [], calls = [], errors = [], tasks = new Map(), identities = new Map();
const sentTools = new Map(); let core, coreIdentity, worker, baseUrl, logs = '', processError, phase = 'fixture compilation', report;
const redact = text => text.replaceAll(token, '[REDACTED]').replaceAll(credential, '[REDACTED]');
function assertCredentialRedacted(value) {
  if (typeof value === 'string') assert.equal(value.includes(credential), false, 'A decoded file-history string leaked the original credential');
  else if (Array.isArray(value)) value.forEach(assertCredentialRedacted);
  else if (value && typeof value === 'object') for (const [key, item] of Object.entries(value)) { assertCredentialRedacted(key); assertCredentialRedacted(item); }
}
const save = (name, value) => writeFileSync(join(evidence, name), JSON.stringify(value, null, 2) + '\n');
const pass = name => { checks.push({ name, status: 'passed', at: new Date().toISOString() }); console.log(`PASS ${name}`); };
const wait = milliseconds => new Promise(done => setTimeout(done, milliseconds));
const ended = () => core && (core.exitCode !== null || core.signalCode !== null);
function remember(value) { if (value?.pid && value.processStartedAt) identities.set(`${value.pid}@${value.processStartedAt}`, { pid: value.pid, processStartedAt: value.processStartedAt }); }
async function until(name, action, timeout = 60000) {
  const deadline = Date.now() + timeout;
  while (Date.now() < deadline) { const value = await action(); if (value) return value; await wait(100); }
  throw new Error(`${name} did not complete within its original bounded deadline`);
}
async function api(path, body, expected = 200) {
  const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(15000) });
  const text = await response.text(); assert.equal(response.status, expected, `${path}: HTTP ${response.status}: ${redact(text.slice(0, 300))}`); return JSON.parse(text);
}
function git(...args) { return execFileSync('git', ['--no-optional-locks', '-c', 'core.hooksPath=/dev/null', '-c', 'core.autocrlf=false', '-C', source, ...args], { encoding: 'utf8', windowsHide: true, timeout: 20000 }); }
function sourceFacts() { return { head: git('rev-parse', 'HEAD').trim(), branch: git('branch', '--show-current').trim(), index: digest(readFileSync(join(source, '.git/index'))),
  config: digest(readFileSync(join(source, '.git/config'))), refs: git('for-each-ref', '--format=%(refname) %(objectname)'), status: git('status', '--porcelain=v1', '-uall'),
  text: digest(readFileSync(join(source, 'value.txt'))), binary: digest(readFileSync(join(source, 'value.bin'))) }; }
function processProbe(mode, pid, birth) { return JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 15000 })); }
function checkOriginalIdentities(name, mode = 'inspect-all') {
  const values = [...identities.values()], file = join(evidence, `${name}-identities.tsv`); assert.ok(values.length > 0);
  writeFileSync(file, values.map(value => `${value.pid}\t${value.processStartedAt}\n`).join(''));
  const command = [java, '-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, file];
  save(`${name}-command.json`, { command, inputSha256: digest(readFileSync(file)), originalIdentities: values });
  const results = JSON.parse(execFileSync(command[0], command.slice(1), { encoding: 'utf8', windowsHide: true, timeout: 90000, maxBuffer: 8 * 1024 * 1024 }));
  assert.equal(results.length, values.length);
  for (let index = 0; index < results.length; index++) { assert.equal(results[index].pid, values[index].pid); assert.equal(Date.parse(results[index].originalStartedAt), Date.parse(values[index].processStartedAt)); }
  save(`${name}.json`, results); return results;
}
async function facts(taskId) {
  const [task, requests, executions, leases, operations, sessions, runs] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/model-requests?limit=100`), api(`/tasks/${taskId}/task-executions?limit=100`),
    api(`/tasks/${taskId}/workspace-write-leases?limit=100`), api(`/tasks/${taskId}/file-tool-operations?limit=100`), api(`/tasks/${taskId}/process-sessions`), api(`/tasks/${taskId}/agent-runs?limit=100`)]);
  const observations = sessions.flatMap(session => session.recordedProcesses);
  for (const value of sessions) remember(value);
  for (const value of observations) remember(value);
  assert.equal(runs.hasMore, false);
  return { task, requests: requests.requests, executions: executions.executions, leases: leases.leases, operations, sessions, observations, observationsAreLivePreview: true, runs: runs.runs };
}
async function settled(taskId, state) {
  return until(`Original ${state} task and root receipt`, async () => {
    const [task, executions] = await Promise.all([api(`/tasks/${taskId}`), api(`/tasks/${taskId}/task-executions?limit=100`)]);
    if (['FAILED', 'UNKNOWN', 'CANCELLED'].includes(task.state) || state !== 'WAITING_INPUT' && task.state === 'WAITING_INPUT') throw new Error(`Unexpected original task state ${task.state}: ${redact(task.error || '')}`);
    const rootReceipt = executions.executions[0];
    if (task.state !== state || !rootReceipt?.endedAt) return null;
    const value = await facts(taskId);
    if (['FAILED', 'UNKNOWN', 'CANCELLED'].includes(value.task.state) || state !== 'WAITING_INPUT' && value.task.state === 'WAITING_INPUT') throw new Error(`Unexpected original task state ${value.task.state}: ${redact(value.task.error || '')}`);
    assert.equal(value.executions[0]?.id, rootReceipt.id, 'Complete history must belong to the same original settled root');
    assert.equal(value.executions[0]?.state, state === 'WAITING_INPUT' ? 'WAITING_INPUT' : 'SUCCEEDED');
    return value.task.state === state && value.executions[0]?.endedAt ? value : null;
  });
}
const model = createServer(async (request, response) => {
  try {
    const chunks = []; for await (const chunk of request) chunks.push(chunk); const bytes = Buffer.concat(chunks), body = JSON.parse(bytes), { prompt, results, names } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.equal(request.headers.authorization, `Bearer ${credential}`);
    const marker = [...tasks.keys()].find(value => prompt.includes(value)); assert.ok(marker); const current = await facts(tasks.get(marker));
    const original = current.requests.find(value => value.state === 'DISPATCHING' && value.inputDigest === digest(bytes)); assert.ok(original, 'Model budget and original source must commit before HTTP arrives');
    assert.equal(original.executorPid, worker.pid); assert.equal(original.processStartedAt, worker.processStartedAt); assert.equal(original.taskExecutionId, current.task.taskExecutionId);
    calls.push({ number: calls.length + 1, marker, taskId: current.task.id, requestId: original.id, agentRunId: original.agentRunId, stage: original.stage, executorPid: original.executorPid, processStartedAt: original.processStartedAt });
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1], unresolved = marker.includes('UNRESOLVED'), file = join(current.task.workspace, 'value.txt'), binary = join(current.task.workspace, 'value.bin');
    const toolResult = index => JSON.parse(results[index].content), tool = (name, suffix, args) => {
      assert.ok(names.includes(name)); const raw = JSON.stringify(args), toolCallId = `file-${marker}-${suffix}`;
      sentTools.set(toolCallId, { toolCallId, requestId: original.id, tool: name, argumentDigest: digest(raw), ordinal: 1 });
      return { role: 'assistant', content: null, tool_calls: [{ id: toolCallId, type: 'function', function: { name, arguments: raw } }] };
    };
    let message = { role: 'assistant', content: stage === 'REVIEW' ? '{"approved":true,"issues":[]}' : 'Original stage completed.' };
    if (stage === 'IMPLEMENT') {
      switch (results.length) {
        case 0:
          assert.equal(readFileSync(file, 'utf8'), originalText); utimesSync(file, fixedTime, fixedTime); if (!windows) chmodSync(file, 0o750);
          message = tool('read_file', 'read-original', { path: 'value.txt' }); break;
        case 1: {
          const read = toolResult(0), before = statSync(file, { bigint: true }); assert.equal(read.content, originalText); assert.match(read.readVersion, /^file-read-/);
          writeFileSync(file, userText); utimesSync(file, fixedTime, fixedTime); const after = statSync(file, { bigint: true }); assert.equal(before.size, after.size); assert.equal(before.mtimeNs, after.mtimeNs);
          save(`${marker}-same-size-and-mtime-user-edit.json`, { before: { size: String(before.size), mtimeNs: String(before.mtimeNs) }, after: { size: String(after.size), mtimeNs: String(after.mtimeNs) }, originalSha256: digest(originalText), userSha256: digest(userText), readVersion: read.readVersion });
          message = tool('write_file', 'stale-write', { path: 'value.txt', content: 'AI-should-not-write\n', expectedVersion: read.readVersion }); break;
        }
        case 2:
          assert.equal(toolResult(1).code, 'FILE_VERSION_CONFLICT'); assert.equal(readFileSync(file, 'utf8'), userText);
          message = unresolved ? { role: 'assistant', content: 'Incorrect final success claim must be rejected by the original runtime.' } : tool('read_file', 'fresh-read', { path: 'value.txt' }); break;
        case 3:
          assert.equal(toolResult(2).content, userText); assert.notEqual(toolResult(2).readVersion, toolResult(0).readVersion);
          message = tool('write_file', 'fresh-write', { path: 'value.txt', content: finalText, expectedVersion: toolResult(2).readVersion }); break;
        case 4:
          assert.equal(toolResult(3).error, undefined); assert.equal(readFileSync(file, 'utf8'), finalText); if (!windows) assert.equal(statSync(file).mode & 0o777, 0o750);
          utimesSync(binary, fixedTime, fixedTime); message = tool('inspect_file', 'inspect-original', { path: 'value.bin' }); break;
        case 5: {
          const read = toolResult(4); assert.equal(read.fileVersion.sha256, digest(originalBinary)); assert.equal(read.contentRead, false);
          const before = statSync(binary, { bigint: true }); writeFileSync(binary, userBinary); utimesSync(binary, fixedTime, fixedTime); const after = statSync(binary, { bigint: true });
          assert.equal(before.size, after.size); assert.equal(before.mtimeNs, after.mtimeNs);
          message = tool('delete_file', 'stale-delete', { path: 'value.bin', expectedVersion: read.readVersion }); break;
        }
        case 6:
          assert.equal(toolResult(5).code, 'FILE_VERSION_CONFLICT'); assert.deepEqual(readFileSync(binary), userBinary); message = tool('inspect_file', 'inspect-fresh', { path: 'value.bin' }); break;
        case 7:
          assert.equal(toolResult(6).fileVersion.sha256, digest(userBinary)); message = tool('delete_file', 'fresh-delete', { path: 'value.bin', expectedVersion: toolResult(6).readVersion }); break;
        case 8:
          assert.equal(toolResult(7).error, undefined); assert.equal(existsSync(binary), false); message = { role: 'assistant', content: 'Fresh read and inspection handled the original conflicts; independent root verification remains required.' }; break;
        default: throw new Error('Unexpected file-version tool replay');
      }
    } else if (stage === 'REVIEW') {
      const review = current.runs.find(run => run.id === original.agentRunId); assert.ok(review);
      assert.notEqual(dirname(review.workspace), dirname(current.task.workspace)); assert.equal(readFileSync(join(review.workspace, 'value.txt'), 'utf8'), finalText);
      if (results.length === 0) message = tool('read_file', 'review-read', { path: 'value.txt' });
      else {
        assert.equal(results.length, 1); assert.equal(toolResult(0).content, finalText);
        message = { role: 'assistant', content: '{"approved":true,"issues":[]}' };
      }
    }
    response.writeHead(200, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ id: `file-response-${calls.length}`, model: body.model, choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) { errors.push(redact(error.message)); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: 'Local file-version protocol fixture failed' } })); }
});

try {
  const probe = join(root, 'tools/ProcessRecoveryProbe.java'); execFileSync(join(javaRoot, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', classes, probe], { windowsHide: true, timeout: 30000 });
  writeFileSync(join(evidence, 'ProcessRecoveryProbe.java'), readFileSync(probe)); writeFileSync(join(evidence, 'file-version-smoke-source.mjs'), scriptSource);
  const jarBytes = readFileSync(jar), productionFiles = ['dev/agentworkbench/FileToolHistory.class', 'dev/agentworkbench/WorkbenchController.class', 'dev/agentworkbench/WorkerProcessMain.class', 'dev/agentworkbench/runtime/WorkspaceRuntime.class', 'dev/agentworkbench/runtime/FileEditSession.class'];
  save('fixture.json', { version, productionEntry: 'JarLauncher', jarSha256, scriptSha256, probeSourceSha256: digest(readFileSync(probe)), fixtureClassesCompiledOnce: true,
    fixtureClasses: readdirSync(classes, { recursive: true }).filter(file => file.endsWith('.class')).map(file => ({ file: file.replaceAll('\\', '/'), sha256: digest(readFileSync(join(classes, file))) })),
    productionClasses: productionFiles.map(file => ({ file, sha256: digest(zipEntry(jarBytes, `BOOT-INF/classes/${file}`)) })), schedulerParallelism: 2, schedulerMaxPoolSize: 2 });
  mkdirSync(source); writeFileSync(join(source, 'value.txt'), originalText); writeFileSync(join(source, 'value.bin'), originalBinary);
  writeFileSync(join(source, 'verify.mjs'), "import assert from 'node:assert/strict';import{readFileSync}from'node:fs';assert.equal(process.env.WORKBENCH_TOKEN,undefined);assert.ok(readFileSync('value.txt','utf8').endsWith('\\n'));console.log('FILE_VERSION_NODE_VERIFIED '+JSON.stringify({pid:process.pid,parentPid:process.ppid}));\n");
  git('init', '--initial-branch=main'); git('config', 'user.name', 'File version fixture'); git('config', 'user.email', 'fixture@example.invalid'); git('add', '.'); git('commit', '-m', 'original file version fixture'); const sourceBefore = sourceFacts(); save('original-source.json', sourceBefore);
  await new Promise(done => model.listen(0, '127.0.0.1', done)); phase = 'ordinary final Jar startup';
  core = spawn(java, ['-Dfile.encoding=UTF-8', `-Djdk.net.unixdomain.tmpdir=${nioDirectory}`, '-Djdk.virtualThreadScheduler.parallelism=2', '-Djdk.virtualThreadScheduler.maxPoolSize=2', '-jar', jar],
    { cwd: directory, windowsHide: true, stdio: ['ignore', 'pipe', 'pipe'], env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: data, LOGGING_LEVEL_ROOT: 'INFO' } });
  for (const stream of [core.stdout, core.stderr]) stream.on('data', bytes => { logs += bytes.toString(); }); core.once('error', error => { processError = error; });
  coreIdentity = processProbe('identity', core.pid, '-'); remember(coreIdentity);
  await until('Final ordinary Jar startup', () => { if (processError) throw processError; if (ended()) throw new Error('Original core exited during startup'); const port = logs.match(/Tomcat started on port (\d+)/)?.[1]; if (port) { baseUrl = `http://127.0.0.1:${port}`; return true; } });
  const health = await api('/health'); assert.equal(health.version, version); assert.equal(health.schemaVersion, 8); assert.equal(health.apiVersion, 1); assert.equal(health.fileToolProtocolVersion, 2);
  save('health.json', health);
  worker = (await api('/workers')).find(value => value.state === 'RUNNING'); assert.ok(worker); remember(worker); assert.notEqual(worker.pid, core.pid); assert.equal(worker.deployment, 'LOCAL_PROCESS');
  pass('Final Jar and original independent Worker exposed file tool protocol 2 with API 1/schema 8');
  const project = await api('/projects', { path: source }), route = await api('/routes', { name: 'Original file version fixture', engine: 'API', model: 'file-version-fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1`, apiKey: credential });
  for (const strategy of ['CLONE', 'WORKTREE']) {
    phase = `${strategy} actual read/write/delete conflict`; const marker = `FILE_VERSION_${strategy}`;
    const task = await api('/tasks', { projectId: project.id, routeId: route.id, title: `文件版本 ${strategy}`, goal: marker, mode: 'LEAD', workspaceSelection: { strategy, ref: 'HEAD' },
      contract: { scope: ['**'], acceptance: ['Preserve user changes and original file receipts'], commands: ['node verify.mjs'] }, budget: { maxCalls: 24, reserveCalls: 4 } });
    tasks.set(marker, task.id); await api(`/tasks/${task.id}/approve`, {}); const complete = await settled(task.id, 'AWAITING_ACCEPTANCE'); save(`${strategy}-completed.json`, complete);
    assert.equal(complete.requests.length, 12); assert.equal(complete.task.budget.usedCalls, 12); assert.ok(complete.requests.every(request => request.state === 'SUCCEEDED'));
    const records = complete.operations.operations; assert.equal(records.length, 9); assert.ok(records.every(record => record.sourceStatus === 'REQUEST_BOUND' && record.source.executorPid === worker.pid && record.source.processStartedAt === worker.processStartedAt));
    assert.equal(records.filter(record => record.state === 'REJECTED' && record.errorCode === 'FILE_VERSION_CONFLICT').length, 2);
    for (const record of records) { const sent = sentTools.get(record.toolCallId); assert.ok(sent); assert.equal(record.modelRequestId, sent.requestId); assert.equal(record.argumentDigest, sent.argumentDigest); assert.equal(record.toolOrdinal, sent.ordinal); }
    const find = suffix => records.find(record => record.toolCallId === `file-${marker}-${suffix}`), freshRead = find('fresh-read'), freshWrite = find('fresh-write'), freshInspection = find('inspect-fresh'), deletion = find('fresh-delete');
    assert.equal(freshWrite.fileReceipt.sourceReadOperationId, freshRead.id); assert.equal(freshWrite.expectedReadVersion, freshRead.fileReceipt.readVersion); assert.equal(freshWrite.fileReceipt.beforeVersion.sha256, digest(userText));
    assert.equal(deletion.fileReceipt.sourceReadOperationId, freshInspection.id); assert.equal(deletion.fileReceipt.beforeVersion.sha256, digest(userBinary)); assert.equal(deletion.fileReceipt.fileVersion.exists, false);
    const reviewRead = find('review-read'), reviewRun = complete.runs.find(run => run.id === reviewRead.source.agentRunId);
    assert.equal(reviewRead.source.stage, 'review'); assert.ok(reviewRun);
    assert.equal(realpathSync(reviewRead.journalWorkspace), realpathSync(reviewRun.workspace)); assert.notEqual(dirname(reviewRun.workspace), dirname(complete.task.workspace));
    assert.equal(reviewRead.fileReceipt.fileVersion.sha256, freshWrite.fileReceipt.fileVersion.sha256); assert.notEqual(reviewRead.fileReceipt.fileVersion.fileKey, freshWrite.fileReceipt.fileVersion.fileKey);
    if (strategy === 'WORKTREE') {
      const first = await api(`/tasks/${tasks.get('FILE_VERSION_CLONE')}/file-tool-operations?limit=100`);
      assert.equal(first.total, 130); assert.equal(first.operations.filter(record => record.sourceStatus === 'REQUEST_BOUND').length, 9);
      assert.ok(first.operations.every(record => !record.toolCallId || !record.toolCallId.includes(marker)));
      save('CLONE-history-after-other-review.json', first);
    }
    assert.equal(readFileSync(join(complete.task.workspace, 'value.txt'), 'utf8'), finalText); assert.equal(existsSync(join(complete.task.workspace, 'value.bin')), false);
    assert.ok(complete.task.evidence.some(value => value.output?.includes('FILE_VERSION_NODE_VERIFIED'))); assert.equal(complete.leases[0].state, 'RELEASED'); assert.equal(complete.leases[0].protectionReleased, true);
    assert.deepEqual(sourceFacts(), sourceBefore); pass(`${strategy}: same-size restored-mtime edits rejected both stale actions and fresh receipts recovered in the original Worker`);
    const page = await api(`/tasks/${task.id}/file-tool-operations?limit=1`), older = await api(`/tasks/${task.id}/file-tool-operations?limit=100&before=${encodeURIComponent(page.cursor)}`);
    assert.equal(page.operations.length, 1); assert.equal(older.operations.length, 8); assert.equal(older.operations.some(record => record.id === page.operations[0].id), false);
    assert.equal((await api(`/tasks/${task.id}/file-tool-operations?state=REJECTED`)).operations.length, 2);
    await api(`/tasks/${task.id}/file-tool-operations?before=invalid`, undefined, 400); await api(`/tasks/${task.id}/file-tool-operations?state=FAKE`, undefined, 400);
    pass(`${strategy}: original request IDs, tool ordinals, argument digests, version links and stable string cursors remained visible`);
    const legacyFolder = join(complete.task.workspace, '..', 'operations');
    for (let index = 0; index < 121; index++) { const id = randomUUID(); writeFileSync(join(legacyFolder, `${id}.json`), JSON.stringify({ id, tool: 'inspect_file', state: 'REJECTED', path: `legacy-${index}`, error: `Legacy ${credential}`, [credential]: 'legacy root detail', source: { workerId: 'invented-worker' }, createdAt: '2100-01-01T00:00:00Z' })); }
    const bounded = await api(`/tasks/${task.id}/file-tool-operations?limit=1000`), remaining = await api(`/tasks/${task.id}/file-tool-operations?limit=100&before=${encodeURIComponent(bounded.cursor)}`);
    assert.equal(bounded.operations.length, 100); assert.equal(bounded.hasMore, true); assert.equal(remaining.operations.length, 30); assert.equal(new Set([...bounded.operations, ...remaining.operations].map(record => record.id)).size, 130);
    assert.equal(bounded.total, 130); assert.equal(bounded.unattributedCount, 121); assertCredentialRedacted(bounded); assertCredentialRedacted(remaining);
    assert.ok([...bounded.operations, ...remaining.operations].filter(record => record.sourceStatus === 'LEGACY_SOURCE_UNAVAILABLE')
      .every(record => !record.source && record.error === 'Legacy [REDACTED]' && record['[REDACTED]'] === 'legacy root detail'));
    save(`${strategy}-actual-pages.json`, { newest: page, older, fullFirst: bounded, fullRemaining: remaining });
    const exportResponse = await fetch(`${baseUrl}/api/tasks/${task.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) }); assert.equal(exportResponse.status, 200);
    const archive = Buffer.from(await exportResponse.arrayBuffer()); writeFileSync(join(evidence, `${strategy}-original-receipts.zip`), archive);
    const completeProcessObservations = JSON.parse(zipEntry(archive, 'process-observations.json')); completeProcessObservations.forEach(remember);
    const exported = JSON.parse(zipEntry(archive, 'file-tool-operations.json')), journals = JSON.parse(zipEntry(archive, 'file-tool-journal-sources.json'));
    assert.equal(exported.length, 130); assert.equal(exported.filter(record => record.sourceStatus === 'REQUEST_BOUND').length, 9); assert.equal(exported.filter(record => record.errorCode === 'FILE_VERSION_CONFLICT').length, 2);
    assert.ok(journals.length > 0); assertCredentialRedacted(exported); assertCredentialRedacted(journals); assert.equal(JSON.stringify(exported).includes('invented-worker'), false);
    assert.ok(exported.filter(record => record.sourceStatus === 'LEGACY_SOURCE_UNAVAILABLE').every(record => record['[REDACTED]'] === 'legacy root detail'));
    assert.ok(zipEntry(archive, 'changes.patch').toString('utf8').includes('agent addition')); save(`${strategy}-export-summary.json`, { sha256: digest(archive), bytes: archive.length, fileOperations: exported.length, journals, completeProcessObservationCount: completeProcessObservations.length, originalTaskExecutionId: complete.task.taskExecutionId });
    pass(`${strategy}: complete ZIP retained 130 actual/legacy records, an isolated original review, conflicts and missing-source facts beyond the UI limit`);
  }
  phase = 'unresolved conflict cannot claim final success'; const marker = 'FILE_VERSION_UNRESOLVED';
  const task = await api('/tasks', { projectId: project.id, routeId: route.id, title: '未处理版本冲突', goal: marker, mode: 'LEAD', workspaceSelection: { strategy: 'CLONE', ref: 'HEAD' },
    contract: { scope: ['**'], acceptance: ['Do not claim final success with an unresolved file conflict'], commands: ['node verify.mjs'] }, budget: { maxCalls: 12, reserveCalls: 4 } });
  tasks.set(marker, task.id); await api(`/tasks/${task.id}/approve`, {}); const waiting = await settled(task.id, 'WAITING_INPUT'); save('unresolved-original-facts.json', waiting);
  assert.equal(waiting.task.budget.usedCalls, 4); assert.equal(waiting.operations.operations.length, 2); assert.equal(waiting.operations.operations.filter(record => record.errorCode === 'FILE_VERSION_CONFLICT').length, 1);
  assert.equal(readFileSync(join(waiting.task.workspace, 'value.txt'), 'utf8'), userText); assert.equal(waiting.requests.some(request => request.stage === 'review'), false);
  const waitingExport = await fetch(`${baseUrl}/api/tasks/${task.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(30000) }); assert.equal(waitingExport.status, 200);
  const waitingArchive = Buffer.from(await waitingExport.arrayBuffer()); writeFileSync(join(evidence, 'unresolved-original-receipts.zip'), waitingArchive);
  const waitingObservations = JSON.parse(zipEntry(waitingArchive, 'process-observations.json')); waitingObservations.forEach(remember);
  save('unresolved-export-summary.json', { sha256: digest(waitingArchive), bytes: waitingArchive.length, completeProcessObservationCount: waitingObservations.length });
  assert.deepEqual(sourceFacts(), sourceBefore); pass('An unresolved original conflict kept WAITING_INPUT and the user bytes after a false final success answer');
  save('final-source.json', sourceFacts());
  save('sent-file-tools.json', [...sentTools.values()]);
  assert.deepEqual(errors, []); assert.equal(digest(readFileSync(jar)), jarSha256); assert.equal(digest(readFileSync(fileURLToPath(import.meta.url))), scriptSha256);
  await api('/shutdown', {}); await until('Original core shutdown', ended, 30000); const stopped = checkOriginalIdentities('independent-original-stop'); assert.ok(stopped.every(value => value.originalStopped));
  pass('Original core/Worker/observed descendants independently stopped and final Jar/source bytes remained unchanged');
  report = { recordedAt: new Date().toISOString(), version, platform: process.platform, status: 'passed', scope: 'ACTUAL_FINAL_JAR_ORIGINAL_WORKER_FILE_VERSIONS_AND_HISTORY', jarSha256, scriptSha256,
    schedulerParallelism: 2, schedulerMaxPoolSize: 2, checks, taskIds: [...tasks.values()], calls, originalCore: coreIdentity, originalWorker: worker, processCoverage: 'OBSERVED_ONLY',
    originalIdentities: [...identities.values()], originalIdentitiesVerifiedStopped: stopped.length, modelErrors: errors, automaticReplay: false, evidenceDirectory: evidence };
  save('report.json', report); writeFileSync(join(root, `docs/qa/file-versions-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify({ status: report.status, checks: checks.length, modelRequests: calls.length, originalIdentitiesVerifiedStopped: stopped.length, jarSha256, evidenceDirectory: evidence }));
} catch (error) {
  const failure = { recordedAt: new Date().toISOString(), version, status: 'failed', phase, error: redact(error.message), stack: redact(error.stack || ''), checks, calls, modelErrors: errors, currentTasks: [] };
  if (baseUrl && !ended()) for (const taskId of tasks.values()) { try { failure.currentTasks.push(await facts(taskId)); } catch {} }
  save('failure-before-cleanup.json', failure); console.error(`${phase}: ${failure.error}; original facts preserved in ${evidence}`); process.exitCode = 1;
} finally {
  if (core && !ended()) {
    try { await api('/shutdown', {}); await until('Original core cleanup', ended, 30000); }
    catch (error) { save('core-stop-failure.json', { error: redact(error.message), identity: coreIdentity }); if (coreIdentity) processProbe('stop', coreIdentity.pid, coreIdentity.processStartedAt); }
  }
  model.closeAllConnections(); await new Promise(done => model.close(done));
  if (!report && identities.size) { try { const stopped = checkOriginalIdentities('after-failure-original-stop', 'stop-all'); if (stopped.some(value => !value.originalStopped)) process.exitCode = 1; } catch (error) { save('cleanup-failure.json', { error: redact(error.message) }); process.exitCode = 1; } }
  writeFileSync(join(evidence, 'core.log'), redact(logs));
}
