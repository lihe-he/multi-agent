import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

const layoutKey = 'workbench-layout';
const separatorName = '调整侧栏宽度';

async function openWorkbench(page: Page) {
  await page.route('**/api/state', route => route.fulfill({ json: {
    projects: [{ id: 'layout-project', name: '侧栏验证项目', path: '/fixtures/layout', branch: 'main', fileCount: 0 }],
    tasks: [], routes: [], profiles: [], memories: [], system: { storageHealthy: true },
  } }));
  await page.goto('/');
  await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
}

const sidebarWidth = (page: Page) => page.locator('.sidebar').evaluate(element => element.getBoundingClientRect().width);
const savedLayout = (page: Page) => page.evaluate(key => JSON.parse(localStorage.getItem(key) || 'null'), layoutKey);

async function pressHandle(page: Page) {
  const handle = page.getByRole('separator', { name: separatorName, exact: true });
  await expect(handle).toBeVisible({ timeout: 3000 });
  const box = (await handle.boundingBox())!;
  await page.mouse.move(box.x + box.width / 2, 180);
  await page.mouse.down();
  return { handle, x: box.x + box.width / 2 };
}

async function dragBy(page: Page, delta: number) {
  const { x } = await pressHandle(page);
  await page.mouse.move(x + delta, 210, { steps: 8 });
  await page.mouse.up();
}

test('真实拖动调整侧栏、内容和提示的位置，松开前不保存', async ({ page }) => {
  await page.route('**/api/projects/layout-project/scan', route => route.fulfill({ json: {} }));
  await openWorkbench(page);
  const before = await sidebarWidth(page), { x } = await pressHandle(page);
  await page.mouse.move(x + 120, 210, { steps: 12 });
  await expect.poll(() => sidebarWidth(page)).toBe(before + 120);
  expect(await savedLayout(page)).toBeNull();
  await page.mouse.up();
  expect((await savedLayout(page)).sidebarWidth).toBe(before + 120);
  const bounds = await page.locator('.main-shell').boundingBox();
  expect(bounds!.x).toBe(before + 120);
  await page.getByRole('button', { name: '更新项目索引', exact: true }).click();
  const toast = page.getByRole('status').filter({ hasText: '项目索引已更新' });
  await expect(toast).toBeVisible();
  const box = (await toast.boundingBox())!;
  expect(Math.abs(box.x + box.width / 2 - (bounds!.x + bounds!.width / 2))).toBeLessThan(2);
  await dragBy(page, 1000);
  expect(await sidebarWidth(page)).toBe(420);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
});

test('无移动点击、非主按钮和其他指针不改变或结束当前拖动', async ({ page }) => {
  await openWorkbench(page);
  const before = await sidebarWidth(page), first = await pressHandle(page);
  await page.mouse.up();
  expect(await savedLayout(page)).toBeNull();
  await first.handle.click({ button: 'right' });
  expect(await sidebarWidth(page)).toBe(before);
  await page.evaluate(() => {
    document.addEventListener('pointerdown', event => {
      (window as unknown as { testPointerId: number }).testPointerId = event.pointerId;
    }, { once: true });
  });
  const { handle, x } = await pressHandle(page);
  await page.mouse.move(x + 80, 210, { steps: 4 });
  const pointerId = await page.evaluate(() => (window as unknown as { testPointerId: number }).testPointerId);
  await handle.dispatchEvent('pointermove', { pointerId: pointerId + 1, isPrimary: false, clientX: 900 });
  await handle.dispatchEvent('pointercancel', { pointerId: pointerId + 1 });
  await handle.dispatchEvent('pointerup', { pointerId: pointerId + 1 });
  await expect.poll(() => sidebarWidth(page)).toBe(before + 80);
  await page.mouse.move(x + 100, 210);
  await page.mouse.up();
  expect((await savedLayout(page)).sidebarWidth).toBe(before + 100);
});

for (const cancellation of ['Escape', 'pointercancel', 'lostpointercapture', 'blur']) {
  test(`拖动通过 ${cancellation} 取消后恢复原宽度、光标和选择能力`, async ({ page }) => {
    await openWorkbench(page);
    await page.evaluate(() => {
      document.body.style.setProperty('cursor', 'crosshair', 'important');
      document.body.style.setProperty('user-select', 'text', 'important');
      document.addEventListener('pointerdown', event => {
        (window as unknown as { testPointerId: number }).testPointerId = event.pointerId;
      }, { once: true });
    });
    const before = await sidebarWidth(page), { handle, x } = await pressHandle(page);
    await page.mouse.move(x + 100, 210, { steps: 5 });
    await expect.poll(() => sidebarWidth(page)).toBe(before + 100);
    if (cancellation === 'Escape') await page.keyboard.press('Escape');
    else if (cancellation === 'blur') await page.evaluate(() => window.dispatchEvent(new Event('blur')));
    else {
      const pointerId = await page.evaluate(() => (window as unknown as { testPointerId: number }).testPointerId);
      if (cancellation === 'lostpointercapture') await handle.evaluate((element, id) => element.releasePointerCapture(id), pointerId);
      else await handle.dispatchEvent(cancellation, { pointerId });
    }
    await page.mouse.up();
    await expect.poll(() => sidebarWidth(page)).toBe(before);
    expect(await savedLayout(page)).toBeNull();
    expect(await page.evaluate(() => ['cursor', 'user-select'].map(property => [document.body.style.getPropertyValue(property), document.body.style.getPropertyPriority(property)])))
      .toEqual([['crosshair', 'important'], ['text', 'important']]);
    await page.getByRole('button', { name: '资源与设置', exact: true }).click();
    await expect(page.getByRole('button', { name: '外观', exact: true })).toBeVisible();
    await dragBy(page, 40);
    expect((await savedLayout(page)).sidebarWidth).toBe(before + 40);
  });
}

test('分隔器支持键盘调整、上下界和收起恢复并保留焦点', async ({ page }) => {
  await openWorkbench(page);
  const handle = page.getByRole('separator', { name: separatorName, exact: true });
  await handle.focus();
  await page.keyboard.press('ArrowRight');
  expect(await sidebarWidth(page)).toBe(254);
  await page.keyboard.press('Shift+ArrowRight');
  expect(await sidebarWidth(page)).toBe(304);
  await page.keyboard.press('ArrowLeft');
  expect(await sidebarWidth(page)).toBe(294);
  await page.keyboard.press('Home');
  expect(await sidebarWidth(page)).toBe(200);
  await page.keyboard.press('End');
  expect(await sidebarWidth(page)).toBe(420);
  await expect(handle).toHaveAttribute('aria-valuenow', '420');
  await expect(handle).toHaveAttribute('aria-controls', 'workbench-sidebar');
  await page.keyboard.press('Enter');
  expect(await sidebarWidth(page)).toBe(75);
  await expect(handle).toBeFocused();
  await expect(handle).toHaveAttribute('aria-valuenow', '75');
  await page.keyboard.press('Enter');
  expect(await sidebarWidth(page)).toBe(420);
  await expect(handle).toBeFocused();
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click();
  await page.reload();
  await expect(page.getByRole('button', { name: '展开侧栏', exact: true })).toBeVisible();
  await expect(page.getByRole('button', { name: '本地核心已连接', exact: true })).toBeVisible();
  expect(await sidebarWidth(page)).toBe(75);
  await page.getByRole('button', { name: '展开侧栏', exact: true }).click();
  expect(await sidebarWidth(page)).toBe(420);
});

test('窗口缩小限制实际宽度但保留大窗口意图，窄屏保持图标侧栏', async ({ page }) => {
  await openWorkbench(page);
  await dragBy(page, 300);
  await page.setViewportSize({ width: 986, height: 640 });
  await expect.poll(async () => (await page.locator('.main-shell').boundingBox())!.width).toBeGreaterThanOrEqual(720);
  expect(await sidebarWidth(page)).toBeLessThanOrEqual(266);
  expect((await page.locator('.main-shell').boundingBox())!.width).toBeLessThanOrEqual(721);
  expect((await page.locator('.main-shell').boundingBox())!.width).toBeGreaterThanOrEqual(720);
  await pressHandle(page);
  await page.mouse.up();
  expect((await savedLayout(page)).sidebarWidth).toBe(420);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect.poll(() => sidebarWidth(page)).toBe(420);
  const handle = page.getByRole('separator', { name: separatorName, exact: true });
  await handle.focus();
  await page.setViewportSize({ width: 980, height: 640 });
  await expect(handle).toBeHidden();
  expect(await sidebarWidth(page)).toBe(75);
  await expect(page.getByRole('button', { name: '收起侧栏', exact: true })).toBeFocused();
  await page.setViewportSize({ width: 430, height: 900 });
  expect(await sidebarWidth(page)).toBe(58);
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect.poll(() => sidebarWidth(page)).toBe(420);
});

test('页面预留侧边空间后仍为内容保留 720px 并保全大窗口意图', async ({ page }) => {
  await openWorkbench(page);
  await dragBy(page, 300);
  await page.setViewportSize({ width: 986, height: 640 });
  const reserved = await page.addStyleTag({ content: 'body { margin-right: 15px; }' });
  await expect.poll(async () => (await page.locator('.main-shell').boundingBox())!.width).toBeGreaterThanOrEqual(720);
  const measured = await page.evaluate(() => ({ viewport: innerWidth,
    available: document.querySelector('.app-shell')!.getBoundingClientRect().width,
    sidebar: document.querySelector('.sidebar')!.getBoundingClientRect().width,
    main: document.querySelector('.main-shell')!.getBoundingClientRect().width }));
  await test.info().attach('actual-reserved-layout-width', { body: Buffer.from(JSON.stringify(measured)), contentType: 'application/json' });
  expect(measured.sidebar).toBeLessThan(266);
  expect(measured.main).toBeLessThanOrEqual(721);
  expect((await savedLayout(page)).sidebarWidth).toBe(420);
  await reserved.evaluate(element => element.remove());
  await page.setViewportSize({ width: 1440, height: 1000 });
  await expect.poll(() => sidebarWidth(page)).toBe(420);
});

test('无效或不支持的布局记录安全回退，旧响应式默认宽度保留', async ({ page }) => {
  await openWorkbench(page);
  for (const raw of ['{', '{"version":2,"sidebarWidth":320,"sidebarCollapsed":false}',
    '{"version":1,"sidebarWidth":"NaN","sidebarCollapsed":false}',
    '{"version":1,"sidebarWidth":1000000,"sidebarCollapsed":false}']) {
    await page.evaluate(({ key, value }) => localStorage.setItem(key, value), { key: layoutKey, value: raw });
    await page.reload();
    expect(await sidebarWidth(page)).toBe(244);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  }
  await page.setViewportSize({ width: 1100, height: 900 });
  await expect.poll(() => sidebarWidth(page)).toBe(215);
});

test('保存失败后的恢复事件保留本窗口意图，重新保存成功后可刷新恢复', async ({ page }) => {
  await page.addInitScript(key => {
    if (localStorage.getItem(key) === null)
      localStorage.setItem(key, JSON.stringify({ version: 1, sidebarWidth: 260, sidebarCollapsed: false }));
    const original = Storage.prototype.setItem;
    (window as unknown as { failLayoutWrite: boolean }).failLayoutWrite = true;
    Storage.prototype.setItem = function (name, value) {
      if (name === key && (window as unknown as { failLayoutWrite: boolean }).failLayoutWrite)
        throw new DOMException('Test quota', 'QuotaExceededError');
      return original.call(this, name, value);
    };
  }, layoutKey);
  await openWorkbench(page);
  await dragBy(page, 100);
  expect(await sidebarWidth(page)).toBe(360);
  expect((await savedLayout(page)).sidebarWidth).toBe(260);
  const warning = page.getByRole('status').filter({ hasText: '无法保存布局' });
  await expect(warning).toBeVisible();
  await page.evaluate(key => {
    window.dispatchEvent(new PageTransitionEvent('pageshow', { persisted: true }));
    window.dispatchEvent(new StorageEvent('storage', { key, storageArea: localStorage, newValue: localStorage.getItem(key) }));
  }, layoutKey);
  expect(await sidebarWidth(page)).toBe(360);
  await expect(warning).toBeVisible();
  await page.evaluate(() => { (window as unknown as { failLayoutWrite: boolean }).failLayoutWrite = false; });
  await page.getByRole('separator', { name: separatorName, exact: true }).focus();
  await page.keyboard.press('ArrowRight');
  expect((await savedLayout(page)).sidebarWidth).toBe(370);
  await expect(warning).toBeHidden();
  await page.reload();
  await expect.poll(() => sidebarWidth(page)).toBe(370);
});

test('浏览器阻止存储时仍能调整、收起、恢复和使用业务入口', async ({ page }) => {
  await page.addInitScript(() => Object.defineProperty(window, 'localStorage', {
    get() { throw new DOMException('Test denied', 'SecurityError'); },
  }));
  await openWorkbench(page);
  await dragBy(page, 60);
  expect(await sidebarWidth(page)).toBe(304);
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click();
  expect(await sidebarWidth(page)).toBe(75);
  await page.getByRole('button', { name: '展开侧栏', exact: true }).click();
  expect(await sidebarWidth(page)).toBe(304);
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '外观', exact: true }).click();
  await expect(page.getByRole('button', { name: '恢复默认布局', exact: true })).toBeVisible();
});

test('同源窗口同步最新布局，旧排队事件不能覆盖最新记录', async ({ page, context }) => {
  await openWorkbench(page);
  const other = await context.newPage();
  try {
    await openWorkbench(other);
    await dragBy(page, 80);
    await expect.poll(() => sidebarWidth(other)).toBe(324);
    await other.getByRole('button', { name: '收起侧栏', exact: true }).click();
    await expect.poll(() => sidebarWidth(page)).toBe(75);
    await page.evaluate(key => window.dispatchEvent(new StorageEvent('storage', {
      key, storageArea: localStorage, newValue: '{"version":1,"sidebarWidth":210,"sidebarCollapsed":false}',
    })), layoutKey);
    expect(await sidebarWidth(page)).toBe(75);
    await other.getByRole('button', { name: '展开侧栏', exact: true }).click();
    await expect.poll(() => sidebarWidth(page)).toBe(324);
    await page.reload();
    await expect.poll(() => sidebarWidth(page)).toBe(324);
    const { x } = await pressHandle(page);
    await page.mouse.move(x + 50, 210, { steps: 4 });
    await expect.poll(() => sidebarWidth(page)).toBe(374);
    await other.evaluate(key => localStorage.setItem(key, JSON.stringify({ version: 1, sidebarWidth: 280, sidebarCollapsed: false })), layoutKey);
    await expect.poll(async () => (await savedLayout(page)).sidebarWidth).toBe(280);
    expect(await sidebarWidth(page)).toBe(374);
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await expect.poll(() => sidebarWidth(page)).toBe(280);
    await other.evaluate(() => localStorage.clear());
    await expect.poll(() => sidebarWidth(page)).toBe(244);
  } finally { await other.close(); }
});

test('窗口变化取消在途拖动且不保存临时宽度', async ({ page }) => {
  await openWorkbench(page);
  const { x } = await pressHandle(page);
  await page.mouse.move(x + 100, 210, { steps: 5 });
  await expect.poll(() => sidebarWidth(page)).toBe(344);
  await page.setViewportSize({ width: 1100, height: 900 });
  await page.mouse.up();
  await expect.poll(() => sidebarWidth(page)).toBe(215);
  expect(await savedLayout(page)).toBeNull();
  expect(await page.evaluate(() => document.body.style.cursor)).toBe('');
});

for (const theme of ['pure-white', 'paper-ink', 'classic-warm', 'celadon', 'ink-night', 'ink-night-blue']) {
  test(`分隔器焦点在 ${theme} 的两侧背景上清晰可见`, async ({ page }) => {
    await page.addInitScript(value => localStorage.setItem('workbench-theme', value), theme);
    await openWorkbench(page);
    const handle = page.getByRole('separator', { name: separatorName, exact: true });
    await handle.focus();
    await page.keyboard.press('ArrowRight');
    const result = await handle.evaluate(element => {
      const luminance = (color: string) => {
        const channels = color.match(/[\d.]+/g)!.slice(0, 3).map(Number).map(value => {
          const normalized = value / 255;
          return normalized <= .04045 ? normalized / 12.92 : Math.pow((normalized + .055) / 1.055, 2.4);
        });
        return channels[0] * .2126 + channels[1] * .7152 + channels[2] * .0722;
      };
      const contrast = (a: string, b: string) => {
        const first = luminance(a), second = luminance(b);
        return (Math.max(first, second) + .05) / (Math.min(first, second) + .05);
      };
      const left = getComputedStyle(element, '::before'), right = getComputedStyle(element, '::after');
      const sidebar = getComputedStyle(document.querySelector('.sidebar')!).backgroundColor;
      const canvas = getComputedStyle(document.documentElement).backgroundColor, topbar = getComputedStyle(document.querySelector('.topbar')!).backgroundColor;
      return { focusVisible: element.matches(':focus-visible'), leftWidth: left.width, rightWidth: right.width,
        leftContrast: contrast(left.backgroundColor, sidebar), rightContrast: Math.min(contrast(right.backgroundColor, canvas), contrast(right.backgroundColor, topbar)),
        bounds: element.getBoundingClientRect().toJSON(), sidebar, canvas, topbar };
    });
    await test.info().attach('actual-separator-focus', { body: Buffer.from(JSON.stringify(result)), contentType: 'application/json' });
    expect(result.focusVisible).toBe(true);
    expect(parseFloat(result.leftWidth)).toBeGreaterThanOrEqual(3);
    expect(parseFloat(result.rightWidth)).toBeGreaterThanOrEqual(3);
    expect(result.leftContrast).toBeGreaterThanOrEqual(3);
    expect(result.rightContrast).toBeGreaterThanOrEqual(3);
  });
}

test('外观设置可恢复默认布局和响应式宽度', async ({ page }) => {
  await openWorkbench(page);
  await dragBy(page, 300);
  await page.getByRole('button', { name: '收起侧栏', exact: true }).click();
  await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '外观', exact: true }).click();
  await page.getByRole('button', { name: '恢复默认布局', exact: true }).click();
  expect(await sidebarWidth(page)).toBe(244);
  expect((await savedLayout(page)).sidebarWidth).toBeNull();
  expect((await savedLayout(page)).sidebarCollapsed).toBe(false);
  await page.setViewportSize({ width: 1100, height: 900 });
  await expect.poll(() => sidebarWidth(page)).toBe(215);
});
