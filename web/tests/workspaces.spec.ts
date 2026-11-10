import { expect, test } from '@playwright/test';
import { featureHead, fillWorkspaceTask, head, openWorkspaceTask, otherHead, remoteRef, workspaceFixture } from './fixtures/workspaces';

for (const width of [1440, 430]) {
  const size = width === 430 ? 'narrow' : 'desktop';
  test(`创建 worktree 携带真实分支、固定提交与纯文本引用 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page); await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = await fillWorkspaceTask(page);
    await expect(dialog.getByLabel('启动提交')).toBeEnabled(); await dialog.getByLabel('任务现场', { exact: true }).selectOption('WORKTREE'); await dialog.getByLabel('启动提交').selectOption(remoteRef);
    await expect(dialog.getByText(featureHead, { exact: true })).toBeVisible(); expect(await dialog.locator('.workspace-picker script').count()).toBe(0);
    await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.posts[0]).toMatchObject({ repositoryBindingId: 'binding-a', bindingVersion: 1, nodeVersion: 1, includeChanges: false,
      workspaceSelection: { strategy: 'WORKTREE', ref: remoteRef, expectedHead: featureHead, sourceVersion: 'a'.repeat(64), sourceIndex: 'b'.repeat(64) } });
    const contract = page.getByRole('region', { name: '固定任务现场' }); await expect(contract.getByText('origin/review<script>literal</script>', { exact: true })).toBeVisible(); await expect(contract.getByText(featureHead, { exact: true })).toBeVisible();
    expect(await contract.locator('script').count()).toBe(0); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await contract.scrollIntoViewIfNeeded(); await page.screenshot({ path: `qa/workspace-created-${size}.png` }); expect(fixture.errors).toEqual([]);
  });

  test(`源选择过期后刷新保留任务输入和现场策略 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page); await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = await fillWorkspaceTask(page);
    await expect(dialog.getByLabel('启动提交')).toBeEnabled(); await dialog.getByLabel('任务现场', { exact: true }).selectOption('WORKTREE'); await dialog.getByLabel('启动提交').selectOption('refs/heads/feature');
    fixture.options['binding-a'].source.version = 'd'.repeat(64); fixture.options['binding-a'].source.index = 'e'.repeat(64);
    await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog.getByRole('alert')).toContainText('仓库提交、文件或暂存区已变化');
    const queriesBeforeRefresh = fixture.optionQueries.length; await dialog.getByRole('button', { name: '刷新启动选择' }).click(); await expect.poll(() => fixture.optionQueries.length).toBeGreaterThan(queriesBeforeRefresh); await expect(dialog.getByLabel('启动提交')).toBeEnabled();
    await expect(dialog.getByLabel('任务名称')).toHaveValue('保留输入的启动计划'); await expect(dialog.getByLabel('你希望完成什么？')).toHaveValue('固定当前选择并核对源内容'); await expect(dialog.getByLabel('任务现场', { exact: true })).toHaveValue('WORKTREE'); await expect(dialog.getByLabel('启动提交')).toHaveValue('refs/heads/feature');
    await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog).toHaveCount(0); expect(fixture.posts).toHaveLength(2);
    expect(fixture.posts[1].workspaceSelection).toMatchObject({ sourceVersion: 'd'.repeat(64), sourceIndex: 'e'.repeat(64), expectedHead: featureHead, strategy: 'WORKTREE' }); expect(fixture.errors).toEqual([]);
  });

  test(`切换仓库等待新选择时不能发送旧绑定参数 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page); let release!: () => void; fixture.gates['binding-b'] = new Promise(resolve => { release = resolve; });
    await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = await fillWorkspaceTask(page); await expect(dialog.getByLabel('启动提交')).toBeEnabled();
    await dialog.getByLabel('执行仓库绑定').selectOption('binding-b'); await expect(dialog.getByLabel('启动提交')).toBeDisabled(); await dialog.getByRole('button', { name: '生成任务计划' }).click();
    await expect(dialog.getByRole('alert')).toContainText('当前仓库的有效启动提交'); expect(fixture.posts).toHaveLength(0); release(); await expect(dialog.getByLabel('启动提交')).toBeEnabled();
    await expect(dialog.getByText(otherHead, { exact: true })).toBeVisible(); await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.posts[0]).toMatchObject({ repositoryBindingId: 'binding-b', workspaceSelection: { ref: 'HEAD', expectedHead: otherHead } }); expect(fixture.errors).toEqual([]);
  });

  test(`带入修改仅能使用当前 HEAD 并保留显式选择 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page); fixture.options['binding-a'].source.dirty = true;
    await page.goto('/'); await page.getByRole('button', { name: '新建任务', exact: true }).click(); const dialog = await fillWorkspaceTask(page); await expect(dialog.getByLabel('启动提交')).toBeEnabled();
    await dialog.getByRole('checkbox', { name: /带入当前未提交修改/ }).check(); await dialog.getByLabel('启动提交').selectOption('refs/heads/feature');
    await expect(dialog.getByRole('checkbox', { name: /带入当前未提交修改/ })).toBeDisabled(); await expect(dialog.getByRole('checkbox', { name: /带入当前未提交修改/ })).not.toBeChecked();
    await dialog.getByRole('button', { name: '生成任务计划' }).click(); expect(fixture.posts).toHaveLength(0); await expect(dialog.getByRole('alert')).toContainText('当前仓库的有效启动提交');
    await dialog.getByLabel('启动提交').selectOption('HEAD'); await dialog.getByRole('checkbox', { name: /带入当前未提交修改/ }).check(); await dialog.getByRole('button', { name: '生成任务计划' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.posts[0]).toMatchObject({ includeChanges: true, workspaceSelection: { ref: 'HEAD', expectedHead: head } }); expect(fixture.errors).toEqual([]);
  });

  test(`准备后的归属与旧选择保持固定并按纯文本展示 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page), original = { ...fixture.task.workspaceSelection };
    fixture.task.workspace = `C:\\data\\${'long-workspace-'.repeat(8)}\\work`; fixture.task.workspaceSelection = { ...original, version: 2, strategy: 'WORKTREE', ref: remoteRef, baseline: featureHead };
    fixture.task.workspaceSelectionHistory = [{ selection: original, actor: 'USER', createdAt: '2026-11-09T00:00:00Z' }];
    fixture.task.workspaceOwnership = { schemaVersion: 1, taskId: fixture.task.id, strategy: 'WORKTREE', owner: 'original-owner<script>literal</script>', sourcePath: fixture.task.execution.sourcePath,
      gitDirectory: `${fixture.task.execution.sourcePath}\\.git\\worktrees\\original`, commonDirectory: `${fixture.task.execution.sourcePath}\\.git`, lockReason: 'original lock' };
    await openWorkspaceTask(page); const contract = page.getByRole('region', { name: '固定任务现场' }); await expect(contract.getByText('original-owner<script>literal</script>', { exact: true })).toBeVisible();
    await expect(contract.getByRole('button', { name: '重新确认启动选择' })).toHaveCount(0); await contract.getByText('之前的启动选择（1）').click(); await expect(contract.getByText(/v1 · 独立 clone · 创建时的 HEAD/)).toBeVisible();
    expect(await contract.locator('script').count()).toBe(0); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true); await contract.scrollIntoViewIfNeeded(); await page.screenshot({ path: `qa/workspace-owned-${size}.png` });
    await page.getByRole('button', { name: '更新执行授权' }).click(); await expect(page.getByRole('dialog').getByLabel('启动提交')).toHaveCount(0); expect(fixture.optionQueries).toHaveLength(0); expect(fixture.errors).toEqual([]);
  });

  test(`已有准备记录但缺任务回执时保留选择并更新同源授权 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page);
    fixture.task.workspacePreparationPresent = true; const original = { ...fixture.task.workspaceSelection };
    await openWorkspaceTask(page); const contract = page.getByRole('region', { name: '固定任务现场' });
    await expect(contract.getByText('已有现场准备记录，原选择保持固定；继续前请核对原执行和现场。')).toBeVisible();
    await expect(contract.getByRole('button', { name: '重新确认启动选择' })).toHaveCount(0);
    await page.getByRole('button', { name: '更新执行授权' }).click(); const dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('启动提交')).toHaveCount(0); await expect(dialog.getByRole('button', { name: '确认新的执行版本' })).toBeEnabled();
    await dialog.getByRole('button', { name: '确认新的执行版本' }).click(); await expect(dialog).toHaveCount(0);
    expect(fixture.authorizations[0]).toMatchObject({ expectedVersion: 1, repositoryBindingId: 'binding-a' });
    expect(fixture.authorizations[0].workspaceSelection).toBeUndefined(); expect(fixture.task.workspaceSelection).toEqual(original);
    expect(fixture.optionQueries).toHaveLength(0); expect(fixture.errors).toEqual([]); expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
  });

  test(`两种授权弹窗保留打开时的版本并拒绝轮询绕过冲突 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const fixture = await workspaceFixture(page);
    fixture.task.workspaceSelection = { ...fixture.task.workspaceSelection, ref: 'refs/heads/feature', baseline: featureHead, includeChanges: true, source: { ...fixture.task.workspaceSelection.source, head: featureHead, dirty: true } };
    await openWorkspaceTask(page); await page.getByRole('button', { name: '重新确认启动选择' }).click(); let dialog = page.getByRole('dialog');
    await expect(dialog.getByLabel('启动提交')).toBeEnabled(); await expect(dialog.getByRole('button', { name: '确认新的启动选择' })).toBeEnabled(); await dialog.getByLabel('任务现场', { exact: true }).selectOption('WORKTREE'); await dialog.getByLabel('启动提交').selectOption('refs/heads/feature');
    fixture.task.workspaceSelection = { ...fixture.task.workspaceSelection, version: 2 }; await page.waitForTimeout(3300);
    await dialog.getByRole('button', { name: '确认新的启动选择' }).click(); await expect(dialog.getByRole('alert')).toHaveText('启动选择版本已变化，请刷新后确认'); expect(fixture.selections[0].expectedVersion).toBe(1);
    await expect(dialog.getByLabel('任务现场', { exact: true })).toHaveValue('WORKTREE'); await expect(dialog.getByLabel('启动提交')).toHaveValue('refs/heads/feature'); await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '更新执行授权' }).click(); dialog = page.getByRole('dialog'); await expect(dialog.getByLabel('启动提交')).toBeEnabled();
    fixture.task.execution = { ...fixture.task.execution, version: 2 }; fixture.task.workspaceSelection = { ...fixture.task.workspaceSelection, version: 3 }; await page.waitForTimeout(3300);
    await dialog.getByRole('button', { name: '确认新的执行版本' }).click(); await expect(dialog.getByRole('alert')).toHaveText('任务授权或启动选择版本已变化，请刷新后确认');
    expect(fixture.authorizations[0]).toMatchObject({ expectedVersion: 1, expectedWorkspaceVersion: 2 }); await dialog.getByRole('button', { name: '取消', exact: true }).click();
    await page.getByRole('button', { name: '重新确认启动选择' }).click(); dialog = page.getByRole('dialog'); await expect(dialog.getByLabel('启动提交')).toBeEnabled(); await dialog.getByLabel('任务现场', { exact: true }).selectOption('WORKTREE');
    await dialog.getByRole('button', { name: '确认新的启动选择' }).click(); await expect(dialog).toHaveCount(0); expect(fixture.selections[1].expectedVersion).toBe(3); expect(fixture.task.workspaceSelectionHistory[0].selection.version).toBe(3); expect(fixture.task.budget.usedCalls).toBe(3); expect(fixture.errors).toEqual([]);
  });
}
