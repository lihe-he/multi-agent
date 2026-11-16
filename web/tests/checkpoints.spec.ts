import { expect, test, type Page } from '@playwright/test';
import { openWorkspaceTask, workspaceFixture } from './fixtures/workspaces';

const version = 'a'.repeat(64), literal = '实现前 <script>literal</script>';
const longPath = `src/${'长目录 和空格_'.repeat(40)}/组件.tsx`;
const record = (sequence: number): any => ({ id: `checkpoint-${sequence}`, sequence, createdAt: '2026-11-15T00:00:00Z', version, head: '1'.repeat(40),
  status: sequence === 33 ? 'PARTIAL' : 'READY', fileCount: 14, excludedCount: sequence === 33 ? 1 : 0, limitedCount: sequence === 33 ? 1 : 0,
  metadata: { kind: 'STAGE_END', stage: 'implement', label: sequence === 34 ? literal : `保存边界 ${sequence}` } });

async function fixture(page: Page) {
  const original = await workspaceFixture(page);
  original.task.checkpointProtocolVersion = 1; original.task.workspace = 'C:\data\实际任务 work'; original.task.version = version; original.task.latestCheckpointId = 'checkpoint-35';
  const settings: any = { records: Array.from({ length: 35 }, (_, index) => record(35 - index)), undoAllowed: true, changes: [{ path: longPath, action: 'MODIFY', bytes: 128 }, { path: '新增\t文件\n.txt', action: 'DELETE', bytes: 0 }],
    conflicts: [], indexChanges: true, canRestore: true };
  const requests: any[] = [], gates = new Map<string, Promise<void>>();
  const respond = async (route: any, key: string, body: any, status = 200) => {
    const captured = structuredClone(body);
    if (gates.has(key)) await gates.get(key);
    try { await route.fulfill({ status, json: captured }); } catch { /* Superseded requests can be aborted by task changes. */ }
  };
  await page.route('**/api/tasks/*/checkpoints?**', async route => {
    const url = new URL(route.request().url()), taskId = url.pathname.split('/')[3], before = Number(url.searchParams.get('before') || 0);
    requests.push({ method: 'GET', taskId, before });
    const matching = settings.records.filter((item: any) => !before || item.sequence < before), selected = matching.slice(0, 30);
    const result = { checkpoints: selected, cursor: selected.at(-1)?.sequence || before, hasMore: matching.length > selected.length,
      undoAllowed: settings.undoAllowed, blockedReason: settings.blockedReason, sourceCheckpointId: original.task.latestCheckpointId };
    return respond(route, `page:${taskId}:${before}`, settings.malformedPage ? settings.malformedPage(result) : result);
  });
  await page.route('**/api/tasks/*/checkpoints', async route => {
    const taskId = new URL(route.request().url()).pathname.split('/')[3]; requests.push({ method: 'SAVE', taskId, body: route.request().postDataJSON() });
    const saved = record(settings.records[0].sequence + 1); settings.records.unshift(saved); original.task.latestCheckpointId = saved.id;
    return respond(route, `save:${taskId}`, saved);
  });
  await page.route('**/api/tasks/*/checkpoint-restores**', async route => {
    const path = new URL(route.request().url()).pathname, taskId = path.split('/')[3], body = route.request().postDataJSON();
    const preview = path.endsWith('/preview'); requests.push({ method: preview ? 'PREVIEW' : 'RESTORE', taskId, body });
    if (!preview) {
      if (settings.restoreError) return respond(route, `restore:${taskId}`, { message: settings.restoreError }, 409);
      original.task.state = 'QUEUED'; return respond(route, `restore:${taskId}`, original.task);
    }
    const result = { id: `preview-${taskId}`, taskId, targetCheckpointId: body.checkpointId, sourceCheckpointId: original.task.latestCheckpointId,
      createdAt: '2026-11-15T00:01:00Z', changes: settings.changes, conflicts: settings.conflicts, indexChanges: settings.indexChanges, canRestore: settings.canRestore };
    return respond(route, `preview:${taskId}`, settings.malformedPreview ? settings.malformedPreview(result) : result);
  });
  return { ...original, settings, requests, checkpointGates: gates };
}
async function open(page: Page) {
  await openWorkspaceTask(page); await page.getByRole('button', { name: '检查点与撤销', exact: true }).click();
  return page.getByRole('region', { name: '检查点与撤销', exact: true });
}
async function inspect(panel: any, label = literal) {
  await panel.getByRole('button', { name: new RegExp(label.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')) }).click();
  await panel.getByRole('button', { name: '预览撤销', exact: true }).click();
}

for (const width of [1440, 430]) {
  test(`撤销失效成果保留原成功回执并要求新现场验证 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page);
    source.task.review = { status: 'NOT_RUN' }; source.task.invalidatedEvidence = ['old-evidence'];
    source.task.evidence = [{ id: 'old-evidence', command: 'node old-check.mjs', kind: 'VERIFICATION', version: 'b'.repeat(64), status: 'PASSED', exitCode: 0, output: 'original successful receipt', createdAt: '2026-11-15T00:00:00Z' },
      { id: 'kept-evidence', command: 'node baseline.mjs', kind: 'VERIFICATION', version, status: 'PASSED', exitCode: 0, output: 'kept baseline receipt', createdAt: '2026-11-15T00:00:00Z' }];
    await openWorkspaceTask(page); await page.getByRole('button', { name: '验证与审查', exact: false }).click();
    const old = page.locator('.evidence-card').filter({ has: page.getByRole('heading', { name: 'node old-check.mjs', exact: true }) });
    await expect(old.locator('.evidence-invalidated')).toHaveText('此成果已随撤销失效，原结果保留供历史核对。当前现场需要重新验证。');
    await old.getByRole('button').click(); await expect(old.locator('.evidence-output')).toHaveText('original successful receipt');
    await expect(page.locator('.evidence-invalidated')).toHaveCount(1); await expect(page.locator('.review-banner')).toContainText('尚未执行');
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); expect(source.errors).toEqual([]);
  });

  test(`检查点选择、字面预览和取消确认保留原请求 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page), panel = await open(page);
    await inspect(panel); await expect(panel.locator('.checkpoint-paths li')).toHaveCount(2); await expect(panel.locator('.checkpoint-paths code').first()).toHaveText(longPath);
    await expect(panel.getByRole('heading', { name: literal, exact: true })).toBeVisible(); expect(await panel.locator('script,img').count()).toBe(0);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const confirm = panel.getByRole('button', { name: '确认撤销到此检查点', exact: true }); page.once('dialog', dialog => dialog.dismiss()); await confirm.click();
    expect(source.requests.filter(item => item.method === 'RESTORE')).toEqual([]); await expect(confirm).toBeEnabled();
    page.once('dialog', dialog => { expect(dialog.message()).toContain('重新验证'); return dialog.accept(); }); await confirm.click();
    await expect.poll(() => source.requests.filter(item => item.method === 'RESTORE').length).toBe(1); expect(source.requests.find(item => item.method === 'RESTORE').body).toEqual({ previewId: `preview-${source.task.id}` });
    await expect(panel.locator('.checkpoint-plan')).toHaveCount(0); await expect(panel.getByRole('button', { name: '保存当前检查点' })).toBeDisabled(); expect(source.errors).toEqual([]);
  });

  test(`受影响后续修改与部分归档阻止确认 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page); source.settings.conflicts = [{ path: longPath, reason: '有后续修改 <img src=x>，保留当前内容。' }]; source.settings.canRestore = false;
    const panel = await open(page); await inspect(panel, '保存边界 33'); await expect(panel.locator('.checkpoint-limits')).toHaveText('1 项被排除，1 项超过保存上限。受影响内容不可用时会阻止撤销。');
    await expect(panel.locator('.checkpoint-conflicts')).toContainText(source.settings.conflicts[0].reason); await expect(panel.getByRole('button', { name: '确认撤销到此检查点' })).toBeDisabled();
    expect(source.requests.filter(item => item.method === 'RESTORE')).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); expect(source.errors).toEqual([]);
  });

  test(`确认时真实409清除旧预览并允许重新核对 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page); source.settings.restoreError = '预览之后文件已变化，请重新预览。';
    const panel = await open(page); await inspect(panel); page.once('dialog', dialog => dialog.accept()); await panel.getByRole('button', { name: '确认撤销到此检查点' }).click();
    await expect(panel.getByRole('alert')).toHaveText(source.settings.restoreError); await expect(panel.locator('.checkpoint-plan')).toHaveCount(0);
    source.settings.restoreError = undefined; await panel.getByRole('button', { name: '预览撤销', exact: true }).click(); await expect(panel.locator('.checkpoint-plan')).toHaveCount(1);
    expect(source.requests.filter(item => item.method === 'RESTORE')).toHaveLength(1); expect(source.errors).toEqual([]);
  });

  test(`仅暂存区改动仍提供具体确认且没有文件改动不能排队 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page); source.settings.changes = [];
    const panel = await open(page); await inspect(panel); await expect(panel.getByRole('button', { name: '确认撤销到此检查点' })).toBeEnabled(); await expect(panel).toContainText('本次也会恢复检查点保存的暂存状态。');
    source.settings.indexChanges = false; await panel.getByRole('button', { name: '预览撤销', exact: true }).click(); await expect(panel.getByRole('button', { name: '确认撤销到此检查点' })).toBeDisabled(); expect(source.errors).toEqual([]);
  });

  test(`稳定分页、刷新边界与手动保存 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page), panel = await open(page);
    await expect(panel.locator('.checkpoint-choice')).toHaveCount(30); await inspect(panel); source.settings.records.unshift(record(36)); source.task.latestCheckpointId = 'checkpoint-36';
    await panel.getByRole('button', { name: '读取更早的检查点' }).click(); await expect(panel.locator('.checkpoint-choice')).toHaveCount(35); await expect(panel.locator('.checkpoint-plan')).toHaveCount(0);
    expect(source.requests.filter(item => item.method === 'GET').at(-1)?.before).toBe(6); await panel.getByRole('button', { name: '刷新', exact: true }).click(); await expect(panel.locator('.checkpoint-choice')).toHaveCount(30);
    await expect(panel.locator('.checkpoint-choice').first()).toContainText('保存边界 36'); await panel.getByRole('button', { name: '保存当前检查点' }).click(); await expect(panel.locator('.checkpoint-choice').first()).toContainText('保存边界 37');
    expect(source.requests.filter(item => item.method === 'SAVE')).toEqual([{ method: 'SAVE', taskId: source.task.id, body: {} }]); expect(source.errors).toEqual([]);
  });
}

for (const bad of ['task', 'target', 'source', 'index', 'conflicts', 'duplicate-path', 'inconsistent', 'prototype-action']) {
  test(`拒绝与当前检查点不一致的预览 ${bad}`, async ({ page }) => {
    const source = await fixture(page); source.settings.malformedPreview = (result: any) => {
      if (bad === 'task') result.taskId = 'foreign-task'; if (bad === 'target') result.targetCheckpointId = 'foreign-checkpoint';
      if (bad === 'source') result.sourceCheckpointId = 'previous-source'; if (bad === 'index') delete result.indexChanges;
      if (bad === 'conflicts') result.conflicts = [{ path: 'a', reason: {} }]; if (bad === 'duplicate-path') result.changes.push(result.changes[0]);
      if (bad === 'inconsistent') result.conflicts = [{ path: 'a', reason: 'conflict' }]; if (bad === 'prototype-action') result.changes[0].action = 'toString'; return result;
    };
    const panel = await open(page); await inspect(panel); await expect(panel.getByRole('alert')).toHaveText('撤销预览与所选任务或检查点不一致，请重新读取。');
    await expect(panel.locator('.checkpoint-plan')).toHaveCount(0); expect(source.requests.filter(item => item.method === 'RESTORE')).toEqual([]); expect(source.errors).toEqual([]);
  });
}

for (const action of ['preview', 'save']) {
  test(`切换任务隔离迟到的${action}响应`, async ({ page }) => {
    const source = await fixture(page), second = { ...source.task, id: 'second-checkpoint-task', title: '另一个检查点任务' }; source.tasks.push(second);
    await page.route('**/api/tasks/second-checkpoint-task', route => route.fulfill({ json: second })); const panel = await open(page); await expect(panel.locator('.checkpoint-choice')).toHaveCount(30);
    let release!: () => void; source.checkpointGates.set(`${action}:${source.task.id}`, new Promise(resolve => { release = resolve; }));
    try {
      if (action === 'preview') await inspect(panel); else await panel.getByRole('button', { name: '保存当前检查点' }).click();
      await expect.poll(() => source.requests.some(item => item.method === action.toUpperCase())).toBe(true);
      await page.getByRole('button', { name: '全部任务', exact: true }).click(); await page.getByRole('row').filter({ has: page.getByText(second.title, { exact: true }) }).getByRole('button').click();
      await page.getByRole('button', { name: '检查点与撤销', exact: true }).click(); await expect(panel.locator('.checkpoint-choice')).toHaveCount(30);
      release(); await page.waitForTimeout(150); await expect(page.getByRole('heading', { name: second.title, exact: true })).toBeVisible(); await expect(panel.locator('.checkpoint-plan')).toHaveCount(0); expect(source.errors).toEqual([]);
    } finally { release(); source.checkpointGates.clear(); }
  });
}

for (const theme of ['pure-white', 'paper-ink', 'classic-warm', 'celadon', 'ink-night', 'ink-night-blue']) for (const width of [1440, 430]) {
  test(`检查点实际文字对比度、键盘与布局 ${theme} ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1100 }); await page.addInitScript(value => localStorage.setItem('workbench-theme', value), theme);
    const source = await fixture(page), panel = await open(page); await inspect(panel); const preview = panel.getByRole('button', { name: '预览撤销', exact: true });
    await expect(preview).toBeEnabled();
    await preview.focus(); await expect(preview).toBeFocused(); await preview.press('Enter'); await expect(panel.locator('.checkpoint-paths li')).toHaveCount(2);
    const measurements = await panel.evaluate(root => {
      const rgb = (value: string) => value.match(/[\d.]+/g)?.map(Number) || [0, 0, 0];
      const composite = (front: number[], back: number[]) => [0, 1, 2].map(i => front[i] * (front[3] ?? 1) + back[i] * (1 - (front[3] ?? 1)));
      const lum = (color: number[]) => color.slice(0, 3).map(value => { const x = value / 255; return x <= .04045 ? x / 12.92 : ((x + .055) / 1.055) ** 2.4; }).reduce((sum, value, i) => sum + value * [.2126, .7152, .0722][i], 0);
      const background = (element: Element) => { const parents: Element[] = []; for (let node: Element | null = element; node; node = node.parentElement) parents.push(node); return parents.reverse().reduce((color, node) => composite(rgb(getComputedStyle(node).backgroundColor), color), [255, 255, 255]); };
      const failures: any[] = []; let count = 0, minimum = Infinity; const walker = document.createTreeWalker(root, NodeFilter.SHOW_TEXT);
      for (let node = walker.nextNode(); node; node = walker.nextNode()) {
        const text = node.textContent?.trim(), element = node.parentElement; if (!text || !element || element.closest('[aria-hidden=true],[disabled]') || !element.getClientRects().length) continue;
        const style = getComputedStyle(element); if (style.visibility !== 'visible') continue;
        const bg = background(element), fg = composite(rgb(style.color), bg), first = lum(bg), second = lum(fg), actual = (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
        count++; minimum = Math.min(minimum, actual); if (actual + .005 < 4.5) failures.push({ text: text.slice(0, 80), ratio: actual });
      }
      return { count, minimum, failures, width: root.getBoundingClientRect().width };
    });
    expect(measurements.count).toBeGreaterThan(40); expect(measurements.failures).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await info.attach('checkpoint-theme-measurements.json', { body: Buffer.from(JSON.stringify({ theme, width, ...measurements })), contentType: 'application/json' });
    const screenshot = info.outputPath('checkpoints.png'); await page.screenshot({ path: screenshot, fullPage: true }); await info.attach('checkpoints.png', { path: screenshot, contentType: 'image/png' });
    if (theme === 'celadon') await page.screenshot({ path: `qa/checkpoints-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true }); expect(source.errors).toEqual([]);
  });
}
