import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

test('运行时配置切换同源 API 前缀，凭据不进入公开配置', async ({ page }) => {
  const requests: { url: string; authorization?: string }[] = [];
  await page.addInitScript(() => sessionStorage.setItem('workbench-token', 'runtime-session-fixture'));
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '/service' } }));
  await page.route('**/api/state', route => {
    requests.push({ url: new URL(route.request().url()).pathname, authorization: route.request().headers().authorization });
    return route.fulfill({ json: { projects: [], tasks: [], routes: [], profiles: [], memories: [], system: {} } });
  });
  await page.goto('/');
  await expect(page.getByText('还没有研发任务')).toBeVisible();
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '核心连接', exact: true }).click();
  await expect(page.getByLabel('本机核心地址', { exact: true })).toHaveValue('/service');
  await page.getByLabel('访问令牌', { exact: true }).fill('runtime-session-fixture');
  await page.getByRole('button', { name: '连接并验证', exact: true }).click();
  await expect(page.getByRole('dialog')).toBeHidden();
  await expect.poll(() => requests.at(-1)).toEqual({ url: '/service/api/state', authorization: 'Bearer runtime-session-fixture' });
});

test('运行时配置含凭据或外部地址时停止连接', async ({ page }) => {
  let apiRequests = 0;
  await page.route('**/api/**', route => { apiRequests++; return route.abort(); });
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: '', token: 'must-not-be-public' } }));
  await page.goto('/');
  await expect(page.getByText('运行时配置仅允许 apiBaseUrl，不能包含令牌或凭据。')).toBeVisible();
  expect(apiRequests).toBe(0);
  await page.route('**/workbench-config.json', route => route.fulfill({ json: { apiBaseUrl: 'https://other.example' } }));
  await page.reload();
  await expect(page.getByText('核心地址必须是本机回环地址或同源代理路径。')).toBeVisible();
  expect(apiRequests).toBe(0);
});

const project = { id: 'project-fixture', name: '交互验证项目', path: 'D:\\fixtures\\trusted-project', branch: 'main', head: '1234567890abcdef', fileCount: 12, files: [], modules: ['src', 'tests'], indexedAt: '2026-11-05T14:00:00Z' };
const runner = { id: 'route-fixture', name: '测试路由', engine: 'API', model: 'test-model', provider: 'fixture', baseUrl: 'http://127.0.0.1:9999/v1', enabled: true };

async function fixture(page: Page, taskState = '') {
  const task = { id: 'task-fixture', projectId: project.id, title: '验证恢复流程', goal: '仅用于前端协议测试，不执行模型', mode: 'PIPELINE', state: taskState, contract: { scope: ['src'], acceptance: ['验证行为正确'], commands: ['npm test'] }, budget: { maxCalls: 24, reserveCalls: 4, usedCalls: 2 }, routeId: runner.id, plan: [{ id: 'implement', title: '实现', role: 'developer', state: 'COMPLETED' }], workspace: 'D:\\fixtures\\isolated-workspace' };
  const details = { ...task, evidence: [{ id: 'evidence-fixture', command: 'npm test', kind: 'VERIFICATION', exitCode: 0, status: 'PASSED', output: 'Fixture result: one check', version: 'code-version-fixture' }], context: [{ kind: 'RULE', content: '独立的固定上下文', source: 'fixture' }] };
  const actions: { action: string; body: any }[] = [];
  await page.route('**/api/**', async route => {
    const url = new URL(route.request().url());
    const path = url.pathname.replace('/api', '');
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: { runs: [], cursor: 0, hasMore: false } });
    if (path.endsWith('/model-requests')) return route.fulfill({ json: { requests: [], cursor: 0, hasMore: false } });
    if (path === '/state') return route.fulfill({ json: { projects: [project], tasks: taskState ? [task] : [], routes: [runner], memories: [], profiles: [], system: { modelCapacity: 2, activeModels: 0, version: 'ui-test', storageHealthy: true } } });
    if (path === '/mcp/task-tools') return route.fulfill({ json: { tools: [{ serverId: 'mcp-fixture', serverName: '测试服务', name: 'write_note', description: '写入笔记', configVersion: 3, definitionVersion: 'fixture-def', inputSchema: { type: 'object' }, available: true }] } });
    if (path.endsWith('/mcp-operations')) return route.fulfill({ json: [] });
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
  await page.getByRole('checkbox', { name: /测试服务 \/ write_note/ }).check();
  await page.getByLabel('固定 JSON 参数', { exact: true }).fill('{"query":"confirmed value"}');
  await page.getByRole('button', { name: '生成任务计划', exact: true }).click();
  await expect.poll(() => actions.length).toBe(1);
  expect(actions[0].action).toBe('/tasks');
  expect(actions[0].body.contract.acceptance).toEqual(['空参数返回明确提示', '已有测试通过']);
  expect(actions[0].body.contract.commands).toEqual(['npm test', 'npm run build']);
  expect(actions[0].body.locks.routeId).toBe('route-fixture');
  expect(actions[0].body.budget).toEqual({ maxCalls: 24, reserveCalls: 4 });
  expect(actions[0].body.mcpBindings).toEqual([{ serverId: 'mcp-fixture', name: 'write_note', configVersion: 3, definitionVersion: 'fixture-def', fixedArguments: { query: 'confirmed value' }, timeoutMs: 30000 }]);
  expect(actions[0].body.mcpBudget).toEqual({ maxCalls: 8, maxDurationMs: 240000 });
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
  const saved = page.waitForResponse(response => new URL(response.url()).pathname === '/api/tasks/task-fixture/reconcile' && response.request().method() === 'POST');
  await page.getByRole('button', { name: '记录人工核对', exact: true }).click();
  expect((await saved).ok()).toBe(true);
  expect(actions).toHaveLength(1);
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
for (const width of [1440, 430]) {
  test(`模型请求追踪保留未知用量、筛选和历史位置 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 });
    await fixture(page, 'AWAITING_ACCEPTANCE');
    const queries: string[] = [];
    const request = { id: 'request-current', agentRunId: 'run-current', taskId: 'task-fixture', stage: 'implement', sequence: 3, attempt: 1, state: 'SUCCEEDED', engine: 'API', model: 'fixture-model', unit: 'API_REQUEST', createdAt: '2026-11-07T09:00:00Z', elapsedMs: 1250, httpStatus: 200, providerRequestId: 'provider-abc', responseId: 'response-abc', finishReason: 'stop', inputPreview: '<script>请求输入 [REDACTED]</script>', inputTruncated: true, inputBytes: 3200, inputDigest: 'a'.repeat(64), messageCount: 5, toolCount: 3, usage: { inputTokens: 0, outputTokens: 'UNKNOWN', cachedInputTokens: 0 }, outputPreview: '<script>仅显示的输出 [REDACTED]</script>' };
    await page.route('**/api/tasks/task-fixture/model-requests*', route => {
      const url = new URL(route.request().url()); queries.push(url.search);
      const failed = url.searchParams.get('state') === 'FAILED'; const older = Number(url.searchParams.get('before')) > 0;
      return route.fulfill({ json: { requests: [{ ...request, id: older ? 'request-older' : request.id, sequence: older ? 1 : 3, ...(failed ? { state: 'FAILED', httpStatus: 429, errorType: 'HTTP_429', reason: '未自动重试' } : {}) }], cursor: older ? 1 : 30, hasMore: !older && !failed } });
    });
    await page.goto('/'); await page.getByRole('button', { name: /验证恢复流程/ }).click();
    await page.getByRole('button', { name: '模型请求', exact: true }).click();
    const panel = page.getByRole('region', { name: '模型请求追踪' });
    await expect(panel.getByText('implement · 第 3 次 API 请求')).toBeVisible();
    await expect(panel.locator('.badge')).toHaveText('已收到结果');
    await expect(panel.getByText(/输入 0 · 输出 未知 · 缓存读取 0 · 推理 未知/)).toBeVisible();
    await panel.getByRole('button', { name: '加载更早请求' }).click();
    await expect(panel.getByText('implement · 第 1 次 API 请求')).toBeVisible();
    await page.waitForTimeout(3200); expect(queries.at(-1)).toContain('before=30');
    await panel.getByRole('button', { name: '返回最新请求' }).click();
    await panel.getByLabel('请求状态').selectOption('FAILED');
    await expect(panel.getByText('HTTP_429', { exact: true })).toBeVisible();
    await expect(panel.locator('.badge')).toHaveText('请求失败');
    await panel.getByLabel('检索请求记录').fill('中文 HTTP_429'); await panel.getByRole('button', { name: '检索', exact: true }).click();
    await expect.poll(() => queries.at(-1)).toContain('q=%E4%B8%AD%E6%96%87%20HTTP_429');
    await panel.getByText('查看脱敏输入片段（已截断）', { exact: true }).click();
    await expect(panel.getByText('<script>请求输入 [REDACTED]</script>', { exact: true })).toBeVisible();
    await panel.getByText('查看脱敏输出片段', { exact: true }).click();
    await expect(panel.getByText('<script>仅显示的输出 [REDACTED]</script>', { exact: true })).toBeVisible();
    expect(await panel.locator('script').count()).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `qa/model-requests-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true });
  });

  test(`Agent 运行按状态筛选、稳定翻页并定位证据 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 });
    await fixture(page, 'AWAITING_ACCEPTANCE');
    const requests: string[] = [];
    const modelRequests: string[] = [];
    await page.route('**/api/tasks/task-fixture/model-requests*', route => {
      modelRequests.push(new URL(route.request().url()).search);
      return route.fulfill({ json: { requests: [], cursor: 0, hasMore: false } });
    });
    const current = { id: 'run-current', taskId: 'task-fixture', sessionId: 'session-fixture', stage: 'implement', kind: 'IMPLEMENT', title: '完成接口实现', role: 'developer', attempt: 2, state: 'SUCCEEDED', createdAt: '2026-11-07T09:00:00Z', endedAt: '2026-11-07T09:00:02Z', elapsedMs: 2000, routeName: '测试路由', engine: 'API', model: 'fixture', readOnly: false, calls: 3, callUnit: 'API_REQUEST', inputVersion: 'input-version', resultVersion: 'result-version', evidenceId: 'evidence-fixture', reason: '执行已返回；人工验收独立记录。' };
    await page.route('**/api/tasks/task-fixture/agent-runs*', route => {
      const url = new URL(route.request().url()); requests.push(url.search);
      const failed = url.searchParams.get('state') === 'FAILED'; const older = Number(url.searchParams.get('before')) > 0;
      return route.fulfill({ json: { runs: [failed ? { ...current, id: 'failed-run', state: 'FAILED', reason: '<script>不能执行的纯文本</script>' } : older ? { ...current, id: 'run-old', title: '较早执行尝试', attempt: 1 } : current], cursor: older ? 1 : 30, hasMore: !older && !failed } });
    });
    await page.goto('/');
    await page.getByRole('button', { name: /验证恢复流程/ }).click();
    await page.getByRole('button', { name: 'Agent 运行', exact: true }).click();
    const panel = page.getByRole('region', { name: 'Agent 运行记录' });
    await expect(panel.getByText('完成接口实现', { exact: true })).toBeVisible();
    await expect(panel.getByText(/3 次 API 请求/)).toBeVisible();
    await panel.getByRole('button', { name: '加载更早运行' }).click();
    await expect(panel.getByText('较早执行尝试')).toBeVisible();
    await page.waitForTimeout(3200);
    await expect(panel.getByText('较早执行尝试')).toBeVisible();
    expect(requests.at(-1)).toContain('before=30');
    await panel.getByRole('button', { name: '返回最新运行' }).click();
    await expect(panel.getByText('完成接口实现', { exact: true })).toBeVisible();
    await panel.getByLabel('运行状态').selectOption('FAILED');
    await expect(panel.getByText('<script>不能执行的纯文本</script>')).toBeVisible();
    expect(await panel.locator('script').count()).toBe(0);
    await panel.getByLabel('运行状态').selectOption('');
    await expect(panel.getByText('完成接口实现', { exact: true })).toBeVisible();
    await panel.getByRole('button', { name: '查看模型请求', exact: true }).click();
    await expect(panel.getByRole('region', { name: '模型请求追踪' })).toBeVisible();
    await expect.poll(() => modelRequests.at(-1)).toContain('runId=run-current');
    await panel.getByRole('button', { name: '收起模型请求', exact: true }).click();
    await expect(panel.getByRole('region', { name: '模型请求追踪' })).toHaveCount(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true);
    await page.screenshot({ path: `qa/agent-runs-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true });
    await panel.getByRole('button', { name: '查看关联证据' }).click();
    await expect(page.getByText('Fixture result: one check')).toBeVisible();
  });
}
