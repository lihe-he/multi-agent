import { expect, test, type Page, type TestInfo } from '@playwright/test';
import { workspaceFixture } from './fixtures/workspaces';

const themes = [
  ['pure-white', '纯白', 'light'], ['paper-ink', '纸墨', 'light'], ['classic-warm', '经典暖色', 'light'],
  ['celadon', '青瓷', 'light'], ['ink-night', '墨夜', 'dark'], ['ink-night-blue', '墨夜蓝', 'dark'],
] as const;

async function fixture(page: Page) {
  const original = await workspaceFixture(page), task = original.task;
  task.state = 'AWAITING_ACCEPTANCE'; task.sessionId = 'theme-session';
  task.plan = [{ id: 'done', title: '已经实现', role: 'developer', state: 'COMPLETED' }, { id: 'active', title: '等待独立审查', role: 'reviewer', state: 'RUNNING' }, { id: 'failure', title: '原失败保留', role: 'verifier', state: 'FAILED' }];
  task.review = { status: 'CHANGES_REQUESTED', output: '独立审查保留原始输出。中文与 code 都应清晰可读。' };
  task.evidence = [{ id: 'theme-evidence', command: 'node verify.mjs', kind: 'VERIFICATION', status: 'PASSED', exitCode: 0, output: '实际输出样式夹具：完成一项检查。', version: 'a'.repeat(64) }];
  task.context = [{ id: 'context', kind: 'RULE', content: '当前项目的固定上下文与来源。', source: 'theme-ui-fixture' }];
  await page.route('**/api/state', route => route.fulfill({ json: {
    projects: [{ id: task.projectId, name: '主题验证项目', path: task.execution.sourcePath, head: 'a'.repeat(40), branch: 'main', fileCount: 12, modules: ['src', 'tests'] }],
    tasks: [task, { ...task, id: 'running-task', title: '执行中的主题夹具', state: 'RUNNING' }, { ...task, id: 'failed-task', title: '原失败的主题夹具', state: 'FAILED' }],
    routes: [task.routeSnapshot, { ...task.routeSnapshot, id: 'codex', name: 'CLI 示例', engine: 'CODEX' }, { ...task.routeSnapshot, id: 'claude', name: 'CLI 暖色示例', engine: 'CLAUDE' }],
    profiles: [{ id: 'profile', name: '独立审查职责', role: 'reviewer', version: 1, instructions: '核对实际证据与交付条件。', capabilities: ['read'] }],
    memories: ['RULE', 'DECISION', 'EXPERIENCE', 'FACT'].map(kind => ({ id: kind, projectId: task.projectId, kind, content: '项目记忆保持明确来源与清晰文字。', source: 'theme-ui-fixture', status: 'ACTIVE' })),
    executionNodes: [{ id: 'local-node', name: '本机节点', version: 1, enabled: true, deployment: 'LOCAL_PROCESS', state: 'ONLINE' }], repositoryBindings: original.bindings,
    workers: [], agentDefinitions: [], system: { version: 'theme-ui-fixture', storageHealthy: true, modelCapacity: 2, activeModels: 1, capabilities: ['实际能力声明'] },
  } }));
  await page.route('**/api/**/sessions?**', route => route.fulfill({ json: { sessions: [{ id: 'theme-session', projectId: task.projectId, title: '外观验证会话', isOpen: true, version: 1 }], cursor: 1, hasMore: false } }));
  await page.route('**/api/**/messages?**', route => route.fulfill({ json: { messages: [{ id: 'message', sequence: 1, role: 'USER', content: '会话消息中的中文、路径与目标都应清晰可读。', createdAt: '2026-11-11T00:00:00Z' }], cursor: 1, latestCursor: 1, hasMore: false } }));
  await page.route('**/api/**/agent-runs?**', route => route.fulfill({ json: { runs: [{ id: 'theme-run', sequence: 1, title: '主题验证运行', stage: 'IMPLEMENT', role: 'developer', state: 'UNKNOWN', reason: '原运行结果待核对', routeSnapshot: task.routeSnapshot }], cursor: 1, hasMore: false } }));
  await page.route('**/api/**/model-requests?**', route => route.fulfill({ json: { requests: [{ id: 'theme-request', agentRunId: 'theme-run', stage: 'IMPLEMENT', sequence: 1, state: 'SUCCEEDED', engine: 'API', model: 'fixture', outputPreview: '请求输出片段保持原格式。' }], cursor: 1, hasMore: false } }));
  await page.route('**/api/**/file-tool-operations?**', route => route.fulfill({ json: { operations: [{ id: 'theme-operation', tool: 'write_file', path: 'src/中文目录/value.txt', state: 'REJECTED', errorCode: 'FILE_VERSION_CONFLICT', error: '读取后文件已改变，当前内容继续保留。', sourceStatus: 'REQUEST_BOUND', modelRequestId: 'theme-request', toolCallId: 'theme-tool' }], cursor: '', hasMore: false, total: 1 } }));
  await page.route('**/api/**/diff', route => route.fulfill({ json: { diff: 'diff --git a/src/value.txt b/src/value.txt\n--- a/src/value.txt\n+++ b/src/value.txt\n@@ -1 +1 @@\n-原内容保持历史记录\n+新内容保持清晰对比\n 不变的上下文', version: 'a'.repeat(64) } }));
  await page.route('**/api/mcp/servers', route => route.fulfill({ json: [{ id: 'theme-mcp', name: '主题工具示例', transport: 'HTTP', url: 'http://127.0.0.1:9999/mcp', health: 'AVAILABLE', enabled: true, discovery: { tools: [{ name: 'read_note', description: '只读资料与实际参数', authorized: true, inputSchema: { type: 'object' } }] } }] }));
  return original;
}

async function openAppearance(page: Page) {
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '外观', exact: true }).click();
  await expect(page.getByRole('heading', { name: '工作台外观', exact: true })).toBeVisible();
}

async function contrast(page: Page, view: string) {
  const result = await page.evaluate(() => {
    const rgba = (value: string) => {
      const parts = value.match(/[\d.]+/g)?.map(Number) || [0, 0, 0];
      return [parts[0], parts[1], parts[2], parts[3] ?? 1];
    };
    const composite = (front: number[], back: number[]) => [0, 1, 2].map(i => front[i] * front[3] + back[i] * (1 - front[3]));
    const luminance = (color: number[]) => color.slice(0, 3).map(value => { const v = value / 255; return v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
    const ratio = (a: number[], b: number[]) => { const first = luminance(a), second = luminance(b); return (Math.max(first, second) + .05) / (Math.min(first, second) + .05); };
    const background = (element: Element) => {
      const ancestors: Element[] = []; for (let current: Element | null = element; current; current = current.parentElement) ancestors.push(current);
      return ancestors.reverse().reduce((color, current) => composite(rgba(getComputedStyle(current).backgroundColor), color), [255, 255, 255]);
    };
    const failures: { text: string; selector: string; ratio: number; minimum: number }[] = [];
    let measured = 0, minimumRatio = Infinity;
    const inspect = (element: Element, text: string, color: string, minimum: number) => {
      const bg = background(element), actual = ratio(composite(rgba(color), bg), bg); measured++; minimumRatio = Math.min(minimumRatio, actual);
      if (actual + .005 < minimum) failures.push({ text: text.slice(0, 70), selector: element.tagName.toLowerCase() + '.' + element.className, ratio: Number(actual.toFixed(3)), minimum });
    };
    const walker = document.createTreeWalker(document.body, NodeFilter.SHOW_TEXT);
    for (let node = walker.nextNode(); node; node = walker.nextNode()) {
      const text = node.textContent?.trim(), element = node.parentElement;
      if (!text || !element || element.closest('script, style, [aria-hidden=true], [disabled]')) continue;
      const range = document.createRange(); range.selectNodeContents(node);
      if (![...range.getClientRects()].some(rect => rect.width > 0 && rect.height > 0)) continue;
      const style = getComputedStyle(element); if (style.visibility !== 'visible') continue;
      const size = parseFloat(style.fontSize), weight = parseInt(style.fontWeight);
      inspect(element, text, style.color, size >= 24 || size >= 18.6667 && weight >= 700 ? 3 : 4.5);
    }
    for (const input of document.querySelectorAll<HTMLInputElement | HTMLTextAreaElement>('input:not([type=checkbox]):not([type=file]), textarea')) {
      if (input.disabled || !input.getClientRects().length) continue;
      inspect(input, input.value || 'input text', getComputedStyle(input).color, 4.5);
      if (input.placeholder) inspect(input, input.placeholder, getComputedStyle(input, '::placeholder').color, 4.5);
    }
    return { measured, minimumRatio, failures };
  });
  expect(result.measured, view).toBeGreaterThan(15);
  expect(result.failures, `${view} actual text contrast`).toEqual([]);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth), `${view} overflow`).toBe(true);
  return { view, ...result };
}

for (const [id, name, scheme] of themes) for (const width of [1440, 430]) {
  test(`六主题覆盖实际页面与文字对比度 ${name} ${width}px`, async ({ page }, info: TestInfo) => {
    await page.setViewportSize({ width, height: 1000 });
    await page.addInitScript(id => localStorage.setItem('workbench-theme', id), id);
    const source = await fixture(page), audits = [];
    await page.goto('/'); await expect(page.getByRole('heading', { name: '主题验证项目', exact: true })).toBeVisible();
    await expect(page.locator('html')).toHaveAttribute('data-theme', id);
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).colorScheme)).toBe(scheme);
    audits.push(await contrast(page, 'home'));
    await page.getByRole('button', { name: '协作会话', exact: true }).click(); await expect(page.getByText('会话消息中的中文、路径与目标都应清晰可读。', { exact: true })).toBeVisible(); audits.push(await contrast(page, 'sessions'));
    await page.getByRole('button', { name: '研发任务', exact: true }).click(); audits.push(await contrast(page, 'tasks'));
    await page.getByRole('button', { name: '需要我处理', exact: true }).first().click(); audits.push(await contrast(page, 'inbox'));
    await page.getByRole('button', { name: '上下文与记忆', exact: true }).click(); audits.push(await contrast(page, 'knowledge'));
    await openAppearance(page); await expect(page.getByRole('button', { name, exact: true })).toHaveAttribute('aria-pressed', 'true'); audits.push(await contrast(page, 'appearance'));
    const screenshot = info.outputPath(`theme-${id}-${width === 430 ? 'narrow' : 'desktop'}.png`);
    await page.screenshot({ path: screenshot, fullPage: true });
    await info.attach('appearance', { path: screenshot, contentType: 'image/png' });
    for (const tab of ['运行器与模型', '执行节点与仓库', '职责模板', '动态副 Agent', 'MCP 工具', '资源与运行状态']) {
      await page.getByRole('button', { name: tab, exact: true }).click();
      if (tab === 'MCP 工具') { await expect(page.getByRole('heading', { name: '主题工具示例', exact: true })).toBeVisible(); await page.getByText('read_note', { exact: true }).click(); }
      audits.push(await contrast(page, tab));
    }
    await page.getByRole('button', { name: '研发任务', exact: true }).click();
    await page.getByRole('row').filter({ has: page.getByText(source.task.title, { exact: true }) }).getByRole('button').click();
    audits.push(await contrast(page, 'task-plan'));
    for (const tab of ['验证与审查', '代码差异', 'Agent 运行', '模型请求', '文件操作', 'Worker 执行', '执行上下文', '活动记录']) {
      await page.getByRole('button', { name: tab, exact: tab !== '验证与审查' }).click();
      if (tab === '代码差异') await expect(page.getByText('+新内容保持清晰对比', { exact: true })).toBeVisible();
      if (tab === '模型请求') { await expect(page.getByText('REQ theme-request', { exact: true })).toBeVisible(); await page.getByText('查看脱敏输出片段', { exact: true }).click(); }
      if (tab === '文件操作') await expect(page.getByText('读取后文件已改变，当前内容继续保留。', { exact: true })).toBeVisible();
      audits.push(await contrast(page, tab));
    }
    await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '核心连接', exact: true }).click(); await expect(page.getByRole('dialog')).toBeVisible(); audits.push(await contrast(page, 'connection-dialog'));
    expect(source.errors).toEqual([]);
    await info.attach('actual-theme-view-contrast', { body: Buffer.from(JSON.stringify({ id, width, audits }, null, 2)), contentType: 'application/json' });
  });
}

test('已保存深色在 React 入口到达前应用', async ({ page }) => {
  await fixture(page); await page.emulateMedia({ colorScheme: 'light' });
  await page.addInitScript(() => localStorage.setItem('workbench-theme', 'ink-night-blue'));
  let release!: () => void; const delayed = new Promise<void>(resolve => { release = resolve; });
  await page.route('**/src/main.tsx', async route => { await delayed; await route.continue(); });
  try {
    await page.goto('/', { waitUntil: 'commit' });
    await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night-blue');
    expect(await page.evaluate(() => getComputedStyle(document.documentElement).backgroundColor)).toBe('rgb(23, 34, 56)');
    expect(await page.locator('#root').textContent()).toBe('');
    await expect(page.locator('meta[name=theme-color]')).toHaveAttribute('content', '#172238');
  } finally { release(); }
  await expect(page.getByRole('heading', { name: '主题验证项目', exact: true })).toBeVisible();
});

test('系统变化更新跟随外观，明确选择跨重载与系统变化保持', async ({ page }) => {
  await fixture(page); await page.emulateMedia({ colorScheme: 'dark' }); await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night'); await openAppearance(page);
  await page.emulateMedia({ colorScheme: 'light' }); await expect(page.locator('html')).toHaveAttribute('data-theme', 'celadon');
  await page.getByRole('button', { name: '纸墨', exact: true }).click(); await page.emulateMedia({ colorScheme: 'dark' }); await expect(page.locator('html')).toHaveAttribute('data-theme', 'paper-ink');
  await page.reload(); await expect(page.locator('html')).toHaveAttribute('data-theme', 'paper-ink'); await openAppearance(page);
  await page.getByRole('button', { name: '跟随系统' }).click(); await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night');
  await expect(page.getByRole('status')).toContainText('跟随系统深色 · 墨夜');
});

test('同源标签接收最新偏好，清空回到系统且忽略会话存储', async ({ page, context }) => {
  await fixture(page); await page.goto('/'); await openAppearance(page);
  const second = await context.newPage(); await fixture(second); await second.goto('/'); await expect(second.locator('html')).toHaveAttribute('data-theme', 'celadon');
  await page.getByRole('button', { name: '墨夜蓝', exact: true }).click(); await expect(second.locator('html')).toHaveAttribute('data-theme', 'ink-night-blue');
  await second.evaluate(() => { localStorage.setItem('workbench-theme', 'paper-ink'); localStorage.setItem('workbench-theme', 'classic-warm'); sessionStorage.setItem('workbench-theme', 'ink-night'); });
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'classic-warm');
  await second.evaluate(() => localStorage.clear()); await expect(page.locator('html')).toHaveAttribute('data-theme-choice', 'system'); await expect(page.locator('html')).toHaveAttribute('data-theme', 'celadon');
});

for (const invalid of ['obsolete-theme', '<style>body{display:none}</style>']) test(`无效存储值回退系统且不进入页面 ${invalid}`, async ({ page }) => {
  await fixture(page); await page.emulateMedia({ colorScheme: 'dark' }); await page.addInitScript(invalid => localStorage.setItem('workbench-theme', invalid), invalid); await page.goto('/');
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night'); await expect(page.getByRole('heading', { name: '主题验证项目', exact: true })).toBeVisible();
  await openAppearance(page); await expect(page.getByRole('button', { name: '跟随系统' })).toHaveAttribute('aria-pressed', 'true');
});

for (const kind of ['localStorage', 'sessionStorage', 'both']) test(`存储被阻止仍能切换主题、会话与内存连接 ${kind}`, async ({ page }) => {
  const source = await fixture(page);
  await page.addInitScript(kind => { for (const name of ['localStorage', 'sessionStorage']) if (kind === 'both' || kind === name) Object.defineProperty(window, name, { get() { throw new DOMException('Browser storage blocked by the fixture', 'SecurityError'); } }); }, kind);
  await page.goto('/'); await expect(page.getByRole('heading', { name: '主题验证项目', exact: true })).toBeVisible(); await openAppearance(page);
  await page.getByRole('button', { name: '墨夜蓝', exact: true }).click(); await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night-blue');
  if (kind !== 'sessionStorage') await expect(page.getByRole('status').filter({ hasText: '当前外观：' })).toContainText('本次窗口仍可切换主题');
  await page.getByRole('button', { name: '协作会话', exact: true }).click(); await expect(page.getByText('会话消息中的中文、路径与目标都应清晰可读。', { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '资源与设置', exact: true }).click(); await page.getByRole('button', { name: '核心连接', exact: true }).click();
  await page.getByLabel('访问令牌', { exact: true }).fill('memory-connection-fixture');
  const request = page.waitForRequest(request => new URL(request.url()).pathname === '/api/state' && request.headers().authorization === 'Bearer memory-connection-fixture');
  await page.getByRole('button', { name: '连接并验证', exact: true }).click(); await request; await expect(page.getByRole('dialog')).toBeHidden();
  expect(source.errors).toEqual([]);
});

test('键盘选择、焦点可见与未连接核心时的外观可用', async ({ page }) => {
  const errors: string[] = []; page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', route => route.fulfill({ status: 503, json: { message: '本机核心当前不可用' } }));
  await page.goto('/'); await openAppearance(page);
  const white = page.getByRole('button', { name: '纯白', exact: true }); await white.focus(); await page.keyboard.press('Space'); await expect(white).toHaveAttribute('aria-pressed', 'true');
  expect(await white.evaluate(element => getComputedStyle(element).outlineStyle)).toBe('solid');
  expect(await white.evaluate(element => parseFloat(getComputedStyle(element).outlineWidth))).toBeGreaterThanOrEqual(3);
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'pure-white'); expect(errors).toEqual([]);
});

test('存储配额失败后的页面恢复保留本窗口选择与未保存状态', async ({ page }) => {
  const source = await fixture(page);
  await page.addInitScript(() => {
    localStorage.setItem('workbench-theme', 'paper-ink');
    Storage.prototype.setItem = () => { throw new DOMException('Browser storage quota fixture', 'QuotaExceededError'); };
  });
  await page.goto('/'); await expect(page.locator('html')).toHaveAttribute('data-theme', 'paper-ink'); await openAppearance(page);
  await page.getByRole('button', { name: '墨夜蓝', exact: true }).click();
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night-blue');
  await expect(page.getByRole('status')).toContainText('本次窗口仍可切换主题');
  await page.evaluate(() => window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true })));
  await expect(page.locator('html')).toHaveAttribute('data-theme', 'ink-night-blue');
  await expect(page.getByRole('status')).toContainText('本次窗口仍可切换主题');
  expect(await page.evaluate(() => localStorage.getItem('workbench-theme'))).toBe('paper-ink');
  expect(source.errors).toEqual([]);
});

test('六主题侧栏键盘焦点与实际背景至少达到 3:1', async ({ page }, info) => {
  const source = await fixture(page); await page.goto('/'); await openAppearance(page);
  const audits = [];
  for (const [id, name] of themes) {
    await page.getByRole('button', { name, exact: true }).click();
    const target = page.getByRole('button', { name: '项目总览', exact: true });
    await target.focus(); await page.keyboard.press('ArrowRight');
    await expect.poll(() => target.evaluate(element => { getComputedStyle(element).outlineColor; return element.getAnimations().filter(animation => animation.playState === 'running').length; })).toBe(0);
    const audit = await target.evaluate(element => {
      const rgba = (value: string) => { const parts = value.match(/[\d.]+/g)?.map(Number) || [0, 0, 0]; return [parts[0], parts[1], parts[2], parts[3] ?? 1]; };
      const composite = (front: number[], back: number[]) => [0, 1, 2].map(index => front[index] * front[3] + back[index] * (1 - front[3]));
      const ancestors: Element[] = [];
      for (let current = element.parentElement; current; current = current.parentElement) ancestors.push(current);
      const background = ancestors.reverse().reduce((color, current) => composite(rgba(getComputedStyle(current).backgroundColor), color), [255, 255, 255]);
      const style = getComputedStyle(element), foreground = composite(rgba(style.outlineColor), background);
      const luminance = (color: number[]) => color.map(value => { const v = value / 255; return v <= .04045 ? v / 12.92 : ((v + .055) / 1.055) ** 2.4; }).reduce((sum, value, index) => sum + value * [.2126, .7152, .0722][index], 0);
      const a = luminance(foreground), b = luminance(background);
      return { focusVisible: element.matches(':focus-visible'), outlineStyle: style.outlineStyle, outlineWidth: parseFloat(style.outlineWidth), outlineColor: style.outlineColor, background,
        ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) };
    });
    audits.push({ id, ...audit });
    await page.screenshot({ path: info.outputPath(`sidebar-focus-${id}.png`) });
  }
  await info.attach('actual-sidebar-focus-contrast', { body: Buffer.from(JSON.stringify(audits, null, 2)), contentType: 'application/json' });
  expect(audits.filter(item => !item.focusVisible || item.outlineStyle !== 'solid' || item.outlineWidth < 3 || item.ratio < 3)).toEqual([]);
  expect(source.errors).toEqual([]);
});
