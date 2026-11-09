package dev.agentworkbench;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 租约、派发和回执原子落库；过期只失去归属，不证明旧进程或远端效果已停止。
 */
final class WorkerLedger {
    static final long LEASE_MILLIS = 30_000;
    private final ExecutionStore store;
    private final ExecutionNodes nodes;

    WorkerLedger(ExecutionStore store, ExecutionNodes nodes) { this.store = store; this.nodes = nodes; }

    Map<String, Object> start(Instant time) {
        return start(time, obj("deployment", "CORE_PROCESS", "pid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().map(Instant::toString).orElse(""), "protocolVersion", 1));
    }

    /**
     * 独立进程身份来自已完成握手的实际句柄，核心计时器不能代替子进程心跳。
     */
    Map<String, Object> start(Instant time, Map<String, Object> process) {
        synchronized (store) {
            Map<String, Object> node = nodes.localNode();
            if (!Set.of("CORE_PROCESS", "LOCAL_PROCESS").contains(str(process, "deployment")) || !(process.get("pid") instanceof Number actualPid)
                    || actualPid.longValue() < 1 || str(process, "processStartedAt").isBlank() || num(process, "protocolVersion", -1) != 1) throw new ApiException(403, "Worker 缺少已核验的实际进程身份");
            long pid = actualPid.longValue(); String processStart = str(process, "processStartedAt");
            List<Map<String, Object>> oldWorkers = store.list("worker").stream().filter(worker -> str(node, "id").equals(str(worker, "executionNodeId")) && "RUNNING".equals(str(worker, "state"))).toList();
            if (oldWorkers.stream().anyMatch(worker -> ((Number) worker.get("pid")).longValue() == pid && processStart.equals(str(worker, "processStartedAt")))) throw new ApiException(409, "当前核心进程已有本机 Worker，请先确认旧执行停止");
            node.put("epoch", num(node, "epoch", 0) + 1);
            Map<String, Object> worker = obj("id", id(), "executionNodeId", node.get("id"), "epoch", node.get("epoch"), "protocolVersion", 1,
                "deployment", process.get("deployment"), "pid", pid, "processStartedAt", processStart, "startedAt", time.toString(), "heartbeatAt", time.toString(),
                "expiresAt", time.plusMillis(LEASE_MILLIS).toString(), "state", "RUNNING", "platform", System.getProperty("os.name"), "architecture", System.getProperty("os.arch"));
            for (String field : List.of("parentPid", "corePid", "coreStartedAt", "processSessionId", "controlEnvironmentPresent", "jvmOptionsEnvironmentPresent", "controlPropertiesPresent")) if (process.containsKey(field)) worker.put(field, process.get(field));
            worker.put("heartbeatSource", "LOCAL_PROCESS".equals(str(process, "deployment")) ? "STARTUP_HANDSHAKE" : "CORE_TIMER"); worker.put("lastHeartbeatSequence", 0L);
            node.put("workerId", worker.get("id")); node.put("lastSeenAt", time.toString()); node.put("deployment", worker.get("deployment"));
            for (Map<String, Object> old : oldWorkers) old.putAll(obj("state", "LOST", "endedAt", time.toString(), "stopState", "UNKNOWN", "reason", "核心会话更换，旧执行不自动重放"));
            List<Map<String, Object>> workerRecords = new ArrayList<>(oldWorkers); workerRecords.add(worker);
            Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>(); records.put("executionNode", List.of(node)); records.put("worker", workerRecords);
            List<Map<String, Object>> abandoned = store.list("workerLease").stream().filter(lease -> str(node, "id").equals(str(lease, "executionNodeId")) && "ACTIVE".equals(str(lease, "state"))).toList();
            appendUnknown(records, abandoned, "核心会话更换，旧租约结果需核对", time);
            store.commitRecords(records, null, null, null);
            return worker;
        }
    }

    void requireOwner(Map<String, Object> identity, Instant time) {
        Map<String, Object> worker = store.get("worker", str(identity, "id")), node = store.get("executionNode", str(identity, "executionNodeId"));
        if (!"RUNNING".equals(str(worker, "state")) || !str(identity, "id").equals(str(node, "workerId")) || num(identity, "epoch", -1) != num(node, "epoch", -2)
                || !samePid(worker, identity) || !str(identity, "processStartedAt").equals(str(worker, "processStartedAt")) || !str(identity, "deployment").equals(str(worker, "deployment"))
                || !time.isBefore(Instant.parse(str(worker, "expiresAt")))) throw new ApiException(409, "Worker 归属或租约已失效，不允许继续派发");
    }

    Map<String, Object> claim(Map<String, Object> worker, String kind, String operationId, String workspace, String token, Map<String, Object> details, Instant time) {
        synchronized (store) {
            requireOwner(worker, time);
            Map<String, Object> operation = store.get(kind, operationId), task = store.get("task", str(operation, "taskId"));
            if (!"PREPARED".equals(str(operation, "state"))) throw new ApiException(409, "操作已领取或结束，不能再次派发");
            store.requireActiveTaskExecution(task, operation);
            nodes.requireCurrent(task);
            if (store.hasUnknownEffects(str(task, "id")) || store.hasUnknownTaskExecution(str(task, "id"))) throw new ApiException(409, "根任务有未核对效果，新租约不能绕过");
            if (store.workerLeaseForOperation(kind, operationId) != null) throw new ApiException(409, "同一操作只能有一次执行租约");
            Map<String, Object> execution = map(task, "execution");
            if (!execution.isEmpty() && !str(worker, "executionNodeId").equals(str(execution, "executionNodeId"))) throw new ApiException(403, "Worker 不属于任务固定节点");
            requireWorkspace(operation, task, workspace);
            String digest = digest(token), leaseId = id();
            Map<String, Object> lease = obj("id", leaseId, "operationKind", kind, "operationId", operationId, "taskId", task.get("id"), "projectId", task.get("projectId"),
                "agentRunId", operation.getOrDefault("agentRunId", ""), "workerId", worker.get("id"), "executionNodeId", worker.get("executionNodeId"), "epoch", worker.get("epoch"),
                "repositoryBindingId", execution.getOrDefault("repositoryBindingId", ""), "bindingVersion", execution.getOrDefault("bindingVersion", 0), "authorizationVersion", execution.getOrDefault("version", 0),
                "claimDigest", digest, "state", "ACTIVE", "stopState", "RUNNING", "workingDirectory", Path.of(workspace).toAbsolutePath().normalize().toString(),
                "createdAt", time.toString(), "heartbeatAt", time.toString(), "expiresAt", time.plusMillis(LEASE_MILLIS).toString());
            lease.putAll(obj("operationType", operation.get("kind"), "subjectId", subject(operation, task), "deployment", worker.get("deployment"), "executorPid", worker.get("pid"), "processStartedAt", worker.get("processStartedAt")));
            ExecutionStore.copyTaskExecutionSource(lease, operation);
            if (worker.containsKey("corePid")) lease.put("corePid", worker.get("corePid"));
            if (worker.containsKey("processSessionId")) lease.put("processSessionId", worker.get("processSessionId"));
            if ("LOCAL_PROCESS".equals(str(worker, "deployment"))) {
                try { lease.put("realWorkingDirectory", Path.of(workspace).toRealPath().toString()); }
                catch (java.io.IOException exception) { throw new ApiException(409, "无法固定 Worker 领取的实际目录"); }
            }
            operation.putAll(details);
            operation.putAll(obj("state", "DISPATCHED", "updatedAt", time.toString(), "workerLeaseId", leaseId, "workerId", worker.get("id"), "executionNodeId", worker.get("executionNodeId"),
                "workerEpoch", worker.get("epoch"), "repositoryBindingId", execution.getOrDefault("repositoryBindingId", ""), "bindingVersion", execution.getOrDefault("bindingVersion", 0), "workingDirectory", lease.get("workingDirectory"), "dispatchCount", 1));
            for (String key : List.of("deployment", "executorPid", "processStartedAt", "corePid", "processSessionId")) if (lease.containsKey(key)) operation.put(key, lease.get(key));
            Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>(); records.put(kind, List.of(operation)); records.put("workerLease", List.of(lease));
            if (!str(operation, "agentRunId").isBlank()) {
                Map<String, Object> run = store.get("agentRun", str(operation, "agentRunId"));
                for (String key : List.of("workerLeaseId", "workerId", "workerEpoch", "executionNodeId", "repositoryBindingId", "bindingVersion", "deployment", "executorPid", "processStartedAt", "corePid", "processSessionId")) if (operation.containsKey(key)) run.put(key, operation.get(key));
                records.put("agentRun", List.of(run));
            }
            store.commitRecords(records, str(task, "id"), "WORKER_DISPATCHED", str(operation, "stage") + " · 操作 " + operationId + " · Worker " + str(worker, "id"));
            return lease;
        }
    }

    private String subject(Map<String, Object> operation, Map<String, Object> task) {
        if ("ADOPT_SUBAGENT".equals(str(operation, "kind"))) return str(operation, "target");
        if (!str(operation, "agentRunId").isBlank()) {
            String child = str(store.get("agentRun", str(operation, "agentRunId")), "subtaskId"); if (!child.isBlank()) return child;
        }
        return str(task, "id");
    }

    private void requireWorkspace(Map<String, Object> operation, Map<String, Object> task, String workspace) {
        String expected;
        if ("WORKSPACE".equals(str(operation, "kind"))) expected = nodes.sourcePath(task);
        else if (!str(operation, "agentRunId").isBlank()) {
            Map<String, Object> run = store.get("agentRun", str(operation, "agentRunId"));
            expected = "SUBAGENT_SNAPSHOT".equals(str(operation, "kind")) ? str(store.get("agentRun", str(run, "parentRunId")), "workspace") : str(run, "workspace");
        }
        else expected = str(task, "workspace");
        if (expected.isBlank() || !ExecutionNodes.samePath(Path.of(expected).toAbsolutePath().normalize().toString(), Path.of(workspace).toAbsolutePath().normalize().toString())) throw new ApiException(403, "操作工作目录与实际任务运行归属不一致");
    }

    void heartbeat(Map<String, Object> identity, Instant time) {
        heartbeat(identity, time, obj());
    }

    void heartbeat(Map<String, Object> identity, Instant time, Map<String, Object> pulse) {
        synchronized (store) {
            requireOwner(identity, time);
            Map<String, Object> worker = store.get("worker", str(identity, "id")), node = store.get("executionNode", str(identity, "executionNodeId"));
            if ("LOCAL_PROCESS".equals(str(identity, "deployment"))) {
                if (!str(identity, "id").equals(str(pulse, "workerId")) || num(identity, "epoch", -1) != num(pulse, "epoch", -2)
                        || !(pulse.get("pid") instanceof Number pid) || pid.longValue() != ((Number) identity.get("pid")).longValue()
                        || !str(identity, "processStartedAt").equals(str(pulse, "processStartedAt")) || !(pulse.get("sequence") instanceof Number sequence)
                        || sequence.longValue() != ((Number) worker.get("lastHeartbeatSequence")).longValue() + 1) throw new ApiException(403, "续约必须来自原子进程的下一条实际心跳");
                worker.putAll(obj("lastHeartbeatSequence", sequence.longValue(), "heartbeatSource", "CHILD_PIPE", "activeCalls", pulse.get("activeCalls")));
            }
            worker.putAll(obj("heartbeatAt", time.toString(), "expiresAt", time.plusMillis(LEASE_MILLIS).toString())); node.put("lastSeenAt", time.toString());
            List<Map<String, Object>> leases = store.workerLeases(str(identity, "id"), "", "ACTIVE");
            for (Map<String, Object> lease : leases) {
                if (!time.isBefore(Instant.parse(str(lease, "expiresAt")))) { lose(lease, "执行租约已过期，不能续约或重放", time); continue; }
                lease.putAll(obj("heartbeatAt", time.toString(), "expiresAt", time.plusMillis(LEASE_MILLIS).toString()));
            }
            store.commitRecords(Map.of("worker", List.of(worker), "executionNode", List.of(node), "workerLease", leases.stream().filter(lease -> "ACTIVE".equals(str(lease, "state"))).toList()), null, null, null);
        }
    }

    Map<String, Object> receipt(Map<String, Object> identity, String kind, String operationId, String token, String state, Instant time) {
        Map<String, Object> operation = store.get(kind, operationId), lease = store.get("workerLease", str(operation, "workerLeaseId"));
        if (!str(identity, "id").equals(str(lease, "workerId")) || num(identity, "epoch", -1) != num(lease, "epoch", -2) || !digest(token).equals(str(lease, "claimDigest"))) throw new ApiException(403, "回执不属于该操作的原 Worker 与租约");
        if (!"ACTIVE".equals(str(lease, "state")) || !time.isBefore(Instant.parse(str(lease, "expiresAt")))) throw new ApiException(409, "租约已失效，旧回执不能确认成功");
        requireOwner(identity, time);
        if (!Set.of("ACKNOWLEDGED", "REJECTED", "CANCELLED", "UNKNOWN").contains(state)) throw new ApiException(400, "无效 Worker 回执状态");
        lease.putAll(obj("state", "UNKNOWN".equals(state) ? "UNKNOWN" : "RELEASED", "stopState", "UNKNOWN".equals(state) ? "UNKNOWN" : "CONFIRMED", "receiptState", state, "endedAt", time.toString()));
        return lease;
    }

    void lose(Map<String, Object> lease, String reason, Instant time) {
        synchronized (store) {
            lease = store.get("workerLease", str(lease, "id"));
            if (!"ACTIVE".equals(str(lease, "state"))) return;
            Map<String, Object> operation = store.get(str(lease, "operationKind"), str(lease, "operationId")), task = store.get("task", str(lease, "taskId"));
            lease.putAll(obj("state", "UNKNOWN", "stopState", "UNKNOWN", "endedAt", time.toString(), "reason", reason));
            if ("DISPATCHED".equals(str(operation, "state"))) operation.putAll(obj("state", "UNKNOWN", "updatedAt", time.toString(), "reason", reason));
            task.putAll(obj("state", "UNKNOWN", "error", unknownReason(task, reason), "updatedAt", time.toString()));
            store.commitRecords(Map.of("workerLease", List.of(lease), str(lease, "operationKind"), List.of(operation), "task", List.of(task)), str(task, "id"), "WORKER_LEASE_UNKNOWN", reason);
        }
    }

    void stop(Map<String, Object> identity, Instant time, String reason) {
        stop(identity, time, reason, obj());
    }

    void stop(Map<String, Object> identity, Instant time, String reason, Map<String, Object> processFacts) {
        synchronized (store) {
            Map<String, Object> worker = store.get("worker", str(identity, "id"));
            if (!"RUNNING".equals(str(worker, "state"))) return;
            List<Map<String, Object>> leases = store.workerLeases(str(identity, "id"), "", "ACTIVE");
            boolean stopped = !"LOCAL_PROCESS".equals(str(identity, "deployment")) || Boolean.TRUE.equals(processFacts.get("treeStopped"));
            worker.putAll(obj("state", leases.isEmpty() && stopped ? "STOPPED" : "LOST", "stopState", leases.isEmpty() && stopped ? "CONFIRMED" : "UNKNOWN", "endedAt", time.toString(), "reason", reason));
            if (!processFacts.isEmpty()) worker.put("processStopFacts", processFacts);
            Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>(); records.put("worker", List.of(worker)); appendUnknown(records, leases, reason, time); store.commitRecords(records, null, null, null);
        }
    }

    void observeStopped(Map<String, Object> identity, Map<String, Object> facts, Instant time) {
        synchronized (store) {
            Map<String, Object> worker = store.get("worker", str(identity, "id"));
            if (!samePid(identity, worker) || !str(identity, "processStartedAt").equals(str(worker, "processStartedAt"))) throw new ApiException(403, "停止事实不属于原 Worker");
            worker.putAll(obj("processStopFacts", facts, "processStopObservedAt", time.toString())); store.put("worker", worker);
        }
    }

    private void appendUnknown(Map<String, List<Map<String, Object>>> records, List<Map<String, Object>> leases, String reason, Instant time) {
        Map<String, Map<String, Object>> tasks = new LinkedHashMap<>();
        for (Map<String, Object> lease : leases) {
            Map<String, Object> operation = store.get(str(lease, "operationKind"), str(lease, "operationId")), task = store.get("task", str(lease, "taskId"));
            lease.putAll(obj("state", "UNKNOWN", "stopState", "UNKNOWN", "endedAt", time.toString(), "reason", reason));
            if ("DISPATCHED".equals(str(operation, "state"))) operation.putAll(obj("state", "UNKNOWN", "updatedAt", time.toString(), "reason", reason));
            task.putAll(obj("state", "UNKNOWN", "error", unknownReason(task, reason), "updatedAt", time.toString())); tasks.put(str(task, "id"), task);
            records.computeIfAbsent(str(lease, "operationKind"), ignored -> new ArrayList<>()).add(operation);
        }
        records.put("workerLease", leases); if (!tasks.isEmpty()) records.put("task", new ArrayList<>(tasks.values()));
    }

    void reconcile(String taskId, String note, Instant time) {
        synchronized (store) {
            List<Map<String, Object>> leases = store.workerLeases("", taskId, "UNKNOWN");
            for (Map<String, Object> lease : leases) lease.putAll(obj("state", "RECONCILED", "stopState", "USER_CONFIRMED", "reconciliation", obj("actor", "USER", "note", note, "createdAt", time.toString())));
            store.commitRecords(Map.of("workerLease", leases), null, null, null);
        }
    }

    static Map<String, Object> publicLease(Map<String, Object> lease) { Map<String, Object> visible = new LinkedHashMap<>(lease); visible.remove("claimDigest"); return visible; }
    private static boolean samePid(Map<String, Object> left, Map<String, Object> right) { return left.get("pid") instanceof Number a && right.get("pid") instanceof Number b && a.longValue() == b.longValue(); }
    private static String unknownReason(Map<String, Object> task, String reason) {
        String previous = str(task, "error");
        return previous.isBlank() ? reason : previous.contains(reason) ? previous : previous + "\n" + reason;
    }
    private static String digest(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法校验 Worker 归属", exception); }
    }
}
