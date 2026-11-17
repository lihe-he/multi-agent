const assert = require('node:assert/strict');
const { _electron, expect } = require('../web/node_modules/@playwright/test');
const { execFileSync } = require('node:child_process');
const { createHash, randomBytes } = require('node:crypto');
const { createServer } = require('node:http');
const { copyFileSync, existsSync, mkdirSync, mkdtempSync, readFileSync, readdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');

(async () => {
  const root = path.resolve(__dirname, '..'), version = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')).version;
  const packaged = process.argv.slice(2).includes('--packaged');
  assert.ok(process.argv.slice(2).every(value => value === '--packaged'), 'Only --packaged is supported');
  mkdirSync(path.join(root, '.runtime'), { recursive: true });
  const directory = mkdtempSync(path.join(root, '.runtime', packaged ? 'preview-native-packaged-' : 'preview-native-'));
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  const save = (file, value) => writeFileSync(path.join(directory, file), JSON.stringify(value, null, 2) + '\n');
  const inputs = ['package.json', 'tools/preview-native-smoke.cjs', 'tools/terminal-fixture.mjs', 'tools/ProcessRecoveryProbe.java', 'desktop/main.cjs', 'desktop/preload.cjs', 'desktop/preview-host.cjs', 'desktop/preview-policy.cjs', 'desktop/preview-ipc-policy.cjs',
    'core/src/main/java/dev/agentworkbench/PreviewCatalog.java', 'core/src/main/java/dev/agentworkbench/PreviewController.java', 'web/src/SessionWorkspace.tsx', 'web/src/App.tsx',
    ...readdirSync(path.join(root, 'web/dist/assets')).map(file => `web/dist/assets/${file}`), `core/target/agent-workbench-core-${version}.jar`]
    .map(file => ({ file, sha256: hash(readFileSync(path.join(root, file))) }));
  copyFileSync(__filename, path.join(directory, 'runner-source.cjs'));
  const { javaHome } = await import('./java-runtime.mjs');
  let artifactInputs, launchEnvironment = {}, executable = process.env.WORKBENCH_ELECTRON_PATH;
  let home = javaHome(root), java = path.join(home, 'bin', process.platform === 'win32' ? 'java.exe' : 'java'), classes = path.join(directory, 'probe-classes');
  if (packaged) {
    const { freezeTerminalArtifacts, terminalCoreEnvironment } = await import('./terminal-fixture.mjs');
    copyFileSync(path.join(root, 'tools/terminal-fixture.mjs'), path.join(directory, 'artifact-fixture-source.mjs'));
    artifactInputs = freezeTerminalArtifacts(directory, { withWeb: true, packaged: true });
    ({ java, javaRoot: home, probeClasses: classes } = artifactInputs);
    launchEnvironment = terminalCoreEnvironment(directory, true);
    const metadata = JSON.parse(readFileSync(path.join(root, 'package.json'), 'utf8')), resources = artifactInputs.core.resources;
    executable = process.platform === 'darwin' ? path.resolve(resources, '../MacOS', metadata.build.productName)
      : path.resolve(resources, '..', process.platform === 'win32' ? `${metadata.build.productName}.exe` : metadata.build.linux.executableName || metadata.name.toLowerCase());
    assert.ok(existsSync(executable), 'The verified packaged executable must exist');
    for (const file of [executable, path.join(resources, 'app.asar'), artifactInputs.core.originalJar,
      ...artifactInputs.core.packagedRuntimeFiles.map(value => path.join(home, value.file))]) inputs.push({ file, sha256: hash(readFileSync(file)) });
  } else {
    mkdirSync(classes);
    const probeSource = path.join(root, 'tools/ProcessRecoveryProbe.java');
    execFileSync(path.join(home, 'bin', process.platform === 'win32' ? 'javac.exe' : 'javac'), ['-encoding', 'UTF-8', '-d', classes, probeSource], { windowsHide: true, timeout: 30000 });
    copyFileSync(probeSource, path.join(classes, 'ProcessRecoveryProbe.java'));
  }
  const probe = (pid, birth = '-', mode = 'identity') => JSON.parse(execFileSync(java,
    ['-Dfile.encoding=UTF-8', '-cp', classes, 'ProcessRecoveryProbe', mode, String(pid), birth], { windowsHide: true, encoding: 'utf8', timeout: 30000 }));
  const source = path.join(directory, 'original preview project'); mkdirSync(source);
  writeFileSync(path.join(source, 'app.txt'), 'original preview source\n');
  const git = (...args) => execFileSync('git', ['-C', source, ...args], { windowsHide: true, encoding: 'utf8', timeout: 20000 }).trim();
  git('init', '--initial-branch=main'); git('config', 'user.name', 'Preview Fixture'); git('config', 'user.email', 'preview@example.invalid'); git('add', '.'); git('commit', '-m', 'Original preview fixture');
  const login = randomBytes(16).toString('hex'), requests = [], checks = [], launches = [], errors = [];
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1');
    requests.push({ path: url.pathname, callbackPresent: url.searchParams.has('code'), cookiePresent: Boolean(request.headers.cookie), authorizationPresent: Boolean(request.headers.authorization) });
    if (url.pathname === '/login' && url.searchParams.has('code')) response.setHeader('Set-Cookie', `preview-login=${login}; HttpOnly; SameSite=Lax; Path=/; Max-Age=3600`);
    if (url.pathname === '/whoami') { response.setHeader('Content-Type', 'application/json'); response.end(JSON.stringify({ authenticated: request.headers.cookie?.includes(`preview-login=${login}`) || false })); return; }
    response.setHeader('Content-Type', 'text/html; charset=utf-8');
    response.end('<!doctype html><html><head><title>Actual preview fixture</title></head><body><h1>Isolated preview</h1><a href="/other">Other page</a></body></html>');
  });
  await new Promise(done => server.listen(0, '127.0.0.1', done));
  const base = `http://127.0.0.1:${server.address().port}`, port = server.address().port;
  const pass = name => { checks.push({ name, at: new Date().toISOString() }); console.log(JSON.stringify({ passed: name })); };
  const pause = ms => new Promise(done => setTimeout(done, ms));
  async function until(name, operation, timeout = 20000) {
    const end = Date.now() + timeout;
    while (Date.now() < end) { const value = await operation(); if (value) return value; await pause(100); }
    throw new Error(`${name} exceeded ${timeout}ms`);
  }
  let electron, page, connection, status = 'failed', failure, project, firstSession, secondSession;
  const env = { ...process.env, WORKBENCH_JAVA_HOME: home, ...launchEnvironment, WORKBENCH_USER_DATA: path.join(directory, 'user-data'),
    WORKBENCH_CORE_URL: '', WORKBENCH_CORE_TOKEN: '', JAVA_TOOL_OPTIONS: '-Dfile.encoding=UTF-8 -Djdk.virtualThreadScheduler.parallelism=2 -Djdk.virtualThreadScheduler.maxPoolSize=2' };
  delete env.ELECTRON_RUN_AS_NODE;
  async function api(endpoint, body) {
    const response = await fetch(`${connection.baseUrl}/api${endpoint}`, { method: body === undefined ? 'GET' : 'POST',
      headers: { Authorization: `Bearer ${connection.token}`, ...(body === undefined ? {} : { 'Content-Type': 'application/json' }) },
      ...(body === undefined ? {} : { body: JSON.stringify(body) }), signal: AbortSignal.timeout(20000) });
    const value = await response.json(); assert.equal(response.status, 200, `${endpoint}: ${JSON.stringify(value)}`); return value;
  }
  async function launch() {
    electron = await _electron.launch({ args: [...(packaged ? [] : [root]), ...(process.platform === 'linux' ? ['--no-sandbox'] : [])], executablePath: executable, env, timeout: 60000 });
    const record = { identities: [probe(electron.process().pid)], observationsAfterStop: [] }; launches.push(record);
    record.application = await electron.evaluate(({ app }) => ({ packaged: app.isPackaged, version: app.getVersion(), appPath: app.getAppPath(), resourcesPath: process.resourcesPath }));
    assert.equal(record.application.packaged, packaged); assert.equal(record.application.version, version);
    if (packaged) {
      assert.equal(path.resolve(record.application.resourcesPath), path.resolve(artifactInputs.core.resources));
      assert.equal(path.resolve(record.application.appPath), path.join(artifactInputs.core.resources, 'app.asar'));
    }
    page = await electron.firstWindow({ timeout: 60000 }); page.on('pageerror', error => errors.push(error.message));
    await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor({ timeout: 45000 });
    connection = await page.evaluate(() => window.workbench.getConnection());
    const health = await api('/health'); assert.equal(health.version, version); record.identities.push(probe(health.corePid));
    for (const worker of await api('/workers')) record.identities.push({ pid: worker.pid, processStartedAt: worker.processStartedAt });
    record.coreVersion = health.version;
  }
  async function stop() {
    if (!electron) return;
    const current = electron, record = launches.at(-1); electron = null;
    try { await current.close(); }
    finally {
      for (const identity of record.identities) {
        const observation = probe(identity.pid, identity.processStartedAt, 'inspect'); record.observationsAfterStop.push(observation);
        assert.equal(observation.originalStopped, true, 'Original desktop, core and Worker must stop');
      }
    }
  }
  const endpoint = session => `/projects/${project.id}/sessions/${session.id}/preview`;
  async function configure(session, ports, tabs = []) {
    const current = await api(endpoint(session));
    return api(endpoint(session), { expectedVersion: current.version, allowedLoopbackPorts: ports, tabs, activeTabId: tabs[0]?.id || '' });
  }
  async function openSession(session) {
    await page.getByLabel('选择项目', { exact: true }).selectOption(project.id);
    await page.getByRole('button', { name: '协作会话', exact: true }).click();
    await page.getByRole('tab', { name: session.title, exact: true }).click();
    await expect(page.getByRole('region', { name: '会话网页预览', exact: true })).toBeVisible();
  }
  async function openAddress(url, newTab = false) {
    const panel = page.getByRole('region', { name: '会话网页预览', exact: true });
    if (newTab) await panel.getByRole('button', { name: '新建预览标签', exact: true }).click();
    await panel.getByLabel('网页地址', { exact: true }).fill(url);
    await panel.getByRole('button', { name: '转到', exact: true }).click();
    await expect(panel.getByRole('button', { name: '转到', exact: true })).toBeEnabled();
    await until('actual preview URL', async () => (await viewFacts()).some(value => value.url === url && !value.loading));
    await panel.locator('.preview-stage').scrollIntoViewIfNeeded();
  }
  async function viewFacts() {
    return electron.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].contentView.children
      .filter(view => view.webContents).map(view => ({ id: view.webContents.id, url: view.webContents.getURL(), loading: view.webContents.isLoading(), visible: view.getVisible(), bounds: view.getBounds() })));
  }
  async function evaluateView(id, code) {
    return electron.evaluate(async ({ webContents }, value) => {
      const contents = webContents.fromId(value.id); if (!contents) throw new Error('Original preview was destroyed');
      return contents.executeJavaScript(value.code);
    }, { id, code });
  }
  const storage = id => evaluateView(id, "Promise.all([fetch('/whoami').then(r=>r.json()),Promise.resolve(localStorage.getItem('preview-saved'))]).then(([who,saved])=>({authenticated:who.authenticated,saved}))");
  try {
    await launch(); project = await api('/projects', { name: 'Actual preview project', path: source });
    firstSession = await api(`/projects/${project.id}/sessions`, { title: 'Preview first session' });
    secondSession = await api(`/projects/${project.id}/sessions`, { title: 'Preview second session' });
    await configure(firstSession, [port]); await configure(secondSession, [port]);
    await page.reload(); await openSession(firstSession);
    const callback = `${base}/login?code=local-fixture#callback`;
    await openAddress(callback);
    let facts = await viewFacts(), first = facts.find(value => value.url === callback);
    assert.ok(first); await until('native view bounds after initial fetch', async () => (await viewFacts()).some(value => value.id === first.id && value.visible && value.bounds.width >= 100 && value.bounds.height >= 100));
    assert.equal(requests.filter(value => value.path === '/login').length, 1);
    assert.equal(requests.find(value => value.path === '/login').callbackPresent, true);
    await evaluateView(first.id, "localStorage.setItem('preview-saved','persisted');true");
    assert.deepEqual(await storage(first.id), { authenticated: true, saved: 'persisted' });
    assert.deepEqual(await evaluateView(first.id, "({node:typeof require,bridge:typeof window.workbench,opener:opener===null,cookies:document.cookie,permission:Notification.permission})"),
      { node: 'undefined', bridge: 'undefined', opener: true, cookies: '', permission: 'denied' });
    await pause(2800);
    assert.equal((await viewFacts()).find(value => value.id === first.id).url, callback);
    assert.equal(requests.filter(value => value.path === '/login').length, 1);
    let saved = await api(endpoint(firstSession)); assert.equal(saved.tabs[0].url, `${base}/login`); assert.equal(JSON.stringify(saved).includes('local-fixture'), false);
    pass('Initial fetch attaches a visible isolated native page; callback loads once and core metadata drops query and fragment');
    await page.getByRole('button', { name: '本地核心已连接', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeVisible();
    await until('preview suspended while application dialog is open', async () => (await viewFacts()).length === 0);
    await page.getByRole('button', { name: '关闭对话框', exact: true }).click();
    await until('preview restored after dialog closes', async () => (await viewFacts()).some(value => value.url === `${base}/login` && !value.loading));
    first = (await viewFacts()).find(value => value.url === `${base}/login`);
    assert.deepEqual(await storage(first.id), { authenticated: true, saved: 'persisted' });
    pass('Application dialogs suspend the native page and closing a dialog restores its isolated login');
    const tabId = saved.tabs[0].id;
    const blocked = await page.evaluate(async value => {
      const replies = [];
      for (const url of value.urls) try { await window.workbench.desktopPreview.action({ type: 'navigate', projectId: value.projectId, sessionId: value.sessionId, tabId: value.tabId, url }); replies.push(false); } catch { replies.push(true); }
      return replies;
    }, { projectId: project.id, sessionId: firstSession.id, tabId, urls: [connection.baseUrl + '/api/state', 'http://localhost:1/', 'file:///etc/passwd', 'javascript:alert(1)', 'https://user:password@example.invalid/'] });
    assert.ok(blocked.every(Boolean));
    assert.equal(await evaluateView(first.id, `fetch(${JSON.stringify(connection.baseUrl + '/api/state')}).then(()=>false,()=>true)`), true);
    assert.equal(await evaluateView(first.id, "window.open('/other') === null"), true);
    pass('Actual preview has no Node, management bridge or opener; permissions, popups, unbound loopback and core requests are refused');
    await openAddress(`${base}/other`, true); facts = await viewFacts(); assert.equal(facts.length, 2);
    const second = facts.find(value => value.url === `${base}/other`); assert.deepEqual(await storage(second.id), { authenticated: true, saved: 'persisted' });
    saved = await api(endpoint(firstSession)); assert.equal(saved.tabs.length, 2);
    await openSession(secondSession); await openAddress(`${base}/state`);
    facts = await viewFacts(); assert.equal(facts.length, 1); assert.deepEqual(await storage(facts[0].id), { authenticated: false, saved: null });
    await openSession(firstSession); await until('first session tabs restored', async () => {
      const views = await viewFacts(); return views.length === 2 && views.every(value => !value.loading) && views.some(value => value.url === `${base}/login`);
    });
    const firstRestored = (await viewFacts()).find(value => value.url === `${base}/login`); assert.deepEqual(await storage(firstRestored.id), { authenticated: true, saved: 'persisted' });
    pass('Multiple native tabs share one session; switching sessions destroys old pages and isolates login and local storage');
    const originalProject = project, otherSource = path.join(directory, 'other original project'); mkdirSync(otherSource);
    execFileSync('git', ['clone', '--no-hardlinks', '--', source, otherSource], { windowsHide: true, timeout: 20000, stdio: 'pipe' });
    const otherProject = await api('/projects', { name: 'Other preview project', path: otherSource });
    project = otherProject;
    const otherSession = await api(`/projects/${project.id}/sessions`, { title: 'Other project session' });
    await configure(otherSession, [port]);
    await until('other project appears', () => page.getByLabel('选择项目', { exact: true }).locator(`option[value="${project.id}"]`).count());
    await openSession(otherSession); await openAddress(`${base}/state`);
    assert.deepEqual(await storage((await viewFacts())[0].id), { authenticated: false, saved: null });
    project = originalProject; await openSession(firstSession);
    await until('original project pages restored', async () => { const views = await viewFacts(); return views.length === 2 && views.every(value => !value.loading); });
    assert.deepEqual(await storage((await viewFacts())[0].id), { authenticated: true, saved: 'persisted' });
    pass('Switching projects preserves each isolated native storage partition and destroys the previous project pages');
    await page.getByRole('region', { name: '会话网页预览', exact: true }).locator('.preview-stage').scrollIntoViewIfNeeded();
    await until('restored native view is visible', async () => (await viewFacts()).some(value => value.visible && value.bounds.width >= 100 && value.bounds.height >= 100));
    const captures = await electron.evaluate(async ({ BrowserWindow }) => {
      const window = BrowserWindow.getAllWindows()[0], views = window.contentView.children.filter(view => view.webContents && view.getVisible());
      return Promise.all(views.map(async view => ({ id: view.webContents.id, bounds: view.getBounds(), image: (await view.webContents.capturePage()).toPNG().toString('base64') })));
    });
    assert.equal(captures.length, 1);
    writeFileSync(path.join(directory, 'actual-native-preview.png'), Buffer.from(captures[0].image, 'base64'));
    save('actual-native-preview-bounds.json', captures.map(({ image, ...facts }) => facts));
    await page.screenshot({ path: path.join(directory, 'actual-workbench-chrome.png'), fullPage: true });
    await stop(); await launch(); await openSession(firstSession);
    await until('tabs after full desktop restart', async () => (await viewFacts()).length === 2 && (await viewFacts()).every(value => !value.loading));
    const restored = (await viewFacts())[0]; assert.deepEqual(await storage(restored.id), { authenticated: true, saved: 'persisted' });
    pass('HttpOnly login and local storage survive full desktop, core and Worker shutdown and restart');
    await configure(firstSession, [port], [{ id: 'state-only', url: `${base}/state`, title: '' }, { id: 'state-two', url: `${base}/other`, title: '' }]);
    await page.reload(); await openSession(firstSession);
    await until('same-origin state pages', async () => (await viewFacts()).length === 2 && (await viewFacts()).every(value => !value.loading));
    const beforeClear = (await viewFacts()).map(value => value.id);
    await page.getByRole('region', { name: '会话网页预览', exact: true }).getByRole('button', { name: '清理站点', exact: true }).click();
    await expect(page.getByText('当前站点的 Cookie 与本地存储已清理。', { exact: true })).toBeVisible();
    await until('state replacements loaded', async () => (await viewFacts()).length === 2 && (await viewFacts()).every(value => !value.loading && !beforeClear.includes(value.id)));
    for (const view of await viewFacts()) assert.deepEqual(await storage(view.id), { authenticated: false, saved: null });
    pass('Site clear destroys original same-origin pages before clearing cookies and storage and replacing pages');
    await openAddress(`${base}/login?code=retired-fixture#callback`);
    const beforeReset = await api(endpoint(firstSession));
    await evaluateView((await viewFacts())[0].id, "localStorage.setItem('preview-saved','retired');true");
    const oldPartition = require('../desktop/preview-policy.cjs').partition(beforeReset);
    const oldCookies = await electron.evaluate(async ({ session }, value) => session.fromPartition(value.partition).cookies.get({ url: value.url }), { partition: oldPartition, url: base });
    assert.ok(oldCookies.some(cookie => cookie.name === 'preview-login'));
    await stop();
    await launch();
    let current = await api(endpoint(firstSession)); await api(endpoint(firstSession) + '/reset', { expectedVersion: current.version });
    await openSession(firstSession); await openAddress(`${base}/state`);
    assert.deepEqual(await storage((await viewFacts())[0].id), { authenticated: false, saved: null });
    const registry = JSON.parse(readFileSync(path.join(directory, 'user-data/preview-partitions.json'), 'utf8'));
    assert.equal(registry.some(value => value.sessionId === firstSession.id && value.storageGeneration === beforeReset.storageGeneration), false);
    const retiredCookies = await electron.evaluate(async ({ session }, value) => session.fromPartition(value.partition).cookies.get({ url: value.url }), { partition: oldPartition, url: base });
    assert.deepEqual(retiredCookies, []);
    pass('A generation reset after full restart retires the previous process partition ledger and starts empty storage');
    await configure(firstSession, [], []);
    await until('revoked port destroys original native views', async () => (await viewFacts()).length === 0);
    pass('Port revocation destroys the actual native page without waiting for another user action');
    assert.deepEqual(errors, []); assert.equal(requests.some(value => value.authorizationPresent), false);
    await stop(); status = 'passed';
  } catch (error) {
    failure = error.stack; process.exitCode = 1;
    if (electron) save('failure-views.json', await viewFacts().catch(cause => ({ error: cause.message })));
    if (page && !page.isClosed()) await page.screenshot({ path: path.join(directory, 'failure.png'), fullPage: true }).catch(() => {});
  } finally {
    try { await stop(); } catch (error) { failure = [failure, error.stack].filter(Boolean).join('\n'); status = 'failed'; process.exitCode = 1; }
    server.closeAllConnections(); await new Promise(done => server.close(done));
    const sourcesUnchanged = inputs.every(input => hash(readFileSync(path.resolve(root, input.file))) === input.sha256);
    if (!sourcesUnchanged) { status = 'failed'; process.exitCode = 1; }
    const receipt = { recordedAt: new Date().toISOString(), version, platform: process.platform, status, failure, directory, checks, launches, requests, pageErrors: errors,
      inputs, sourcesUnchanged, packaged, runtimeJava: java, externalNodeAndJavaUnavailableOnPath: packaged,
      artifactInputsFile: artifactInputs ? path.join(directory, 'artifact-inputs.json') : null,
      scope: packaged ? 'ACTUAL_PACKAGED_ELECTRON_MAIN_PRELOAD_JRE_JAR_AND_ASAR_WEB; LOCAL_HTTP_LOGIN_FIXTURE; EXTERNAL_OAUTH_REMOTE_FORWARDING_AND_INSTALLER_SEPARATE'
        : 'ACTUAL_DEVELOPMENT_ELECTRON_MAIN_PRELOAD_CORE_AND_WEB; LOCAL_HTTP_LOGIN_FIXTURE; PACKAGED_AND_REMOTE_FORWARDING_SEPARATE' };
    save('receipt.json', receipt);
    if (status === 'passed') { mkdirSync(path.join(root, 'docs/qa'), { recursive: true }); writeFileSync(path.join(root, `docs/qa/preview-native${packaged ? '-packaged' : ''}-${process.platform}.json`), JSON.stringify(receipt, null, 2) + '\n'); }
    console.log(JSON.stringify({ directory, status, checks: checks.length, failure }));
  }
})().catch(error => { console.error(error); process.exitCode = 1; });
