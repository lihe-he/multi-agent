package dev.agentworkbench;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;
import static dev.agentworkbench.Json.*;

/**
 * 根任务持久队列。只领取未开始的原动作，已领取的未知结果永不自动重放。
 */
final class TaskDispatchQueue {
    static final Set<String> EXECUTION_STATES = Set.of("QUEUED", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN", "PAUSED", "CANCELLED", "WAITING_INPUT");
    static final Set<String> ACTION_STATES = Set.of("PENDING", "CLAIMED", "SETTLED", "WITHDRAWN", "UNKNOWN");
    private final ExecutionStore store;
    private final WorkspaceWriteLedger workspaceWrites;
    private final Supplier<Map<String, Object>> writerIdentity;

    TaskDispatchQueue(ExecutionStore store) { this(store, null, Map::of); }

    TaskDispatchQueue(ExecutionStore store, WorkspaceWriteLedger workspaceWrites, Supplier<Map<String, Object>> writerIdentity) {
        this.store = store; this.workspaceWrites = workspaceWrites; this.writerIdentity = writerIdentity;
    }

    Map<String, Object> enqueue(String taskId, String trigger, Consumer<Map<String, Object>> edit) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId);
            if (!Set.of("DRAFT", "PAUSED", "WAITING_INPUT", "FAILED").contains(str(task, "state"))) throw new ApiException(409, "当前任务不能建立新的执行尝试");
            if (store.hasUnknownTaskExecution(taskId) || store.hasUnknownEffects(taskId)) throw new ApiException(409, "根执行或外部效果尚未核对，不能建立新尝试");
            String previousState = str(task, "state"), time = now(), executionId = id(), actionId = id();
            edit.accept(task);
            if (!bool(map(task, "contract"), "confirmed", false)) throw new ApiException(409, "完成约定尚未确认");
            int attempt = store.nextTaskExecutionAttempt(taskId);
            Map<String, Object> execution = obj("id", executionId, "schemaVersion", 1, "taskId", taskId, "rootTaskId", taskId,
                "projectId", task.get("projectId"), "sessionId", task.getOrDefault("sessionId", ""), "attempt", attempt, "trigger", trigger,
                "state", "QUEUED", "createdAt", time, "dispatchActionId", actionId, "sourceTaskState", previousState,
                "policyVersion", task.getOrDefault("policyVersion", ""), "budgetAtEnqueue", new LinkedHashMap<>(map(task, "budget")),
                "contractVersion", num(map(task, "contract"), "version", 1), "authorizationVersion", num(map(task, "execution"), "version", 0));
            if (task.containsKey("workspaceSelection")) execution.put("workspaceSelection", task.get("workspaceSelection"));
            Map<String, Object> action = obj("id", actionId, "schemaVersion", 1, "kind", "ROOT_TASK", "taskId", taskId,
                "projectId", task.get("projectId"), "sessionId", task.getOrDefault("sessionId", ""), "taskExecutionId", executionId,
                "attempt", attempt, "state", "PENDING", "version", 1, "dispatchCount", 0, "createdAt", time,
                "enqueuedCoreSessionId", store.coreSessionId(), "authorization", authorization(task));
            task.putAll(obj("state", "QUEUED", "taskExecutionId", executionId, "dispatchActionId", actionId, "updatedAt", time));
            task.remove("workspaceWriteLeaseId");
            task.remove("error"); summarize(task, execution, action);
            List<Map<String, Object>> events = new ArrayList<>();
            if ("USER_APPROVAL".equals(trigger)) events.add(obj("type", "PLAN_APPROVED", "message", "用户确认了完成约定、范围、路由与预算"));
            if ("USER_RESUME".equals(trigger)) events.add(obj("type", "CONTEXT_REFRESHED", "message", "按当前有效记忆重新组装上下文，旧证据保留"));
            events.add(obj("type", "QUEUED", "message", "任务与第 " + attempt + " 次执行已同事务进入持久队列，动作 " + actionId + " 尚未领取"));
            store.commitRecords(records(task, execution, action), taskId, events);
            return action;
        }
    }

    /**
     * 原状态、版本、任务指针及冻结授权在同一 owner 锁内复查；竞争失败不产生执行。
     */
    Map<String, Object> claim(String actionId, int expectedVersion, Consumer<Map<String, Object>> preflight) {
        synchronized (store) {
            Map<String, Object> action = store.find("dispatchAction", actionId);
            if (action == null || !"PENDING".equals(str(action, "state")) || num(action, "version", 0) != expectedVersion || num(action, "dispatchCount", -1) != 0) return Map.of();
            Map<String, Object> task = store.get("task", str(action, "taskId")), execution = store.get("taskExecution", str(action, "taskExecutionId"));
            if (!unclaimed(task, execution, action) || !map(action, "authorization").equals(authorization(task))) {
                waiting(actionId, "原任务状态或冻结授权已变化，请暂停队列并重新确认"); return Map.of();
            }
            if (store.hasUnknownEffects(str(task, "id")) || store.hasUnknownTaskExecution(str(task, "id"))) {
                waiting(actionId, "存在尚未核对的根执行或外部效果，当前动作不会领取"); return Map.of();
            }
            preflight.accept(task);
            Map<String, Object> writeLease = workspaceWrites == null ? obj() : workspaceWrites.intent(task, execution, action, writerIdentity.get());
            String time = now();
            Map<String, Object> owner = obj("ownerCoreSessionId", store.coreSessionId(), "ownerCorePid", ProcessHandle.current().pid(),
                "ownerCoreStartedAt", ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""));
            action.putAll(owner); action.putAll(obj("state", "CLAIMED", "version", expectedVersion + 1, "dispatchCount", 1, "claimedAt", time)); action.remove("waitReason");
            execution.putAll(owner); execution.putAll(obj("state", "RUNNING", "startedAt", time, "budgetAtStart", new LinkedHashMap<>(map(task, "budget"))));
            if (!writeLease.isEmpty()) {
                task.put("workspaceWriteLeaseId", writeLease.get("id")); execution.put("workspaceWriteLeaseId", writeLease.get("id")); action.put("workspaceWriteLeaseId", writeLease.get("id"));
            }
            task.put("updatedAt", time); task.remove("error"); summarize(task, execution, action);
            return store.claimDispatch(task, execution, action, expectedVersion, writeLease) ? execution : Map.of();
        }
    }

    void waiting(String actionId, String reason) {
        synchronized (store) {
            Map<String, Object> action = store.find("dispatchAction", actionId);
            if (action == null || !"PENDING".equals(str(action, "state")) || reason.equals(str(action, "waitReason"))) return;
            Map<String, Object> task = store.get("task", str(action, "taskId")), execution = store.get("taskExecution", str(action, "taskExecutionId"));
            action.put("waitReason", reason); action.put("observedAt", now());
            if (actionId.equals(str(task, "dispatchActionId"))) { task.put("updatedAt", now()); summarize(task, execution, action); }
            store.commitRecords(records(task, execution, action), str(task, "id"), "DISPATCH_WAITING", "未领取动作等待条件：" + reason);
        }
    }

    /**
     * 未领取的暂停与取消撤回原动作，不生成 Control，也不发送进程停止信号。
     */
    boolean withdraw(String taskId, String state, String reason) {
        synchronized (store) {
            if (!Set.of("PAUSED", "CANCELLED").contains(state)) throw new IllegalArgumentException("Invalid withdrawal state");
            Map<String, Object> task = store.get("task", taskId), action = store.find("dispatchAction", str(task, "dispatchActionId"));
            if (action == null || !"PENDING".equals(str(action, "state")) || num(action, "dispatchCount", -1) != 0) return false;
            Map<String, Object> execution = store.get("taskExecution", str(action, "taskExecutionId"));
            task.putAll(obj("state", state, "updatedAt", now(), "stoppedAt", now()));
            if ("CANCELLED".equals(state)) task.put("outcome", "CANCELLED");
            end(execution, task, state, reason); action.putAll(obj("state", "WITHDRAWN", "version", num(action, "version", 1) + 1, "endedAt", now(), "reason", reason));
            summarize(task, execution, action);
            store.commitRecords(records(task, execution, action), taskId, "CONTROL", reason + "；原动作未领取，未开始任何执行");
            return true;
        }
    }

    void finish(String executionId) {
        synchronized (store) {
            Map<String, Object> execution = store.get("taskExecution", executionId);
            if (!"RUNNING".equals(str(execution, "state"))) return;
            Map<String, Object> task = store.get("task", str(execution, "taskId")), action = store.get("dispatchAction", str(execution, "dispatchActionId"));
            if (!store.coreSessionId().equals(str(action, "ownerCoreSessionId")) || !"CLAIMED".equals(str(action, "state"))) throw new ApiException(409, "根执行终态缺少本次核心原领取");
            Map<String, Object> writeLease = store.find("workspaceWriteLease", str(execution, "workspaceWriteLeaseId"));
            if (writeLease != null) {
                if (!bool(writeLease, "protectionReleased", false)) {
                    writeLease.put("state", "UNKNOWN"); writeLease.putIfAbsent("lostAt", now()); writeLease.putIfAbsent("reason", "根结束缺少原现场写入保护的完整释放回执");
                    unresolved(task, "原现场写入保护尚未完成收尾，保留原根归属供核对");
                }
                execution.putAll(obj("workspaceWriteStateAtEnd", writeLease.get("state"), "workspaceWriteProtectionReleased", writeLease.get("protectionReleased")));
            }
            if (store.hasUnknownEffects(str(task, "id")) && executionId.equals(str(task, "taskExecutionId"))) unresolved(task, "根执行收尾仍有未确认效果，保留原动作供核对");
            String state = switch (str(task, "state")) {
                case "AWAITING_ACCEPTANCE", "COMPLETED" -> "SUCCEEDED";
                case "FAILED", "PAUSED", "CANCELLED", "WAITING_INPUT" -> str(task, "state");
                default -> "UNKNOWN";
            };
            end(execution, task, state, str(task, "error"));
            action.putAll(obj("state", "UNKNOWN".equals(state) ? "UNKNOWN" : "SETTLED", "version", num(action, "version", 1) + 1, "endedAt", now(), "executionState", state));
            if (executionId.equals(str(task, "taskExecutionId"))) { task.put("updatedAt", now()); summarize(task, execution, action); }
            Map<String, List<Map<String, Object>>> records = records(task, execution, action);
            if (writeLease != null && !bool(writeLease, "protectionReleased", false)) records.put("workspaceWriteLease", List.of(writeLease));
            store.commitRecords(records, str(task, "id"), "TASK_EXECUTION_FINISHED", "根执行 " + executionId + "：" + state + "；任务状态与人工验收另行保存");
        }
    }

    /**
     * 重开只封闭已领取动作；PENDING 的原编号和从未执行事实保持不变。
     */
    void recover() {
        for (Map<String, Object> original : store.list("dispatchAction")) {
            if (!"CLAIMED".equals(str(original, "state"))) continue;
            synchronized (store) {
                Map<String, Object> action = store.get("dispatchAction", str(original, "id")), execution = store.get("taskExecution", str(action, "taskExecutionId")), task = store.get("task", str(action, "taskId"));
                if (!"CLAIMED".equals(str(action, "state"))) continue;
                String reason = "核心重启，原动作已领取但根结束回执缺失；保留原身份和预算，不自动重放";
                if (str(execution, "id").equals(str(task, "taskExecutionId")) && !Set.of("COMPLETED", "CANCELLED").contains(str(task, "state"))) task.putAll(obj("state", "UNKNOWN", "error", reason, "updatedAt", now()));
                if ("RUNNING".equals(str(execution, "state"))) end(execution, task, "UNKNOWN", reason);
                action.putAll(obj("state", "UNKNOWN", "version", num(action, "version", 1) + 1, "endedAt", now(), "reason", reason, "executionState", "UNKNOWN"));
                if (str(execution, "id").equals(str(task, "taskExecutionId"))) summarize(task, execution, action);
                store.commitRecords(records(task, execution, action), str(task, "id"), "RECOVERY_REQUIRED", reason);
            }
        }
    }

    boolean unclaimed(Map<String, Object> task) {
        synchronized (store) {
            Map<String, Object> action = store.find("dispatchAction", str(task, "dispatchActionId")), execution = store.find("taskExecution", str(task, "taskExecutionId"));
            return action != null && execution != null && unclaimed(task, execution, action);
        }
    }

    void reconcile(String taskId, Map<String, Object> body, Consumer<Map<String, Object>> edit) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId);
            List<Map<String, Object>> executions = new ArrayList<>(), actions = new ArrayList<>();
            Map<String, Object> confirmation = obj("actor", "USER", "note", str(body, "note"), "processesStopped", true, "sideEffectsChecked", true, "createdAt", now());
            for (Map<String, Object> execution : store.taskDispatchRecords("taskExecution", taskId)) if ("UNKNOWN".equals(str(execution, "state")) && !execution.containsKey("reconciliation")) { execution.put("reconciliation", confirmation); executions.add(execution); }
            for (Map<String, Object> action : store.taskDispatchRecords("dispatchAction", taskId)) if ("UNKNOWN".equals(str(action, "state")) && !action.containsKey("reconciliation")) { action.put("reconciliation", confirmation); actions.add(action); }
            edit.accept(task); task.put("updatedAt", now());
            if (!str(task, "taskExecutionId").isBlank()) {
                Map<String, Object> execution = executions.stream().filter(item -> str(item, "id").equals(str(task, "taskExecutionId"))).findFirst().orElseGet(() -> store.get("taskExecution", str(task, "taskExecutionId")));
                Map<String, Object> action = actions.stream().filter(item -> str(item, "id").equals(str(task, "dispatchActionId"))).findFirst().orElseGet(() -> store.get("dispatchAction", str(task, "dispatchActionId")));
                summarize(task, execution, action);
            }
            store.commitRecords(Map.of("task", List.of(task), "taskExecution", executions, "dispatchAction", actions), taskId, "RECONCILED", "人工核对后保持暂停；原 UNKNOWN 和预算保留，下一次恢复建立新尝试");
        }
    }

    private static boolean unclaimed(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action) {
        return "QUEUED".equals(str(task, "state")) && "QUEUED".equals(str(execution, "state")) && "PENDING".equals(str(action, "state")) && num(action, "dispatchCount", -1) == 0
            && str(task, "id").equals(str(execution, "taskId")) && str(task, "id").equals(str(action, "taskId"))
            && str(task, "taskExecutionId").equals(str(execution, "id")) && str(task, "dispatchActionId").equals(str(action, "id"))
            && str(execution, "dispatchActionId").equals(str(action, "id")) && str(action, "taskExecutionId").equals(str(execution, "id")) && !action.containsKey("claimedAt");
    }

    private static Map<String, Object> authorization(Map<String, Object> task) {
        Map<String, Object> authorization = new LinkedHashMap<>();
        for (String field : List.of("contract", "execution", "workspaceSelection", "routeId", "routeSnapshot", "locks", "mcpAuthorization", "collaboration", "policyVersion")) if (task.containsKey(field)) authorization.put(field, task.get(field));
        return authorization;
    }

    private static Map<String, List<Map<String, Object>>> records(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action) {
        Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>();
        records.put("task", List.of(task)); records.put("taskExecution", List.of(execution)); records.put("dispatchAction", List.of(action)); return records;
    }

    private static void end(Map<String, Object> execution, Map<String, Object> task, String state, String reason) {
        execution.putAll(obj("state", state, "endedAt", now(), "reason", reason, "taskStateAtEnd", task.get("state"), "taskOutcomeAtEnd", task.getOrDefault("outcome", "UNDETERMINED"),
            "budgetAtEnd", new LinkedHashMap<>(map(task, "budget")), "workspace", task.getOrDefault("workspace", ""), "resultVersion", task.getOrDefault("version", "")));
        execution.put("elapsedMs", Math.max(0, Duration.between(Instant.parse(str(execution, "createdAt")), Instant.parse(str(execution, "endedAt"))).toMillis()));
        if (task.containsKey("workspaceOwnership")) execution.put("workspaceOwnership", task.get("workspaceOwnership"));
    }

    private static void unresolved(Map<String, Object> task, String reason) {
        String previous = str(task, "error");
        task.putAll(obj("state", "UNKNOWN", "error", previous.isBlank() ? reason : previous.contains(reason) ? previous : previous + "\n" + reason));
    }

    private static void summarize(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action) {
        task.put("taskExecution", obj("id", execution.get("id"), "attempt", execution.get("attempt"), "state", execution.get("state"), "createdAt", execution.get("createdAt"),
            "endedAt", execution.getOrDefault("endedAt", ""), "reconciled", execution.containsKey("reconciliation")));
        task.put("dispatch", obj("id", action.get("id"), "state", action.get("state"), "dispatchCount", action.get("dispatchCount"), "createdAt", action.get("createdAt"),
            "claimedAt", action.getOrDefault("claimedAt", ""), "waitReason", action.getOrDefault("waitReason", "")));
    }
}
