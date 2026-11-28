import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const project = { id: 'schedule-project', name: '计划测试项目', path: 'fixture', branch: 'main', fileCount: 0 };
const route = { id: 'schedule-route', name: '本地测试路由', engine: 'API', model: 'fixture', enabled: true, hasKey: true };
const task = (overrides: Record<string, unknown> = {}) => ({
  projectId: project.id, routeId: route.id, title: '每日代码巡检', goal: '检查项目状态并报告验证结果', mode: 'LEAD',
  contract: { acceptance: ['状态已检查'], commands: ['npm test'] }, budget: { maxCalls: 24, reserveCalls: 6 }, locks: {}, ...overrides,
});
const schedule = (overrides: Record<string, unknown> = {}) => ({ id: 'schedule-1', version: 2, name: '每日代码巡检', timezone: 'Asia/Shanghai', localTime: '09:00', enabled: true, task: task(), ...overrides });
const attention = (overrides: Record<string, unknown> = {}) => ({
  id: 'occurrence-1', version: 2, scheduleId: 'schedule-1', scheduleVersion: 1, localDate: '2026-11-27', timezone: 'Asia/Shanghai', localTime: '09:00',
  scheduledAt: '2026-11-27T01:00:00Z', state: 'NEEDS_ATTENTION', message: '工作区引用需要重新核对', recoveries: [], ...overrides,
});

async function fixture(page: Page, initialSchedules = [schedule()], initialOccurrences = [attention()], options: { failFirstRecovery?: boolean; delayedScheduleId?: string } = {}) {
  const requests: { method: string; path: string; body?: Record<string, any> }[] = [];
  let schedules = [...initialSchedules] as ReturnType<typeof schedule>[];
  let occurrences = [...initialOccurrences] as ReturnType<typeof attention>[];
  let failRecovery = options.failFirstRecovery || false;
  await page.route('**/workbench-config.json', handler => handler.fulfill({ json: { apiBaseUrl: '' } }));
  await page.route('**/api/**', async handler => {
    const request = handler.request(), url = new URL(request.url());
    const body = request.postDataJSON?.() as Record<string, any> | undefined;
    requests.push({ method: request.method(), path: url.pathname, body });
    if (url.pathname === '/api/state') return handler.fulfill({ json: { projects: [project], tasks: [], routes: [route], profiles: [], memories: [], system: { storageHealthy: true } } });
    if (url.pathname === '/api/schedules' && request.method() === 'GET') return handler.fulfill({ json: schedules });
    if (url.pathname === '/api/schedules' && request.method() === 'POST') {
      const created = { id: 'schedule-new', version: 1, ...body };
      schedules = [created as ReturnType<typeof schedule>, ...schedules];
      return handler.fulfill({ json: created });
    }
    if (url.pathname === '/api/schedules/schedule-1' && request.method() === 'PATCH') {
      schedules = schedules.map(item => item.id === 'schedule-1' ? { ...item, ...body, version: item.version + 1 } : item);
      return handler.fulfill({ json: schedules.find(item => item.id === 'schedule-1') });
    }
    if (url.pathname.endsWith('/occurrences') && request.method() === 'GET') {
      if (options.delayedScheduleId && url.pathname.includes(`/schedules/${options.delayedScheduleId}/`)) await new Promise(resolve => setTimeout(resolve, 800));
      const selectedOccurrences = url.pathname.includes('/schedule-2/') ? [{ ...attention({ id: 'occurrence-2', scheduleId: 'schedule-2', state: 'TRIGGERED', message: '第二计划历史' }), recoveries: [] }] : occurrences;
      return handler.fulfill({ json: selectedOccurrences });
    }
    if (url.pathname.endsWith('/recover') && request.method() === 'POST') {
      if (failRecovery) { failRecovery = false; return handler.fulfill({ status: 503, json: { message: '恢复响应暂时不可用' } }); }
      occurrences = occurrences.map(item => ({ ...item, state: body?.action === 'RECONCILE_EXISTING_TASK' || body?.action?.startsWith('RETRY_') ? 'TRIGGERED' : item.state, taskId: 'task-created', taskState: 'QUEUED', message: '已处理' }));
      return handler.fulfill({ json: occurrences[0] });
    }
    return handler.fulfill({ json: {} });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '每日计划', exact: true }).click();
  return { requests, page };
}

test('计划页读取并展示触发快照、错误和人工恢复历史', async ({ page }) => {
  await fixture(page, [schedule({ version: 3 })], [attention({ version: 2, scheduleVersion: 1, recoveries: [{ action: 'RETRY_SNAPSHOT', actor: 'LOCAL_USER', reason: '依赖已恢复', createdAt: '2026-11-27T02:00:00Z' }] })]);
  await expect(page.getByRole('heading', { name: '每日代码巡检', exact: true })).toBeVisible();
  await expect(page.getByText('工作区引用需要重新核对')).toBeVisible();
  await expect(page.getByRole('button', { name: '按原模板重试' })).toBeVisible();
  await expect(page.getByRole('button', { name: '使用当前计划版本重试' })).toBeVisible();
  await page.getByText('人工处理记录（1）').click();
  await expect(page.getByText('依赖已恢复')).toBeVisible();
  await page.screenshot({ path: 'qa/schedule-operations-desktop.png', fullPage: true });
});

test('计划页窄屏布局不产生横向溢出', async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 900 });
  await fixture(page);
  await expect(page.getByRole('heading', { name: '每日代码巡检', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'qa/schedule-operations-narrow.png', fullPage: true });
});

test('创建和编辑计划保留完整任务模板并以版本号保护更新', async ({ page }) => {
  const advanced = task({ workflow: { definitionId: 'workflow-1', version: 4, digest: 'saved-digest' }, mcpBindings: [{ serverId: 'mcp-1', name: 'read_issue' }] });
  const data = await fixture(page, [schedule({ version: 4, enabled: false, task: advanced })], []);
  await page.getByRole('button', { name: '编辑计划' }).click();
  await page.getByLabel('本地触发时间').fill('10:35');
  await page.getByLabel('保存后启用计划').uncheck();
  await page.getByRole('button', { name: '保存计划版本' }).click();
  await expect.poll(() => data.requests.find(item => item.path === '/api/schedules/schedule-1' && item.method === 'PATCH')?.body).toMatchObject({
    expectedVersion: 4, localTime: '10:35', enabled: false,
    task: { workflow: { definitionId: 'workflow-1', version: 4, digest: 'saved-digest' }, mcpBindings: [{ serverId: 'mcp-1', name: 'read_issue' }] },
  });

  await page.getByRole('button', { name: '新建计划' }).click();
  await page.getByLabel('计划名称').fill('每周项目回顾');
  await page.getByLabel('本地触发时间').fill('17:45');
  await page.getByRole('button', { name: '创建每日计划' }).click();
  await expect.poll(() => data.requests.find(item => item.path === '/api/schedules' && item.method === 'POST')?.body).toMatchObject({
    name: '每周项目回顾', localTime: '17:45', enabled: true,
    task: { projectId: project.id, routeId: route.id, contract: { acceptance: ['关键变化已检查并记录', '所有指定验证均通过'], commands: ['npm test'] } },
  });
  await expect(page.getByRole('button', { name: '每周项目回顾' })).toBeVisible();
});

test('启停使用乐观版本并保留已记录触发', async ({ page }) => {
  const data = await fixture(page, [schedule({ version: 8 })], []);
  await page.getByRole('button', { name: '暂停计划' }).click();
  await expect.poll(() => data.requests.find(item => item.path === '/api/schedules/schedule-1' && item.method === 'PATCH')?.body).toMatchObject({ expectedVersion: 8, enabled: false });
  await expect(page.getByText(/暂停只停止未来触发/)).toBeVisible();
});

test('切换计划先清除上一计划历史并忽略迟到触发响应', async ({ page }) => {
  const second = schedule({ id: 'schedule-2', name: '另一个每日计划', task: task({ title: '另一项任务' }) });
  await fixture(page, [schedule(), second], [attention()], { delayedScheduleId: 'schedule-2' });
  await expect(page.getByText('工作区引用需要重新核对')).toBeVisible();
  await page.getByRole('button', { name: /另一个每日计划/ }).click();
  await expect(page.getByRole('button', { name: '按原模板重试' })).toHaveCount(0);
  await expect(page.getByText('第二计划历史')).toBeVisible();
  await expect(page.getByText('工作区引用需要重新核对')).toHaveCount(0);
});

test('单次重试必须显式确认，重复请求在本地使用同一幂等编号', async ({ page }) => {
  const data = await fixture(page, [schedule({ version: 3 })], [attention({ version: 2, scheduleVersion: 1 })], { failFirstRecovery: true });
  await page.getByRole('button', { name: '使用当前计划版本重试' }).click();
  await expect(page.getByText('触发快照将更新为计划 v3；旧模板会保存在本次审计记录。')).toBeVisible();
  await expect(page.getByRole('checkbox', { name: '我确认只重试这一次；任务会立即排入执行' })).toBeVisible();
  await page.getByRole('checkbox', { name: '我确认只重试这一次；任务会立即排入执行' }).check();
  await page.getByRole('button', { name: '确认单次重试' }).click();
  await expect(page.getByRole('alert')).toContainText('恢复响应暂时不可用');
  await page.getByRole('button', { name: '确认单次重试' }).click();
  await expect.poll(() => data.requests.filter(item => item.path.endsWith('/recover')).length).toBe(2);
  const payloads = data.requests.filter(item => item.path.endsWith('/recover')).map(item => item.body);
  const payload = payloads[0];
  expect(payload).toMatchObject({ expectedVersion: 2, action: 'RETRY_CURRENT_VERSION' });
  expect(payload?.requestId).toMatch(/^[0-9a-f-]{36}$/i);
  expect(payloads[1]?.requestId).toBe(payload?.requestId);
  await expect(page.getByRole('status')).toContainText('已恢复并将本次任务排入执行');
});

test('已有草稿任务只允许打开任务，非草稿才可无派发核对关联', async ({ page }) => {
  await fixture(page, [schedule()], [attention({ taskId: 'task-existing', taskState: 'DRAFT' })]);
  await expect(page.getByRole('button', { name: '打开待确认任务' })).toBeVisible();
  await expect(page.getByRole('button', { name: '按原模板重试' })).toHaveCount(0);
  await expect(page.getByRole('button', { name: '核对并关联已有任务' })).toHaveCount(0);
});

test('现有任务关联提示核对且不会再次派发', async ({ page }) => {
  const data = await fixture(page, [schedule()], [attention({ taskId: 'task-existing', taskState: 'UNKNOWN' })]);
  await page.getByRole('button', { name: '核对并关联已有任务' }).click();
  await expect(page.getByText('将核对已有任务并把触发记录标为已关联，不触碰任务状态或执行队列。')).toBeVisible();
  await page.getByRole('checkbox', { name: '我已核对该任务状态' }).check();
  await page.getByRole('button', { name: '核对并关联', exact: true }).click();
  const recovery = data.requests.find(item => item.path.endsWith('/recover'))?.body;
  expect(recovery).toMatchObject({ expectedVersion: 2, action: 'RECONCILE_EXISTING_TASK' });
  await expect(page.getByText('现有任务已关联；没有重新派发。')).toBeVisible();
});
