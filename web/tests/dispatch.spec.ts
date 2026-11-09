import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

// Browser protocol fixtures exercise rendering and controls; Java and the real
// Jar separately verify durable claims, process recovery and execution effects.
async function fixture(page: Page) {
  const project = { id: 'dispatch-project', name: '持久队列项目', path: 'D:\\Projects\\中文 仓库' };
  const route = { id: 'dispatch-route', name: '本机模型', engine: 'API', version: 1, model: 'fixture', enabled: true };
  const task: any = { id: 'dispatch-task', projectId: project.id, title: '保存原任务派发', goal: '分别保留排队、执行与人工核对', state: 'QUEUED', mode: 'LEAD', routeId: route.id, routeSnapshot: route,
    contract: { scope: ['src/**'], commands: ['node verify.mjs'], acceptance: ['只领取一次'] }, plan: [], evidence: [], budget: { maxCalls: 12, reserveCalls: 4, usedCalls: 3 }, createdAt: '2026-11-08T01:00:00Z', workspace: '' };
  const executions: any[] = Array.from({ length: 65 }, (_, index) => {
    const attempt = index + 1, state = attempt === 65 ? 'QUEUED' : attempt === 64 ? 'PAUSED' : attempt === 63 ? 'UNKNOWN' : 'SUCCEEDED';
    const execution: any = { id: `original-attempt-${attempt}-${'source-'.repeat(8)}`, sequence: attempt, taskId: task.id, dispatchActionId: `original-action-${attempt}`, attempt, trigger: attempt === 1 ? 'USER_APPROVAL' : 'USER_RESUME', state,
      createdAt: task.createdAt, contractVersion: 1, authorizationVersion: 3, budgetAtEnqueue: { usedCalls: 3 }, budgetAtStart: { usedCalls: 3 }, budgetAtEnd: { usedCalls: 6 } };
    execution.dispatchAction = { id: execution.dispatchActionId, taskId: task.id, taskExecutionId: execution.id, attempt, version: 1, state: state === 'QUEUED' ? 'PENDING' : state === 'PAUSED' ? 'WITHDRAWN' : state === 'UNKNOWN' ? 'UNKNOWN' : 'SETTLED', dispatchCount: ['QUEUED', 'PAUSED'].includes(state) ? 0 : 1,
      createdAt: task.createdAt, claimedAt: ['QUEUED', 'PAUSED'].includes(state) ? undefined : '2026-11-08T01:01:00Z', ownerCorePid: 12345, ownerCoreSessionId: `original-core-${'identity-'.repeat(12)}`, ownerCoreStartedAt: '2026-11-08T01:00:00.123Z' };
    if (state !== 'QUEUED') { execution.endedAt = '2026-11-08T01:05:00Z'; execution.taskStateAtEnd = state === 'SUCCEEDED' ? 'AWAITING_ACCEPTANCE' : state; }
    if (state === 'QUEUED') { execution.dispatchAction.waitReason = '旧进程清单需要核验，原排队编号保持不变'; delete execution.budgetAtStart; delete execution.budgetAtEnd; }
    if (state === 'UNKNOWN') { execution.reason = '原核心退出，已领取动作不会自动重放'; execution.reconciliation = { note: '原进程与外部效果已核对 <script>window.__dispatchFixtureInjected=true</script>', createdAt: '2026-11-08T01:10:00Z' }; }
    return execution;
  });
  const latest = executions.at(-1)!; task.taskExecutionId = latest.id; task.dispatchActionId = latest.dispatchActionId; task.taskExecution = latest; task.dispatch = latest.dispatchAction;
  const queries: string[] = [], posts: string[] = [], errors: string[] = []; let failNext = false;
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async intercepted => {
    const request = intercepted.request(), url = new URL(request.url()), path = url.pathname;
    if (path === '/api/state') return intercepted.fulfill({ json: { projects: [project], routes: [route], tasks: [task], profiles: [], memories: [], system: { version: 'dispatch-browser-fixture', storageHealthy: true } } });
    if (path === '/api/tasks/dispatch-task/task-executions') {
      queries.push(url.search); if (failNext) { failNext = false; return intercepted.fulfill({ status: 503, json: { message: '派发历史暂不能读取，请稍后重试' } }); }
      const before = Number(url.searchParams.get('before') || 0), state = url.searchParams.get('state'), limit = Number(url.searchParams.get('limit') || 30);
      const matching = executions.filter(execution => (!before || execution.sequence < before) && (!state || execution.state === state)).sort((a, b) => b.sequence - a.sequence), found = matching.slice(0, limit);
      return intercepted.fulfill({ json: { executions: found, cursor: found.at(-1)?.sequence || before, hasMore: matching.length > limit } });
    }
    if (path.endsWith('/events')) return intercepted.fulfill({ json: { events: [], cursor: 0 } });
    if (path.endsWith('/mcp-operations')) return intercepted.fulfill({ json: [] });
    if (path === '/api/tasks/dispatch-task/pause') { posts.push('pause'); task.state = 'PAUSED'; latest.state = 'PAUSED'; latest.dispatchAction.state = 'WITHDRAWN'; return intercepted.fulfill({ json: task }); }
    return intercepted.fulfill({ json: task });
  });
  return { task, executions, queries, posts, errors, fail: () => { failNext = true; } };
}

async function openHistory(page: Page) {
  await page.goto('/'); await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '保存原任务派发 持久队列项目 · dispatch', exact: true }).click();
  await page.getByRole('button', { name: '任务派发', exact: true }).click();
  return page.getByRole('region', { name: '任务派发历史' });
}

for (const width of [1440, 430]) test(`原排队编号、未知核对与窄屏来源 ${width}px`, async ({ page }) => {
  await page.setViewportSize({ width, height: 1000 }); const f = await fixture(page), history = await openHistory(page);
  await expect(history.getByRole('heading', { name: '第 65 次执行', exact: true })).toBeVisible(); await expect(history.getByText('尚未领取 · 领取 0 次', { exact: true })).toBeVisible();
  await expect(page.getByText('第 65 次执行已排队，尚未领取。旧进程清单需要核验，原排队编号保持不变', { exact: true })).toBeVisible();
  await history.getByLabel('执行尝试状态').selectOption('UNKNOWN'); await expect(history.getByRole('heading', { name: '第 63 次执行', exact: true })).toBeVisible();
  await expect(history.getByText('领取后结果待核对 · 领取 1 次', { exact: true })).toBeVisible(); await expect(history.getByText('已记录人工核对', { exact: true })).toBeVisible();
  await expect(history.getByText(f.executions[62].reconciliation.note, { exact: true })).toBeVisible(); expect(await page.evaluate(() => (window as any).__dispatchFixtureInjected)).toBeUndefined();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); expect(f.posts).toEqual([]); expect(f.errors).toEqual([]);
  await page.screenshot({ path: `qa/dispatch-history-${width}.png`, fullPage: true });
});

test('稳定游标读取更早尝试，成功执行仍展示当时待验收', async ({ page }) => {
  const f = await fixture(page), history = await openHistory(page); await history.getByRole('button', { name: '加载更早尝试' }).click();
  await expect(history.getByRole('heading', { name: '第 35 次执行', exact: true })).toBeVisible(); expect(f.queries.some(query => query.includes('before=36'))).toBe(true);
  await expect(history.getByText('待验收 · 等待人工验收', { exact: true }).first()).toBeVisible();
  await history.getByRole('button', { name: '加载更早尝试' }).click(); await expect(history.getByRole('heading', { name: '第 5 次执行', exact: true })).toBeVisible();
  await history.getByRole('button', { name: '返回最新尝试' }).click(); await expect(history.getByRole('heading', { name: '第 65 次执行', exact: true })).toBeVisible(); expect(f.errors).toEqual([]);
});

test('读取失败保留筛选，轮询恢复后不产生派发请求', async ({ page }) => {
  const f = await fixture(page), history = await openHistory(page); f.fail(); await history.getByLabel('执行尝试状态').selectOption('UNKNOWN');
  await expect(history.getByRole('alert')).toHaveText('派发历史暂不能读取，请稍后重试'); await expect(history.getByLabel('执行尝试状态')).toHaveValue('UNKNOWN');
  await expect(history.getByRole('heading', { name: '第 63 次执行', exact: true })).toBeVisible(); await expect(history.getByRole('alert')).toHaveCount(0); expect(f.posts).toEqual([]); expect(f.errors).toEqual([]);
});

test('历史任务没有根尝试时保留原活动记录提示', async ({ page }) => {
  const f = await fixture(page); f.executions.length = 0; delete f.task.dispatch; delete f.task.taskExecution; f.task.state = 'DRAFT';
  const history = await openHistory(page); await expect(history.getByText('尚无匹配的执行尝试。旧版本任务保留原活动记录。', { exact: true })).toBeVisible(); expect(f.posts).toEqual([]); expect(f.errors).toEqual([]);
});
