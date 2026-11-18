const { test } = require('node:test');
const assert = require('node:assert/strict');
const { EventEmitter } = require('node:events');
const Module = require('node:module');
const fs = require('node:fs');
const os = require('node:os');
const path = require('node:path');

const sessions = [];
const sessionByPartition = new Map();
class FakeSession {
  constructor(partition) {
    this.partition = partition;
    this.cookies = { flushStore: async () => { this.flushCount = (this.flushCount || 0) + 1; }, get: async () => [], remove: async () => {} };
    this.webRequest = { onBeforeRequest: handler => { this.requestHandler = handler; } };
    this.storageClears = 0;
  }
  setPermissionRequestHandler(handler) { this.permissionRequestHandler = handler; }
  setPermissionCheckHandler(handler) { this.permissionCheckHandler = handler; }
  setDevicePermissionHandler(handler) { this.devicePermissionHandler = handler; }
  setDisplayMediaRequestHandler(handler) { this.displayMediaRequestHandler = handler; }
  on() {}
  async clearStorageData(options) { this.storageClears++; this.lastClearOptions = options; if (this.beforeStorageClear) this.beforeStorageClear(); if (this.failStorageClear) throw new Error('storage clear failed'); }
  async clearAuthCache() { this.authClears = (this.authClears || 0) + 1; }
  async flushStorageData() {}
  async closeAllConnections() { this.closedConnections = (this.closedConnections || 0) + 1; }
  async clearCache() { this.cacheClears = (this.cacheClears || 0) + 1; }
}

class FakeContents extends EventEmitter {
  constructor(view) {
    super(); this.view = view; this.session = view.webPreferences.session; this.url = ''; this.title = ''; this.destroyed = false;
    this.navigations = [];
    this.navigationHistory = { canGoBack: () => false, canGoForward: () => false };
  }
  setWindowOpenHandler(handler) { this.windowOpenHandler = handler; }
  async loadURL(url) { this.url = url; this.navigations.push(url); }
  isDestroyed() { return this.destroyed; }
  getTitle() { return this.title; }
  getURL() { return this.url; }
  isLoading() { return false; }
  close() { this.destroyed = true; this.emit('destroyed'); }
  focus() {}
  reload() {}
}

class FakeView {
  constructor({ webPreferences }) { this.webPreferences = webPreferences; this.webContents = new FakeContents(this); }
  setVisible(value) { this.visible = value; }
  setBounds(value) { this.bounds = value; }
}

const electron = {
  WebContentsView: FakeView,
  session: { fromPartition(key) { if (!sessionByPartition.has(key)) sessionByPartition.set(key, new FakeSession(key)); const value = sessionByPartition.get(key); sessions.push(value); return value; } },
};
const originalLoad = Module._load;
Module._load = function (request, parent, isMain) { return request === 'electron' ? electron : originalLoad.call(this, request, parent, isMain); };
const { createPreviewHost } = require('./preview-host.cjs');
Module._load = originalLoad;

const makeScope = (overrides = {}) => ({ serverId: 'core-A', projectId: 'project-A', sessionId: 'session-A', storageGeneration: 1,
  version: 1, authorizationVersion: 1, allowedLoopbackPorts: [5173], tabs: [{ id: 'tab-A', url: 'http://127.0.0.1:5173/', title: 'Preview' }], activeTabId: 'tab-A', ...overrides });
function fixture(initial = makeScope(), options = {}) {
  sessions.length = 0;
  sessionByPartition.clear();
  const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'awb-preview-test-'));
  const registryPath = path.join(directory, 'preview-partitions.json');
  let current = initial;
  let authorizeGate = null;
  const children = new Set(), events = [], saves = [];
  const owner = { contentView: { addChildView: view => children.add(view), removeChildView: view => children.delete(view) }, getContentSize: () => [900, 700] };
  const host = createPreviewHost({ owner, connection: { baseUrl: 'http://127.0.0.1:18765' }, partitionRegistryPath: registryPath,
    authorize: async () => { const value = current; if (authorizeGate) await authorizeGate(); return value; }, publish: event => events.push(event),
    ...(options.persist ? { persist: async (scope, tab, { activate = false } = {}) => {
      assert.equal(scope.version, current.version); saves.push({ tab, activate });
      const tabs = scope.tabs.some(value => value.id === tab.id) ? scope.tabs.map(value => value.id === tab.id ? tab : value) : [...scope.tabs, tab];
      current = { ...scope, version: scope.version + 1, tabs, ...(activate ? { activeTabId: tab.id } : {}) }; return current;
    } } : {}) });
  return { host, children, events, sessions, saves, registryPath, directory, getCurrent() { return current; }, setCurrent(value) { current = value; }, setAuthorizeGate(value) { authorizeGate = value; } };
}

test('attaches an isolated view and blocks unsafe navigation and subrequests', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const view = [...f.children][0], contents = view.webContents;
  assert.equal(contents.url, 'http://127.0.0.1:5173/');
  assert.equal(view.webPreferences.nodeIntegration, false); assert.equal(view.webPreferences.contextIsolation, true);
  assert.equal(Object.hasOwn(view.webPreferences, 'preload'), false);
  assert.equal(contents.windowOpenHandler().action, 'deny');
  let prevented = false; contents.emit('will-frame-navigate', { preventDefault() { prevented = true; } }, { url: 'file:///C:/Windows/win.ini' }); assert.equal(prevented, true);
  let allowed = true; contents.emit('will-frame-navigate', { url: 'https://example.test/', preventDefault() { allowed = false; } }); assert.equal(allowed, true);
  let corePrevented = false; contents.emit('will-redirect', { url: 'http://127.0.0.1:18765/api/state', preventDefault() { corePrevented = true; } }); assert.equal(corePrevented, true);
  const request = url => new Promise(resolve => contents.session.requestHandler({ url }, resolve));
  assert.deepEqual(await request('http://127.0.0.1:18765/api/state'), { cancel: true });
  assert.deepEqual(await request('http://127.0.0.1:5174/private'), { cancel: true });
  assert.deepEqual(await request('http://127.0.0.1:5173/app.js'), { cancel: false });
  await f.host.close();
});

test('destroys stale views when authorization version changes', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const prior = [...f.children][0].webContents;
  f.setCurrent(makeScope({ version: 2, authorizationVersion: 2, allowedLoopbackPorts: [] }));
  await assert.rejects(f.host.command({ type: 'reload', tabId: 'tab-A' }), /授权已变化/);
  assert.equal(prior.isDestroyed(), true); assert.equal(f.children.size, 0);
  await f.host.close();
});

test('metadata version changes update tabs while preserving the isolated session', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const prior = [...f.children][0].webContents, record = f.sessions[0];
  f.setCurrent(makeScope({ version: 2, tabs: [{ id: 'tab-A', url: 'https://example.test/path', title: 'Updated' }], activeTabId: 'tab-A' }));
  await f.host.command({ type: 'reload', tabId: 'tab-A' });
  assert.equal(prior.isDestroyed(), false); assert.equal(prior.url, 'https://example.test/path');
  assert.equal(f.sessions[0], record); assert.equal(f.children.size, 1);
  await f.host.close();
});

test('metadata synchronization preserves live query strings and fragments on the same saved path', async () => {
  const f = fixture(makeScope({ tabs: [{ id: 'tab-A', url: 'https://example.test/login', title: 'Login' }] }));
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const contents = [...f.children][0].webContents;
  await f.host.command({ type: 'navigate', tabId: 'tab-A', url: 'https://example.test/login?code=one#callback' });
  f.setCurrent(makeScope({ version: 2, tabs: [{ id: 'tab-A', url: 'https://example.test/login', title: 'Callback' }] }));
  await f.host.command({ type: 'reload', tabId: 'tab-A' });
  assert.equal(contents.url, 'https://example.test/login?code=one#callback');
  await f.host.close();
});

test('new tab navigation skips the sanitized placeholder and opens the requested callback directly', async () => {
  const f = fixture(makeScope({ tabs: [] }));
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const url = 'https://example.test/login?code=one#callback';
  f.setCurrent(makeScope({ version: 2, tabs: [{ id: 'tab-A', url: 'https://example.test/login', title: '' }], activeTabId: 'tab-A' }));
  await f.host.command({ type: 'navigate', tabId: 'tab-A', url });
  const contents = [...f.children][0].webContents;
  assert.deepEqual(contents.navigations, [url]);
  await f.host.close();
});

test('open persists only the sanitized address before loading the complete callback once', async () => {
  const f = fixture(makeScope({ tabs: [], activeTabId: '' }), { persist: true });
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const url = 'https://example.test/login?code=one#callback';
  const result = await f.host.command({ type: 'open', projectId: 'project-A', sessionId: 'session-A', tabId: 'tab-A', url, expectedVersion: 1 });
  const contents = [...f.children][0].webContents;
  assert.deepEqual(contents.navigations, [url]); assert.equal(result.tabs[0].url, 'https://example.test/login');
  assert.equal(f.saves[0].activate, true); assert.equal(result.activeTabId, 'tab-A'); assert.equal(JSON.stringify(result).includes('code='), false);
  await assert.rejects(f.host.command({ type: 'open', tabId: 'tab-A', url: 'https://example.test/other', expectedVersion: 1 }), /配置已变化/);
  assert.deepEqual(contents.navigations, [url]);
  await f.host.close();
});

test('queued page metadata cannot reject a new tab opened from the preceding UI version', async t => {
  const f = fixture(makeScope(), { persist: true }); t.after(() => f.host.close());
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const first = [...f.children][0].webContents;
  first.title = 'Restored page title';
  const metadata = f.host.command({ type: 'pageState', tabId: 'tab-A' });
  const url = 'http://127.0.0.1:5173/other?code=once#callback';
  const opening = f.host.command({ type: 'open', projectId: 'project-A', sessionId: 'session-A', tabId: 'tab-B', url, expectedVersion: 1 });
  await metadata;
  const result = await opening;
  assert.equal(result.version, 3); assert.equal(result.activeTabId, 'tab-B'); assert.equal(result.tabs.length, 2);
  assert.equal(result.tabs[0].title, 'Restored page title'); assert.equal(result.tabs[1].url, 'http://127.0.0.1:5173/other');
  assert.deepEqual([...f.children][1].webContents.navigations, [url]);
  assert.equal(first.isDestroyed(), false); assert.equal(f.saves.length, 2);
});

test('unchanged page metadata does not advance the configuration version', async t => {
  const f = fixture(makeScope(), { persist: true }); t.after(() => f.host.close());
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const contents = [...f.children][0].webContents; contents.title = 'Preview';
  await f.host.command({ type: 'pageState', tabId: 'tab-A' });
  assert.equal(f.getCurrent().version, 1); assert.equal(f.saves.length, 0);
});

test('metadata allowance is bounded and cannot cover an intervening external configuration write', async t => {
  const f = fixture(makeScope(), { persist: true }); t.after(() => f.host.close());
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const contents = [...f.children][0].webContents;
  for (let index = 0; index < 65; index++) {
    contents.title = `Title ${index}`;
    await f.host.command({ type: 'pageState', tabId: 'tab-A' });
  }
  const input = { type: 'open', projectId: 'project-A', sessionId: 'session-A', tabId: 'tab-B', url: 'https://example.test/new', expectedVersion: 1 };
  await assert.rejects(f.host.command(input), /配置已变化/);
  const external = { ...f.getCurrent(), version: 67, tabs: [{ id: 'tab-A', url: 'http://127.0.0.1:5173/external', title: 'External' }] };
  f.setCurrent(external);
  await assert.rejects(f.host.command({ ...input, expectedVersion: 66 }), /配置已变化/);
  await f.host.command({ type: 'pageState', tabId: 'tab-A' });
  await assert.rejects(f.host.command({ ...input, expectedVersion: 66 }), /配置已变化/);
  assert.equal(f.children.size, 1); assert.equal(f.getCurrent().tabs[0].url, 'http://127.0.0.1:5173/external');
  assert.equal(f.events.some(event => event.configuration?.version === 67), true);
});

test('metadata allowance never survives port revocation or a storage reset', async t => {
  for (const change of [{ authorizationVersion: 2, allowedLoopbackPorts: [] }, { storageGeneration: 2 }]) {
    const f = fixture(makeScope(), { persist: true }); t.after(() => f.host.close());
    await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
    const contents = [...f.children][0].webContents; contents.title = 'Updated';
    await f.host.command({ type: 'pageState', tabId: 'tab-A' });
    f.setCurrent({ ...f.getCurrent(), version: 3, ...change });
    await assert.rejects(f.host.command({ type: 'open', tabId: 'tab-B', url: 'http://127.0.0.1:5173/new', expectedVersion: 1 }), /授权已变化/);
    assert.equal(contents.isDestroyed(), true); assert.equal(f.children.size, 0); assert.equal(f.saves.length, 1);
  }
});

test('explicit selection and opening another tab end the preceding metadata allowance', async t => {
  for (const action of ['select', 'open']) {
    const f = fixture(makeScope({ tabs: [
      { id: 'tab-A', url: 'https://example.test/one', title: '' }, { id: 'tab-B', url: 'https://example.test/two', title: '' },
    ] }), { persist: true }); t.after(() => f.host.close());
    await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
    [...f.children][0].webContents.title = 'Updated';
    await f.host.command({ type: 'pageState', tabId: 'tab-A' });
    await f.host.command({ type: action, tabId: 'tab-B', url: 'https://example.test/new', expectedVersion: 2 });
    await assert.rejects(f.host.command({ type: 'open', tabId: 'tab-C', url: 'https://example.test/old', expectedVersion: 1 }), /配置已变化/);
    assert.equal(f.children.size, 2); assert.equal(f.getCurrent().activeTabId, 'tab-B');
  }
});

test('background navigation preserves selected tab and selecting a tab persists it', async () => {
  const f = fixture(makeScope({ tabs: [
    { id: 'tab-A', url: 'https://example.test/one', title: '' }, { id: 'tab-B', url: 'https://example.test/two', title: '' },
  ] }), { persist: true });
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  await f.host.command({ type: 'pageState', tabId: 'tab-B' });
  assert.equal(f.getCurrent().activeTabId, 'tab-A');
  await f.host.command({ type: 'select', projectId: 'project-A', sessionId: 'session-A', tabId: 'tab-B' });
  assert.equal(f.getCurrent().activeTabId, 'tab-B');
  await f.host.close();
});

test('old scoped cleanup and navigation cannot affect a newly attached session', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const prior = [...f.children][0].webContents, priorSession = prior.session;
  f.setCurrent(makeScope({ sessionId: 'session-B' }));
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-B' });
  assert.equal(prior.isDestroyed(), true);
  const oldRequest = await new Promise(resolve => priorSession.requestHandler({ url: 'http://127.0.0.1:5173/app.js' }, resolve));
  assert.deepEqual(oldRequest, { cancel: true });
  assert.ok(priorSession.closedConnections > 0);
  await f.host.command({ type: 'suspend', projectId: 'project-A', sessionId: 'session-A' });
  assert.equal(f.children.size, 1);
  await assert.rejects(f.host.command({ type: 'navigate', projectId: 'project-A', sessionId: 'session-A', tabId: 'tab-A', url: 'https://example.test/' }), /会话已切换/);
  assert.equal([...f.children][0].webContents.isDestroyed(), false);
  await f.host.close();
});

test('reset generation clears old persistent site data after destroying its page', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const oldContents = [...f.children][0].webContents, oldSession = f.sessions[0];
  f.setCurrent(makeScope({ storageGeneration: 2, version: 2 }));
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  assert.equal(oldContents.isDestroyed(), true); assert.equal(oldSession.storageClears, 1); assert.equal(oldSession.authClears, 1);
  assert.equal((JSON.parse(fs.readFileSync(f.registryPath, 'utf8'))).some(value => value.storageGeneration === 1), false);
  assert.equal(f.sessions.length, 2); assert.notEqual(f.sessions[0].partition, f.sessions[1].partition);
  await f.host.close();
});

test('failed old-generation cleanup is surfaced and never reports a cleared reset', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  f.sessions[0].failStorageClear = true;
  f.setCurrent(makeScope({ storageGeneration: 2, version: 2 }));
  await assert.rejects(f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' }), /storage clear failed/);
  assert.equal(f.events.some(event => event.cleared === true), false);
  await f.host.close();
});

test('reset cleans persistent generations left by a previous desktop process', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const oldPartition = f.sessions[0].partition;
  const priorHost = f.host; await priorHost.close();
  const oldSession = sessionByPartition.get(oldPartition);
  const owner = { contentView: { addChildView() {}, removeChildView() {} }, getContentSize: () => [900, 700] };
  let current = makeScope({ storageGeneration: 2, version: 2, authorizationVersion: 2, tabs: [] });
  const host = createPreviewHost({ owner, connection: { baseUrl: 'http://127.0.0.1:18765' }, partitionRegistryPath: f.registryPath,
    authorize: async () => current, publish: () => {} });
  await host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  assert.equal(oldSession.storageClears, 1); assert.equal(oldSession.authClears, 1);
  assert.equal(JSON.parse(fs.readFileSync(f.registryPath, 'utf8')).some(value => value.storageGeneration === 1), false);
  await host.close(); fs.rmSync(f.directory, { recursive: true, force: true });
});

test('authorization polling closes and clears the retired generation without another attach', async () => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const oldContents = [...f.children][0].webContents, oldSession = f.sessions[0];
  f.setCurrent(makeScope({ storageGeneration: 2, version: 2 }));
  await new Promise((resolve, reject) => {
    const deadline = setTimeout(() => reject(new Error('authorization polling did not retire the old preview')), 2500);
    const poll = setInterval(() => {
      if (f.events.some(event => event.closed)) { clearTimeout(deadline); clearInterval(poll); resolve(); }
    }, 10);
  });
  assert.equal(oldContents.isDestroyed(), true); assert.equal(oldSession.storageClears, 1);
  assert.equal(f.children.size, 0); await f.host.close();
});

test('site reset destroys same-origin pages before clearing cookies and storage', async () => {
  const initial = makeScope({ tabs: [
    { id: 'tab-A', url: 'https://example.test/deep/page', title: 'One' },
    { id: 'tab-B', url: 'https://example.test/other', title: 'Two' },
    { id: 'tab-C', url: 'https://other.test/', title: 'Other' },
  ], activeTabId: 'tab-A' });
  const f = fixture(initial); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const before = [...f.children].map(view => view.webContents), active = [...f.children].find(view => view.webContents.getURL().includes('/deep/'));
  const siteSession = active.webContents.session;
  siteSession.beforeStorageClear = () => {
    assert.equal(before[0].isDestroyed(), true); assert.equal(before[1].isDestroyed(), true); assert.equal(before[2].isDestroyed(), false);
    assert.deepEqual(siteSession.lastClearOptions.storages, ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'cachestorage', 'filesystem']);
  };
  const result = await f.host.command({ type: 'clearSite', tabId: 'tab-A' });
  assert.deepEqual(result, { cleared: true, origin: 'https://example.test' });
  assert.equal(f.children.size, 3); assert.equal([...f.children].filter(view => view.webContents.getURL().includes('example.test')).length, 2);
  assert.equal([...f.children].filter(view => view.webContents.getURL().includes('other.test')).length, 1);
  await f.host.close();
});

test('failed site reset does not report success or reopen pages', async () => {
  const f = fixture(makeScope({ tabs: [{ id: 'tab-A', url: 'https://example.test/', title: '' }] }));
  await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  const page = [...f.children][0].webContents; page.session.failStorageClear = true;
  await assert.rejects(f.host.command({ type: 'clearSite', tabId: 'tab-A' }), /storage clear failed/);
  assert.equal(page.isDestroyed(), true); assert.equal(f.children.size, 0);
  assert.equal(f.events.some(event => event.cleared === true), false);
  await f.host.close();
});

test('serializes navigation with authorization changes so stale actions cannot win', { timeout: 3000 }, async t => {
  const f = fixture(); await f.host.command({ type: 'attach', projectId: 'project-A', sessionId: 'session-A' });
  t.after(() => f.host.close());
  const view = [...f.children][0].webContents;
  let release, entered;
  const gateEntered = new Promise(resolve => { entered = resolve; });
  f.setAuthorizeGate(() => new Promise(resolve => { release = resolve; entered(); }));
  const navigate = f.host.command({ type: 'navigate', tabId: 'tab-A', url: 'http://127.0.0.1:5173/old' });
  await gateEntered;
  f.setCurrent(makeScope({ version: 2, authorizationVersion: 2, allowedLoopbackPorts: [] }));
  f.setAuthorizeGate(null);
  const reload = f.host.command({ type: 'reload', tabId: 'tab-A' });
  const rejected = assert.rejects(reload, /授权已变化/);
  release();
  await navigate;
  await rejected;
  assert.equal(view.isDestroyed(), true); assert.equal(f.children.size, 0);
  await f.host.close();
});
