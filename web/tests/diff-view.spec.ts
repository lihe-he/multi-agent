import { expect, test, type Page } from '@playwright/test';
import { openWorkspaceTask, workspaceFixture } from './fixtures/workspaces';

const mainPath = 'src/components/中文 view.tsx', otherPath = 'src/model.ts';
const longPath = `docs/${'长目录与空格 '.repeat(35)}/说明.md`, controlPath = 'src/制表\t与\n换行.txt';
const literal = '+++literal <img src=x onerror="window.diffInjected=1"> <script>literal</script>';
const baseline = '1'.repeat(40), version = 'a'.repeat(64), longLine = '横向滚动代码内容_'.repeat(60);
const types = { 'assets/image.bin': 'BINARY', '.env': 'EXCLUDED', 'large.txt': 'LIMIT', 'mode.sh': 'MODE_ONLY', 'notes/empty.txt': 'EMPTY', 'unsupported.txt': 'UNSUPPORTED' } as const;

function changedFile(path: string): any {
  const added = path === 'notes/empty.txt', deleted = path === 'docs/removed.md', modeOnly = path === 'mode.sh';
  return { path, status: added ? 'ADDED' : deleted ? 'DELETED' : 'MODIFIED', modeOnly, excluded: path === '.env', oldMode: added ? '000000' : '100644', newMode: deleted ? '000000' : modeOnly ? '100755' : '100644',
    bytes: added || deleted ? 0 : path === 'large.txt' ? 300_000 : 8_192, sha256: deleted ? '' : 'b'.repeat(64), gitBlob: deleted ? '' : '2'.repeat(40), baseBlob: added ? '' : baseline };
}
function preview(file: any, current: string): any {
  const type = types[file.path as keyof typeof types] || 'TEXT';
  const facts = { path: file.path, version: current, baseBlob: file.baseBlob, sha256: file.sha256, beforeBytes: type === 'EXCLUDED' ? null : file.status === 'ADDED' ? 0 : 4_096, afterBytes: file.bytes,
    type, message: type === 'LIMIT' ? '文件超过差异文本预览大小上限，可在工作目录中查看。' : type === 'BINARY' ? '该文件不是可完整解码的 UTF-8 文本。' : type === 'EXCLUDED' ? '内部或敏感文件不提供差异文本预览。' : type === 'MODE_ONLY' ? '文件内容未变化，只有执行位变化。' : type === 'EMPTY' ? '新增空文件。' : type === 'UNSUPPORTED' ? '本次输出不能完整解析，未展示不完整行。' : '',
    limit: type === 'LIMIT' ? 'FILE_BYTES' : type === 'EXCLUDED' ? 'POLICY' : type === 'UNSUPPORTED' ? 'FORMAT' : '', complete: ['TEXT', 'MODE_ONLY', 'EMPTY'].includes(type), hunks: [] };
  if (type !== 'TEXT') return facts;
  if (file.status === 'DELETED') return { ...facts, additions: 0, deletions: 1, rows: 1, hunks: [{ oldStart: 1, oldCount: 1, newStart: 0, newCount: 0, lines: [{ kind: 'REMOVE', content: '移除原文件', oldLine: 1, newLine: null, noNewline: true }] }] };
  return { ...facts, additions: 3, deletions: 2, rows: 6, hunks: [
    { oldStart: 1, oldCount: 2, newStart: 1, newCount: 2, lines: [{ kind: 'CONTEXT', content: '保留上下文', oldLine: 1, newLine: 1, noNewline: false },
      { kind: 'REMOVE', content: '原内容', oldLine: 2, newLine: null, noNewline: false }, { kind: 'ADD', content: `${literal} · ${file.path} · 版本${current.slice(0, 1)} · ${longLine}`, oldLine: null, newLine: 2, noNewline: false }] },
    { oldStart: 20, oldCount: 1, newStart: 20, newCount: 2, lines: [{ kind: 'REMOVE', content: '原末行', oldLine: 20, newLine: null, noNewline: true },
      { kind: 'ADD', content: '+新末行', oldLine: null, newLine: 20, noNewline: false }, { kind: 'ADD', content: '新增尾行', oldLine: null, newLine: 21, noNewline: true }] },
  ] };
}
async function fixture(page: Page) {
  const original = await workspaceFixture(page); original.task.diffViewProtocolVersion = 1; original.task.version = version; original.task.workspace = 'C:\\data\\独立 work';
  const settings: { version: string; files: any[]; error?: { status: number; message: string }; malformed?: (response: any) => any } = {
    version, files: [mainPath, otherPath, longPath, controlPath, 'docs/removed.md', ...Object.keys(types)].map(changedFile),
  };
  const queries: { taskId: string; path: string; expectedVersion: string; expectedBase: string }[] = [], gates = new Map<string, Promise<void>>();
  await page.route('**/api/tasks/*/diff-view?**', async route => {
    const url = new URL(route.request().url()), path = url.searchParams.get('path') || '', expectedVersion = url.searchParams.get('expectedVersion') || '', expectedBase = url.searchParams.get('expectedBase') || '', taskId = url.pathname.split('/')[3];
    queries.push({ taskId, path, expectedVersion, expectedBase });
    if (settings.error) return route.fulfill({ status: settings.error.status, json: { error: settings.error.message } });
    if (expectedVersion && expectedVersion !== settings.version || expectedBase && expectedBase !== baseline) return route.fulfill({ status: 409, json: { error: '工作目录或 Git 基线发生变化，请刷新后查看。' } });
    const selected = path ? settings.files.find(file => file.path === path) : settings.files[0];
    if (path && !selected) return route.fulfill({ status: 404, json: { error: '该文件已不在本次变更列表中，请刷新。' } });
    const response = { protocolVersion: 1, workspace: original.task.workspace, version: settings.version, baseline, files: settings.files, selectedPath: selected?.path || '', preview: selected ? preview(selected, settings.version) : null,
      complete: true, consistency: 'CAPTURED_FILE_DIGESTS', limits: { fileBytes: 256_000, changedFiles: 2_000, rows: 5_000, parallelReaders: 2 } };
    const captured = structuredClone(settings.malformed ? settings.malformed(response) : response);
    if (gates.has(path)) await gates.get(path);
    try { await route.fulfill({ json: captured }); } catch { /* An intentionally superseded request can already be aborted. */ }
  });
  return { ...original, settings, queries, gates };
}
async function open(page: Page) { await openWorkspaceTask(page); await page.getByRole('button', { name: '代码差异', exact: true }).click(); return page.getByRole('region', { name: '工作目录差异', exact: true }); }
const fileButton = (page: Page, path: string) => page.getByRole('treeitem', { name: `${path} · ${path === 'docs/removed.md' ? '删除' : path === 'notes/empty.txt' ? '新增' : '修改'}`, exact: true });

for (const width of [1440, 430]) {
  test(`逐文件树、原新行号、多个差异块与字面文本 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1100 }); const source = await fixture(page), panel = await open(page);
    await expect(panel.getByRole('heading', { name: mainPath, exact: true })).toBeVisible(); await expect(panel.locator('.diff-hunk')).toHaveCount(2);
    const addition = panel.locator('.diff-code-row.add').first(), removal = panel.locator('.diff-code-row.remove').first();
    await expect(addition.locator('.diff-line-old')).toBeEmpty(); await expect(addition.locator('.diff-line-new')).toHaveText('2'); await expect(removal.locator('.diff-line-old')).toHaveText('2'); await expect(removal.locator('.diff-line-new')).toBeEmpty();
    await expect(addition.locator('code')).toHaveText(`${literal} · ${mainPath} · 版本a · ${longLine}`); await expect(panel.getByText('末行无换行符 · 原文件', { exact: true })).toBeVisible(); await expect(panel.getByText('末行无换行符 · 新文件', { exact: true })).toBeVisible();
    expect(await panel.locator('script,img').count()).toBe(0); expect(await page.evaluate(() => (window as any).diffInjected)).toBeUndefined();
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const scroll = panel.locator('.diff-code-scroll'); expect(await scroll.evaluate(element => element.scrollWidth > element.clientWidth)).toBe(true);
    await fileButton(page, otherPath).click(); await expect(panel.getByRole('heading', { name: otherPath, exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText(otherPath);
    expect(source.queries.at(-1)).toEqual({ taskId: source.task.id, path: otherPath, expectedVersion: version, expectedBase: baseline });
    await fileButton(page, longPath).click(); await expect(panel.getByRole('heading', { name: longPath, exact: true })).toBeVisible(); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await fileButton(page, mainPath).click(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText(mainPath);
    await page.getByRole('treeitem', { name: '文件夹 docs', exact: true }).click();
    await page.screenshot({ path: `qa/diff-view-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true }); expect(source.errors).toEqual([]);
  });

  test(`文件树键盘焦点、折叠与选择 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const source = await fixture(page), panel = await open(page), folder = page.getByRole('treeitem', { name: '文件夹 src', exact: true });
    await expect(panel.locator('.diff-code-row')).toHaveCount(6); await folder.focus(); await folder.press('ArrowLeft'); await expect(folder).toHaveAttribute('aria-expanded', 'false'); await expect(fileButton(page, mainPath)).toHaveCount(0);
    await folder.press('ArrowRight'); await expect(folder).toHaveAttribute('aria-expanded', 'true'); await folder.press('ArrowRight'); const nested = page.getByRole('treeitem', { name: '文件夹 src/components', exact: true }); await expect(nested).toBeFocused();
    await nested.press('ArrowRight'); await expect(fileButton(page, mainPath)).toBeFocused(); await fileButton(page, mainPath).press('Enter'); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
    await fileButton(page, mainPath).press('ArrowLeft'); await expect(nested).toBeFocused(); await nested.press(' '); await expect(nested).toHaveAttribute('aria-expanded', 'false'); await nested.press(' '); await expect(nested).toHaveAttribute('aria-expanded', 'true');
    await nested.press('Home'); await expect(panel.getByRole('treeitem').first()).toBeFocused(); await panel.getByRole('treeitem').first().press('End'); await expect(panel.getByRole('treeitem').last()).toBeFocused();
    expect(await panel.locator('[role="treeitem"][tabindex="0"]').count()).toBe(1); expect(source.errors).toEqual([]);
  });

  test(`刷新保留现存选择并在文件移除后回到新的清单 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const source = await fixture(page), panel = await open(page);
    await fileButton(page, otherPath).click(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText('版本a');
    source.settings.version = 'c'.repeat(64); let release!: () => void; source.gates.set('', new Promise(resolve => { release = resolve; }));
    await panel.getByRole('button', { name: '刷新代码差异' }).click(); await expect(panel.locator('.diff-code-row')).toHaveCount(0); await expect(panel.getByText('正在读取代码差异…', { exact: true })).toBeVisible();
    release(); source.gates.delete(''); await expect(panel.getByRole('heading', { name: otherPath, exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText('版本c');
    expect(source.queries.at(-1)?.expectedVersion).toBe('c'.repeat(64)); source.settings.files = source.settings.files.filter(file => file.path !== otherPath);
    await panel.getByRole('button', { name: '刷新代码差异' }).click(); await expect(panel.getByRole('heading', { name: mainPath, exact: true })).toBeVisible(); await expect(fileButton(page, otherPath)).toHaveCount(0); expect(source.errors).toEqual([]);
  });

  test(`迟到的旧文件响应不能覆盖新的选择 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
    let release!: () => void; source.gates.set(otherPath, new Promise(resolve => { release = resolve; }));
    try {
      await fileButton(page, otherPath).click(); await expect(panel.getByText('正在读取所选文件…', { exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row')).toHaveCount(0);
      await fileButton(page, mainPath).click(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText(mainPath);
      release(); source.gates.delete(otherPath); await page.waitForTimeout(150); await expect(panel.getByRole('heading', { name: mainPath, exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText(mainPath);
      expect(source.errors).toEqual([]);
    } finally { release(); source.gates.delete(otherPath); }
  });

  test(`二进制、敏感、超限、模式、空文件与不可解析状态 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
    for (const [path, heading] of [['assets/image.bin', '二进制或非 UTF-8 文件'], ['.env', '该路径不提供文本预览'], ['large.txt', '差异超出预览上限'], ['mode.sh', '只修改文件执行位'], ['notes/empty.txt', '空文件变更'], ['unsupported.txt', '无法完整显示文本差异']]) {
      await fileButton(page, path).click(); await expect(panel.getByRole('heading', { name: heading, exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row')).toHaveCount(0);
    }
    await fileButton(page, 'docs/removed.md').click(); await expect(panel.locator('.diff-code-row.remove')).toHaveCount(1); await expect(panel.locator('.diff-line-new')).toBeEmpty();
    source.settings.files = []; await panel.getByRole('button', { name: '刷新代码差异' }).click(); await expect(panel.getByRole('heading', { name: '当前没有代码差异' })).toBeVisible(); expect(source.errors).toEqual([]);
  });

  test(`读取冲突清除旧行并允许显式刷新 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
    source.settings.error = { status: 409, message: '工作目录已变化 <script>literal</script>，请刷新。' }; await fileButton(page, otherPath).click();
    await expect(panel.getByRole('alert')).toHaveText(source.settings.error.message); await expect(panel.locator('.diff-code-row')).toHaveCount(0); await expect(panel.getByRole('heading', { name: otherPath, exact: true })).toBeVisible();
    source.settings.error = undefined; source.settings.version = 'd'.repeat(64); await panel.getByRole('button', { name: '刷新代码差异' }).click();
    await expect(panel.getByRole('alert')).toHaveCount(0); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText('版本d'); expect(source.errors).toEqual([]);
  });
}

for (const bad of ['preview-version', 'preview-path', 'digest', 'coordinate', 'hunk-count', 'missing-list']) {
  test(`差异响应边界拒绝无效的 ${bad}`, async ({ page }) => {
    const source = await fixture(page); source.settings.malformed = response => {
      if (bad === 'preview-version') response.preview.version = 'f'.repeat(64);
      if (bad === 'preview-path') response.preview.path = otherPath;
      if (bad === 'digest') response.preview.sha256 = 'f'.repeat(64);
      if (bad === 'coordinate') response.preview.hunks[0].lines[2].newLine = 999;
      if (bad === 'hunk-count') response.preview.hunks[0].newCount = 9;
      if (bad === 'missing-list') delete response.files;
      return response;
    };
    const panel = await open(page); await expect(panel.getByRole('alert')).toHaveText('差异视图响应格式无效'); await expect(panel.locator('.diff-code-row')).toHaveCount(0); expect(source.errors).toEqual([]);
  });
}

test('切换任务后迟到的旧任务响应不进入新的详情', async ({ page }) => {
  const source = await fixture(page), second = { ...source.task, id: 'second-diff-task', title: '另一个差异任务' }; source.tasks.push(second);
  await page.route('**/api/tasks/second-diff-task', route => route.fulfill({ json: second })); const panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
  let release!: () => void; source.gates.set(otherPath, new Promise(resolve => { release = resolve; }));
  try {
    await fileButton(page, otherPath).click(); await expect(panel.getByText('正在读取所选文件…', { exact: true })).toBeVisible();
    await page.getByRole('button', { name: '全部任务', exact: true }).click(); await page.getByRole('row').filter({ has: page.getByText(second.title, { exact: true }) }).getByRole('button').click(); await page.getByRole('button', { name: '代码差异', exact: true }).click();
    await expect(panel.getByRole('heading', { name: mainPath, exact: true })).toBeVisible(); release(); source.gates.delete(otherPath); await page.waitForTimeout(150);
    await expect(page.getByRole('heading', { name: second.title, exact: true })).toBeVisible(); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText(mainPath); expect(source.queries.at(-1)?.taskId).toBe(second.id); expect(source.errors).toEqual([]);
  } finally { release(); source.gates.delete(otherPath); }
});

test('任务内容版本变化会重读视图，字面控制字符保留在查询中', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
  const control = panel.locator('[role="treeitem"]').filter({ has: page.locator('.diff-tree-label', { hasText: '制表' }) }); await control.click();
  await expect.poll(() => source.queries.at(-1)?.path).toBe(controlPath); await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText('制表');
  source.settings.version = 'e'.repeat(64); source.task.version = source.settings.version;
  await expect(panel.locator('.diff-code-row.add').first().locator('code')).toContainText('版本e'); expect(source.queries.at(-1)?.expectedVersion).toBe(source.settings.version); expect(source.errors).toEqual([]);
});

test('当前读取超时显示错误并结束加载', async ({ page }) => {
  await page.addInitScript(() => {
    const original = AbortSignal.timeout.bind(AbortSignal);
    AbortSignal.timeout = milliseconds => original((window as any).diffTimeout && milliseconds === 30_000 ? 120 : milliseconds);
  });
  const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
  let release!: () => void; source.gates.set(otherPath, new Promise(resolve => { release = resolve; })); await page.evaluate(() => { (window as any).diffTimeout = true; });
  try {
    await fileButton(page, otherPath).click(); await expect(panel.getByRole('alert')).toHaveText('读取代码差异超时，请刷新后重试。'); await expect(panel.getByText('正在读取所选文件…', { exact: true })).toHaveCount(0); await expect(panel.locator('.diff-code-row')).toHaveCount(0);
    await page.evaluate(() => { (window as any).diffTimeout = false; }); release(); source.gates.delete(otherPath); await panel.getByRole('button', { name: '刷新代码差异' }).click(); await expect(panel.locator('.diff-code-row')).toHaveCount(6); expect(source.errors).toEqual([]);
  } finally { release(); source.gates.delete(otherPath); }
});

const themes = ['pure-white', 'paper-ink', 'classic-warm', 'celadon', 'ink-night', 'ink-night-blue'];
for (const theme of themes) for (const width of [1440, 430]) {
  test(`逐文件差异实际文字对比度与布局 ${theme} ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1100 }); await page.addInitScript(value => localStorage.setItem('workbench-theme', value), theme);
    const source = await fixture(page), panel = await open(page); await expect(panel.locator('.diff-code-row')).toHaveCount(6);
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
        count++; minimum = Math.min(minimum, actual); if (actual + .005 < 4.5) failures.push({ text: text.slice(0, 80), tag: element.tagName, ratio: actual });
      }
      return { count, minimum, failures, panelWidth: root.getBoundingClientRect().width, treeWidth: root.querySelector('.diff-file-tree')?.getBoundingClientRect().width, previewWidth: root.querySelector('.diff-file-preview')?.getBoundingClientRect().width };
    });
    expect(measurements.count).toBeGreaterThan(30); expect(measurements.failures).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await info.attach('diff-theme-measurements.json', { body: Buffer.from(JSON.stringify({ theme, width, ...measurements })), contentType: 'application/json' });
    await panel.scrollIntoViewIfNeeded(); const screenshot = info.outputPath('diff-view.png'); await page.screenshot({ path: screenshot }); await info.attach('diff-view.png', { path: screenshot, contentType: 'image/png' }); expect(source.errors).toEqual([]);
  });
}
