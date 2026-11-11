import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { configureTeamMcp, createTeamProtocol, teamGoal, userNote, verifyTeamResult, zipEntry } from './team-smoke-fixture.mjs';

// This endpoint is a deterministic test fixture. It is not an external-model certification.
const baseUrl = (process.env.WORKBENCH_URL || 'http://127.0.0.1:18765').replace(/\/$/, '');
const token = process.env.WORKBENCH_TOKEN;
if (!token) throw new Error('Set WORKBENCH_TOKEN to the token of a separately started test core.');
const fixture = await mkdtemp(path.join(process.env.WORKBENCH_SMOKE_ROOT || tmpdir(), 'agent-workbench-smoke-'));
const projectPath = path.join(fixture, '中文 sample project');
const checks = [];
const calls = [];
const teamProtocol = createTeamProtocol();
const teamReports = [];
let routeId;
let routeConfig;
let server;

function git(...args) {
  return execFileSync('git', ['-C', projectPath, ...args], { encoding: 'utf8', windowsHide: true }).trim();
}

async function check(name, action) {
  const started = Date.now();
  try {
    const result = await action();
    checks.push({ name, status: 'passed', durationMs: Date.now() - started });
    console.log(`PASS ${name}`);
    return result;
  } catch (error) {
    checks.push({ name, status: 'failed', durationMs: Date.now() - started, message: error.message });
    throw error;
  }
}

async function request(url, body, { expected = 200, method, authorized = true } = {}) {
  const response = await fetch(`${baseUrl}/api${url}`, {
    method: method || (body === undefined ? 'GET' : 'POST'),
    headers: { ...(authorized ? { Authorization: `Bearer ${token}` } : {}), ...(body !== undefined ? { 'Content-Type': 'application/json' } : {}) },
    ...(body !== undefined ? { body: JSON.stringify(body) } : {}),
    signal: AbortSignal.timeout(20_000),
  });
  const raw = await response.text();
  let data;
  try { data = JSON.parse(raw); } catch { data = raw; }
  if (expected !== null) {
    const valid = Array.isArray(expected) ? expected.includes(response.status) : response.status === expected;
    assert.ok(valid, `${url}: HTTP ${response.status}, expected ${expected}: ${raw.slice(0, 800)}`);
  }
  return { response, data };
}

function reply(response, message) {
  response.writeHead(200, { 'Content-Type': 'application/json', 'x-request-id': `provider-${calls.length}` });
  response.end(JSON.stringify({ id: `fixture-${calls.length}`, object: 'chat.completion', model: 'smoke-model', choices: [{ index: 0, message: { role: 'assistant', ...message }, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 40, completion_tokens: 20, total_tokens: 60 } }));
}

function tool(name, args) {
  return { id: `tool-${calls.length}-${name}`, type: 'function', function: { name, arguments: JSON.stringify(args) } };
}

async function startFixture() {
  server = createServer(async (incoming, response) => {
    try {
      if (incoming.url === '/team-mcp') { await teamProtocol.handleMcp(incoming, response); return; }
      if (incoming.method === 'GET') {
        response.writeHead(200, { 'Content-Type': 'application/json' });
        response.end(JSON.stringify({ object: 'list', data: [{ id: 'smoke-model', object: 'model' }] }));
        return;
      }
      let raw = '';
      for await (const chunk of incoming) raw += chunk;
      const body = JSON.parse(raw);
      assert.equal(incoming.url, '/v1/chat/completions');
      assert.equal(body.model, 'smoke-model');
      assert.equal(incoming.headers.authorization, 'Bearer test-only-not-a-secret');
      const messages = body.messages || [];
      const prompt = messages.filter(message => ['system', 'user'].includes(message.role)).map(message => String(message.content)).join('\n');
      const toolNames = (body.tools || []).map(entry => entry.function?.name);
      const completedTools = messages.filter(message => message.role === 'tool');
      const writable = toolNames.includes('write_file');
      const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1] || 'UNSPECIFIED';
      const reviewing = stage === 'REVIEW';
      const kind = reviewing ? 'review' : writable ? 'implementation' : 'analysis';
      calls.push({ number: calls.length + 1, path: incoming.url, kind, stage, tools: toolNames, completedTools: completedTools.length,
        proposalIncludesPriorHandoffs: stage === 'PROPOSAL' && prompt.includes('已完成阶段交接资料') });
      if (teamProtocol.matches(body)) { reply(response, await teamProtocol.message(body)); return; }
      if (prompt.includes('SMOKE_DELAY')) await new Promise(resolve => setTimeout(resolve, 1_500));
      if (completedTools.length === 0 && toolNames.includes('read_file')) {
        reply(response, { content: null, tool_calls: [tool('read_file', { path: 'src/greeting.txt' })] });
      } else if (writable && completedTools.length === 1) {
        const read = JSON.parse(completedTools[0].content); assert.match(read.readVersion, /^file-read-/);
        reply(response, { content: null, tool_calls: [tool('write_file', { path: 'src/greeting.txt', content: 'hello agent\n', expectedVersion: read.readVersion })] });
      } else if (writable && completedTools.length === 2 && toolNames.includes('run_command')) {
        reply(response, { content: null, tool_calls: [tool('run_command', { command: 'node verify.mjs' })] });
      } else if (reviewing) {
        reply(response, { content: JSON.stringify({ approved: true, issues: [], summary: 'Test fixture independently inspected the isolated review copy.' }) });
      } else {
        reply(response, { content: writable ? '已将 src/greeting.txt 更新为 hello agent，并请求执行约定验证命令。' : '已读取样例项目。建议修改 src/greeting.txt，保留约定命令，完成验证后独立审查。' });
      }
    } catch (error) {
      response.writeHead(500, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ error: { message: error.message } }));
    }
  });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  return `http://127.0.0.1:${server.address().port}/v1`;
}

async function untilTask(id, states, timeout = 120_000) {
  const deadline = Date.now() + timeout;
  let task;
  while (Date.now() < deadline) {
    task = (await request(`/tasks/${id}`)).data;
    if (states.includes(task.state) && (task.state !== 'AWAITING_ACCEPTANCE' || task.taskExecution?.state === 'SUCCEEDED')) return task;
    if (['FAILED', 'UNKNOWN', 'WAITING_INPUT'].includes(task.state)) {
      throw new Error(`Task ${id} stopped in ${task.state}: ${task.error || JSON.stringify(task.issues || [])}`);
    }
    await new Promise(resolve => setTimeout(resolve, 150));
  }
  throw new Error(`Task ${id} timed out in ${task?.state}: ${task?.error || ''}`);
}

try {
  await mkdir(path.join(projectPath, 'src'), { recursive: true });
  await writeFile(path.join(projectPath, '.gitattributes'), '* text eol=lf\n');
  await writeFile(path.join(projectPath, 'src/greeting.txt'), 'hello\n');
  await writeFile(path.join(projectPath, 'verify.mjs'), "import assert from 'node:assert/strict';\nimport {readFileSync} from 'node:fs';\nassert.equal(process.env.WORKBENCH_TOKEN, undefined, 'Project commands must not inherit the platform access token');\nconst value = readFileSync('src/greeting.txt', 'utf8');\nassert.match(value, /^hello(?: agent)?\\n$/);\nconsole.log('greeting format verified: ' + value.trim());\n");
  git('init');
  git('config', 'user.name', 'Workbench Smoke Test');
  git('config', 'user.email', 'smoke@example.invalid');
  git('config', 'core.autocrlf', 'false');
  git('add', '.');
  git('commit', '-m', 'Create isolated smoke fixture');
  const originalHead = git('rev-parse', 'HEAD');

  await check('API rejects missing access token', () => request('/state', undefined, { expected: [401, 403], authorized: false }));
  const state = await check('Core returns persisted state', async () => {
    const value = (await request('/state')).data;
    assert.ok(Array.isArray(value.projects));
    assert.ok(Array.isArray(value.tasks));
    return value;
  });
  const project = await check('Import real Git repository with Chinese and spaced path', async () => {
    const value = (await request('/projects', { name: `Smoke ${Date.now()}`, path: projectPath }, { expected: [200, 201] })).data;
    assert.ok(value.id);
    assert.equal(value.head, originalHead);
    return value;
  });
  await check('Local execution node and repository binding are authoritative and versioned', async () => {
    await request('/execution-nodes', undefined, { expected: 401, authorized: false });
    await request(`/repository-bindings?projectId=${project.id}`, undefined, { expected: 401, authorized: false });
    const nodes = (await request('/execution-nodes')).data, workers = (await request('/workers')).data, bindings = (await request(`/repository-bindings?projectId=${project.id}`)).data;
    assert.equal(nodes.length, 1); assert.equal(nodes[0].deployment, 'LOCAL_PROCESS'); assert.equal(nodes[0].state, 'ONLINE');
    assert.equal(workers[0].executionNodeId, nodes[0].id); assert.ok(Number.isInteger(workers[0].pid)); assert.equal(workers[0].state, 'RUNNING');
    assert.equal(workers[0].deployment, 'LOCAL_PROCESS'); assert.notEqual(workers[0].pid, workers[0].corePid); assert.equal(workers[0].parentPid, workers[0].corePid);
    if (process.env.WORKBENCH_EXPECTED_CORE_PID) assert.equal(workers[0].corePid, Number(process.env.WORKBENCH_EXPECTED_CORE_PID));
    assert.ok(Number.isFinite(Date.parse(workers[0].processStartedAt))); assert.equal(workers[0].controlEnvironmentPresent, false); assert.equal(workers[0].jvmOptionsEnvironmentPresent, false); assert.equal(workers[0].controlPropertiesPresent, false);
    assert.equal(bindings.length, 1); assert.equal(bindings[0].id, project.repositoryBindingId); assert.equal(bindings[0].executionNodeId, nodes[0].id); assert.equal(bindings[0].projectId, project.id);
    await request('/execution-nodes', { id: nodes[0].id, expectedVersion: nodes[0].version, name: 'bad input', enabled: 'true' }, { expected: 400 });
    await request('/execution-nodes', { id: nodes[0].id, expectedVersion: nodes[0].version + 1, name: 'stale configuration', enabled: true }, { expected: 409 });
    await request('/repository-bindings', { projectId: project.id, executionNodeId: nodes[0].id, name: 'relative input', path: 'relative' }, { expected: 400 });
    return { executionNodeId: nodes[0].id, repositoryBindingId: bindings[0].id, workerId: workers[0].id, epoch: workers[0].epoch, workerPid: workers[0].pid, corePid: workers[0].corePid, deployment: workers[0].deployment };
  });
  await check('Search reports source locations and coverage', async () => {
    const value = (await request(`/projects/${project.id}/search?q=hello`)).data;
    assert.ok(value.results.some(result => result.path === 'src/greeting.txt' && result.line === 1));
    assert.ok(value.coverage && value.version);
  });
  await check('Session routes require authentication and preserve server-owned message identity', async () => {
    const health = (await request('/health')).data;
    assert.equal(health.schemaVersion, 8); assert.equal(health.apiVersion, 1);
    const base = `/projects/${project.id}/sessions`;
    await request(base, undefined, { expected: 401, authorized: false });
    await request(`${base}/search?q=background`, undefined, { expected: 401, authorized: false });
    await request(`${base}/search?q=`, undefined, { expected: 400 });
    const session = (await request(base, { title: 'Persistent session fixture' })).data;
    const note = { requestId: 'note_smoke_1', content: '协作背景与验证依据 fulltextmarker', role: 'ASSISTANT', kind: 'TASK_GOAL', taskId: 'forged' };
    const first = (await request(`${base}/${session.id}/messages`, note)).data;
    const repeated = (await request(`${base}/${session.id}/messages`, note)).data;
    assert.equal(first.id, repeated.id); assert.equal(first.kind, 'NOTE'); assert.equal(first.role, 'USER'); assert.equal(first.taskId, undefined);
    await request(`${base}/${session.id}/messages`, { ...note, content: 'conflict' }, { expected: 409 });
    await request(`${base}/${session.id}/messages`, { ...note, requestId: 'task:reserved' }, { expected: 400 });
    const page = (await request(`${base}/${session.id}/messages?limit=1`)).data;
    assert.equal(page.messages.length, 1); assert.ok(page.messages[0].sequence > 0);
    const search = (await request(`${base}/search?q=${encodeURIComponent('验证依据')}`)).data;
    assert.equal(search.results.length, 1); assert.equal(search.results[0].messageId, first.id); assert.ok(search.results[0].matchEnd > search.results[0].matchStart);
    assert.equal((await request(`${base}/search?q=FULLTEXTMARKER`)).data.results[0].messageId, first.id);
    assert.equal((await request(`${base}/search?q=${encodeURIComponent('协作')}`)).data.results[0].messageId, first.id);
    await request(`${base}/${session.id}`, { expectedVersion: session.version, isOpen: false });
    await request(`${base}/${session.id}`, { expectedVersion: session.version, title: 'stale' }, { expected: 409 });
    assert.equal((await request(`${base}/${session.id}`)).data.isOpen, false);
    assert.ok((await request(base)).data.sessions.some(item => item.id === session.id));
  });
  const memory = await check('Save project knowledge with an explicit source', async () => {
    const value = (await request('/memories', { projectId: project.id, kind: 'FACT', content: '样例问候文件位于 src/greeting.txt。', source: `src/greeting.txt @ ${originalHead}` }, { expected: [200, 201] })).data;
    assert.equal(value.projectId, project.id);
    return value;
  });
  await check('Reject unsupported permission in an imported role template', () => request('/profiles', { name: 'Invalid fixture role', role: 'fixture', instructions: 'Request unsupported authority', capabilities: ['read', 'system.admin'] }, { expected: 400 }));
  await check('Reject cyclic execution plan before dispatch', async () => {
    const stage = (id, dependency) => ({ id, title: id, role: 'developer', dependencies: [dependency], inputs: [], outputs: [id], acceptance: ['verified'], resources: [], permissions: [] });
    const result = (await request('/plans/validate', { stages: [stage('a', 'b'), stage('b', 'a')], permissions: [], maxRoles: 6 })).data;
    assert.equal(result.valid, false);
    assert.ok(result.errors.length > 0);
  });
  const fixtureUrl = await startFixture();
  const route = await check('Configure local protocol test route', async () => {
    const value = (await request('/routes', { name: `Local fixture ${Date.now()}`, engine: 'API', model: 'smoke-model', provider: 'LOCAL_TEST_ONLY', baseUrl: fixtureUrl, enabled: true, apiKey: 'test-only-not-a-secret' }, { expected: [200, 201] })).data;
    assert.ok(value.id);
    return value;
  });
  routeId = route.id;
  routeConfig = route;
  const definition = {
    projectId: project.id,
    title: `SMOKE tool loop ${Date.now()}`,
    goal: 'SMOKE：将 src/greeting.txt 从 hello 改成 hello agent。只修改该文件，执行 node verify.mjs，完成后独立审查。',
    mode: 'PIPELINE', routeId,
    contract: { scope: ['src/greeting.txt'], acceptance: ['src/greeting.txt 内容恰好为 hello agent，尾部保留换行', '约定验证命令执行成功'], commands: ['node verify.mjs'] },
    budget: { maxCalls: 24, reserveCalls: 6 },
    locks: { engine: 'API', model: 'smoke-model', routeId }, includeChanges: false,
  };
  const task = await check('Create task with fixed contract and route locks', async () => {
    const value = (await request('/tasks', definition, { expected: [200, 201] })).data;
    assert.ok(value.id);
    assert.equal(value.state, 'DRAFT');
    assert.ok(value.context.some(item => item.id === memory.id));
    assert.ok(value.sessionId);
    const session = (await request(`/projects/${project.id}/sessions/${value.sessionId}`)).data;
    assert.equal(session.projectId, project.id);
    return value;
  });
  await check('One session carries multiple tasks with source goals and scoped activity', async () => {
    const base = `/projects/${project.id}/sessions/${task.sessionId}`;
    const sibling = (await request('/tasks', { ...definition, title: 'Sibling session task', goal: 'SMOKE sibling: review the project background without executing the primary task', sessionId: task.sessionId })).data;
    assert.equal(sibling.sessionId, task.sessionId); assert.equal(sibling.state, 'DRAFT');
    const goals = (await request(`${base}/messages`)).data.messages;
    assert.deepEqual(goals.map(message => message.taskId), [task.id, sibling.id]);
    assert.ok(goals.every(message => message.kind === 'TASK_GOAL'));
    assert.deepEqual(goals.map(message => message.content), [definition.goal, sibling.goal]);
    const activity = (await request(`${base}/events`)).data.events;
    assert.ok(activity.some(event => event.taskId === task.id)); assert.ok(activity.some(event => event.taskId === sibling.id));
    assert.ok(activity.every(event => [task.id, sibling.id].includes(event.taskId)));
    await request(base, { expectedVersion: 1, isOpen: false });
    assert.equal((await request(`/tasks/${task.id}`)).data.state, 'DRAFT');
  });
  await check('Reject route that does not match the hard model lock', async () => {
    const before = calls.length;
    await request('/tasks', { ...definition, goal: `${definition.goal}\nSMOKE_LOCK`, locks: { model: 'different-model' } }, { expected: 409 });
    assert.equal(calls.length, before);
  });
  await check('Reject acceptance before required execution and evidence', async () => {
    const { response } = await request(`/tasks/${task.id}/accept`, {}, { expected: null });
    assert.ok(response.status >= 400 && response.status < 500);
  });
  await request(`/tasks/${task.id}/approve`, {});
  const finished = await check('Execute actual Java API tool loop, verification and independent review', async () => {
    const value = await untilTask(task.id, ['AWAITING_ACCEPTANCE']);
    assert.ok(value.workspace);
    assert.equal(await readFile(path.join(value.workspace, 'src/greeting.txt'), 'utf8'), 'hello agent\n');
    assert.ok(calls.some(call => call.kind === 'implementation' && call.completedTools >= 3));
    assert.ok(calls.some(call => call.kind === 'review'));
    assert.ok(value.evidence.some(evidence => evidence.kind === 'BASELINE' && evidence.status === 'PASSED'));
    assert.ok(value.evidence.filter(evidence => evidence.kind === 'VERIFICATION').every(evidence => evidence.status === 'PASSED'));
    assert.ok(value.evidence.some(evidence => evidence.exitCode === 0 && evidence.version));
    assert.ok(value.budget.usedCalls > 0 && value.budget.usedCalls <= value.budget.maxCalls);
    return value;
  });
  await check('Agent runs persist real calls, scoped pages and evidence identity', async () => {
    await request(`/tasks/${task.id}/agent-runs`, undefined, { expected: 401, authorized: false });
    const base = `/projects/${project.id}/sessions/${task.sessionId}/agent-runs`;
    await request(`${base}?state=FORGED`, undefined, { expected: 400 });
    await request(`${base}?before=-1`, undefined, { expected: 400 });
    const page = (await request(`/tasks/${task.id}/agent-runs`)).data;
    assert.equal(page.runs.length, 3); assert.equal(page.hasMore, false);
    assert.ok(page.runs.every(run => run.taskId === task.id && run.rootTaskId === task.id && run.state === 'SUCCEEDED' && run.elapsedMs >= 0));
    assert.equal(page.runs.reduce((sum, run) => sum + run.calls, 0), finished.budget.usedCalls);
    assert.equal(page.runs.filter(run => run.reserved).length, 1);
    assert.ok(page.runs.every(run => finished.evidence.some(item => item.id === run.evidenceId && item.agentRunId === run.id)));
    assert.ok(!JSON.stringify(page).includes('test-only-not-a-secret'));
    const first = (await request(`${base}?limit=1`)).data;
    assert.equal(first.runs.length, 1); assert.equal(first.hasMore, true);
    const second = (await request(`${base}?limit=1&before=${first.cursor}`)).data;
    assert.notEqual(first.runs[0].id, second.runs[0].id);
    assert.equal((await request(`${base}?state=FAILED`)).data.runs.length, 0);
  });
  await check('Durable root attempt keeps its original action, owner and execution receipt', async () => {
    for (const endpoint of ['task-executions', 'dispatch-actions']) {
      await request(`/tasks/${task.id}/${endpoint}`, undefined, { expected: 401, authorized: false });
      await request(`/tasks/${task.id}/${endpoint}?state=FORGED`, undefined, { expected: 400 });
      await request(`/tasks/${task.id}/${endpoint}?before=-1`, undefined, { expected: 400 });
    }
    const executions = (await request(`/tasks/${task.id}/task-executions?limit=1`)).data;
    const actions = (await request(`/tasks/${task.id}/dispatch-actions?limit=1`)).data;
    assert.equal(executions.executions.length, 1); assert.equal(actions.actions.length, 1); assert.equal(executions.hasMore, false);
    const original = executions.executions[0], action = actions.actions[0];
    assert.equal(original.id, finished.taskExecutionId); assert.equal(original.dispatchActionId, finished.dispatchActionId);
    assert.equal(original.state, 'SUCCEEDED'); assert.equal(original.taskStateAtEnd, 'AWAITING_ACCEPTANCE'); assert.equal(original.attempt, 1);
    assert.equal(action.id, original.dispatchActionId); assert.equal(action.taskExecutionId, original.id); assert.equal(action.state, 'SETTLED'); assert.equal(action.dispatchCount, 1);
    assert.equal(action.ownerCorePid, (await request('/health')).data.corePid); assert.ok(action.ownerCoreSessionId); assert.ok(action.ownerCoreStartedAt);
    assert.equal(original.budgetAtEnqueue.usedCalls, 0); assert.equal(original.budgetAtStart.usedCalls, 0); assert.equal(original.budgetAtEnd.usedCalls, finished.budget.usedCalls);
    for (const records of [(await request(`/tasks/${task.id}/agent-runs`)).data.runs, (await request(`/tasks/${task.id}/model-requests`)).data.requests,
      (await request(`/tasks/${task.id}/worker-leases?limit=100`)).data.leases, (await request(`/tasks/${task.id}/operations`)).data, finished.evidence]) {
      assert.ok(records.length); assert.ok(records.every(record => record.taskExecutionId === original.id && record.dispatchActionId === action.id));
    }
  });
  await check('Model request trace binds each API round to budget, usage and provider receipt', async () => {
    const endpoint = `/tasks/${task.id}/model-requests`;
    await request(endpoint, undefined, { expected: 401, authorized: false });
    await request(`${endpoint}?state=FORGED`, undefined, { expected: 400 });
    await request(`${endpoint}?before=-1`, undefined, { expected: 400 });
    const page = (await request(endpoint)).data;
    assert.equal(page.requests.length, finished.budget.usedCalls);
    assert.ok(page.requests.every(value => value.state === 'SUCCEEDED' && value.httpStatus === 200 && value.elapsedMs >= 0));
    assert.ok(page.requests.every(value => value.providerRequestId?.startsWith('provider-') && value.responseId?.startsWith('fixture-')));
    assert.ok(page.requests.every(value => value.usage.inputTokens === 40 && value.usage.outputTokens === 20 && value.usage.cachedInputTokens === 'UNKNOWN'));
    assert.ok(page.requests.every(value => value.inputDigest.length === 64 && value.inputBytes > 0 && !value.input && !value.messages));
    assert.ok(page.requests.every(value => typeof value.inputPreview === 'string' && value.inputPreview.length <= 6000 && typeof value.inputTruncated === 'boolean'));
    assert.ok(!JSON.stringify(page).includes('test-only-not-a-secret'));
    const runId = page.requests[0].agentRunId;
    const scoped = (await request(`${endpoint}?runId=${runId}`)).data;
    assert.ok(scoped.requests.length > 0 && scoped.requests.every(value => value.agentRunId === runId));
    const newest = (await request(`${endpoint}?limit=1`)).data;
    const older = (await request(`${endpoint}?limit=1&before=${newest.cursor}`)).data;
    assert.notEqual(newest.requests[0].id, older.requests[0].id);
    assert.equal((await request(`${endpoint}?q=${encodeURIComponent(page.requests[0].responseId)}`)).data.requests.length, 1);
    assert.equal((await request(`${endpoint}?state=FAILED`)).data.requests.length, 0);
  });
  await check('Preserve source Git worktree and staged state', async () => {
    assert.equal(await readFile(path.join(projectPath, 'src/greeting.txt'), 'utf8'), 'hello\n');
    assert.equal(git('status', '--porcelain'), '');
    assert.equal(git('rev-parse', 'HEAD'), originalHead);
    assert.notEqual(path.resolve(finished.workspace), path.resolve(projectPath));
  });
  await check('Diff and evidence are bound to the delivered version', async () => {
    const value = (await request(`/tasks/${task.id}/diff`)).data;
    assert.ok(value.diff.includes('hello agent'));
    assert.equal(value.version, finished.version);
    assert.equal(finished.review.version, finished.version);
  });
  await check('Persistent events can be replayed with a cursor', async () => {
    const first = (await request(`/tasks/${task.id}/events?after=0&limit=2`)).data;
    assert.ok(first.events.length > 0 && first.events.length <= 2);
    const second = (await request(`/tasks/${task.id}/events?after=${first.cursor}&limit=2`)).data;
    const ids = new Set(first.events.map(event => event.id));
    assert.ok(second.events.every(event => !ids.has(event.id)));
  });
  await check('Reject acceptance and export when content changes after review', async () => {
    const file = path.join(finished.workspace, 'src/greeting.txt');
    await writeFile(file, 'changed after review\n');
    try {
      await request(`/tasks/${task.id}/accept`, {}, { expected: 409 });
      await request(`/tasks/${task.id}/export`, undefined, { expected: 409 });
      assert.equal((await request(`/tasks/${task.id}`)).data.state, 'AWAITING_ACCEPTANCE');
    } finally {
      await writeFile(file, 'hello agent\n');
    }
  });
  await check('Manual acceptance records completion without writing source', async () => {
    await request(`/tasks/${task.id}/accept`, {});
    const value = (await request(`/tasks/${task.id}`)).data;
    assert.equal(value.state, 'COMPLETED');
    assert.equal(await readFile(path.join(projectPath, 'src/greeting.txt'), 'utf8'), 'hello\n');
  });
  await check('Export portable delivery archive', async () => {
    const response = await fetch(`${baseUrl}/api/tasks/${task.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20_000) });
    assert.equal(response.status, 200);
    const archive = Buffer.from(await response.arrayBuffer());
    assert.equal(archive.subarray(0, 2).toString(), 'PK');
    assert.ok(archive.includes(Buffer.from('agent-runs.json')), '交付包必须包含运行记录');
    assert.ok(archive.includes(Buffer.from('model-requests.json')), '交付包必须包含请求追踪');
    await writeFile(path.join(fixture, 'delivery.zip'), archive);
  });
  await check('Disable knowledge while preserving its revision', async () => {
    const value = (await request(`/memories/${memory.id}`, { status: 'DISABLED' }, { method: 'PATCH' })).data;
    assert.equal(value.status, 'DISABLED');
    assert.ok(value.version > memory.version);
  });
  for (const mode of ['LEAD', 'ROUNDTABLE']) {
    await check(`Complete ${mode} stages under the same root budget`, async () => {
      const start = calls.length;
      const created = (await request('/tasks', { ...definition, mode, title: `SMOKE ${mode} ${Date.now()}`, goal: `${definition.goal}\nSMOKE_MODE_${mode}` }, { expected: [200, 201] })).data;
      assert.ok(created.context.every(item => item.id !== memory.id));
      await request(`/tasks/${created.id}/approve`, {});
      const result = await untilTask(created.id, ['AWAITING_ACCEPTANCE']);
      assert.equal(await readFile(path.join(result.workspace, 'src/greeting.txt'), 'utf8'), 'hello agent\n');
      assert.ok(result.budget.usedCalls <= result.budget.maxCalls);
      assert.ok(result.budget.usedDevelopmentCalls <= result.budget.maxCalls - result.budget.reserveCalls);
      if (mode === 'ROUNDTABLE') {
        const proposals = calls.slice(start).filter(call => call.stage === 'PROPOSAL' && call.completedTools === 0);
        assert.equal(proposals.length, 3);
        assert.ok(proposals.every(call => !call.proposalIncludesPriorHandoffs));
      }
      await request(`/tasks/${created.id}/accept`, {});
      assert.equal(git('status', '--porcelain'), '');
    });
  }
  await check('Actual dynamic team shares the root budget, adopts dependencies and preserves MCP source in the ZIP', async () => {
    const api = async (url, body) => (await request(url, body)).data;
    await request('/agent-definitions', undefined, { authorized: false, expected: 401 });
    const definitions = await api(`/agent-definitions?projectId=${project.id}`);
    const selected = definitions.filter(item => ['agent-developer', 'agent-reviewer'].includes(item.id)); assert.equal(selected.length, 2);
    const collaboration = { enabled: true, definitionIds: selected.map(item => item.id), definitionVersions: Object.fromEntries(selected.map(item => [item.id, item.version])), maxActive: 3, maxDepth: 2, maxTotal: 2 };
    const mcp = await configureTeamMcp(api, project.id, fixtureUrl.replace(/\/v1$/, '/team-mcp'));
    const teamDefinition = { ...definition, goal: teamGoal, title: `SMOKE actual team ${Date.now()}`, mode: 'LEAD', contract: { ...definition.contract, scope: ['**'] }, budget: { maxCalls: 40, reserveCalls: 6 }, collaboration, mcpBindings: [mcp.binding], mcpBudget: { maxCalls: 2, maxDurationMs: 10000 } };
    const rootCount = (await api('/state')).tasks.length;
    await request('/tasks', { ...teamDefinition, collaboration: { ...collaboration, definitionVersions: { ...collaboration.definitionVersions, 'agent-developer': 9999 } } }, { expected: 409 });
    assert.equal((await api('/state')).tasks.length, rootCount);
    const created = await api('/tasks', teamDefinition); assert.ok(created.teamId); assert.equal(created.state, 'DRAFT');
    assert.deepEqual(created.collaboration.definitions.map(item => [item.id, item.version]), selected.map(item => [item.id, item.version]));
    await request(`/tasks/${created.id}/team`, undefined, { authorized: false, expected: 401 });
    await request(`/tasks/${created.id}/team/messages`, undefined, { authorized: false, expected: 401 });
    const message = { requestId: 'team_smoke_note_1', content: userNote, sender: 'forged-model', modelRequestId: 'forged-request' };
    const first = await api(`/tasks/${created.id}/team/messages`, message), repeated = await api(`/tasks/${created.id}/team/messages`, message);
    assert.equal(first.sender, 'USER'); assert.equal(first.requestId, ''); assert.equal(first.id, repeated.id);
    await request(`/tasks/${created.id}/team/messages`, { ...message, content: 'conflicting retry' }, { expected: 409 });
    await api(`/tasks/${created.id}/approve`, {});
    const result = await untilTask(created.id, ['AWAITING_ACCEPTANCE']);
    const observed = await verifyTeamResult(api, result, teamProtocol);
    assert.equal((await api('/state')).tasks.length, rootCount + 1, 'Children must not create independent root tasks or budgets');
    assert.equal(git('status', '--porcelain'), ''); assert.equal(await readFile(path.join(projectPath, 'src/greeting.txt'), 'utf8'), 'hello\n');
    await api(`/tasks/${created.id}/accept`, {}); assert.equal((await api(`/tasks/${created.id}`)).state, 'COMPLETED');
    const response = await fetch(`${baseUrl}/api/tasks/${created.id}/export`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(20000) }); assert.equal(response.status, 200);
    const archive = Buffer.from(await response.arrayBuffer()), json = name => JSON.parse(zipEntry(archive, name).toString('utf8'));
    assert.equal(json('team.json').agents.length, 2); assert.equal(json('team-operations.json').length, 1); assert.equal(json('team-operations.json')[0].state, 'ACKNOWLEDGED');
    assert.equal(json('team-operations.json')[0].id, observed.writer.integrations[0].operationId);
    assert.equal(json('team-messages.json').length, 2); assert.equal(json('model-requests.json').length, result.budget.usedCalls);
    assert.equal(json('worker-leases.json').length, observed.workerLeases.length); assert.ok(json('worker-leases.json').every(lease => !Object.hasOwn(lease, 'claimDigest')));
    assert.equal(json('execution.json').repositoryBindingId, result.execution.repositoryBindingId); assert.equal(json('workers.json')[0].id, observed.workers[0].id);
    assert.equal(json('mcp-operations.json')[0].agentRunId, observed.writer.agentRunId); assert.equal(json('mcp-operations.json')[0].modelRequestId, observed.mcp[0].modelRequestId);
    assert.ok(json('agent-runs.json').some(run => run.id === observed.reader.agentRunId)); assert.match(zipEntry(archive, 'changes.patch').toString('utf8'), /parent change preserved/);
    await writeFile(path.join(fixture, 'team-delivery.zip'), archive);
    teamReports.push({ taskId: created.id, teamId: created.teamId, rootCalls: result.budget.usedCalls, children: observed.team.agents.map(agent => ({ id: agent.id, agentRunId: agent.agentRunId, state: agent.state, dependsOn: agent.dependsOn, integrationState: agent.integrationState })), mcpSource: observed.mcp[0], calls: teamProtocol.calls, externalMcpCalls: teamProtocol.mcpCalls.length, zipSourcesVerified: true });
    await api('/mcp/servers', { ...mcp.server, enabled: false });
  });
  await check('Cancel an in-flight task and keep source intact', async () => {
    const start = calls.length;
    const value = (await request('/tasks', { ...definition, title: `SMOKE cancel ${Date.now()}`, goal: `${definition.goal}\nSMOKE_DELAY` }, { expected: [200, 201] })).data;
    await request(`/tasks/${value.id}/approve`, {});
    const deadline = Date.now() + 30_000;
    while (calls.length === start && Date.now() < deadline) await new Promise(resolve => setTimeout(resolve, 100));
    assert.ok(calls.length > start, 'The delayed model request must actually start before cancellation');
    await request(`/tasks/${value.id}/cancel`, {});
    const cancelled = await untilTask(value.id, ['CANCELLED', 'UNKNOWN'], 30_000);
    assert.ok(['CANCELLED', 'UNKNOWN'].includes(cancelled.state));
    if (cancelled.state === 'UNKNOWN') assert.ok(cancelled.error || cancelled.issues?.length || cancelled.evidence?.length);
    const stoppedCalls = calls.length;
    await new Promise(resolve => setTimeout(resolve, 1_700));
    assert.equal(calls.length, stoppedCalls, 'Cancellation must prevent follow-up model requests');
    assert.equal(git('status', '--porcelain'), '');
  });
  console.log(`Passed ${checks.length} checks. This validates a local protocol fixture, not an external provider. Initial task count: ${state.tasks.length}.`);
} catch (error) {
  console.error(error.stack || error.message);
  process.exitCode = 1;
} finally {
  if (routeId) {
    try { await request('/routes', { ...routeConfig, id: routeId, enabled: false }, { expected: null }); } catch { /* Keep the report even if the core stopped. */ }
  }
  if (server) {
    server.closeAllConnections();
    await new Promise(resolve => server.close(resolve));
  }
  const report = { createdAt: new Date().toISOString(), platform: process.platform, status: process.exitCode ? 'failed' : 'passed', baseUrl, fixture, projectPath, checks, calls, teams: teamReports, scope: 'Local deterministic model protocol fixture only; not external provider, CLI or cross-platform certification.' };
  const serialized = JSON.stringify(report, (_key, value) => typeof value === 'string'
    ? value.replaceAll(token, '[REDACTED]').replaceAll('test-only-not-a-secret', '[REDACTED_FIXTURE_KEY]')
    : value, 2);
  const reportFile = path.join(fixture, 'smoke-report.json');
  await writeFile(reportFile, serialized);
  console.log(`Report: ${reportFile}`);
  if (process.env.WORKBENCH_REPORT_PATH) {
    const reportCopy = path.resolve(process.env.WORKBENCH_REPORT_PATH);
    await mkdir(path.dirname(reportCopy), { recursive: true });
    await writeFile(reportCopy, serialized);
    console.log(`Report copy: ${reportCopy}`);
  }
}
