import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function sessionsFixture(page: Page, loseFirstNoteResponse = false) {
  const project = { id: 'a', name: '会话测试项目', path: 'fixture', branch: 'main', fileCount: 1 };
  const routeConfig = { id: 'route', name: '测试路由', engine: 'API', model: 'fixture', enabled: true };
  const sessions = ['甲会话', '乙会话'].map((title, i) => ({ id: `s${i + 1}`, projectId: 'a', title, version: 1, isOpen: true, createdAt: '2026-11-07T00:00:00Z', updatedAt: '2026-11-07T00:00:00Z' }));
  const messages: any[] = [];
  const notes: any[] = [];
  const taskRequests: any[] = [];
  const tasks: any[] = [{ id: 't1', projectId: 'a', sessionId: 's1', title: '仍在执行的任务', goal: 'fixture', state: 'RUNNING', mode: 'PIPELINE', budget: { usedCalls: 1, maxCalls: 24 }, plan: [], evidence: [], context: [] }];
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  let lost = false;
  await page.route('**/api/**', async route => {
    const request = route.request(); const url = new URL(request.url()); const path = url.pathname;
    if (path.endsWith('/agent-runs')) return route.fulfill({ json: { runs: [], cursor: 0, hasMore: false } });
    if (path === '/api/state') return route.fulfill({ json: { projects: [project, { ...project, id: 'b', name: '另一个项目' }], tasks, routes: [routeConfig], profiles: [], memories: [], system: { storageHealthy: true } } });
    if (path === '/api/mcp/task-tools') return route.fulfill({ json: { tools: [] } });
    if (path === '/api/tasks' && request.method() === 'POST') {
      const body = request.postDataJSON(); taskRequests.push(body);
      const task = { ...tasks[0], ...body, id: 't2', state: 'DRAFT' }; tasks.push(task); return route.fulfill({ json: task });
    }
    if (path.startsWith('/api/tasks/')) {
      if (path.endsWith('/events')) return route.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
      if (path.endsWith('/mcp-operations')) return route.fulfill({ json: [] });
      return route.fulfill({ json: tasks.find(task => path === `/api/tasks/${task.id}`) || {} });
    }
    const match = path.match(/^\/api\/projects\/([^/]+)\/sessions(?:\/([^/]+))?(?:\/(messages|events))?$/);
    if (!match) return route.fulfill({ json: {} });
    const [, projectId, sessionId, resource] = match;
    if (!sessionId) {
      if (request.method() === 'POST') {
        const session = { ...sessions[0], ...request.postDataJSON(), id: `s${sessions.length + 1}`, version: 1, isOpen: true, projectId }; sessions.push(session); return route.fulfill({ json: session });
      }
      return route.fulfill({ json: { sessions: sessions.filter(s => s.projectId === projectId && (url.searchParams.get('openOnly') !== 'true' || s.isOpen)), cursor: 0, hasMore: false } });
    }
    const session = sessions.find(s => s.id === sessionId && s.projectId === projectId);
    if (sessionId === 'search' && request.method() === 'GET') {
      const query = url.searchParams.get('q') || '';
      const found = messages.filter(message => sessions.some(s => s.id === message.sessionId && s.projectId === projectId) && message.content.includes(query)).sort((a, b) => b.sequence - a.sequence);
      return route.fulfill({ json: { results: found.slice(0, 30).map(message => ({ sessionId: message.sessionId, sessionTitle: sessions.find(s => s.id === message.sessionId)?.title, messageId: message.id, sequence: message.sequence, content: message.content, matchStart: message.content.indexOf(query), matchEnd: message.content.indexOf(query) + query.length, createdAt: message.createdAt })), hasMore: found.length > 30 } });
    }
    if (!session) return route.fulfill({ status: 404, json: { message: '当前项目没有此会话' } });
    if (resource === 'events') return route.fulfill({ json: { events: sessionId === 's1' ? [{ id: 1, taskId: 't1', type: 'TASK_CREATED', message: '可追溯活动', createdAt: session.createdAt }] : [], cursor: sessionId === 's1' ? 1 : 0, hasMore: false } });
    if (resource === 'messages') {
      if (request.method() === 'POST') {
        const body = request.postDataJSON(); notes.push(body);
        let message = messages.find(m => m.sessionId === sessionId && m.requestId === body.requestId);
        if (!message) { message = { ...body, id: `m${messages.length + 1}`, sequence: messages.length + 1, sessionId, role: 'USER', kind: 'NOTE', createdAt: session.createdAt }; messages.push(message); }
        if (loseFirstNoteResponse && !lost) { lost = true; return route.abort('failed'); }
        return route.fulfill({ json: message });
      }
      const after = Number(url.searchParams.get('after') || 0); const before = Number(url.searchParams.get('before') || 0);
      const found = messages.filter(m => m.sessionId === sessionId && m.sequence > after && (!before || m.sequence < before)).sort((a, b) => a.sequence - b.sequence);
      const limit = Number(url.searchParams.get('limit') || 50); const visible = after ? found.slice(0, limit) : found.slice(-limit);
      return route.fulfill({ json: { messages: visible, cursor: (after ? visible.at(-1)?.sequence : visible[0]?.sequence) || 0, latestCursor: visible.at(-1)?.sequence || after, hasMore: found.length > limit } });
    }
    if (request.method() === 'POST') {
      const body = request.postDataJSON();
      if (body.expectedVersion !== session.version) return route.fulfill({ status: 409, json: { message: '会话已变化，请刷新后重试' } });
      Object.assign(session, body, { version: session.version + 1 });
    }
    return route.fulfill({ json: session });
  });
  await page.goto('/'); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: '甲会话' })).toBeVisible();
  return { sessions, messages, notes, taskRequests, tasks, errors };
}

test('标签保留草稿，关闭后可从历史打开，刷新后记录仍在', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  await page.getByLabel('协作笔记', { exact: true }).fill('甲会话的背景');
  await page.getByRole('tab', { name: '乙会话' }).click();
  await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('');
  await page.getByRole('tab', { name: '甲会话' }).click();
  await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('甲会话的背景');
  await page.getByRole('button', { name: '保存笔记', exact: true }).click();
  await expect(page.getByText('甲会话的背景', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '关闭会话 甲会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: '甲会话' })).toHaveCount(0);
  expect(fixture.tasks[0].state).toBe('RUNNING');
  await page.getByRole('button', { name: '会话历史', exact: true }).click();
  await page.getByRole('region', { name: '会话历史列表' }).locator('div').filter({ hasText: '甲会话' }).getByRole('button', { name: '打开会话' }).click();
  await expect(page.getByRole('tab', { name: '甲会话' })).toHaveAttribute('aria-selected', 'true');
  await page.reload(); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await expect(page.getByText('甲会话的背景', { exact: true })).toBeVisible();
  await page.getByLabel('选择项目', { exact: true }).selectOption('b');
  await expect(page.getByRole('heading', { name: '选择或创建一个会话' })).toBeVisible();
  await expect(page.getByText('甲会话的背景', { exact: true })).toHaveCount(0);
  expect(fixture.errors).toEqual([]);
});

test('响应丢失后切换标签再重试沿用编号，不重复写入', async ({ page }) => {
  const fixture = await sessionsFixture(page, true);
  await page.getByLabel('协作笔记', { exact: true }).fill('响应丢失时只保存一次');
  await page.getByRole('button', { name: '保存笔记', exact: true }).click();
  await expect(page.getByRole('alert')).toBeVisible();
  await page.getByRole('tab', { name: '乙会话' }).click(); await page.getByRole('tab', { name: '甲会话' }).click();
  await page.getByRole('button', { name: '保存笔记', exact: true }).click();
  await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('');
  expect(fixture.notes).toHaveLength(2); expect(fixture.notes[0].requestId).toBe(fixture.notes[1].requestId); expect(fixture.messages).toHaveLength(1);
  expect(fixture.errors).toEqual([]);
});

test('会话任务固定项目和会话，详情返回后保留草稿', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  await page.getByLabel('协作笔记', { exact: true }).fill('待补充的背景');
  await page.getByRole('button', { name: '仍在执行的任务', exact: false }).click();
  await page.getByRole('button', { name: '返回会话', exact: true }).click();
  await expect(page.getByLabel('协作笔记', { exact: true })).toHaveValue('待补充的背景');
  await page.getByRole('button', { name: '新建会话任务', exact: true }).click();
  await expect(page.getByRole('dialog').getByLabel('所属项目', { exact: true })).toBeDisabled();
  await page.getByLabel('任务名称', { exact: true }).fill('第二个任务');
  await page.getByLabel('你希望完成什么？').fill('有明确目标的会话任务');
  await page.getByLabel('验收条件', { exact: true }).fill('行为通过验证');
  await page.getByLabel('必要验证命令', { exact: true }).fill('npm test');
  await page.getByRole('button', { name: '生成任务计划', exact: true }).click();
  await expect.poll(() => fixture.taskRequests.length).toBe(1);
  expect(fixture.taskRequests[0].projectId).toBe('a'); expect(fixture.taskRequests[0].sessionId).toBe('s1');
  expect(fixture.taskRequests[0]).not.toHaveProperty('approved');
  expect(fixture.errors).toEqual([]);
});

test('创建、重命名与窄屏浏览无横向溢出', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  await page.getByLabel('新会话名称', { exact: true }).fill('新功能研发');
  await page.getByRole('button', { name: '创建会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: '新功能研发' })).toHaveAttribute('aria-selected', 'true');
  await page.getByLabel('当前会话名称', { exact: true }).fill('持久协作空间');
  await page.getByRole('button', { name: '重命名', exact: true }).click();
  await expect(page.getByRole('tab', { name: '持久协作空间' })).toBeVisible();
  await expect(page.getByRole('alert')).toHaveCount(0);
  await page.screenshot({ path: 'qa/sessions-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 430, height: 932 });
  await expect(page.getByRole('button', { name: '新建会话任务', exact: true })).toBeVisible();
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'qa/sessions-narrow.png', fullPage: true });
  expect(fixture.errors).toEqual([]);
});

test('过期重命名显示冲突并刷新，保留服务端的新名称', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  fixture.sessions[0].title = '另一个窗口更新'; fixture.sessions[0].version = 2;
  await page.getByLabel('当前会话名称', { exact: true }).fill('过期的覆盖');
  await page.getByRole('button', { name: '重命名', exact: true }).click();
  await expect(page.getByRole('alert')).toHaveText('会话已变化，请刷新后重试');
  await expect(page.getByRole('tab', { name: '另一个窗口更新' })).toBeVisible();
  expect(fixture.sessions[0].title).toBe('另一个窗口更新'); expect(fixture.sessions[0].version).toBe(2);
  expect(fixture.errors).toEqual([]);
});

test('搜索跨会话消息并定位到原记录', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  fixture.messages.push({ id: 'm-search', sessionId: 's1', requestId: 'search', sequence: 9, role: 'USER', kind: 'NOTE', content: '跨会话搜索定位目标', createdAt: fixture.sessions[0].createdAt });
  await page.getByLabel('搜索会话记录', { exact: true }).fill('搜索定位');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  await expect(page.getByRole('region', { name: '会话搜索结果' }).locator('mark')).toHaveText('搜索定位');
  await page.screenshot({ path: 'qa/session-search-desktop.png', fullPage: true });
  await page.setViewportSize({ width: 430, height: 932 });
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.screenshot({ path: 'qa/session-search-narrow.png', fullPage: true });
  await page.getByRole('region', { name: '会话搜索结果' }).locator('.session-search-result').click();
  await expect(page.locator('#session-message-m-search')).toBeVisible();
  await expect(page.locator('#session-message-m-search')).toHaveClass(/search-target/);
  expect(fixture.errors).toEqual([]);
});

test('关闭会话搜索可重新打开，历史定位保持连续且可返回最新', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  await page.clock.install();
  for (let sequence = 1; sequence <= 140; sequence++) fixture.messages.push({ id: `history-${sequence}`, sessionId: 's1', sequence, role: 'USER', kind: 'NOTE', content: sequence === 20 ? '历史定位关键词 <img src=x onerror="alert(1)">' : `连续记录 ${sequence}`, createdAt: fixture.sessions[0].createdAt });
  await page.getByRole('button', { name: '关闭会话 甲会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: '甲会话' })).toHaveCount(0);
  await page.getByLabel('搜索会话记录', { exact: true }).fill('历史定位关键词');
  await page.getByRole('button', { name: '搜索', exact: true }).click();
  const results = page.getByRole('region', { name: '会话搜索结果' });
  await expect(results.locator('mark')).toHaveText('历史定位关键词');
  await expect(results.locator('img')).toHaveCount(0);
  await results.locator('.session-search-result').click();
  await expect(page.getByRole('tab', { name: '甲会话' })).toHaveAttribute('aria-selected', 'true');
  await expect(page.locator('#session-message-history-20')).toBeFocused();
  await page.clock.runFor(6000);
  await expect(page.locator('#session-message-history-140')).toHaveCount(0);
  await expect(page.getByRole('region', { name: '会话记录', exact: true }).locator('article')).toHaveCount(20);
  await page.getByRole('button', { name: '返回最新记录', exact: true }).click();
  await expect(page.locator('#session-message-history-140')).toBeVisible();
  await expect(page.getByRole('region', { name: '会话记录', exact: true }).locator('article')).toHaveCount(50);
  expect(fixture.sessions[0].isOpen).toBe(true); expect(fixture.tasks[0].state).toBe('RUNNING');
  expect(fixture.errors).toEqual([]);
});

test('全局搜索按钮和 Ctrl/Cmd K 从其他页面聚焦当前项目检索', async ({ page }) => {
  const fixture = await sessionsFixture(page);
  await page.getByRole('button', { name: '项目总览', exact: true }).click();
  await page.getByRole('button', { name: '全局搜索会话', exact: true }).click();
  await expect(page.getByLabel('搜索会话记录', { exact: true })).toBeFocused();
  await page.getByRole('button', { name: '研发任务', exact: true }).click();
  await page.keyboard.press('Control+k');
  await expect(page.getByLabel('搜索会话记录', { exact: true })).toBeFocused();
  await page.getByRole('button', { name: '研发任务', exact: true }).click();
  await page.keyboard.press('Meta+k');
  await expect(page.getByLabel('搜索会话记录', { exact: true })).toBeFocused();
  expect(fixture.errors).toEqual([]);
});
