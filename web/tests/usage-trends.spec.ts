import { expect, test } from '@playwright/test';
import type { Page, Route } from '@playwright/test';
import type { UsageAggregate, UsageMetric, UsageTrendsResponse, UsageUnit, UsageWindow } from '../src/usage-types';

const project = (id = 'usage-a') => ({ id, name: id === 'usage-a' ? '用量测试项目' : '另一个用量项目', path: 'fixture', branch: 'main', fileCount: 0 });
const sessions = Array.from({ length: 50 }, (_, index) => ({ id: index === 0 ? 's-main' : `s-${index}`, projectId: 'usage-a', title: index === 0 ? '当前研发会话' : `研发会话 ${index}`, version: 1, isOpen: index % 2 === 0, createdAt: '2026-11-26T00:00:00Z', updatedAt: '2026-11-26T00:00:00Z' }));
const closedSession = { ...sessions[0], id: 's-closed', title: '更早的关闭会话', isOpen: false };
const tasks = [
  { id: 't-main', projectId: 'usage-a', sessionId: 's-main', title: '当前会话任务', goal: 'fixture', state: 'RUNNING', mode: 'PIPELINE' },
  { id: 't-other', projectId: 'usage-a', sessionId: 's-1', title: '别的会话任务', goal: 'fixture', state: 'COMPLETED', mode: 'PIPELINE' },
  { id: 't-closed', projectId: 'usage-a', sessionId: 's-closed', title: '历史会话任务', goal: 'fixture', state: 'COMPLETED', mode: 'PIPELINE' },
  { id: 't-foreign', projectId: 'usage-b', sessionId: 's-main', title: '其他项目任务', goal: 'fixture', state: 'RUNNING', mode: 'PIPELINE' },
];
const metric = (sum: number | 'UNKNOWN' = 0, known = 0, unknown = 0, overflow = false): UsageMetric => ({ observedSum: sum, knownCount: known, unknownCount: unknown, total: known > 0 && unknown === 0 && !overflow ? sum : 'UNKNOWN', overflow });
function emptyUnit(): UsageUnit {
  return { recordCount: 0, hasRecords: false, states: { DISPATCHING: 0, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0, UNKNOWN: 0 }, tokens: { inputTokens: metric(), outputTokens: metric(), cachedInputTokens: metric(), reasoningTokens: metric() } };
}
function aggregate(empty = false): UsageAggregate {
  const api = emptyUnit(), cli = emptyUnit(), unknown = emptyUnit();
  if (!empty) {
    Object.assign(api, { recordCount: 4, hasRecords: true, states: { DISPATCHING: 1, SUCCEEDED: 2, FAILED: 1, CANCELLED: 0, UNKNOWN: 0 }, tokens: { inputTokens: metric(100, 3, 1), outputTokens: metric(0, 4), cachedInputTokens: metric(40, 2, 2), reasoningTokens: metric('UNKNOWN', 4, 0, true) } });
    Object.assign(cli, { recordCount: 2, hasRecords: true, states: { DISPATCHING: 0, SUCCEEDED: 1, FAILED: 0, CANCELLED: 1, UNKNOWN: 0 }, tokens: { inputTokens: metric(1000, 1, 1), outputTokens: metric(0, 0, 2), cachedInputTokens: metric(0, 0, 2), reasoningTokens: metric(0, 0, 2) } });
    Object.assign(unknown, { recordCount: 1, hasRecords: true, states: { DISPATCHING: 0, SUCCEEDED: 0, FAILED: 0, CANCELLED: 0, UNKNOWN: 1 }, tokens: { inputTokens: metric(0, 1), outputTokens: metric(0, 0, 1), cachedInputTokens: metric(0, 0, 1), reasoningTokens: metric(0, 0, 1) } });
  }
  return { recordCount: empty ? 0 : 7, hasRecords: !empty, units: { API_REQUEST: api, CLI_SESSION: cli, UNKNOWN: unknown } };
}
function response(url: URL, empty = false): UsageTrendsResponse {
  const days = Number(url.searchParams.get('days') || 7) as UsageWindow;
  const end = new Date('2026-11-27T00:00:00Z'), from = new Date(end.getTime() - days * 86400000);
  const buckets = Array.from({ length: days }, (_, index) => ({ date: new Date(from.getTime() + index * 86400000).toISOString().slice(0, 10), ...aggregate(empty || index !== days - 1) }));
  return { schemaVersion: 1, source: 'MODEL_REQUEST', scope: { projectId: url.pathname.split('/projects/')[1].split('/')[0], sessionId: url.searchParams.get('sessionId') || '', taskId: url.searchParams.get('taskId') || '' }, days, timeZone: 'UTC', bucket: 'DAY', from: from.toISOString(), toExclusive: end.toISOString(), asOf: '2026-11-26T12:34:56Z', cost: 'UNKNOWN', cliInternalRequests: 'UNKNOWN', summary: aggregate(empty), buckets };
}
function withInput(value: UsageTrendsResponse, total: number) {
  const unit = value.summary.units.API_REQUEST;
  unit.tokens.inputTokens = metric(total, unit.recordCount);
  value.buckets[value.buckets.length - 1].units.API_REQUEST.tokens.inputTokens = { ...unit.tokens.inputTokens };
  return value;
}
type FixtureOptions = { noProjects?: boolean; usage?: (route: Route, url: URL) => Promise<unknown>; session?: (route: Route, url: URL) => Promise<unknown> };
async function fixture(page: Page, options: FixtureOptions = {}) {
  const requests: { path: string; query: string; method: string; authorization?: string }[] = [], errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()); const path = url.pathname.slice(url.pathname.indexOf('/api'));
    requests.push({ path: url.pathname, query: url.search, method: request.method(), authorization: request.headers().authorization });
    if (path === '/api/state') return route.fulfill({ json: { projects: options.noProjects ? [] : [project(), project('usage-b')], tasks, routes: [], profiles: [], memories: [], system: { storageHealthy: true } } });
    if (path.endsWith('/usage-trends')) return options.usage ? options.usage(route, url) : route.fulfill({ json: response(url) });
    if (path.endsWith('/sessions')) {
      if (options.session) return options.session(route, url);
      if (path.includes('/usage-b/')) return route.fulfill({ json: { sessions: [], cursor: 0, hasMore: false } });
      return route.fulfill({ json: url.searchParams.get('before') === '101' ? { sessions: [closedSession], cursor: 0, hasMore: false } : { sessions, cursor: 101, hasMore: true } });
    }
    return route.fulfill({ json: {} });
  });
  await page.goto('/'); await page.getByRole('button', { name: '用量趋势', exact: true }).click();
  return { requests, errors };
}
const card = (page: Page, unit = 'API 请求') => page.getByRole('region', { name: `${unit}用量`, exact: true });
const token = (page: Page, name: string, unit = 'API 请求') => card(page, unit).locator('.usage-token-grid > div').filter({ has: page.locator('dt', { hasText: name }) });

test('三种计量单位独立呈现，每字段覆盖率区分部分、未知、明确零及精度溢出', async ({ page }) => {
  const data = await fixture(page);
  await expect(card(page).locator('.usage-record-count > b')).toHaveText('4');
  await expect(card(page, 'CLI 会话').locator('.usage-record-count > b')).toHaveText('2');
  await expect(card(page, '未归类记录').locator('.usage-record-count > b')).toHaveText('1');
  await expect(token(page, '输入 tokens')).toContainText('未知'); await expect(token(page, '输入 tokens')).toContainText('已知部分 100');
  await expect(token(page, '输入 tokens')).toContainText('已知 3/4 · 未知 1');
  await expect(token(page, '输出 tokens').locator('strong')).toHaveText('0'); await expect(token(page, '输出 tokens')).toContainText('已知 4/4');
  await expect(token(page, '缓存输入')).toContainText('已含在输入内'); await expect(token(page, '缓存输入')).toContainText('已知部分 40');
  await expect(token(page, '推理输出')).toContainText('超出安全精度'); await expect(token(page, '推理输出')).toContainText('已知 4/4');
  await expect(card(page).locator('footer')).toContainText('派发中 1');
  await expect(page.getByRole('complementary', { name: '用量统计说明' })).toContainText('CLI 内部请求数：未知；费用：未知');
  await expect(page.locator('.usage-chart-point')).toHaveCount(7);
  await expect(page.locator('.usage-chart-point').last()).toHaveAccessibleName('2026-11-26 UTC：4 条记录');
  await page.getByLabel('趋势指标', { exact: true }).selectOption('inputTokens');
  await expect(page.locator('.usage-chart-point').last()).toHaveAccessibleName('2026-11-26 UTC：未知 · 已知部分 100');
  await expect(page.locator('.usage-chart-point').last()).toHaveClass(/partial/);
  await page.getByRole('tab', { name: 'CLI 会话', exact: true }).click();
  await expect(page.locator('.usage-table caption')).toHaveText('CLI 会话每日用量 · UTC · 7 天');
  await expect(page.locator('.usage-chart-point').last()).toHaveAccessibleName('2026-11-26 UTC：未知 · 已知部分 1,000');
  expect(data.requests.every(request => request.method === 'GET')).toBe(true); expect(data.errors).toEqual([]);
});

test('7/30/90 UTC窗口包含今天，空范围保留无记录而不是合成零用量', async ({ page }) => {
  const data = await fixture(page, { usage: (route, url) => route.fulfill({ json: response(url, true) }) });
  for (const days of [7, 30, 90]) {
    await page.getByRole('button', { name: `近 ${days} 天`, exact: true }).click();
    await expect(page.getByRole('button', { name: `近 ${days} 天`, exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.locator('.usage-table tbody tr')).toHaveCount(days);
    await expect(page.locator('.usage-chart-point')).toHaveCount(days);
    await expect(page.locator('.usage-table tbody tr').last().getByRole('rowheader')).toHaveText('2026-11-26');
    await expect(page.getByRole('heading', { name: '所选范围暂无请求记录', exact: true })).toBeVisible();
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('—');
    await expect(page.locator('.usage-chart-point').last()).toHaveAccessibleName('2026-11-26 UTC：无记录');
    await expect(page.locator('.usage-chart-point').last()).toHaveClass(/empty/);
  }
  expect(data.requests.filter(request => request.path.endsWith('/usage-trends')).some(request => request.query.includes('days=90'))).toBe(true);
  expect(data.requests.every(request => request.method === 'GET')).toBe(true); expect(data.errors).toEqual([]);
});

test('会话分页包含关闭历史，仅列当前会话任务且切换会话清空任务', async ({ page }) => {
  const data = await fixture(page);
  await expect(page.getByLabel('任务范围', { exact: true })).toBeDisabled();
  await expect(page.getByLabel('会话范围', { exact: true }).locator('option')).toHaveCount(51);
  await page.getByRole('button', { name: '加载更多会话', exact: true }).click();
  await expect(page.getByLabel('会话范围', { exact: true }).locator('option')).toHaveCount(52);
  await expect(page.getByLabel('会话范围', { exact: true }).locator('option[value="s-closed"]')).toHaveText('更早的关闭会话（已关闭）');
  await page.getByLabel('会话范围', { exact: true }).selectOption('s-main');
  await expect(page.getByLabel('任务范围', { exact: true }).locator('option')).toHaveText(['当前会话全部任务', '当前会话任务']);
  await page.getByLabel('任务范围', { exact: true }).selectOption('t-main');
  await expect.poll(() => data.requests.filter(request => request.path.endsWith('/usage-trends')).at(-1)?.query).toContain('taskId=t-main');
  await page.getByLabel('会话范围', { exact: true }).selectOption('s-closed');
  await expect(page.getByLabel('任务范围', { exact: true })).toHaveValue('');
  await expect(page.getByLabel('任务范围', { exact: true }).locator('option')).toHaveText(['当前会话全部任务', '历史会话任务']);
  await expect.poll(() => data.requests.filter(request => request.path.endsWith('/usage-trends')).at(-1)?.query).toContain('sessionId=s-closed&taskId=');
  expect(data.requests.filter(request => request.path.endsWith('/sessions')).every(request => request.query.includes('openOnly=false'))).toBe(true);
  expect(data.requests.some(request => request.path.endsWith('/sessions') && request.query.includes('before=101'))).toBe(true);
  expect(data.requests.every(request => request.method === 'GET')).toBe(true); expect(data.errors).toEqual([]);
});

test('GET失败可重读且不触发执行，成功后继续自动更新原日期派发状态', async ({ page }) => {
  let reads = 0;
  const data = await fixture(page, { usage: async (route, url) => {
    if (++reads <= 2) return route.fulfill({ status: 503, json: { message: '持久统计暂时不可用' } });
    const next = response(url);
    if (reads >= 4) for (const value of [next.summary.units.API_REQUEST, next.buckets[next.buckets.length - 1].units.API_REQUEST]) { value.states.DISPATCHING = 0; value.states.SUCCEEDED = 3; }
    return route.fulfill({ json: next });
  } });
  await expect(page.getByRole('alert')).toContainText('持久统计暂时不可用');
  await page.getByRole('button', { name: '重新读取用量', exact: true }).click();
  await expect(card(page).locator('footer')).toContainText('派发中 1'); await expect(page.getByRole('alert')).toHaveCount(0);
  await expect(card(page).locator('footer')).toContainText('派发中 0', { timeout: 10000 });
  await expect(card(page).locator('footer')).toContainText('成功 3');
  await expect(page.locator('.usage-table tbody tr').last().getByRole('rowheader')).toHaveText('2026-11-26');
  await expect(card(page).locator('.usage-record-count > b')).toHaveText('4');
  expect(data.requests.every(request => request.method === 'GET')).toBe(true); expect(data.errors).toEqual([]);
});

test('会话首读失败可单独恢复，筛选名按纯文本呈现', async ({ page }) => {
  let sessionReads = 0;
  const text = '<img src=x onerror="window.__usageInjected=true">关闭会话';
  const data = await fixture(page, { session: async route => {
    if (++sessionReads <= 2) return route.fulfill({ status: 500, json: { message: '会话页读取失败' } });
    return route.fulfill({ json: { sessions: [{ ...closedSession, title: text }], cursor: 0, hasMore: false } });
  } });
  await expect(card(page)).toBeVisible(); await expect(page.getByRole('alert')).toContainText('会话页读取失败');
  await page.getByRole('button', { name: '重新读取会话', exact: true }).click();
  await expect(page.getByLabel('会话范围', { exact: true }).locator('option[value="s-closed"]')).toHaveText(`${text}（已关闭）`);
  await page.getByLabel('会话范围', { exact: true }).selectOption('s-closed');
  await expect(page.getByRole('alert')).toHaveCount(0);
  expect(await page.evaluate(() => (window as Window & { __usageInjected?: boolean }).__usageInjected)).toBeUndefined();
  await expect(page.locator('.usage-trends img')).toHaveCount(0); expect(data.errors).toEqual([]);
});

test('旧窗口和旧筛选响应迟到不能覆盖当前快照，切换项目重置范围', async ({ page }) => {
  let release: () => void = () => {}, oldRead = false, returned = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const data = await fixture(page, { usage: async (route, url) => {
    if (url.searchParams.get('days') === '30') { oldRead = true; await gate; await route.fulfill({ json: withInput(response(url), 999999) }).catch(() => {}); returned = true; return; }
    return route.fulfill({ json: withInput(response(url), url.pathname.includes('/usage-b/') ? 222 : 111) });
  } });
  try {
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('111');
    await page.getByRole('button', { name: '近 30 天', exact: true }).click(); await expect.poll(() => oldRead).toBe(true);
    await page.getByRole('button', { name: '近 90 天', exact: true }).click(); await expect(page.locator('.usage-table tbody tr')).toHaveCount(90);
    await page.getByLabel('会话范围', { exact: true }).selectOption('s-main'); await page.getByLabel('任务范围', { exact: true }).selectOption('t-main');
    await page.getByLabel('选择项目', { exact: true }).selectOption('usage-b');
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('222');
    await expect(page.getByRole('button', { name: '近 7 天', exact: true })).toHaveAttribute('aria-pressed', 'true');
    await expect(page.getByLabel('会话范围', { exact: true })).toHaveValue(''); await expect(page.getByLabel('任务范围', { exact: true })).toHaveValue('');
    release(); await expect.poll(() => returned).toBe(true);
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('222');
    await expect(page.locator('.usage-table tbody tr')).toHaveCount(7);
    expect(data.requests.filter(request => request.path.includes('/usage-b/') && request.path.endsWith('/usage-trends')).every(request => !/sessionId=s|taskId=t/.test(request.query))).toBe(true);
    expect(data.errors).toEqual([]);
  } finally { release(); }
});

test('同地址更新令牌后清空原会话和任务，旧连接响应不能覆盖', async ({ page }) => {
  let release: () => void = () => {}, oldRead = false, returned = false;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const data = await fixture(page, { usage: async (route, url) => {
    const fresh = route.request().headers().authorization === 'Bearer next-usage-token';
    if (!fresh && url.searchParams.get('taskId') === 't-main') { oldRead = true; await gate; await route.fulfill({ json: withInput(response(url), 999999) }).catch(() => {}); returned = true; return; }
    return route.fulfill({ json: withInput(response(url), fresh ? 333 : 111) });
  } });
  try {
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('111');
    await page.getByLabel('会话范围', { exact: true }).selectOption('s-main'); await page.getByLabel('任务范围', { exact: true }).selectOption('t-main');
    await expect.poll(() => oldRead).toBe(true);
    await page.getByRole('button', { name: '本地核心已连接', exact: true }).click();
    await page.getByLabel('访问令牌', { exact: true }).fill('next-usage-token'); await page.getByRole('button', { name: '连接并验证', exact: true }).click();
    await expect(page.getByRole('dialog')).toBeHidden();
    await expect(token(page, '输入 tokens').locator('strong')).toHaveText('333');
    await expect(page.getByLabel('会话范围', { exact: true })).toHaveValue(''); await expect(page.getByLabel('任务范围', { exact: true })).toHaveValue('');
    release(); await expect.poll(() => returned).toBe(true); await expect(token(page, '输入 tokens').locator('strong')).toHaveText('333');
    expect(data.requests.filter(request => request.authorization === 'Bearer next-usage-token' && request.path.endsWith('/usage-trends')).every(request => request.query.includes('sessionId=&taskId='))).toBe(true);
    expect(data.errors).toEqual([]);
  } finally { release(); }
});

test('离开页面取消在途读取，返回后继续GET', async ({ page }) => {
  let release: () => void = () => {}, held = false, first = true;
  const gate = new Promise<void>(resolve => { release = resolve; });
  const data = await fixture(page, { usage: async (route, url) => {
    if (first) { first = false; held = true; await gate; }
    await route.fulfill({ json: response(url) }).catch(() => {});
  } });
  try {
    await expect.poll(() => held).toBe(true);
    await page.getByRole('button', { name: '研发任务', exact: true }).click();
    release(); await expect(page.locator('.usage-trends')).toHaveCount(0);
    const count = data.requests.filter(request => request.path.endsWith('/usage-trends')).length;
    await page.getByRole('button', { name: '用量趋势', exact: true }).click();
    await expect(card(page)).toBeVisible();
    expect(data.requests.filter(request => request.path.endsWith('/usage-trends')).length).toBeGreaterThan(count);
    expect(data.requests.every(request => request.method === 'GET')).toBe(true); expect(data.errors).toEqual([]);
  } finally { release(); }
});

test('没有项目显示项目入口空态且不发用量或会话请求', async ({ page }) => {
  const data = await fixture(page, { noProjects: true });
  await expect(page.getByRole('heading', { name: '请先选择一个项目', exact: true })).toBeVisible();
  expect(data.requests.some(request => /\/(usage-trends|sessions)$/.test(request.path))).toBe(false); expect(data.errors).toEqual([]);
});

for (const width of [1440, 430]) test(`90天图表和日表在${width}px局部滚动，键盘与纯文本标签可用`, async ({ page }, testInfo) => {
  await page.setViewportSize({ width, height: 1000 }); const data = await fixture(page);
  await page.getByRole('button', { name: '近 90 天', exact: true }).click(); await expect(page.locator('.usage-table tbody tr')).toHaveCount(90);
  await page.getByRole('region', { name: 'API 请求每日趋势图，可横向滚动', exact: true }).focus();
  await expect(page.getByRole('region', { name: 'API 请求每日趋势图，可横向滚动', exact: true })).toBeFocused();
  await expect(page.locator('.usage-chart-point').last()).toHaveAccessibleName('2026-11-26 UTC：4 条记录');
  const widths = await page.evaluate(() => {
    const chart = document.querySelector('.usage-chart-scroll')!, table = document.querySelector('.usage-table-scroll')!;
    return { body: document.documentElement.scrollWidth, viewport: innerWidth, chart: chart.scrollWidth > chart.clientWidth, table: table.scrollWidth > table.clientWidth };
  });
  expect(widths.body).toBeLessThanOrEqual(widths.viewport); expect(widths.chart).toBe(true);
  if (width === 430) expect(widths.table).toBe(true);
  await page.evaluate(() => window.scrollTo(0, 0));
  await page.screenshot({ path: testInfo.outputPath(`usage-trends-${width}.png`) });
  await page.locator('.usage-chart').screenshot({ path: testInfo.outputPath(`usage-trends-chart-${width}.png`) });
  expect(data.errors).toEqual([]);
});
