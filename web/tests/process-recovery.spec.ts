import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';

async function fixture(page: Page, mode: 'live' | 'stopped' | 'unknown' | 'reused' = 'live') {
  const process: any = { id: 'original-process-observation', pid: 9101, processStartedAt: mode === 'unknown' ? '' : '2026-11-08T01:02:03.004Z', origin: 'WORKER_PIPE', observation: mode === 'stopped' ? 'EXITED' : mode === 'reused' ? 'PID_REUSED' : mode === 'unknown' ? 'UNVERIFIABLE' : 'RUNNING' };
  if (mode === 'reused') process.currentProcessStartedAt = '2026-11-08T02:03:04.005Z';
  const session: any = { id: `original-${'session-source-'.repeat(5)}`, type: mode === 'unknown' || mode === 'reused' ? 'MCP_STDIO' : 'WORKER', state: 'UNKNOWN', pid: process.pid, processStartedAt: process.processStartedAt, workerId: 'original-worker-source', serverId: mode === 'unknown' || mode === 'reused' ? 'original-mcp-service-source' : '', taskId: 'original-task-source', coverage: mode === 'unknown' ? 'LEGACY_NO_LIST' : 'OBSERVED_ONLY', launchReceiptMissing: mode === 'unknown', observedProcessesStopped: ['stopped', 'reused'].includes(mode), observedProcessCount: 1, unconfirmedProcessCount: ['stopped', 'reused'].includes(mode) ? 0 : 1, processes: ['stopped', 'reused'].includes(mode) ? [] : [process], recordedProcesses: [process], processesTruncated: false };
  const state: any = { projects: [], tasks: [], routes: [], memories: [], profiles: [], agentDefinitions: [], executionNodes: [], repositoryBindings: [], workers: [], processRecovery: { dispatchAllowed: false, pendingCount: 1, sessions: [session], hasMore: false }, system: { version: 'recovery-protocol-fixture', storageHealthy: true } };
  const reviewPosts: any[] = [], stopPosts: any[] = [], saved: any[] = [], errors: string[] = [];
  let rejectNext = false, loseNextReply = false;
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async intercepted => {
    const request = intercepted.request(), path = decodeURIComponent(new URL(request.url()).pathname);
    if (path === '/api/state') return intercepted.fulfill({ json: state });
    if (path === '/api/process-recovery') return intercepted.fulfill({ json: state.processRecovery });
    if (path === `/api/process-recovery/${session.id}/stop`) {
      stopPosts.push(request.postDataJSON());
      if (process.observation === 'RUNNING') { process.observation = 'EXITED'; session.processes = []; session.unconfirmedProcessCount = 0; session.observedProcessesStopped = true; }
      return intercepted.fulfill({ json: session });
    }
    if (path === `/api/process-recovery/${session.id}/reconcile`) {
      const body = request.postDataJSON(); reviewPosts.push({ id: session.id, ...body });
      if (rejectNext || session.unconfirmedProcessCount) { rejectNext = false; return intercepted.fulfill({ status: 409, json: { message: '原进程仍在运行或观察失败，不能接受停止声明' } }); }
      if (saved.length && saved[0].note !== body.note) return intercepted.fulfill({ status: 409, json: { message: '不能覆盖原核对依据' } });
      if (!saved.length) saved.push({ ...body, id: session.id });
      session.state = 'RECONCILED'; state.processRecovery.sessions = []; state.processRecovery.pendingCount = 0; state.processRecovery.dispatchAllowed = true;
      if (loseNextReply) { loseNextReply = false; return intercepted.abort('failed'); }
      return intercepted.fulfill({ json: session });
    }
    return intercepted.fulfill({ json: {} });
  });
  return { session, process, state, reviewPosts, stopPosts, saved, errors, reject: () => { rejectNext = true; }, loseReply: () => { loseNextReply = true; } };
}

async function openRecovery(page: Page) {
  await page.goto('/'); await page.getByRole('button', { name: '资源与设置', exact: true }).click();
  await page.getByRole('button', { name: '执行节点与仓库', exact: true }).click();
  await page.getByRole('region', { name: '旧进程核验' }).waitFor();
}

for (const width of [1440, 430]) {
  test(`旧进程停止门禁与轮询输入保全 ${width}px`, async ({ page }) => {
    await page.setViewportSize({ width, height: 1000 }); const f = await fixture(page); await openRecovery(page);
    const panel = page.getByRole('region', { name: '旧进程核验' });
    await expect(panel.getByText('原进程仍在运行', { exact: true })).toBeVisible();
    await page.screenshot({ path: `qa/process-recovery-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true });
    await panel.getByRole('button', { name: '记录核对依据', exact: true }).click(); const dialog = page.getByRole('dialog');
    await dialog.getByLabel('我已核对旧执行和已记录进程停止').check(); await dialog.getByLabel('我已核对清单未列出的相关进程').check();
    const note = '保留原 PID 与出生时间；<script>literal note</script>；核对了未列出的进程';
    await dialog.getByLabel('旧进程核对依据', { exact: true }).fill(note);
    await expect(dialog.getByRole('button', { name: '保存旧进程核对', exact: true })).toBeDisabled(); expect(f.reviewPosts).toEqual([]);
    f.process.observation = 'EXITED'; f.session.processes = []; f.session.unconfirmedProcessCount = 0; f.session.observedProcessesStopped = true;
    await expect(dialog.getByRole('button', { name: '保存旧进程核对', exact: true })).toBeEnabled();
    await expect(dialog.getByLabel('旧进程核对依据', { exact: true })).toHaveValue(note);
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
    await page.screenshot({ path: `qa/process-recovery-dialog-${width === 430 ? 'narrow' : 'desktop'}.png`, fullPage: true });
    await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).click(); await expect(dialog).toBeHidden();
    expect(f.reviewPosts).toEqual([{ id: f.session.id, processesStopped: true, coverageChecked: true, note }]); expect(f.saved).toHaveLength(1);
    expect(f.stopPosts).toEqual([]); expect(f.errors).toEqual([]);
  });
}

test('出生时间缺失阻止勾选核对，停止请求也不能补造出生', async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 1000 }); const f = await fixture(page, 'unknown'); await openRecovery(page);
  const panel = page.getByRole('region', { name: '旧进程核验' }); await expect(panel.getByText('出生时间无法核验', { exact: true })).toBeVisible();
  await panel.getByRole('button', { name: '请求停止原进程', exact: true }).click(); expect(f.stopPosts).toHaveLength(1);
  await panel.getByRole('button', { name: '记录核对依据', exact: true }).click();
  await expect(page.getByRole('dialog').getByRole('button', { name: '保存旧进程核对', exact: true })).toBeDisabled();
  await page.getByRole('dialog').getByRole('button', { name: '取消', exact: true }).click();
  expect(f.session.processStartedAt).toBe(''); expect(f.reviewPosts).toEqual([]); expect(f.errors).toEqual([]);
});

test('PID 复用保留原出生和新出生信息，原停止按钮不可用', async ({ page }) => {
  await page.setViewportSize({ width: 430, height: 1000 }); const f = await fixture(page, 'reused'); await openRecovery(page);
  const panel = page.getByRole('region', { name: '旧进程核验' });
  await panel.getByRole('button', { name: '重新核验进程', exact: true }).click();
  await expect(panel.getByText('原 PID 已复用', { exact: true })).toBeVisible(); await expect(panel.getByText('2026-11-08T02:03:04.005Z', { exact: true })).toBeVisible();
  await expect(panel.getByRole('button', { name: '请求停止原进程', exact: true })).toBeDisabled(); expect(f.reviewPosts).toEqual([]); expect(f.errors).toEqual([]);
});

test('服务端拒绝陈旧停止声明时保留核对依据，不自动提交', async ({ page }) => {
  const f = await fixture(page, 'stopped'); f.reject(); await openRecovery(page);
  await page.getByRole('button', { name: '记录核对依据', exact: true }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('我已核对旧执行和已记录进程停止').check(); await dialog.getByLabel('我已核对清单未列出的相关进程').check();
  await dialog.getByLabel('旧进程核对依据', { exact: true }).fill('我的原核对记录必须保留');
  await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).click();
  await expect(dialog.getByRole('alert')).toContainText('不能接受停止声明'); await expect(dialog.getByLabel('旧进程核对依据', { exact: true })).toHaveValue('我的原核对记录必须保留');
  await page.waitForTimeout(1400); expect(f.reviewPosts).toHaveLength(1); expect(f.saved).toEqual([]); expect(f.errors).toEqual([]);
});

test('核对记录回执丢失后，显式提交仍沿原会话与原依据', async ({ page }) => {
  const f = await fixture(page, 'stopped'); f.loseReply(); await openRecovery(page);
  await page.getByRole('button', { name: '记录核对依据', exact: true }).click(); const dialog = page.getByRole('dialog');
  await dialog.getByLabel('我已核对旧执行和已记录进程停止').check(); await dialog.getByLabel('我已核对清单未列出的相关进程').check();
  await dialog.getByLabel('旧进程核对依据', { exact: true }).fill('原会话核对依据'); await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).click();
  await expect(dialog.getByRole('alert')).toBeVisible(); await expect(dialog.getByLabel('旧进程核对依据', { exact: true })).toHaveValue('原会话核对依据');
  expect(f.reviewPosts).toHaveLength(1); expect(f.saved).toHaveLength(1);
  await dialog.getByRole('button', { name: '保存旧进程核对', exact: true }).click(); await expect(dialog).toBeHidden();
  expect(f.reviewPosts).toHaveLength(2); expect(f.reviewPosts[1]).toEqual(f.reviewPosts[0]); expect(f.saved).toHaveLength(1); expect(f.errors).toEqual([]);
});
