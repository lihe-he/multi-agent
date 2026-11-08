package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.mcp.McpManager;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;

/**
 * 子任务在独立现场执行，根取消、预算、路由与资源共用；依赖只等待已创建的同父副任务。
 */
final class TeamCoordinator {
    private static final Set<String> LIVE = Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_DEPENDENCY", "WAITING_CHILDREN", "RUNNING");
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final WorkspaceRuntime runtime;
    private final ResourceManager resources;
    private final ObjectMapper mapper;
    private final McpManager mcp;
    private final ExecutorService workers;
    private final ExecutionWorker executionWorker;
    private final Map<String, CompletableFuture<Map<String, Object>>> executions = new ConcurrentHashMap<>();

    TeamCoordinator(ExecutionStore store, WorkbenchCatalog catalog, WorkspaceRuntime runtime, ResourceManager resources, ObjectMapper mapper, McpManager mcp, ExecutorService workers, ExecutionWorker executionWorker) {
        this.store = store; this.catalog = catalog; this.runtime = runtime; this.resources = resources; this.mapper = mapper; this.mcp = mcp; this.workers = workers;
        this.executionWorker = executionWorker;
    }

    Session session(String taskId, String runId, String workspace, Map<String, Object> route, BooleanSupplier cancelled) {
        return new Session(taskId, runId, workspace, route, cancelled);
    }

    WorkspaceRuntime.FixedToolInvoker fixedTools(String taskId, String runId, String stageKind, BooleanSupplier cancelled) {
        return new WorkspaceRuntime.FixedToolInvoker() {
            @Override public Map<String, Object> invoke(String alias) { return obj("state", "REJECTED", "error", "团队 MCP 动作缺少原请求身份"); }
            @Override public Map<String, Object> invoke(String alias, String requestId, String toolCallId) {
                try { return mcp == null ? obj("state", "REJECTED", "error", "MCP 不可用") : mcp.invokeFixedTool(taskId, stageKind, alias, runId, requestId, toolCallId, cancelled); }
                catch (ApiException exception) { return obj("state", exception.status() >= 500 || !store.healthy() ? "UNKNOWN" : "REJECTED", "error", safe(exception)); }
            }
        };
    }

    void requireStopped(String taskId) {
        for (var entry : executions.entrySet()) if (!entry.getValue().isDone() && taskId.equals(str(store.get("subtask", entry.getKey()), "taskId"))) throw new ApiException(409, "副 Agent 仍在停止或收尾中");
    }

    private String safe(Throwable error) {
        String text = catalog.redact(error.getMessage() == null ? error.getClass().getSimpleName() : error.getMessage());
        return text.substring(0, Math.min(3000, text.length()));
    }

    private Map<String, Object> childResult(String taskId, String childId) {
        Map<String, Object> child = store.subtask(taskId, childId);
        Map<String, Object> evidence = str(child, "evidenceId").isBlank() ? Map.of() : store.get("evidence", str(child, "evidenceId"));
        String output = str(evidence, "output");
        return obj("subtaskId", child.get("id"), "agentRunId", child.get("agentRunId"), "state", child.get("state"), "title", child.get("title"), "reason", child.get("reason"),
            "evidenceId", child.getOrDefault("evidenceId", ""), "resultVersion", child.getOrDefault("resultVersion", ""), "integrationState", child.get("integrationState"),
            "output", output.substring(0, Math.min(12000, output.length())), "outputTruncated", output.length() > 12000);
    }

    final class Session implements AgentToolSession {
        private final String taskId;
        private final String runId;
        private final String workspace;
        private final Map<String, Object> route;
        private final Map<String, Object> permissions;
        private final boolean sharedTeam;
        private final BooleanSupplier cancelled;
        private final long deadline;
        private final AtomicBoolean closing = new AtomicBoolean();
        private long messageCursor;

        private Session(String taskId, String runId, String workspace, Map<String, Object> route, BooleanSupplier cancelled) {
            this.taskId = taskId; this.runId = runId; this.workspace = workspace; this.route = route; this.cancelled = cancelled;
            this.permissions = map(store.get("agentRun", runId), "permissions");
            this.sharedTeam = !Set.of("PROPOSAL", "REVIEW").contains(str(store.get("agentRun", runId), "kind"));
            this.deadline = System.nanoTime() + Duration.ofMinutes(15).toNanos();
        }

        @Override public Map<String, Object> permissions() { return permissions; }
        boolean stopped() {
            if (closing.get() || cancelled.getAsBoolean() || System.nanoTime() >= deadline || !store.healthy() || !executionWorker.current(taskId)) return true;
            Map<String, Object> current = store.find("route", str(route, "id"));
            if (current == null || !bool(current, "enabled", false)) return true;
            String definitionId = str(store.get("agentRun", runId), "definitionId");
            if (!definitionId.isBlank()) { Map<String, Object> definition = store.find("agentDefinition", definitionId); if (definition == null || !bool(definition, "enabled", true)) return true; }
            try {
                String host = java.net.URI.create(str(route, "baseUrl")).getHost();
                if (!Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(host) && catalog.credential(str(route, "id"), num(route, "version", 1)).isBlank()) return true;
            } catch (IllegalArgumentException exception) { return true; }
            return store.hasUnknownEffects(taskId);
        }
        void stopChildren() { closing.set(true); }

        @Override
        public void checkCurrent() {
            if (store.healthy() && store.hasUnknownEffects(taskId)) throw new IllegalStateException("团队有未确认操作，需先人工核对");
            if (stopped()) throw new CancellationException("根任务停止或副 Agent 达到执行时限");
            Map<String, Object> current = store.find("route", str(route, "id"));
            if (current == null || !bool(current, "enabled", false) || !BudgetPolicy.matchesLocks(route, map(store.get("task", taskId), "locks"))) throw new CancellationException("Agent 路由撤回或不符合根锁定");
            String definitionId = str(store.get("agentRun", runId), "definitionId");
            if (!definitionId.isBlank()) {
                Map<String, Object> live = store.find("agentDefinition", definitionId);
                if (live == null || !bool(live, "enabled", true)) throw new CancellationException("Agent 定义已停用");
            }
        }

        @Override
        public AutoCloseable acquireModel() {
            checkCurrent(); store.agentRunWaiting(runId, "WAITING_RESOURCE");
            ResourceManager.Lease lease = resources.acquire(Map.of("model", 1, "process", 1, "route:" + str(route, "id"), 1), this::stopped);
            try { checkCurrent(); store.agentRunWaiting(runId, "RUNNING"); }
            catch (RuntimeException exception) { lease.close(); throw exception; }
            return () -> {
                lease.close();
                if (store.healthy() && LIVE.contains(str(store.get("agentRun", runId), "state"))) store.agentRunWaiting(runId, "WAITING_TOOL");
            };
        }

        @Override
        public AutoCloseable acquireTool() {
            checkCurrent();
            return resources.acquire(Map.of("tool", 1, "process", 1, "write:" + workspace, 1), this::stopped);
        }

        @Override
        public List<Map<String, Object>> inbox() {
            if (!sharedTeam) return List.of();
            List<Map<String, Object>> inbox = new ArrayList<>(store.teamInbox(taskId, messageCursor, runId));
            if (!inbox.isEmpty()) messageCursor = inbox.stream().mapToLong(message -> num(message, "sequence", 0)).max().orElse(messageCursor);
            inbox.removeIf(message -> runId.equals(str(message, "sender")));
            inbox.sort(java.util.Comparator.comparingInt(message -> num(message, "sequence", 0))); return inbox;
        }

        @Override
        public List<Map<String, Object>> definitions() {
            if (!sharedTeam) return List.of();
            List<Map<String, Object>> tools = new ArrayList<>();
            Map<String, Object> idField = obj("type", "string");
            if (AgentPermissions.has(permissions, "delegate")) tools.add(tool("delegate_agent", "创建独立副 Agent，可选择同父已创建任务为依赖。权限取交集，全部调用计入根预算。", obj(
                "definitionId", obj("type", "string", "enum", objects(map(store.get("task", taskId), "collaboration").get("definitions")).stream().map(definition -> str(definition, "id")).toList()),
                "title", obj("type", "string"), "goal", obj("type", "string"), "scope", obj("type", "array", "items", idField),
                "capabilities", obj("type", "array", "items", obj("type", "string", "enum", AgentPermissions.CAPABILITIES.stream().sorted().toList())),
                "dependsOn", obj("type", "array", "items", idField), "maxCalls", obj("type", "integer", "minimum", 1, "maximum", 32)), List.of("definitionId", "title", "goal", "scope")));
            tools.add(tool("wait_agent", "等待自己的直接副任务，等待时不占模型容量；返回原始结果和证据，写入成果需另行采纳。", obj("subtaskId", idField), List.of("subtaskId")));
            if (AgentPermissions.has(permissions, "write")) tools.add(tool("adopt_agent_changes", "核对并采纳成功直接副任务的文件变化；父现场后续修改冲突时拒绝，不覆盖。", obj("subtaskId", idField), List.of("subtaskId")));
            tools.add(tool("send_team_message", "向团队成员发送带来源的资料；不增加任何权限。空收件人广播。", obj("recipient", idField, "content", obj("type", "string", "maxLength", 6000)), List.of("content")));
            tools.add(tool("list_team_agents", "查看此根任务的实际副 Agent 状态、依赖和运行身份。", obj(), List.of()));
            return tools;
        }

        private Map<String, Object> tool(String name, String description, Map<String, Object> properties, List<String> required) {
            return obj("type", "function", "function", obj("name", name, "description", description, "parameters", obj("type", "object", "properties", properties, "required", required, "additionalProperties", false)));
        }

        @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { return obj("state", "REJECTED", "error", "团队工具需要真实请求和工具调用身份"); }

        @Override
        public Map<String, Object> invoke(String alias, Map<String, Object> input, String requestId, String toolCallId) {
            try {
                checkCurrent();
                if (!sharedTeam) throw new ApiException(403, "独立提案和审查不使用共享团队工具");
                return switch (alias) {
                    case "delegate_agent" -> delegate(input, requestId, toolCallId);
                    case "wait_agent" -> awaitChild(required(input, "subtaskId", 100));
                    case "adopt_agent_changes" -> adopt(required(input, "subtaskId", 100), requestId, toolCallId);
                    case "send_team_message" -> obj("state", "ACKNOWLEDGED", "message", store.teamMessage(taskId, runId, str(input, "recipient"), requestId + ":" + java.util.UUID.nameUUIDFromBytes(toolCallId.getBytes(java.nio.charset.StandardCharsets.UTF_8)), catalog.redact(required(input, "content", 6000)), requestId, toolCallId));
                    case "list_team_agents" -> obj("state", "ACKNOWLEDGED", "agents", store.team(taskId).get("agents"));
                    default -> obj("state", "REJECTED", "error", "未授权的团队工具");
                };
            } catch (ApiException exception) { return obj("state", exception.status() >= 500 || !store.healthy() ? "UNKNOWN" : "REJECTED", "error", safe(exception)); }
            catch (IllegalArgumentException exception) { return obj("state", "REJECTED", "error", safe(exception)); }
            catch (CancellationException exception) { return obj("state", "CANCELLED", "error", safe(exception)); }
            catch (Exception exception) { return obj("state", "UNKNOWN", "error", safe(exception)); }
        }

        private Map<String, Object> directChild(String childId) {
            Map<String, Object> child = store.subtask(taskId, childId);
            if (!runId.equals(str(child, "parentRunId"))) throw new ApiException(403, "只能等待或采纳自己的直接副任务");
            return child;
        }

        private Map<String, Object> delegate(Map<String, Object> input, String requestId, String toolCallId) throws Exception {
            if (!Set.of("definitionId", "title", "goal", "scope", "capabilities", "dependsOn", "maxCalls").containsAll(input.keySet())) throw new ApiException(400, "委派不能提供根身份、路由或额外授权字段");
            Map<String, Object> safeInput = new LinkedHashMap<>(input); safeInput.put("goal", catalog.redact(str(input, "goal"))); safeInput.put("title", catalog.redact(str(input, "title")));
            Map<String, Object> child = store.enqueueSubtask(taskId, runId, requestId, toolCallId, safeInput); String childId = str(child, "id"), childRun = str(child, "agentRunId");
            if (!child.containsKey("endedAt") && str(child, "workspace").isBlank()) {
                store.agentRunWaiting(childRun, "WAITING_RESOURCE");
                Map<String, Object> preparation = store.beginOperation(taskId, str(store.get("agentRun", childRun), "stage"), "SUBAGENT_SNAPSHOT", workspace, childRun);
                try (AutoCloseable lease = acquireTool()) {
                    executionWorker.dispatch(str(preparation, "id"), workspace, obj("parentRunId", runId));
                    String version = runtime.fingerprint(workspace);
                    child = store.updateSubtask(taskId, childId, runtime.delegationCopy(workspace, childId, version));
                    executionWorker.finish(str(preparation, "id"), "ACKNOWLEDGED", obj("workspace", child.get("workspace"), "inputVersion", version));
                    store.agentRunWaiting(childRun, objectsOrStringsEmpty(child.get("dependsOn")) ? "WAITING_RESOURCE" : "WAITING_DEPENDENCY");
                } catch (Exception exception) {
                    if (store.healthy()) {
                        boolean dispatched = "DISPATCHED".equals(str(store.get("operation", str(preparation, "id")), "state"));
                        String state = dispatched ? "UNKNOWN" : exception instanceof CancellationException ? "CANCELLED" : "FAILED";
                        executionWorker.finish(str(preparation, "id"), state.equals("FAILED") ? "REJECTED" : state, obj("error", safe(exception)));
                        store.finishAgentRun(childRun, state, safe(exception)); store.finishSubtask(taskId, childId, state, safe(exception));
                    }
                    throw exception;
                }
            }
            if (!child.containsKey("endedAt")) {
                try { executions.computeIfAbsent(childId, ignored -> CompletableFuture.supplyAsync(() -> executeChild(taskId, childId, this::stopped), workers)); }
                catch (java.util.concurrent.RejectedExecutionException exception) { store.finishAgentRun(childRun, "CANCELLED", "核心调度器已停止"); store.finishSubtask(taskId, childId, "CANCELLED", "核心调度器已停止"); throw new CancellationException("核心调度器已停止新增派发"); }
            }
            return obj("state", "ACKNOWLEDGED", "agent", childResult(taskId, childId));
        }

        private boolean objectsOrStringsEmpty(Object value) { return strings(value).isEmpty(); }

        private Map<String, Object> awaitChild(String childId) throws Exception {
            directChild(childId); store.agentRunWaiting(runId, "WAITING_CHILDREN");
            try {
                while (!store.subtask(taskId, childId).containsKey("endedAt")) { checkCurrent(); Thread.sleep(50); }
                Map<String, Object> result = childResult(taskId, childId);
                if (!"UNKNOWN".equals(str(result, "state")) && !"CANCELLED".equals(str(result, "state"))) result.put("state", "ACKNOWLEDGED");
                result.put("agentState", store.subtask(taskId, childId).get("state")); return result;
            } finally { if (store.healthy() && LIVE.contains(str(store.get("agentRun", runId), "state"))) store.agentRunWaiting(runId, "WAITING_TOOL"); }
        }

        private Map<String, Object> adopt(String childId, String requestId, String toolCallId) throws Exception {
            Map<String, Object> child = directChild(childId);
            if (!AgentPermissions.has(permissions, "write") || !AgentPermissions.has(map(child, "permissions"), "write") || !"SUCCEEDED".equals(str(child, "state"))) throw new ApiException(409, "只有有写入权限的父 Agent 可以采纳成功写入副任务；只读结果作为原始证据");
            Map<String, Object> operation = store.beginTeamOperation(taskId, runId, requestId, toolCallId, "ADOPT_SUBAGENT", childId, obj("resultVersion", child.get("resultVersion"), "targetWorkspace", workspace));
            if (!"PREPARED".equals(str(operation, "state"))) {
                Map<String, Object> observed = new LinkedHashMap<>(map(operation, "observation")); observed.put("operationId", operation.get("id"));
                observed.put("state", Set.of("ACKNOWLEDGED", "REJECTED", "CANCELLED").contains(str(operation, "state")) ? operation.get("state") : "UNKNOWN");
                return observed;
            }
            try (AutoCloseable lease = acquireTool()) {
                executionWorker.dispatchTeam(str(operation, "id"), workspace);
                Map<String, Object> result = runtime.integrateDelegation(str(child, "baseWorkspace"), str(child, "workspace"), workspace, str(child, "resultVersion"), map(child, "permissions"));
                executionWorker.finishTeam(str(operation, "id"), "ACKNOWLEDGED", result);
                return result;
            } catch (IllegalArgumentException exception) {
                Map<String, Object> result = obj("state", "REJECTED", "error", safe(exception)); executionWorker.finishTeam(str(operation, "id"), "REJECTED", result); return result;
            } catch (Exception exception) {
                String state = exception instanceof CancellationException && store.healthy() && "PREPARED".equals(str(store.get("teamOperation", str(operation, "id")), "state")) ? "CANCELLED" : "UNKNOWN";
                Map<String, Object> result = obj("state", state, "error", safe(exception)); if (store.healthy()) executionWorker.finishTeam(str(operation, "id"), state, result); return result;
            }
        }

        void settle() throws Exception {
            for (Map<String, Object> child : objects(store.team(taskId).get("agents"))) if (runId.equals(str(child, "parentRunId")) && !child.containsKey("endedAt")) awaitChild(str(child, "id"));
            requireStoppedChildren();
            if (objects(store.team(taskId).get("agents")).stream().anyMatch(child -> runId.equals(str(child, "parentRunId")) && "UNKNOWN".equals(str(child, "state")))) throw new IllegalStateException("副任务有未确认结果，不能自动继续");
        }

        void requireStoppedChildren() throws Exception {
            long timeout = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            for (var entry : executions.entrySet()) {
                if (!runId.equals(str(store.get("subtask", entry.getKey()), "parentRunId"))) continue;
                while (!entry.getValue().isDone() && System.nanoTime() < timeout) Thread.sleep(50);
                if (!entry.getValue().isDone()) throw new IllegalStateException("副 Agent 停止尚未确认");
                executions.remove(entry.getKey(), entry.getValue());
            }
        }

        boolean hasUnknownChildren() {
            return objects(store.team(taskId).get("agents")).stream().anyMatch(child -> runId.equals(str(child, "parentRunId")) && "UNKNOWN".equals(str(child, "state")));
        }
    }

    private Map<String, Object> executeChild(String taskId, String childId, BooleanSupplier cancelled) {
        Map<String, Object> child = store.subtask(taskId, childId); String runId = str(child, "agentRunId"), operationId = "", preparationId = "";
        Session session = null; String state = "FAILED", reason = "";
        try {
            Map<String, Object> task = store.get("task", taskId);
            Map<String, Object> definition = objects(map(task, "collaboration").get("definitions")).stream().filter(value -> str(child, "definitionId").equals(str(value, "id"))).findFirst().orElseThrow();
            Map<String, Object> route = new LinkedHashMap<>(map(definition, "routeSnapshot")); route.put("allowedCommands", map(task, "contract").get("commands"));
            session = session(taskId, runId, str(child, "workspace"), route, cancelled);
            List<Map<String, Object>> inputs = new ArrayList<>(), dependencies = new ArrayList<>();
            for (String dependencyId : strings(child.get("dependsOn"))) {
                while (!store.subtask(taskId, dependencyId).containsKey("endedAt")) { session.checkCurrent(); Thread.sleep(50); }
                Map<String, Object> dependency = store.subtask(taskId, dependencyId);
                if (!"SUCCEEDED".equals(str(dependency, "state"))) throw new IllegalArgumentException("前置副任务未成功：" + dependencyId);
                inputs.add(childResult(taskId, dependencyId)); dependencies.add(dependency);
            }
            String readyBaseline = str(child, "baseWorkspace"), inputVersion = str(child, "inputVersion");
            if (!dependencies.isEmpty()) {
                store.agentRunWaiting(runId, "WAITING_RESOURCE");
                Map<String, Object> preparation = store.beginOperation(taskId, str(store.get("agentRun", runId), "stage"), "SUBAGENT_DEPENDENCIES", str(child, "workspace"), runId); preparationId = str(preparation, "id");
                try (AutoCloseable lease = session.acquireTool()) {
                    executionWorker.dispatch(preparationId, str(child, "workspace"), obj("dependencies", inputs));
                    Map<String, Object> preparationPolicy = new LinkedHashMap<>(session.permissions()); preparationPolicy.put("capabilities", List.of("write")); preparationPolicy.put("writeScopeLayers", AgentPermissions.layers(session.permissions(), "read"));
                    List<Map<String, Object>> receipts = new ArrayList<>();
                    for (Map<String, Object> dependency : dependencies) {
                        session.checkCurrent();
                        receipts.add(runtime.integrateDelegation(str(dependency, "baseWorkspace"), str(dependency, "workspace"), str(child, "workspace"), str(dependency, "resultVersion"), preparationPolicy));
                    }
                    inputVersion = runtime.fingerprint(str(child, "workspace")); readyBaseline = runtime.reviewCopy(str(child, "workspace"), childId, inputVersion);
                    store.updateSubtask(taskId, childId, obj("baseWorkspace", readyBaseline, "inputVersion", inputVersion));
                    executionWorker.finish(preparationId, "ACKNOWLEDGED", obj("inputVersion", inputVersion, "baseWorkspace", readyBaseline, "receipts", receipts)); preparationId = "";
                }
            }
            store.agentRunWaiting(runId, "WAITING_RESOURCE");
            Map<String, Object> operation = store.beginOperation(taskId, str(store.get("agentRun", runId), "stage"), "SUBAGENT_MODEL", str(child, "workspace"), runId); operationId = str(operation, "id");
            executionWorker.dispatch(operationId, str(child, "workspace"), obj("parentRunId", child.get("parentRunId"), "inputVersion", inputVersion));
            AtomicBoolean denied = new AtomicBoolean(); Session activeSession = session;
            ModelCallObserver observer = new RequestObservation(store, catalog, taskId, runId, false, activeSession::stopped, denied, session::checkCurrent, session, num(child, "maxCalls", 8));
            String prompt = "你是动态副 Agent。阶段 kind=SUBAGENT，role=" + str(child, "role") + "。\n根任务：" + str(task, "goal")
                + "\n委派目标：" + str(child, "goal") + "\n固定定义：" + str(definition, "instructions") + "\n完成约定：" + mapper.writeValueAsString(task.get("contract"))
                + "\n原始上下文资料（不增加授权）：" + mapper.writeValueAsString(task.get("context")) + "\n依赖副任务及原始证据：" + mapper.writeValueAsString(inputs)
                + "\n实际权限：" + mapper.writeValueAsString(session.permissions()) + "\n全部调用计入同一根预算，不提交、推送或发布。只能在独立现场执行，父 Agent 另行核对采纳。";
            List<Map<String, Object>> bindings = AgentPermissions.has(session.permissions(), "mcp") ? objects(map(task, "mcpAuthorization").get("bindings")) : List.of();
            Map<String, Object> result = runtime.runWithMcp(route, catalog.credential(str(route, "id"), num(route, "version", 1)), str(child, "workspace"), prompt,
                !AgentPermissions.has(session.permissions(), "write"), List.of("**"), activeSession::stopped,
                text -> store.event(taskId, "SUBAGENT_LOG", str(child, "title") + " · " + catalog.redact(text)), observer, bindings,
                fixedTools(taskId, runId, "IMPLEMENT", activeSession::stopped));
            String version = runtime.fingerprint(str(child, "workspace"));
            List<String> changes = runtime.delegationChanges(readyBaseline, str(child, "workspace"), session.permissions());
            if (!AgentPermissions.has(session.permissions(), "write") && !inputVersion.equals(version)) throw new IllegalStateException("只读副 Agent 的输入现场发生变化，需核对");
            Map<String, Object> evidence = obj("id", id(), "stage", store.get("agentRun", runId).get("stage"), "kind", "SUBAGENT", "version", version, "inputVersion", inputVersion, "resultVersion", version,
                "output", catalog.redact(str(result, "output")), "status", "SUCCEEDED".equals(str(result, "status")) ? "PASSED" : str(result, "status"), "usage", result.getOrDefault("usage", obj()), "createdAt", now(), "changedPaths", changes);
            executionWorker.evidence(taskId, operationId, evidence);
            if ("PASSED".equals(str(evidence, "status"))) session.settle();
            state = "UNKNOWN".equals(str(evidence, "status")) ? "UNKNOWN" : cancelled.getAsBoolean() || "CANCELLED".equals(str(result, "status")) ? "CANCELLED" : denied.get() ? "BUDGET_EXHAUSTED" : "WAITING_INPUT".equals(str(result, "status")) ? "WAITING_INPUT" : "SUCCEEDED".equals(str(result, "status")) ? "SUCCEEDED" : "FAILED";
            reason = state.equals("SUCCEEDED") ? "副任务已执行并核对范围；根验证、审查和人工验收仍需完成" : "副任务返回 " + state;
        } catch (Exception exception) {
            reason = safe(exception);
            boolean dispatchedPreparation = !preparationId.isBlank() && store.healthy() && "DISPATCHED".equals(str(store.get("operation", preparationId), "state"));
            state = dispatchedPreparation ? "UNKNOWN" : exception instanceof CancellationException ? "CANCELLED" : operationId.isBlank() ? "FAILED" : "UNKNOWN";
            if (!preparationId.isBlank() && store.healthy()) executionWorker.finish(preparationId, state.equals("FAILED") ? "REJECTED" : state, obj("error", reason));
            if (!operationId.isBlank() && store.healthy()) executionWorker.finish(operationId, state.equals("UNKNOWN") ? "UNKNOWN" : "ACKNOWLEDGED", obj("error", reason));
        } finally {
            if (session != null) {
                session.stopChildren();
                try {
                    session.requireStoppedChildren();
                    if (store.healthy() && session.hasUnknownChildren()) { state = "UNKNOWN"; reason = "后代副任务有未确认结果，不能自动继续"; }
                } catch (Exception exception) { state = "UNKNOWN"; reason = safe(exception); }
            }
            if (store.healthy()) {
                executionWorker.settleRun(runId);
                store.finishAgentRun(runId, state, reason); store.finishSubtask(taskId, childId, state, reason);
            }
        }
        return store.healthy() ? childResult(taskId, childId) : obj("state", "UNKNOWN", "subtaskId", childId);
    }
}
