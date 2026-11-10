package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
    private final ThreadLocal<Claim> current = new ThreadLocal<>();
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Map<String, Invocation> invocations = new ConcurrentHashMap<>();
    private volatile WorkerProcess process;
    private volatile LocalExecutionWorker worker;
    private ExecutionStore store;
    private ManagedProcessLedger processLedger;
    private boolean started;

    public ProcessWorkspaceRuntime(ObjectMapper mapper, @Value("${workbench.data-dir:./data}") String dataDirectory) {
        super(mapper, dataDirectory); this.mapper = mapper; this.dataDirectory = dataDirectory;
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
    void fence(String reason) { if (process != null) process.fence(reason); }
    void closeProcess() { if (process != null) process.close(); }

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

    @Override public String reviewCopy(String workspace, String subjectId, String version) {
        return stringValue(invoke("REVIEW_COPY", obj("workspace", workspace, "subjectId", subjectId, "version", version), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> delegationCopy(String workspace, String subjectId, String version) {
        return objectValue(invoke("DELEGATION_COPY", obj("workspace", workspace, "subjectId", subjectId, "version", version), () -> false, ignored -> {}, null, null));
    }

    @Override public Map<String, Object> integrateDelegation(String baseline, String working, String target, String expected, Map<String, Object> permissions) {
        return objectValue(invoke("INTEGRATE", obj("baseline", baseline, "working", working, "targetWorkspace", target, "expectedResult", expected, "permissions", permissions), () -> false, ignored -> {}, null, null));
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
            "hasTools", tools != null, "definitions", tools == null ? List.of() : tools.definitions(), "permissions", tools == null ? obj() : tools.permissions(), "mcpBindings", mcpBindings),
            cancelled, log, observer, mcpInvoker));
    }

    @Override public void stop(String taskId) { if (process != null && !taskId.isBlank()) process.cancel(taskId); }

    private Object invoke(String method, Map<String, Object> parameters, BooleanSupplier cancelled, Consumer<String> log, ModelCallObserver observer, FixedToolInvoker mcp) {
        Claim claim = current.get();
        if (claim == null) throw new ApiException(403, "现场执行必须持有核心的原 Worker 领取");
        requireCurrent(claim, cancelled);
        long sequence;
        synchronized (claim) {
            if (!claim.active.compareAndSet(false, true)) throw new ApiException(409, "同一原领取仍有实际在途调用");
            sequence = ++claim.sequence;
        }
        Invocation call = new Invocation(id(), claim, sequence, method, cancelled, log, observer, mcp); invocations.put(call.id, call);
        boolean interrupted = false;
        try {
            var result = process.invoke(call.id, claim.lease, claim.token, sequence, method, parameters);
            long deadline = System.nanoTime() + Duration.ofMinutes(16).toNanos();
            Map<String, Object> response;
            while (true) {
                if ((cancelled.getAsBoolean() || !worker.current(str(claim.lease, "taskId"))) && call.cancelSent.compareAndSet(false, true)) process.cancel(str(claim.lease, "taskId"));
                if (System.nanoTime() >= deadline) process.fence("WORKER_CALL_TIMED_OUT");
                try { response = objectValue(result.get(150, TimeUnit.MILLISECONDS)); break; }
                catch (TimeoutException ignored) { }
            }
            if (!call.id.equals(str(response, "callId")) || !str(claim.lease, "workerId").equals(str(response, "workerId")) || integer(claim.lease, "epoch") != integer(response, "workerEpoch")
                    || integer(response, "executorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(response, "processStartedAt"))
                    || !str(claim.lease, "id").equals(str(response, "workerLeaseId")) || !response.containsKey("value")) {
                process.fence("WORKER_INVOCATION_RECEIPT_INVALID"); throw new IllegalStateException("独立 Worker 结果失去原领取来源，必须核对");
            }
            Object value = response.get("value");
            if (value instanceof Map<?, ?> outcome && "UNKNOWN".equals(outcome.get("status")) && process.childrenRunning()) process.fence("WORKER_COMMAND_STOP_UNKNOWN");
            return value;
        } catch (InterruptedException exception) {
            interrupted = true; process.fence("WORKER_WAIT_INTERRUPTED"); throw new CancellationException("原 Worker 调用被停止，失去的结果仍需核对");
        } catch (ExecutionException exception) {
            if (exception.getCause() instanceof WorkerRpc.RemoteFailure remote) {
                if ("VALIDATION".equals(remote.category())) throw new IllegalArgumentException(remote.getMessage());
                if ("CANCELLED".equals(remote.category())) throw new CancellationException(remote.getMessage());
                if ("API".equals(remote.category()) && remote.status() < 500) throw new ApiException(remote.status(), remote.getMessage());
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
        final AtomicBoolean cancelSent = new AtomicBoolean();
        /**
         * 阻塞回调保持原顺序，同时允许 Java 21 虚拟线程释放载体供子调用推进。
         */
        final ReentrantLock callbackLock = new ReentrantLock();
        final Map<String, AutoCloseable> resources = new LinkedHashMap<>();
        long callbacks;
        boolean requestInFlight;
        boolean closed;
        Invocation(String id, Claim claim, long sequence, String method, BooleanSupplier cancelled, Consumer<String> log, ModelCallObserver observer, FixedToolInvoker mcp) {
            this.id = id; this.claim = claim; this.sequence = sequence; this.method = method; this.cancelled = cancelled; this.log = log; this.observer = observer;
            this.tools = observer == null ? null : observer.tools(); this.mcp = mcp;
        }

        Object callback(Map<String, Object> message) throws Exception {
            Map<String, Object> lease = claim.lease;
            if (closed || !str(lease, "workerId").equals(str(message, "workerId")) || integer(lease, "epoch") != integer(message, "workerEpoch")
                    || !str(lease, "id").equals(str(message, "workerLeaseId")) || !str(lease, "operationId").equals(str(message, "operationId"))
                    || !str(lease, "operationKind").equals(str(message, "operationKind")) || !str(lease, "taskId").equals(str(message, "taskId"))
                    || integer(message, "executorPid") != process.pid() || !str(process.processIdentity(), "processStartedAt").equals(str(message, "processStartedAt"))
                    || integer(message, "callSequence") != sequence || integer(message, "callbackSequence") != callbacks + 1) throw new ApiException(403, "回调失去原 Worker、领取或调用顺序");
            callbacks++;
            String action = str(message, "action"); Map<String, Object> arguments = map(message, "arguments");
            if (!List.of("MODEL_END", "RESOURCE_CLOSE", "LOG").contains(action)) requireCurrent(claim, cancelled);
            return switch (action) {
                case "CHECK_CURRENT" -> { if (tools != null) tools.checkCurrent(); yield true; }
                case "LOG" -> { log.accept(str(arguments, "text")); yield true; }
                case "MODEL_BEGIN" -> {
                    if (!"RUN".equals(method) || observer == null || requestInFlight) throw new ApiException(409, "模型派发不属于原空闲请求边界");
                    requestInFlight = observer.begin(map(arguments, "metadata")); yield requestInFlight;
                }
                case "MODEL_END" -> {
                    if (!requestInFlight || observer == null) throw new ApiException(409, "模型回执缺少原请求派发");
                    observer.end(map(arguments, "observation")); requestInFlight = false; yield observer.lastRequestId();
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
