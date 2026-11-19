package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 流程代次、节点尝试与整合回执分别保存，只有完整实际来源支持完成节点复用。
 */
final class WorkflowLedger {
    static final Set<String> LIVE = Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING");
    private final ExecutionStore store;
    private final WorkflowCatalog catalog;
    private final ObjectMapper mapper;

    WorkflowLedger(ExecutionStore store, WorkflowCatalog catalog, ObjectMapper mapper) {
        this.store = store; this.catalog = catalog; this.mapper = mapper;
    }

    String inputDigest(Map<String, Object> task) {
        Map<String, Object> contract = new LinkedHashMap<>(map(task, "contract")); contract.remove("confirmed");
        return WorkflowDsl.digest(obj("goal", task.get("goal"), "contract", contract, "context", task.get("context"),
            "contextGeneration", num(task, "contextGeneration", 1), "execution", task.get("execution"),
            "mcpAuthorization", task.get("mcpAuthorization"), "workflowSnapshotDigest", map(task, "workflow").get("snapshotDigest")), mapper);
    }

    Map<String, Object> start(String taskId, String rootVersion) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId), snapshot = map(task, "workflow");
            requireTask(task); catalog.binding(snapshot, str(objects(map(snapshot, "dsl").get("nodes")).getFirst(), "id"));
            Map<String, Object> current = store.find("workflowRun", str(task, "workflowRunId"));
            if (current != null && !bool(task, "workflowRestartRequested", false)) {
                requireInput(task, current);
                if (!rootVersion.equals(str(current, "rootVersion"))) throw new ApiException(409, "根现场已变化，原节点输入不能复用；请明确重新开始流程并保留原记录");
                if ("UNKNOWN".equals(str(current, "state"))) throw new ApiException(409, "原流程有未确认结果，请核对后明确重新开始流程");
                current.putAll(obj("state", str(current, "baseWorkspace").isBlank() ? "PREPARING" : "RUNNING", "resumedAt", now()));
                store.commitRecords(Map.of("workflowRun", List.of(current)), taskId, "WORKFLOW_RESUMED", "核对原输入和根版本，保留完成节点与累计预算");
                return current;
            }
            if (current != null && store.list("workflowNodeAttempt").stream().anyMatch(attempt -> str(current, "id").equals(str(attempt, "workflowRunId")) && !attempt.containsKey("endedAt"))) throw new ApiException(409, "原流程仍有未发布停止边界的节点，不能建立新代次");
            int generation = store.list("workflowRun").stream().filter(run -> taskId.equals(str(run, "taskId"))).mapToInt(run -> num(run, "generation", 0)).max().orElse(0) + 1;
            if (generation > 1024) throw new ApiException(409, "流程代次达到保留上限，请保留原任务并创建新任务");
            Map<String, Object> nodes = new LinkedHashMap<>();
            for (Map<String, Object> node : objects(map(snapshot, "dsl").get("nodes"))) nodes.put(str(node, "id"), obj("state", "PENDING", "attempt", 0));
            Map<String, Object> run = obj("id", id(), "protocolVersion", 1, "taskId", taskId, "projectId", task.get("projectId"),
                "sessionId", task.getOrDefault("sessionId", ""), "generation", generation, "state", "PREPARING", "createdAt", now(),
                "definitionId", snapshot.get("definitionId"), "definitionVersion", snapshot.get("version"), "definitionDigest", snapshot.get("digest"),
                "snapshotDigest", snapshot.get("snapshotDigest"), "inputDigest", inputDigest(task), "baselineVersion", rootVersion, "rootVersion", rootVersion,
                "nodes", nodes, "baseWorkspace", "", "maxParallel", map(snapshot, "dsl").get("maxParallel"));
            ExecutionStore.copyTaskExecutionSource(run, task);
            if (current != null) run.put("previousRunId", current.get("id"));
            task.put("workflowRunId", run.get("id")); task.remove("workflowRestartRequested"); task.put("updatedAt", now());
            store.commitRecords(Map.of("workflowRun", List.of(run), "task", List.of(task)), taskId, "WORKFLOW_STARTED", "固定流程输入，建立第 " + generation + " 个实际运行代次");
            return run;
        }
    }

    void prepared(String runId, String workspace, String version, String operationId) {
        synchronized (store) {
            Map<String, Object> run = current(runId), operation = store.get("operation", operationId);
            if (!"PREPARING".equals(str(run, "state")) || !"WORKFLOW_ROOT_SNAPSHOT".equals(str(operation, "kind"))
                    || !"ACKNOWLEDGED".equals(str(operation, "state")) || !str(run, "baselineVersion").equals(version)
                    || !str(run, "taskId").equals(str(operation, "taskId")) || workspace.isBlank()) throw new ApiException(409, "流程基线缺少原快照回执");
            run.putAll(obj("baseWorkspace", workspace, "state", "RUNNING", "preparationOperationId", operationId, "preparedAt", now()));
            store.commitRecords(Map.of("workflowRun", List.of(run)), str(run, "taskId"), "WORKFLOW_BASELINE_READY", "原 Worker 的流程基线快照已固定");
        }
    }

    Map<String, Object> queue(String runId, Map<String, Object> node, List<String> ancestors) {
        synchronized (store) {
            Map<String, Object> workflow = current(runId), task = store.get("task", str(workflow, "taskId")); requireTask(task); requireInput(task, workflow);
            if (!"RUNNING".equals(str(workflow, "state")) || str(workflow, "baseWorkspace").isBlank()) throw new ApiException(409, "流程基线尚未完整准备");
            Map<String, Object> state = map(map(workflow, "nodes"), str(node, "id"));
            if (Set.of("SUCCEEDED", "UNKNOWN").contains(str(state, "state")) || LIVE.contains(str(state, "state"))) throw new ApiException(409, "原节点已完成、结果未知或仍在执行，不能重新派发");
            List<Map<String, Object>> fixedNodes = objects(map(map(task, "workflow"), "dsl").get("nodes"));
            Map<String, Map<String, Object>> fixedById = WorkflowDsl.byId(fixedNodes);
            if (!fixedById.containsKey(str(node, "id")) || !WorkflowDsl.order(fixedNodes).stream().filter(WorkflowDsl.ancestors(str(node, "id"), fixedById)::contains).toList().equals(ancestors)) throw new ApiException(409, "节点祖先来源不属于固定流程");
            for (String dependency : strings(node.get("dependencies"))) if (!"SUCCEEDED".equals(str(map(map(workflow, "nodes"), dependency), "state"))) throw new ApiException(409, "节点依赖尚未成功");
            int active = (int) store.list("agentRun").stream().filter(run -> str(task, "id").equals(str(run, "taskId")) && LIVE.contains(str(run, "state"))).count();
            int maximum = num(map(task, "collaboration"), "maxActive", 6);
            if (active >= maximum) throw new CapacityUnavailable();
            if (!BudgetPolicy.acquire(new LinkedHashMap<>(map(task, "budget")), false)) throw new DevelopmentUnavailable();
            Map<String, Object> binding = catalog.binding(map(task, "workflow"), str(node, "id")), actor = map(binding, "actor"), route = map(binding, "routeSnapshot");
            Map<String, Object> declared = WorkflowDsl.byId(objects(map(map(task, "workflow"), "dsl").get("nodes"))).get(str(node, "id"));
            if (declared == null || !WorkflowDsl.digest(declared, mapper).equals(WorkflowDsl.digest(node, mapper))) throw new ApiException(409, "节点内容不属于任务固定流程");
            int number = num(state, "attempt", 0) + 1, sequence = num(workflow, "nodeSequence", 0) + 1;
            String attemptId = id(), agentId = id(), stage = "workflow-node-" + str(node, "id");
            Map<String, Object> attempt = obj("id", attemptId, "protocolVersion", 1, "taskId", task.get("id"), "projectId", task.get("projectId"),
                "workflowRunId", runId, "nodeId", node.get("id"), "attempt", number, "sequence", sequence, "agentRunId", agentId, "state", "WAITING_RESOURCE", "createdAt", now(),
                "node", node, "permissions", binding.get("permissions"), "inputDigest", workflow.get("inputDigest"), "baselineVersion", workflow.get("baselineVersion"),
                "rootVersionAtQueue", workflow.get("rootVersion"), "ancestorNodeIds", ancestors, "source", "CONFIRMED_WORKFLOW_DEFINITION",
                "definitionId", actor.get("id"), "definitionVersion", actor.get("version"), "maxCalls", node.get("maxCalls"), "integrationState", "NOT_INTEGRATED");
            Map<String, Object> agent = obj("id", agentId, "schemaVersion", 1, "taskId", task.get("id"), "rootTaskId", task.get("id"),
                "projectId", task.get("projectId"), "sessionId", task.getOrDefault("sessionId", ""), "teamId", task.get("teamId"), "depth", 0,
                "stage", stage, "kind", node.get("kind"), "title", node.get("title"), "role", node.get("role"), "attempt", number,
                "state", "WAITING_RESOURCE", "createdAt", now(), "workspace", workflow.get("baseWorkspace"), "inputVersion", workflow.get("baselineVersion"),
                "permissions", binding.get("permissions"), "readOnly", !AgentPermissions.has(map(binding, "permissions"), "write"), "scope", node.get("scope"),
                "definitionId", actor.get("id"), "definitionVersion", actor.get("version"), "workflowRunId", runId, "workflowNodeId", node.get("id"),
                "workflowNodeAttemptId", attemptId, "workflowAncestorNodeIds", ancestors, "routeId", route.get("id"), "routeVersion", route.get("version"),
                "routeName", route.get("name"), "engine", "API", "model", route.get("model"), "provider", route.get("provider"),
                "reserved", false, "calls", 0, "callUnit", "API_REQUEST", "usage", obj("cost", "UNKNOWN"));
            ExecutionStore.copyTaskExecutionSource(attempt, task); ExecutionStore.copyTaskExecutionSource(agent, task);
            state.putAll(obj("state", "WAITING_RESOURCE", "attempt", number, "attemptId", attemptId, "agentRunId", agentId));
            workflow.put("updatedAt", now()); workflow.put("nodeSequence", sequence);
            store.commitRecords(Map.of("workflowRun", List.of(workflow), "workflowNodeAttempt", List.of(attempt), "agentRun", List.of(agent)),
                str(task, "id"), "WORKFLOW_NODE_QUEUED", str(node, "title") + " · 第 " + number + " 次，来源为已确认流程版本");
            return attempt;
        }
    }

    void inputReady(String attemptId, Map<String, Object> facts, String operationId) {
        synchronized (store) {
            Map<String, Object> attempt = active(attemptId), workflow = current(str(attempt, "workflowRunId")), agent = store.get("agentRun", str(attempt, "agentRunId"));
            Map<String, Object> operation = store.get("operation", operationId);
            if (!"ACKNOWLEDGED".equals(str(operation, "state")) || !str(agent, "id").equals(str(operation, "agentRunId"))
                    || !"WORKFLOW_INPUTS".equals(str(operation, "kind")) || str(facts, "workspace").isBlank() || str(facts, "inputVersion").isBlank()) throw new ApiException(409, "节点输入缺少原 Worker 的完整准备回执");
            attempt.putAll(facts); attempt.put("inputOperationId", operationId); attempt.put("state", "WAITING_RESOURCE");
            agent.putAll(obj("workspace", facts.get("workspace"), "inputVersion", facts.get("inputVersion"), "state", "WAITING_RESOURCE"));
            map(map(workflow, "nodes"), str(attempt, "nodeId")).put("state", "WAITING_RESOURCE");
            store.commitRecords(Map.of("workflowNodeAttempt", List.of(attempt), "agentRun", List.of(agent), "workflowRun", List.of(workflow)),
                str(attempt, "taskId"), "WORKFLOW_NODE_INPUT_READY", "节点固定实际祖先输入与独立现场：" + str(attempt, "nodeId"));
        }
    }

    void setWorkspace(String attemptId, Map<String, Object> copy) {
        synchronized (store) {
            Map<String, Object> attempt = active(attemptId), agent = store.get("agentRun", str(attempt, "agentRunId"));
            if (!Set.of("workspace", "baseWorkspace", "inputVersion").containsAll(copy.keySet()) || str(copy, "workspace").isBlank()) throw new ApiException(400, "节点快照事实不完整");
            attempt.putAll(copy); agent.put("workspace", copy.get("workspace")); agent.put("inputVersion", copy.get("inputVersion")); agent.put("state", "WAITING_RESOURCE");
            attempt.put("state", "WAITING_RESOURCE"); Map<String, Object> workflow = current(str(attempt, "workflowRunId"));
            map(map(workflow, "nodes"), str(attempt, "nodeId")).put("state", "WAITING_RESOURCE");
            store.commitRecords(Map.of("workflowNodeAttempt", List.of(attempt), "agentRun", List.of(agent), "workflowRun", List.of(workflow)), str(attempt, "taskId"), null, null);
        }
    }

    /**
     * 节点成功须同时对应真实模型回执、Worker 证据及固定输入，不能用状态文字补造。
     */
    void finish(String attemptId, String state, String reason) {
        synchronized (store) {
            Map<String, Object> attempt = active(attemptId), workflow = current(str(attempt, "workflowRunId")), agent = store.get("agentRun", str(attempt, "agentRunId"));
            if ("SUCCEEDED".equals(state)) {
                proof(attempt, false);
                attempt.putAll(obj("evidenceId", agent.get("evidenceId"), "resultVersion", agent.get("resultVersion"), "proofDigest", proofDigest(attempt)));
            }
            attempt.putAll(obj("state", state, "reason", reason, "endedAt", now()));
            map(map(workflow, "nodes"), str(attempt, "nodeId")).putAll(obj("state", state, "reason", reason)); workflow.put("updatedAt", now());
            store.finishWorkflowAttempt(attempt, workflow, state, reason);
        }
    }

    Map<String, Object> completed(String runId, String nodeId) {
        Map<String, Object> workflow = current(runId), state = map(map(workflow, "nodes"), nodeId);
        if (!"SUCCEEDED".equals(str(state, "state"))) throw new ApiException(409, "流程节点未完成：" + nodeId);
        Map<String, Object> attempt = store.get("workflowNodeAttempt", str(state, "attemptId")); proof(attempt, true);
        if (!runId.equals(str(attempt, "workflowRunId")) || !nodeId.equals(str(attempt, "nodeId"))
                || !str(workflow, "inputDigest").equals(str(attempt, "inputDigest")) || !str(state, "agentRunId").equals(str(attempt, "agentRunId"))) throw new ApiException(409, "完成节点引用不属于原流程、输入或节点");
        validateAttempt(attempt);
        if (!str(attempt, "proofDigest").equals(proofDigest(attempt))) throw new ApiException(409, "完成节点的原证据内容已变化，停止复用");
        return attempt;
    }

    void integrated(String attemptId, String operationId, String version) {
        synchronized (store) {
            Map<String, Object> attempt = store.get("workflowNodeAttempt", attemptId), workflow = current(str(attempt, "workflowRunId"));
            Map<String, Object> operation = store.get("operation", operationId);
            if (!"SUCCEEDED".equals(str(attempt, "state")) || !"ACKNOWLEDGED".equals(str(operation, "state"))
                    || !"WORKFLOW_INTEGRATE".equals(str(operation, "kind")) || !str(attempt, "taskId").equals(str(operation, "taskId"))
                    || !str(attempt, "id").equals(str(operation, "workflowNodeAttemptId")) || !version.equals(str(operation, "resultVersion"))) throw new ApiException(409, "流程整合缺少原成果和 Worker 回执");
            attempt.putAll(obj("integrationState", "INTEGRATED", "integrationOperationId", operationId, "integrationVersion", version, "integratedAt", now()));
            workflow.put("rootVersion", version); workflow.put("updatedAt", now());
            Map<String, Object> task = store.get("task", str(attempt, "taskId")); task.put("version", version); task.put("updatedAt", now());
            store.commitRecords(Map.of("workflowNodeAttempt", List.of(attempt), "workflowRun", List.of(workflow), "task", List.of(task)),
                str(attempt, "taskId"), "WORKFLOW_NODE_INTEGRATED", "原节点成果按版本检查整合：" + str(attempt, "nodeId"));
        }
    }

    void state(String runId, String state, String reason) {
        synchronized (store) {
            Map<String, Object> workflow = current(runId);
            if ("UNKNOWN".equals(str(workflow, "state")) && !"UNKNOWN".equals(state)) return;
            if ("SUCCEEDED".equals(state) && objects(map(store.get("task", str(workflow, "taskId")), "workflow").get("bindings")).stream()
                    .anyMatch(binding -> !"SUCCEEDED".equals(str(map(map(workflow, "nodes"), str(binding, "nodeId")), "state")))) throw new ApiException(409, "流程仍有未完成节点");
            if ("SUCCEEDED".equals(state)) for (String nodeId : map(workflow, "nodes").keySet()) {
                Map<String, Object> attempt = completed(runId, nodeId);
                if (AgentPermissions.has(map(attempt, "permissions"), "write") && !"INTEGRATED".equals(str(attempt, "integrationState"))) throw new ApiException(409, "流程写入成果尚未完成根整合");
            }
            workflow.putAll(obj("state", state, "reason", reason, "updatedAt", now()));
            store.commitRecords(Map.of("workflowRun", List.of(workflow)), str(workflow, "taskId"), "WORKFLOW_STATE", "流程：" + state + " · " + reason);
        }
    }

    Map<String, Object> current(String runId) {
        Map<String, Object> workflow = store.get("workflowRun", runId), task = store.get("task", str(workflow, "taskId"));
        if (!runId.equals(str(task, "workflowRunId")) || !str(map(task, "workflow"), "snapshotDigest").equals(str(workflow, "snapshotDigest"))) throw new ApiException(409, "旧流程回执不能进入新的运行代次");
        return workflow;
    }

    void requireInput(Map<String, Object> task, Map<String, Object> workflow) {
        if (!str(workflow, "inputDigest").equals(inputDigest(task))) throw new ApiException(409, "流程目标、上下文或执行授权变化，请明确重新开始流程");
    }

    List<Map<String, Object>> runs(String taskId) {
        store.get("task", taskId);
        return store.list("workflowRun").stream().filter(run -> taskId.equals(str(run, "taskId"))).toList();
    }

    List<Map<String, Object>> attempts(String taskId, String runId) {
        Map<String, Object> workflow = store.get("workflowRun", runId);
        if (!taskId.equals(str(workflow, "taskId"))) throw new ApiException(404, "流程运行不属于当前任务");
        return store.list("workflowNodeAttempt").stream().filter(attempt -> runId.equals(str(attempt, "workflowRunId"))).toList();
    }

    void recover() {
        synchronized (store) {
            for (Map<String, Object> attempt : store.list("workflowNodeAttempt")) if (!attempt.containsKey("endedAt")) {
                Map<String, Object> agent = store.get("agentRun", str(attempt, "agentRunId"));
                attempt.putAll(obj("state", "UNKNOWN", "reason", "核心退出时节点未发布完整完成边界，不自动重放", "endedAt", now()));
                Map<String, Object> workflow = store.get("workflowRun", str(attempt, "workflowRunId"));
                map(map(workflow, "nodes"), str(attempt, "nodeId")).putAll(obj("state", "UNKNOWN", "reason", attempt.get("reason"))); workflow.put("state", "UNKNOWN");
                if (LIVE.contains(str(agent, "state"))) store.finishAgentRun(str(agent, "id"), "UNKNOWN", str(attempt, "reason"));
                store.commitRecords(Map.of("workflowNodeAttempt", List.of(attempt), "workflowRun", List.of(workflow)), str(attempt, "taskId"), "WORKFLOW_RECOVERY_REQUIRED", str(attempt, "reason"));
            }
            for (Map<String, Object> workflow : store.list("workflowRun")) if (Set.of("RUNNING", "PREPARING").contains(str(workflow, "state"))) {
                workflow.putAll(obj("state", "UNKNOWN", "reason", "核心退出时流程未发布停止边界，需核对原进程和效果", "updatedAt", now()));
                store.commitRecords(Map.of("workflowRun", List.of(workflow)), str(workflow, "taskId"), "WORKFLOW_RECOVERY_REQUIRED", str(workflow, "reason"));
            }
        }
    }

    private Map<String, Object> active(String id) {
        Map<String, Object> attempt = store.get("workflowNodeAttempt", id);
        if (attempt.containsKey("endedAt")) throw new ApiException(409, "节点尝试终态不可覆盖");
        current(str(attempt, "workflowRunId")); validateAttempt(attempt); return attempt;
    }

    private void validateAttempt(Map<String, Object> attempt) {
        Map<String, Object> task = store.get("task", str(attempt, "taskId")), snapshot = map(task, "workflow");
        Map<String, Object> declared = WorkflowDsl.byId(objects(map(snapshot, "dsl").get("nodes"))).get(str(attempt, "nodeId"));
        Map<String, Object> binding = catalog.binding(snapshot, str(attempt, "nodeId"));
        if (declared == null || !WorkflowDsl.digest(declared, mapper).equals(WorkflowDsl.digest(map(attempt, "node"), mapper))
                || !WorkflowDsl.digest(map(binding, "permissions"), mapper).equals(WorkflowDsl.digest(map(attempt, "permissions"), mapper))
                || !str(attempt, "definitionId").equals(str(map(binding, "actor"), "id")) || num(attempt, "definitionVersion", -1) != num(map(binding, "actor"), "version", -2)
                || !"CONFIRMED_WORKFLOW_DEFINITION".equals(str(attempt, "source"))) throw new ApiException(409, "节点尝试偏离固定内容、职责或实际权限");
        Map<String, Map<String, Object>> fixed = WorkflowDsl.byId(objects(map(snapshot, "dsl").get("nodes")));
        if (!WorkflowDsl.order(new ArrayList<>(fixed.values())).stream().filter(WorkflowDsl.ancestors(str(attempt, "nodeId"), fixed)::contains).toList().equals(strings(attempt.get("ancestorNodeIds")))) throw new ApiException(409, "节点尝试的祖先来源已变化");
    }

    private void requireTask(Map<String, Object> task) {
        if (!"RUNNING".equals(str(task, "state")) || !bool(map(task, "contract"), "confirmed", false)) throw new ApiException(409, "根任务尚未确认或已停止");
        if (store.hasUnknownEffects(str(task, "id"))) throw new ApiException(409, "原根有未确认效果，停止流程新派发");
        store.requireActiveTaskExecution(task, task);
    }

    private void proof(Map<String, Object> attempt, boolean ended) {
        Map<String, Object> agent = store.get("agentRun", str(attempt, "agentRunId"));
        Map<String, Object> evidence = store.find("evidence", str(agent, "evidenceId"));
        Map<String, Object> node = map(attempt, "node"), binding = catalog.binding(map(store.get("task", str(attempt, "taskId")), "workflow"), str(attempt, "nodeId"));
        Map<String, Object> route = map(binding, "routeSnapshot");
        List<Map<String, Object>> requests = store.list("modelRequest").stream().filter(request -> str(agent, "id").equals(str(request, "agentRunId"))).toList();
        if ((ended && !"SUCCEEDED".equals(str(agent, "state"))) || !str(attempt, "workflowRunId").equals(str(agent, "workflowRunId"))
                || !str(attempt, "id").equals(str(agent, "workflowNodeAttemptId")) || evidence == null || !"PASSED".equals(str(evidence, "status"))
                || !str(agent, "id").equals(str(evidence, "agentRunId")) || !str(attempt, "inputVersion").equals(str(evidence, "inputVersion"))
                || str(agent, "resultVersion").isBlank() || !str(agent, "resultVersion").equals(str(evidence, "resultVersion"))
                || !str(attempt, "nodeId").equals(str(agent, "workflowNodeId")) || !str(node, "kind").equals(str(agent, "kind")) || !str(node, "role").equals(str(agent, "role"))
                || !str(attempt, "definitionId").equals(str(agent, "definitionId")) || num(attempt, "definitionVersion", -1) != num(agent, "definitionVersion", -2)
                || !WorkflowDsl.digest(map(attempt, "permissions"), mapper).equals(WorkflowDsl.digest(map(agent, "permissions"), mapper))
                || !str(attempt, "workspace").equals(str(agent, "workspace")) || !str(attempt, "inputVersion").equals(str(agent, "inputVersion"))
                || !str(route, "id").equals(str(agent, "routeId")) || num(route, "version", -1) != num(agent, "routeVersion", -2)
                || !str(route, "engine").equals(str(agent, "engine")) || !str(route, "model").equals(str(agent, "model"))
                || !"WORKFLOW_NODE".equals(str(evidence, "kind")) || !str(attempt, "id").equals(str(evidence, "workflowNodeAttemptId"))
                || !str(attempt, "workflowRunId").equals(str(evidence, "workflowRunId")) || !str(attempt, "nodeId").equals(str(evidence, "workflowNodeId"))
                || requests.isEmpty() || requests.size() != num(agent, "calls", -1) || requests.stream().anyMatch(request -> !"SUCCEEDED".equals(str(request, "state"))
                    || !str(attempt, "taskId").equals(str(request, "taskId")) || !str(attempt, "id").equals(str(request, "workflowNodeAttemptId"))
                    || !str(attempt, "workflowRunId").equals(str(request, "workflowRunId")) || !str(attempt, "nodeId").equals(str(request, "workflowNodeId"))
                    || !str(route, "id").equals(str(request, "routeId")) || num(route, "version", -1) != num(request, "routeVersion", -2))) throw new ApiException(409, "节点缺少固定职责、路由、完整真实请求、输入版本或成功证据");
        Map<String, Object> operation = store.get("operation", str(evidence, "operationId")), lease = store.get("workerLease", str(evidence, "workerLeaseId"));
        if (!"WORKFLOW_MODEL".equals(str(operation, "kind")) || !"ACKNOWLEDGED".equals(str(operation, "state"))
                || !str(agent, "id").equals(str(operation, "agentRunId")) || !str(evidence, "id").equals(str(operation, "evidenceId"))
                || !"RELEASED".equals(str(lease, "state")) || !"CONFIRMED".equals(str(lease, "stopState"))
                || !str(operation, "id").equals(str(lease, "operationId")) || !str(evidence, "workerId").equals(str(lease, "workerId"))) throw new ApiException(409, "节点缺少原 Worker 完整执行回执");
        for (String key : List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId")) if (!str(attempt, key).equals(str(agent, key))
                || !str(attempt, key).equals(str(evidence, key)) || requests.stream().anyMatch(request -> !str(attempt, key).equals(str(request, key)))) throw new ApiException(409, "节点原根执行来源不完整");
        Map<String, Object> inputs = store.get("operation", str(attempt, "inputOperationId"));
        if (!"ACKNOWLEDGED".equals(str(inputs, "state")) || !"WORKFLOW_INPUTS".equals(str(inputs, "kind"))
                || !str(agent, "id").equals(str(inputs, "agentRunId")) || !str(attempt, "inputVersion").equals(str(inputs, "inputVersion"))
                || !str(attempt, "baseWorkspace").equals(str(inputs, "baseWorkspace"))) throw new ApiException(409, "节点原输入准备回执不完整");
    }

    private String proofDigest(Map<String, Object> attempt) {
        Map<String, Object> agent = store.get("agentRun", str(attempt, "agentRunId"));
        return WorkflowDsl.digest(obj("attemptId", attempt.get("id"), "inputDigest", attempt.get("inputDigest"), "inputVersion", attempt.get("inputVersion"),
            "node", attempt.get("node"), "permissions", attempt.get("permissions"), "ancestorNodeIds", attempt.get("ancestorNodeIds"),
            "inputs", attempt.get("inputs"), "inputReceipts", attempt.get("inputReceipts"), "inputOperation", store.get("operation", str(attempt, "inputOperationId")),
            "workspace", attempt.get("workspace"), "baseWorkspace", attempt.get("baseWorkspace"), "evidence", store.get("evidence", str(agent, "evidenceId")),
            "requests", store.list("modelRequest").stream().filter(request -> str(agent, "id").equals(str(request, "agentRunId"))).toList()), mapper);
    }

    static final class CapacityUnavailable extends RuntimeException { }
    static final class DevelopmentUnavailable extends RuntimeException { }
}
