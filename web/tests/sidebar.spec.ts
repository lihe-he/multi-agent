import { expect, test } from '@playwright/test';

for (const width of [986, 1280]) for (const projectCount of [0, 8]) {
  test(`较矮窗口中设置入口可鼠标点击和键盘访问，${width}px/${projectCount} 个项目`, async ({ page }) => {
    await page.setViewportSize({ width, height: 640 });
    const projects = Array.from({ length: projectCount }, (_, index) => ({
      id: `sidebar-project-${index}`, name: `侧栏项目 ${index + 1}`, path: `D:\\fixtures\\sidebar-${index}`,
      branch: 'main', fileCount: 0, files: [], modules: [],
    }));
    await page.route('**/api/state', route => route.fulfill({ json: {
      projects, tasks: [], routes: [], profiles: [], memories: [], system: { storageHealthy: true },
    } }));
    await page.goto('/');
    await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
    const settings = page.getByRole('button', { name: '资源与设置', exact: true });
    await settings.scrollIntoViewIfNeeded();
    await expect(settings).toBeInViewport();
    await settings.click();
    await page.getByRole('button', { name: '外观', exact: true }).click();
    await expect(page.getByRole('button', { name: '墨夜蓝', exact: true })).toBeVisible();
    await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    const systemTab = page.getByRole('button', { name: '资源与运行状态', exact: true });
    await page.keyboard.press('Tab');
    await expect(systemTab).toBeFocused();
    await expect(systemTab).toBeInViewport();
    const focusPainted = await systemTab.evaluate(button => {
      const style = getComputedStyle(button), tabs = button.parentElement!, bounds = button.getBoundingClientRect(), clip = tabs.getBoundingClientRect();
      const spread = parseFloat(style.outlineOffset) + parseFloat(style.outlineWidth), clipped = getComputedStyle(tabs).overflowX !== 'visible';
      const visible = button.matches(':focus-visible') && style.outlineStyle === 'solid' && parseFloat(style.outlineWidth) >= 3
        && (!clipped || bounds.left - spread >= clip.left - 1 && bounds.right + spread <= clip.right + 1
          && bounds.top - spread >= clip.top - 1 && bounds.bottom + spread <= clip.bottom + 1);
      return { visible, focusVisible: button.matches(':focus-visible'), outlineStyle: style.outlineStyle, outlineWidth: style.outlineWidth,
        outlineOffset: style.outlineOffset, bounds: bounds.toJSON(), clip: clip.toJSON(), scrollLeft: tabs.scrollLeft, clientWidth: tabs.clientWidth, scrollWidth: tabs.scrollWidth };
    });
    await test.info().attach('actual-layout-tab-focus', { body: Buffer.from(JSON.stringify(focusPainted)), contentType: 'application/json' });
    expect(focusPainted.visible, JSON.stringify(focusPainted)).toBe(true);
    await page.keyboard.press('Enter');
    await expect(page.getByRole('heading', { name: '核心运行状态', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '上下文与记忆', exact: true }).focus();
    await page.keyboard.press('Tab');
    await expect(page.getByRole('button', { name: '导入项目', exact: true })).toBeFocused();
    for (const project of projects) {
      await page.keyboard.press('Tab');
      await expect(page.locator('.sidebar-projects').getByRole('button', { name: project.name, exact: true })).toBeFocused();
    }
    await page.keyboard.press('Tab');
    await expect(settings).toBeFocused();
    await expect(settings).toBeInViewport();
    await page.keyboard.press('Enter');
    await expect(page.getByRole('button', { name: '外观', exact: true })).toBeVisible();

    await page.getByRole('button', { name: '收起侧栏', exact: true }).click();
    await settings.click();
    await expect(settings).toBeInViewport();
    await expect(page.getByRole('button', { name: '外观', exact: true })).toBeVisible();
  });
}
