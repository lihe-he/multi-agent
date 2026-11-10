const { _electron } = require(process.env.PLAYWRIGHT_MODULE || 'playwright');
const { mkdirSync, readFileSync, readdirSync, writeFileSync } = require('node:fs');
const { createHash } = require('node:crypto');
const path = require('node:path');
const assert = require('node:assert/strict');
const { execFileSync } = require('node:child_process');

(async () => {
  const root = path.resolve(__dirname, '..');
  const env = { ...process.env, WORKBENCH_USER_DATA: path.join(root, '.runtime', `desktop-qa-${Date.now()}`) };
  env.JAVA_TOOL_OPTIONS = '-Dfile.encoding=UTF-8 -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2';
  if (process.env.WORKBENCH_EXPECT_PACKAGED === '1') {
    env.JAVA_HOME = path.join(root, '.runtime', 'intentionally-missing-java');
    env.WORKBENCH_JAVA_HOME = env.JAVA_HOME;
  }
  delete env.ELECTRON_RUN_AS_NODE;
  const { javaHome } = await import('./java-runtime.mjs');
  const home = javaHome(root), windows = process.platform === 'win32';
  const java = path.join(home, 'bin', windows ? 'java.exe' : 'java'), classes = path.join(env.WORKBENCH_USER_DATA, 'probe-classes');
  mkdirSync(classes, { recursive: true });
  const source = path.join(root, 'tools', 'ProcessRecoveryProbe.java'), hash = bytes => createHash('sha256').update(bytes).digest('hex');
  execFileSync(path.join(home, 'bin', windows ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', classes, source], { encoding: 'utf8', windowsHide: true, timeout: 30000 });
  writeFileSync(path.join(classes, 'ProcessRecoveryProbe.java'), readFileSync(source));
  const probeFixture = { compiledOnce: true, sourceSha256: hash(readFileSync(source)), classes: readdirSync(classes).filter(file => file.endsWith('.class')).map(file => ({ file, sha256: hash(readFileSync(path.join(classes, file))) })) };
  writeFileSync(path.join(classes, 'fixture.json'), JSON.stringify(probeFixture, null, 2) + '\n');
  const probe = (pid, birth, mode = 'inspect') => JSON.parse(execFileSync(java, ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { encoding: 'utf8', windowsHide: true, timeout: 30000 }));
  const originals = new Map();
  const remember = identity => { assert.ok(identity.pid && identity.processStartedAt); originals.set(`${identity.pid}@${identity.processStartedAt}`, identity); };
  const errors = [];
  const electron = await _electron.launch({ args: [root, ...(process.platform === 'linux' ? ['--no-sandbox'] : [])], executablePath: process.env.WORKBENCH_ELECTRON_PATH, env, timeout: 60000, dumpio: true });
  let report;
  try {
    remember(probe(electron.process().pid, '-', 'identity'));
    const page = await electron.firstWindow({ timeout: 60000 });
    page.on('pageerror', error => errors.push(error.message));
    await page.waitForLoadState('domcontentloaded');
    await page.getByText('项目总览', { exact: true }).first().waitFor({ timeout: 15000 });
    await page.waitForTimeout(1500);
    const state = await page.evaluate(async () => {
      const connection = await window.workbench.getConnection();
      const response = await fetch(`${connection.baseUrl}/api/state`, { headers: { Authorization: `Bearer ${connection.token}` } });
      const workers = await fetch(`${connection.baseUrl}/api/workers`, { headers: { Authorization: `Bearer ${connection.token}` } });
      return { status: response.status, state: await response.json(), workersStatus: workers.status, workers: await workers.json() };
    });
    assert.equal(state.status, 200, 'Electron file origin must connect to the authenticated local core');
    assert.equal(state.workersStatus, 200); assert.ok(state.workers.length > 0);
    for (const worker of state.workers) {
      remember({ pid: worker.corePid, processStartedAt: worker.coreStartedAt });
      remember({ pid: worker.pid, processStartedAt: worker.processStartedAt });
    }
    assert.equal(state.state.system.storageHealthy, true);
    const application = await electron.evaluate(({ app }) => ({ packaged: app.isPackaged, version: app.getVersion() }));
    if (process.env.WORKBENCH_EXPECT_PACKAGED === '1') assert.equal(application.packaged, true, 'Check the installed application with its bundled Java runtime');
    mkdirSync(path.join(root, 'docs', 'qa'), { recursive: true });
    await page.screenshot({ path: path.join(root, 'docs', 'qa', 'desktop-home.png'), fullPage: true });
    assert.deepEqual(errors, []);
    report = { desktop: 'PASS', api: state.status, version: state.state.system.version, application, externalJavaDisabled: process.env.WORKBENCH_EXPECT_PACKAGED === '1', pageErrors: errors,
      schedulerParallelism: 2, schedulerMaxPoolSize: 2, processCoverage: 'OBSERVED_ONLY', originalProcessProbeCompiledOnce: true, probeFixture, probeEvidenceDirectory: classes };
  } finally {
    let forced = false;
    const timer = setTimeout(() => {
      forced = true;
      const attempts = [...originals.values()].reverse().map(identity => {
        try { return probe(identity.pid, identity.processStartedAt, 'stop'); }
        catch (error) { return { ...identity, originalStopped: false, error: error.message }; }
      });
      writeFileSync(path.join(classes, 'original-timeout-stop.json'), JSON.stringify(attempts, null, 2) + '\n');
    }, 25000);
    try { await electron.close(); } finally { clearTimeout(timer); }
    assert.equal(forced, false, 'Desktop must shut down without being forcibly stopped');
    const observations = [...originals.values()].map(identity => probe(identity.pid, identity.processStartedAt));
    writeFileSync(path.join(classes, 'original-process-stop.json'), JSON.stringify(observations, null, 2) + '\n');
    assert.ok(observations.length > 0 && observations.every(identity => identity.originalStopped), 'All recorded original desktop, core and Worker identities must be independently stopped');
    if (report) { report.originalIdentitiesVerifiedStopped = observations.length; report.originalProcessesStopped = true; report.originalProcessObservations = observations; }
  }
  writeFileSync(path.join(root, 'docs', 'qa', report.application.packaged ? `desktop-packaged-${process.platform}.json` : `desktop-${process.platform}.json`), JSON.stringify(report, null, 2) + '\n');
  console.log(JSON.stringify(report));
})().catch(error => { console.error(error); process.exitCode = 1; });
