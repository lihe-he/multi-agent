import { expect, test } from '@playwright/test';

const emptyState = { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} };
const project = (name: string) => ({ id: 'page-project', name, path: 'fixture', branch: 'main', fileCount: 0 });
const pauseClock = async (page: import('@playwright/test').Page) => {
  await page.clock.install({ time: new Date('2026-11-18T12:00:00Z') });
  await page.clock.pauseAt(new Date('2026-11-18T12:00:01Z'));
};

test('网络提示触发实际读取核验，读取失败后联网立即恢复原令牌与轮询', async ({ page, context }) => {
  let configurationReads = 0, reads = 0, offline = false, recovered = false; const requests: { method: string; authorization?: string }[] = [];
  await page.addInitScript(() => sessionStorage.setItem('workbench-token', 'page-recovery-fixture'));
  await page.route('**/workbench-config.json', route => { configurationReads++; return route.fulfill({ json: { apiBaseUrl: '' } }); });
  await page.route('**/api/**', route => {
    reads++; requests.push({ method: route.request().method(), authorization: route.request().headers().authorization });
    if (offline) return route.abort('internetdisconnected');
    return route.fulfill({ json: { ...emptyState, projects: [project(recovered ? '联网后的项目' : '原项目')] } });
  });
  await pauseClock(page); await page.goto('/'); await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  offline = true; await context.setOffline(true);
  await expect(page.getByRole('button', { name: '本地核心未连接', exact: true })).toBeVisible();
  const failedReads = reads; await page.clock.runFor(6500); await expect.poll(() => reads).toBeGreaterThan(failedReads);
  offline = false; recovered = true; await context.setOffline(false);
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['联网后的项目']);
  const resumedReads = reads;
  await page.evaluate(() => { window.dispatchEvent(new Event('online')); window.dispatchEvent(new Event('online')); });
  expect(reads).toBe(resumedReads);
  await page.clock.runFor(3100); await expect.poll(() => reads).toBeGreaterThan(resumedReads);
  expect(configurationReads).toBe(1); expect(requests.every(request => request.method === 'GET' && request.authorization === 'Bearer page-recovery-fixture')).toBe(true);
});

test('初始网络提示离线仍读取可达核心，重复网络事件不替代真实连接结果', async ({ page }) => {
  let reads = 0, configurationReads = 0, changed = false;
  await page.addInitScript(() => Object.defineProperty(navigator, 'onLine', { configurable: true, value: false }));
  await page.route('**/workbench-config.json', route => { configurationReads++; return route.fulfill({ json: { apiBaseUrl: '' } }); });
  await page.route('**/api/state', route => { reads++; return route.fulfill({ json: { ...emptyState, projects: [project(changed ? '仍可达的本地项目' : '离线提示下的本地项目')] } }); });
  await pauseClock(page); await page.goto('/');
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['离线提示下的本地项目']);
  const connectedReads = reads; changed = true; await page.clock.runFor(3100);
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['仍可达的本地项目']);
  expect(reads).toBeGreaterThan(connectedReads);
  await page.evaluate(() => { window.dispatchEvent(new Event('online')); window.dispatchEvent(new Event('online')); });
  await expect.poll(() => reads).toBeGreaterThan(connectedReads + 1);
  const verifiedReads = reads;
  await page.evaluate(() => { window.dispatchEvent(new Event('offline')); window.dispatchEvent(new Event('offline')); });
  await expect.poll(() => reads).toBe(verifiedReads + 1);
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  expect(configurationReads).toBe(1); expect(await page.evaluate(() => navigator.onLine)).toBe(false);
});

test('受控页恢复取消旧读取，迟到旧状态不能覆盖恢复后的项目', async ({ page }) => {
  let release: () => void = () => {}, firstRead = false, oldReturned = false, reads = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/state', async route => {
    const old = ++reads === 1;
    if (old) { firstRead = true; await gate; }
    await route.fulfill({ json: { ...emptyState, projects: [project(old ? '过期项目' : '恢复后的项目')] } }).catch(() => {});
    if (old) oldReturned = true;
  });
  await pauseClock(page); await page.goto('/'); await expect.poll(() => firstRead).toBe(true);
  await page.evaluate(() => { window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })); window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })); });
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['恢复后的项目']);
  release(); await expect.poll(() => oldReturned).toBe(true);
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['恢复后的项目']);
  expect(reads).toBe(2);
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
  expect(reads).toBe(2);
});

test('前台返回时重新核对超过二十秒的旧读取，重复可见事件不重叠请求', async ({ page }) => {
  let release: () => void = () => {}, reads = 0, oldReturned = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/state', async route => {
    const old = ++reads === 1;
    if (old) await gate;
    await route.fulfill({ json: { ...emptyState, projects: [project(old ? '旧前台项目' : '前台核对后的项目')] } }).catch(() => {});
    if (old) oldReturned = true;
  });
  await pauseClock(page); await page.goto('/'); await expect.poll(() => reads).toBe(1);
  await page.clock.setSystemTime(new Date('2026-11-18T12:00:25Z'));
  await page.evaluate(() => { document.dispatchEvent(new Event('visibilitychange')); document.dispatchEvent(new Event('visibilitychange')); });
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['前台核对后的项目']);
  expect(reads).toBe(2); release(); await expect.poll(() => oldReturned).toBe(true);
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['前台核对后的项目']);
});

test('初始化明确失败后，联网和受控页恢复不自动重读配置或派发任务', async ({ page, context }) => {
  let reads = 0, apiRequests = 0;
  await page.route('**/workbench-config.json', route => { reads++; return route.fulfill({ status: 503, json: {} }); });
  await page.route('**/api/**', route => { apiRequests++; return route.fulfill({ json: emptyState }); });
  await pauseClock(page); await page.goto('/'); await expect(page.locator('.connection-banner')).toContainText('无法读取工作台运行时配置');
  await context.setOffline(true); await context.setOffline(false);
  await page.evaluate(() => { window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })); window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })); });
  await page.clock.setSystemTime(new Date('2026-11-18T12:00:25Z'));
  await page.evaluate(() => document.dispatchEvent(new Event('visibilitychange')));
  await page.clock.runFor(10000); expect(reads).toBe(1); expect(apiRequests).toBe(0);
  await expect(page.locator('.connection-banner')).toContainText('无法读取工作台运行时配置');
  await expect(page.getByRole('button', { name: '重试连接', exact: true })).toBeEnabled();
  await page.getByRole('button', { name: '重试连接', exact: true }).click(); await expect.poll(() => reads).toBe(2);
});

test('受控会话恢复按原游标续读并去重，保留更早记录、选择与未提交笔记', async ({ page }) => {
  let recovered = false; const messageQueries: string[] = [], eventQueries: string[] = [], writes: string[] = [];
  const session = { id: 'page-session', projectId: 'page-project', title: '恢复中的会话', version: 1, isOpen: true, createdAt: '2026-11-18T00:00:00Z', updatedAt: '2026-11-18T00:00:00Z' };
  const message = (sequence: number, content: string) => ({ id: `message-${sequence}`, sessionId: session.id, projectId: session.projectId, kind: 'NOTE', sequence, content, createdAt: '2026-11-18T00:00:00Z' });
  const event = (id: number) => ({ id, type: 'ACTIVITY', message: `持久活动${id}`, createdAt: '2026-11-18T00:00:00Z' });
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/**', route => {
    const url = new URL(route.request().url()); if (route.request().method() !== 'GET') writes.push(url.pathname);
    if (url.pathname === '/api/state') return route.fulfill({ json: { ...emptyState, projects: [project('恢复项目')] } });
    if (url.pathname === '/api/projects/page-project/sessions') return route.fulfill({ json: { sessions: [session], cursor: 0, hasMore: false } });
    if (url.pathname.endsWith('/messages')) {
      messageQueries.push(url.search);
      if (url.searchParams.has('before')) return route.fulfill({ json: { messages: [message(1, '更早的持久笔记')], cursor: 0, latestCursor: 52, hasMore: false } });
      return route.fulfill({ json: { messages: recovered ? [message(52, '原笔记52'), message(53, '恢复新增笔记53')] : [message(51, '原笔记51'), message(52, '原笔记52')], cursor: 51, latestCursor: recovered ? 53 : 52, hasMore: !recovered } });
    }
    if (url.pathname.endsWith('/events')) { eventQueries.push(url.search); return route.fulfill({ json: { events: recovered ? [event(10), event(11)] : [event(10)], cursor: recovered ? 11 : 10, hasMore: false } }); }
    if (url.pathname.endsWith('/agent-runs')) return route.fulfill({ json: { runs: [], cursor: 0, hasMore: false } });
    return route.fulfill({ json: {} });
  });
  await pauseClock(page); await page.goto('/'); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: '恢复中的会话' })).toHaveAttribute('aria-selected', 'true');
  await page.getByRole('button', { name: '加载更早记录', exact: true }).click(); await expect(page.getByText('更早的持久笔记', { exact: true })).toBeVisible();
  await page.getByLabel('协作笔记', { exact: true }).fill('尚未提交的恢复草稿');
  await page.getByLabel('当前会话名称', { exact: true }).fill('尚未提交的名称');
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pagehide', { persisted: true })));
  recovered = true; await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
  await expect(page.getByText('恢复新增笔记53', { exact: true })).toBeVisible(); await expect(page.getByText('持久活动11', { exact: true })).toBeVisible();
  expect(messageQueries.at(-1)).toContain('after=52'); expect(eventQueries.at(-1)).toContain('after=10');
  await expect(page.getByText('原笔记52', { exact: true })).toHaveCount(1); await expect(page.getByText('持久活动10', { exact: true })).toHaveCount(1);
  await expect(page.getByText('更早的持久笔记', { exact: true })).toBeVisible();
  await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('尚未提交的恢复草稿');
  await expect(page.getByLabel('当前会话名称', { exact: true })).toHaveValue('尚未提交的名称');
  await expect(page.getByRole('tab', { name: '恢复中的会话' })).toHaveAttribute('aria-selected', 'true'); expect(writes).toEqual([]);
});

test('会话读取的取消信号保留状态期限，超时后仍可明确读取原记录', async ({ page }) => {
  let release: () => void = () => {}, reads = 0; const gate = new Promise<void>(resolve => { release = resolve; });
  await page.addInitScript(() => {
    const original = AbortSignal.timeout.bind(AbortSignal);
    AbortSignal.timeout = milliseconds => original(milliseconds === 30000 ? 120 : milliseconds);
  });
  const session = { id: 'deadline-session', projectId: 'page-project', title: '有界读取会话', version: 1, isOpen: true };
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (path === '/api/state') return route.fulfill({ json: { ...emptyState, projects: [project('期限项目')] } });
    if (path === '/api/projects/page-project/sessions') return route.fulfill({ json: { sessions: [session], cursor: 0, hasMore: false } });
    if (path.endsWith('/messages')) {
      reads++; await gate;
      return route.fulfill({ json: { messages: [{ id: 'bounded-note', sequence: 1, kind: 'NOTE', content: '期限后仍保存的原记录' }], cursor: 0, latestCursor: 1, hasMore: false } }).catch(() => {});
    }
    if (path.endsWith('/events')) return route.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: { runs: [], cursor: 0, hasMore: false } });
    return route.fulfill({ json: {} });
  });
  try {
    await page.goto('/'); await page.getByRole('button', { name: '协作会话', exact: true }).click();
    await expect(page.getByRole('tabpanel').getByRole('alert')).toContainText('signal timed out');
    expect(reads).toBe(1); release();
    await page.getByRole('button', { name: '刷新活动', exact: true }).click();
    await expect(page.getByText('期限后仍保存的原记录', { exact: true })).toBeVisible();
    await expect(page.getByRole('tabpanel').getByRole('alert')).toHaveCount(0);
  } finally { release(); }
});
