const assert = require('node:assert/strict');
const { createHash } = require('node:crypto');
const { copyFileSync, readFileSync, readdirSync, writeFileSync } = require('node:fs');
const path = require('node:path');

const observe = page => page.evaluate(() => {
  const sidebar = document.querySelector('.sidebar').getBoundingClientRect();
  const main = document.querySelector('.main-shell').getBoundingClientRect();
  const handle = document.querySelector('[role="separator"][aria-controls="workbench-sidebar"]');
  return { recordedAt: new Date().toISOString(), viewport: { width: innerWidth, height: innerHeight },
    sidebarWidth: sidebar.width, mainLeft: main.left, mainWidth: main.width,
    documentWidth: document.documentElement.scrollWidth, saved: JSON.parse(localStorage.getItem('workbench-layout') || 'null'),
    separatorValue: handle.getAttribute('aria-valuenow'), cursor: document.body.style.cursor, userSelect: document.body.style.userSelect };
});

async function waitWidth(page, width) {
  await page.waitForFunction(value => document.querySelector('.sidebar').getBoundingClientRect().width === value, width);
}

async function press(page) {
  const handle = page.getByRole('separator', { name: '调整侧栏宽度', exact: true });
  const box = await handle.boundingBox();
  assert.ok(box);
  const x = box.x + box.width / 2;
  await page.mouse.move(x, 180);
  await page.mouse.down();
  return { handle, x };
}

async function sizeWindow(electron, page, width, height) {
  const actual = await electron.evaluate(({ BrowserWindow }, size) => {
    const window = BrowserWindow.getAllWindows()[0];
    window.setSize(size.width, size.height);
    return { requested: size, bounds: window.getBounds(), content: window.getContentBounds() };
  }, { width, height });
  await page.waitForFunction(content => Math.abs(innerWidth - content.width) <= 2 && Math.abs(innerHeight - content.height) <= 2, actual.content);
  return actual;
}

exports.checkRestore = async ({ page, attempt, record, launch }) => {
  launch.sidebarRestored = await observe(page);
  if (attempt !== 2) return;
  assert.deepEqual(launch.sidebarRestored.saved, { version: 1, sidebarWidth: 320, sidebarCollapsed: true });
  assert.equal(launch.sidebarRestored.sidebarWidth, 75);
  record.sidebar.checks.push('Saved width and collapse restored in a new original desktop/core/Worker launch');
};

exports.checkLaunch = async ({ page, electron, attempt, record, launch, reports }) => {
  const checks = record.sidebar.checks, facts = { attempt };
  launch.sidebar = facts;
  facts.largeWindow = await sizeWindow(electron, page, 1280, 850);
  assert.ok(facts.largeWindow.content.width >= 1040, 'Declared desktop size must allow the tested 320px sidebar and 720px content');
  const handle = page.getByRole('separator', { name: '调整侧栏宽度', exact: true });
  if (attempt === 1) {
    await handle.focus();
    await page.keyboard.press('Home');
    await waitWidth(page, 200);
    const start = await press(page);
    await page.mouse.move(start.x + 120, 210, { steps: 8 });
    await waitWidth(page, 320);
    facts.beforeCommit = await observe(page);
    assert.equal(facts.beforeCommit.saved.sidebarWidth, 200);
    await page.mouse.up();
    facts.committed = await observe(page);
    assert.equal(facts.committed.saved.sidebarWidth, 320);
    assert.equal(facts.committed.mainLeft, 320);
    assert.ok(facts.committed.mainWidth >= 720);
    assert.ok(facts.committed.documentWidth <= facts.committed.viewport.width);
    checks.push('Actual file-origin pointer drag commits only on release and aligns the content');
    await page.screenshot({ path: path.join(reports, 'launch-1-sidebar-320.png'), fullPage: true });

    const originalStyle = await page.evaluate(() => ({ cursor: document.body.style.cursor, userSelect: document.body.style.userSelect }));
    const cancel = await press(page);
    await page.mouse.move(cancel.x + 60, 210, { steps: 5 });
    await waitWidth(page, 380);
    await page.keyboard.press('Escape');
    await page.mouse.up();
    await waitWidth(page, 320);
    facts.cancelled = await observe(page);
    assert.equal(facts.cancelled.saved.sidebarWidth, 320);
    assert.equal(facts.cancelled.cursor, originalStyle.cursor);
    assert.equal(facts.cancelled.userSelect, originalStyle.userSelect);
    checks.push('Escape restores actual desktop width, body styles and persisted intent');

    const reload = await press(page);
    await page.mouse.move(reload.x + 50, 210, { steps: 5 });
    await waitWidth(page, 370);
    await page.reload();
    await page.mouse.up();
    await page.getByRole('button', { name: '本地核心已连接', exact: true }).waitFor();
    await waitWidth(page, 320);
    facts.reloaded = await observe(page);
    assert.equal(facts.reloaded.saved.sidebarWidth, 320);
    assert.equal(facts.reloaded.cursor, '');
    assert.equal(facts.reloaded.userSelect, '');
    checks.push('Renderer reload during actual captured drag restores the last committed layout');

    await handle.focus();
    await page.keyboard.press('Enter');
    await waitWidth(page, 75);
    facts.collapsed = await observe(page);
    assert.equal(facts.collapsed.saved.sidebarCollapsed, true);
    assert.equal(facts.collapsed.saved.sidebarWidth, 320);
    checks.push('Keyboard collapse retains the expanded width for complete desktop restart');
    await page.screenshot({ path: path.join(reports, 'launch-1-sidebar-collapsed.png'), fullPage: true });
  } else {
    await handle.focus();
    await page.keyboard.press('Enter');
    await waitWidth(page, 320);
    facts.expanded = await observe(page);
    assert.equal(facts.expanded.saved.sidebarWidth, 320);
    checks.push('Keyboard expansion after full original restart restores the saved width');

    const minimum = await electron.evaluate(({ BrowserWindow }) => BrowserWindow.getAllWindows()[0].getMinimumSize());
    facts.minimumWindow = await sizeWindow(electron, page, minimum[0], minimum[1]);
    await page.waitForFunction(() => document.querySelector('.sidebar').getBoundingClientRect().width <= innerWidth - 720);
    facts.minimumLayout = await observe(page);
    assert.ok(facts.minimumLayout.sidebarWidth <= 320);
    assert.ok(facts.minimumLayout.mainWidth >= 720);
    assert.equal(facts.minimumLayout.mainLeft, facts.minimumLayout.sidebarWidth);
    assert.equal(facts.minimumLayout.saved.sidebarWidth, 320);
    assert.ok(facts.minimumLayout.documentWidth <= facts.minimumLayout.viewport.width);
    checks.push('Actual minimum native window clamps layout while retaining the larger intent');
    await press(page);
    await page.mouse.up();
    assert.equal((await observe(page)).saved.sidebarWidth, 320);
    checks.push('Unmoved click in the minimum native window preserves the saved width');
    await page.screenshot({ path: path.join(reports, 'launch-2-sidebar-minimum.png'), fullPage: true });

    await sizeWindow(electron, page, 1280, 850);
    await waitWidth(page, 320);
    checks.push('Re-enlarging the native window restores the intended width');
    await page.getByRole('button', { name: '资源与设置', exact: true }).click();
    await page.getByRole('button', { name: '外观', exact: true }).click();
    await page.getByRole('button', { name: '恢复默认布局', exact: true }).click();
    await page.waitForFunction(() => document.querySelector('.sidebar').getBoundingClientRect().width === (innerWidth <= 1200 ? 215 : 244));
    facts.defaults = await observe(page);
    assert.equal(facts.defaults.saved.sidebarWidth, null);
    assert.equal(facts.defaults.saved.sidebarCollapsed, false);
    checks.push('Appearance reset restores the responsive desktop defaults');
    await page.screenshot({ path: path.join(reports, 'launch-2-sidebar-default.png'), fullPage: true });
  }
};

exports.archive = ({ root, reports, record }) => {
  const hash = bytes => createHash('sha256').update(bytes).digest('hex');
  const sources = ['web/src/SidebarLayout.tsx', 'web/src/App.tsx', 'web/src/Appearance.tsx', 'web/src/layout.css',
    'tools/layout-desktop-checks.cjs', 'tools/layout-desktop-smoke.cjs', 'tools/theme-desktop-smoke.cjs'].map(file => {
    const bytes = readFileSync(path.join(root, file));
    return { file, sha256: hash(bytes), normalizedLfSha256: hash(Buffer.from(bytes.toString('utf8').replaceAll('\r\n', '\n'))) };
  });
  const assets = [];
  const walk = directory => {
    for (const entry of readdirSync(directory, { withFileTypes: true })) {
      const file = path.join(directory, entry.name);
      if (entry.isDirectory()) walk(file);
      else assets.push({ file: path.relative(root, file).replaceAll('\\', '/'), sha256: hash(readFileSync(file)) });
    }
  };
  walk(path.join(root, 'web', 'dist'));
  const sidebar = { ...record.sidebar, version: record.version, platform: record.platform, status: record.status,
    scope: 'ACTUAL_FILE_ORIGIN_SIDEBAR_POINTER_KEYBOARD_MINIMUM_WINDOW_AND_COMPLETE_RESTART',
    completedAt: record.completedAt, externalJavaDisabled: record.externalJavaDisabled,
    originalProcessesStopped: record.originalProcessesStopped === true, probeFixture: record.probeFixture,
    launches: record.launches.map(launch => ({ attempt: launch.attempt, restored: launch.sidebarRestored, layout: launch.sidebar,
      application: launch.application, apiVersions: launch.apiVersions, pageErrors: launch.pageErrors, originalProcesses: launch.originalProcesses,
      originalProcessesStopped: launch.originalProcessesStopped, historicalOriginalProcessObservations: launch.historicalOriginalProcessObservations })),
    sources, assets, coreJarSha256: hash(readFileSync(path.join(root, 'core', 'target', `agent-workbench-core-${record.version}.jar`))),
    ...(record.error ? { error: record.error } : {}) };
  for (const filename of ['layout-desktop-checks.cjs', 'layout-desktop-smoke.cjs'])
    copyFileSync(path.join(root, 'tools', filename), path.join(reports, filename));
  writeFileSync(path.join(reports, 'sidebar-report.json'), JSON.stringify(sidebar, null, 2) + '\n');
  writeFileSync(path.join(root, 'docs', 'qa', `sidebar-${record.externalJavaDisabled ? 'packaged' : 'desktop'}-${record.platform}.json`), JSON.stringify(sidebar, null, 2) + '\n');
};
