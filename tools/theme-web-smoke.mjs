import assert from 'node:assert/strict';
import { createHash } from 'node:crypto';
import { createServer } from 'node:http';
import { createRequire } from 'node:module';
import { copyFileSync, existsSync, mkdirSync, readFileSync, statSync, writeFileSync } from 'node:fs';
import { dirname, extname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const root = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const { chromium } = createRequire(join(root, 'web', 'package.json'))('@playwright/test');
const metadata = JSON.parse(readFileSync(join(root, 'package.json'), 'utf8'));
const assets = join(root, 'web', 'dist'), html = readFileSync(join(assets, 'index.html'), 'utf8');
const moduleTag = html.match(/<script\b[^>]*type="module"[^>]*>/)?.[0];
const entry = moduleTag?.match(/src="([^"]+)"/)?.[1]; assert.ok(entry, 'Production module entry is required');
assert.match(html, /script-src 'self';/); assert.ok(!html.includes("script-src 'self' 'unsafe-inline'"));
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
assert.equal(hash(readFileSync(join(assets, 'theme-init.js'))), hash(readFileSync(join(root, 'web', 'public', 'theme-init.js'))));
const directory = join(root, '.runtime', `themes-web-${Date.now()}`), reports = join(directory, 'reports');
mkdirSync(reports, { recursive: true });
const save = (file, value) => writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
const contentTypes = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
const server = createServer((request, response) => {
  const pathname = new URL(request.url, 'http://localhost').pathname;
  if (pathname === '/api/state') {
    response.setHeader('Content-Type', 'application/json');
    response.end(JSON.stringify({ projects: [], tasks: [], routes: [], profiles: [], memories: [], system: { version: metadata.version, storageHealthy: true } }));
    return;
  }
  let file;
  try { file = resolve(assets, `.${decodeURIComponent(pathname === '/' ? '/index.html' : pathname)}`); }
  catch { response.writeHead(400); response.end(); return; }
  if (!file.startsWith(assets + '/') && !file.startsWith(assets + '\\') || !existsSync(file) || !statSync(file).isFile()) { response.writeHead(404); response.end(); return; }
  response.setHeader('Content-Type', contentTypes[extname(file)] || 'application/octet-stream');
  response.end(readFileSync(file));
});
await new Promise((done, reject) => { server.once('error', reject); server.listen(0, '127.0.0.1', done); });
const url = `http://127.0.0.1:${server.address().port}`, entryPath = new URL(entry, `${url}/`).pathname;
const cases = [], record = { status: 'running', version: metadata.version, platform: process.platform, scope: 'PRODUCTION_WEB_BYTES_WITH_LOOPBACK_UI_FIXTURE', directory,
  bootstrapSha256: hash(readFileSync(join(assets, 'theme-init.js'))), htmlSha256: hash(Buffer.from(html)), moduleEntry: entry, cases };
save(join(reports, 'report.json'), record);
const browser = await chromium.launch({ headless: true, channel: process.env.PLAYWRIGHT_CHANNEL || (process.platform === 'win32' ? 'msedge' : undefined) });
try {
  for (const scenario of ['saved-dark', 'system', 'blocked-storage', 'quota']) {
    const context = await browser.newContext({ colorScheme: scenario === 'system' ? 'dark' : 'light', viewport: { width: 1440, height: 1000 } });
    const errors = [], page = await context.newPage(); page.on('pageerror', error => errors.push(error.message));
    const current = { scenario, pageErrors: errors }; cases.push(current);
    let release = () => {};
    try {
      if (scenario === 'saved-dark') await context.addInitScript(() => localStorage.setItem('workbench-theme', 'ink-night-blue'));
      if (scenario === 'blocked-storage') await context.addInitScript(() => {
        for (const name of ['localStorage', 'sessionStorage']) Object.defineProperty(window, name, { get() { throw new DOMException('Browser storage blocked by the production fixture', 'SecurityError'); } });
      });
      if (scenario === 'quota') await context.addInitScript(() => { Storage.prototype.setItem = () => { throw new DOMException('Browser storage quota fixture', 'QuotaExceededError'); }; });
      const pending = new Promise(done => { release = done; });
      await page.route(request => request.pathname === entryPath, async route => { await pending; await route.continue(); });
      await page.goto(url, { waitUntil: 'commit' });
      const expected = scenario === 'saved-dark' ? 'ink-night-blue' : scenario === 'system' ? 'ink-night' : 'celadon';
      await page.waitForFunction(theme => document.documentElement.dataset.theme === theme, expected);
      assert.equal(await page.locator('#root').textContent(), '');
      assert.equal(await page.evaluate(() => typeof window.__workbenchTheme), 'object');
      const background = await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor);
      assert.equal(background, expected === 'ink-night-blue' ? 'rgb(23, 34, 56)' : expected === 'ink-night' ? 'rgb(20, 30, 25)' : 'rgb(245, 247, 246)');
      current.beforeReact = { theme: expected, background, rootEmpty: true };
      await page.screenshot({ path: join(reports, `${scenario}-before-react.png`) });
      release();
      await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
      await page.getByRole('button', { name: '资源与设置', exact: true }).click();
      await page.getByRole('button', { name: '外观', exact: true }).click();
      if (scenario === 'system') {
        await page.emulateMedia({ colorScheme: 'light' });
        await page.waitForFunction(() => document.documentElement.dataset.theme === 'celadon');
        current.systemChangeApplied = true;
      }
      await page.getByRole('button', { name: '墨夜蓝', exact: true }).click();
      await page.waitForFunction(() => document.documentElement.dataset.theme === 'ink-night-blue');
      if (scenario === 'blocked-storage' || scenario === 'quota') {
        assert.ok((await page.getByRole('status').textContent()).includes('本次窗口仍可切换主题'));
        current.inMemoryFallback = true;
        await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
        await page.waitForFunction(() => document.documentElement.dataset.theme === 'ink-night-blue');
        assert.ok((await page.getByRole('status').textContent()).includes('本次窗口仍可切换主题'));
        current.inMemoryPreservedAfterPageShowEvent = true;
      } else {
        await page.reload(); await page.waitForFunction(() => document.documentElement.dataset.theme === 'ink-night-blue');
        await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
        current.persistedAfterReload = true;
      }
      assert.deepEqual(errors, []);
      current.status = 'passed';
    } finally { release(); await context.close(); save(join(reports, 'report.json'), record); }
  }
  record.status = 'passed';
} catch (error) { record.status = 'failed'; record.error = { name: error.name, message: error.message, stack: error.stack }; throw error; }
finally {
  await browser.close(); await new Promise(done => server.close(done));
  record.completedAt = new Date().toISOString(); record.sourceSha256 = hash(readFileSync(fileURLToPath(import.meta.url)));
  copyFileSync(fileURLToPath(import.meta.url), join(directory, 'fixture-source.mjs'));
  save(join(reports, 'report.json'), record);
  mkdirSync(join(root, 'docs', 'qa'), { recursive: true }); save(join(root, 'docs', 'qa', `themes-web-${process.platform}.json`), record);
}
console.log(JSON.stringify({ status: record.status, version: record.version, scenarios: cases.length, directory }));
