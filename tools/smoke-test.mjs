import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import { execFileSync } from 'node:child_process';
import { mkdtemp, mkdir, readFile, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import path from 'node:path';

// This endpoint is a deterministic test fixture. It is not an external-model certification.
const baseUrl = (process.env.WORKBENCH_URL || 'http://127.0.0.1:18765').replace(/\/$/, '');
const token = process.env.WORKBENCH_TOKEN;
if (!token) throw new Error('Set WORKBENCH_TOKEN to the token of a separately started test core.');
const fixture = await mkdtemp(path.join(process.env.WORKBENCH_SMOKE_ROOT || tmpdir(), 'agent-workbench-smoke-'));
const projectPath = path.join(fixture, '中文 sample project');
const checks = [];
const calls = [];
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
  response.writeHead(200, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify({ id: `fixture-${calls.length}`, object: 'chat.completion', model: 'smoke-model', choices: [{ index: 0, message: { role: 'assistant', ...message }, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 40, completion_tokens: 20, total_tokens: 60 } }));
}

function tool(name, args) {
  return { id: `tool-${calls.length}-${name}`, type: 'function', function: { name, arguments: JSON.stringify(args) } };
}

async function startFixture() {
  server = createServer(async (incoming, response) => {
    try {
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
      if (prompt.includes('SMOKE_DELAY')) await new Promise(resolve => setTimeout(resolve, 1_500));
      if (completedTools.length === 0 && toolNames.includes('read_file')) {
        reply(response, { content: null, tool_calls: [tool('read_file', { path: 'src/greeting.txt' })] });
      } else if (writable && completedTools.length === 1) {
        reply(response, { content: null, tool_calls: [tool('write_file', { path: 'src/greeting.txt', content: 'hello agent\n' })] });
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
    if (states.includes(task.state)) return task;
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
  await check('Search reports source locations and coverage', async () => {
    const value = (await request(`/projects/${project.id}/search?q=hello`)).data;
    assert.ok(value.results.some(result => result.path === 'src/greeting.txt' && result.line === 1));
    assert.ok(value.coverage && value.version);
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
    return value;
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
  const report = { createdAt: new Date().toISOString(), platform: process.platform, status: process.exitCode ? 'failed' : 'passed', baseUrl, fixture, projectPath, checks, calls, scope: 'Local deterministic model protocol fixture only; not external provider, CLI or cross-platform certification.' };
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
