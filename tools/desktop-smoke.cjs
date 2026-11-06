const { _electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { mkdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

(async () => {
  const root = path.resolve(__dirname, '..');
  const env = { ...process.env, WORKBENCH_USER_DATA: path.join(root, '.runtime', `desktop-qa-${Date.now()}`) };
  if (process.env.WORKBENCH_EXPECT_PACKAGED === '1') {
    env.JAVA_HOME = path.join(root, '.runtime', 'intentionally-missing-java');
    env.WORKBENCH_JAVA_HOME = env.JAVA_HOME;
  }
  delete env.ELECTRON_RUN_AS_NODE;
  const errors = [];
  const electron = await _electron.launch({ args: [root, ...(process.platform === 'linux' ? ['--no-sandbox'] : [])], executablePath: process.env.WORKBENCH_ELECTRON_PATH, env, timeout: 60000, dumpio: true });
  try {
    const page = await electron.firstWindow({ timeout: 60000 });
    page.on('pageerror', error => errors.push(error.message));
    await page.waitForLoadState('domcontentloaded');
    await page.getByText('项目总览', { exact: true }).first().waitFor({ timeout: 15000 });
    await page.waitForTimeout(1500);
    const state = await page.evaluate(async () => {
      const connection = await window.workbench.getConnection();
      const response = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` } });
      return { status: response.status, state: await response.json() };
    });
    assert.equal(state.status, 200, 'Electron file origin must connect to the authenticated local core');
    assert.equal(state.state.system.storageHealthy, true);
    const application = await electron.evaluate(({ app }) => ({ packaged: app.isPackaged, version: app.getVersion() }));
    if (process.env.WORKBENCH_EXPECT_PACKAGED === '1') assert.equal(application.packaged, true, 'Check the installed application with its bundled Java runtime');
    mkdirSync(path.join(root, 'docs', 'qa'), { recursive: true });
    await page.screenshot({ path: path.join(root, 'docs', 'qa', 'desktop-home.png'), fullPage: true });
    assert.deepEqual(errors, []);
    const report = { desktop: 'PASS', api: state.status, version: state.state.system.version, application, externalJavaDisabled: process.env.WORKBENCH_EXPECT_PACKAGED === '1', pageErrors: errors };
    writeFileSync(path.join(root, 'docs', 'qa', application.packaged ? `desktop-packaged-${process.platform}.json` : `desktop-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
    console.log(JSON.stringify(report));
  } finally {
    let forced = false;
    const timer = setTimeout(() => {
      forced = true;
      try {
        if (process.platform === 'win32') execFileSync('taskkill.exe', ['/PID', String(electron.process().pid), '/T', '/F'], { stdio: 'ignore', windowsHide: true });
        else electron.process().kill('SIGKILL');
      } catch { }
    }, 25000);
    try { await electron.close(); } finally { clearTimeout(timer); }
    assert.equal(forced, false, 'Desktop must shut down without being forcibly stopped');
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
