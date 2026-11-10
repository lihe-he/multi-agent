import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

// These are browser protocol fixtures; real worker ownership is checked with Java and the packaged Jar.
async function workerFixture(page: Page, independent = false) {
  const node: any = { id: 'node-local-id', name: '已配对本机', version: 2, protocolVersion: 1, type: 'LOCAL', deployment: 'CORE_PROCESS', platform: 'Windows', architecture: 'amd64', enabled: true, epoch: 7, state: 'ONLINE', workerId: 'actual-worker-source', lastSeenAt: '2026-11-07T13:00:00Z' };
  const project = { id: 'worker-project', name: '节点归属项目', path: 'C:\\Projects\\项目 repo', repositoryBindingId: 'binding-original', executionNodeId: node.id };
  const bindings: any[] = [{ id: 'binding-original', name: '原仓库目录', projectId: project.id, executionNodeId: node.id, path: project.path, enabled: true, version: 3 }, { id: 'binding-another', name: '另一个本机副本', projectId: project.id, executionNodeId: node.id, path: 'D:\\Projects\\项目 repo', enabled: true, version: 1 }];
  const route = { id: 'route', version: 1, name: '本机协议模型', engine: 'API', model: 'fixture', baseUrl: 'http://127.0.0.1:9999/v1', enabled: true };
  const task: any = { id: 'worker-task', title: '核对实际操作与租约', projectId: project.id, state: 'DRAFT', goal: '浏览器协议验收', mode: 'LEAD', routeId: route.id, routeSnapshot: route, plan: [], contract: { scope: ['src/'], commands: ['node verify.mjs'], acceptance: ['原执行来源可核对'] }, budget: { maxCalls: 24, usedCalls: 0, reserveCalls: 4 }, evidence: [{ id: 'lease-evidence', kind: 'VERIFICATION', stage: 'verify', status: 'PASSED', output: '原始执行回执 <script>literal data</script>', version: 'v1' }], execution: { version: 1, protocolVersion: 1, executionNodeId: node.id, nodeVersion: 1, nodeName: '原本机名称', repositoryBindingId: bindings[0].id, bindingVersion: 1, bindingName: '原仓库目录', sourcePath: project.path, deployment: 'CORE_PROCESS' }, createdAt: '2026-11-07T12:00:00Z', workspace: 'C:\\data\\workspaces\\fixed-source\\work' };
  const tasks: any[] = [task];
  const worker = { id: node.workerId, executionNodeId: node.id, epoch: 7, state: 'RUNNING', pid: 4000, processStartedAt: task.createdAt, startedAt: task.createdAt, heartbeatAt: node.lastSeenAt, expiresAt: '2026-11-07T13:00:30Z', deployment: 'CORE_PROCESS', platform: 'Windows' };
  const leases: any[] = Array.from({ length: 65 }, (_, index) => ({ id: `lease-${index + 1}`, sequence: index + 1, operationId: `operation-${index + 1}`, operationKind: 'operation', kind: index === 64 ? 'SUBAGENT_MODEL' : 'VERIFICATION', taskId: task.id, agentRunId: 'child-real-run-source', workerId: worker.id, executionNodeId: node.id, epoch: 7, repositoryBindingId: bindings[0].id, bindingVersion: 1, authorizationVersion: 1, workingDirectory: `${task.workspace}\\${'long-name-'.repeat(10)}`, state: index === 64 ? 'UNKNOWN' : 'RELEASED', operationState: index === 64 ? 'UNKNOWN' : 'ACKNOWLEDGED', stopState: index === 64 ? 'UNKNOWN' : 'CONFIRMED', createdAt: task.createdAt, heartbeatAt: node.lastSeenAt, expiresAt: worker.expiresAt, reason: index === 64 ? '失去归属，旧远端效果尚未确认' : '', evidenceId: index === 64 ? 'lease-evidence' : '' }));
  if (independent) {
    node.deployment = 'LOCAL_PROCESS'; task.execution.deployment = 'LOCAL_PROCESS'; Object.assign(worker, { deployment: 'LOCAL_PROCESS', pid: 5200, corePid: 4000, parentPid: 4000 });
    for (const lease of leases) Object.assign(lease, { deployment: 'LOCAL_PROCESS', executorPid: worker.pid, corePid: 4000, processStartedAt: worker.processStartedAt });
  }
  const queries: string[] = [], bindingPosts: any[] = [], nodePosts: any[] = [], taskPosts: any[] = [], authorizations: any[] = [], errors: string[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async intercepted => {
    const request = intercepted.request(), url = new URL(request.url()), path = url.pathname;
    if (path === '/api/state') return intercepted.fulfill({ json: { projects: [project], routes: [route], tasks, profiles: [], memories: [], agentDefinitions: [], executionNodes: [node], repositoryBindings: bindings, workers: [worker], system: { version: 'worker-ui-fixture', storageHealthy: true } } });
    if (path === '/api/execution-nodes') {
      if (request.method() === 'GET') return intercepted.fulfill({ json: [node] });
      const body = request.postDataJSON(); nodePosts.push(body); if (body.expectedVersion !== node.version) return intercepted.fulfill({ status: 409, json: { message: '执行节点版本已变化' } });
      Object.assign(node, body, { version: node.version + 1 }); return intercepted.fulfill({ json: node });
    }
    if (path === '/api/repository-bindings') {
      if (request.method() === 'GET') return intercepted.fulfill({ json: bindings });
      const body = request.postDataJSON(); bindingPosts.push(body); const prior = bindings.find(binding => binding.id === body.id);
      if (prior && body.expectedVersion !== prior.version) return intercepted.fulfill({ status: 409, json: { message: '仓库绑定版本已变化，请刷新后编辑' } });
      const saved = { ...body, id: prior?.id || `binding-${bindings.length}`, version: (prior?.version || 0) + 1 }; if (prior) Object.assign(prior, saved); else bindings.push(saved); return intercepted.fulfill({ json: saved });
    }
    if (/^\/api\/repository-bindings\/[^/]+\/workspace-options$/.test(path)) {
      const binding = bindings.find(binding => binding.id === decodeURIComponent(path.split('/')[3]));
      const head = (binding.id === bindings[0].id ? '1' : '2').repeat(40);
      return intercepted.fulfill({ json: { schemaVersion: 1, repositoryBindingId: binding.id, bindingVersion: binding.version, projectId: project.id, executionNodeId: node.id, nodeVersion: node.version,
        sourcePath: binding.path, source: { head, branch: 'main', dirty: false, version: 'a'.repeat(64), index: 'b'.repeat(64), statusDigest: 'c'.repeat(64) },
        refs: [{ ref: 'refs/heads/main', head, name: 'main', kind: 'LOCAL' }], strategies: ['CLONE', 'WORKTREE'], worktrees: [] } });
    }
    if (path === '/api/tasks/worker-task/worker-leases') {
      queries.push(url.search); const before = Number(url.searchParams.get('before') || 0), state = url.searchParams.get('state'), limit = Number(url.searchParams.get('limit') || 30);
      const matching = leases.filter(lease => (!before || lease.sequence < before) && (!state || lease.state === state)).sort((a, b) => b.sequence - a.sequence), found = matching.slice(0, limit);
      return intercepted.fulfill({ json: { leases: found, cursor: found.at(-1)?.sequence || before, hasMore: matching.length > limit } });
    }
    if (path === '/api/tasks/worker-task/execution-authorization') {
      const body = request.postDataJSON(); authorizations.push(body); const selected = bindings.find(binding => binding.id === body.repositoryBindingId);
      if (body.nodeVersion !== node.version || body.bindingVersion !== selected?.version) return intercepted.fulfill({ status: 409, json: { message: '执行配置版本已变化，请刷新后确认' } });
      task.execution = { ...task.execution, version: task.execution.version + 1, nodeName: node.name, nodeVersion: node.version, bindingVersion: selected.version, bindingName: selected.name }; task.state = 'PAUSED'; task.review = { status: 'NOT_RUN' }; return intercepted.fulfill({ json: task });
    }
    if (path === '/api/tasks' && request.method() === 'POST') {
      const body = request.postDataJSON(); taskPosts.push(body); const binding = bindings.find(binding => binding.id === body.repositoryBindingId);
      const created = { ...task, ...body, id: 'created-worker-task', workspace: '', state: 'DRAFT', workspaceSelection: body.workspaceSelection ? { ...body.workspaceSelection, schemaVersion: 1, version: 1, baseline: body.workspaceSelection.expectedHead, sourcePath: binding.path, includeChanges: body.includeChanges } : undefined,
        execution: { ...task.execution, nodeName: node.name, nodeVersion: node.version, repositoryBindingId: binding.id, bindingVersion: binding.version, bindingName: binding.name, sourcePath: binding.path } }; tasks.push(created); return intercepted.fulfill({ json: created });
    }
    if (path.endsWith('/mcp-operations')) return intercepted.fulfill({ json: [] });
    if (path.endsWith('/events')) return intercepted.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (path === '/api/agent-definitions') return intercepted.fulfill({ json: [] });
    if (path === '/api/mcp/task-tools') return intercepted.fulfill({ json: { tools: [] } });
    if (path === '/api/tasks/worker-task') return intercepted.fulfill({ json: task });
    if (path === '/api/tasks/created-worker-task') return intercepted.fulfill({ json: tasks.at(-1) });
    return intercepted.fulfill({ json: {} });
  });
  return { task, project, node, bindings, leases, queries, nodePosts, bindingPosts, taskPosts, authorizations, errors };
}

async function openWorker(page: Page) { await page.goto('/'); await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '核对实际操作与租约' }).click(); await page.getByRole('button', { name: 'Worker 执行', exact: true }).click(); }

for (const width of [1440, 430]) {
  test(`独立 Worker 与核心进程使用各自的原 PID ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workerFixture(page, true); await openWorker(page);
    const first = page.getByRole('region', { name: 'Worker 实际执行记录' }).locator('.worker-lease').first();
    await expect(first.getByText('本机独立 Worker · PID 5200', { exact: true })).toBeVisible(); await expect(first.getByText('PID 4000', { exact: true })).toBeVisible();
    await expect(first.getByText('旧进程或外部效果未确认', { exact: true })).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `qa/worker-independent-${width === 430 ? 'narrow' : 'desktop'}.png` });
    await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '执行节点与仓库', exact: true }).click();
    await expect(page.getByRole('heading', { name: '进程 5200 · 代次 7' })).toBeVisible(); await expect(page.getByText(/本机独立 Worker · 核心 PID 4000/)).toBeVisible(); expect(fixture.errors).toEqual([]);
  });
  test(`Worker 来源、未知事实和历史游标 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workerFixture(page); await openWorker(page); const panel = page.getByRole('region', { name: 'Worker 实际执行记录' });
    await expect(panel.getByRole('heading', { name: 'SUBAGENT_MODEL', exact: true })).toBeVisible(); const first = panel.locator('.worker-lease').first();
    await expect(first.getByText('actual-worker-source', { exact: true })).toBeVisible(); await expect(first.getByText('旧进程或外部效果未确认', { exact: true })).toBeVisible();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await panel.getByRole('heading', { name: 'Worker 操作与租约' }).scrollIntoViewIfNeeded(); await page.screenshot({ path: `qa/worker-${width === 430 ? 'narrow' : 'desktop'}.png` });
    await panel.getByRole('button', { name: '加载更早租约' }).click(); await expect(panel.getByText('operation-35', { exact: false })).toBeVisible();
    fixture.leases.push({ ...fixture.leases[0], id: 'new-lease', sequence: 66, operationId: 'new-operation', kind: 'NEW_OPERATION' }); await page.waitForTimeout(3300); expect(fixture.queries.at(-1)).toContain('before=36'); await expect(panel.getByRole('heading', { name: 'NEW_OPERATION' })).toHaveCount(0);
    await panel.getByLabel('租约状态').selectOption('UNKNOWN'); await expect(panel.locator('.worker-lease')).toHaveCount(1); expect(fixture.queries.at(-1)).toContain('state=UNKNOWN');
    await panel.getByRole('button', { name: '查看原始证据' }).click(); await expect(page.locator('.evidence-card.expanded .evidence-output')).toHaveText('原始执行回执 <script>literal data</script>'); expect(await page.locator('.evidence-output script').count()).toBe(0); expect(fixture.errors).toEqual([]);
  });

  test(`节点与仓库配置版本冲突保留输入 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workerFixture(page); await page.goto('/'); await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '执行节点与仓库', exact: true }).click();
    await expect(page.getByRole('heading', { name: '进程 4000 · 代次 7' })).toBeVisible(); await page.getByRole('button', { name: '编辑节点授权' }).click(); let dialog = page.getByRole('dialog');
    await dialog.getByLabel('节点名称').fill('显式核对的本机名称'); await dialog.getByRole('checkbox', { name: '允许此节点执行任务' }).uncheck(); await dialog.getByRole('button', { name: '保存节点版本' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.nodePosts[0]).toMatchObject({ id: 'node-local-id', expectedVersion: 2, enabled: false }); expect(fixture.nodePosts[0]).not.toHaveProperty('epoch');
    await page.getByRole('button', { name: '编辑仓库绑定' }).first().click(); dialog = page.getByRole('dialog'); await dialog.getByLabel('仓库绑定名称').fill('保留冲突时的输入'); fixture.bindings[0].version = 4;
    await dialog.getByRole('button', { name: '保存仓库版本' }).click(); await expect(dialog.getByRole('alert')).toHaveText('仓库绑定版本已变化，请刷新后编辑'); await expect(dialog.getByLabel('仓库绑定名称')).toHaveValue('保留冲突时的输入'); expect(fixture.bindingPosts[0].expectedVersion).toBe(3);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await page.screenshot({ path: `qa/execution-binding-${width === 430 ? 'narrow' : 'desktop'}.png` }); expect(fixture.errors).toEqual([]);
  });
}

test('任务创建携带展示的节点和仓库版本并显示固定源目录', async ({ page }) => {
  const fixture = await workerFixture(page); await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('执行仓库绑定').selectOption('binding-another'); await expect(dialog.getByText(/D:\\Projects\\项目 repo/)).toBeVisible();
  await dialog.getByLabel('任务名称').fill('另一个本机绑定的计划'); await dialog.getByLabel('你希望完成什么？').fill('检查实际源仓库'); await dialog.getByLabel('验收条件', { exact: true }).fill('固定源目录'); await dialog.getByLabel('必要验证命令').fill('node verify.mjs'); await dialog.getByRole('button', { name: '生成任务计划' }).click();
  await expect(dialog).toHaveCount(0); expect(fixture.taskPosts[0]).toMatchObject({ repositoryBindingId: 'binding-another', bindingVersion: 1, nodeVersion: 2 }); expect(fixture.taskPosts[0]).not.toHaveProperty('workerId');
  const contract = page.getByRole('region', { name: '固定执行归属' }); await expect(contract.getByText('D:\\Projects\\项目 repo', { exact: true })).toBeVisible(); await expect(contract.getByText('另一个本机副本 · v1', { exact: true })).toBeVisible(); expect(fixture.errors).toEqual([]);
});

test('任务更新执行授权显式刷新版本并保留原工作目录', async ({ page }) => {
  const fixture = await workerFixture(page); await page.goto('/'); await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '核对实际操作与租约' }).click();
  const originalWorkspace = fixture.task.workspace; await page.getByRole('button', { name: '更新执行授权' }).click(); const dialog = page.getByRole('dialog'); await expect(dialog.getByText('已配对本机 · v2 · 已启用', { exact: true })).toBeVisible(); fixture.bindings[0].version = 4;
  await dialog.getByRole('button', { name: '确认新的执行版本' }).click(); await expect(dialog.getByRole('alert')).toHaveText('执行配置版本已变化，请刷新后确认'); await dialog.getByRole('button', { name: '刷新配置版本' }).click(); await expect(dialog.getByLabel('核对仓库绑定').locator('option:checked')).toHaveText('原仓库目录 · v4');
  await dialog.getByRole('button', { name: '确认新的执行版本' }).click(); await expect(dialog).toHaveCount(0); expect(fixture.authorizations).toHaveLength(2); expect(fixture.authorizations[1]).toMatchObject({ expectedVersion: 1, bindingVersion: 4, nodeVersion: 2 }); expect(fixture.task.workspace).toBe(originalWorkspace); expect(fixture.task.state).toBe('PAUSED'); expect(fixture.errors).toEqual([]);
});
