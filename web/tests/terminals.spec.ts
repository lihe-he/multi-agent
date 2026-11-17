import { createHash, randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';
import { openWorkspaceTask, workspaceFixture } from './fixtures/workspaces';

// Browser protocol fixtures only; actual native shell/PID/core-loss evidence is verified independently.
const digest = (bytes: Buffer) => createHash('sha256').update(bytes).digest('hex');
const originalText = 'ORIGINAL_PTY_中文_\u001b[32mGREEN\u001b[0m\r\n<script>window.__terminalMarkupExecuted=true</script>\r\n';
function contrast(foreground: string, background: string) {
  const luminance = (value: string) => {
    const components = value.match(/^rgb\((\d+), (\d+), (\d+)\)$/); expect(components).not.toBeNull();
    const linear = components!.slice(1).map(part => Number(part) / 255).map(part => part <= 0.04045 ? part / 12.92 : ((part + 0.055) / 1.055) ** 2.4);
    return linear[0] * 0.2126 + linear[1] * 0.7152 + linear[2] * 0.0722;
  };
  const front = luminance(foreground), back = luminance(background); return (Math.max(front, back) + 0.05) / (Math.min(front, back) + 0.05);
}
async function fixture(page: Page, options: { supported?: boolean; empty?: boolean } = {}) {
  const base = await workspaceFixture(page); await page.addInitScript(() => sessionStorage.setItem('workbench-token', 'terminal-ui-protocol-fixture'));
  const original: any = { id: randomUUID(), protocolVersion: 1, taskId: base.task.id, projectId: base.task.projectId, startRequestId: randomUUID(),
    workspace: 'D:\\fixture\\中文 original work', recipe: 'SYSTEM_CMD', epoch: randomUUID(), state: 'RUNNING', columns: 80, rows: 24,
    createdAt: '2026-11-16T00:00:00Z', historyCursor: 100, controllerGeneration: 0, lastActionSequence: 0, inputFenced: false, outputEnd: Buffer.byteLength(originalText), outputLimit: 67_108_864, outputSegments: 1, outputIntegrity: 'LIVE',
    nativeProcess: { pid: 12345, processStartedAt: '2026-11-16T00:00:00Z', backend: 'BROWSER_PROTOCOL_FIXTURE' }, source: { workerId: 'original-fixture-worker', executorPid: 23456 } };
  const settings: any = { bytes: Buffer.from(originalText), frameBytes: 65536, loseAck: false, unknownAction: false, loseStart: false, corruptAfter: -1, stopped: false };
  const sessions: any[] = options.empty ? [] : [original], actions = new Map<string, any>(), requests: any[] = [];
  base.task.terminalProtocolVersion = options.supported === false ? undefined : 1; base.task.workspace = original.workspace; base.task.version = 'a'.repeat(64);
  base.task.state = options.empty ? 'PAUSED' : 'RUNNING'; base.task.pendingTerminalId = options.empty ? undefined : original.id; base.task.terminal = options.empty ? undefined : original;
  let secret = '', actionGate: Promise<void> | undefined, outputGate: Promise<void> | undefined, startGate: Promise<void> | undefined;
  const record = (request: any, body?: unknown) => requests.push({ method: request.method(), path: new URL(request.url()).pathname, query: new URL(request.url()).search,
    authorization: request.headers().authorization, body });
  await page.route('**/api/tasks/*/terminals**', async route => {
    const request = route.request(), url = new URL(request.url()); record(request, request.method() === 'POST' ? request.postDataJSON() : undefined);
    if (request.method() === 'POST') {
      const body = request.postDataJSON(); original.startRequestId = body.requestId; sessions.unshift(original); base.task.pendingTerminalId = original.id; base.task.terminal = original; base.task.state = 'RUNNING';
      if (startGate) await startGate;
      if (settings.loseStart) { settings.loseStart = false; return route.abort(); } return route.fulfill({ json: original });
    }
    const before = Number(url.searchParams.get('before') || 0), matching = sessions.filter(session => !before || session.historyCursor < before), selected = matching.slice(0, 30);
    return route.fulfill({ json: { terminals: selected, cursor: selected.at(-1)?.historyCursor || 100, hasMore: matching.length > selected.length } });
  });
  await page.route('**/api/terminals/**', async route => {
    const request = route.request(), url = new URL(request.url()), body = request.method() === 'POST' ? request.postDataJSON() : undefined; record(request, body);
    if (url.pathname.endsWith('/output')) {
      if (outputGate) await outputGate;
      const after = Number(url.searchParams.get('after')), limit = Math.min(Number(url.searchParams.get('limit')), settings.frameBytes), bytes = settings.bytes.subarray(after, after + limit);
      settings.outputDeliveries = (settings.outputDeliveries || 0) + 1;
      return route.fulfill({ json: { terminalId: original.id, epoch: settings.outputEpoch || original.epoch, offset: after, length: bytes.length, next: after + bytes.length,
        sha256: settings.corruptAfter >= 0 && after >= settings.corruptAfter ? '0'.repeat(64) : digest(bytes), dataBase64: bytes.toString('base64'), publishedEnd: settings.bytes.length,
        hasMore: after + bytes.length < settings.bytes.length, terminalState: original.state, outputIntegrity: original.outputIntegrity } });
    }
    if (url.pathname.endsWith('/control')) {
      if (body.expectedGeneration !== original.controllerGeneration) return route.fulfill({ status: 409, json: { message: '控制代数已变化' } });
      original.controllerGeneration++; original.controllerId = body.controllerId; secret = 'c'.repeat(43);
      return route.fulfill({ json: { terminal: original, generation: original.controllerGeneration, controlSecret: secret, nextSequence: original.lastActionSequence + 1 } });
    }
    if (url.pathname.endsWith('/actions') && request.method() === 'POST') {
      if (body.controlSecret !== secret || body.generation !== original.controllerGeneration || body.controllerId !== original.controllerId) return route.fulfill({ status: 403, json: { message: '原控制已失效' } });
      const bytes = body.kind === 'INPUT' ? Buffer.from(body.dataBase64, 'base64') : Buffer.alloc(0), columns = body.kind === 'RESIZE' ? body.columns : 0, rows = body.kind === 'RESIZE' ? body.rows : 0;
      const action: any = { id: `${original.id}:${body.requestId}`, terminalId: original.id, taskId: original.taskId, requestId: body.requestId, kind: body.kind, sequence: body.sequence,
        controllerId: body.controllerId, controllerGeneration: body.generation, length: bytes.length, columns, rows,
        sha256: digest(body.kind === 'INPUT' ? bytes : Buffer.from(`${body.kind}:${columns}:${rows}`)), state: settings.unknownAction && body.kind === 'INPUT' ? 'UNKNOWN' : 'ACKNOWLEDGED',
        dispatchCount: 1, nativeAuthorizationCount: 1, createdAt: '2026-11-16T00:01:00Z', historyCursor: actions.size + 1 };
      original.lastActionSequence = body.sequence; actions.set(body.requestId, action);
      if (body.kind === 'RESIZE') { original.columns = columns; original.rows = rows; }
      if (body.kind === 'STOP') { original.state = 'CANCELLED'; original.inputFenced = true; original.outputIntegrity = 'COMPLETE'; base.task.state = 'PAUSED'; base.task.pendingTerminalId = undefined; }
      if (action.state === 'UNKNOWN') { original.state = 'UNKNOWN'; original.inputFenced = true; original.outputIntegrity = 'UNCONFIRMED'; }
      if (body.kind === 'INPUT' && actionGate) await actionGate;
      if (body.kind === 'INPUT' && settings.loseAck) { settings.loseAck = false; return route.abort(); }
      return route.fulfill({ json: action });
    }
    if (/\/actions\/[^/]+$/.test(url.pathname)) {
      const found = actions.get(url.pathname.split('/').at(-1)!); return found ? route.fulfill({ json: found }) : route.fulfill({ status: 404, json: { message: '原动作尚不可读取' } });
    }
    if (url.pathname.endsWith('/actions')) {
      const before = Number(url.searchParams.get('before') || 0), matching = [...actions.values()].reverse().filter(action => !before || action.historyCursor < before), selected = matching.slice(0, 30);
      return route.fulfill({ json: { actions: selected, cursor: selected.at(-1)?.historyCursor || before, hasMore: matching.length > selected.length } });
    }
    return route.fulfill({ json: settings.metadataOverride || original });
  });
  return { ...base, original, settings, sessions, actions, requests, setActionGate: (gate?: Promise<void>) => { actionGate = gate; },
    setOutputGate: (gate?: Promise<void>) => { outputGate = gate; }, setStartGate: (gate?: Promise<void>) => { startGate = gate; } };
}
async function open(page: Page) { await openWorkspaceTask(page); await page.getByRole('button', { name: '终端', exact: true }).click(); return page.locator('.task-terminals'); }
async function control(page: Page, panel: any) {
  await expect(panel.locator('.terminal-output-status')).toContainText('正在读取原输出'); await panel.getByRole('button', { name: '取得控制', exact: true }).click();
  await expect(panel.locator('.terminal-mode')).toHaveText('当前窗口控制'); await expect(page.getByRole('textbox', { name: '终端输入', exact: true })).toBeEditable();
}
async function paste(page: Page, text: string) {
  await page.getByRole('textbox', { name: '终端输入', exact: true }).evaluate((element, text) => {
    const data = new DataTransfer(); data.setData('text/plain', text); element.dispatchEvent(new ClipboardEvent('paste', { clipboardData: data, bubbles: true, cancelable: true }));
  }, text);
}

test('旧核心显示终端不可用，查看不派发任何终端请求', async ({ page }) => {
  const source = await fixture(page, { supported: false }), panel = await open(page);
  await expect(panel.getByText('当前核心未提供终端能力')).toBeVisible(); expect(source.requests).toEqual([]); expect(source.errors).toEqual([]);
});
test('原终端排队和启动保持只读，原RUNNING回执后才提供显式控制', async ({ page }) => {
  const source = await fixture(page); source.original.state = 'QUEUED'; source.original.outputIntegrity = 'PENDING'; source.settings.bytes = Buffer.alloc(0);
  const panel = await open(page); await expect(panel.locator('.terminal-output-status')).toContainText('等待原终端领取');
  await expect(panel.getByRole('button', { name: '取得控制', exact: true })).toBeDisabled();
  source.original.state = 'STARTING'; await expect(panel.locator('.terminal-output-status')).toContainText('正在启动原终端');
  source.original.state = 'RUNNING'; source.original.outputIntegrity = 'LIVE'; source.settings.bytes = Buffer.from(originalText);
  await expect(panel.locator('.terminal-output-status')).toContainText('正在读取原输出'); await expect(panel.getByRole('button', { name: '取得控制', exact: true })).toBeEnabled();
  expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.errors).toEqual([]);
});
test('原字节跨三字节页保留Unicode与字面控制文本，查看者不调整原尺寸', async ({ page }) => {
  const source = await fixture(page); source.settings.frameBytes = 3; const panel = await open(page);
  await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
  await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_PTY_中文_GREEN'); await expect(panel.locator('.xterm-accessibility')).toContainText('<script>window.__terminalMarkupExecuted=true</script>');
  expect(await page.evaluate(() => (window as any).__terminalMarkupExecuted)).toBeUndefined(); expect(await panel.locator('script,img').count()).toBe(0);
  expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.original.columns).toBe(80); expect(source.original.rows).toBe(24); expect(source.errors).toEqual([]);
});
test('显式控制后发送实测尺寸和Unicode输入，凭据只留当前内存', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel);
  await expect.poll(() => source.requests.filter(request => request.body?.kind === 'RESIZE').length).toBe(1);
  await paste(page, 'echo 中文\r'); await expect.poll(() => source.requests.filter(request => request.body?.kind === 'INPUT').length).toBe(1);
  const input = source.requests.find(request => request.body?.kind === 'INPUT'); expect(Buffer.from(input.body.dataBase64, 'base64').toString('utf8')).toBe('echo 中文\r'); expect(input.authorization).toBe('Bearer terminal-ui-protocol-fixture');
  expect(source.requests.every(request => !request.query.includes('controlSecret') && !request.query.includes('token'))).toBe(true);
  const storage = await page.evaluate(() => JSON.stringify({ local: { ...localStorage }, session: { ...sessionStorage } })); expect(storage).not.toContain('c'.repeat(43)); expect(source.errors).toEqual([]);
});
test('离开、返回和重载只重放保存输出，原shell和已发送输入保持一次', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); await paste(page, 'original-once\r');
  await expect.poll(() => source.actions.size).toBe(2); const pid = source.original.nativeProcess.pid;
  await page.getByRole('button', { name: '代码差异', exact: true }).click(); await page.getByRole('button', { name: '终端', exact: true }).click();
  await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
  await page.reload(); await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '固定启动现场的任务' }).click(); await page.getByRole('button', { name: '终端', exact: true }).click();
  await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1); expect(source.requests.filter(request => request.method === 'POST' && request.path.endsWith('/terminals'))).toHaveLength(0);
  expect(source.original.nativeProcess.pid).toBe(pid); expect(source.requests.filter(request => request.path.endsWith('/output') && request.query.includes('after=0'))).toHaveLength(3); expect(source.errors).toEqual([]);
});
test('输入响应丢失只查询原动作，不重发原输入', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); source.settings.loseAck = true; await paste(page, 'lost-ack-once\r');
  await expect.poll(() => source.requests.filter(request => /\/actions\/[^/]+$/.test(request.path)).length).toBe(1);
  await expect(panel.locator('.terminal-mode')).toHaveText('当前窗口控制'); expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1);
  const action = [...source.actions.values()].find(action => action.kind === 'INPUT'); expect(action.dispatchCount).toBe(1); expect(action.nativeAuthorizationCount).toBe(1); expect(source.errors).toEqual([]);
});
test('UNKNOWN回执清空未发送输入，原编号可只读核对', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); source.settings.unknownAction = true; await paste(page, 'unknown-original\r');
  await expect(panel.getByRole('button', { name: '只核对原动作' })).toBeVisible(); await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
  await panel.getByRole('button', { name: '只核对原动作' }).click(); await expect(panel).toContainText('原动作仍未取得完整确认');
  expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1); await expect(panel.getByRole('button', { name: '接管控制' })).toBeDisabled(); expect(source.errors).toEqual([]);
});
test('坏摘要保留最后排空的字节游标，不跳过原输出缺口', async ({ page }) => {
  const source = await fixture(page); source.settings.frameBytes = 5; source.settings.corruptAfter = 5; const panel = await open(page);
  await expect(panel.getByRole('alert')).toContainText('摘要不一致'); await expect(panel.locator('.terminal-output-status')).toContainText('已排空 5 字节');
  expect(source.requests.filter(request => request.path.endsWith('/output')).map(request => new URLSearchParams(request.query).get('after'))).toEqual(['0', '5']); expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.errors).toEqual([]);
});
test('超过16KiB的Unicode粘贴整次拒绝，不形成输入动作', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); await paste(page, '测'.repeat(6000));
  await expect(panel.getByRole('status').filter({ hasText: '一次输入最多 16 KiB' })).toBeVisible(); expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toEqual([]); expect(source.errors).toEqual([]);
});
test('新建响应丢失后读取原启动记录，禁止建立第二个shell', async ({ page }) => {
  const source = await fixture(page, { empty: true }); source.settings.loseStart = true; const panel = await open(page);
  await panel.getByRole('button', { name: '新建终端', exact: true }).click(); await expect(panel).toContainText('已找到原启动记录'); await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
  expect(source.sessions).toHaveLength(1); expect(source.requests.filter(request => request.method === 'POST' && request.path.endsWith('/terminals'))).toHaveLength(1); expect(source.errors).toEqual([]);
});
test('停止动作与原输出记录保留，完成后不发送后续输入', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); await panel.getByRole('button', { name: '停止原终端', exact: true }).click();
  await expect(panel.locator('.terminal-state')).toHaveText('已停止'); await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
  expect(source.requests.filter(request => request.body?.kind === 'STOP')).toHaveLength(1); await panel.locator('.terminal-actions summary').click(); await expect(panel.locator('.terminal-action-row')).toHaveCount(2); expect(source.errors).toEqual([]);
});

test('真实xterm排空回调被延迟时保持原字节游标和下一页请求', async ({ page }) => {
  await page.addInitScript(() => {
    const original = window.setTimeout.bind(window), held: (() => void)[] = [], state = window as any;
    state.__holdOriginalWriteTimers = false; state.__originalWriteTimers = held;
    window.setTimeout = ((handler: TimerHandler, delay?: number, ...args: unknown[]) => {
      if (state.__holdOriginalWriteTimers && (delay === undefined || delay === 0) && typeof handler === 'function') {
        return original(() => { held.push(() => handler(...args)); }, 0);
      }
      return original(handler, delay, ...args);
    }) as typeof window.setTimeout;
    state.__releaseOriginalWriteTimers = () => { state.__holdOriginalWriteTimers = false; for (const run of held.splice(0)) original(run, 0); };
  });
  const source = await fixture(page); let release!: () => void; source.setOutputGate(new Promise<void>(done => { release = done; }));
  const panel = await open(page); await expect.poll(() => source.requests.filter(request => request.path.endsWith('/output')).length).toBe(1);
  await page.evaluate(() => { (window as any).__holdOriginalWriteTimers = true; }); release();
  await expect.poll(() => page.evaluate(() => (window as any).__originalWriteTimers.length)).toBeGreaterThan(0);
  await expect(panel.locator('.terminal-output-status')).toContainText('已排空 0 字节');
  expect(source.requests.filter(request => request.path.endsWith('/output')).map(request => new URLSearchParams(request.query).get('after'))).toEqual(['0']);
  await page.evaluate(() => (window as any).__releaseOriginalWriteTimers());
  await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
  await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_PTY_中文_GREEN'); expect(source.errors).toEqual([]);
});

test('在途输入保持一次，32KiB未发送队列拒绝额外粘贴并顺序排空', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel);
  await expect.poll(() => source.actions.size).toBe(1);
  let release!: () => void; source.setActionGate(new Promise<void>(done => { release = done; }));
  try {
    await paste(page, 'x'.repeat(16000)); await expect.poll(() => source.requests.filter(request => request.body?.kind === 'INPUT').length).toBe(1);
    await paste(page, 'y'.repeat(16000)); await paste(page, 'z'.repeat(16000)); await paste(page, 'overflow'.repeat(500));
    await expect(panel.locator('.terminal-output-status')).toContainText('待发送 32,000 字节');
    await expect(panel.getByRole('status').filter({ hasText: '待发送输入已达到 32 KiB' })).toBeVisible();
    expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1);
    release(); await expect.poll(() => source.requests.filter(request => request.body?.kind === 'INPUT').length).toBe(3);
    const inputs = source.requests.filter(request => request.body?.kind === 'INPUT');
    expect(inputs.map(request => request.body.sequence)).toEqual([2, 3, 4]);
    expect(inputs.map(request => Buffer.from(request.body.dataBase64, 'base64').toString('utf8'))).toEqual(['x'.repeat(16000), 'y'.repeat(16000), 'z'.repeat(16000)]);
    expect(source.errors).toEqual([]);
  } finally { release(); }
});

test('原输出先报告UNKNOWN时清空未发送输入，即使旧元数据和输入回执仍在途', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); await expect.poll(() => source.actions.size).toBe(1);
  source.settings.metadataOverride = { ...source.original }; source.settings.unknownAction = true;
  let release!: () => void; source.setActionGate(new Promise<void>(done => { release = done; }));
  let releaseOutput!: () => void; source.setOutputGate(new Promise<void>(done => { releaseOutput = done; }));
  try {
    await paste(page, 'original-unknown\r'); await expect.poll(() => source.requests.filter(request => request.body?.kind === 'INPUT').length).toBe(1);
    await paste(page, 'must-remain-unsent\r');
    await expect(panel.locator('.terminal-output-status')).toContainText('待发送'); releaseOutput();
    await expect(panel.locator('.terminal-output-status')).toContainText('查看已保存输出');
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看');
    await expect(panel.locator('.terminal-output-status')).not.toContainText('待发送');
    release(); await expect(panel.getByRole('button', { name: '只核对原动作' })).toBeVisible();
    expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1); expect(source.errors).toEqual([]);
  } finally { releaseOutput(); release(); }
});

test('其他窗口接管时撤去本窗口控制和未发送队列，晚ACK不恢复旧控制', async ({ page }) => {
  const source = await fixture(page), panel = await open(page); await control(page, panel); await expect.poll(() => source.actions.size).toBe(1);
  let release!: () => void; source.setActionGate(new Promise<void>(done => { release = done; }));
  try {
    await paste(page, 'original-owner\r'); await expect.poll(() => source.requests.filter(request => request.body?.kind === 'INPUT').length).toBe(1);
    await paste(page, 'queued-old-owner\r'); source.original.controllerGeneration++; source.original.controllerId = randomUUID();
    await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(panel.locator('.terminal-output-status')).not.toContainText('待发送');
    release(); await expect(panel.getByRole('button', { name: '接管控制', exact: true })).toBeEnabled();
    expect(source.requests.filter(request => request.body?.kind === 'INPUT')).toHaveLength(1); expect(source.errors).toEqual([]);
  } finally { release(); }
});

test('输出epoch变化保留已排空游标，显式重读只查看原shell', async ({ page }) => {
  const source = await fixture(page), panel = await open(page);
  await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
  source.settings.outputEpoch = randomUUID(); await expect(panel.getByRole('alert')).toContainText('epoch');
  await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
  source.settings.outputEpoch = source.original.epoch; await panel.getByRole('button', { name: '从原保存起点重读输出' }).click();
  await expect(panel.locator('.terminal-mode')).toHaveText('仅查看'); await expect(panel.locator('.terminal-output-status')).toContainText('正在读取原输出');
  expect(source.requests.filter(request => request.path.endsWith('/output') && request.query.includes('after=0'))).toHaveLength(2);
  expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.errors).toEqual([]);
});

test('终端与原动作历史按稳定游标换页，当前原终端选择保持', async ({ page }) => {
  const source = await fixture(page);
  for (let number = 99; number >= 60; number--) source.sessions.push({ ...source.original, id: `old-terminal-${number}`, historyCursor: number, state: 'EXITED' });
  for (let number = 1; number <= 35; number++) source.actions.set(`old-action-${number}`, { id: `old-action-${number}`, terminalId: source.original.id, requestId: `old-action-${number}`,
    kind: 'INPUT', sequence: number, historyCursor: number, length: number, sha256: 'a'.repeat(64), state: 'ACKNOWLEDGED', dispatchCount: 1, createdAt: '2026-11-16T00:00:00Z' });
  const panel = await open(page); await expect(panel.getByLabel('原终端记录')).toHaveValue(source.original.id);
  await panel.getByRole('button', { name: '读取更早的终端记录' }).click();
  await expect.poll(() => source.requests.filter(request => request.path.endsWith('/terminals')).at(-1)?.query).toContain('before=71');
  await expect(panel.getByLabel('原终端记录')).toHaveValue(source.original.id); await expect(panel.getByLabel('原终端记录').locator('option[value="old-terminal-60"]')).toHaveCount(1);
  await panel.getByRole('button', { name: '返回较新的终端记录' }).click(); await expect(panel.getByLabel('原终端记录').locator('option[value="old-terminal-99"]')).toHaveCount(1);
  await panel.locator('.terminal-actions summary').click(); await expect(panel.locator('.terminal-action-row')).toHaveCount(30);
  await panel.getByRole('button', { name: '更早记录', exact: true }).click(); await expect(panel.locator('.terminal-action-row')).toHaveCount(5);
  await expect(panel.locator('.terminal-action-row').first()).toContainText('#5'); await expect(panel.locator('.terminal-action-row').last()).toContainText('#1');
  await panel.getByRole('button', { name: '较新记录', exact: true }).click(); await expect(panel.locator('.terminal-action-row')).toHaveCount(30);
  expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.errors).toEqual([]);
});

test('离开任务后原启动晚响应不污染另一个任务，也不重发原启动', async ({ page }) => {
  const source = await fixture(page, { empty: true }), another = { ...source.task, id: 'another-terminal-task', title: '另一个原任务', terminalProtocolVersion: undefined };
  source.tasks.push(another); await page.route('**/api/tasks/another-terminal-task', route => route.fulfill({ json: another }));
  let release!: () => void; source.setStartGate(new Promise<void>(done => { release = done; }));
  try {
    const panel = await open(page); await panel.getByRole('button', { name: '新建终端', exact: true }).click();
    await expect.poll(() => source.requests.filter(request => request.method === 'POST' && request.path.endsWith('/terminals')).length).toBe(1);
    await page.getByRole('button', { name: '全部任务', exact: true }).click(); await page.getByRole('button', { name: '另一个原任务' }).click();
    await page.getByRole('button', { name: '终端', exact: true }).click(); release();
    await expect(page.locator('.task-terminals')).toContainText('当前核心未提供终端能力'); await expect(page.getByRole('heading', { name: '另一个原任务', exact: true })).toBeVisible();
    expect(source.requests.filter(request => request.method === 'POST' && request.path.endsWith('/terminals'))).toHaveLength(1); expect(source.errors).toEqual([]);
  } finally { release(); }
});

for (const theme of ['pure-white', 'paper-ink', 'classic-warm', 'celadon', 'ink-night', 'ink-night-blue']) for (const width of [1440, 430]) {
  test(`终端六主题原字节、实际尺寸与布局 ${theme} ${width}px`, async ({ page }, info) => {
    await page.setViewportSize({ width, height: 1100 }); await page.addInitScript(value => localStorage.setItem('workbench-theme', value), theme);
    const source = await fixture(page), panel = await open(page); await expect(panel.locator('.terminal-output-status')).toContainText(`已排空 ${source.settings.bytes.length} 字节`);
    await expect(panel.locator('.xterm-accessibility')).toContainText('ORIGINAL_PTY_中文_GREEN');
    const measurement = await panel.evaluate(element => { const pane = element.querySelector('.native-terminal')!, screen = element.querySelector('.xterm-screen')!, root = getComputedStyle(document.documentElement);
      const background = getComputedStyle(element.querySelector('.terminal-resizable')!).backgroundColor;
      const colors = [...element.querySelectorAll('.xterm-rows span')].filter(span => span.textContent?.trim()).map(span => ({ text: span.textContent, foreground: getComputedStyle(span).color, background }));
      return { width: innerWidth, pageWidth: document.documentElement.scrollWidth, pane: pane.getBoundingClientRect().toJSON(), screen: screen.getBoundingClientRect().toJSON(), foreground: root.getPropertyValue('--text').trim(), background: root.getPropertyValue('--surface').trim(), theme: document.documentElement.dataset.theme, colors }; });
    expect(measurement.pageWidth).toBeLessThanOrEqual(width); expect(measurement.screen.width).toBeLessThanOrEqual(measurement.pane.width + 1); expect(measurement.screen.height).toBeLessThanOrEqual(measurement.pane.height + 1); expect(measurement.theme).toBe(theme);
    expect(measurement.colors.length).toBeGreaterThan(2); expect(measurement.colors.some(color => color.text === 'GREEN')).toBe(true);
    for (const color of measurement.colors) expect(contrast(color.foreground, color.background), `${theme}: ${color.text}`).toBeGreaterThanOrEqual(4.5);
    expect(source.original.columns).toBe(80); expect(source.original.rows).toBe(24); expect(source.requests.filter(request => request.method === 'POST')).toEqual([]); expect(source.errors).toEqual([]);
    await info.attach('terminal-layout-measurement', { body: Buffer.from(JSON.stringify(measurement)), contentType: 'application/json' }); await page.screenshot({ path: info.outputPath(`terminal-${theme}-${width}.png`), fullPage: true });
  });
}
