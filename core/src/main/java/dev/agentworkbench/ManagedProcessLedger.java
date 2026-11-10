package dev.agentworkbench;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import static dev.agentworkbench.Json.*;

/**
 * 原启动意图、PID 与观察来源持久保存；旧进程停止和历史覆盖核对分别约束新派发。
 */
public final class ManagedProcessLedger {
    private static final Set<String> SOURCE_FIELDS = Set.of("workerId", "workerEpoch", "executionNodeId", "taskId", "projectId", "operationId", "serverId", "serverIdentity", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId");
    private static final NativeProcessAccess NATIVE_PROCESSES = new NativeProcessAccess();
    private final ExecutionStore store;
    private final long corePid;
    private final String coreStartedAt;
    private final String coreSessionId;
    private final ProcessAccess processes;

    public ManagedProcessLedger(ExecutionStore store) {
        this(store, ProcessHandle.current().pid(), ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""), NATIVE_PROCESSES);
    }

    ManagedProcessLedger(ExecutionStore store, long corePid, String coreStartedAt, ProcessAccess processes) {
        this.store = store; this.corePid = corePid; this.coreStartedAt = coreStartedAt; this.coreSessionId = store.coreSessionId(); this.processes = processes;
    }

    /**
     * 新核心可以建立空闲 Worker 会话；所有任务与 MCP 派发仍须通过旧进程门禁。
     */
    public Tracking begin(String type, Map<String, Object> source) {
        if (!Set.of("WORKER", "MCP_STDIO").contains(type)) throw new IllegalArgumentException("无效本机进程会话类型");
        if (coreStartedAt.isBlank()) throw new ApiException(503, "核心出生时间无法核验，不能启动新的本机执行进程");
        if (!"WORKER".equals(type)) requireDispatch();
        Map<String, Object> session = obj("id", id(), "type", type, "ownerCorePid", corePid, "ownerCoreStartedAt", coreStartedAt,
            "ownerCoreSessionId", coreSessionId, "state", "LAUNCHING", "launchPhase", "PREPARED", "launchReceipt", "MISSING", "coverage", "OBSERVED_ONLY", "createdAt", now());
        copySource(session, source);
        store.put("processSession", session);
        return new Tracking(str(session, "id"));
    }

    public final class Tracking {
        private final String sessionId;
        private final Set<String> observed = ConcurrentHashMap.newKeySet();
        private Tracking(String sessionId) { this.sessionId = sessionId; }
        public String id() { return sessionId; }

        public void starting() {
            synchronized (store) {
                Map<String, Object> session = store.get("processSession", sessionId);
                session.putAll(obj("launchPhase", "START_REQUESTED", "startRequestedAt", now())); store.put("processSession", session);
            }
        }

        public void launched(ProcessHandle handle) {
            if (handle.pid() == corePid) throw new IllegalArgumentException("本机子进程不能使用核心 PID");
            String birth = handle.info().startInstant().map(Object::toString).orElse("");
            synchronized (store) {
                Map<String, Object> session = store.get("processSession", sessionId);
                session.putAll(obj("state", "RUNNING", "launchPhase", "PID_RECORDED", "launchReceipt", "RECORDED", "pid", handle.pid(), "processStartedAt", birth, "launchedAt", now()));
                Map<String, Object> observation = observation(sessionId, obj("pid", handle.pid(), "processStartedAt", birth, "observedFromPid", corePid,
                    "observedFromStartedAt", coreStartedAt), "ACTUAL_START", "ROOT");
                store.commitRecords(Map.of("processSession", List.of(session), "processObservation", List.of(observation)), null, null, null);
                observed.add(str(observation, "id"));
            }
        }

        public void bind(Map<String, Object> source) {
            synchronized (store) {
                Map<String, Object> session = store.get("processSession", sessionId);
                for (String field : SOURCE_FIELDS) if (source.containsKey(field) && session.containsKey(field) && !session.get(field).equals(source.get(field))) throw new ApiException(409, "进程会话不能更换原执行来源");
                copySource(session, source); store.put("processSession", session);
            }
        }

        public void observed(Map<String, Object> facts, String origin) {
            String key = observationId(sessionId, number(facts, "pid"), str(facts, "processStartedAt"));
            if (observed.contains(key)) return;
            appendObserved(sessionId, facts, origin);
            observed.add(key);
        }

        public void observed(ProcessHandle handle, ProcessHandle owner, String origin) {
            observed(obj("pid", handle.pid(), "processStartedAt", handle.info().startInstant().map(Object::toString).orElse(""),
                "observedFromPid", owner.pid(), "observedFromStartedAt", owner.info().startInstant().map(Object::toString).orElse("")), origin);
        }

        public void finished(boolean normal) {
            synchronized (store) {
                Map<String, Object> session = store.get("processSession", sessionId), view = inspect(session);
                boolean stopped = Boolean.TRUE.equals(view.get("observedProcessesStopped"));
                String state = stopped && "RECONCILED".equals(str(session, "state")) ? "RECONCILED" : normal && stopped && "RECORDED".equals(str(session, "launchReceipt")) ? "CLOSED" : "UNKNOWN";
                session.putAll(obj("state", state,
                    "observedProcessesStopped", stopped, "stopObservedAt", now(), "coverage", "OBSERVED_ONLY"));
                store.put("processSession", session);
            }
        }

        public void failed() {
            synchronized (store) {
                Map<String, Object> session = store.get("processSession", sessionId);
                if ("PREPARED".equals(str(session, "launchPhase")) && number(session, "pid") == 0) session.putAll(obj("state", "CLOSED", "launchReceipt", "NOT_STARTED", "observedProcessesStopped", true));
                else session.put("state", "UNKNOWN");
                session.put("failureObservedAt", now()); store.put("processSession", session);
            }
        }

        public boolean observedStopped() { return Boolean.TRUE.equals(inspect(store.get("processSession", sessionId)).get("observedProcessesStopped")); }
    }

    private void appendObserved(String sessionId, Map<String, Object> facts, String origin) {
        if (!Set.of("WORKER_PIPE", "CORE_SNAPSHOT", "MCP_SNAPSHOT", "RECOVERY_SNAPSHOT").contains(origin)) throw new IllegalArgumentException("无效进程观察来源");
        synchronized (store) {
            Map<String, Object> session = store.get("processSession", sessionId);
            long pid = number(facts, "pid"), owner = number(facts, "observedFromPid");
            List<Map<String, Object>> previous = observations(sessionId);
            boolean sourceKnown = owner == number(session, "pid") && str(session, "processStartedAt").equals(str(facts, "observedFromStartedAt"))
                || previous.stream().anyMatch(item -> owner == number(item, "pid") && str(item, "processStartedAt").equals(str(facts, "observedFromStartedAt")));
            if (pid < 1 || pid == corePid || pid == number(session, "pid") || !sourceKnown) throw new ApiException(403, "后代进程缺少原会话的观察来源");
            Map<String, Object> observation = observation(sessionId, facts, origin, "DESCENDANT");
            if (store.find("processObservation", str(observation, "id")) != null) return;
            if (previous.size() >= 32_768) throw new ApiException(409, "进程观察清单超过边界，不能继续派发");
            if ("CLOSED".equals(str(session, "state")) || "RECONCILED".equals(str(session, "state"))) {
                session.putAll(obj("state", "UNKNOWN", "lateObservationAt", now()));
                store.commitRecords(Map.of("processSession", List.of(session), "processObservation", List.of(observation)), null, null, null);
            } else store.put("processObservation", observation);
        }
    }

    public void recoverWorkers() {
        recoverUnstarted();
        for (Map<String, Object> worker : store.list("worker")) {
            if (!str(worker, "processSessionId").isBlank() || !Set.of("CORE_PROCESS", "LOCAL_PROCESS").contains(str(worker, "deployment")) || "CONFIRMED".equals(str(worker, "stopState"))) continue;
            String sessionId = "legacy-worker-" + str(worker, "id");
            if (store.find("processSession", sessionId) != null) continue;
            Map<String, Object> session = legacySession(sessionId, "LEGACY_WORKER", obj("workerId", worker.get("id"), "workerEpoch", worker.get("epoch"), "executionNodeId", worker.get("executionNodeId")));
            session.putAll(obj("pid", number(worker, "pid"), "processStartedAt", str(worker, "processStartedAt")));
            List<Map<String, Object>> recorded = new ArrayList<>();
            if (number(worker, "pid") > 0) recorded.add(observation(sessionId, obj("pid", worker.get("pid"), "processStartedAt", str(worker, "processStartedAt")), "LEGACY_RECORD", "ROOT"));
            for (Map<String, Object> child : objects(map(worker, "processStopFacts").get("unconfirmedProcesses"))) if (number(child, "pid") > 0) recorded.add(observation(sessionId, child, "LEGACY_RECORD", "DESCENDANT"));
            store.commitRecords(Map.of("processSession", List.of(session), "processObservation", recorded), null, null, null);
        }
    }

    public void recoverMcp() {
        recoverUnstarted();
        for (Map<String, Object> operation : store.list("mcpOperation")) {
            if (!str(operation, "processSessionId").isBlank() || "HTTP".equals(str(operation, "transport")) || "CONFIRMED".equals(str(operation, "stopState"))
                    || !Set.of("PREPARED", "DISPATCHED", "UNKNOWN", "ACKNOWLEDGED").contains(str(operation, "state"))) continue;
            String sessionId = "legacy-mcp-" + str(operation, "id");
            if (store.find("processSession", sessionId) != null) continue;
            Map<String, Object> session = legacySession(sessionId, "LEGACY_MCP", obj("operationId", operation.get("id"), "serverId", operation.get("serverId"), "taskId", str(operation, "taskId"), "projectId", str(operation, "projectId")));
            session.put("legacyTransport", str(operation, "transport").isBlank() ? "UNKNOWN" : operation.get("transport"));
            store.put("processSession", session);
        }
    }

    private Map<String, Object> legacySession(String sessionId, String type, Map<String, Object> source) {
        Map<String, Object> session = obj("id", sessionId, "type", type, "state", "UNKNOWN", "launchReceipt", "LEGACY_INCOMPLETE", "coverage", "LEGACY_NO_LIST", "legacy", true, "createdAt", now());
        copySource(session, source); return session;
    }

    private void recoverUnstarted() {
        for (Map<String, Object> session : store.list("processSession")) if (!current(session) && "LAUNCHING".equals(str(session, "state")) && "PREPARED".equals(str(session, "launchPhase")) && observations(str(session, "id")).isEmpty()) {
            session.putAll(obj("state", "CLOSED", "launchReceipt", "NOT_STARTED", "observedProcessesStopped", true, "recoveredAt", now())); store.put("processSession", session);
        }
    }

    public void requireDispatch() {
        if (!store.healthy()) throw new ApiException(503, "持久进程清单不可用，不能新增派发");
        for (Map<String, Object> session : store.list("processSession")) if (couldRequireRecovery(session) && requiresRecovery(session, inspect(session))) throw new ApiException(409, "旧本机进程或清单覆盖尚未核对，请在资源设置核验后再开始执行");
    }

    public boolean dispatchAllowed() { try { requireDispatch(); return true; } catch (ApiException exception) { return false; } }

    public void requireTaskStopped(String taskId) {
        Set<String> workers = taskWorkers(taskId);
        for (Map<String, Object> session : store.list("processSession")) {
            if (!taskId.equals(str(session, "taskId")) && !workers.contains(str(session, "workerId"))) continue;
            if (!couldRequireRecovery(session)) continue;
            Map<String, Object> view = inspect(session);
            if (requiresRecovery(session, view) || !current(session) && !Boolean.TRUE.equals(view.get("observedProcessesStopped"))) throw new ApiException(409, "原任务的旧进程仍在运行、无法核验或覆盖未确认，人工勾选不能绕过；请先在资源设置核验");
        }
    }

    public Map<String, Object> snapshot() {
        List<Map<String, Object>> pending = new ArrayList<>();
        for (Map<String, Object> session : store.list("processSession")) {
            if (!couldRequireRecovery(session)) continue;
            Map<String, Object> view = inspect(session);
            if (requiresRecovery(session, view)) pending.add(view);
        }
        return obj("dispatchAllowed", store.healthy() && pending.isEmpty(), "pendingCount", pending.size(), "sessions", pending.stream().limit(30).toList(), "hasMore", pending.size() > 30,
            "coverage", "OBSERVED_ONLY", "corePid", corePid, "coreStartedAt", coreStartedAt);
    }

    public Map<String, Object> detail(String sessionId) { return inspect(store.get("processSession", sessionId)); }

    /**
     * 请求停止仅处理已核验的原 PID/出生时间；先保存新观察，再发送停止请求。
     */
    public Map<String, Object> stop(String sessionId) {
        synchronized (store) {
            Map<String, Object> session = store.get("processSession", sessionId);
            if (current(session) && Set.of("RUNNING", "LAUNCHING").contains(str(session, "state"))) throw new ApiException(409, "当前会话请使用任务取消，不能通过恢复入口停止正在使用的执行者");
            if (Set.of("CLOSED", "RECONCILED").contains(str(session, "state")) && Boolean.TRUE.equals(inspect(session).get("observedProcessesStopped"))) return inspect(session);
            session.putIfAbsent("stopRequestedAt", now());
            session.putAll(obj("lastStopRequestedAt", now(), "stopActor", "USER", "state", "STOP_REQUESTED")); store.put("processSession", session);
        }
        captureForRecovery(sessionId);
        List<Map<String, Object>> recorded = observations(sessionId);
        try (var inspection = processes.inspection()) {
            recorded.stream().sorted(java.util.Comparator.comparing(item -> "ROOT".equals(str(item, "role")))).forEach(this::requestStop);
        }
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (System.nanoTime() < deadline) {
            boolean running;
            // 每轮共享已退出 PID 的枚举回退，下一轮重新观察；停止信号仍逐项核验原出生。
            try (var inspection = processes.inspection()) {
                running = observations(sessionId).stream().map(this::inspectProcess).anyMatch(item -> "RUNNING".equals(str(item, "observation")));
            }
            if (!running) break;
            try { Thread.sleep(25); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); break; }
        }
        return detail(sessionId);
    }

    private void requestStop(Map<String, Object> item) {
        if (!"RUNNING".equals(str(inspectProcess(item), "observation"))) return;
        try { processes.stop(number(item, "pid"), str(item, "processStartedAt")); } catch (RuntimeException ignored) { }
    }

    private void captureForRecovery(String sessionId) {
        try (var inspection = processes.inspection()) {
            for (Map<String, Object> item : observations(sessionId)) {
                if (!"RUNNING".equals(str(inspectProcess(item), "observation"))) continue;
                try {
                    for (Map<String, Object> child : processes.descendants(number(item, "pid"), str(item, "processStartedAt"))) appendObserved(sessionId, child, "RECOVERY_SNAPSHOT");
                } catch (ApiException exception) { throw exception; }
                catch (RuntimeException exception) {
                    Map<String, Object> session = store.get("processSession", sessionId); session.put("recoveryEnumerationFailed", true); store.put("processSession", session);
                }
            }
        }
    }

    public Map<String, Object> reconcile(String sessionId, Map<String, Object> body) {
        if (!bool(body, "processesStopped", false) || !bool(body, "coverageChecked", false) || str(body, "note").isBlank() || str(body, "note").length() > 4000) throw new ApiException(400, "需核对原进程和清单未覆盖的进程，并填写核对依据");
        synchronized (store) {
            Map<String, Object> session = store.get("processSession", sessionId), view = inspect(session);
            if (current(session) && Set.of("RUNNING", "LAUNCHING").contains(str(session, "state"))) throw new ApiException(409, "当前在用的会话不能标记为旧进程已核对");
            if (!Boolean.TRUE.equals(view.get("observedProcessesStopped"))) throw new ApiException(409, "原进程仍在运行、出生时间无法核验或观察失败，不能接受停止声明");
            if ("RECONCILED".equals(str(session, "state"))) {
                if (!str(body, "note").equals(str(map(session, "lastReconciliation"), "note"))) throw new ApiException(409, "会话已保存核对记录，不能覆盖原核对依据");
                return view;
            }
            Map<String, Object> receipt = obj("actor", "USER", "note", str(body, "note"), "observedProcessesStopped", true,
                "unrecordedProcessesChecked", true, "historicalCoverage", session.get("coverage"), "createdAt", now());
            List<Map<String, Object>> history = new ArrayList<>(objects(session.get("reconciliations"))); history.add(receipt);
            session.putIfAbsent("reconciliation", receipt);
            session.putAll(obj("state", "RECONCILED", "observedProcessesStopped", true, "lastReconciliation", receipt, "reconciliations", history));
            store.put("processSession", session); return inspect(session);
        }
    }

    private Map<String, Object> inspect(Map<String, Object> session) {
        List<Map<String, Object>> facts;
        try (var inspection = processes.inspection()) { facts = observations(str(session, "id")).stream().map(this::inspectProcess).toList(); }
        long unconfirmed = facts.stream().filter(item -> !Set.of("EXITED", "PID_REUSED").contains(str(item, "observation"))).count();
        Map<String, Object> view = new LinkedHashMap<>(session);
        view.putAll(obj("currentCoreSession", current(session), "observedProcessCount", facts.size(), "unconfirmedProcessCount", unconfirmed,
            "observedProcessesStopped", unconfirmed == 0, "launchReceiptMissing", !"RECORDED".equals(str(session, "launchReceipt")),
            "processes", facts.stream().filter(item -> !Set.of("EXITED", "PID_REUSED").contains(str(item, "observation"))).limit(50).toList(),
            "recordedProcesses", facts.stream().limit(50).toList(), "processesTruncated", facts.size() > 50));
        return view;
    }

    private boolean requiresRecovery(Map<String, Object> session, Map<String, Object> view) {
        if ("RECONCILED".equals(str(session, "state"))) return !Boolean.TRUE.equals(view.get("observedProcessesStopped"));
        if ("CLOSED".equals(str(session, "state"))) return !Boolean.TRUE.equals(view.get("observedProcessesStopped"));
        return !current(session) || Set.of("UNKNOWN", "STOP_REQUESTED").contains(str(session, "state"));
    }

    private boolean couldRequireRecovery(Map<String, Object> session) {
        if (Set.of("CLOSED", "RECONCILED").contains(str(session, "state")) && bool(session, "observedProcessesStopped", false)) return false;
        return !current(session) || !Set.of("RUNNING", "LAUNCHING").contains(str(session, "state"));
    }

    private boolean current(Map<String, Object> session) { return !bool(session, "legacy", false) && coreSessionId.equals(str(session, "ownerCoreSessionId")) && !coreStartedAt.isBlank() && corePid == number(session, "ownerCorePid") && coreStartedAt.equals(str(session, "ownerCoreStartedAt")); }

    private Map<String, Object> inspectProcess(Map<String, Object> item) {
        Map<String, Object> view = new LinkedHashMap<>(item);
        try {
            ProcessSnapshot actual = processes.inspect(number(item, "pid"));
            String observation = !actual.alive() ? "EXITED" : str(item, "processStartedAt").isBlank() || actual.startedAt().isBlank() ? "UNVERIFIABLE"
                : !str(item, "processStartedAt").equals(actual.startedAt()) ? "PID_REUSED" : number(item, "pid") == corePid ? "CURRENT_CORE" : "RUNNING";
            view.putAll(obj("observation", observation, "observedAt", now()));
            if (actual.alive()) view.put("currentProcessStartedAt", actual.startedAt());
        } catch (RuntimeException exception) { view.putAll(obj("observation", "OBSERVATION_FAILED", "observedAt", now())); }
        return view;
    }

    public List<Map<String, Object>> taskSessions(String taskId) {
        Set<String> workers = taskWorkers(taskId);
        return store.list("processSession").stream().filter(session -> taskId.equals(str(session, "taskId")) || workers.contains(str(session, "workerId"))).map(this::inspect).toList();
    }

    /**
     * 交付保留完整原始观察清单，界面最多显示五十条不会截断导出证据。
     */
    public List<Map<String, Object>> taskObservations(String taskId) {
        synchronized (store) {
            Set<String> workers = taskWorkers(taskId);
            return store.list("processSession").stream().filter(session -> taskId.equals(str(session, "taskId")) || workers.contains(str(session, "workerId")))
                .flatMap(session -> observations(str(session, "id")).stream()).toList();
        }
    }

    public List<Map<String, Object>> serverSessions(String serverId) { return store.list("processSession").stream().filter(session -> serverId.equals(str(session, "serverId"))).map(this::inspect).toList(); }

    private Set<String> taskWorkers(String taskId) {
        return java.util.stream.Stream.concat(store.workerLeases("", taskId, "").stream(), store.workspaceWriteLeases(taskId).stream())
            .map(lease -> str(lease, "workerId")).filter(worker -> !worker.isBlank()).collect(java.util.stream.Collectors.toSet());
    }
    private List<Map<String, Object>> observations(String sessionId) { return store.processObservations(sessionId); }

    private static Map<String, Object> observation(String sessionId, Map<String, Object> facts, String origin, String role) {
        return obj("id", observationId(sessionId, number(facts, "pid"), str(facts, "processStartedAt")), "sessionId", sessionId, "pid", number(facts, "pid"),
            "processStartedAt", str(facts, "processStartedAt"), "origin", origin, "role", role, "observedFromPid", number(facts, "observedFromPid"),
            "observedFromStartedAt", str(facts, "observedFromStartedAt"), "recordedAt", now());
    }

    private static String observationId(String sessionId, long pid, String birth) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((sessionId + "|" + pid + "|" + birth).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("无法生成进程观察编号"); }
    }
    private static void copySource(Map<String, Object> target, Map<String, Object> source) { for (String field : SOURCE_FIELDS) if (source.get(field) != null) target.put(field, source.get(field)); }
    private static long number(Map<String, Object> value, String field) { return value.get(field) instanceof Integer || value.get(field) instanceof Long ? ((Number) value.get(field)).longValue() : 0; }

    /**
     * 清理路径使用相同出生核验；读取失败和可见但缺少出生信息均保持未确认。
     */
    public static boolean observedProcessStopped(long pid, String startedAt) {
        try {
            ProcessSnapshot actual = NATIVE_PROCESSES.inspect(pid);
            return !actual.alive() || !startedAt.isBlank() && !actual.startedAt().isBlank() && !startedAt.equals(actual.startedAt());
        } catch (RuntimeException exception) { return false; }
    }

    /**
     * 一次清理核验共用系统 PID 快照，历史条数增加时不重复枚举所有系统进程。
     */
    public static <T> T inspectProcesses(java.util.function.Supplier<T> inspection) {
        try (var scope = NATIVE_PROCESSES.inspection()) { return inspection.get(); }
    }

    public static void stopObservedProcess(long pid, String startedAt) {
        try { NATIVE_PROCESSES.stop(pid, startedAt); } catch (RuntimeException ignored) { }
    }

    /**
     * 后代快照前后均核验原父进程；身份变化或无法核验时不采用新增后代。
     */
    public static List<ProcessHandle> originalDescendants(ProcessHandle parent, String startedAt) {
        if (!originalParent(parent, startedAt)) return List.of();
        List<ProcessHandle> captured = parent.descendants().toList();
        return originalParent(parent, startedAt) ? captured : List.of();
    }

    private static boolean originalParent(ProcessHandle parent, String startedAt) {
        return !startedAt.isBlank() && parent.isAlive() && startedAt.equals(parent.info().startInstant().map(Object::toString).orElse(""));
    }

    record ProcessSnapshot(boolean alive, String startedAt) { }
    interface Inspection extends AutoCloseable { @Override void close(); }
    interface ProcessAccess {
        ProcessSnapshot inspect(long pid);
        void stop(long pid, String startedAt);
        default List<Map<String, Object>> descendants(long pid, String startedAt) { return List.of(); }
        default Inspection inspection() { return () -> {}; }
    }

    private static final class NativeProcessAccess implements ProcessAccess {
        private final ThreadLocal<VisibleProcesses> inspection = new ThreadLocal<>();
        @Override public Inspection inspection() {
            if (inspection.get() != null) return () -> {};
            inspection.set(new VisibleProcesses()); return inspection::remove;
        }
        /**
         * Windows 句柄读取失败也可能返回空；可见 PID 仍存在时保持无法核验。
         */
        @Override public ProcessSnapshot inspect(long pid) {
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            boolean alive = handle != null && handle.isAlive();
            String birth = alive ? handle.info().startInstant().map(Object::toString).orElse("") : "";
            if (!birth.isBlank()) return new ProcessSnapshot(true, birth);
            VisibleProcesses visible = inspection.get();
            Set<Long> listed;
            if (visible == null) listed = visiblePids();
            else { if (visible.pids == null) visible.pids = visiblePids(); listed = visible.pids; }
            alive |= listed.contains(pid);
            if (alive && System.getProperty("os.name", "").startsWith("Windows")) {
                // 受限的新进程可能复用旧 PID；快照只补充出生事实，停止仍核验原句柄。
                VisibleProcesses snapshot = visible == null ? new VisibleProcesses() : visible;
                if (!snapshot.windowsAttempted) {
                    snapshot.windowsAttempted = true;
                    try { snapshot.windowsBirths = WindowsProcessSnapshot.capture(); }
                    catch (RuntimeException | LinkageError ignored) { snapshot.windowsBirths = Map.of(); }
                }
                birth = snapshot.windowsBirths.getOrDefault(pid, "");
            }
            return new ProcessSnapshot(alive, birth);
        }
        private Set<Long> visiblePids() { return ProcessHandle.allProcesses().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet()); }
        private static final class VisibleProcesses { Set<Long> pids; Map<Long, String> windowsBirths; boolean windowsAttempted; }
        @Override public void stop(long pid, String startedAt) {
            ProcessHandle original = matching(pid, startedAt);
            if (original != null && pid != ProcessHandle.current().pid()) original.destroyForcibly();
        }
        @Override public List<Map<String, Object>> descendants(long pid, String startedAt) {
            ProcessHandle original = matching(pid, startedAt);
            if (original == null) return List.of();
            return originalDescendants(original, startedAt).stream().map(child -> obj("pid", child.pid(), "processStartedAt", child.info().startInstant().map(Object::toString).orElse(""),
                "observedFromPid", pid, "observedFromStartedAt", startedAt)).toList();
        }
        private ProcessHandle matching(long pid, String startedAt) {
            if (startedAt.isBlank()) return null;
            ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
            return handle != null && handle.isAlive() && startedAt.equals(handle.info().startInstant().map(Object::toString).orElse("")) ? handle : null;
        }
    }
}
