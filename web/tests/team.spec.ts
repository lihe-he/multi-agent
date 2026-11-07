import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

// Browser protocol fixtures; actual execution is covered by the Java and Jar tests.
async function teamFixture(page: Page, loseFirstResponse = false) {
  const project = { id: 'team-project', name: '团队界面验证项目', path: 'fixture-only', branch: 'main', fileCount: 3 };
  const routes = [
    { id: 'root-route', version: 1, name: '根任务路由', engine: 'API', model: 'root-fixture-model', baseUrl: 'http://127.0.0.1:9901/v1', enabled: true },
    { id: 'alternate-route', version: 1, name: '另行确认的模型', engine: 'API', model: 'alternate-fixture-model', baseUrl: 'http://127.0.0.1:9902/v1', enabled: true },
    { id: 'cli-route', version: 1, name: '原生 CLI', engine: 'CODEX', executable: 'fixture-only', enabled: true },
  ];
  const definitions: any[] = [
    { id: 'agent-developer', version: 1, name: '实现副 Agent', role: 'developer', instructions: '尊重已有注释，交付实际文件与原始证据。', capabilities: ['read', 'search', 'write', 'delegate'], scope: ['**'], maxCalls: 8, enabled: true },
    { id: 'agent-reviewer', version: 1, name: '核对副 Agent', role: 'reviewer', instructions: '只读检查依赖成果。', capabilities: ['read', 'search'], scope: ['src/'], maxCalls: 5, enabled: true },
    { id: 'agent-research', version: 1, name: '调查副 Agent', role: 'researcher', instructions: '仅使用经任务确认的额外模型。', capabilities: ['read', 'search'], routeId: 'alternate-route', scope: ['src/'], maxCalls: 4, enabled: true },
  ];
  const fixedDefinitions = definitions.map(definition => ({ ...definition, routeSnapshot: routes.find(route => route.id === (definition.routeId || 'root-route')) }));
  const task: any = { id: 'team-task', projectId: project.id, title: '查看副任务与团队来源', goal: '仅用于浏览器协议测试，不执行模型。', state: 'RUNNING', mode: 'LEAD', routeId: 'root-route', routeSnapshot: routes[0], contract: { scope: ['src/'], commands: ['node verify.mjs'], acceptance: ['范围与来源可核对'] }, budget: { maxCalls: 24, usedCalls: 8, reserveCalls: 4 }, plan: [], context: [], teamId: 'team-id', collaboration: { enabled: true, maxActive: 6, maxDepth: 3, maxTotal: 24, spawned: 2, definitions: fixedDefinitions }, evidence: [{ id: 'writer-evidence', kind: 'SUBAGENT', stage: 'subagent-writer', status: 'PASSED', version: 'a'.repeat(64), output: 'writer original evidence <script>literal data</script>' }], createdAt: '2026-11-07T10:00:00Z' };
  const tasks: any[] = [task];
  const permissions = { capabilities: ['read', 'search', 'write'], readScopeLayers: [['**'], ['src/']], writeScopeLayers: [['src/'], ['src/']] };
  const agents: any[] = [
    { id: 'writer-child', taskId: task.id, agentRunId: 'writer-run', parentRunId: 'parent-run', title: '授权范围实现', goal: '在 src/source.txt 中实现行为并保留父现场后续改动。', role: 'developer', definitionId: 'agent-developer', definitionVersion: 1, depth: 1, dependsOn: [], state: 'SUCCEEDED', permissions, createdAt: task.createdAt, endedAt: task.createdAt, resultVersion: 'a'.repeat(64), integrationState: 'ADOPTED', integrations: [{ operationId: 'adopt-operation-1', agentRunId: 'parent-run', resultVersion: 'b'.repeat(64), createdAt: task.createdAt }], evidenceId: 'writer-evidence', routeId: 'root-route', model: 'root-fixture-model', requestId: 'parent-request-1', toolCallId: 'delegate-tool-1' },
    { id: 'reader-child', taskId: task.id, agentRunId: 'reader-run', parentRunId: 'parent-run', title: '依赖成果核对', goal: '等待授权范围实现的原始成果，然后只读核对。', role: 'reviewer', definitionId: 'agent-reviewer', definitionVersion: 1, depth: 1, dependsOn: ['writer-child'], state: 'WAITING_DEPENDENCY', permissions: { ...permissions, capabilities: ['read', 'search'] }, createdAt: task.createdAt, integrationState: 'NOT_ADOPTED', routeId: 'root-route', model: 'root-fixture-model', requestId: 'parent-request-2', toolCallId: 'delegate-tool-2' },
  ];
  const runs = [{ id: 'parent-run', title: '实现父阶段', role: 'developer', state: 'WAITING_CHILDREN', stage: 'implement', kind: 'IMPLEMENT', taskId: task.id, teamId: task.teamId }, ...agents.map(agent => ({ id: agent.agentRunId, subtaskId: agent.id, title: agent.title, role: agent.role, state: agent.state, stage: `subagent-${agent.id}`, kind: 'SUBAGENT', taskId: task.id, teamId: task.teamId }))];
  const messages: any[] = Array.from({ length: 85 }, (_, index) => ({ id: `message-${index + 1}`, sequence: index + 1, sender: 'writer-run', recipient: '', content: `来源资料 #${index + 1}`, requestId: 'writer-request-source', toolCallId: 'send-tool-source', createdAt: task.createdAt }));
  const messagePosts: any[] = [], taskPosts: any[] = [], definitionPosts: any[] = [], messageQueries: string[] = [], modelQueries: string[] = [], errors: string[] = [];
  let lost = false; page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname;
    if (path === '/api/state') return route.fulfill({ json: { projects: [project], tasks: tasks.map(item => ({ ...item, collaboration: item.collaboration ? { ...item.collaboration, definitions: undefined } : undefined, evidence: undefined })), routes, agentDefinitions: definitions, profiles: [], memories: [], system: { modelCapacity: 2, activeModels: 0, storageHealthy: true, version: 'team-ui-fixture' } } });
    if (path === '/api/agent-definitions') {
      if (request.method() === 'GET') return route.fulfill({ json: definitions });
      const body = request.postDataJSON(); definitionPosts.push(body); const previous = definitions.find(definition => definition.id === body.id);
      if (previous && previous.version !== body.expectedVersion) return route.fulfill({ status: 409, json: { message: 'Agent 定义已变化，请刷新后编辑' } });
      const saved = { ...body, id: body.id || `custom-${definitions.length}`, version: (previous?.version || 0) + 1 };
      if (previous) Object.assign(previous, saved); else definitions.push(saved);
      return route.fulfill({ json: saved });
    }
    if (path === '/api/mcp/task-tools') return route.fulfill({ json: { tools: [] } });
    if (path === '/api/tasks' && request.method() === 'POST') {
      const body = request.postDataJSON(); taskPosts.push(body);
      if (body.collaboration?.enabled && body.collaboration.definitionIds.some((id: string) => body.collaboration.definitionVersions[id] !== definitions.find(definition => definition.id === id)?.version)) return route.fulfill({ status: 409, json: { message: '副 Agent 定义已变化，请刷新并核对' } });
      const created = { ...task, ...body, id: 'created-task', state: 'DRAFT', evidence: [], collaboration: { ...body.collaboration, spawned: 0, definitions: definitions.filter(definition => body.collaboration.definitionIds.includes(definition.id)).map(definition => ({ ...definition, routeSnapshot: routes.find(route => route.id === (definition.routeId || body.routeId)) })) } };
      tasks.push(created); return route.fulfill({ json: created });
    }
    if (path === '/api/tasks/team-task/team') return route.fulfill({ json: { team: { id: task.teamId, name: task.title, taskState: task.state }, agents, runs, limits: task.collaboration } });
    if (path === '/api/tasks/team-task/team/messages') {
      if (request.method() === 'POST') {
        const body = request.postDataJSON(); messagePosts.push(body); let message = messages.find(message => message.key === body.requestId && message.sender === 'USER');
        if (!message) { message = { ...body, key: body.requestId, requestId: '', toolCallId: '', sender: 'USER', id: `message-${messages.length + 1}`, sequence: messages.length + 1, createdAt: task.createdAt }; messages.push(message); }
        if (loseFirstResponse && !lost) { lost = true; return route.abort('failed'); }
        return route.fulfill({ json: message });
      }
      messageQueries.push(url.search); const before = Number(url.searchParams.get('before') || 0), limit = Number(url.searchParams.get('limit') || 40);
      const found = messages.filter(message => !before || message.sequence < before).sort((a, b) => b.sequence - a.sequence), visible = found.slice(0, limit);
      return route.fulfill({ json: { messages: visible, cursor: visible.at(-1)?.sequence || before, hasMore: found.length > limit } });
    }
    if (path.endsWith('/model-requests')) {
      modelQueries.push(url.search); const runId = url.searchParams.get('runId');
      return route.fulfill({ json: { requests: [{ id: 'writer-http-request', taskId: task.id, agentRunId: runId || 'writer-run', stage: 'subagent-writer', sequence: 1, attempt: 1, state: 'SUCCEEDED', engine: 'API', model: 'root-fixture-model', unit: 'API_REQUEST', createdAt: task.createdAt, httpStatus: 200, usage: { inputTokens: 12, outputTokens: 0 }, outputPreview: 'actual source fixture' }], cursor: 1, hasMore: false } });
    }
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: { runs, cursor: 0, hasMore: false } });
    if (path.endsWith('/events')) return route.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (path.endsWith('/mcp-operations')) return route.fulfill({ json: [] });
    if (path.startsWith('/api/tasks/')) return route.fulfill({ json: tasks.find(item => path === `/api/tasks/${item.id}`) || {} });
    return route.fulfill({ json: {} });
  });
  return { task, routes, definitions, agents, runs, messages, messagePosts, taskPosts, definitionPosts, messageQueries, modelQueries, errors };
}

async function openTeam(page: Page) {
  await page.goto('/'); await page.getByRole('button', { name: /查看副任务与团队来源/ }).click(); await page.getByRole('button', { name: '团队与副 Agent', exact: true }).click();
  await expect(page.getByRole('region', { name: '团队与副 Agent' }).getByRole('heading', { name: '授权范围实现', exact: true })).toBeVisible();
}

for (const width of [1440, 430]) {
  test(`定义编辑提交能力、路由和版本，过期编辑保留输入 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const fixture = await teamFixture(page);
    await page.goto('/'); await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '动态副 Agent', exact: true }).click();
    const card = page.locator('.definition-card').filter({ has: page.getByRole('heading', { name: '实现副 Agent', exact: true }) }); await card.getByRole('button', { name: '编辑定义与模型' }).click();
    const dialog = page.getByRole('dialog'); await dialog.getByLabel('Agent 名称', { exact: true }).fill('版本化实现副 Agent'); await dialog.getByLabel('职责与交付要求').fill('保留已有注释与字面资料 <script>data</script>'); await dialog.getByLabel('文件范围', { exact: true }).fill('src/' + String.fromCharCode(10) + 'tests/'); await dialog.getByLabel('模型运行路由').selectOption('alternate-route'); await dialog.getByLabel('单次调用上限').fill('6');
    await dialog.getByLabel('验证命令', { exact: true }).check(); fixture.definitions[0].version = 2;
    await dialog.getByRole('button', { name: '保存定义版本' }).click(); await expect(dialog.getByRole('alert')).toHaveText('Agent 定义已变化，请刷新后编辑'); await expect(dialog.getByLabel('Agent 名称')).toHaveValue('版本化实现副 Agent');
    expect(fixture.definitionPosts[0]).toMatchObject({ id: 'agent-developer', expectedVersion: 1, routeId: 'alternate-route', scope: ['src/', 'tests/'], maxCalls: 6 }); expect(fixture.definitionPosts[0].capabilities).toContain('verify');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); await page.screenshot({ path: `qa/agent-definition-${width === 430 ? 'narrow' : 'desktop'}.png` });
    await dialog.getByRole('button', { name: '取消', exact: true }).click(); await expect(card.getByText('developer · v2')).toBeVisible(); await card.getByRole('button', { name: '编辑定义与模型' }).click(); await dialog.getByLabel('Agent 名称').fill('保存第二版之后'); await dialog.getByRole('button', { name: '保存定义版本' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.definitionPosts[1].expectedVersion).toBe(2); expect(fixture.definitions[0].version).toBe(3); expect(fixture.errors).toEqual([]);
  });

  test(`团队显示真实字段、依赖、权限与证据，历史轮询保持游标 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const fixture = await teamFixture(page); await openTeam(page);
    const panel = page.getByRole('region', { name: '团队与副 Agent' }), writer = panel.locator('.team-agent').filter({ has: page.getByRole('heading', { name: '授权范围实现', exact: true }) });
    await expect(panel.getByText('活动 2 / 6', { exact: true })).toBeVisible(); await expect(panel.getByText('等待前置副任务', { exact: true })).toBeVisible(); await expect(writer.getByText('已执行完成')).toBeVisible();
    await writer.getByText('查看实际权限与来源', { exact: true }).click(); await expect(writer.getByText('parent-request-1', { exact: true })).toBeVisible(); await expect(writer.getByText('delegate-tool-1', { exact: true })).toBeVisible(); await expect(writer.getByText('adopt-operation-1', { exact: true })).toBeVisible();
    await writer.getByRole('button', { name: '查看副任务请求' }).click(); await expect(writer.getByRole('region', { name: '模型请求追踪' }).getByText('subagent-writer · 第 1 次 API 请求')).toBeVisible(); expect(fixture.modelQueries.at(-1)).toContain('runId=writer-run');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); await panel.getByRole('heading', { name: '团队与动态副 Agent' }).scrollIntoViewIfNeeded(); await page.screenshot({ path: `qa/team-${width === 430 ? 'narrow' : 'desktop'}.png` });
    await panel.getByLabel('副任务状态').selectOption('live'); await expect(panel.getByRole('heading', { name: '授权范围实现', exact: true })).toHaveCount(0); await expect(panel.getByRole('heading', { name: '依赖成果核对', exact: true })).toBeVisible();
    await panel.getByLabel('副任务状态').selectOption(''); await panel.getByRole('button', { name: '查看原始证据', exact: true }).click(); await expect(page.locator('.evidence-card.expanded .evidence-output')).toHaveText('writer original evidence <script>literal data</script>'); expect(await page.locator('.evidence-output script').count()).toBe(0);
    await page.getByRole('button', { name: '团队与副 Agent', exact: true }).click(); const messages = panel.getByRole('region', { name: '团队消息' }); await messages.getByRole('button', { name: '加载更早消息' }).click(); await expect(messages.getByText('来源资料 #45', { exact: true })).toBeVisible();
    fixture.messages.push({ ...fixture.messages[0], id: 'new-message', sequence: 86, content: '新消息不改变历史页' }); await page.waitForTimeout(3300); expect(fixture.messageQueries.at(-1)).toContain('before=46'); await expect(messages.getByText('新消息不改变历史页', { exact: true })).toHaveCount(0);
    await messages.getByRole('button', { name: '返回最新消息' }).click(); await expect(messages.getByText('新消息不改变历史页', { exact: true })).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth)).toBe(true); expect(fixture.errors).toEqual([]);
  });
}

test('任务固定所选定义版本与额外路由，版本变化后可刷新再确认', async ({ page }) => {
  const fixture = await teamFixture(page); await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('任务名称').fill('版本明确的委派计划'); await dialog.getByLabel('你希望完成什么？').fill('按明确范围协作并核对来源'); await dialog.getByLabel('验收条件', { exact: true }).fill('原始来源可查看'); await dialog.getByLabel('必要验证命令').fill('node verify.mjs'); await dialog.getByLabel('允许修改的范围').fill('src/');
  await dialog.getByRole('checkbox', { name: /调查副 Agent v1/ }).check(); await dialog.getByLabel('活动或排队 Agent 上限').fill('3'); await dialog.getByLabel('最大委派深度').fill('2'); await dialog.getByLabel('根累计委派上限').fill('5');
  fixture.definitions[0].version = 2; fixture.definitions[0].instructions = '版本二固定职责'; await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog.getByText('副 Agent 定义已变化，请刷新并核对', { exact: true })).toBeVisible();
  await dialog.getByRole('button', { name: '刷新副 Agent 定义' }).click(); await expect(dialog.getByRole('checkbox', { name: /实现副 Agent v2/ })).toBeChecked(); await dialog.getByRole('checkbox', { name: /调查副 Agent v1/ }).check(); await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog).toHaveCount(0);
  expect(fixture.taskPosts).toHaveLength(2); expect(fixture.taskPosts[0].collaboration.definitionVersions['agent-developer']).toBe(1); const body = fixture.taskPosts[1];
  expect(body.collaboration).toMatchObject({ enabled: true, maxActive: 3, maxDepth: 2, maxTotal: 5, allowedRouteIds: ['alternate-route'], definitionVersions: { 'agent-developer': 2, 'agent-reviewer': 1, 'agent-research': 1 } }); expect(body).not.toHaveProperty('approved');
  const contract = page.getByRole('region', { name: '固定委派约定' }); await expect(contract.getByText('实现副 Agent · v2', { exact: true })).toBeVisible(); await expect(contract.getByText(/alternate-fixture-model/)).toBeVisible(); await page.waitForTimeout(3300); await expect(contract.getByText('实现副 Agent · v2', { exact: true })).toBeVisible(); expect(fixture.errors).toEqual([]);
});

test('原生 CLI 创建计划时明确关闭动态委派', async ({ page }) => {
  const fixture = await teamFixture(page); await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('首选运行路由').selectOption('cli-route'); await expect(dialog.getByRole('checkbox', { name: /允许在执行中创建副 Agent/ })).toBeDisabled(); await expect(dialog.getByRole('checkbox', { name: /允许在执行中创建副 Agent/ })).not.toBeChecked();
  await dialog.getByLabel('任务名称').fill('原生工具单阶段执行'); await dialog.getByLabel('你希望完成什么？').fill('验证兼容的原生执行计划'); await dialog.getByLabel('验收条件', { exact: true }).fill('运行器可核对'); await dialog.getByLabel('必要验证命令').fill('node verify.mjs'); await dialog.getByRole('button', { name: '生成任务计划' }).click();
  await expect.poll(() => fixture.taskPosts.length).toBe(1); expect(fixture.taskPosts[0].collaboration.enabled).toBe(false); expect(fixture.taskPosts[0].routeId).toBe('cli-route'); expect(fixture.errors).toEqual([]);
});

test('消息回执丢失后切换页面与重载，重试沿用编号并只保留一条', async ({ page }) => {
  const fixture = await teamFixture(page, true); await openTeam(page); const panel = page.getByRole('region', { name: '团队消息' });
  await panel.getByLabel('团队消息', { exact: true }).fill('待确认的消息 <script>literal data</script>'); await panel.getByRole('button', { name: '发送团队消息' }).click(); await expect(panel.getByRole('button', { name: '重试原消息' })).toBeVisible();
  await page.getByRole('button', { name: '协作计划', exact: true }).click(); await page.getByRole('button', { name: '团队与副 Agent', exact: true }).click(); await expect(panel.getByLabel('团队消息', { exact: true })).toBeDisabled(); await page.reload(); await openTeam(page);
  await expect(panel.getByLabel('团队消息', { exact: true })).toHaveValue('待确认的消息 <script>literal data</script>'); await panel.getByRole('button', { name: '重试原消息' }).click(); await expect(panel.getByLabel('团队消息', { exact: true })).toHaveValue(''); await expect(panel.getByRole('status')).toHaveText('消息已保存');
  expect(fixture.messagePosts).toHaveLength(2); expect(fixture.messagePosts[0]).toEqual(fixture.messagePosts[1]); expect(fixture.messages.filter(message => message.sender === 'USER')).toHaveLength(1); expect(fixture.messagePosts[0]).not.toHaveProperty('sender'); expect(await panel.locator('script').count()).toBe(0); expect(fixture.errors).toEqual([]);
});

test('结束恢复的重试后立即解锁输入并保留草稿', async ({ page }) => {
  await page.addInitScript(() => sessionStorage.setItem('workbench-team-draft::team-task', JSON.stringify({ recipient: '', content: '可继续编辑的草稿', pending: { requestId: 'restored-message-id', recipient: '', content: '可继续编辑的草稿' } })));
  const fixture = await teamFixture(page); await openTeam(page); const panel = page.getByRole('region', { name: '团队消息' }); await expect(panel.getByLabel('团队消息', { exact: true })).toBeDisabled(); await panel.getByRole('button', { name: '保留草稿并结束重试' }).click();
  await expect(panel.getByLabel('团队消息', { exact: true })).toBeEnabled(); await expect(panel.getByLabel('团队消息', { exact: true })).toHaveValue('可继续编辑的草稿'); expect(fixture.messagePosts).toHaveLength(0); expect(fixture.errors).toEqual([]);
});
