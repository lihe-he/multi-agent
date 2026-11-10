import { expect, test, type Page } from '@playwright/test';
import { openWorkspaceTask, workspaceFixture } from './fixtures/workspaces';

const note = '原进程与效果已检查 <img src=x onerror="window.writerInjected=1"> <script>literal</script>';
const scope = `C:\\data\\${'中文 long-workspace-'.repeat(10)}\\work`;

async function writeFixture(page: Page) {
  const original = await workspaceFixture(page);
  const leases: any[] = Array.from({ length: 65 }, (_, index) => {
    const sequence = index + 1, state = sequence === 65 ? 'HELD' : sequence % 2 === 0 ? 'UNKNOWN' : 'RELEASED';
    return { id: `write-${sequence}`, sequence, taskId: original.task.id, projectId: original.task.projectId, taskExecutionId: `root-${sequence}`, dispatchActionId: `action-${sequence}`, attempt: sequence,
      scopePath: scope, workerId: 'original-worker', executionNodeId: 'local-node', workerEpoch: sequence, executorPid: 5200, processStartedAt: '2026-11-10T00:00:00Z',
      ownerCoreSessionId: 'original-core-session', ownerCorePid: 4000, ownerCoreStartedAt: '2026-11-10T00:00:00Z', authorizationVersion: 3, workspaceSelectionVersion: 2, strategy: 'WORKTREE',
      baseline: 'a'.repeat(40), state, protectionReleased: state !== 'HELD', createdAt: '2026-11-10T00:00:00Z', grantedAt: '2026-11-10T00:01:00Z',
      ...(state !== 'HELD' ? { releasedAt: '2026-11-10T00:02:00Z', release: { receiptExecutorPid: 5400, receiptProcessStartedAt: '2026-11-10T00:00:00Z', observedAt: '2026-11-10T00:02:00Z' } } : {}),
      grant: { lockPath: `${scope}\\..\\writer.lock`, fileKey: `WINDOWS:volume:${'1'.repeat(32)}`, lockValid: state === 'HELD' },
      ...(state === 'UNKNOWN' ? { reason: '原请求未取得完整回执', reconciliation: { actor: 'USER', note, createdAt: '2026-11-10T00:02:00Z' } } : {}) };
  });
  original.task.workspaceWriteLeaseId = 'write-65'; original.task.workspaceWriteHistoryPresent = true;
  const queries: string[] = [], gates: { all?: Promise<void> } = {};
  await page.route('**/api/tasks/workspace-task/workspace-write-leases?**', async route => {
    const url = new URL(route.request().url()), before = Number(url.searchParams.get('before') || 0), state = url.searchParams.get('state'); queries.push(url.search);
    const matching = leases.filter(lease => (!before || lease.sequence < before) && (!state || lease.state === state)).sort((left, right) => right.sequence - left.sequence);
    const records = matching.slice(0, 30), response = { leases: records, cursor: records.at(-1)?.sequence || 0, hasMore: matching.length > records.length };
    if (!state && gates.all) await gates.all;
    await route.fulfill({ json: response });
  });
  return { ...original, leases, queries, gates };
}

async function openWrites(page: Page) {
  await openWorkspaceTask(page); await page.getByRole('button', { name: 'Worker 执行', exact: true }).click();
  return page.getByRole('region', { name: '现场写入保护历史' });
}

for (const width of [1440, 430]) {
  test(`根写入保护保留未知核对、原身份与纯文本回执 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await writeFixture(page), panel = await openWrites(page);
    await expect(panel.locator('.worker-lease')).toHaveCount(30);
    const held = panel.locator('.worker-lease').first(), checked = panel.locator('.worker-lease').nth(1);
    await expect(held.getByText('原根持有保护', { exact: true })).toBeVisible(); await expect(held.getByText('原归属继续保留，尚无释放回执', { exact: true })).toBeVisible();
    await expect(held.getByText('root-65', { exact: false })).toBeVisible(); await expect(held.getByText('write-65', { exact: true })).toBeVisible();
    await expect(held.getByText('PID 5200', { exact: false })).toBeVisible(); await expect(held.getByText('PID 4000', { exact: false })).toBeVisible();
    await expect(checked.getByText('未知记录已核对', { exact: true })).toBeVisible(); await expect(checked.getByText('原保护已释放', { exact: true })).toBeVisible();
    await checked.getByText('查看原回执与核对依据', { exact: true }).click(); await expect(checked.getByText(note, { exact: false })).toBeVisible();
    expect(await panel.locator('script,img').count()).toBe(0); expect(await page.evaluate(() => (window as any).writerInjected)).toBeUndefined();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 430) await page.setViewportSize({ width, height: 1500 });
    await checked.scrollIntoViewIfNeeded(); await checked.evaluate(element => window.scrollBy(0, element.getBoundingClientRect().top - 90));
    await page.screenshot({ path: `qa/workspace-writes-${width === 430 ? 'narrow' : 'desktop'}.png` }); expect(fixture.errors).toEqual([]);
  });

  test(`写入归属稳定游标覆盖 65 条记录且轮询保留历史位置 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await writeFixture(page), panel = await openWrites(page);
    await expect(panel.locator('.worker-lease')).toHaveCount(30); await panel.getByRole('button', { name: '加载更早写入记录' }).click();
    await expect(panel.locator('.worker-lease').first().getByRole('heading', { name: '第 35 次根执行', exact: true })).toBeVisible(); await expect(panel.locator('.worker-lease')).toHaveCount(30);
    fixture.leases.at(-1).state = 'RELEASED'; fixture.leases.at(-1).protectionReleased = true;
    fixture.leases.push({ ...fixture.leases.at(-1), id: 'write-66', sequence: 66, taskExecutionId: 'root-66', dispatchActionId: 'action-66', attempt: 66, state: 'HELD', protectionReleased: false });
    const queryCount = fixture.queries.length; await expect.poll(() => fixture.queries.length).toBeGreaterThan(queryCount); expect(fixture.queries.at(-1)).toContain('before=36');
    await expect(panel.getByRole('heading', { name: '第 66 次根执行', exact: true })).toHaveCount(0);
    await panel.getByRole('button', { name: '加载更早写入记录' }).click(); await expect(panel.locator('.worker-lease')).toHaveCount(5);
    await expect(panel.locator('.worker-lease').last().getByText('write-1', { exact: true })).toBeVisible(); await expect(panel.getByRole('button', { name: '加载更早写入记录' })).toHaveCount(0);
    await panel.getByRole('button', { name: '返回最新写入记录' }).click(); await expect(panel.getByRole('heading', { name: '第 66 次根执行', exact: true })).toBeVisible();
    await panel.getByLabel('写入保护状态').selectOption('UNKNOWN'); await expect(panel.locator('.worker-lease')).toHaveCount(30); expect(fixture.queries.at(-1)).toContain('state=UNKNOWN');
    await expect(panel.getByRole('heading', { name: '第 64 次根执行', exact: true })).toBeVisible(); await panel.getByRole('button', { name: '加载更早写入记录' }).click();
    await expect(panel.locator('.worker-lease')).toHaveCount(2); await expect(panel.locator('.worker-lease').last().getByText('write-2', { exact: true })).toBeVisible(); expect(fixture.errors).toEqual([]);
  });

  test(`迟到旧轮询不能覆盖新的写入状态筛选 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await writeFixture(page), panel = await openWrites(page);
    await expect(panel.locator('.worker-lease')).toHaveCount(30); let release!: () => void; fixture.gates.all = new Promise(resolve => { release = resolve; });
    const before = fixture.queries.length; await expect.poll(() => fixture.queries.length).toBeGreaterThan(before);
    await panel.getByLabel('写入保护状态').selectOption('HELD'); await expect(panel.locator('.worker-lease')).toHaveCount(1); await expect(panel.getByText('write-65', { exact: true })).toBeVisible();
    release(); fixture.gates.all = undefined; await page.waitForTimeout(200);
    await expect(panel.getByLabel('写入保护状态')).toHaveValue('HELD'); await expect(panel.locator('.worker-lease')).toHaveCount(1); await expect(panel.getByText('write-64', { exact: true })).toHaveCount(0);
    expect(fixture.errors).toEqual([]);
  });
}

test('同一根写入编号贯穿模型请求、Agent 运行与 MCP 来源', async ({ page }) => {
  const fixture = await writeFixture(page), source = 'original-write-source';
  fixture.task.mcpAuthorization = { version: 1, bindings: [{ actionKey: 'original-action', label: '原固定动作', name: 'fixture-tool' }] };
  await page.route('**/api/tasks/workspace-task/mcp-operations', route => route.fulfill({ json: [{ actionKey: 'original-action', state: 'ACKNOWLEDGED', agentRunId: 'run-original', modelRequestId: 'request-original', toolCallId: 'call-original', workspaceWriteLeaseId: source }] }));
  await page.route('**/api/tasks/workspace-task/agent-runs?**', route => route.fulfill({ json: { runs: [{ id: 'run-original', taskId: fixture.task.id, title: '原模型阶段', stage: 'implement', attempt: 1, state: 'SUCCEEDED', calls: 1, taskExecutionId: 'root-original', dispatchActionId: 'action-original', workspaceWriteLeaseId: source }], cursor: 1, hasMore: false } }));
  await page.route('**/api/tasks/workspace-task/model-requests?**', route => route.fulfill({ json: { requests: [{ id: 'request-original', taskId: fixture.task.id, agentRunId: 'run-original', stage: 'implement', sequence: 1, state: 'SUCCEEDED', taskExecutionId: 'root-original', dispatchActionId: 'action-original', workspaceWriteLeaseId: source }], cursor: 1, hasMore: false } }));
  await openWorkspaceTask(page); await expect(page.locator('.mcp-source').getByText(source, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: 'Agent 运行', exact: true }).click(); await expect(page.getByRole('region', { name: 'Agent 运行记录' }).getByText(source, { exact: true })).toBeVisible();
  await page.getByRole('button', { name: '模型请求', exact: true }).click(); await expect(page.getByRole('region', { name: '模型请求追踪' }).getByText(source, { exact: true })).toBeVisible(); expect(fixture.errors).toEqual([]);
});
