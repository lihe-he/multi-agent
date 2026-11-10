import type { Page } from '@playwright/test';

// Browser protocol fixtures; Git association, ownership and source preservation use real Java/Jar checks.
export const remoteRef = 'refs/remotes/origin/review<script>literal</script>';
export const head = '1'.repeat(40), featureHead = '2'.repeat(40), otherHead = '3'.repeat(40);

export async function workspaceFixture(page: Page) {
  const node = { id: 'local-node', name: '本机节点', version: 1, protocolVersion: 1, type: 'LOCAL', deployment: 'LOCAL_PROCESS', enabled: true, epoch: 1, state: 'ONLINE' };
  const project = { id: 'workspace-project', name: '所选提交项目', path: 'C:\\Projects\\中文 repo', repositoryBindingId: 'binding-a', executionNodeId: node.id };
  const bindings = [{ id: 'binding-a', projectId: project.id, executionNodeId: node.id, path: project.path, name: '原仓库绑定', version: 1, enabled: true },
    { id: 'binding-b', projectId: project.id, executionNodeId: node.id, path: 'D:\\Projects\\另一个 repo', name: '另一个仓库', version: 1, enabled: true }];
  const source = { head, branch: 'main', dirty: false, version: 'a'.repeat(64), index: 'b'.repeat(64), statusDigest: 'c'.repeat(64) };
  const options: Record<string, any> = Object.fromEntries(bindings.map(binding => [binding.id, { schemaVersion: 1, repositoryBindingId: binding.id, bindingVersion: binding.version, projectId: project.id,
    executionNodeId: node.id, nodeVersion: node.version, sourcePath: binding.path, source: { ...source, head: binding.id === 'binding-a' ? head : otherHead },
    refs: [{ ref: 'refs/heads/main', head: binding.id === 'binding-a' ? head : otherHead, name: 'main', kind: 'LOCAL' },
      { ref: 'refs/heads/feature', head: featureHead, name: 'feature', kind: 'LOCAL' }, { ref: remoteRef, head: featureHead, name: 'origin/review<script>literal</script>', kind: 'REMOTE' }],
    strategies: ['CLONE', 'WORKTREE'], worktrees: [] }]));
  const route = { id: 'route', name: '本机模型夹具', version: 1, engine: 'API', model: 'fixture', enabled: true };
  const task: any = { id: 'workspace-task', projectId: project.id, title: '固定启动现场的任务', goal: '保留选择和原执行来源', mode: 'LEAD', state: 'PAUSED', workspace: '', routeId: route.id, routeSnapshot: route,
    contract: { scope: ['**'], acceptance: ['固定所选提交'], commands: ['node verify.mjs'] }, budget: { maxCalls: 24, reserveCalls: 4, usedCalls: 3 }, evidence: [], plan: [],
    execution: { version: 1, protocolVersion: 1, executionNodeId: node.id, nodeVersion: node.version, nodeName: node.name, repositoryBindingId: bindings[0].id, bindingVersion: 1, bindingName: bindings[0].name, sourcePath: project.path, deployment: 'LOCAL_PROCESS' },
    workspaceSelection: { schemaVersion: 1, version: 1, strategy: 'CLONE', ref: 'HEAD', baseline: head, sourcePath: project.path, source: { ...source }, includeChanges: false, createdAt: '2026-11-09T00:00:00Z' }, createdAt: '2026-11-09T00:00:00Z' };
  const tasks = [task], posts: any[] = [], selections: any[] = [], authorizations: any[] = [], optionQueries: string[] = [], errors: string[] = [];
  const gates: Record<string, Promise<void> | undefined> = {};
  page.on('pageerror', error => errors.push(error.message));
  function select(body: any, binding: any, version: number) {
    const input = body.workspaceSelection, available = options[binding.id];
    const baseline = input.ref === 'HEAD' ? available.source.head : available.refs.find((ref: any) => ref.ref === input.ref)?.head;
    if (!baseline || input.expectedHead !== baseline || input.sourceVersion !== available.source.version || input.sourceIndex !== available.source.index) throw new Error('仓库提交、文件或暂存区已变化，请刷新启动选择后重新确认');
    if (available.source.dirty && !body.includeChanges || body.includeChanges && baseline !== available.source.head) throw new Error('带入改动需要选择当前 HEAD');
    return { schemaVersion: 1, version, strategy: input.strategy, ref: input.ref, baseline, sourcePath: binding.path, source: { ...available.source }, includeChanges: body.includeChanges, createdAt: '2026-11-09T01:00:00Z' };
  }
  await page.route('**/api/**', async intercepted => {
    const request = intercepted.request(), path = new URL(request.url()).pathname;
    if (path === '/api/state') return intercepted.fulfill({ json: { projects: [project], routes: [route], tasks, profiles: [], memories: [], agentDefinitions: [], executionNodes: [node], repositoryBindings: bindings, workers: [], system: { version: 'workspace-ui-fixture', storageHealthy: true } } });
    if (path === '/api/repository-bindings') return intercepted.fulfill({ json: bindings });
    if (path === '/api/execution-nodes') return intercepted.fulfill({ json: [node] });
    if (/^\/api\/repository-bindings\/[^/]+\/workspace-options$/.test(path)) {
      const bindingId = decodeURIComponent(path.split('/')[3]); optionQueries.push(bindingId); if (gates[bindingId]) await gates[bindingId];
      return intercepted.fulfill({ json: options[bindingId] });
    }
    if (path === '/api/tasks' && request.method() === 'POST') {
      const body = request.postDataJSON(); posts.push(body); const binding = bindings.find(binding => binding.id === body.repositoryBindingId)!;
      try {
        if (body.bindingVersion !== binding.version || body.nodeVersion !== node.version) throw new Error('执行配置版本已变化，请刷新后确认');
        const selection = select(body, binding, 1), created = { ...task, ...body, id: 'created-task', state: 'DRAFT', workspace: '', workspaceSelection: selection,
          execution: { ...task.execution, repositoryBindingId: binding.id, bindingVersion: binding.version, bindingName: binding.name, sourcePath: binding.path } };
        tasks.push(created); return intercepted.fulfill({ json: created });
      } catch (error) { return intercepted.fulfill({ status: 409, json: { message: (error as Error).message } }); }
    }
    if (path === '/api/tasks/workspace-task/workspace-selection') {
      const body = request.postDataJSON(); selections.push(body);
      if (body.expectedVersion !== task.workspaceSelection.version) return intercepted.fulfill({ status: 409, json: { message: '启动选择版本已变化，请刷新后确认' } });
      try {
        const selected = select(body, bindings.find(binding => binding.id === task.execution.repositoryBindingId), task.workspaceSelection.version + 1);
        task.workspaceSelectionHistory = [...(task.workspaceSelectionHistory || []), { selection: task.workspaceSelection, actor: 'USER', createdAt: '2026-11-09T01:00:00Z' }]; task.workspaceSelection = selected;
        return intercepted.fulfill({ json: task });
      } catch (error) { return intercepted.fulfill({ status: 409, json: { message: (error as Error).message } }); }
    }
    if (path === '/api/tasks/workspace-task/execution-authorization') {
      const body = request.postDataJSON(); authorizations.push(body); const binding = bindings.find(binding => binding.id === body.repositoryBindingId)!;
      if (body.expectedVersion !== task.execution.version || body.workspaceSelection && body.expectedWorkspaceVersion !== task.workspaceSelection.version) return intercepted.fulfill({ status: 409, json: { message: '任务授权或启动选择版本已变化，请刷新后确认' } });
      try {
        if (body.workspaceSelection) {
          const selection = select(body, binding, task.workspaceSelection.version + 1);
          task.workspaceSelectionHistory = [...(task.workspaceSelectionHistory || []), { selection: task.workspaceSelection, actor: 'USER', createdAt: '2026-11-09T01:00:00Z' }]; task.workspaceSelection = selection;
        }
        task.execution = { ...task.execution, version: task.execution.version + 1, repositoryBindingId: binding.id, bindingVersion: binding.version, bindingName: binding.name, sourcePath: binding.path };
        return intercepted.fulfill({ json: task });
      } catch (error) { return intercepted.fulfill({ status: 409, json: { message: (error as Error).message } }); }
    }
    if (path === '/api/tasks/workspace-task') return intercepted.fulfill({ json: task });
    if (path === '/api/tasks/created-task') return intercepted.fulfill({ json: tasks.at(-1) });
    if (path.endsWith('/worker-leases')) return intercepted.fulfill({ json: { leases: [], cursor: 0, hasMore: false } });
    if (path.endsWith('/events')) return intercepted.fulfill({ json: { events: [], cursor: 0, hasMore: false } });
    if (path.endsWith('/mcp-operations')) return intercepted.fulfill({ json: [] });
    if (path === '/api/agent-definitions') return intercepted.fulfill({ json: [] });
    if (path === '/api/mcp/task-tools') return intercepted.fulfill({ json: { tools: [] } });
    return intercepted.fulfill({ json: {} });
  });
  return { task, tasks, bindings, options, posts, selections, authorizations, optionQueries, gates, errors };
}

export async function openWorkspaceTask(page: Page) {
  await page.goto('/'); await page.getByRole('button', { name: '研发任务', exact: true }).click(); await page.getByRole('button', { name: '固定启动现场的任务' }).click();
}

export async function fillWorkspaceTask(page: Page) {
  const dialog = page.getByRole('dialog'); await dialog.getByLabel('任务名称').fill('保留输入的启动计划'); await dialog.getByLabel('你希望完成什么？').fill('固定当前选择并核对源内容');
  await dialog.getByLabel('验收条件', { exact: true }).fill('固定提交来源'); await dialog.getByLabel('必要验证命令').fill('node verify.mjs');
  return dialog;
}
