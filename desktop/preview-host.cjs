const { WebContentsView, session } = require('electron');
const fs = require('node:fs');
const path = require('node:path');
const { partition, destination, preferences } = require('./preview-policy.cjs');

function createPreviewHost({ owner, connection, authorize, persist, partitionRegistryPath, publish = () => {} }) {
  const views = new Map(), partitions = new Map();
  let attached = null, selected = '', bounds = null, stopped = false;
  let operationQueue = Promise.resolve(), pollQueued = false;
  const rate = { timestamps: [] };
  const persistTimers = new Map();
  const retired = new Map();
  if (partitionRegistryPath && fs.existsSync(partitionRegistryPath)) {
    let saved;
    try { saved = JSON.parse(fs.readFileSync(partitionRegistryPath, 'utf8')); }
    catch (error) { throw new Error(`预览存储账本无法读取，已停用安全预览：${error.message}`); }
    if (!Array.isArray(saved) || saved.length > 10000 || saved.some(value => !validScope(value))) throw new Error('预览存储账本格式无效，已停用安全预览。');
    for (const scope of saved) retired.set(partition(scope), scope);
  }
  function validScope(value) {
    return value && typeof value === 'object' && typeof value.serverId === 'string' && value.serverId.length > 0
      && typeof value.projectId === 'string' && value.projectId.length > 0 && typeof value.sessionId === 'string' && value.sessionId.length > 0
      && Number.isSafeInteger(value.storageGeneration) && value.storageGeneration > 0;
  }

  function saveRegistry() {
    if (!partitionRegistryPath) return;
    fs.mkdirSync(path.dirname(partitionRegistryPath), { recursive: true });
    const temporary = `${partitionRegistryPath}.${process.pid}.${Date.now()}.tmp`;
    fs.writeFileSync(temporary, JSON.stringify([...retired.values()]), { mode: 0o600 });
    fs.renameSync(temporary, partitionRegistryPath);
  }
  const emit = (entry, error = '') => {
    if (!entry || views.get(entry.id) !== entry || entry.contents.isDestroyed()) return;
    publish({ projectId: entry.projectId, sessionId: entry.sessionId, tabId: entry.id,
      title: entry.contents.getTitle() || '', url: entry.contents.getURL() || '', loading: entry.contents.isLoading() || false, error });
  };

  async function closeViews() {
    const entries = [...views.values()]; views.clear(); selected = '';
    await Promise.all(entries.map(destroyEntry));
  }

  async function destroyEntry(entry) {
    clearTimeout(persistTimers.get(entry.id)); persistTimers.delete(entry.id);
    owner.contentView.removeChildView(entry.view);
    if (entry.contents.isDestroyed()) return;
    const done = new Promise(resolve => entry.contents.once('destroyed', resolve));
    entry.contents.close({ waitForBeforeUnload: false }); await done;
  }

  async function clearPartition(record) {
    await record.session.closeAllConnections();
    await record.session.clearStorageData();
    await record.session.clearCache();
    await record.session.clearAuthCache();
    await record.session.cookies.flushStore();
    if (record.session.flushStorageData) await record.session.flushStorageData();
  }

  async function retireOldGenerations(scope) {
    const current = partition(scope);
    const stale = [...retired.entries()].filter(([key, record]) => key !== current
      && record.serverId === scope.serverId && record.projectId === scope.projectId
      && record.sessionId === scope.sessionId && record.storageGeneration !== scope.storageGeneration);
    for (const [key] of stale) {
      const record = partitions.get(key) || { session: session.fromPartition(key) };
      record.active = false;
      record.session.webRequest.onBeforeRequest((_request, callback) => callback({ cancel: true }));
      await clearPartition(record);
      partitions.delete(key); retired.delete(key);
    }
    if (stale.length) saveRegistry();
  }

  function visible(entry) {
    const current = entry.id === selected && bounds;
    entry.view.setVisible(Boolean(current));
    if (current) entry.view.setBounds(bounds);
  }

  function sessionFor(scope) {
    const key = partition(scope);
    if (partitions.has(key)) { const record = partitions.get(key); record.active = true; return record; }
    const actual = session.fromPartition(key), record = { session: actual, scope, active: true };
    actual.setPermissionRequestHandler((_web, _permission, callback) => callback(false));
    actual.setPermissionCheckHandler(() => false); actual.setDevicePermissionHandler(() => false);
    actual.setDisplayMediaRequestHandler((_request, callback) => callback({}));
    actual.on('will-download', (event, item) => { event.preventDefault(); item.cancel(); });
    actual.webRequest.onBeforeRequest((request, callback) => {
      if (!record.active || stopped) { callback({ cancel: true }); return; }
      if (request.url.startsWith('data:') || request.url.startsWith('blob:') || request.url === 'about:blank') { callback({ cancel: false }); return; }
      try { destination(request.url, record.scope, connection, { subresource: true }); callback({ cancel: false }); }
      catch { callback({ cancel: true }); }
    });
    partitions.set(key, record);
    if (!retired.has(key)) { retired.set(key, { serverId: scope.serverId, projectId: scope.projectId, sessionId: scope.sessionId, storageGeneration: scope.storageGeneration }); saveRegistry(); }
    return record;
  }

  function page(id) {
    const entry = views.get(id); if (!entry || entry.contents.isDestroyed()) throw new Error('原预览标签已关闭。'); return entry;
  }

  function create(tab) {
    const record = sessionFor(attached), view = new WebContentsView({ webPreferences: preferences(record.session) }), contents = view.webContents;
    const entry = { id: tab.id, projectId: attached.projectId, sessionId: attached.sessionId, view, contents }; views.set(tab.id, entry); owner.contentView.addChildView(view); visible(entry);
    contents.setWindowOpenHandler(() => ({ action: 'deny' }));
    const guard = (event, navigation) => {
      const url = event.url || (typeof navigation === 'string' ? navigation : navigation?.url);
      if (url === 'about:blank') return;
      try { destination(url, record.scope, connection); } catch { event.preventDefault(); }
    };
    contents.on('will-frame-navigate', guard); contents.on('will-redirect', guard);
    contents.on('did-navigate', () => { emit(entry); schedulePageState(entry); });
    contents.on('did-navigate-in-page', (_event, _url, mainFrame) => { emit(entry); if (mainFrame !== false) schedulePageState(entry); });
    contents.on('page-title-updated', () => { emit(entry); schedulePageState(entry); }); contents.on('did-start-loading', () => emit(entry)); contents.on('did-stop-loading', () => emit(entry));
    contents.on('render-process-gone', (_event, detail) => emit(entry, `预览页面已停止：${detail.reason}`));
    contents.on('did-fail-load', (_event, code, _description, _url, mainFrame) => { if (mainFrame && code !== -3) emit(entry, `预览加载未完成（${code}）。`); });
    return entry;
  }

  function schedulePageState(entry) {
    if (views.get(entry.id) !== entry) return;
    clearTimeout(persistTimers.get(entry.id));
    persistTimers.set(entry.id, setTimeout(() => {
      persistTimers.delete(entry.id);
      void command({ type: 'pageState', projectId: entry.projectId, sessionId: entry.sessionId, tabId: entry.id, entry }).catch(error => emit(entry, error.message));
    }, 250));
  }

  function navigate(entry, url) {
    const target = destination(url, attached, connection);
    void entry.contents.loadURL(target).catch(error => emit(entry, error.message));
  }

  function savedAddress(url) {
    const value = new URL(url);
    return `${value.origin}${value.pathname}`;
  }

  async function current(projectId, sessionId) {
    const authorized = await authorize(projectId, sessionId);
    if (!authorized || authorized.projectId !== projectId || authorized.sessionId !== sessionId) throw new Error('预览授权范围不一致。');
    return authorized;
  }

  async function syncTabs(scope, deferredNavigationTabId = '') {
    const previous = attached, wanted = new Map(scope.tabs.map(tab => [tab.id, tab]));
    for (const [tabId, entry] of views) {
      if (!wanted.has(tabId)) { views.delete(tabId); await destroyEntry(entry); }
    }
    attached = scope;
    for (const record of partitions.values()) record.active = false;
    sessionFor(scope).scope = scope;
    selected = wanted.has(scope.activeTabId) ? scope.activeTabId : scope.tabs[0]?.id || '';
    for (const tab of scope.tabs) {
      const entry = views.get(tab.id), oldTab = previous?.tabs?.find(value => value.id === tab.id);
      if (!entry) {
        const created = create(tab);
        if (tab.id !== deferredNavigationTabId) navigate(created, tab.url);
      } else if (oldTab && savedAddress(oldTab.url) !== tab.url) {
        if (tab.id !== deferredNavigationTabId) navigate(entry, tab.url);
      }
    }
    for (const entry of views.values()) visible(entry);
  }

  async function detach() {
    for (const record of partitions.values()) record.active = false;
    await closeViews(); attached = null;
    await Promise.all([...partitions.values()].map(record => record.session.closeAllConnections()));
  }

  async function attach(projectId, sessionId) {
    if (stopped) throw new Error('预览客户端已停止。');
    if (attached && (attached.projectId !== projectId || attached.sessionId !== sessionId)) await detach();
    let scope;
    try { scope = await current(projectId, sessionId); }
    catch (error) { if (attached) await detach(); throw error; }
    const identityChanged = attached && partition(scope) !== partition(attached);
    const authorizationChanged = attached && scope.authorizationVersion !== attached.authorizationVersion;
    if (!attached || identityChanged || authorizationChanged) {
      await detach();
    }
    await retireOldGenerations(scope);
    await syncTabs(scope);
    return { attached: true, storageGeneration: scope.storageGeneration, tabs: scope.tabs, activeTabId: selected };
  }

  async function executeCommand(input) {
    if (input.type === 'attach') return attach(input.projectId, input.sessionId);
    if ((input.projectId || input.sessionId) && (!attached || attached.projectId !== input.projectId || attached.sessionId !== input.sessionId)) {
      if (['hide', 'suspend'].includes(input.type)) return;
      throw new Error('原预览会话已切换，请重新打开。');
    }
    if (input.type === 'hide') { bounds = null; for (const entry of views.values()) visible(entry); return; }
    if (input.type === 'suspend') { await detach(); return; }
    if (input.type === 'pageState') {
      if (!attached || stopped || !persist) return;
      if (input.entry && views.get(input.tabId) !== input.entry) return;
      let authorized, entry;
      try {
        authorized = await current(attached.projectId, attached.sessionId);
        if (partition(authorized) !== partition(attached) || authorized.authorizationVersion !== attached.authorizationVersion) {
          await detach(); await retireOldGenerations(authorized); publish({ projectId: authorized.projectId, sessionId: authorized.sessionId, closed: true, error: '预览授权已变化，请刷新。' }); return;
        }
        if (authorized.version !== attached.version) await syncTabs(authorized);
        if (!authorized.tabs.some(tab => tab.id === input.tabId)) return;
        entry = page(input.tabId);
        const url = destination(entry.contents.getURL(), authorized, connection);
        const tab = { id: entry.id, url: savedAddress(url), title: entry.contents.getTitle().slice(0, 180) };
        const value = await persist(authorized, tab);
        attached = value;
        publish({ projectId: value.projectId, sessionId: value.sessionId, configuration: value });
      } catch (error) {
        if (entry) emit(entry, error.message);
        else { const previous = attached; await detach(); publish({ projectId: previous?.projectId, sessionId: previous?.sessionId, closed: true, error: error.message }); }
      }
      return;
    }
    if (!['bounds', 'select'].includes(input.type)) {
      const now = Date.now(); rate.timestamps = rate.timestamps.filter(value => now - value < 1000);
      if (rate.timestamps.length >= 20) throw new Error('预览操作过于频繁，请稍后重试。');
      rate.timestamps.push(now);
    }
    if (!attached || stopped) throw new Error('请先打开原会话预览。');
    const authorized = await current(attached.projectId, attached.sessionId);
    if (partition(authorized) !== partition(attached) || authorized.authorizationVersion !== attached.authorizationVersion) {
      const previous = attached; await detach();
      if (authorized.storageGeneration !== previous.storageGeneration) await retireOldGenerations(authorized);
      throw new Error('原预览授权已变化，请刷新。');
    }
    if (input.type === 'open') {
      if (authorized.version !== input.expectedVersion) throw new Error('预览配置已变化，请刷新后重新打开地址。');
      if (!persist) throw new Error('预览地址保存能力不可用。');
      const url = destination(input.url, authorized, connection);
      const value = await persist(authorized, { id: input.tabId, url: savedAddress(url), title: '' }, { activate: true });
      await syncTabs(value, input.tabId);
      navigate(page(input.tabId), url);
      return value;
    }
    if (authorized.version !== attached.version) await syncTabs(authorized, input.type === 'navigate' ? input.tabId : '');
    if (input.type === 'bounds') {
      const value = input.bounds;
      if (!value || !['x', 'y', 'width', 'height'].every(key => Number.isInteger(value[key])) || value.x < 0 || value.y < 0 || value.width < 100 || value.height < 100) throw new Error('预览布局尺寸无效。');
      const [width, height] = owner.getContentSize();
      if (value.x + value.width > width || value.y + value.height > height) throw new Error('预览布局超出当前窗口。');
      bounds = value; for (const entry of views.values()) visible(entry); return;
    }
    const entry = page(input.tabId);
    if (input.type === 'select') {
      if (persist && authorized.activeTabId !== entry.id) attached = await persist(authorized, authorized.tabs.find(tab => tab.id === entry.id), { activate: true });
      selected = entry.id; for (const entry of views.values()) visible(entry); entry.contents.focus();
      publish({ projectId: attached.projectId, sessionId: attached.sessionId, configuration: attached }); return;
    }
    if (input.type === 'navigate') { navigate(entry, input.url); return; }
    if (input.type === 'reload') { entry.contents.reload(); return; }
    if (input.type === 'back' && entry.contents.navigationHistory.canGoBack()) { entry.contents.navigationHistory.goBack(); return; }
    if (input.type === 'forward' && entry.contents.navigationHistory.canGoForward()) { entry.contents.navigationHistory.goForward(); return; }
    if (input.type === 'clearSite') {
      const url = new URL(destination(entry.contents.getURL(), attached, connection));
      const siteSession = entry.contents.session;
      const matching = [...views.values()].filter(value => {
        try { return new URL(value.contents.getURL()).origin === url.origin; } catch { return false; }
      });
      const restore = matching.map(value => ({ id: value.id, url: value.contents.getURL(), title: value.contents.getTitle() }));
      for (const value of matching) views.delete(value.id);
      await Promise.all(matching.map(destroyEntry));
      await siteSession.clearStorageData({ origin: url.origin, storages: ['cookies', 'localstorage', 'indexdb', 'serviceworkers', 'cachestorage', 'filesystem'] });
      await siteSession.clearAuthCache();
      for (const tab of restore) {
        const replacement = create(tab);
        navigate(replacement, tab.url);
      }
      for (const value of views.values()) visible(value);
      return { cleared: true, origin: url.origin };
    }
    throw new Error('预览动作不可用。');
  }

  function command(input) {
    const operation = operationQueue.then(() => executeCommand(input), () => executeCommand(input));
    operationQueue = operation.catch(() => {});
    return operation;
  }

  const timer = setInterval(() => {
    if (!attached || stopped) return;
    if (pollQueued) return;
    pollQueued = true;
    const operation = operationQueue.then(async () => {
      if (!attached || stopped) return;
      const previous = attached, value = await current(previous.projectId, previous.sessionId);
      if (partition(value) !== partition(previous) || value.authorizationVersion !== previous.authorizationVersion) {
        await detach();
        if (value.storageGeneration !== previous.storageGeneration) await retireOldGenerations(value);
        publish({ projectId: previous.projectId, sessionId: previous.sessionId, closed: true, error: '预览授权已变化，请刷新。' });
      } else if (value.version !== previous.version) await syncTabs(value);
    });
    operationQueue = operation.catch(async error => { const previous = attached; await detach(); if (previous) await retireOldGenerations(previous).catch(() => {}); publish({ closed: true, error: error.message }); }).finally(() => { pollQueued = false; });
  }, 1000);
  timer.unref();

  return { command, async close() {
    stopped = true; clearInterval(timer);
    const operation = operationQueue.then(async () => {
      await detach();
      for (const record of partitions.values()) { await record.session.cookies.flushStore(); await record.session.flushStorageData(); }
    });
    operationQueue = operation.catch(() => {});
    await operation;
  } };
}

module.exports = { createPreviewHost };
