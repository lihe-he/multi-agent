import { expect, test } from '@playwright/test';

for (const width of [1440, 430]) test(`运行时配置失败可明确重读，保留令牌并恢复后续轮询 ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 });
  await page.addInitScript(() => sessionStorage.setItem('workbench-token', 'connection-recovery-fixture'));
  let reads = 0, stateReads = 0, release: () => void = () => {};
  const gate = new Promise<void>(resolve => { release = resolve; });
  const requests: { path: string; authorization?: string }[] = [];
  await page.route('**/workbench-config.json', async route => {
    reads++;
    if (reads === 1) return route.fulfill({ status: 503, json: {} });
    await gate; return route.fulfill({ json: { apiBaseUrl: '/runtime' } });
  });
  await page.route('**/api/**', route => {
    const path = new URL(route.request().url()).pathname;
    requests.push({ path, authorization: route.request().headers().authorization });
    stateReads++;
    return route.fulfill({ json: { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} } });
  });
  await page.goto('/');
  await expect(page.getByText('无法读取工作台运行时配置，请检查 workbench-config.json。')).toBeVisible();
  expect(reads).toBe(1); expect(requests).toHaveLength(0);
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await expect.poll(() => reads).toBe(2);
  await expect(page.getByRole('button', { name: '重试连接', exact: true })).toBeDisabled();
  release();
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  const firstReads = stateReads;
  await expect.poll(() => stateReads).toBeGreaterThan(firstReads);
  expect(reads).toBe(2); expect(requests.every(request => request.path === '/runtime/api/state' && request.authorization === 'Bearer connection-recovery-fixture')).toBe(true);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('手工验证连接后，迟到的初始配置不能覆盖新地址与令牌', async ({ page }) => {
  let release: () => void = () => {}, configurationRead = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const requests: { path: string; authorization?: string }[] = [];
  await page.route('**/workbench-config.json', async route => {
    configurationRead = true; await gate;
    await route.fulfill({ json: { apiBaseUrl: '/stale' } }).catch(() => {});
  });
  await page.route('**/api/**', route => {
    requests.push({ path: new URL(route.request().url()).pathname, authorization: route.request().headers().authorization });
    return route.fulfill({ json: { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} } });
  });
  await page.goto('/'); await expect.poll(() => configurationRead).toBe(true);
  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('本机核心地址', { exact: true }).fill('/chosen');
  await page.getByLabel('访问令牌', { exact: true }).fill('chosen-connection-fixture');
  await page.getByRole('button', { name: '连接并验证', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  release();
  const firstReads = requests.length;
  await expect.poll(() => requests.length).toBeGreaterThan(firstReads);
  expect(requests.every(request => request.path === '/chosen/api/state' && request.authorization === 'Bearer chosen-connection-fixture')).toBe(true);
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  await page.getByRole('button', { name: '本地核心已连接', exact: true }).click();
  await expect(page.getByLabel('本机核心地址', { exact: true })).toHaveValue('/chosen');
  await expect(page.getByLabel('访问令牌', { exact: true })).toHaveValue('chosen-connection-fixture');
});

test('手工连接恢复后持续轮询，初始化失败不导致任务自动派发', async ({ page }) => {
  let reads = 0; const methods: string[] = [];
  await page.route('**/workbench-config.json', route => route.fulfill({ status: 503, json: {} }));
  await page.route('**/api/**', route => {
    reads++; methods.push(route.request().method());
    return route.fulfill({ json: { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} } });
  });
  await page.goto('/');
  await expect(page.getByText('无法读取工作台运行时配置，请检查 workbench-config.json。')).toBeVisible();
  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('本机核心地址', { exact: true }).fill('/manual');
  await page.getByRole('button', { name: '连接并验证', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  const firstReads = reads;
  await expect.poll(() => reads).toBeGreaterThan(firstReads);
  expect(methods.every(method => method === 'GET')).toBe(true);
});

test('原五秒配置期限超时后可明确重试，旧配置返回不会重新连接', async ({ page }) => {
  let reads = 0, release: () => void = () => {};
  const gate = new Promise<void>(resolve => { release = resolve; });
  const requests: string[] = [];
  await page.route('**/workbench-config.json', async route => {
    if (++reads === 1) { await gate; return route.fulfill({ json: { apiBaseUrl: '/expired' } }).catch(() => {}); }
    return route.fulfill({ json: { apiBaseUrl: '/recovered' } });
  });
  await page.route('**/api/**', route => {
    requests.push(new URL(route.request().url()).pathname);
    return route.fulfill({ json: { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} } });
  });
  await page.goto('/');
  await expect(page.locator('.connection-banner')).toContainText('signal timed out', { timeout: 10000 });
  expect(requests).toHaveLength(0); expect(reads).toBe(1);
  await page.getByRole('button', { name: '重试连接', exact: true }).click();
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  release(); const firstReads = requests.length;
  await expect.poll(() => requests.length).toBeGreaterThan(firstReads);
  expect(reads).toBe(2); expect(requests.every(path => path === '/recovered/api/state')).toBe(true);
});

test('切换连接后旧状态请求迟到不会写回原项目', async ({ page }) => {
  let release: () => void = () => {}, oldStateRead = false, oldStateReturned = false, newReads = 0;
  const gate = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '/old' } }));
  await page.route('**/api/state', async route => {
    const old = new URL(route.request().url()).pathname === '/old/api/state';
    if (old) { oldStateRead = true; await gate; }
    else newReads++;
    await route.fulfill({ json: { projects: [{ id: old ? 'old-project' : 'new-project', name: old ? '旧连接项目' : '新连接项目', path: 'fixture', branch: 'main', fileCount: 0 }], tasks: [], routes: [], profiles: [], memories: [], system: {} } }).catch(() => {});
    if (old) oldStateReturned = true;
  });
  await page.goto('/'); await expect.poll(() => oldStateRead).toBe(true);
  await page.getByRole('button', { name: '连接设置', exact: true }).click();
  await page.getByLabel('本机核心地址', { exact: true }).fill('/new');
  await page.getByRole('button', { name: '连接并验证', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['新连接项目']);
  const readsBeforeRelease = newReads; release();
  await expect.poll(() => oldStateReturned).toBe(true);
  await expect.poll(() => newReads).toBeGreaterThan(readsBeforeRelease);
  await expect(page.getByLabel('选择项目').locator('option')).toHaveText(['新连接项目']);
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
});
