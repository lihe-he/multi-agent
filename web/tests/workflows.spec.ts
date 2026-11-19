import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { AgentDefinition, Route, Task } from '../src/types';
import type { WorkflowDsl, WorkflowSelection, WorkflowVersion } from '../src/workflow-types';

const createdAt = '2026-11-19T00:00:00Z';
const project = { id: 'workflow-project', name: '流程项目', path: 'D:/Projects/fixture' };
const rootRoute: Route = { id: 'root-route', engine: 'API', name: '根模型', model: 'fixture', version: 1, enabled: true };
const extraRoute: Route = { ...rootRoute, id: 'extra-route', name: '另一个 API 路由' };
const actor: AgentDefinition = { id: 'researcher', name: '调查员', version: 1, role: 'researcher', instructions: '核对来源', routeId: extraRoute.id, capabilities: ['read'], scope: ['src/**'], maxCalls: 4, enabled: true };
const dsl: WorkflowDsl = { dslVersion: 1, inputs: ['goal', 'contract', 'context', 'baseline'], maxParallel: 2, nodes: [{ id: 'inspect', kind: 'INVESTIGATE', title: '调查与交接', role: actor.role,
  goal: '调查实际项目', definitionId: actor.id, definitionVersion: actor.version, dependencies: [], inputs: ['goal'], outputs: ['inspect:result'], scope: actor.scope,
  permissions: actor.capabilities, maxCalls: 2, timeoutSeconds: 60, acceptance: ['保存实际依据'], termination: '完成或取消' }] };
const v1: WorkflowVersion = { id: 'original:v1', definitionId: 'original', version: 1, name: '固定流程', projectId: project.id, digest: '1'.repeat(64), dsl, agentDefinitions: [actor], source: 'USER', createdAt };
const v2: WorkflowVersion = { ...v1, id: 'original:v2', version: 2, digest: '2'.repeat(64), dsl: { ...dsl, maxParallel: 1 } };
const sourceDigest = 'a'.repeat(64), snapshotDigest = 'b'.repeat(64), workspaceVersion = 'c'.repeat(64);
const selection = (v: WorkflowVersion): WorkflowSelection => ({ definitionId: v.definitionId, version: v.version, digest: v.digest, allowedRouteIds: [extraRoute.id] });
const snapshot = (v: WorkflowVersion) => ({ ...v, protocolVersion: 1, snapshotDigest: v.version === 1 ? snapshotDigest : 'e'.repeat(64) });

// The browser runs the production bundle. The API fixture records the exact user writes.
async function fixture(page: Page, scenario: 'create' | 'publish' | 'restart' | 'retry') {
  const definitions = [{ id: v2.definitionId, name: v2.name, version: 2, projectId: project.id, enabled: true, createdAt, updatedAt: createdAt }];
  const runs = scenario === 'restart' ? [{ id: 'original-run', generation: 1, state: 'PAUSED', definitionVersion: 1, baselineVersion: workspaceVersion, rootVersion: workspaceVersion, createdAt, nodes: { inspect: { state: 'SUCCEEDED', attempt: 1 } } }] : [];
  const original: Task = { id: 'old-task', title: '原流程任务', goal: '保留固定版本', mode: 'LEAD', state: runs.length ? 'PAUSED' : 'DRAFT', projectId: project.id, routeId: rootRoute.id, routeSnapshot: rootRoute,
    createdAt, updatedAt: createdAt, workflowProtocolVersion: 1, workflow: snapshot(v1), evidence: [], plan: [], contract: { scope: ['src/**'], acceptance: ['核对来源'], commands: ['node verify.mjs'] },
    budget: { maxCalls: 16, reserveCalls: 4, usedCalls: 1, developmentAvailable: 11 }, ...(runs.length ? { workflowRunId: runs[0].id, workspace: 'D:/test/work', version: workspaceVersion } : {}) };
  const proposal = { id: 'proposal', taskId: original.id, projectId: project.id, generationId: 'generation', sequence: 1, name: '模型给出的候选', state: 'VALIDATED', sourceDigest,
    dsl, agentDefinitions: [actor], errors: [], evidenceId: 'original-evidence', modelRequestId: 'original-request' };
  const tasks = [original], writes: { path: string; body: any }[] = [], errors: string[] = [], reads: string[] = [];
  const generations: { id: string; requestId: string; sequence: number; state: string; maxCalls: number; createdAt: string }[] = [];
  page.on('pageerror', error => errors.push(error.message));
  await page.route('**/api/**', async intercepted => {
    const request = intercepted.request(), path = new URL(request.url()).pathname;
    const send = (json: unknown) => intercepted.fulfill({ json });
    const fail = (message: string) => intercepted.fulfill({ status: 409, json: { message } });
    if (path === '/api/state') return send({ projects: [project], routes: [rootRoute, extraRoute], tasks, profiles: [], memories: [], agentDefinitions: [actor], system: { version: 'workflow-fixture', storageHealthy: true } });
    if (request.method() === 'POST') {
      const body = request.postDataJSON(); writes.push({ path, body });
      if (path === '/api/tasks') {
        if (!body.workflow?.allowedRouteIds.includes(extraRoute.id)) return fail('缺少额外路由确认');
        const task = { ...original, id: 'created-task', title: body.title, workflow: snapshot(body.workflow.version === 1 ? v1 : v2) };
        tasks.push(task); return send(task);
      }
      if (path === '/api/workflows/validate') return send({ valid: true, errors: [], order: ['inspect'], dsl: body.dsl });
      if (path.endsWith('/proposal/publish')) {
        if (body.expectedSourceDigest !== sourceDigest) return fail('原候选来源已变化');
        const version = { ...v1, id: 'published:v1', definitionId: 'published', name: body.name, dsl: body.dsl, source: 'GENERATED_EDIT' };
        const definition = { ...definitions[0], id: version.definitionId, name: version.name, version: 1 };
        definitions.push(definition); proposal.state = 'PUBLISHED'; return send({ definition, version });
      }
      if (path.endsWith('/workflow-restart')) {
        if (body.expectedVersion !== original.version || body.expectedRunId !== original.workflowRunId || body.expectedSnapshotDigest !== original.workflow?.snapshotDigest) return fail('原根现场已变化，请重新核对当前版本');
        original.workflow = snapshot(v2); original.state = 'DRAFT'; return send(original);
      }
      if (path.endsWith('/workflow-generations')) {
        if (!generations.some(item => item.requestId === body.requestId)) {
          generations.push({ id: `${original.id}:${body.requestId}`, requestId: body.requestId, sequence: generations.length + 1, state: 'COMPLETED', maxCalls: body.maxCalls, createdAt });
          if (generations.length === 1) return intercepted.abort('connectionreset');
        }
        return send(original);
      }
      return fail('Unexpected write');
    }
    const task = tasks.find(item => path === `/api/tasks/${item.id}`);
    if (task) { reads.push(task.version || ''); return send(task); }
    if (path === '/api/agent-definitions') return send([actor]);
    if (path === '/api/mcp/task-tools') return send({ tools: [] });
    if (path === '/api/workflows') return send(definitions);
    if (path === '/api/workflows/original/versions') return send({ versions: [v2, v1], hasMore: false, next: 1 });
    if (path === '/api/workflows/original/versions/1') return send(v1);
    if (path === '/api/workflows/original/versions/2') return send(v2);
    if (path.endsWith('/workflow-runs')) return send({ runs, hasMore: false, next: 1 });
    if (path.endsWith('/attempts')) return send({ attempts: [{ id: 'inspect-1', node: dsl.nodes[0], attempt: 1, state: 'SUCCEEDED', integrationState: 'NOT_INTEGRATED', inputVersion: workspaceVersion, resultVersion: workspaceVersion }], hasMore: false, next: 1 });
    if (path.endsWith('/workflow-proposals/proposal')) return send(proposal);
    if (path.endsWith('/workflow-proposals')) return send({ proposals: scenario === 'publish' ? [proposal] : [], hasMore: false, next: 1 });
    if (path.endsWith('/workflow-generations')) return send(generations);
    if (path.endsWith('/events')) return send({ events: [], cursor: 0, hasMore: false });
    if (path.endsWith('/mcp-operations')) return send([]);
    return send({});
  });
  return { original, writes, errors, reads, generations };
}

async function openOriginal(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: '研发任务', exact: true }).click();
  await page.getByRole('button', { name: '原流程任务' }).click();
  await page.getByRole('button', { name: '流程协作', exact: true }).click();
}

async function checkViewport(page: Page) {
  await expect.poll(() => page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true);
}

for (const width of [1440, 430]) {
  test.describe(`流程协作 ${width}px`, () => {
    test.use({ viewport: { width, height: 1000 } });

    test('确认额外路由后才提交固定历史版本', async ({ page }, info) => {
      const { writes, errors } = await fixture(page, 'create'); await page.goto('/');
      await page.getByRole('button', { name: '新建任务', exact: true }).click();
      const dialog = page.getByRole('dialog');
      await dialog.getByLabel('任务名称').fill('历史版本任务');
      await dialog.getByLabel('你希望完成什么？').fill('调查并保留证据');
      await dialog.getByLabel('验收条件', { exact: true }).fill('保存来源');
      await dialog.getByLabel('必要验证命令').fill('node verify.mjs');
      await dialog.getByRole('checkbox', { name: /使用版本化流程/ }).check();
      await dialog.getByLabel('流程定义').selectOption(v1.definitionId);
      await dialog.getByLabel('流程版本').selectOption('1');
      const acceptance = dialog.getByLabel('验收条件', { exact: true });
      await acceptance.scrollIntoViewIfNeeded(); await expect(acceptance).toBeInViewport();
      const consent = dialog.getByRole('checkbox', { name: `${extraRoute.name} · ${extraRoute.model}`, exact: true });
      await expect(consent).toBeVisible();
      const create = dialog.getByRole('button', { name: '生成任务计划' });
      await expect(create).toBeDisabled(); expect(writes).toHaveLength(0);
      await consent.check(); await expect(create).toBeEnabled(); await create.click(); await expect(dialog).toHaveCount(0);
      expect(writes).toHaveLength(1); expect(writes[0]).toMatchObject({ path: '/api/tasks', body: { workflow: selection(v1) } });
      await page.getByRole('button', { name: '流程协作', exact: true }).click();
      const panel = page.getByRole('region', { name: '流程协作' });
      await expect(panel.getByText(/固定流程 · v1 · 摘要/)).toBeVisible();
      await checkViewport(page); await page.screenshot({ path: info.outputPath('pinned-workflow.png') }); expect(errors).toEqual([]);
    });

    test('编辑发布候选携带原摘要，任务保留原流程', async ({ page }) => {
      const { writes, original, errors } = await fixture(page, 'publish'); await openOriginal(page);
      await page.getByRole('button', { name: '查看候选' }).click();
      await page.getByRole('dialog').getByRole('button', { name: '编辑并发布' }).click();
      const editor = page.getByRole('dialog', { name: '编辑并发布流程候选' });
      const edited = { ...dsl, maxParallel: 1 };
      await editor.getByLabel('流程名称').fill('已编辑的候选');
      await editor.getByLabel('流程 DSL（JSON）').fill(JSON.stringify(edited));
      const publish = editor.getByRole('button', { name: '发布流程' });
      await expect(publish).toBeDisabled();
      await editor.getByRole('button', { name: '校验流程' }).click();
      await expect(editor.getByRole('status')).toContainText('流程校验通过');
      await checkViewport(page);
      await publish.click(); await expect(editor).toHaveCount(0);
      expect(writes.map(write => write.path)).toEqual(['/api/workflows/validate', '/api/tasks/old-task/workflow-proposals/proposal/publish']);
      expect(writes[1].body).toEqual({ name: '已编辑的候选', dsl: edited, expectedSourceDigest: sourceDigest });
      expect(original.workflow).toEqual(snapshot(v1));
      await expect(page.getByRole('region', { name: '流程协作' }).getByText(/固定流程 · v1 · 摘要/)).toBeVisible();
      await page.getByRole('button', { name: '资源与设置' }).click();
      await page.getByRole('button', { name: '流程模板', exact: true }).click();
      await expect(page.getByRole('heading', { name: '已编辑的候选' })).toBeVisible(); expect(errors).toEqual([]);
    });

    test('换版拒绝过期现场，重新确认后返回协作计划', async ({ page }) => {
      const { writes, original, reads, errors } = await fixture(page, 'restart'); await openOriginal(page);
      await expect(page.getByLabel('流程运行代次')).toHaveValue('original-run');
      const attempt = page.getByText('调查与交接 · 第 1 次');
      await attempt.click(); await expect(page.getByText('调查实际项目', { exact: true })).toBeVisible();
      const restart = page.getByRole('button', { name: '选择版本并重新开始' });
      async function prepareRestart() {
        await restart.click();
        const dialog = page.getByRole('dialog', { name: '用当前现场开始新流程' });
        await dialog.getByLabel('流程定义').selectOption(v2.definitionId);
        await dialog.getByRole('checkbox', { name: /另一个 API 路由/ }).check();
        await dialog.getByLabel('重新开始的原因').fill('核对当前现场后重新开始'); return dialog;
      }
      let dialog = await prepareRestart();
      original.version = 'd'.repeat(64); original.updatedAt = '2026-11-19T01:00:00Z';
      await expect.poll(() => reads.includes(original.version!)).toBe(true);
      await dialog.getByRole('button', { name: '应用并返回计划' }).click();
      await expect(dialog.getByRole('alert')).toHaveText('原根现场已变化，请重新核对当前版本');
      expect(writes[0].body).toMatchObject({ expectedVersion: workspaceVersion, expectedSnapshotDigest: snapshotDigest, expectedRunId: 'original-run', selection: selection(v2) });
      await expect(dialog.getByLabel('重新开始的原因')).toHaveValue('核对当前现场后重新开始');
      await dialog.getByRole('button', { name: '取消', exact: true }).click();
      dialog = await prepareRestart(); await dialog.getByRole('button', { name: '应用并返回计划' }).click();
      await expect(dialog).toHaveCount(0); await expect(page.getByRole('heading', { name: '协作流程', exact: true })).toBeVisible();
      expect(writes[1].body.expectedVersion).toBe(original.version); expect(writes).toHaveLength(2); expect(original.budget?.usedCalls).toBe(1);
      await checkViewport(page); expect(errors).toEqual([]);
    });

    test('请求断开后重试使用同一生成编号和输入', async ({ page }) => {
      const { writes, generations, errors } = await fixture(page, 'retry'); await openOriginal(page);
      await page.getByRole('button', { name: '生成流程候选', exact: true }).click();
      const dialog = page.getByRole('dialog', { name: '生成流程候选' });
      const instructions = dialog.getByLabel('希望流程如何协作');
      await instructions.fill('用只读调查节点归纳证据');
      await dialog.getByRole('checkbox', { name: '调查员 v1' }).check();
      await dialog.getByRole('button', { name: '确认并生成候选' }).click();
      await expect(dialog.getByRole('alert')).toBeVisible(); await expect(instructions).toBeDisabled();
      await dialog.getByRole('button', { name: '重试原生成请求' }).click(); await expect(dialog).toHaveCount(0);
      expect(writes).toHaveLength(2); expect(writes[0]).toEqual(writes[1]); expect(generations).toHaveLength(1);
      expect(writes[0].body).toMatchObject({ requestId: expect.stringMatching(/^[0-9a-f-]{36}$/), instructions: '用只读调查节点归纳证据', definitionIds: [actor.id], expectedSnapshotDigest: snapshotDigest });
      await expect(page.getByText('生成 #1', { exact: true })).toBeVisible();
      await checkViewport(page); expect(errors).toEqual([]);
    });
  });
}
