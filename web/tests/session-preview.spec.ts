import { expect, test, type Page } from '@playwright/test';

async function previewFixture(page: Page, desktop = false) {
  const session = { id: 'session-preview', projectId: 'project-preview', title: '隔离预览', version: 1, isOpen: true, createdAt: '2026-11-17T00:00:00Z', updatedAt: '2026-11-17T00:00:00Z' };
  const project = { id: session.projectId, name: '预览项目', path: 'fixture', branch: 'main', fileCount: 0 };
  const value: any = { id: session.id, projectId: session.projectId, sessionId: session.id, executionNodeId: 'node', serverId: 'server', version: 1,
    authorizationVersion: 1, storageGeneration: 1, allowedLoopbackPorts: [], tabs: [], activeTabId: '', protocolVersion: 1 };
  const requests: any[] = [], errors: string[] = [];
  const host = { actions: [] as any[], rejectOpen: '' };
  if (desktop) {
    await page.exposeFunction('__previewAction', (input: any) => {
      host.actions.push(input);
      if (input.type !== 'open') return;
      if (host.rejectOpen) throw new Error(host.rejectOpen);
      if (input.expectedVersion !== value.version) throw new Error('预览配置已变化，请刷新后重新打开地址。');
      const url = new URL(input.url), tab = { id: input.tabId, url: `${url.origin}${url.pathname}`, title: '' };
      value.tabs = value.tabs.some((item: any) => item.id === tab.id) ? value.tabs.map((item: any) => item.id === tab.id ? tab : item) : [...value.tabs, tab];
      value.activeTabId = tab.id; value.version++;
      return structuredClone(value);
    });
    await page.addInitScript(() => {
      const previewWindow = window as any;
      previewWindow.__previewListeners = new Set();
      previewWindow.workbench = {
        getConnection: async () => ({ baseUrl: location.origin, token: '' }),
        desktopPreview: {
          attach: async () => ({ attached: true, tabs: [], activeTabId: '' }),
          action: (input: any) => previewWindow.__previewAction(input),
          onEvent: (callback: any) => { previewWindow.__previewListeners.add(callback); return () => previewWindow.__previewListeners.delete(callback); },
        },
      };
    });
  }
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async route => {
    const request = route.request(), url = new URL(request.url()), pathname = url.pathname;
    if (pathname === '/api/state') return route.fulfill({ json: { projects: [project], tasks: [], routes: [], profiles: [], memories: [], system: { storageHealthy: true } } });
    if (pathname === `/api/projects/${project.id}/sessions`) return route.fulfill({ json: { sessions: [session], cursor: 0, hasMore: false } });
    if (pathname.endsWith('/agent-runs')) return route.fulfill({ json: { runs: [], cursor: 0, hasMore: false } });
    if (pathname.endsWith('/preview/reset')) {
      const body = request.postDataJSON(); requests.push({ path: pathname, body });
      if (body.expectedVersion !== value.version) return route.fulfill({ status: 409, json: { message: '预览配置已经变化，请刷新后确认。' } });
      value.version++; value.storageGeneration++; value.authorizationVersion++; value.tabs = []; value.activeTabId = '';
      return route.fulfill({ json: value });
    }
    if (pathname.endsWith('/preview')) {
      if (request.method() === 'GET') return route.fulfill({ json: value });
      const body = request.postDataJSON(); requests.push({ path: pathname, body });
      if (body.expectedVersion !== value.version) return route.fulfill({ status: 409, json: { message: '预览配置已经变化，请刷新后确认。' } });
      value.version++; if (body.allowedLoopbackPorts) value.allowedLoopbackPorts = body.allowedLoopbackPorts;
      if (body.tabs) value.tabs = body.tabs; if (body.activeTabId !== undefined) value.activeTabId = body.activeTabId;
      return route.fulfill({ json: value });
    }
    if (pathname.includes('/sessions/') && pathname.endsWith('/events')) return route.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (pathname.includes('/sessions/') && pathname.endsWith('/messages')) return route.fulfill({ json: { messages: [], cursor: 0, latestCursor: 0, hasMore: false } });
    if (pathname.includes('/sessions/') && request.method() === 'GET') return route.fulfill({ json: session });
    return route.fulfill({ json: {} });
  });
  await page.goto('/'); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await expect(page.getByRole('region', { name: '会话网页预览' })).toBeVisible();
  return { session, value, requests, errors, host };
}

test('独立 Web 保留项目会话预览授权与配置，并拒绝不安全 URL', async ({ page }) => {
  const fixture = await previewFixture(page);
  await page.getByLabel('允许访问的本机端口').fill('5173, 3000');
  await page.getByRole('button', { name: '更新授权' }).click();
  await expect.poll(() => fixture.value.allowedLoopbackPorts).toEqual([5173, 3000]);
  const address = page.getByLabel('网页地址');
  await address.fill('javascript:alert(1)'); await page.getByRole('button', { name: '转到' }).click();
  await expect(page.getByRole('alert')).toContainText('完整 HTTP 或 HTTPS');
  await address.fill('https://example.test/login?token=secret#code'); await page.getByRole('button', { name: '转到' }).click();
  await expect.poll(() => fixture.value.tabs).toHaveLength(1);
  expect(fixture.value.tabs[0].url).toBe('https://example.test/login');
  expect(JSON.stringify(fixture.value)).not.toContain('secret');
  await page.getByRole('button', { name: '关闭预览标签' }).click();
  await expect.poll(() => fixture.value.tabs).toHaveLength(0);
  expect(fixture.errors).toEqual([]);
});

test('会话存储重置推进授权代次并清空标签', async ({ page }) => {
  const fixture = await previewFixture(page);
  fixture.value.tabs = [{ id: 'tab-one', url: 'https://example.test/', title: '' }]; fixture.value.activeTabId = 'tab-one'; fixture.value.version = 2;
  await page.reload(); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await page.getByRole('button', { name: '重置会话存储' }).click();
  await expect.poll(() => fixture.value.storageGeneration).toBe(2);
  expect(fixture.value.tabs).toEqual([]); expect(fixture.value.authorizationVersion).toBe(2); expect(fixture.errors).toEqual([]);
});

test('预览地址可清空，新建标签和选中状态在刷新后保留', async ({ page }) => {
  const fixture = await previewFixture(page), address = page.getByLabel('网页地址');
  await address.fill('https://first.example.test/path?code=one#callback');
  await page.getByRole('button', { name: '转到', exact: true }).click();
  await expect.poll(() => fixture.value.tabs.length).toBe(1);
  await address.fill(''); await expect(address).toHaveValue('');
  await expect(page.getByRole('button', { name: '转到', exact: true })).toBeDisabled();
  await page.getByRole('button', { name: '新建预览标签', exact: true }).click();
  await expect(address).toBeFocused(); await expect(address).toHaveValue('');
  await address.fill('https://second.example.test/page'); await page.getByRole('button', { name: '转到', exact: true }).click();
  await expect.poll(() => fixture.value.tabs.length).toBe(2);
  await page.getByRole('tab', { name: 'first.example.test', exact: true }).click();
  await expect.poll(() => fixture.value.activeTabId).toBe(fixture.value.tabs[0].id);
  await page.reload(); await page.getByRole('button', { name: '协作会话', exact: true }).click();
  await expect(page.getByRole('tab', { name: 'first.example.test', exact: true })).toHaveAttribute('aria-selected', 'true');
  await expect(address).toHaveValue('https://first.example.test/path');
  expect(fixture.errors).toEqual([]);
});

test('桌面打开失败在成功轮询后仍保留提示与输入，明确重新提交后才导航', async ({ page }) => {
  const fixture = await previewFixture(page, true);
  const panel = page.getByRole('region', { name: '会话网页预览' }), address = panel.getByLabel('网页地址');
  const target = 'https://example.test/other?code=one#callback';
  fixture.host.rejectOpen = '预览配置已变化，请刷新后重新打开地址。';
  await panel.getByRole('button', { name: '新建预览标签', exact: true }).click();
  await address.fill(target); await panel.getByRole('button', { name: '转到', exact: true }).click();
  await expect(panel.getByRole('alert')).toContainText(fixture.host.rejectOpen);
  await page.waitForResponse(response => response.url().endsWith('/preview') && response.request().method() === 'GET');
  await expect(panel.getByRole('alert')).toContainText(fixture.host.rejectOpen);
  await expect(address).toHaveValue(target); expect(fixture.host.actions.filter(item => item.type === 'open')).toHaveLength(1);
  expect(fixture.value.tabs).toHaveLength(0);
  fixture.host.rejectOpen = '';
  await panel.getByRole('button', { name: '转到', exact: true }).click();
  await expect.poll(() => fixture.value.tabs.length).toBe(1); await expect(panel.getByRole('alert')).toHaveCount(0);
  await expect(panel.getByRole('tab', { name: 'example.test', exact: true })).toHaveAttribute('aria-selected', 'true');
  expect(fixture.host.actions.filter(item => item.type === 'open')).toHaveLength(2);
  expect(fixture.value.tabs[0].url).toBe('https://example.test/other'); expect(fixture.errors).toEqual([]);
});
