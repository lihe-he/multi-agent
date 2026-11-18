import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { createServer } from 'node:http';
import { cpSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } from 'node:fs';
import { createRequire } from 'node:module';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(fileURLToPath(new URL('..', import.meta.url))), runtime = join(root, '.runtime');
mkdirSync(runtime, { recursive: true }); const directory = mkdtempSync(join(runtime, 'page-recovery-'));
const require = createRequire(join(root, 'web/package.json')), { chromium, expect } = require('@playwright/test');
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const inventory = (folder, relative = '', result = []) => {
  for (const entry of readdirSync(join(folder, relative), { withFileTypes: true })) {
    const file = relative ? `${relative}/${entry.name}` : entry.name;
    if (entry.isDirectory()) inventory(folder, file, result);
    else result.push({ file, bytes: readFileSync(join(folder, file)).length, sha256: hash(readFileSync(join(folder, file))) });
  }
  return result;
};
const dist = resolve(root, process.env.WORKBENCH_PAGE_TEST_DIST || 'web/dist');
cpSync(dist, join(directory, 'dist'), { recursive: true });
const assets = inventory(join(directory, 'dist')), assetMap = new Map(assets.map(asset => [`/${asset.file}`, asset]));
const sources = ['package.json', 'web/package.json', 'tools/page-recovery-smoke.mjs', ...inventory(join(root, 'web/src')).map(value => `web/src/${value.file}`)].map(file => {
  const bytes = readFileSync(join(root, file)), target = join(directory, 'sources', file);
  mkdirSync(dirname(target), { recursive: true }); writeFileSync(target, bytes, { flag: 'wx' });
  return { file, bytes: bytes.length, sha256: hash(bytes) };
});
const token = `page-fixture-${randomUUID()}`, requests = [], checks = [], failures = [], cacheMisses = [];
const version = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8')).version;
assert.equal(version, JSON.parse(readFileSync(join(root, 'web/package.json'), 'utf8')).version);
let phase = 0, stateReads = 0, browser, page, failurePage;
const sessions = ['first', 'chosen'].map(id => ({ id, projectId: 'page-project', title: id === 'chosen' ? '选中的持久会话' : '另一个会话', version: 1, isOpen: true, createdAt: '2026-11-18T00:00:00Z', updatedAt: '2026-11-18T00:00:00Z' }));
const message = (id, content) => ({ id: `note-${id}`, projectId: 'page-project', sessionId: 'chosen', kind: 'NOTE', sequence: id, content, createdAt: '2026-11-18T00:00:00Z' });
const event = id => ({ id, type: 'ACTIVITY', message: `实际续读活动${id}`, createdAt: '2026-11-18T00:00:00Z' });
const server = createServer((request, response) => {
  const url = new URL(request.url, 'http://127.0.0.1'), path = url.pathname;
  const send = (status, data, type = 'application/json') => { response.writeHead(status, { 'Content-Type': type, 'Cache-Control': 'private, no-cache' }); response.end(type === 'application/json' ? JSON.stringify(data) : data); };
  if (path === '/away') { send(200, '<!doctype html><html><title>独立导航页面</title><body>页面已离开工作台</body></html>', 'text/html; charset=utf-8'); return; }
  if (path.startsWith('/api/')) {
    const authorized = request.headers.authorization === `Bearer ${token}`;
    requests.push({ at: new Date().toISOString(), method: request.method, path: `${path}${url.search}`, authorized, phase });
    if (!authorized) { send(401, {}); return; }
    if (request.method !== 'GET') { send(409, { message: '恢复验收仅允许读取原状态' }); return; }
    if (path === '/api/state') {
      stateReads++; send(200, { projects: [{ id: 'page-project', name: phase ? `返回后的项目${phase}` : '原项目', path: 'fixture', branch: 'main', fileCount: 0 }], tasks: [], routes: [], profiles: [], memories: [], system: {} }); return;
    }
    if (path === '/api/projects/page-project/sessions') { send(200, { sessions, cursor: 0, hasMore: false }); return; }
    if (path.endsWith('/messages')) {
      if (url.searchParams.has('before')) { send(200, { messages: [message(1, '更早的持久记录')], cursor: 0, latestCursor: 52 + phase, hasMore: false }); return; }
      send(200, { messages: [message(51, '原笔记51'), message(52, '原笔记52'), ...Array.from({ length: phase }, (_, index) => message(53 + index, `缓存返回新增笔记${index + 1}`))], cursor: 51, latestCursor: 52 + phase, hasMore: phase === 0 }); return;
    }
    if (path.endsWith('/events')) { send(200, { events: Array.from({ length: phase + 1 }, (_, index) => event(70 + index)), cursor: 70 + phase, hasMore: false }); return; }
    if (path.endsWith('/agent-runs')) { send(200, { runs: [], cursor: 0, hasMore: false }); return; }
    if (path.endsWith('/preview')) { send(200, { version: 1, allowedLoopbackPorts: [], tabs: [], activeTabId: '' }); return; }
    send(200, {}); return;
  }
  const asset = assetMap.get(path === '/' ? '/index.html' : path);
  if (!asset) { send(404, {}); return; }
  const bytes = readFileSync(join(directory, 'dist', asset.file)); assert.equal(hash(bytes), asset.sha256);
  response.writeHead(200, { 'Content-Type': { '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8', '.css': 'text/css; charset=utf-8', '.json': 'application/json' }[extname(asset.file)] || 'application/octet-stream', 'Cache-Control': 'private, no-cache' }); response.end(bytes);
});
await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
const baseUrl = `http://127.0.0.1:${server.address().port}`, channel = process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : 'chromium');
try {
  browser = await chromium.launch({ headless: true, channel, ignoreDefaultArgs: ['--disable-back-forward-cache'] });
  const context = await browser.newContext({ viewport: { width: 1440, height: 1000 } });
  await context.addInitScript(value => {
    sessionStorage.setItem('workbench-token', value);
    window.__pageRecoveryProbe = { documentId: crypto.randomUUID(), restores: [], hides: [] };
    window.addEventListener('pageshow', event => window.__pageRecoveryProbe.restores.push({ persisted: event.persisted, at: Date.now() }));
    window.addEventListener('pagehide', event => window.__pageRecoveryProbe.hides.push({ persisted: event.persisted, at: Date.now() }));
  }, token);
  page = await context.newPage(); page.on('pageerror', error => failures.push(error.message));
  const session = await context.newCDPSession(page); await session.send('Page.enable');
  session.on('Page.backForwardCacheNotUsed', value => cacheMisses.push(value));
  await page.goto(baseUrl); await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await page.getByRole('tab', { name: '选中的持久会话', exact: true }).click();
  await page.getByRole('button', { name: '加载更早记录', exact: true }).click();
  await expect(page.getByText('更早的持久记录', { exact: true })).toBeVisible();
  await page.getByLabel('协作笔记', { exact: true }).fill('真实返回缓存中的未提交笔记');
  await page.getByLabel('当前会话名称', { exact: true }).fill('未提交的持久会话名称');
  const originalDocument = await page.evaluate(() => window.__pageRecoveryProbe.documentId);
  await page.screenshot({ path: join(directory, 'before-cache.png'), fullPage: true });
  for (const cycle of [1, 2]) {
    const precedingRead = stateReads; await expect.poll(() => stateReads).toBeGreaterThan(precedingRead);
    const requestIndex = requests.length;
    await page.goto(`${baseUrl}/away`); phase = cycle;
    const requestsWhileAway = requests.length;
    await page.evaluate(() => new Promise(done => setTimeout(done, 100)));
    assert.equal(requests.length, requestsWhileAway, 'A cached page must not continue API polling.');
    const started = Date.now(); await page.goBack({ waitUntil: 'commit' });
    const probe = await page.evaluate(() => window.__pageRecoveryProbe);
    assert.equal(probe.documentId, originalDocument, 'The navigation must restore the original document, not reload it.');
    assert.equal(probe.restores.at(-1).persisted, true, 'The browser must report an actual BFCache restore.');
    await expect(page.getByLabel('选择项目').locator('option')).toHaveText([`返回后的项目${cycle}`], { timeout: 1500 });
    await expect(page.getByText(`缓存返回新增笔记${cycle}`, { exact: true })).toBeVisible({ timeout: 1500 });
    await expect(page.getByText(`实际续读活动${70 + cycle}`, { exact: true })).toBeVisible({ timeout: 1500 });
    const resumed = requests.slice(requestIndex).filter(request => request.phase === cycle);
    assert.ok(resumed.some(request => request.path.includes(`/sessions/chosen/messages?limit=50&after=${51 + cycle}`)));
    assert.ok(resumed.some(request => request.path.includes(`/sessions/chosen/events?after=${69 + cycle}`)));
    await expect(page.getByRole('tab', { name: '选中的持久会话', exact: true })).toHaveAttribute('aria-selected', 'true');
    await expect(page.getByText('更早的持久记录', { exact: true })).toBeVisible();
    await expect(page.getByText('原笔记52', { exact: true })).toHaveCount(1);
    await expect(page.getByText('实际续读活动70', { exact: true })).toHaveCount(1);
    await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('真实返回缓存中的未提交笔记');
    await expect(page.getByLabel('当前会话名称', { exact: true })).toHaveValue('未提交的持久会话名称');
    checks.push({ cycle, actualBFCacheRestore: true, documentIdUnchanged: true, restoredAt: probe.restores.at(-1).at, elapsedMs: Date.now() - started, originalMessageCursor: 51 + cycle, originalEventCursor: 69 + cycle, draftAndSelectionPreserved: true });
  }
  await page.screenshot({ path: join(directory, 'after-cache.png'), fullPage: true });
  assert.ok(requests.every(request => request.method === 'GET' && request.authorized)); assert.deepEqual(failures, []);
} catch (error) {
  failures.push(error.message);
  if (page) {
    failurePage = await page.evaluate(() => ({ url: location.href, probe: window.__pageRecoveryProbe })).catch(() => undefined);
    await page.screenshot({ path: join(directory, 'failure.png'), fullPage: true }).catch(() => {});
  }
} finally {
  await browser?.close(); server.closeAllConnections(); await new Promise(done => server.close(done));
}
const sourcesUnchanged = sources.every(source => hash(readFileSync(join(root, source.file))) === source.sha256);
const assetsUnchanged = assets.every(asset => hash(readFileSync(join(directory, 'dist', asset.file))) === asset.sha256 && hash(readFileSync(join(dist, asset.file))) === asset.sha256);
const report = { recordedAt: new Date().toISOString(), version, platform: process.platform, status: failures.length || checks.length !== 2 || !sourcesUnchanged || !assetsUnchanged ? 'failed' : 'passed',
  baseCommit: execFileSync('git', ['rev-parse', 'HEAD'], { cwd: root, windowsHide: true, encoding: 'utf8' }).trim(), directory, browserChannel: channel,
  browserArguments: { ignoredDefaultArgs: ['--disable-back-forward-cache'] }, fixtureCacheControl: 'private, no-cache', productionDist: dist, sources, assets, sourcesUnchanged, assetsUnchanged,
  checks, requests, failures, failurePage, cacheMisses, scope: 'ACTUAL_BROWSER_BFCACHE_ORIGINAL_DOCUMENT_RESTORE_WITH_FROZEN_PRODUCTION_WEB_AND_HTTP_PROTOCOL_FIXTURE; NO_REAL_CORE_OR_TERMINAL_OR_OS_SLEEP_ACCEPTANCE' };
writeFileSync(join(directory, 'receipt.json'), JSON.stringify(report, null, 2) + '\n');
console.log(JSON.stringify({ directory, status: report.status, checks: checks.length, failures, sourcesUnchanged, assetsUnchanged })); process.exitCode = report.status === 'passed' ? 0 : 1;
