import { expect, test, type Page } from '@playwright/test';
import { openWorkspaceTask, workspaceFixture } from './fixtures/workspaces';

const id = (sequence: number) => `00000000-0000-0000-0000-${String(sequence).padStart(12, '0')}`;
const literal = '读取后用户已修改文件 <img src=x onerror="window.fileReceiptInjected=1"> <script>literal</script>';
const longPath = `src/${'中文文件路径很长-'.repeat(22)}/value.txt`;
const version = (sha: string) => ({ exists: true, sha256: sha.repeat(64), bytes: 123, fileKey: `WINDOWS:original-file:${'1'.repeat(32)}` });

async function fileFixture(page: Page) {
  const original = await workspaceFixture(page), queries: string[] = [], requestQueries: string[] = [];
  const records: any[] = Array.from({ length: 65 }, (_, index) => {
    const sequence = index + 1, state = sequence === 63 ? 'UNKNOWN' : sequence % 2 === 0 ? 'REJECTED' : 'CONFIRMED';
    return { sequence, id: id(sequence), tool: sequence === 63 ? 'delete_file' : 'write_file', path: sequence === 65 ? longPath : `src/file-${sequence}.txt`, state,
      createdAt: '2026-11-11T00:00:00Z', modelRequestId: `request-${sequence}`, toolCallId: `tool-${sequence}`, toolOrdinal: 1,
      expectedReadVersion: `file-read-${id(sequence + 100)}`, sourceStatus: sequence === 1 ? 'LEGACY_SOURCE_UNAVAILABLE' : 'REQUEST_BOUND',
      ...(sequence === 64 ? { errorCode: 'FILE_VERSION_CONFLICT', error: literal } : {}), ...(sequence === 63 ? { failedReceiptPhase: 'CONFIRM_METADATA' } : {}),
      fileReceipt: state === 'REJECTED' ? undefined : { beforeVersion: version('a'), fileVersion: sequence === 63 ? { exists: false } : version('b'), readVersion: `file-read-${id(sequence + 200)}`, sourceReadOperationId: id(sequence + 300) },
      source: { stage: 'IMPLEMENT', agentRunId: 'original-run', taskExecutionId: 'original-root', workspaceWriteLeaseId: 'original-write', workerId: sequence === 1 ? 'unverified-worker-must-not-show' : 'original-worker', workerEpoch: 7, executorPid: 9123, processStartedAt: '2026-11-11T00:00:00Z' } };
  });
  const gates: { all?: Promise<void>; malformed?: unknown } = {};
  await page.route('**/api/tasks/workspace-task/file-tool-operations?**', async route => {
    const url = new URL(route.request().url()); queries.push(url.search);
    const cursor = url.searchParams.get('before') || '', before = cursor ? Number(cursor.split(':')[0]) - 3_000_000_000 : 0, state = url.searchParams.get('state');
    const matching = records.filter(record => (!before || record.sequence < before) && (!state || record.state === state)).sort((left, right) => right.sequence - left.sequence);
    const operations = matching.slice(0, 30), last = operations.at(-1);
    const response = { operations, cursor: last ? `${3_000_000_000 + last.sequence}:1:${last.id}` : cursor, hasMore: matching.length > operations.length,
      total: records.length, unattributedCount: 1, journalSources: [{ workspace: 'C:\\data\\original\\work', journalPresent: true, operationCount: records.length }, { workspace: 'C:\\data\\missing\\work', journalPresent: false, operationCount: 0 }] };
    if (!state && gates.all) await gates.all;
    await route.fulfill({ json: gates.malformed === undefined ? response : gates.malformed });
  });
  await page.route('**/api/tasks/*/model-requests?**', route => {
    const url = new URL(route.request().url()), query = url.searchParams.get('q') || ''; requestQueries.push(url.pathname + url.search);
    return route.fulfill({ json: { requests: query ? [{ id: query, agentRunId: 'original-run', stage: 'IMPLEMENT', sequence: 1, state: 'SUCCEEDED', engine: 'API', model: 'fixture' }] : [], cursor: 1, hasMore: false } });
  });
  return { ...original, records, queries, requestQueries, gates };
}

async function openFiles(page: Page) {
  await openWorkspaceTask(page); await page.getByRole('button', { name: '文件操作', exact: true }).click();
  return page.getByRole('region', { name: '文件工具回执' });
}

for (const width of [1440, 430]) {
  test(`文件冲突与实际版本来源按纯文本展示 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await fileFixture(page), panel = await openFiles(page);
    await expect(panel.locator('.file-tool-operation')).toHaveCount(30); const current = panel.locator('.file-tool-operation').first(), conflict = panel.locator('.file-tool-operation').nth(1);
    await expect(current.getByRole('heading', { name: `写入文件 · ${longPath}` })).toBeVisible(); await expect(current.getByText('aaaaaaaaaaaa · 123 字节', { exact: true })).toBeVisible();
    await expect(current.getByText('bbbbbbbbbbbb · 123 字节', { exact: true })).toBeVisible(); await current.getByText('查看读回执与执行来源', { exact: true }).click();
    await expect(current.getByText('original-worker · 代次 7', { exact: false })).toBeVisible(); await expect(current.getByText('PID 9123', { exact: false })).toBeVisible();
    await expect(current.getByText(`file-read-${id(165)}`, { exact: true })).toBeVisible(); await expect(current.getByText(`file-read-${id(265)}`, { exact: true })).toBeVisible();
    await expect(conflict.getByText('文件版本冲突', { exact: true })).toBeVisible(); await expect(conflict.getByText(literal, { exact: true })).toBeVisible();
    await expect(panel.getByText('1 份现场当前没有文件工具清单', { exact: false })).toBeVisible();
    expect(await panel.locator('script,img').count()).toBe(0); expect(await page.evaluate(() => (window as any).fileReceiptInjected)).toBeUndefined();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    if (width === 430) await page.setViewportSize({ width, height: 1500 });
    await current.scrollIntoViewIfNeeded(); await current.evaluate(element => window.scrollBy(0, element.getBoundingClientRect().top - 75));
    await page.screenshot({ path: `qa/file-tool-operations-${width === 430 ? 'narrow' : 'desktop'}.png` }); expect(fixture.errors).toEqual([]);
  });

  test(`文件回执稳定分页、状态变化与新增记录 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await fileFixture(page), panel = await openFiles(page);
    await expect(panel.locator('.file-tool-operation')).toHaveCount(30); await panel.getByRole('button', { name: '加载更多历史回执', exact: true }).click();
    await expect(panel.locator('.file-tool-operation').first().getByRole('heading', { name: '写入文件 · src/file-35.txt', exact: true })).toBeVisible();
    fixture.records[34].state = 'CONFIRMED'; fixture.records[34].completedAt = '2100-01-01T00:00:00Z';
    fixture.records.push({ ...fixture.records.at(-1), sequence: 66, id: id(66), path: 'src/new-66.txt' });
    const count = fixture.queries.length; await expect.poll(() => fixture.queries.length).toBeGreaterThan(count); expect(fixture.queries.at(-1)).toContain('before=3000000036');
    await expect(panel.getByRole('heading', { name: '写入文件 · src/new-66.txt', exact: true })).toHaveCount(0);
    await panel.getByRole('button', { name: '加载更多历史回执', exact: true }).click(); await expect(panel.locator('.file-tool-operation')).toHaveCount(5);
    const legacy = panel.locator('.file-tool-operation').last(); await legacy.getByText('查看读回执与执行来源', { exact: true }).click();
    await expect(legacy.getByText('原请求来源尚未核验', { exact: true })).toBeVisible(); await expect(legacy.getByText('unverified-worker-must-not-show', { exact: false })).toHaveCount(0);
    await expect(legacy.getByRole('button', { name: '查看对应模型请求', exact: true })).toHaveCount(0);
    await panel.getByRole('button', { name: '返回最新文件回执', exact: true }).click(); await expect(panel.getByRole('heading', { name: '写入文件 · src/new-66.txt', exact: true })).toBeVisible();
    await panel.getByLabel('文件工具状态').selectOption('UNKNOWN'); await expect(panel.locator('.file-tool-operation')).toHaveCount(1);
    await expect(panel.getByRole('heading', { name: '删除文件 · src/file-63.txt', exact: true })).toBeVisible(); expect(fixture.queries.at(-1)).toContain('before=&state=UNKNOWN');
    expect(fixture.errors).toEqual([]);
  });

  test(`迟到文件轮询不能覆盖新的筛选结果 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await fileFixture(page), panel = await openFiles(page);
    await expect(panel.locator('.file-tool-operation')).toHaveCount(30); let release!: () => void; fixture.gates.all = new Promise(resolve => { release = resolve; });
    const count = fixture.queries.length; await expect.poll(() => fixture.queries.length).toBeGreaterThan(count);
    try {
      await panel.getByLabel('文件工具状态').selectOption('UNKNOWN'); await expect(panel.locator('.file-tool-operation')).toHaveCount(1);
      release(); fixture.gates.all = undefined; await page.waitForTimeout(200);
      await expect(panel.getByLabel('文件工具状态')).toHaveValue('UNKNOWN'); await expect(panel.locator('.file-tool-operation')).toHaveCount(1);
      await expect(panel.getByRole('heading', { name: '删除文件 · src/file-63.txt', exact: true })).toBeVisible(); expect(fixture.errors).toEqual([]);
    } finally { release(); fixture.gates.all = undefined; }
  });
}

test('从文件回执打开并切换对应的原模型请求', async ({ page }) => {
  const fixture = await fileFixture(page), second = { ...fixture.task, id: 'second-file-task', title: '另一个文件操作任务' }; fixture.tasks.push(second);
  await page.route('**/api/tasks/second-file-task', route => route.fulfill({ json: second }));
  const panel = await openFiles(page); await expect(panel.locator('.file-tool-operation')).toHaveCount(30);
  await panel.locator('.file-tool-operation').first().getByRole('button', { name: '查看对应模型请求', exact: true }).click();
  await expect(page.getByLabel('检索请求记录')).toHaveValue('request-65'); await expect(page.getByRole('region', { name: '模型请求追踪' }).getByText('REQ request-65', { exact: true })).toBeVisible();
  expect(fixture.requestQueries.at(-1)).toContain('q=request-65');
  await page.getByRole('button', { name: '文件操作', exact: true }).click(); await expect(panel.locator('.file-tool-operation')).toHaveCount(30);
  await panel.locator('.file-tool-operation').nth(1).getByRole('button', { name: '查看对应模型请求', exact: true }).click();
  await expect(page.getByLabel('检索请求记录')).toHaveValue('request-64'); await expect(page.getByRole('region', { name: '模型请求追踪' }).getByText('REQ request-64', { exact: true })).toBeVisible();
  expect(fixture.requestQueries.at(-1)).toContain('q=request-64');
  await page.getByRole('button', { name: '全部任务', exact: true }).click(); await page.getByRole('button', { name: second.title }).click();
  await page.getByRole('button', { name: '模型请求', exact: true }).click(); await expect(page.getByLabel('检索请求记录')).toHaveValue('');
  await expect.poll(() => fixture.requestQueries.at(-1)).toContain('/second-file-task/model-requests?');
  expect(fixture.requestQueries.filter(query => query.includes('/second-file-task/')).every(query => !query.includes('q=request-'))).toBe(true); expect(fixture.errors).toEqual([]);
});

for (const malformed of ['page', 'cursor', 'source']) {
  test(`无效文件回执响应保留读取错误 ${malformed}`, async ({ page }) => {
    const fixture = await fileFixture(page);
    fixture.gates.malformed = malformed === 'page' ? {} : { operations: [{ ...fixture.records.at(-1), ...(malformed === 'source' ? { source: { executorPid: { invented: true } } } : {}) }], cursor: malformed === 'cursor' ? 'bad-cursor' : `3000000065:1:${id(65)}`, hasMore: false };
    const panel = await openFiles(page); await expect(panel.getByRole('alert')).toHaveText('文件工具回执响应格式无效'); await expect(panel.locator('.file-tool-operation')).toHaveCount(0); expect(fixture.errors).toEqual([]);
  });
}
