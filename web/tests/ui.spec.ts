import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const project = { id: 'project-fixture', name: '交互验证项目', path: 'D:\\fixtures\\trusted-project', branch: 'main', head: '1234567890abcdef', fileCount: 12, files: [], modules: ['src', 'tests'], indexedAt: '2026-11-05T14:00:00Z' };
const runner = { id: 'route-fixture', name: '测试路由', engine: 'API', model: 'test-model', provider: 'fixture', baseUrl: 'http://127.0.0.1:9999/v1', enabled: true };

async function fixture(page: Page, taskState = '') {
  const task = { id: 'task-fixture', projectId: project.id, title: '验证恢复流程', goal: '仅用于前端协议测试，不执行模型', mode: 'PIPELINE', state: taskState, contract: { scope: ['src'], acceptance: ['验证行为正确'], commands: ['npm test'] }, budget: { maxCalls: 24, reserveCalls: 4, usedCalls: 2 }, routeId: runner.id, plan: [{ id: 'implement', title: '实现', role: 'developer', state: 'COMPLETED' }], workspace: 'D:\\fixtures\\isolated-workspace' };
  const details = { ...task, evidence: [{ id: 'evidence-fixture', command: 'npm test', kind: 'VERIFICATION', exitCode: 0, status: 'PASSED', output: 'Fixture result: one check', version: 'code-version-fixture' }], context: [{ kind: 'RULE', content: '独立的固定上下文', source: 'fixture' }] };
  const actions: { action: string; body: any }[] = [];
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api', '');
    if (path === '/state') return route.fulfill({ json: { projects: [project], tasks: taskState ? [task] : [], routes: [runner], memories: [], profiles: [], system: { modelCapacity: 2, activeModels: 0, version: 'ui-test', storageHealthy: true } } });
    if (path === '/tasks/task-fixture' && route.request().method() === 'GET') return route.fulfill({ json: details });
    if (path.endsWith('/events')) return route.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (route.request().method() === 'POST') {
      actions.push({ action: path, body: route.request().postDataJSON() });
      if (path.endsWith('/reconcile')) { task.state = 'PAUSED'; details.state = 'PAUSED'; }
      return route.fulfill({ json: path === '/tasks' ? { ...task, ...route.request().postDataJSON(), state: 'DRAFT' } : details });
    }
    return route.fulfill({ json: {} });
  });
  return { actions };
}

test('完成约定、锁定和预留随任务提交，创建不会自动批准', async ({ page }) => {
  const { actions } = await fixture(page);
  await page.goto('/');
  await page.getByRole('button', { name: '新建任务', exact: true }).click();
  await page.getByLabel('任务名称', { exact: true }).fill('接口边界修复');
  await page.getByLabel('你希望完成什么？').fill('处理空参数并保留现有行为');
  await page.getByLabel('验收条件', { exact: true }).fill('空参数返回明确提示\n已有测试通过');
  await page.getByLabel('必要验证命令', { exact: true }).fill('npm test\nnpm run build');
  await page.getByLabel('锁定此路由', { exact: true }).check();
  await page.getByRole('button', { name: '生成任务计划', exact: true }).click();
  await expect.poll(() => actions.length).toBe(1);
  expect(actions[0].action).toBe('/tasks');
  expect(actions[0].body.contract.acceptance).toEqual(['空参数返回明确提示', '已有测试通过']);
  expect(actions[0].body.contract.commands).toEqual(['npm test', 'npm run build']);
  expect(actions[0].body.locks.routeId).toBe('route-fixture');
  expect(actions[0].body.budget).toEqual({ maxCalls: 24, reserveCalls: 4 });
});

test('摘要刷新后仍显示详情证据，页面没有运行时异常', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await fixture(page, 'AWAITING_ACCEPTANCE');
  await page.goto('/');
  await page.getByRole('button', { name: /验证恢复流程/ }).click();
  await page.getByRole('button', { name: /验证与审查/ }).click();
  await expect(page.getByRole('heading', { name: 'npm test', exact: true })).toBeVisible();
  await page.waitForTimeout(3500);
  await expect(page.getByRole('heading', { name: 'npm test', exact: true })).toBeVisible();
  await page.getByRole('button', { name: /npm test/ }).click();
  await expect(page.getByText('Fixture result: one check')).toBeVisible();
  expect(errors).toEqual([]);
});

test('结果未知只能经明确现场核对恢复，不能自动重放', async ({ page }) => {
  const { actions } = await fixture(page, 'UNKNOWN');
  await page.goto('/');
  await page.getByRole('button', { name: /验证恢复流程/ }).click();
  await page.getByRole('button', { name: '核对未知操作', exact: true }).click();
  expect(actions).toHaveLength(0);
  await page.getByLabel('我已确认相关执行与子进程停止').check();
  await page.getByLabel('我已核对未确认操作的实际副作用').check();
  await page.getByLabel('核对依据', { exact: true }).fill('检查了本机进程树与工作目录，已确认没有残留写入。');
  await page.getByRole('button', { name: '记录人工核对', exact: true }).click();
  await expect.poll(() => actions.length).toBe(1);
  expect(actions[0]).toEqual({ action: '/tasks/task-fixture/reconcile', body: { processesStopped: true, sideEffectsChecked: true, note: '检查了本机进程树与工作目录，已确认没有残留写入。' } });
  await expect(page.getByRole('button', { name: '核验并继续', exact: true })).toBeVisible();
});

test('桌面和窄窗口均可浏览，空状态不显示虚假成果', async ({ page }) => {
  await fixture(page);
  await page.goto('/');
  await expect(page.getByText('还没有研发任务')).toBeVisible();
  await page.screenshot({ path: 'qa/dashboard-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 430, height: 932 });
  await expect(page.getByText('还没有研发任务')).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
  await page.screenshot({ path: 'qa/dashboard-narrow.png', fullPage: true });
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await expect(page.getByRole('heading', { name: '让合适的 Agent，做合适的事' })).toBeVisible();
});

test('MCP 探测只发现工具，保存空清单不会扩大工具授权', async ({ page }) => {
  await fixture(page);
  const requests: { path: string; body: any }[] = [];
  let servers: any[] = [];
  await page.route('**/api/mcp/**', async route => {
    const path = new URL(route.request().url()).pathname;
    if (route.request().method() === 'GET') return route.fulfill({ json: servers });
    const body = route.request().postDataJSON(); requests.push({ path, body });
    if (path.endsWith('/probe')) {
      servers[0].discovery = { available: true, definitionVersion: 'fixture-def', tools: [{ name: 'read_notes', authorized: false }] };
      return route.fulfill({ json: servers[0].discovery });
    }
    servers = [{ ...body, id: 'mcp-fixture' }];
    return route.fulfill({ json: servers[0] });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: 'MCP 工具', exact: true }).click();
  await page.getByRole('button', { name: '添加服务', exact: true }).click();
  await page.getByLabel('服务名称', { exact: true }).fill('资料检索服务');
  await page.getByLabel('MCP 服务地址', { exact: true }).fill('http://127.0.0.1:9876/mcp');
  await page.getByRole('button', { name: '保存服务配置', exact: true }).click();
  await expect.poll(() => requests.length).toBe(1);
  expect(requests[0].body.allowedTools).toEqual([]);
  expect(requests[0].body.projectIds).toEqual([]);
  await page.getByRole('button', { name: '发现工具', exact: true }).click();
  await expect(page.getByText('read_notes', { exact: true })).toBeVisible();
  expect(requests.map(request => request.path)).toEqual(['/api/mcp/servers', '/api/mcp/servers/mcp-fixture/probe']);
});

test('职责模板包导入先预览，剥离原 ID 后逐项创建', async ({ page }) => {
  const { actions } = await fixture(page);
  await page.goto('/');
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '职责模板', exact: true }).click();
  await page.locator('input[type=file]').setInputFiles({ name: 'profiles.json', mimeType: 'application/json', buffer: Buffer.from(JSON.stringify({ version: 1, profiles: [{ id: 'old-one', name: '接口审查者', role: 'reviewer', instructions: '核验接口约定', capabilities: ['read'] }, { id: 'old-two', name: '测试工程师', role: 'tester', instructions: '核验必要检查', capabilities: ['read', 'verify'] }] })) });
  await expect(page.getByRole('dialog', { name: '检查并导入职责模板' })).toBeVisible();
  expect(actions).toHaveLength(0);
  await page.getByRole('button', { name: '导入选中的 2 个模板', exact: true }).click();
  await expect.poll(() => actions.length).toBe(2);
  expect(actions.map(action => action.action)).toEqual(['/profiles', '/profiles']);
  expect(actions.every(action => action.body.id === undefined)).toBe(true);
  expect(actions.map(action => action.body.name)).toEqual(['接口审查者', '测试工程师']);
});

test('项目 MCP 令牌独立创建复制撤销，离开页面后不保留明文', async ({ page }) => {
  await fixture(page);
  await page.addInitScript(() => {
    sessionStorage.setItem('workbench-token', 'desktop-admin-fixture');
    Object.defineProperty(navigator, 'clipboard', { configurable: true, value: { writeText: async (text: string) => { (window as any).__copiedText = text; } } });
  });
  const requests: { method: string; body?: any; authorization?: string }[] = [];
  const secret = 'project-read-fixture-secret';
  const metadata = { id: 'access-fixture', projectId: project.id, endpoint: `/api/mcp/rpc/${project.id}`, createdAt: '2026-11-05T14:00:00Z', scope: 'PROJECT_READ_ONLY', credentialStorage: 'SESSION_ONLY' };
  let active = false;
  await page.route('**/api/mcp/access**', async route => {
    const request = route.request();
    if (request.method() === 'GET') return route.fulfill({ json: active ? [metadata] : [] });
    requests.push({ method: request.method(), body: request.method() === 'POST' ? request.postDataJSON() : undefined, authorization: request.headers().authorization });
    if (request.method() === 'POST') { active = true; return route.fulfill({ json: { ...metadata, token: secret } }); }
    active = false; return route.fulfill({ json: { revoked: true } });
  });
  await page.goto('/');
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: 'MCP 工具', exact: true }).click();
  await page.getByRole('button', { name: '创建项目只读令牌', exact: true }).click();
  await expect(page.getByLabel('项目只读令牌 access-fix', { exact: true })).toHaveValue(secret);
  expect(requests[0]).toEqual({ method: 'POST', body: { projectId: project.id }, authorization: 'Bearer desktop-admin-fixture' });
  await page.getByRole('button', { name: '复制只读令牌', exact: true }).click();
  expect(await page.evaluate(() => (window as any).__copiedText)).toBe(secret);
  expect(await page.evaluate(() => JSON.stringify([Object.entries(localStorage), Object.entries(sessionStorage)]))).not.toContain(secret);
  await page.getByRole('button', { name: '运行器与模型', exact: true }).click();
  await page.getByRole('button', { name: 'MCP 工具', exact: true }).click();
  await expect(page.getByText('令牌仍有效，值只在创建时返回。需要再次取得令牌时，可撤销此项并重新创建。')).toBeVisible();
  await expect(page.getByRole('button', { name: '复制只读令牌', exact: true })).toHaveCount(0);
  await expect(page.getByText('desktop-admin-fixture', { exact: true })).toHaveCount(0);
  await page.getByRole('button', { name: '撤销令牌 access-fix', exact: true }).click();
  await expect(page.getByText('尚未创建项目只读令牌。')).toBeVisible();
  expect(requests.map(request => request.method)).toEqual(['POST', 'DELETE']);
});
