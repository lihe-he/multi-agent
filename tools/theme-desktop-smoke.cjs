const { _electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');
const { createHash } = require('node:crypto');
const { copyFileSync, mkdirSync, readFileSync, readdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');

(async () => {
  const root = path.resolve(__dirname, '..');
  const metadata = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8'));
  const directory = path.join(root, '.runtime', `themes-desktop-${Date.now()}`);
  const reports = path.join(directory, 'reports'), classes = path.join(directory, 'probe-classes');
  mkdirSync(reports, { recursive: true }); mkdirSync(classes);
  const { javaHome } = await import('./java-runtime.mjs');
  const home = javaHome(root), windows = process.platform === 'win32';
  const java = path.join(home, 'bin', windows ? 'java.exe' : 'java');
  const source = path.join(root, 'tools', 'ProcessRecoveryProbe.java');
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  execFileSync(path.join(home, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', classes, source], { windowsHide: true, timeout: 30000 });
  copyFileSync(source, path.join(classes, 'ProcessRecoveryProbe.java'));
  const probeFixture = { compiledOnce: true, sourceSha256: hash(readFileSync(source)), classes: readdirSync(classes).filter(file => file.endsWith('.class')).map(file => ({ file, sha256: hash(readFileSync(path.join(classes, file))) })) };
  const probe = (pid, birth, mode = 'inspect') => JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { windowsHide: true, encoding: 'utf8', timeout: 30000 }));
  const env = { ...process.env, WORKBENCH_USER_DATA: path.join(directory, 'user-data'), WORKBENCH_JAVA_HOME: home,
    WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '',
    JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8 -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2' };
  if (process.env.WORKBENCH_EXPECT_PACKAGED === '1') env.JAVA_HOME = env.WORKBENCH_JAVA_HOME = path.join(directory, 'intentionally-missing-java');
  delete env.ELECTRON_RUN_AS_NODE;
  const save = (file, value) => writeFileSync(file, `${JSON.stringify(value, null, 2)}\n`);
  save(path.join(classes, 'fixture.json'), probeFixture);
  const checks = [], launches = [], verifiedStopped = new Map();
  const record = { version: metadata.version, platform: process.platform, status: 'running', scope: 'ACTUAL_DESKTOP_FILE_ORIGIN_THEME_PERSISTENCE', directory,
    probeFixture, processCoverage: 'OBSERVED_ONLY', externalJavaDisabled: process.env.WORKBENCH_EXPECT_PACKAGED === '1', checks, launches };
  save(path.join(reports, 'report.json'), record);
  try {
    for (let attempt = 1; attempt <= 2; attempt++) {
      const originals = new Map(), errors = [], observationsBeforeClose = [], historicalObservations = [], historicalKeys = new Set();
      const remember = identity => {
        assert.ok(identity.pid && identity.processStartedAt);
        const key = `${identity.pid}@${identity.processStartedAt}`;
        if (originals.has(key) || historicalKeys.has(key)) return;
        const observation = probe(identity.pid, identity.processStartedAt);
        if (verifiedStopped.has(key)) {
          assert.equal(observation.originalStopped, true, 'Previously verified original processes must remain stopped');
          historicalKeys.add(key); historicalObservations.push({ ...observation, verifiedAfterLaunch: verifiedStopped.get(key) });
          return;
        }
        assert.equal(observation.alive, true); assert.equal(observation.matchesOriginal, true);
        originals.set(key, identity); observationsBeforeClose.push(observation);
      };
      const electron = await _electron.launch({ args: [root, ...(process.platform === 'linux' ? ['--no-sandbox'] : [])], executablePath: process.env.WORKBENCH_ELECTRON_PATH, env, timeout: 60000 });
      const launch = { attempt, pageErrors: errors, originalProcessObservationsBeforeClose: observationsBeforeClose,
        historicalOriginalProcessObservations: historicalObservations }; launches.push(launch);
      try {
        remember(probe(electron.process().pid, '-', 'identity'));
        const page = await electron.firstWindow({ timeout: 60000 });
        page.on('pageerror', error => errors.push(error.message));
        await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor({ timeout: 30000 });
        const state = await page.evaluate(async () => {
          const connection = await window.workbench.getConnection();
          const response = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` } });
          const workers = await fetch(`${connection.baseUrl}/api/workers`, { headers: { Authorization: `Bearer ${connection.token}` } });
          return { status: response.status, state: await response.json(), workersStatus: workers.status, workers: await workers.json() };
        });
        assert.equal(state.status, 200); assert.equal(state.workersStatus, 200); assert.ok(state.workers.length > 0);
        assert.equal(state.state.system.version, metadata.version); assert.equal(state.state.system.storageHealthy, true);
        launch.workers = state.workers.map(worker => Object.fromEntries(['id', 'state', 'pid', 'processStartedAt', 'corePid', 'coreStartedAt'].map(key => [key, worker[key]])));
        for (const worker of state.workers) {
          remember({ pid: worker.corePid, processStartedAt: worker.coreStartedAt });
          remember({ pid: worker.pid, processStartedAt: worker.processStartedAt });
        }
        launch.application = await electron.evaluate(({ app }) => ({ packaged: app.isPackaged, version: app.getVersion() }));
        assert.equal(launch.application.version, metadata.version);
        if (record.externalJavaDisabled) assert.equal(launch.application.packaged, true);
        assert.ok(page.url().startsWith('file://'));
        assert.equal(await page.evaluate(() => typeof window.require), 'undefined');
        assert.equal(await page.locator('meta[http-equiv="Content-Security-Policy"]').getAttribute('content').then(value => value.includes("script-src 'self';")), true);
        if (attempt === 2) {
          await page.waitForFunction(() => document.documentElement.dataset.theme === 'ink-night-blue');
          assert.equal(await page.evaluate(() => localStorage.getItem('workbench-theme')), 'ink-night-blue');
          checks.push('Saved dark theme survived full original desktop/core/Worker shutdown and relaunch');
        }
        await page.getByRole('button', { name: '资源与设置', exact: true }).click();
        await page.getByRole('button', { name: '外观', exact: true }).click();
        if (attempt === 1) {
          await page.getByRole('button', { name: '跟随系统' }).click();
          await page.emulateMedia({ colorScheme: 'dark' });
          await page.waitForFunction(() => document.documentElement.dataset.theme === 'ink-night');
          await page.emulateMedia({ colorScheme: 'light' });
          await page.waitForFunction(() => document.documentElement.dataset.theme === 'celadon');
          launch.systemScheme = { source: 'ACTUAL_ELECTRON_RENDERER_MEDIA_EMULATION', dark: 'ink-night', light: 'celadon' };
          checks.push('Actual Electron renderer follows dark and light media changes');
        }
        const choices = attempt === 1 ? [['pure-white', '纯白', 'light'], ['paper-ink', '纸墨', 'light'], ['classic-warm', '经典暖色', 'light'],
          ['celadon', '青瓷', 'light'], ['ink-night', '墨夜', 'dark'], ['ink-night-blue', '墨夜蓝', 'dark']] : [['pure-white', '纯白', 'light']];
        for (const [id, name, scheme] of choices) {
          await page.getByRole('button', { name, exact: true }).click();
          await page.waitForFunction(id => document.documentElement.dataset.theme === id, id);
          assert.equal(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme), scheme);
          assert.equal(await page.evaluate(() => localStorage.getItem('workbench-theme')), id);
          const file = path.join(reports, `launch-${attempt}-${id}.png`);
          await page.screenshot({ path: file, fullPage: true });
          checks.push(`Launch ${attempt}: ${name} applied and persisted through actual file origin`);
        }
        if (attempt === 1) {
          await page.emulateMedia({ colorScheme: 'dark' });
          assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'ink-night-blue');
          await page.emulateMedia({ colorScheme: 'light' });
          assert.equal(await page.evaluate(() => document.documentElement.dataset.theme), 'ink-night-blue');
          checks.push('Explicit desktop theme remains selected across renderer media changes');
        }
        await page.reload();
        const expected = attempt === 1 ? 'ink-night-blue' : 'pure-white';
        await page.waitForFunction(id => document.documentElement.dataset.theme === id, expected);
        await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
        checks.push(`Launch ${attempt}: ${expected} restored after renderer reload`);
        assert.deepEqual(errors, []);
      } finally {
        let forced = false;
        const timer = setTimeout(() => {
          forced = true;
          save(path.join(reports, `launch-${attempt}-timeout-stop.json`), [...originals.values()].reverse().map(identity => {
            try { return probe(identity.pid, identity.processStartedAt, 'stop'); }
            catch (error) { return { ...identity, originalStopped: false, error: error.message }; }
          }));
        }, 25000);
        try { await electron.close(); } finally { clearTimeout(timer); }
        launch.originalProcesses = [...originals.values()].map(identity => probe(identity.pid, identity.processStartedAt));
        save(path.join(reports, `launch-${attempt}-original-stopped.json`), launch.originalProcesses);
        assert.equal(forced, false, 'Original desktop must close without forced termination');
        assert.ok(launch.originalProcesses.length >= 3 && launch.originalProcesses.every(identity => identity.originalStopped));
        for (const identity of launch.originalProcesses) verifiedStopped.set(`${identity.pid}@${identity.originalStartedAt}`, attempt);
        launch.originalProcessesStopped = true;
        save(path.join(reports, 'report.json'), record);
      }
    }
    record.status = 'passed'; record.originalProcessesStopped = true;
  } catch (error) {
    record.status = 'failed'; record.error = { name: error.name, message: error.message, stack: error.stack };
    throw error;
  } finally {
    record.completedAt = new Date().toISOString();
    record.sourceSha256 = hash(readFileSync(__filename));
    copyFileSync(__filename, path.join(reports, 'theme-desktop-smoke.cjs'));
    save(path.join(reports, 'report.json'), record);
    mkdirSync(path.join(root, 'docs', 'qa'), { recursive: true });
    save(path.join(root, 'docs', 'qa', `themes-${record.externalJavaDisabled ? 'packaged' : 'desktop'}-${process.platform}.json`), record);
  }
  console.log(JSON.stringify({ status: record.status, version: record.version, checks: checks.length, originalProcessesStopped: true, directory }));
})().catch(error => { console.error(error); process.exitCode = 1; });
