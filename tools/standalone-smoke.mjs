import { execFileSync, spawn } from 'node:child_process';
import { randomBytes } from 'node:crypto';
import { createServer } from 'node:http';
import { existsSync, mkdtempSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import assert from 'node:assert/strict';
import { createRequire } from 'node:module';
import { javaHome } from './java-runtime.mjs';

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
const model = createServer((request, response) => {
  request.resume();
  request.on('end', () => pendingModelResponses.push(response));
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
  writeFileSync(join(projectDir, 'README.md'), 'Standalone lifecycle fixture\n');
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
  const task = await api('/tasks', { projectId: project.id, sessionId: session.id, routeId: route.id, title: '客户端退出不中断', goal: 'Verify client lifecycle while a real local model request is pending', mode: 'PIPELINE', contract: { scope: ['README.md'], acceptance: ['核心继续处理在途请求'], commands: ['git status --porcelain'] }, budget: { maxCalls: 12, reserveCalls: 4 } });
  await api(`/tasks/${task.id}/approve`, {});
  for (let attempt = 0; attempt < 100 && !pendingModelResponses.length; attempt++) await new Promise(resolveWait => setTimeout(resolveWait, 100));
  assert.equal(pendingModelResponses.length, 1, '任务必须实际进入模型请求，不能只验证空闲核心');
  const runs = (await api(`/tasks/${task.id}/agent-runs`)).runs;
  assert.equal(runs.length, 1); assert.equal(runs[0].state, 'RUNNING'); assert.equal(runs[0].calls, 1);
  const runPanel = page.getByRole('region', { name: 'Agent 运行记录' });
  await runPanel.getByText('方案与交接', { exact: true }).waitFor();
  await runPanel.getByRole('button', { name: '查看来源任务', exact: true }).click();
  await page.getByRole('button', { name: 'Agent 运行', exact: true }).click();
  await page.getByRole('region', { name: 'Agent 运行记录' }).getByText('方案与交接', { exact: true }).waitFor();
  await page.getByRole('button', { name: '返回会话', exact: true }).click();

  await page.getByRole('button', { name: '关闭会话 独立浏览器持久会话', exact: true }).click();
  await page.getByRole('tab', { name: '独立浏览器持久会话', exact: true }).waitFor({ state: 'hidden' });
  assert.equal((await api(`/tasks/${task.id}`)).state, 'RUNNING', '关闭会话标签不能停止运行中的任务');
  assert.equal((await api(`/projects/${project.id}/sessions/${session.id}`)).isOpen, false);
  await browser.close(); browser = undefined;
  assert.equal((await health()).status, 200, '关闭浏览器后 API 继续运行');
  assert.equal((await api(`/tasks/${task.id}`)).state, 'RUNNING', '关闭浏览器后在途任务仍运行');
  const env = { ...process.env, JAVA_HOME: join(dir, 'missing-java'), WORKBENCH_JAVA_HOME: join(dir, 'missing-java'), WORKBENCH_USER_DATA: join(dir, 'desktop'), WORKBENCH_CORE_URL: baseUrl, WORKBENCH_CORE_TOKEN: token };
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
  assert.equal(existsSync(join(dir, 'desktop/core')), false, '附加模式不创建嵌入核心');
  assert.deepEqual(errors, []);
  await api(`/tasks/${task.id}/cancel`, {});
  const report = { date: new Date().toISOString(), version, platform: process.platform, coreIndependentOfWebAndNpm: true, runtimeProxyConfiguration: true, browserWorkflowWithoutBridge: true, sessionWorkflowThroughProxy: true, sessionSearchAndReopenThroughProxy: true, agentRunActivityThroughProxy: true, savedNotesSurviveReload: true, sessionClosePreservesRunningTask: true, browserClosePreservesCore: true, desktopUsesExistingCoreWithoutJava: true, desktopExitPreservesCore: true, clientExitPreservesRunningTask: true, modelRequestsBeforeAndAfterReconnect: pendingModelResponses.length, pageErrors: errors };
  mkdirSync(join(root, 'docs/qa'), { recursive: true });
  writeFileSync(join(root, `docs/qa/standalone-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report, null, 2));
} finally {
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
