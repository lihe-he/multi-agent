import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { javaHome } from './java-runtime.mjs';
import { configureTeamMcp, createTeamProtocol, inspectModel, readerTitle, teamGoal, userNote, verifyTeamResult, writerTitle } from './team-smoke-fixture.mjs';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const { chromium, _electron } = createRequire(join(root, 'web/package.json'))('@playwright/test');
mkdirSync(join(root, '.runtime'), { recursive: true });
const dir = mkdtempSync(join(root, '.runtime', 'standalone-'));
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
const token = randomBytes(32).toString('hex');
const windows = process.platform === 'win32';
const home = javaHome(root);
const java = home ? join(home, 'bin', windows ? 'java.exe' : 'java') : 'java';
const jar = join(root, `core/target/agent-workbench-core-${version}.jar`);
if (!existsSync(jar) || !existsSync(join(root, 'web/dist/workbench-config.json'))) throw new Error('请先构建核心和 Web。');
let logs = '';
const core = spawn(java, ['-Dfile.encoding=UTF-8', '-jar', jar], {
  cwd: dir, windowsHide: true,
  env: { ...process.env, WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', WORKBENCH_TOKEN: token, WORKBENCH_PORT: '0', WORKBENCH_DATA_DIR: join(dir, 'data'), LOGGING_LEVEL_ROOT: 'INFO' },
  stdio: ['ignore', 'pipe', 'pipe'],
});
let processError;
core.once('error', error => { processError = error; });
core.stdout.on('data', chunk => { logs = (logs + chunk).slice(-20000); });
core.stderr.on('data', chunk => { logs = (logs + chunk).slice(-20000); });
let baseUrl, browser, desktop;
const pendingModelResponses = [];
const teamProtocol = createTeamProtocol({ model: 'fixture', holdWriter: true });
const modelErrors = [];
const model = createServer(async (request, response) => {
  try {
    if (request.url === '/team-mcp') { await teamProtocol.handleMcp(request, response); return; }
    let raw = ''; for await (const chunk of request) raw += chunk;
    const body = JSON.parse(raw), { prompt, results } = inspectModel(body);
    assert.equal(request.url, '/v1/chat/completions'); assert.ok(teamProtocol.matches(body));
    if (prompt.includes('阶段 kind=SUBAGENT') && prompt.includes('委派目标：TEAM_SMOKE_WRITE') && !results.length) pendingModelResponses.push(response);
    const message = await teamProtocol.message(body);
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ id: `standalone-team-${teamProtocol.calls.length}`, model: 'fixture', choices: [{ message, finish_reason: message.tool_calls ? 'tool_calls' : 'stop' }], usage: { prompt_tokens: 10, completion_tokens: 5 } }));
  } catch (error) {
    modelErrors.push(error.message); response.writeHead(500, { 'Content-Type': 'application/json' }); response.end(JSON.stringify({ error: { message: error.message } }));
  }
});
const contentTypes = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
const web = createServer(async (request, response) => {
  try {
    const route = new URL(request.url, 'http://localhost').pathname;
    if (route.startsWith('/service/api/')) {
      const chunks = []; for await (const chunk of request) chunks.push(chunk);
      const upstream = await fetch(`${baseUrl}${request.url.slice('/service'.length)}`, {
        method: request.method,
        headers: { Authorization: request.headers.authorization || '', 'Content-Type': request.headers['content-type'] || 'application/json' },
        ...(!['GET', 'HEAD'].includes(request.method) ? { body: Buffer.concat(chunks) } : {}),
        signal: AbortSignal.timeout(5000),
      });
      response.writeHead(upstream.status, { 'Content-Type': upstream.headers.get('content-type') || 'application/json' });
      response.end(Buffer.from(await upstream.arrayBuffer()));
      return;
    }
    if (route === '/workbench-config.json') {
      response.writeHead(200, { 'Content-Type': 'application/json' });
      response.end(JSON.stringify({ apiBaseUrl: '/service' }));
      return;
    }
    if (!/^\/(?:index\.html|assets\/[a-zA-Z0-9_.-]+)?$/.test(route)) { response.writeHead(404); response.end(); return; }
    const file = join(root, 'web/dist', route === '/' ? 'index.html' : route);
    const extension = file.slice(file.lastIndexOf('.'));
    response.writeHead(200, { 'Content-Type': contentTypes[extension] || 'application/octet-stream' });
    response.end(readFileSync(file));
  } catch { response.writeHead(502); response.end(); }
});
try {
  for (let attempt = 0; attempt < 150; attempt++) {
    if (processError) throw processError;
    if (core.exitCode !== null) throw new Error(`核心提前退出（${core.exitCode}）：${logs}`);
    const port = logs.match(/Tomcat started on port (\d+)/)?.[1];
    if (port) { baseUrl = `http://127.0.0.1:${port}`; break; }
    await new Promise(resolveWait => setTimeout(resolveWait, 200));
  }
  if (!baseUrl) throw new Error('独立核心启动超时');
  const health = () => fetch(`${baseUrl}/api/health`, { headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(3000) });
  const api = async (path, body) => {
    const response = await fetch(`${baseUrl}/api${path}`, { method: body === undefined ? 'GET' : 'POST', headers: { Authorization: `Bearer ${token}`, 'Content-Type': 'application/json' }, ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(5000) });
    assert.equal(response.status, 200, `${path}: HTTP ${response.status}`);
    return response.json();
  };
  assert.equal((await health()).status, 200);
  assert.equal((await fetch(`${baseUrl}/api/state`)).status, 401);
  await new Promise(resolveListen => web.listen(0, '127.0.0.1', resolveListen));
  const webUrl = `http://127.0.0.1:${web.address().port}`;
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || (windows ? 'msedge' : undefined) });
  const page = await browser.newPage();
  const errors = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.goto(webUrl);
  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  assert.equal(await page.getByLabel('本机核心地址', { exact: true }).inputValue(), '/service');
  await page.getByLabel('访问令牌', { exact: true }).fill(token);
  await page.getByRole('button', { name: '连接并验证', exact: true }).click();
  await page.getByRole('dialog').waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('heading', { name: '让合适的 Agent，做合适的事' }).waitFor();
  assert.equal(await page.evaluate(() => typeof window.workbench), 'undefined');
  await page.reload();
  await page.getByRole('button', { name: '项目总览', exact: true }).waitFor();
  const projectDir = join(dir, '中文 standalone project');
  mkdirSync(projectDir);
  mkdirSync(join(projectDir, 'src'));
  writeFileSync(join(projectDir, 'README.md'), 'Standalone lifecycle fixture\n');
  writeFileSync(join(projectDir, 'src/greeting.txt'), 'hello\n');
  writeFileSync(join(projectDir, '.gitattributes'), '* text eol=lf\n');
  writeFileSync(join(projectDir, 'verify.mjs'), "import assert from 'node:assert/strict'; import {readFileSync} from 'node:fs'; assert.equal(process.env.WORKBENCH_TOKEN, undefined); assert.match(readFileSync('src/greeting.txt', 'utf8'), /^hello(?: agent)?\\n$/); console.log('standalone greeting verified');\n");
  for (const args of [['init'], ['config', 'user.name', 'Lifecycle fixture'], ['config', 'user.email', 'fixture@example.invalid'], ['add', '.'], ['commit', '-m', 'Create lifecycle fixture']]) execFileSync('git', ['-C', projectDir, ...args], { stdio: 'ignore', windowsHide: true });
  await new Promise(resolveListen => model.listen(0, '127.0.0.1', resolveListen));
  const project = await api('/projects', { path: projectDir });
  await page.reload();
  await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await page.getByLabel('新会话名称', { exact: true }).fill('独立浏览器持久会话');
  await page.getByRole('button', { name: '创建会话', exact: true }).click();
  await page.getByRole('tab', { name: '独立浏览器持久会话', exact: true }).waitFor();
  await page.getByLabel('协作笔记', { exact: true }).fill('通过真实同源代理写入并重载验证');
  await page.getByRole('button', { name: '保存笔记', exact: true }).click();
  await page.getByText('通过真实同源代理写入并重载验证', { exact: true }).waitFor();
  await page.reload(); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await page.getByText('通过真实同源代理写入并重载验证', { exact: true }).waitFor();
  const session = (await api(`/projects/${project.id}/sessions`)).sessions.find(item => item.title === '独立浏览器持久会话');
  assert.ok(session?.id);
  await page.getByRole('button', { name: '关闭会话 独立浏览器持久会话', exact: true }).click();
  await page.getByRole('tab', { name: '独立浏览器持久会话', exact: true }).waitFor({ state: 'hidden' });
  await page.getByRole('button', { name: '全局搜索会话', exact: true }).click();
  await page.getByLabel('搜索会话记录', { exact: true }).fill('同源代理');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await page.locator('.session-search-result').click();
  const located = page.locator('.session-records .search-target');
  await located.waitFor({ state: 'visible' });
  assert.ok((await located.innerText()).includes('通过真实同源代理写入并重载验证'));
  assert.equal((await api(`/projects/${project.id}/sessions/${session.id}`)).isOpen, true);
  await page.getByRole('button', { name: '返回最新记录', exact: true }).click();
  const route = await api('/routes', { name: 'Local lifecycle fixture', engine: 'API', model: 'fixture', baseUrl: `http://127.0.0.1:${model.address().port}/v1` });
  const childRoute = await api('/routes', { name: 'Explicit child lifecycle route', engine: 'API', model: 'fixture', baseUrl: route.baseUrl });
  const developer = (await api('/agent-definitions')).find(definition => definition.id === 'agent-developer');
  await api('/agent-definitions', { ...developer, expectedVersion: developer.version, routeId: childRoute.id });
  const mcp = await configureTeamMcp(api, project.id, `http://127.0.0.1:${model.address().port}/team-mcp`);
  const definitions = (await api(`/agent-definitions?projectId=${project.id}`)).filter(definition => ['agent-developer', 'agent-reviewer'].includes(definition.id));
  const task = await api('/tasks', { projectId: project.id, sessionId: session.id, routeId: route.id, title: '客户端退出不中断', goal: teamGoal, mode: 'LEAD', locks: { engine: 'API', model: 'fixture' }, contract: { scope: ['**'], acceptance: ['核心继续处理在途副请求，按依赖采纳并保留原始来源'], commands: ['node verify.mjs'] }, budget: { maxCalls: 40, reserveCalls: 6 }, collaboration: { enabled: true, definitionIds: definitions.map(definition => definition.id), definitionVersions: Object.fromEntries(definitions.map(definition => [definition.id, definition.version])), allowedRouteIds: [childRoute.id], maxActive: 3, maxDepth: 2, maxTotal: 2 }, mcpBindings: [mcp.binding], mcpBudget: { maxCalls: 2, maxDurationMs: 10000 } });
  await api(`/tasks/${task.id}/team/messages`, { requestId: 'standalone_team_note', content: userNote });
  await api(`/tasks/${task.id}/approve`, {});
  for (let attempt = 0; attempt < 100 && !pendingModelResponses.length; attempt++) await new Promise(resolveWait => setTimeout(resolveWait, 100));
  assert.equal(pendingModelResponses.length, 1, '副任务必须实际进入模型请求，不能只验证空闲核心');
  let pendingTeam;
  for (let attempt = 0; attempt < 150; attempt++) {
    pendingTeam = await api(`/tasks/${task.id}/team`);
    if (pendingTeam.agents.length === 2 && pendingTeam.runs.some(run => run.kind === 'IMPLEMENT' && run.state === 'WAITING_CHILDREN')) break;
    await new Promise(resolveWait => setTimeout(resolveWait, 100));
  }
  const pendingWriter = pendingTeam.agents.find(agent => agent.title === writerTitle), pendingReader = pendingTeam.agents.find(agent => agent.title === readerTitle);
  assert.ok(pendingWriter && pendingReader); assert.equal(pendingWriter.state, 'RUNNING'); assert.equal(pendingReader.state, 'WAITING_DEPENDENCY');
  assert.ok(pendingTeam.runs.some(run => run.kind === 'IMPLEMENT' && run.state === 'WAITING_CHILDREN'));
  assert.equal((await api('/state')).system.activeModels, 1, '等待中的父运行已释放模型容量，只有实际子 HTTP 请求仍占用一个容量');
  const runPanel = page.getByRole('region', { name: 'Agent 运行记录' });
  const writerRun = runPanel.locator('.agent-run').filter({ has: page.getByRole('heading', { name: writerTitle, exact: true }) });
  await writerRun.getByRole('button', { name: '查看来源任务', exact: true }).click();
  await page.getByRole('region', { name: '固定委派约定' }).getByText(`实现副 Agent · v${definitions.find(definition => definition.id === 'agent-developer').version}`, { exact: true }).waitFor();
  await page.getByRole('button', { name: '团队与副 Agent', exact: true }).click();
  const teamPanel = page.getByRole('region', { name: '团队与副 Agent' });
  const pendingWriterCard = teamPanel.locator('.team-agent').filter({ has: page.getByRole('heading', { name: writerTitle, exact: true }) });
  await pendingWriterCard.getByText('查看实际权限与来源', { exact: true }).click(); await pendingWriterCard.getByText(pendingWriter.requestId, { exact: true }).waitFor();
  await teamPanel.getByText('等待前置副任务', { exact: true }).waitFor();
  await page.getByRole('button', { name: '模型请求', exact: true }).click();
  const tracePanel = page.getByRole('region', { name: '模型请求追踪' });
  await tracePanel.getByText(`subagent-${pendingWriter.id} · 第 1 次 API 请求`, { exact: true }).waitFor();
  const requestTrace = (await api(`/tasks/${task.id}/model-requests`)).requests;
  const childRequest = requestTrace.find(request => request.agentRunId === pendingWriter.agentRunId);
  assert.ok(childRequest); assert.equal(childRequest.state, 'DISPATCHING'); assert.equal(childRequest.subtaskId, pendingWriter.id);
  await page.getByRole('button', { name: '返回会话', exact: true }).click();

  await page.getByRole('button', { name: '关闭会话 独立浏览器持久会话', exact: true }).click();
  await page.getByRole('tab', { name: '独立浏览器持久会话', exact: true }).waitFor({ state: 'hidden' });
  assert.equal((await api(`/tasks/${task.id}`)).state, 'RUNNING', '关闭会话标签不能停止运行中的任务');
  assert.equal((await api(`/projects/${project.id}/sessions/${session.id}`)).isOpen, false);
  await browser.close(); browser = undefined;
  assert.equal((await health()).status, 200, '关闭浏览器后 API 继续运行');
  assert.equal((await api(`/tasks/${task.id}`)).state, 'RUNNING', '关闭浏览器后在途任务仍运行');
  const env = { ...process.env, JAVA_HOME: join(dir, 'missing-java'), WORKBENCH_JAVA_HOME: join(dir, 'missing-java'), WORKBENCH_USER_DATA: join(dir, 'desktop'), WORKBENCH_CORE_URL: baseUrl, WORKBENCH_CORE_TOKEN: token };
  const callsBeforeReconnect = teamProtocol.calls.length;
  delete env.ELECTRON_RUN_AS_NODE;
  desktop = await _electron.launch({ args: [root, ...(!windows && process.platform === 'linux' ? ['--no-sandbox'] : [])], executablePath: createRequire(import.meta.url)('electron'), env, timeout: 30000, dumpio: true });
  const window = await desktop.firstWindow();
  window.on('pageerror', error => errors.push(error.message));
  await window.getByRole('button', { name: '项目总览', exact: true }).waitFor();
  const connected = await window.evaluate(async () => {
    const connection = await window.workbench.getConnection();
    return { baseUrl: connection.baseUrl, status: (await fetch(`${connection.baseUrl}/api/health`, { headers: { Authorization: `Bearer ${connection.token}` } })).status };
  });
  assert.deepEqual(connected, { baseUrl, status: 200 });
  await desktop.close(); desktop = undefined;
  assert.equal((await health()).status, 200, '退出附加模式桌面后 API 继续运行');
  assert.equal((await api(`/tasks/${task.id}`)).state, 'RUNNING', '退出附加模式桌面不能暂停或取消已有任务');
  assert.equal(pendingModelResponses.length, 1, '客户端重连不能重复派发模型请求');
  assert.equal(teamProtocol.calls.length, callsBeforeReconnect, '父执行者等待子任务时，客户端重连不能重放已有请求');
  assert.equal(existsSync(join(dir, 'desktop/core')), false, '附加模式不创建嵌入核心');
  assert.deepEqual(errors, []);
  teamProtocol.releaseWriter();
  let finished;
  for (let attempt = 0; attempt < 600; attempt++) {
    finished = await api(`/tasks/${task.id}`);
    if (finished.state === 'AWAITING_ACCEPTANCE') break;
    assert.ok(!['FAILED', 'UNKNOWN', 'WAITING_INPUT'].includes(finished.state), `${finished.state}: ${finished.error}`);
    await new Promise(resolveWait => setTimeout(resolveWait, 100));
  }
  const observed = await verifyTeamResult(api, finished, teamProtocol);
  assert.equal(readFileSync(join(projectDir, 'src/greeting.txt'), 'utf8'), 'hello\n');
  assert.deepEqual(modelErrors, []);
  browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || (windows ? 'msedge' : undefined) });
  const resultPage = await browser.newPage(); resultPage.on('pageerror', error => errors.push(error.message)); await resultPage.goto(webUrl);
  await resultPage.getByRole('button', { name: '连接设置', exact: true }).click(); await resultPage.getByLabel('访问令牌', { exact: true }).fill(token); await resultPage.getByRole('button', { name: '连接并验证', exact: true }).click(); await resultPage.getByRole('dialog').waitFor({ state: 'hidden' });
  await resultPage.getByRole('button', { name: '研发任务', exact: true }).click(); await resultPage.getByRole('button', { name: /客户端退出不中断/ }).click();
  await resultPage.locator('.mcp-source').getByText(observed.writer.agentRunId, { exact: true }).waitFor();
  await resultPage.locator('.mcp-source').getByText(observed.mcp[0].modelRequestId, { exact: true }).waitFor();
  await resultPage.locator('.mcp-source').getByText(observed.mcp[0].toolCallId, { exact: true }).waitFor();
  await resultPage.getByRole('button', { name: '团队与副 Agent', exact: true }).click();
  const resultPanel = resultPage.getByRole('region', { name: '团队与副 Agent' }), writerCard = resultPanel.locator('.team-agent').filter({ has: resultPage.getByRole('heading', { name: writerTitle, exact: true }) });
  await writerCard.getByText('父 Agent 已核对采纳', { exact: false }).waitFor(); await writerCard.getByText('查看实际权限与来源', { exact: true }).click(); await writerCard.getByText(observed.writer.integrations[0].operationId, { exact: true }).waitFor();
  await writerCard.getByRole('button', { name: '查看副任务请求' }).click(); await writerCard.getByRole('region', { name: '模型请求追踪' }).getByText(`subagent-${observed.writer.id} · 第 1 次 API 请求`, { exact: true }).waitFor();
  await resultPage.locator('.toast').waitFor({ state: 'hidden' });
  await resultPage.evaluate(() => window.scrollTo(0, 0));
  await resultPage.screenshot({ path: join(root, `web/qa/team-real-${process.platform}.png`), fullPage: true });
  const messagePanel = resultPanel.getByRole('region', { name: '团队消息' }); await messagePanel.getByLabel('团队消息', { exact: true }).fill('通过真实代理保存的用户团队资料'); await messagePanel.getByRole('button', { name: '发送团队消息', exact: true }).click(); await messagePanel.getByRole('status').getByText('消息已保存').waitFor();
  await resultPage.reload(); await resultPage.getByRole('button', { name: '研发任务', exact: true }).click(); await resultPage.getByRole('button', { name: /客户端退出不中断/ }).click(); await resultPage.getByRole('button', { name: '团队与副 Agent', exact: true }).click(); await resultPage.getByRole('region', { name: '团队消息' }).getByText('通过真实代理保存的用户团队资料', { exact: true }).waitFor();
  assert.equal((await api(`/tasks/${task.id}/team/messages`)).messages.filter(message => message.content === '通过真实代理保存的用户团队资料' && message.sender === 'USER').length, 1);
  await api(`/tasks/${task.id}/accept`, {}); assert.equal((await api(`/tasks/${task.id}`)).state, 'COMPLETED');
  assert.deepEqual(errors, []);
  const report = { date: new Date().toISOString(), version, platform: process.platform, coreIndependentOfWebAndNpm: true, runtimeProxyConfiguration: true, browserWorkflowWithoutBridge: true, sessionWorkflowThroughProxy: true, sessionSearchAndReopenThroughProxy: true, agentRunActivityThroughProxy: true, modelRequestTraceThroughProxy: true, savedNotesSurviveReload: true, sessionClosePreservesRunningTask: true, browserClosePreservesCore: true, desktopUsesExistingCoreWithoutJava: true, desktopExitPreservesCore: true, clientExitPreservesRunningTask: true, modelRequestsBeforeAndAfterReconnect: pendingModelResponses.length, childRequestPreservedOnReconnect: childRequest.id, parentWaitReleasesModelCapacity: true, teamDependencyAdoptionThroughProxy: true, teamMessagesSurviveReload: true, mcpActualChildSourceThroughProxy: true, teamRootCalls: finished.budget.usedCalls, teamChildRuns: observed.team.agents.map(agent => ({ id: agent.id, agentRunId: agent.agentRunId, state: agent.state, integrationState: agent.integrationState })), externalMcpCalls: teamProtocol.mcpCalls.length, pageErrors: errors, modelErrors };
  mkdirSync(join(root, 'docs/qa'), { recursive: true });
  writeFileSync(join(root, `docs/qa/standalone-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
  teamProtocol.releaseWriter();
  if (browser) await browser.close();
  if (desktop) await desktop.close();
  for (const response of pendingModelResponses) response.destroy();
  model.closeAllConnections();
  await new Promise(resolveClose => model.close(resolveClose));
  await new Promise(resolveClose => web.close(resolveClose));
  if (baseUrl) { try { await fetch(`${baseUrl}/api/shutdown`, { method: 'POST', headers: { Authorization: `Bearer ${token}` }, signal: AbortSignal.timeout(2000) }); } catch {} }
  for (let attempt = 0; attempt < 100 && core.exitCode === null; attempt++) await new Promise(resolveWait => setTimeout(resolveWait, 100));
  if (core.exitCode === null) core.kill();
}
