package dev.agentworkbench;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 根现场写入归属独立于模型容量；原进程退出不自动解除未知效果的保护。
 */
final class WorkspaceWriteLedger {
    static final Set<String> STATES = Set.of("PREPARED", "HELD", "RELEASED", "UNKNOWN");
    private static final List<String> IDENTITY_FIELDS = List.of("schemaVersion", "id", "taskId", "projectId", "taskExecutionId", "dispatchActionId", "attempt",
        "scopePath", "scopeKey", "workerId", "executionNodeId", "workerEpoch", "executorPid", "processStartedAt",
        "ownerCoreSessionId", "ownerCorePid", "ownerCoreStartedAt", "authorizationVersion", "workspaceSelectionVersion", "strategy", "baseline");
    private final ExecutionStore store;

    WorkspaceWriteLedger(ExecutionStore store) { this.store = store; }

    /**
     * 返回待承诺的原意图，由根领取事务一并保存，不在这里单独取得写入权。
     */
    Map<String, Object> intent(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action, Map<String, Object> worker) {
        if (!str(task, "id").equals(str(execution, "taskId")) || !str(task, "id").equals(str(action, "taskId"))
                || !str(execution, "id").equals(str(action, "taskExecutionId")) || !str(action, "id").equals(str(execution, "dispatchActionId"))) throw new ApiException(403, "写入意图不属于原根尝试与动作");
        Path scope = scope(task); String scopeKey = key(scope);
        Map<String, Object> blocker = store.protectedWorkspaceWriteLease(scopeKey);
        if (blocker != null) throw new ApiException(409, "现场仍受原写入归属保护：" + str(blocker, "id") + "，请等待收尾或核对旧执行");
        long executorPid = positive(worker, "pid");
        if (str(worker, "id").isBlank() || str(worker, "executionNodeId").isBlank() || str(worker, "processStartedAt").isBlank()
                || num(worker, "epoch", 0) < 1) throw new ApiException(403, "写入意图缺少实际 Worker 身份");
        String coreBirth = ProcessHandle.current().info().startInstant().map(Instant::toString).orElse("");
        if (coreBirth.isBlank()) throw new ApiException(409, "核心出生身份不可核验，不能分配现场写入归属");
        Map<String, Object> selection = map(task, "workspaceSelection");
        return obj("schemaVersion", 1, "id", id(), "taskId", task.get("id"), "projectId", task.get("projectId"),
            "taskExecutionId", execution.get("id"), "dispatchActionId", action.get("id"), "attempt", num(execution, "attempt", 1), "scopePath", scope.toString(), "scopeKey", scopeKey,
            "workerId", worker.get("id"), "executionNodeId", worker.get("executionNodeId"), "workerEpoch", worker.get("epoch"),
            "executorPid", executorPid, "processStartedAt", worker.get("processStartedAt"), "ownerCoreSessionId", store.coreSessionId(),
            "ownerCorePid", ProcessHandle.current().pid(), "ownerCoreStartedAt", coreBirth, "authorizationVersion", num(map(task, "execution"), "version", 0),
            "workspaceSelectionVersion", num(selection, "version", 0), "strategy", selection.getOrDefault("strategy", "CLONE"), "baseline", selection.getOrDefault("baseline", task.getOrDefault("baseline", "")),
            "state", "PREPARED", "protectionReleased", false, "createdAt", now());
    }

    /**
     * 稳定身份不含可变化的状态；私有帧和真实锁回执沿用相同原来源。
     */
    static Map<String, Object> identity(Map<String, Object> lease) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : IDENTITY_FIELDS) if (lease.containsKey(field)) result.put(field, lease.get(field));
        for (String field : List.of("schemaVersion", "attempt", "workerEpoch", "authorizationVersion", "workspaceSelectionVersion")) if (result.containsKey(field)) result.put(field, num(result, field, -1));
        for (String field : List.of("executorPid", "ownerCorePid")) if (result.containsKey(field)) result.put(field, positive(result, field));
        return result;
    }

    Map<String, Object> require(Map<String, Object> task, Map<String, Object> worker, boolean preparing) {
        String leaseId = str(task, "workspaceWriteLeaseId");
        if (leaseId.isBlank()) {
            if ("LOCAL_PROCESS".equals(str(worker, "deployment")) && !str(task, "taskExecutionId").isBlank()) throw new ApiException(409, "本次独立 Worker 根尝试缺少现场写入意图");
            return obj();
        }
        Map<String, Object> lease = store.get("workspaceWriteLease", leaseId);
        if (bool(lease, "protectionReleased", false) || !Set.of("PREPARED", "HELD").contains(str(lease, "state"))
                || (!preparing && !"HELD".equals(str(lease, "state"))) || !store.coreSessionId().equals(str(lease, "ownerCoreSessionId"))
                || !str(task, "id").equals(str(lease, "taskId")) || !str(task, "taskExecutionId").equals(str(lease, "taskExecutionId"))
                || !str(task, "dispatchActionId").equals(str(lease, "dispatchActionId")) || !str(worker, "id").equals(str(lease, "workerId"))
                || !str(worker, "executionNodeId").equals(str(lease, "executionNodeId"))
                || num(worker, "epoch", -1) != num(lease, "workerEpoch", -2) || positive(worker, "pid") != positive(lease, "executorPid")
                || !str(worker, "processStartedAt").equals(str(lease, "processStartedAt"))
                || num(map(task, "execution"), "version", 0) != num(lease, "authorizationVersion", -1)
                || num(map(task, "workspaceSelection"), "version", 0) != num(lease, "workspaceSelectionVersion", -1)
                || !scope(task).toString().equals(str(lease, "scopePath"))) throw new ApiException(409, "原根现场写入归属已失效，不允许继续调用");
        return identity(lease);
    }

    /**
     * 实际锁回执与操作 ACK 共用事务，尚未取得锁的意图不能用于模型或验证。
     */
    Map<String, Object> granted(Map<String, Object> operation, Map<String, Object> facts) {
        Map<String, Object> grant = map(facts, "workspaceWriteGrant");
        if (grant.isEmpty()) return obj();
        Map<String, Object> lease = store.get("workspaceWriteLease", str(map(grant, "identity"), "id"));
        store.requireActiveTaskExecution(store.get("task", str(lease, "taskId")), operation);
        Map<String, Object> workerLease = store.get("workerLease", str(operation, "workerLeaseId"));
        Path parent = Path.of(str(lease, "scopePath")).getParent();
        if (!Set.of("WORKSPACE", "WORKSPACE_WRITE").contains(str(operation, "kind")) || !"PREPARED".equals(str(lease, "state"))
                || !str(operation, "taskExecutionId").equals(str(lease, "taskExecutionId")) || !str(operation, "dispatchActionId").equals(str(lease, "dispatchActionId"))
                || !str(operation, "workspaceWriteLeaseId").equals(str(lease, "id")) || !identity(lease).equals(identity(map(workerLease, "workspaceWriteIdentity")))
                || !identity(lease).equals(identity(map(grant, "identity"))) || !bool(grant, "lockValid", false)
                || str(grant, "fileKey").isBlank() || !parent.resolve("writer.lock").toString().equals(str(grant, "lockPath"))
                || !parent.resolve("writer.json").toString().equals(str(grant, "manifestPath")) || !"HELD".equals(str(grant, "state"))
                || positive(grant, "executorPid") != positive(lease, "executorPid") || !str(grant, "processStartedAt").equals(str(lease, "processStartedAt"))) throw new ApiException(403, "真实现场写入锁回执不属于原根意图");
        lease.putAll(obj("state", "HELD", "grantedAt", now(), "grant", grant)); return lease;
    }

    Map<String, Object> current(String taskId) {
        Map<String, Object> task = store.get("task", taskId);
        return str(task, "workspaceWriteLeaseId").isBlank() ? obj() : store.get("workspaceWriteLease", str(task, "workspaceWriteLeaseId"));
    }

    void unknown(String leaseId, String reason) {
        synchronized (store) {
            Map<String, Object> lease = store.get("workspaceWriteLease", leaseId);
            if (bool(lease, "protectionReleased", false) || "UNKNOWN".equals(str(lease, "state"))) return;
            lease.putAll(obj("state", "UNKNOWN", "lostAt", now(), "reason", reason));
            store.commitRecords(Map.of("workspaceWriteLease", List.of(lease)), str(lease, "taskId"), "WORKSPACE_WRITE_UNKNOWN", reason);
        }
    }

    /**
     * UNKNOWN 保留原状态，人工核对另存依据；OS 锁的关闭事实不替代外部效果核对。
     */
    void released(String leaseId, Map<String, Object> receipt, String note) {
        synchronized (store) {
            Map<String, Object> lease = store.get("workspaceWriteLease", leaseId);
            if (!identity(lease).equals(identity(map(receipt, "identity"))) || !bool(receipt, "protectionReleased", false)) throw new ApiException(403, "释放回执不属于原现场写入归属");
            if (bool(receipt, "nativeOwnerAbsent", false) && !map(lease, "grant").isEmpty()) throw new ApiException(409, "原写入锁已取得成功回执，外国清单或锁文件缺失不能证明原保护已释放");
            if (bool(lease, "protectionReleased", false)) return;
            if ("UNKNOWN".equals(str(lease, "state")) && note.isBlank()) throw new ApiException(409, "未知写入归属需要先核对原进程和外部效果");
            lease.putAll(obj("protectionReleased", true, "releasedAt", now(), "release", receipt));
            if ("UNKNOWN".equals(str(lease, "state"))) lease.put("reconciliation", obj("actor", "USER", "note", note, "createdAt", now()));
            else lease.put("state", "RELEASED");
            store.commitRecords(Map.of("workspaceWriteLease", List.of(lease)), str(lease, "taskId"), "WORKSPACE_WRITE_RELEASED", note.isBlank() ? "原根调用已收尾，现场写入保护释放" : "用户核对旧进程与效果后释放原现场写入保护");
        }
    }

    void recover() {
        for (Map<String, Object> lease : store.list("workspaceWriteLease")) if (!bool(lease, "protectionReleased", false) && !store.coreSessionId().equals(str(lease, "ownerCoreSessionId"))) {
            unknown(str(lease, "id"), "核心会话更换，原写入归属和实际进程需核对；不自动接管现场");
        }
    }

    private Path scope(Map<String, Object> task) {
        String taskId = str(task, "id");
        if (!taskId.matches("[a-zA-Z0-9_-]{1,100}")) throw new ApiException(400, "无效任务现场编号");
        try {
            Path root = store.directory().toRealPath(), expected = root.resolve("workspaces").resolve(taskId).resolve("work");
            for (Path current = expected; !current.equals(root); current = current.getParent()) {
                if (Files.exists(current, LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current))) throw new ApiException(409, "写入现场的目录解析目标已变化");
            }
            if (!str(task, "workspace").isBlank() && !Path.of(str(task, "workspace")).toRealPath().equals(expected)) throw new ApiException(409, "写入目标不属于原任务的隔离现场");
            return expected;
        } catch (java.io.IOException exception) { throw new ApiException(409, "原写入现场或核心数据目录无法解析"); }
    }

    private static String key(Path scope) { return System.getProperty("os.name", "").startsWith("Windows") ? scope.toString().toLowerCase(Locale.ROOT) : scope.toString(); }

    private static long positive(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Number number) || number.longValue() < 1 || number.doubleValue() != number.longValue()) throw new ApiException(403, "写入归属缺少可核验的 " + field);
        return number.longValue();
    }
}
