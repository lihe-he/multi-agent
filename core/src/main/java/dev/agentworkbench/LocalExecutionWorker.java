package dev.agentworkbench;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import static dev.agentworkbench.Json.*;

/**
 * 当前实际 JVM 内的本机执行者；心跳独立于浏览器和模型容量，失效后封闭新增操作。
 */
final class LocalExecutionWorker implements ExecutionWorker {
    private final ExecutionStore store;
    private final ExecutionNodes nodes;
    private final WorkerLedger ledger;
    private final Clock clock;
    private final LongSupplier monotonicTime;
    private volatile Pulse lastPulse;
    private final Map<String, Object> identity;
    private final Map<String, String> claims = new ConcurrentHashMap<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("workbench-worker-heartbeat").unstarted(runnable));

    LocalExecutionWorker(ExecutionStore store, ExecutionNodes nodes) { this(store, nodes, Clock.systemUTC(), true); }
    LocalExecutionWorker(ExecutionStore store, ExecutionNodes nodes, Clock clock, boolean heartbeat) { this(store, nodes, clock, heartbeat, System::nanoTime); }
    LocalExecutionWorker(ExecutionStore store, ExecutionNodes nodes, Clock clock, boolean heartbeat, LongSupplier monotonicTime) {
        this.store = store; this.nodes = nodes; this.clock = clock; this.monotonicTime = monotonicTime; this.ledger = new WorkerLedger(store, nodes); this.identity = ledger.start(clock.instant());
        this.lastPulse = new Pulse(clock.instant(), monotonicTime.getAsLong());
        if (heartbeat) heartbeats.scheduleWithFixedDelay(this::heartbeat, 3, 3, TimeUnit.SECONDS);
    }

    @Override public Map<String, Object> identity() { return new java.util.LinkedHashMap<>(identity); }
    @Override public boolean current(String taskId) {
        if (closed.get() || !store.healthy()) return false;
        try { requireContinuity(clock.instant()); ledger.requireOwner(identity, clock.instant()); nodes.requireCurrent(store.get("task", taskId)); return !store.hasUnknownEffects(taskId); }
        catch (RuntimeException exception) { return false; }
    }

    void heartbeat() {
        if (closed.get() || !store.healthy()) return;
        try {
            synchronized (store) { Instant time = clock.instant(); requireContinuity(time); ledger.heartbeat(identity, time); lastPulse = new Pulse(time, monotonicTime.getAsLong()); }
        }
        catch (RuntimeException exception) {
            closed.set(true); heartbeats.shutdown();
            if (store.healthy()) ledger.stop(identity, clock.instant(), "Worker 心跳或归属失效，已封闭新派发；旧效果需核对");
        }
    }

    @Override public void dispatch(String operationId, String workspace, Map<String, Object> details) { dispatch("operation", operationId, workspace, details); }
    @Override public void dispatchTeam(String operationId, String workspace) { dispatch("teamOperation", operationId, workspace, obj()); }
    private void dispatch(String kind, String operationId, String workspace, Map<String, Object> details) {
        if (closed.get()) throw new ApiException(409, "本机 Worker 已停止");
        String token = id();
        synchronized (store) {
            try { requireContinuity(clock.instant()); ledger.claim(identity, kind, operationId, workspace, token, details, clock.instant()); claims.put(operationId, token); }
            catch (ApiException exception) {
                Map<String, Object> operation = store.get(kind, operationId);
                if ("PREPARED".equals(str(operation, "state")) && str(operation, "workerLeaseId").isBlank()) {
                    if (kind.equals("operation")) store.markOperation(operationId, "REJECTED", obj("reason", exception.getMessage(), "dispatchCount", 0));
                    else store.finishTeamOperation(operationId, "REJECTED", obj("reason", exception.getMessage(), "dispatchCount", 0));
                }
                throw exception;
            }
        }
    }

    @Override public void finish(String operationId, String state, Map<String, Object> details) { finish("operation", operationId, state, details); }
    @Override public void finishTeam(String operationId, String state, Map<String, Object> details) { finish("teamOperation", operationId, state, details); }
    private void finish(String kind, String operationId, String state, Map<String, Object> details) {
        synchronized (store) {
            Map<String, Object> operation = store.get(kind, operationId);
            if (!claims.containsKey(operationId)) {
                if (!str(operation, "workerLeaseId").isBlank()) return;
                if (kind.equals("operation")) store.markOperation(operationId, state, details); else store.finishTeamOperation(operationId, state, details);
                return;
            }
            Map<String, Object> lease;
            try {
                requireContinuity(clock.instant());
                if ("ACKNOWLEDGED".equals(state)) nodes.requireCurrent(store.get("task", str(operation, "taskId")));
                lease = ledger.receipt(identity, kind, operationId, claims.get(operationId), state, clock.instant());
            }
            catch (ApiException exception) {
                ledger.lose(store.get("workerLease", str(operation, "workerLeaseId")), "Worker 回执失去原归属，结果保留未知", clock.instant()); claims.remove(operationId); throw exception;
            }
            if (kind.equals("operation")) store.markWorkerOperation(operationId, state, details, lease); else store.finishWorkerTeamOperation(operationId, state, details, lease);
            claims.remove(operationId);
        }
    }

    @Override public void evidence(String taskId, String operationId, Map<String, Object> evidence) {
        synchronized (store) {
            Map<String, Object> operation = store.get("operation", operationId);
            String token = claims.get(operationId);
            if (token == null) throw new ApiException(409, "证据缺少本次 Worker 执行归属");
            Map<String, Object> lease;
            try {
                requireContinuity(clock.instant());
                nodes.requireCurrent(store.get("task", taskId));
                lease = ledger.receipt(identity, "operation", operationId, token, "UNKNOWN".equals(str(evidence, "status")) ? "UNKNOWN" : "ACKNOWLEDGED", clock.instant());
            } catch (ApiException exception) {
                lease = store.get("workerLease", str(operation, "workerLeaseId")); ledger.lose(lease, "收到结果时 Worker 归属或授权已失效，保留原始观测供核对", clock.instant());
                lease = store.get("workerLease", str(lease, "id")); evidence.put("observedStatus", evidence.get("status")); evidence.put("status", "UNKNOWN"); evidence.put("integrity", "WORKER_OWNERSHIP_LOST");
            }
            evidence.putAll(obj("operationId", operationId, "workerLeaseId", lease.get("id"), "workerId", identity.get("id"), "executionNodeId", lease.get("executionNodeId"), "workerEpoch", lease.get("epoch"),
                "repositoryBindingId", lease.get("repositoryBindingId"), "bindingVersion", lease.get("bindingVersion")));
            store.recordWorkerEvidence(taskId, operationId, evidence, lease); claims.remove(operationId);
        }
    }

    @Override public void settleRun(String runId) { settle("", runId); }
    void settleTask(String taskId) { settle(taskId, ""); }

    private void settle(String taskId, String runId) {
        if (!store.healthy()) return;
        synchronized (store) {
            String rootId = taskId.isBlank() ? str(store.get("agentRun", runId), "taskId") : taskId;
            for (Map<String, Object> lease : store.workerLeases(str(identity, "id"), rootId, "")) {
                if (!claims.containsKey(str(lease, "operationId"))) continue;
                if (!runId.isBlank() && !runId.equals(str(lease, "agentRunId"))) continue;
                if (!taskId.isBlank() && (!taskId.equals(str(lease, "taskId")) || (!str(lease, "agentRunId").isBlank() && !str(store.get("agentRun", str(lease, "agentRunId")), "subtaskId").isBlank()))) continue;
                if ("ACTIVE".equals(str(lease, "state"))) ledger.lose(lease, "执行已返回但操作回执不完整，保留未知并禁止重放", clock.instant()); claims.remove(str(lease, "operationId"));
            }
        }
    }

    void requireStopped(String taskId) {
        boolean pending = store.workerLeases(str(identity, "id"), taskId, "").stream().anyMatch(lease -> claims.containsKey(str(lease, "operationId")));
        if (pending || !store.workerLeases("", taskId, "ACTIVE").isEmpty()) throw new ApiException(409, "本机 Worker 仍有实际在途执行，请等待停止回执");
    }
    void reconcile(String taskId, String note) { requireStopped(taskId); ledger.reconcile(taskId, note, clock.instant()); }

    /**
     * 单调时间限制无心跳窗口；系统时间回退不能延长旧归属或确认迟到结果。
     */
    private void requireContinuity(Instant wallTime) {
        Pulse pulse = lastPulse;
        if (monotonicTime.getAsLong() - pulse.monotonicNanos() >= TimeUnit.MILLISECONDS.toNanos(WorkerLedger.LEASE_MILLIS)
                || wallTime.isBefore(pulse.wallTime().minusMillis(1000))) throw new ApiException(409, "Worker 心跳中断或系统时间回退，旧归属必须核对");
    }

    private record Pulse(Instant wallTime, long monotonicNanos) { }

    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        heartbeats.shutdownNow();
        if (store.healthy()) ledger.stop(identity, Instant.now(clock), "本机 Worker 停止；未取得回执的执行保留未知");
    }
}
