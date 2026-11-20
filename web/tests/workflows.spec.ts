import { expect, test } from '@playwright/test';
import type { Page } from '@playwright/test';
import type { AgentDefinition, Route, Task } from '../src/types';
import type { WorkflowDsl, WorkflowReadiness, WorkflowRouteIdentity, WorkflowSelection, WorkflowVersion } from '../src/workflow-types';

const createdAt = '2026-11-19T00:00:00Z';
const project = { id: 'workflow-project', name: '流程项目', path: 'D:/Projects/fixture' };
const rootRoute: Route = { id: 'root-route', engine: 'API', name: '根模型', model: 'fixture', version: 1, enabled: true, provider: '根供应商', baseUrl: 'https://root.example/v1' };
const extraRoute: Route = { ...rootRoute, id: 'extra-route', name: '另一个 API 路由', provider: '其他供应商', baseUrl: 'https://other.example/v1' };
const actor: AgentDefinition = { id: 'researcher', name: '调查员', version: 1, role: 'researcher', instructions: '核对来源', routeId: extraRoute.id, capabilities: ['read'], scope: ['src/**'], maxCalls: 4, enabled: true };
const dsl: WorkflowDsl = { dslVersion: 1, inputs: ['goal', 'contract', 'context', 'baseline'], maxParallel: 2, nodes: [{ id: 'inspect', kind: 'INVESTIGATE', title: '调查与交接', role: actor.role,
  goal: '调查实际项目', definitionId: actor.id, definitionVersion: actor.version, dependencies: [], inputs: ['goal'], outputs: ['inspect:result'], scope: actor.scope,
  permissions: actor.capabilities, maxCalls: 2, timeoutSeconds: 60, acceptance: ['保存实际依据'], termination: '完成或取消' }] };
const v1: WorkflowVersion = { id: 'original:v1', definitionId: 'original', version: 1, name: '固定流程', projectId: project.id, digest: '1'.repeat(64), dsl, agentDefinitions: [actor], source: 'USER', createdAt };
const v2: WorkflowVersion = { ...v1, id: 'original:v2', version: 2, digest: '2'.repeat(64), dsl: { ...dsl, maxParallel: 1 } };
const sourceDigest = 'a'.repeat(64), snapshotDigest = 'b'.repeat(64), workspaceVersion = 'c'.repeat(64);
const selection = (v: WorkflowVersion, routeVersion = 1): WorkflowSelection => ({ definitionId: v.definitionId, version: v.version, digest: v.digest, rootRouteVersion: 1, allowedRouteVersions: { [extraRoute.id]: routeVersion } });
const routeIdentity = (route: Route): WorkflowRouteIdentity => ({ id: route.id, name: route.name, version: route.version!, engine: route.engine, model: route.model || '', provider: route.provider || '', baseUrl: route.baseUrl || '' });
const snapshot = (v: WorkflowVersion) => ({ ...v, protocolVersion: 1, snapshotDigest: v.version === 1 ? snapshotDigest : 'e'.repeat(64) });

// The browser runs the production bundle. The API fixture records the exact user writes.
async function fixture(page: Page, scenario: 'create' | 'publish' | 'restart' | 'retry') {
  const routes = [{ ...rootRoute }, { ...extraRoute }];
  const readinessRequests: { path: string; body: any }[] = [];
  const readinessState = { blockers: [] as string[], nodeBlockers: [] as string[], holdVersion: 0, release: null as (() => void) | null, obsoleteFinished: false, publishRoutes: true,
    holdDefinitionVersion: 0, releaseDefinition: null as (() => void) | null };
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
    if (path === '/api/state') return send({ projects: [project], routes: readinessState.publishRoutes ? routes : [rootRoute, extraRoute], tasks, profiles: [], memories: [], agentDefinitions: [actor], system: { version: 'workflow-fixture', storageHealthy: true } });
    if (request.method() === 'POST') {
      const body = request.postDataJSON();
      if (path === '/api/workflows/readiness' || path.endsWith('/workflow-readiness')) {
        readinessRequests.push({ path, body });
        const choice = path === '/api/workflows/readiness' ? body.selection : body;
        const chosen = choice.version === 1 ? v1 : v2;
        const selectedRoute = routeIdentity(routes[1]);
        const response: WorkflowReadiness = { rootRoute: routeIdentity(rootRoute), blockers: [...readinessState.blockers],
          nodes: chosen.dsl.nodes.map(node => ({ id: node.id, title: node.title, inputs: node.inputs, rootInputs: node.inputs.filter(input => chosen.dsl.inputs.includes(input)), blockers: [...readinessState.nodeBlockers], route: selectedRoute })),
          extraRoutes: [selectedRoute] };
        if (readinessState.holdVersion === choice.version) {
          await new Promise<void>(resolve => { readinessState.release = resolve; });
          readinessState.obsoleteFinished = true;
          try { await send(response); } catch { /* The obsolete request may already be aborted. */ }
          return;
        }
        return send(response);
      }
      writes.push({ path, body });
      if (path === '/api/tasks') {
        if (body.workflow?.allowedRouteVersions[extraRoute.id] !== routes[1].version || body.workflow?.rootRouteVersion !== rootRoute.version) return fail('路由版本已变化，请刷新后重新确认');
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
        if (body.selection.allowedRouteVersions[extraRoute.id] !== routes[1].version) return fail('路由版本已变化，请刷新后重新确认');
        original.workflow = snapshot(body.selection.version === 1 ? v1 : v2); original.state = 'DRAFT'; return send(original);
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
    if (path === '/api/workflows/original/versions/1' || path === '/api/workflows/original/versions/2') {
      const chosen = path.endsWith('/1') ? v1 : v2;
      if (readinessState.holdDefinitionVersion === chosen.version) await new Promise<void>(resolve => { readinessState.releaseDefinition = resolve; });
      return send(chosen);
    }
    if (path.endsWith('/workflow-runs')) return send({ runs, hasMore: false, next: 1 });
    if (path.endsWith('/attempts')) return send({ attempts: [{ id: 'inspect-1', node: dsl.nodes[0], attempt: 1, state: 'SUCCEEDED', integrationState: 'NOT_INTEGRATED', inputVersion: workspaceVersion, resultVersion: workspaceVersion }], hasMore: false, next: 1 });
    if (path.endsWith('/workflow-proposals/proposal')) return send(proposal);
    if (path.endsWith('/workflow-proposals')) return send({ proposals: scenario === 'publish' ? [proposal] : [], hasMore: false, next: 1 });
    if (path.endsWith('/workflow-generations')) return send(generations);
    if (path.endsWith('/events')) return send({ events: [], cursor: 0, hasMore: false });
    if (path.endsWith('/mcp-operations')) return send([]);
    return send({});
  });
  return { original, writes, errors, reads, generations, readinessRequests, readinessState, routes };
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

async function createWorkflowDialog(page: Page) {
  await page.goto('/');
  await page.getByRole('button', { name: '新建任务', exact: true }).click();
  const dialog = page.getByRole('dialog', { name: '创建研发任务' });
  await dialog.getByLabel('任务名称').fill('重新核对路由');
  await dialog.getByLabel('你希望完成什么？').fill('调查并保留证据');
  await dialog.getByLabel('验收条件', { exact: true }).fill('保存来源');
  await dialog.getByLabel('必要验证命令').fill('node verify.mjs');
  await dialog.getByRole('checkbox', { name: /使用版本化流程/ }).check();
  await dialog.getByLabel('流程定义').selectOption(v2.definitionId);
  return dialog;
}

for (const width of [1440, 430]) {
  test.describe(`流程协作 ${width}px`, () => {
    test.use({ viewport: { width, height: 1000 } });

    test('确认额外路由后才提交固定历史版本', async ({ page }, info) => {
      const { writes, errors, readinessRequests } = await fixture(page, 'create'); await page.goto('/');
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
      const consent = dialog.getByRole('checkbox', { name: `确认 ${extraRoute.name} v1`, exact: true });
      await expect(consent).toBeVisible();
      await expect(dialog.getByText('每个节点都会收到固定目标、职责说明和适用的项目规则；声明输入控制自动附加的根任务资料与祖先交接。')).toBeVisible();
      await expect(dialog.getByText('供应商：其他供应商', { exact: false })).toBeVisible();
      await expect(dialog.getByText('https://other.example/v1', { exact: true })).toBeVisible();
      await expect(dialog.getByText('声明输入：任务目标', { exact: false })).toBeVisible();
      const create = dialog.getByRole('button', { name: '生成任务计划' });
      await expect(create).toBeDisabled(); expect(writes).toHaveLength(0);
      await consent.check(); await expect(create).toBeEnabled(); await create.click(); await expect(dialog).toHaveCount(0);
      expect(writes).toHaveLength(1); expect(writes[0]).toMatchObject({ path: '/api/tasks', body: { workflow: selection(v1) } });
      expect(readinessRequests.at(-1)).toEqual({ path: '/api/workflows/readiness', body: { projectId: project.id, routeId: rootRoute.id, locks: {}, selection: { definitionId: v1.definitionId, version: 1, digest: v1.digest } } });
      await page.getByRole('button', { name: '流程协作', exact: true }).click();
      const panel = page.getByRole('region', { name: '流程协作' });
      await expect(panel.getByText(/固定流程 · v1 · 摘要/)).toBeVisible();
      await checkViewport(page); await page.screenshot({ path: info.outputPath('pinned-workflow.png') }); expect(errors).toEqual([]);
    });

    test('改变锁定立即废止勾选，后端阻塞可刷新恢复', async ({ page }) => {
      const { writes, readinessRequests, readinessState, errors } = await fixture(page, 'create');
      const dialog = await createWorkflowDialog(page);
      const consent = dialog.getByRole('checkbox', { name: /确认 另一个 API 路由/ });
      const create = dialog.getByRole('button', { name: '生成任务计划' });
      await consent.check(); await expect(create).toBeEnabled();
      readinessState.blockers = ['调查与交接：所选节点路由不满足根供应商锁定'];
      readinessState.nodeBlockers = ['所选节点路由不满足根供应商锁定'];
      await dialog.getByRole('checkbox', { name: '锁定此供应商', exact: true }).check();
      await expect(create).toBeDisabled();
      await expect(dialog.getByRole('alert')).toContainText('所选节点路由不满足根供应商锁定');
      await expect(dialog.getByRole('alert').getByRole('listitem')).toHaveCount(1);
      await expect(consent).not.toBeChecked(); await expect(consent).toBeDisabled();
      expect(readinessRequests.at(-1)?.body.locks).toEqual({ provider: rootRoute.provider });
      expect(writes).toHaveLength(0);
      readinessState.blockers = [];
      readinessState.nodeBlockers = [];
      await dialog.getByRole('checkbox', { name: '锁定此供应商', exact: true }).uncheck();
      await expect(consent).toBeEnabled(); await expect(consent).not.toBeChecked();
      await consent.check(); await expect(create).toBeEnabled();
      await checkViewport(page); expect(errors).toEqual([]);
    });

    test('同一路由换版拒绝旧确认，刷新显示新接收方后重新提交', async ({ page }, info) => {
      const { writes, routes, readinessState, errors } = await fixture(page, 'create');
      readinessState.publishRoutes = false;
      const dialog = await createWorkflowDialog(page);
      await dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v1', exact: true }).check();
      const create = dialog.getByRole('button', { name: '生成任务计划' });
      await expect(create).toBeEnabled();
      routes[1] = { ...routes[1], version: 2, provider: '更新供应商', model: 'fixture-two', baseUrl: 'https://new.example/v2' };
      await create.click();
      await expect(dialog.getByRole('alert')).toHaveText('路由版本已变化，请刷新后重新确认');
      await expect(create).toBeDisabled();
      await expect(dialog.getByLabel('任务名称')).toHaveValue('重新核对路由');
      await dialog.getByRole('button', { name: '刷新执行检查', exact: true }).click();
      const updated = dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v2', exact: true });
      await expect(updated).toBeVisible(); await expect(updated).not.toBeChecked();
      await expect(dialog.getByText(/供应商：更新供应商 · 模型：fixture-two/)).toBeVisible();
      await expect(dialog.getByText('https://new.example/v2', { exact: true })).toBeVisible();
      await checkViewport(page); await page.screenshot({ path: info.outputPath('refreshed-route-consent.png') });
      await updated.check(); await create.click(); await expect(dialog).toHaveCount(0);
      expect(writes).toHaveLength(2);
      expect(writes[0].body.workflow).toEqual(selection(v2)); expect(writes[1].body.workflow).toEqual(selection(v2, 2));
      expect(errors).toEqual([]);
    });

    test('版本切换丢弃迟到检查结果，刷新撤销原路由勾选', async ({ page }) => {
      const { writes, readinessState, readinessRequests, errors } = await fixture(page, 'create');
      readinessState.holdVersion = 2;
      const dialog = await createWorkflowDialog(page);
      await expect.poll(() => readinessState.release !== null).toBe(true);
      await dialog.getByLabel('流程版本').selectOption('1');
      const consent = dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v1', exact: true });
      await consent.check();
      const create = dialog.getByRole('button', { name: '生成任务计划' });
      await expect(create).toBeEnabled();
      readinessState.release!(); await expect.poll(() => readinessState.obsoleteFinished).toBe(true);
      await expect(dialog.getByLabel('流程版本')).toHaveValue('1');
      await expect(consent).toBeChecked(); await expect(create).toBeEnabled();
      await dialog.getByRole('button', { name: '刷新执行检查', exact: true }).click();
      await expect(create).toBeDisabled(); await expect(consent).not.toBeChecked();
      await consent.check(); await create.click(); await expect(dialog).toHaveCount(0);
      expect(readinessRequests).toHaveLength(3); expect(writes).toHaveLength(1); expect(writes[0].body.workflow).toEqual(selection(v1));
      expect(errors).toEqual([]);
    });

    for (const scenario of ['create', 'restart'] as const) {
      test(`${scenario === 'create' ? '新建' : '重开'}切换已确认版本后，在固定版本和就绪检查加载期间拒绝旧选择`, async ({ page }) => {
        const { writes, readinessState, errors } = await fixture(page, scenario);
        let dialog;
        if (scenario === 'create') dialog = await createWorkflowDialog(page);
        else {
          await openOriginal(page);
          await page.getByRole('button', { name: '选择版本并重新开始' }).click();
          dialog = page.getByRole('dialog', { name: '用当前现场开始新流程' });
          await dialog.getByLabel('流程定义').selectOption(v2.definitionId);
          await dialog.getByLabel('重新开始的原因').fill('切换版本后核对相同路由');
        }
        const consent = dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v1', exact: true });
        const submit = dialog.getByRole('button', { name: scenario === 'create' ? '生成任务计划' : '应用并返回计划', exact: true });
        await consent.check(); await expect(submit).toBeEnabled();
        readinessState.holdDefinitionVersion = 1; readinessState.holdVersion = 1;
        await dialog.getByLabel('流程版本').evaluate(select => {
          (select as HTMLSelectElement).value = '1';
          select.dispatchEvent(new Event('change', { bubbles: true }));
          (select.closest('form') as HTMLFormElement).requestSubmit();
        });
        await expect.poll(() => readinessState.releaseDefinition !== null).toBe(true);
        await expect(dialog.getByText('正在读取固定版本…', { exact: true })).toBeVisible();
        await expect(submit).toBeDisabled(); expect(writes).toHaveLength(0);
        readinessState.releaseDefinition!();
        await expect.poll(() => readinessState.release !== null).toBe(true);
        await dialog.locator('form').evaluate(form => (form as HTMLFormElement).requestSubmit());
        await expect(dialog.getByText('正在检查流程与路由…', { exact: true })).toBeVisible();
        await expect(submit).toBeDisabled(); expect(writes).toHaveLength(0);
        readinessState.release!();
        await expect(consent).toBeVisible(); await expect(consent).not.toBeChecked();
        await dialog.locator('form').evaluate(form => (form as HTMLFormElement).requestSubmit());
        await expect(submit).toBeDisabled(); expect(writes).toHaveLength(0);
        await consent.check(); await expect(submit).toBeEnabled(); await submit.click();
        await expect(dialog).toHaveCount(0); expect(writes).toHaveLength(1);
        expect(scenario === 'create' ? writes[0].body.workflow : writes[0].body.selection).toEqual(selection(v1));
        await checkViewport(page); expect(errors).toEqual([]);
      });
    }

    test('任务换版的路由冲突可就地刷新且保留重开依据', async ({ page }) => {
      const { writes, routes, readinessState, original, readinessRequests, errors } = await fixture(page, 'restart');
      readinessState.publishRoutes = false;
      await openOriginal(page);
      await page.getByRole('button', { name: '选择版本并重新开始' }).click();
      const dialog = page.getByRole('dialog', { name: '用当前现场开始新流程' });
      await dialog.getByLabel('流程定义').selectOption(v2.definitionId);
      await dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v1', exact: true }).check();
      await dialog.getByLabel('重新开始的原因').fill('保留原记录并重新核对路由');
      routes[1] = { ...routes[1], version: 2, provider: '更新供应商' };
      const apply = dialog.getByRole('button', { name: '应用并返回计划' });
      await apply.click(); await expect(dialog.getByRole('alert')).toHaveText('路由版本已变化，请刷新后重新确认');
      await expect(apply).toBeDisabled();
      await dialog.getByRole('button', { name: '刷新执行检查' }).click();
      await expect(dialog.getByLabel('重新开始的原因')).toHaveValue('保留原记录并重新核对路由');
      const updated = dialog.getByRole('checkbox', { name: '确认 另一个 API 路由 v2', exact: true });
      await expect(updated).not.toBeChecked(); await updated.check();
      await apply.click(); await expect(dialog).toHaveCount(0);
      expect(writes).toHaveLength(2); expect(writes[1].body.selection).toEqual(selection(v2, 2));
      expect(writes[0].body.expectedVersion).toBe(writes[1].body.expectedVersion); expect(original.budget?.usedCalls).toBe(1);
      expect(readinessRequests.every(request => request.path === '/api/tasks/old-task/workflow-readiness')).toBe(true);
      await checkViewport(page); expect(errors).toEqual([]);
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
      const { writes, original, reads, errors, readinessRequests } = await fixture(page, 'restart'); await openOriginal(page);
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
      expect(readinessRequests.at(-1)).toEqual({ path: '/api/tasks/old-task/workflow-readiness', body: { definitionId: v2.definitionId, version: 2, digest: v2.digest } });
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
