import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import { join } from 'node:path';
import { inflateRawSync } from 'node:zlib';

// Deterministic protocol data for a real Core/Jar workflow, not a provider certification.
export const teamGoal = 'TEAM_SMOKE_ROOT：由实现副任务更新 src/greeting.txt，依赖副任务只读核对，父任务保留自己后续新增的文件，再采纳并完成独立验证。';
export const writerTitle = '发布验证实现副任务';
export const readerTitle = '发布验证依赖核对';
export const userNote = 'TEAM_SMOKE_USER_NOTE：保留原始来源与父任务后续修改。';

export function inspectModel(body) {
  const messages = body.messages || [];
  return {
    prompt: messages.filter(message => ['system', 'user'].includes(message.role)).map(message => String(message.content)).join('\n'),
    names: (body.tools || []).map(entry => entry.function?.name),
    results: messages.filter(message => message.role === 'tool'),
  };
}

export function createTeamProtocol({ model = 'smoke-model', holdWriter = false } = {}) {
  const calls = [], mcpCalls = [];
  let releaseWriter;
  const writerGate = holdWriter ? new Promise(resolve => { releaseWriter = resolve; }) : Promise.resolve();
  const matches = body => inspectModel(body).prompt.includes('TEAM_SMOKE_ROOT');
  async function message(body) {
    const { prompt, names, results } = inspectModel(body);
    assert.equal(body.model, model); assert.ok(matches(body));
    const stage = prompt.match(/阶段 kind=([A-Z_]+)/)?.[1] || 'UNSPECIFIED';
    const writer = stage === 'SUBAGENT' && prompt.includes('委派目标：TEAM_SMOKE_WRITE');
    const reader = stage === 'SUBAGENT' && prompt.includes('委派目标：TEAM_SMOKE_READ');
    const record = { number: calls.length + 1, stage, actor: writer ? 'writer' : reader ? 'reader' : 'parent', tools: names, completedTools: results.length };
    calls.push(record);
    const answer = content => ({ role: 'assistant', content });
    const tool = (name, args) => {
      assert.ok(names.includes(name), `${record.actor}/${stage} did not receive ${name}`);
      return { role: 'assistant', content: null, tool_calls: [{ id: `team-smoke-${record.number}-${name}`, type: 'function', function: { name, arguments: JSON.stringify(args) } }] };
    };
    const result = index => JSON.parse(results[index].content);
    for (let index = 0; index < results.length; index++) assert.equal(result(index).error, undefined, `${record.actor}: ${results[index].content}`);
    const alias = names.find(name => name.startsWith('mcp_'));
    if (writer) {
      assert.ok(alias, 'The unrestricted writer must receive the fixed MCP action');
      if (!results.length) { await writerGate; return tool('read_file', { path: 'src/greeting.txt' }); }
      if (results.length === 1) { assert.match(result(0).readVersion, /^file-read-/); return tool('write_file', { path: 'src/greeting.txt', content: 'hello agent\n', expectedVersion: result(0).readVersion }); }
      if (results.length === 2) return tool(alias, {});
      assert.equal(result(2).state, 'ACKNOWLEDGED');
      return answer('TEAM_SMOKE_WRITE_EVIDENCE：已实际写入文件并收到固定 MCP 原始回执，需由父任务采纳和验证。');
    }
    if (reader) {
      assert.ok(!names.includes('write_file') && !names.includes('run_command') && !alias, 'A scoped readonly child cannot acquire write, command or MCP tools');
      if (!results.length) return tool('read_file', { path: 'src/greeting.txt' });
      assert.equal(result(0).content, 'hello agent\n', 'The dependent child must see the actual writer result');
      return answer('TEAM_SMOKE_READ_EVIDENCE：从依赖现场实际读取 hello agent，尾部换行正确。');
    }
    if (stage === 'IMPLEMENT') {
      assert.ok(prompt.includes(userNote), 'The first root inbox must include the earlier user message');
      if (!results.length) return tool('delegate_agent', { definitionId: 'agent-developer', title: writerTitle, goal: 'TEAM_SMOKE_WRITE', scope: ['**'] });
      const child = result(0).agent; assert.ok(child?.subtaskId && child.agentRunId);
      if (results.length === 1) return tool('delegate_agent', { definitionId: 'agent-reviewer', title: readerTitle, goal: 'TEAM_SMOKE_READ', scope: ['src/'], dependsOn: [child.subtaskId] });
      if (results.length === 2) return tool('write_file', { path: 'src/parent-note.txt', content: 'parent change preserved\n' });
      if (results.length === 3) return tool('wait_agent', { subtaskId: result(1).agent.subtaskId });
      if (results.length === 4) { assert.equal(result(3).agentState, 'SUCCEEDED'); return tool('adopt_agent_changes', { subtaskId: child.subtaskId }); }
      if (results.length === 5) { assert.equal(result(4).state, 'ACKNOWLEDGED'); return tool(alias, {}); }
      if (results.length === 6) {
        assert.equal(result(5).deduplicated, true); assert.equal(result(5).sourceAgentRunId, child.agentRunId);
        return tool('send_team_message', { recipient: child.agentRunId, content: 'TEAM_SMOKE_ADOPTED：已采纳有来源的实际文件成果。' });
      }
      if (results.length === 7) { assert.equal(result(6).state, 'ACKNOWLEDGED'); return tool('read_file', { path: 'src/greeting.txt' }); }
      assert.equal(result(7).content, 'hello agent\n');
      return answer('父任务核对采纳完成，后续根验证、独立审查与人工验收分别保存。');
    }
    if (stage === 'REVIEW') {
      assert.ok(!names.includes('send_team_message') && !names.includes('delegate_agent'), 'Independent review has no shared team tools');
      assert.ok(!prompt.includes('TEAM_SMOKE_ADOPTED'), 'Independent review must not receive team inbox messages');
    }
    if (!results.length) return tool('read_file', { path: 'src/greeting.txt' });
    if (stage === 'REVIEW') {
      assert.equal(result(0).content, 'hello agent\n');
      return answer(JSON.stringify({ approved: true, issues: [], summary: '独立审查现场实际读取并核对问候内容。' }));
    }
    return answer('已读取实际原始文件，按固定定义和范围委派并保留依赖来源。');
  }

  async function handleMcp(request, response) {
    if (request.method === 'DELETE') { response.writeHead(204); response.end(); return; }
    let raw = ''; for await (const chunk of request) raw += chunk;
    const rpc = JSON.parse(raw);
    if (rpc.method.startsWith('notifications/')) { response.writeHead(202); response.end(); return; }
    let result;
    if (rpc.method === 'initialize') {
      response.setHeader('Mcp-Session-Id', 'team-smoke-mcp-session');
      result = { protocolVersion: '2025-06-18', capabilities: { tools: {} }, serverInfo: { name: 'Team local protocol fixture', version: '1' } };
    } else if (rpc.method === 'tools/list') {
      result = { tools: [{ name: 'echo', description: 'Local receipt for source verification', inputSchema: { type: 'object', properties: { text: { type: 'string' } }, required: ['text'], additionalProperties: false } }] };
    } else {
      assert.equal(rpc.method, 'tools/call'); assert.equal(rpc.params.name, 'echo'); assert.deepEqual(rpc.params.arguments, { text: 'TEAM_SMOKE_MCP_SOURCE' });
      mcpCalls.push({ method: rpc.method, arguments: rpc.params.arguments });
      result = { content: [{ type: 'text', text: 'TEAM_SMOKE_MCP_SOURCE' }], isError: false };
    }
    response.writeHead(200, { 'Content-Type': 'application/json' });
    response.end(JSON.stringify({ jsonrpc: '2.0', id: rpc.id, result }));
  }
  return { model, calls, mcpCalls, matches, message, handleMcp, releaseWriter: () => releaseWriter?.() };
}

export async function configureTeamMcp(api, projectId, url) {
  const saved = await api('/mcp/servers', { name: 'Team source lifecycle fixture', transport: 'HTTP', url, projectIds: [projectId], allowedTools: ['echo'], enabled: true });
  const discovery = await api(`/mcp/servers/${saved.id}/probe`, {}); assert.equal(discovery.available, true);
  return { server: saved, binding: { serverId: saved.id, name: 'echo', configVersion: saved.version, definitionVersion: discovery.definitionVersion, fixedArguments: { text: 'TEAM_SMOKE_MCP_SOURCE' }, timeoutMs: 5000 } };
}

export async function verifyTeamResult(api, finished, protocol) {
  const taskId = finished.id;
  const [team, trace, operations, mcp, messages, workerPage, workers] = await Promise.all([
    api(`/tasks/${taskId}/team`), api(`/tasks/${taskId}/model-requests?limit=100`), api(`/tasks/${taskId}/operations`), api(`/tasks/${taskId}/mcp-operations`), api(`/tasks/${taskId}/team/messages`), api(`/tasks/${taskId}/worker-leases?limit=100`), api('/workers'),
  ]);
  assert.equal(finished.state, 'AWAITING_ACCEPTANCE'); assert.equal(team.team.id, finished.teamId); assert.equal(team.agents.length, 2);
  const writer = team.agents.find(agent => agent.title === writerTitle), reader = team.agents.find(agent => agent.title === readerTitle);
  assert.ok(writer && reader); assert.equal(writer.state, 'SUCCEEDED'); assert.equal(reader.state, 'SUCCEEDED');
  assert.equal(writer.integrationState, 'ADOPTED'); assert.equal(writer.integrations.length, 1); assert.equal(reader.integrationState, 'NOT_ADOPTED');
  assert.equal(reader.parentRunId, writer.parentRunId); assert.deepEqual(reader.dependsOn, [writer.id]); assert.equal(writer.depth, 1); assert.equal(reader.depth, 1);
  assert.notEqual(writer.workspace, finished.workspace); assert.notEqual(reader.workspace, writer.workspace);
  assert.equal(await readFile(join(finished.workspace, 'src/greeting.txt'), 'utf8'), 'hello agent\n');
  assert.equal(await readFile(join(finished.workspace, 'src/parent-note.txt'), 'utf8'), 'parent change preserved\n');
  assert.equal(finished.budget.usedCalls, protocol.calls.length); assert.equal(team.runs.reduce((sum, run) => sum + run.calls, 0), finished.budget.usedCalls);
  assert.equal(trace.requests.length, finished.budget.usedCalls); assert.equal(trace.hasMore, false);
  assert.ok(trace.requests.every(request => request.rootTaskId === taskId && request.state === 'SUCCEEDED' && request.httpStatus === 200));
  assert.ok(finished.budget.usedDevelopmentCalls <= finished.budget.maxCalls - finished.budget.reserveCalls); assert.equal(finished.collaboration.spawned, 2);
  for (const child of [writer, reader]) {
    const request = trace.requests.find(request => request.id === child.requestId); assert.ok(request); assert.equal(request.agentRunId, child.parentRunId); assert.ok(child.toolCallId);
    assert.ok(trace.requests.some(request => request.subtaskId === child.id && request.agentRunId === child.agentRunId));
    const evidence = finished.evidence.find(evidence => evidence.id === child.evidenceId); assert.ok(evidence); assert.equal(evidence.resultVersion, child.resultVersion); assert.equal(evidence.agentRunId, child.agentRunId);
  }
  const readerEvidence = finished.evidence.find(evidence => evidence.id === reader.evidenceId);
  assert.match(readerEvidence.output, /TEAM_SMOKE_READ_EVIDENCE/); assert.deepEqual(readerEvidence.changedPaths, []);
  assert.ok(operations.some(operation => operation.kind === 'SUBAGENT_DEPENDENCIES' && operation.state === 'ACKNOWLEDGED'));
  assert.equal(operations.filter(operation => operation.kind === 'SUBAGENT_SNAPSHOT' && operation.state === 'ACKNOWLEDGED').length, 2);
  assert.equal(mcp.length, 1); assert.equal(protocol.mcpCalls.length, 1); assert.equal(finished.mcpBudget.usedCalls, 1); assert.equal(mcp[0].state, 'ACKNOWLEDGED');
  assert.equal(mcp[0].subtaskId, writer.id); assert.equal(mcp[0].agentRunId, writer.agentRunId); assert.equal(mcp[0].teamId, finished.teamId);
  const original = trace.requests.find(request => request.id === mcp[0].modelRequestId); assert.ok(original); assert.equal(original.agentRunId, writer.agentRunId); assert.ok(mcp[0].toolCallId);
  const user = messages.messages.find(message => message.content === userNote), sent = messages.messages.find(message => message.content.startsWith('TEAM_SMOKE_ADOPTED'));
  assert.ok(user && sent); assert.equal(user.sender, 'USER'); assert.equal(user.requestId, ''); assert.equal(sent.sender, writer.parentRunId); assert.equal(sent.recipient, writer.agentRunId);
  assert.ok(trace.requests.some(request => request.id === sent.requestId && request.agentRunId === sent.sender)); assert.ok(sent.toolCallId);
  assert.equal(finished.review.version, finished.version); assert.ok(finished.evidence.some(evidence => evidence.kind === 'VERIFICATION' && evidence.status === 'PASSED'));
  assert.equal(workerPage.hasMore, false); assert.ok(workerPage.leases.length > 0);
  assert.ok(workerPage.leases.every(lease => lease.state === 'RELEASED' && lease.stopState === 'CONFIRMED' && lease.executionNodeId === finished.execution.executionNodeId && lease.repositoryBindingId === finished.execution.repositoryBindingId && lease.bindingVersion === finished.execution.bindingVersion));
  assert.ok(workerPage.leases.every(lease => !Object.hasOwn(lease, 'claimDigest')));
  for (const lease of workerPage.leases) {
    const owner = workers.find(worker => worker.id === lease.workerId); assert.ok(owner); assert.equal(owner.deployment, 'LOCAL_PROCESS'); assert.notEqual(owner.pid, owner.corePid); assert.equal(owner.parentPid, owner.corePid);
    assert.equal(lease.deployment, owner.deployment); assert.equal(lease.executorPid, owner.pid); assert.equal(lease.corePid, owner.corePid); assert.equal(lease.processStartedAt, owner.processStartedAt);
  }
  for (const operation of operations) {
    const lease = workerPage.leases.find(lease => lease.operationId === operation.id); assert.ok(lease); assert.equal(operation.dispatchCount, 1); assert.equal(operation.workerLeaseId, lease.id);
  }
  assert.equal(workerPage.leases.filter(lease => lease.kind === 'ADOPT_SUBAGENT' && lease.operationKind === 'teamOperation').length, 1);
  assert.ok(trace.requests.every(request => workerPage.leases.some(lease => lease.id === request.workerLeaseId && lease.workerId === request.workerId && lease.agentRunId === request.agentRunId)));
  assert.ok(finished.evidence.every(evidence => workerPage.leases.some(lease => lease.id === evidence.workerLeaseId && lease.workerId === evidence.workerId)));
  for (const record of [...trace.requests, ...finished.evidence]) {
    const lease = workerPage.leases.find(lease => lease.id === record.workerLeaseId); assert.equal(record.deployment, 'LOCAL_PROCESS'); assert.equal(record.executorPid, lease.executorPid); assert.equal(record.corePid, lease.corePid); assert.equal(record.processStartedAt, lease.processStartedAt);
  }
  assert.equal(mcp[0].workerId, workers.find(worker => worker.id === mcp[0].workerId)?.id); assert.equal(mcp[0].workerLeaseId, original.workerLeaseId);
  return { team, trace, operations, mcp, messages, writer, reader, workerLeases: workerPage.leases, workers };
}

// Read central-directory sizes because Java ZIP streams use data descriptors.
export function zipEntry(archive, name) {
  let cursor = archive.length - 22;
  while (cursor >= Math.max(0, archive.length - 65557) && archive.readUInt32LE(cursor) !== 0x06054b50) cursor--;
  assert.ok(cursor >= 0, 'ZIP end record missing');
  const count = archive.readUInt16LE(cursor + 10); cursor = archive.readUInt32LE(cursor + 16);
  for (let index = 0; index < count; index++) {
    assert.equal(archive.readUInt32LE(cursor), 0x02014b50);
    const method = archive.readUInt16LE(cursor + 10), compressed = archive.readUInt32LE(cursor + 20), size = archive.readUInt32LE(cursor + 24);
    const length = archive.readUInt16LE(cursor + 28), extra = archive.readUInt16LE(cursor + 30), comment = archive.readUInt16LE(cursor + 32), offset = archive.readUInt32LE(cursor + 42);
    if (archive.subarray(cursor + 46, cursor + 46 + length).toString('utf8') === name) {
      assert.equal(archive.readUInt32LE(offset), 0x04034b50);
      const start = offset + 30 + archive.readUInt16LE(offset + 26) + archive.readUInt16LE(offset + 28), bytes = archive.subarray(start, start + compressed);
      assert.ok(method === 0 || method === 8); const result = method === 8 ? inflateRawSync(bytes) : bytes; assert.equal(result.length, size); return result;
    }
    cursor += 46 + length + extra + comment;
  }
  assert.fail(`ZIP entry missing: ${name}`);
}
