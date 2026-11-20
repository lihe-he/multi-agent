package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.mcp.StageInputMcpRegistry;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static dev.agentworkbench.Json.*;

/**
 * 核心独占数据库与回调身份；实际现场、模型、文件工具和验证通过原领取交给子进程。
 */
@Component
@Primary
public class ProcessWorkspaceRuntime extends WorkspaceRuntime {
    private final ObjectMapper mapper;
    private final String dataDirectory;
    private final StageInputMcpRegistry stageInputs;
    private final String coreSessionId = id();
    private final ThreadLocal<Claim> current = new ThreadLocal<>();
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Map<String, Invocation> invocations = new ConcurrentHashMap<>();
    private volatile WorkerProcess process;
    private volatile LocalExecutionWorker worker;
    private ExecutionStore store;
    private ManagedProcessLedger processLedger;
    private boolean started;

    public ProcessWorkspaceRuntime(ObjectMapper mapper, @Value("${workbench.data-dir:./data}") String dataDirectory) {
        this(mapper, dataDirectory, new StageInputMcpRegistry());
    }

    @Autowired
    public ProcessWorkspaceRuntime(ObjectMapper mapper, @Value("${workbench.data-dir:./data}") String dataDirectory, StageInputMcpRegistry stageInputs) {
        super(mapper, dataDirectory); this.mapper = mapper; this.dataDirectory = dataDirectory; this.stageInputs = stageInputs;
    }

    synchronized LocalExecutionWorker startWorker(ExecutionStore store, ExecutionNodes nodes) {
        if (started) throw new ApiException(409, "原 Worker 会话只能启动一次，失效后不自动创建替代执行者");
        started = true; this.store = store;
        processLedger = new ManagedProcessLedger(store); processLedger.recoverWorkers();
        process = new WorkerProcess(mapper, dataDirectory, this::callback, processLedger.begin("WORKER", obj()));
        try { worker = new LocalExecutionWorker(store, nodes, this); return worker; }
        catch (RuntimeException exception) { process.close(); throw exception; }
    }

    Map<String, Object> processIdentity() { return process.processIdentity(); }
    Map<String, Object> stopFacts() { return process.stopFacts(); }
    boolean available() { return process != null && process.available(); }
    ManagedProcessLedger managedProcesses() { return processLedger; }
    void requireDispatch() { processLedger.requireDispatch(); }
    void requireAvailable() { if (process == null) throw new ApiException(409, "独立 Worker 尚未启动"); process.requireAvailable(); }
    void bind(Map<String, Object> identity, Consumer<Map<String, Object>> pulses, Consumer<String> lost) { process.bind(identity, pulses, lost); }
    void fence(String reason) { invocations.values().forEach(Invocation::revokeStageInput); if (process != null) process.fence(reason); }
    void closeProcess() { invocations.values().forEach(Invocation::revokeStageInput); if (process != null) process.close(); }

    void handoff(Map<String, Object> lease, String token) {
        requireAvailable();
        if (current.get() != null) throw new ApiException(409, "原线程仍持有未结束的 Worker 领取");
        Claim claim = new Claim(new LinkedHashMap<>(lease), token);
        if (claims.putIfAbsent(str(lease, "operationId"), claim) != null) throw new ApiException(409, "同一 Worker 原操作不能再次交接");
        current.set(claim);
    }

    void requireReturned(String operationId) {
        Claim claim = claims.get(operationId);
        if (claim != null && claim.active.get()) throw new ApiException(409, "原 Worker 调用尚未实际返回，不能确认回执或释放领取");
    }

    void releaseClaim(String operationId) {
        Claim claim = claims.get(operationId);
        if (claim == null) return;
        requireReturned(operationId);
        if (!claims.remove(operationId, claim)) return;
        if (current.get() == claim) current.remove();
        if (claim.sequence > 0 && available()) process.release(claim.lease, claim.token).whenComplete((ignored, error) -> { if (error != null) process.fence("WORKER_RELEASE_RECEIPT_MISSING"); });
    }

    void requireTaskStopped(String taskId) {
        if (invocations.values().stream().anyMatch(call -> taskId.equals(str(call.claim.lease, "taskId")))) throw new ApiException(409, "独立 Worker 仍有原任务的在途调用或收尾");
        if (process != null && !available() && !process.treeStopped()) throw new ApiException(409, "原 Worker 进程树停止尚未确认，资源与未知结果继续保留");
        processLedger.requireTaskStopped(taskId);
    }

    @Override public Map<String, Object> prepare(String source, String taskId, boolean includeChanges) {
        return objectValue(invoke("PREPARE", obj("sourcePath", source, "taskId", taskId, "includeChanges", includeChanges), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> prepareSelected(String source, String taskId, boolean includeChanges, Map<String, Object> selection) {
        return objectValue(invoke("PREPARE", obj("sourcePath", source, "taskId", taskId, "includeChanges", includeChanges, "workspaceSelection", selection), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> acquireWorkspaceWrite(Map<String, Object> identity) {
        Claim claim = current.get();
        if (claim == null || !WorkspaceWriteLedger.identity(map(claim.lease, "workspaceWriteIdentity")).equals(WorkspaceWriteLedger.identity(identity))) throw new ApiException(403, "现场写入申请缺少原 Worker 领取");
        return objectValue(invoke("ACQUIRE_WRITE", obj("workspace", claim.lease.get("workingDirectory")), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> releaseWorkspaceWrite(Map<String, Object> identity, String note) {
        requireAvailable();
        if (invocations.values().stream().anyMatch(call -> str(identity, "taskId").equals(str(call.claim.lease, "taskId")))) throw new ApiException(409, "原根的实际 Worker 调用尚未收尾");
        try {
            Map<String, Object> receipt = objectValue(process.releaseWorkspaceWrite(identity, note).get(10, TimeUnit.SECONDS));
            if (integer(receipt, "receiptExecutorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(receipt, "receiptProcessStartedAt"))
                    || !WorkspaceWriteLedger.identity(identity).equals(WorkspaceWriteLedger.identity(map(receipt, "identity"))) || !bool(receipt, "protectionReleased", false)) throw new IllegalStateException("现场写入保护的释放回执失去原来源");
            return receipt;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new IllegalStateException("等待写入保护释放被中断，原归属需核对");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof WorkerRpc.RemoteFailure remote && remote.status() < 500) throw new ApiException(remote.status() > 0 ? remote.status() : 409, remote.getMessage());
            throw new IllegalStateException("原现场写入保护未取得完整释放回执，保持未知");
        } catch (TimeoutException exception) { throw new IllegalStateException("原现场写入保护释放回执超时，保持未知"); }
    }

    @Override public String reviewCopy(String workspace, String subjectId, String version) {
        return stringValue(invoke("REVIEW_COPY", obj("workspace", workspace, "subjectId", subjectId, "version", version), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> delegationCopy(String workspace, String subjectId, String version) {
        return objectValue(invoke("DELEGATION_COPY", obj("workspace", workspace, "subjectId", subjectId, "version", version), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> integrateDelegation(String baseline, String working, String target, String expected, Map<String, Object> permissions) {
        return objectValue(invoke("INTEGRATE", obj("baseline", baseline, "working", working, "targetWorkspace", target, "expectedResult", expected, "permissions", permissions), () -> false, ignored -> {}, null, null));
    }

    /**
     * 文件撤销仍由原 Worker 领取执行，核心不直接改写任务文件。
     */
    @Override public Map<String, Object> restoreCheckpoint(String workspace, String taskId, String operationId, Map<String, Object> preview, BooleanSupplier cancelled) {
        return objectValue(invoke("RESTORE_CHECKPOINT", obj("workspace", workspace, "taskId", taskId, "operationId", operationId, "preview", preview), cancelled, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> verify(String workspace, String command, BooleanSupplier cancelled, Consumer<String> log) {
        return objectValue(invoke("VERIFY", obj("workspace", workspace, "command", command), cancelled, log, null, null));
    }

    @Override public Map<String, Object> run(Map<String, Object> route, String apiKey, String workspace, String prompt, boolean readOnly, List<String> scope,
                                            BooleanSupplier cancelled, Consumer<String> log, BooleanSupplier acquireCall) {
        return runWithMcp(route, apiKey, workspace, prompt, readOnly, scope, cancelled, log, acquireCall, List.of(), alias -> obj("state", "REJECTED", "error", "未授权 MCP 工具"));
    }

    @Override public Map<String, Object> runWithMcp(Map<String, Object> route, String apiKey, String workspace, String prompt, boolean readOnly, List<String> scope,
                                                   BooleanSupplier cancelled, Consumer<String> log, BooleanSupplier acquireCall, List<Map<String, Object>> mcpBindings, FixedToolInvoker mcpInvoker) {
        ModelCallObserver observer = acquireCall instanceof ModelCallObserver observed ? observed : new ModelCallObserver() {
            @Override public boolean begin(Map<String, Object> metadata) { return acquireCall.getAsBoolean(); }
            @Override public void end(Map<String, Object> observation) { }
        };
        AgentToolSession tools = observer.tools();
        return objectValue(invoke("RUN", obj("route", route, "apiKey", apiKey, "workspace", workspace, "prompt", prompt, "readOnly", readOnly, "scope", scope,
            "hasTools", tools != null, "hasCheckpoints", !observer.checkpointContext().isEmpty(), "definitions", tools == null ? List.of() : tools.definitions(), "permissions", tools == null ? obj() : tools.permissions(), "mcpBindings", mcpBindings),
            cancelled, log, observer, mcpInvoker));
    }

    @Override public void stop(String taskId) {
        if (taskId.isBlank()) return;
        invocations.values().stream().filter(call -> taskId.equals(str(call.claim.lease, "taskId"))).forEach(Invocation::revokeStageInput);
        if (process != null) process.cancel(taskId);
    }

    Map<String, Object> executeTerminal(Map<String, Object> terminal, TerminalLedger ledger, BooleanSupplier cancelled) {
        return objectValue(invoke("TERMINAL", obj("workspace", terminal.get("workspace"), "terminalId", terminal.get("id"),
            "recipe", terminal.get("recipe"), "columns", terminal.get("columns"), "rows", terminal.get("rows")), cancelled, ignored -> {}, null, null, ledger));
    }

    /**
     * 控制请求复用原在途调用，等待回调期间不持有 SQLite monitor。
     */
    Map<String, Object> controlTerminal(String terminalId, Map<String, Object> action, byte[] input) {
        Invocation call = invocations.values().stream().filter(value -> "TERMINAL".equals(value.method) && terminalId.equals(str(value.claim.lease, "terminalId"))).findFirst()
            .orElseThrow(() -> new ApiException(409, "原终端尚未启动或调用已收尾"));
        requireCurrent(call.claim, call.cancelled);
        try {
            Map<String, Object> result = objectValue(process.terminalControl(call.id, call.claim.lease, call.claim.token, call.sequence, action, input).get(10, TimeUnit.SECONDS));
            if (!"ACKNOWLEDGED".equals(str(result, "state")) || !call.id.equals(str(result, "callId"))
                    || !str(call.claim.lease, "id").equals(str(result, "workerLeaseId")) || !str(call.claim.lease, "operationId").equals(str(result, "operationId"))
                    || !terminalId.equals(str(result, "terminalId")) || !str(action, "requestId").equals(str(result, "requestId"))
                    || !str(action, "sha256").equals(str(result, "sha256")) || integer(action, "sequence") != integer(result, "sequence")
                    || integer(result, "executorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(result, "processStartedAt"))) throw new IllegalStateException("原终端控制回执失去调用或输入来源");
            if ("RESIZE".equals(str(action, "kind")) && (integer(action, "columns") != integer(map(result, "effect"), "columns") || integer(action, "rows") != integer(map(result, "effect"), "rows"))) throw new IllegalStateException("原终端实际尺寸与控制回执不一致");
            return result;
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException("原终端控制等待被中断，输入回执未知", exception); }
        catch (ExecutionException exception) {
            if (exception.getCause() instanceof WorkerRpc.RemoteFailure remote && "API".equals(remote.category()) && remote.status() < 500) throw new ApiException(remote.status(), remote.getMessage());
            throw new IllegalStateException("原终端控制未取得完整回执，禁止再次发送输入", exception);
        } catch (TimeoutException exception) { throw new IllegalStateException("原终端控制回执超时，禁止再次发送输入", exception); }
    }

    private Object invoke(String method, Map<String, Object> parameters, BooleanSupplier cancelled, Consumer<String> log, ModelCallObserver observer, FixedToolInvoker mcp) {
        return invoke(method, parameters, cancelled, log, observer, mcp, null);
    }

    private Object invoke(String method, Map<String, Object> parameters, BooleanSupplier cancelled, Consumer<String> log, ModelCallObserver observer, FixedToolInvoker mcp, TerminalLedger terminalLedger) {
        Claim claim = current.get();
        if (claim == null) throw new ApiException(403, "现场执行必须持有核心的原 Worker 领取");
        requireCurrent(claim, cancelled);
        long sequence;
        synchronized (claim) {
            if (!claim.active.compareAndSet(false, true)) throw new ApiException(409, "同一原领取仍有实际在途调用");
            sequence = ++claim.sequence;
        }
        Invocation call = new Invocation(id(), claim, sequence, method, cancelled, log, observer, mcp, terminalLedger); invocations.put(call.id, call);
        boolean interrupted = false;
        try {
            call.openStageInput(parameters);
            var result = process.invoke(call.id, claim.lease, claim.token, sequence, method, parameters);
            long deadline = "TERMINAL".equals(method) ? Long.MAX_VALUE : System.nanoTime() + Duration.ofMinutes(16).toNanos();
            Map<String, Object> response;
            while (true) {
                if ((cancelled.getAsBoolean() || !worker.current(str(claim.lease, "taskId"))) && call.cancelSent.compareAndSet(false, true)) {
                    call.revokeStageInput(); process.cancel(str(claim.lease, "taskId"));
                }
                if (System.nanoTime() >= deadline) process.fence("WORKER_CALL_TIMED_OUT");
                try { response = objectValue(result.get(150, TimeUnit.MILLISECONDS)); break; }
                catch (TimeoutException ignored) { }
            }
            if (!call.id.equals(str(response, "callId")) || !str(claim.lease, "workerId").equals(str(response, "workerId")) || integer(claim.lease, "epoch") != integer(response, "workerEpoch")
                    || integer(response, "executorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(response, "processStartedAt"))
                    || !str(claim.lease, "id").equals(str(response, "workerLeaseId")) || !response.containsKey("value")) {
                process.fence("WORKER_INVOCATION_RECEIPT_INVALID"); throw new IllegalStateException("独立 Worker 结果失去原领取来源，必须核对");
            }
            if (!str(claim.lease, "workspaceWriteLeaseId").equals(str(response, "workspaceWriteLeaseId"))) { process.fence("WORKSPACE_WRITE_SOURCE_MISSING"); throw new IllegalStateException("实际 Worker 回执缺少原根现场写入归属"); }
            Object value = response.get("value");
            if (value instanceof Map<?, ?> outcome && "UNKNOWN".equals(outcome.get("status")) && process.childrenRunning()) process.fence("WORKER_COMMAND_STOP_UNKNOWN");
            return value;
        } catch (InterruptedException exception) {
            interrupted = true; process.fence("WORKER_WAIT_INTERRUPTED"); throw new CancellationException("原 Worker 调用被停止，失去的结果仍需核对");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof WorkerRpc.RemoteFailure remote) {
                if ("VALIDATION".equals(remote.category())) throw new IllegalArgumentException(remote.getMessage());
                if ("CANCELLED".equals(remote.category())) throw new CancellationException(remote.getMessage());
                if ("API".equals(remote.category()) && remote.status() < 500) {
                    if ("RESTORE_CHECKPOINT".equals(method)) throw new dev.agentworkbench.runtime.CheckpointRestoreRejected(remote.status(), remote.getMessage());
                    throw new ApiException(remote.status(), remote.getMessage());
                }
                process.fence("WORKER_REMOTE_ERROR_" + remote.category() + "_" + remote.status());
                throw new IllegalStateException("独立 Worker 返回错误回执（" + remote.category() + "/" + remote.status() + "）；原调用结果未知且不自动重试");
            }
            process.fence("WORKER_RESULT_MISSING"); throw new IllegalStateException("独立 Worker 未取得原调用的完整回执，结果未知且不自动重试");
        } finally {
            if (!process.available()) {
                process.awaitStopped();
                try { worker.processStopped(process.stopFacts()); } catch (RuntimeException ignored) { }
            }
            call.callbackLock.lock();
            try {
                call.closed = true;
                call.revokeStageInput();
                try {
                    if (call.requestInFlight) {
                        call.observer.end(obj("state", "UNKNOWN", "errorType", "WORKER_RECEIPT_MISSING", "remoteOutcome", "UNKNOWN", "reason", "原 Worker 调用结束但模型观测未取得回执，不自动重放"));
                        call.requestInFlight = false;
                    }
                } finally {
                    for (AutoCloseable resource : call.resources.values()) { try { resource.close(); } catch (Exception ignored) { } }
                    call.resources.clear(); invocations.remove(call.id, call); claim.active.set(false);
                    if (interrupted) Thread.currentThread().interrupt();
                }
            } finally { call.callbackLock.unlock(); }
        }
    }

    private void requireCurrent(Claim claim, BooleanSupplier cancelled) {
        requireDispatch();
        requireAvailable();
        Map<String, Object> lease = store.get("workerLease", str(claim.lease, "id"));
        if (!"ACTIVE".equals(str(lease, "state")) || !str(claim.lease, "workerId").equals(str(lease, "workerId")) || integer(claim.lease, "epoch") != integer(lease, "epoch")
                || !str(claim.lease, "claimDigest").equals(str(lease, "claimDigest"))) throw new ApiException(409, "原 Worker 领取已经失效，不能开始新的动作");
        Map<String, Object> task = store.get("task", str(lease, "taskId"));
        store.requireActiveTaskExecution(task, lease);
        Map<String, Object> writeIdentity = new WorkspaceWriteLedger(store).require(task, worker.identity(), Set.of("WORKSPACE", "WORKSPACE_WRITE").contains(str(lease, "operationType")));
        if (!writeIdentity.equals(WorkspaceWriteLedger.identity(map(claim.lease, "workspaceWriteIdentity")))) throw new ApiException(409, "原 Worker 调用的现场写入来源已变化");
        if (cancelled.getAsBoolean() || !worker.current(str(lease, "taskId"))) throw new CancellationException("根任务、授权或原 Worker 归属已经停止");
    }

    private Object callback(String method, Map<String, Object> message) throws Exception {
        if (!"CALLBACK".equals(method)) throw new IllegalArgumentException("核心只接收原调用的回调");
        Invocation call = invocations.get(str(message, "callId"));
        if (call == null) throw new ApiException(409, "回调不属于原在途 Worker 调用");
        Claim before = current.get();
        call.callbackLock.lock();
        try {
            return call.callback(message);
        } finally { call.callbackLock.unlock(); if (before == null) current.remove(); else current.set(before); }
    }

    private static final class Claim {
        final Map<String, Object> lease;
        final String token;
        final AtomicBoolean active = new AtomicBoolean();
        long sequence;
        Claim(Map<String, Object> lease, String token) { this.lease = lease; this.token = token; }
    }

    private final class Invocation {
        final String id;
        final Claim claim;
        final long sequence;
        final String method;
        final BooleanSupplier cancelled;
        final Consumer<String> log;
        final ModelCallObserver observer;
        final AgentToolSession tools;
        final FixedToolInvoker mcp;
        final TerminalLedger terminalLedger;
        final AtomicBoolean cancelSent = new AtomicBoolean();
        /**
         * 阻塞回调保持原顺序，同时允许 Java 21 虚拟线程释放载体供子调用推进。
         */
        final ReentrantLock callbackLock = new ReentrantLock();
        final Map<String, AutoCloseable> resources = new LinkedHashMap<>();
        long callbacks;
        boolean requestInFlight;
        boolean closed;
        volatile StageInputMcpRegistry.Handle stageInput;
        final AtomicBoolean stageInputRevoked = new AtomicBoolean();
        String stageInputDigest = "";
        Invocation(String id, Claim claim, long sequence, String method, BooleanSupplier cancelled, Consumer<String> log, ModelCallObserver observer, FixedToolInvoker mcp, TerminalLedger terminalLedger) {
            this.id = id; this.claim = claim; this.sequence = sequence; this.method = method; this.cancelled = cancelled; this.log = log; this.observer = observer;
            this.tools = observer == null ? null : observer.tools(); this.mcp = mcp;
            this.terminalLedger = terminalLedger;
        }

        /**
         * 阶段令牌仅随原 Worker 私有调用传递，原生运行器尚未获得自动注入能力。
         */
        void openStageInput(Map<String, Object> parameters) {
            if (!"RUN".equals(method) || observer == null) return;
            callbackLock.lock();
            try {
                synchronized (store) {
                    if (stageInputRevoked.get()) throw new CancellationException("阶段输入授权已撤销");
                    requireCurrent(claim, cancelled);
                    Map<String, Object> snapshot = observer.stageInput(claim.lease);
                    if (snapshot.isEmpty()) return;
                    stageInputDigest = WorkflowDsl.digest(snapshot, mapper);
                    Map<String, Object> identity = new LinkedHashMap<>(claim.lease);
                    identity.putAll(obj("coreSessionId", coreSessionId, "invocationId", id, "callSequence", sequence, "stageInputDigest", stageInputDigest));
                    stageInput = stageInputs.open(identity, this::readStageInput, Duration.ofMinutes(16));
                    if (stageInputRevoked.get()) {
                        stageInputs.revoke(stageInput.id()); throw new CancellationException("阶段输入授权已撤销");
                    }
                    parameters.put("stageInputBridge", obj("endpoint", stageInput.endpoint(), "token", stageInput.token()));
                }
            } finally { callbackLock.unlock(); }
        }

        /**
         * HTTP 线程显式定位原调用，复查领取和固定输入，不借用线程本地身份。
         */
        Map<String, Object> readStageInput() {
            callbackLock.lock();
            try {
                synchronized (store) {
                    if (closed || cancelSent.get() || stageInputRevoked.get() || invocations.get(id) != this || !claim.active.get() || claim.sequence != sequence
                            || claims.get(str(claim.lease, "operationId")) != claim || stageInput == null) throw new ApiException(403, "阶段输入的原调用已经结束");
                    requireCurrent(claim, cancelled);
                    Map<String, Object> lease = store.get("workerLease", str(claim.lease, "id"));
                    for (String key : List.of("id", "operationId", "operationKind", "taskId", "projectId", "agentRunId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "workspaceWriteIdentity",
                            "workerId", "epoch", "executionNodeId", "repositoryBindingId", "bindingVersion", "authorizationVersion", "workingDirectory", "realWorkingDirectory", "operationType", "subjectId", "claimDigest",
                            "executorPid", "processStartedAt", "corePid", "processSessionId", "workspaceSelection", "workspaceOwnership", "terminalId",
                            "workflowRunId", "workflowNodeId", "workflowNodeAttemptId", "workflowAncestorNodeIds")) {
                        if (!WorkflowDsl.digest(obj("value", claim.lease.get(key)), mapper).equals(WorkflowDsl.digest(obj("value", lease.get(key)), mapper))) {
                            throw new ApiException(403, "阶段输入的原领取来源已变化");
                        }
                    }
                    if (!java.time.Instant.now().isBefore(java.time.Instant.parse(str(lease, "expiresAt")))) throw new ApiException(403, "阶段输入的原领取已经过期");
                    if (tools != null) tools.checkCurrent();
                    Map<String, Object> snapshot = observer.stageInput(claim.lease);
                    if (!stageInputDigest.equals(WorkflowDsl.digest(snapshot, mapper))) throw new ApiException(403, "阶段输入或其授权已变化");
                    requireCurrent(claim, cancelled);
                    return snapshot;
                }
            } finally { callbackLock.unlock(); }
        }

        void revokeStageInput() {
            stageInputRevoked.set(true);
            StageInputMcpRegistry.Handle handle = stageInput;
            if (handle != null) stageInputs.revoke(handle.id());
        }

        Object callback(Map<String, Object> message) throws Exception {
            Map<String, Object> lease = claim.lease;
            if (closed || !str(lease, "workerId").equals(str(message, "workerId")) || integer(lease, "epoch") != integer(message, "workerEpoch")
                    || !str(lease, "id").equals(str(message, "workerLeaseId")) || !str(lease, "operationId").equals(str(message, "operationId"))
                    || !str(lease, "operationKind").equals(str(message, "operationKind")) || !str(lease, "taskId").equals(str(message, "taskId"))
                    || !str(lease, "workspaceWriteLeaseId").equals(str(message, "workspaceWriteLeaseId"))
                    || integer(message, "executorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(message, "processStartedAt"))
                    || integer(message, "callSequence") != sequence || integer(message, "callbackSequence") != callbacks + 1) throw new ApiException(403, "回调失去原 Worker、领取或调用顺序");
            callbacks++;
            String action = str(message, "action"); Map<String, Object> arguments = map(message, "arguments");
            if (!List.of("MODEL_END", "RESOURCE_CLOSE", "LOG", "TERMINAL_OUTPUT", "TERMINAL_OUTPUT_END").contains(action)) requireCurrent(claim, cancelled);
            return switch (action) {
                case "CHECK_CURRENT" -> { if (tools != null) tools.checkCurrent(); yield true; }
                case "TERMINAL_CONTEXT", "TERMINAL_LAUNCH", "TERMINAL_STARTED", "TERMINAL_OUTPUT", "TERMINAL_OUTPUT_END", "TERMINAL_AUTHORIZE" -> {
                    if (!"TERMINAL".equals(method) || terminalLedger == null || str(lease, "terminalId").isBlank()) throw new ApiException(403, "终端回调缺少原调用来源");
                    String terminalId = str(lease, "terminalId");
                    yield switch (action) {
                        case "TERMINAL_CONTEXT" -> terminalLedger.outputContext(terminalId);
                        case "TERMINAL_LAUNCH" -> { terminalLedger.nativeLaunch(terminalId); yield true; }
                        case "TERMINAL_STARTED" -> { terminalLedger.started(terminalId, arguments); yield true; }
                        case "TERMINAL_OUTPUT" -> terminalLedger.publishOutput(terminalId, arguments);
                        case "TERMINAL_OUTPUT_END" -> terminalLedger.finishOutput(terminalId, arguments);
                        case "TERMINAL_AUTHORIZE" -> terminalLedger.authorize(terminalId, arguments);
                        default -> throw new ApiException(403, "终端回调未获支持");
                    };
                }
                case "LOG" -> { log.accept(str(arguments, "text")); yield true; }
                case "MODEL_BEGIN" -> {
                    if (!"RUN".equals(method) || observer == null || requestInFlight) throw new ApiException(409, "模型派发不属于原空闲请求边界");
                    requestInFlight = observer.begin(map(arguments, "metadata")); yield requestInFlight;
                }
                case "MODEL_END" -> {
                    if (!requestInFlight || observer == null) throw new ApiException(409, "模型回执缺少原请求派发");
                    observer.end(map(arguments, "observation")); requestInFlight = false; yield observer.lastRequestId();
                }
                case "CHECKPOINT_CONTEXT" -> {
                    if (!"RUN".equals(method) || observer == null || requestInFlight || observer.lastRequestId().isBlank()) throw new ApiException(403, "文件快照上下文缺少原成功请求边界");
                    yield observer.checkpointContext();
                }
                case "CHECKPOINT" -> {
                    if (!"RUN".equals(method) || observer == null || requestInFlight || observer.lastRequestId().isBlank()) throw new ApiException(403, "文件快照回执缺少原成功请求来源");
                    observer.checkpoint(map(arguments, "descriptor")); yield true;
                }
                case "MODEL_ACQUIRE", "TOOL_ACQUIRE" -> {
                    if (tools == null) throw new ApiException(403, "原调用没有逐请求资源会话");
                    AutoCloseable resource = "MODEL_ACQUIRE".equals(action) ? tools.acquireModel() : tools.acquireTool(); String token = id(); resources.put(token, resource); yield token;
                }
                case "RESOURCE_CLOSE" -> {
                    AutoCloseable resource = resources.remove(str(arguments, "token"));
                    if (resource == null) throw new ApiException(409, "资源回执不属于原申请或已经释放");
                    resource.close(); yield true;
                }
                case "INBOX" -> { if (tools == null) throw new ApiException(403, "原调用没有团队收件箱"); yield tools.inbox(); }
                case "TOOL" -> {
                    requireReceipt(arguments); if (tools == null) throw new ApiException(403, "原调用没有团队工具");
                    yield tools.invoke(str(arguments, "alias"), map(arguments, "arguments"), str(arguments, "requestId"), str(arguments, "toolCallId"));
                }
                case "MCP" -> {
                    requireReceipt(arguments); if (mcp == null) throw new ApiException(403, "原调用没有固定 MCP 工具");
                    yield mcp.invoke(str(arguments, "alias"), str(arguments, "requestId"), str(arguments, "toolCallId"));
                }
                default -> throw new IllegalArgumentException("核心回调未获支持");
            };
        }

        private void requireReceipt(Map<String, Object> arguments) {
            if (observer == null || requestInFlight || observer.lastRequestId().isBlank() || !observer.lastRequestId().equals(str(arguments, "requestId")) || str(arguments, "toolCallId").isBlank()) throw new ApiException(403, "工具缺少原模型的成功回执与工具编号");
        }
    }

    private static long integer(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Integer || value.get(field) instanceof Long)) throw new IllegalArgumentException("Worker 整数来源无效");
        return ((Number) value.get(field)).longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectValue(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Worker 原调用缺少对象回执"); return (Map<String, Object>) value; }
    private static String stringValue(Object value) { if (!(value instanceof String text) || text.isBlank()) throw new IllegalStateException("Worker 原调用缺少目录回执"); return text; }
}
