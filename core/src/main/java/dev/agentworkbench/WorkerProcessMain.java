package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.runtime.NativePtySession;
import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;
import static dev.agentworkbench.Json.*;

/**
 * 独立 JVM 只运行现场与模型；预算、授权、团队和 SQLite 仍由核心回调持有。
 */
public final class WorkerProcessMain {
    private final ObjectMapper mapper = new ObjectMapper();
    private final long pid = ProcessHandle.current().pid();
    private final String startedAt = ProcessHandle.current().info().startInstant().map(Object::toString).orElse("");
    private final CountDownLatch ended = new CountDownLatch(1);
    private final AtomicBoolean stopping = new AtomicBoolean();
    private final AtomicLong pulses = new AtomicLong();
    private final Map<String, Call> calls = new ConcurrentHashMap<>();
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    private final Set<String> callIds = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService heartbeats = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("workbench-process-heartbeat").unstarted(runnable));
    private final WorkerRpc rpc;
    private volatile Map<String, Object> assignment = obj();
    private volatile WorkspaceRuntime runtime;
    private volatile Path terminalData;

    private WorkerProcessMain() {
        rpc = new WorkerRpc(new WorkerWire(mapper, new FileInputStream(FileDescriptor.in), new FileOutputStream(FileDescriptor.out)), this::handle,
            (name, arguments) -> { throw new IllegalArgumentException("Worker 不接受无归属事件"); }, ignored -> stop(),
            exception -> "Worker 现场执行失败（" + exception.getClass().getSimpleName() + "）；原参数与异常因果不进入日志");
    }

    public static void main(String[] arguments) throws Exception {
        System.setOut(System.err);
        WorkerProcessMain worker = new WorkerProcessMain();
        Thread hook = Thread.ofPlatform().name("workbench-process-stop").unstarted(worker::stop);
        Runtime.getRuntime().addShutdownHook(hook);
        try {
            worker.rpc.event("HELLO", obj("protocolVersion", WorkerWire.PROTOCOL_VERSION, "pid", worker.pid, "processStartedAt", worker.startedAt,
                "parentPid", ProcessHandle.current().parent().map(ProcessHandle::pid).orElse(-1L),
                "controlEnvironmentPresent", System.getenv().keySet().stream().anyMatch(key -> key.toUpperCase(java.util.Locale.ROOT).startsWith("WORKBENCH_")),
                "jvmOptionsEnvironmentPresent", System.getenv().keySet().stream().anyMatch(key -> Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key.toUpperCase(java.util.Locale.ROOT))),
                "controlPropertiesPresent", System.getProperties().stringPropertyNames().stream().anyMatch(key -> key.startsWith("workbench."))));
            worker.ended.await();
        } finally {
            worker.stop(); worker.rpc.close();
            worker.rpc.awaitHandlers(5, TimeUnit.SECONDS);
            worker.awaitTerminals();
            if (worker.runtime != null) worker.runtime.stop("");
            if (worker.runtime != null) worker.runtime.closeWorkspaceWrites();
            try { Runtime.getRuntime().removeShutdownHook(hook); } catch (IllegalStateException ignored) { }
        }
    }

    private Object handle(String method, Map<String, Object> arguments) throws Exception {
        return switch (method) {
            case "BIND" -> bind(arguments);
            case "INVOKE" -> invoke(arguments);
            case "TERMINAL_CONTROL" -> terminalControl(arguments);
            case "CANCEL" -> cancel(arguments);
            case "RELEASE" -> release(arguments);
            case "RELEASE_WORKSPACE_WRITE" -> releaseWorkspaceWrite(arguments);
            default -> throw new IllegalArgumentException("Worker 方法未获支持");
        };
    }

    private synchronized Map<String, Object> bind(Map<String, Object> arguments) throws java.io.IOException {
        if (stopping.get() || !assignment.isEmpty()) throw new ApiException(409, "Worker 只能绑定一次原核心会话");
        Map<String, Object> worker = map(arguments, "worker");
        if (integer(worker, "pid") != pid || !startedAt.equals(str(worker, "processStartedAt")) || integer(worker, "protocolVersion") != WorkerWire.PROTOCOL_VERSION
                || !"LOCAL_PROCESS".equals(str(worker, "deployment")) || integer(worker, "epoch") < 1 || str(worker, "id").isBlank() || str(worker, "executionNodeId").isBlank()) {
            throw new ApiException(403, "绑定不属于实际 Worker 进程");
        }
        Path data = Path.of(str(arguments, "dataDirectory"));
        if (!data.isAbsolute()) throw new IllegalArgumentException("Worker 数据目录必须绝对定位");
        runtime = new WorkspaceRuntime(mapper, data.normalize().toString()); assignment = new LinkedHashMap<>(worker);
        terminalData = data.toRealPath();
        runtime.observeProcesses(facts -> {
            Map<String, Object> event = identity("PROCESS_OBSERVED"); event.put("process", facts);
            try { rpc.event("PROCESS_OBSERVED", event); } catch (Exception ignored) { stop(); }
        });
        heartbeats.scheduleWithFixedDelay(this::heartbeat, 3, 3, TimeUnit.SECONDS);
        return identity("READY");
    }

    private Object invoke(Map<String, Object> arguments) throws Exception {
        requireBound();
        String callId = str(arguments, "callId"), method = str(arguments, "method");
        if (callId.isBlank() || callId.length() > 100) throw new IllegalArgumentException("Worker 调用缺少原编号");
        Map<String, Object> lease = map(arguments, "lease"), parameters = map(arguments, "parameters");
        Claim claim = claim(lease, str(arguments, "claimToken"), integer(arguments, "callSequence"));
        Call call = new Call(callId, lease, parameters, integer(arguments, "callSequence"));
        try {
            requireMethod(lease, method); requireTarget(lease, method, parameters);
            if (callIds.size() >= 32_768 || !callIds.add(callId) || calls.putIfAbsent(callId, call) != null) throw new ApiException(409, "Worker 原调用不能重复执行或越过会话边界");
            if (claim.released.get()) throw new ApiException(409, "Worker 原领取已结束");
            call.callback("CHECK_CURRENT", obj());
            Object value;
            Map<String, Object> writeIdentity = map(lease, "workspaceWriteIdentity");
            if (writeIdentity.isEmpty()) value = execute(call, method);
            else if ("ACQUIRE_WRITE".equals(method)) value = obj("workspaceWriteGrant", runtime.acquireWorkspaceWrite(writeIdentity));
            else {
                Map<String, Object> grant = "PREPARE".equals(method) ? runtime.acquireWorkspaceWrite(writeIdentity) : obj();
                try (var protection = runtime.borrowWorkspaceWrite(writeIdentity)) {
                    value = execute(call, method);
                    if (!grant.isEmpty()) { Map<String, Object> prepared = valueMap(value); prepared.put("workspaceWriteGrant", grant); value = prepared; }
                }
            }
            return obj("callId", callId, "workerId", assignment.get("id"), "workerEpoch", assignment.get("epoch"), "executorPid", pid,
                "processStartedAt", startedAt, "workerLeaseId", lease.get("id"), "workspaceWriteLeaseId", lease.getOrDefault("workspaceWriteLeaseId", ""), "value", value);
        } finally { calls.remove(callId, call); claim.active.set(false); }
    }

    private Object execute(Call call, String method) {
        Map<String, Object> p = call.parameters;
        return switch (method) {
            case "PREPARE" -> map(p, "workspaceSelection").isEmpty() ? runtime.prepare(str(p, "sourcePath"), str(p, "taskId"), bool(p, "includeChanges", false))
                : runtime.prepareSelected(str(p, "sourcePath"), str(p, "taskId"), bool(p, "includeChanges", false), map(p, "workspaceSelection"));
            case "REVIEW_COPY" -> runtime.reviewCopy(str(p, "workspace"), str(p, "subjectId"), str(p, "version"));
            case "DELEGATION_COPY" -> runtime.delegationCopy(str(p, "workspace"), str(p, "subjectId"), str(p, "version"));
            case "INTEGRATE" -> runtime.integrateDelegation(str(p, "baseline"), str(p, "working"), str(p, "targetWorkspace"), str(p, "expectedResult"), map(p, "permissions"));
            case "RESTORE_CHECKPOINT" -> runtime.restoreCheckpoint(str(p, "workspace"), str(p, "taskId"), str(p, "operationId"), map(p, "preview"), call::checkedStop);
            case "TERMINAL" -> executeTerminal(call);
            case "VERIFY" -> runtime.verify(str(p, "workspace"), str(p, "command"), call::stopped, text -> call.callback("LOG", obj("text", text)));
            case "RUN" -> runtime.runWithMcp(map(p, "route"), str(p, "apiKey"), str(p, "workspace"), str(p, "prompt"), bool(p, "readOnly", true), strings(p.get("scope")),
                call::stopped, text -> call.callback("LOG", obj("text", text)), call.observer, objects(p.get("mcpBindings")), new WorkspaceRuntime.FixedToolInvoker() {
                    @Override public Map<String, Object> invoke(String alias) { return invoke(alias, "", ""); }
                    @Override public Map<String, Object> invoke(String alias, String requestId, String toolCallId) {
                        return valueMap(call.callback("MCP", obj("alias", alias, "requestId", requestId, "toolCallId", toolCallId)));
                    }
                });
            default -> throw new IllegalArgumentException("Worker 现场方法未获支持");
        };
    }

    /**
     * 核心分配的原领取固定身份和目录，多步依赖整合沿同一领取递增，不产生第二次派发。
     */
    private Claim claim(Map<String, Object> lease, String token, long sequence) throws Exception {
        if (!assignment.get("id").equals(lease.get("workerId")) || integer(lease, "epoch") != integer(assignment, "epoch")
                || !assignment.get("executionNodeId").equals(lease.get("executionNodeId")) || str(lease, "id").isBlank() || str(lease, "operationId").isBlank()
                || token.isBlank() || !digest(token).equals(str(lease, "claimDigest")) || sequence < 1) throw new ApiException(403, "调用不属于原 Worker 领取");
        String operation = str(lease, "operationKind") + ":" + str(lease, "operationId");
        synchronized (claims) {
            Claim original = claims.get(operation);
            if (original == null) {
                if (claims.size() >= 32_768) throw new ApiException(409, "Worker 原领取数量超过会话边界");
                original = new Claim(new LinkedHashMap<>(lease)); claims.put(operation, original);
            }
            for (String field : List.of("id", "operationId", "operationKind", "taskId", "projectId", "agentRunId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "workspaceWriteIdentity", "workerId", "epoch", "executionNodeId", "repositoryBindingId", "bindingVersion", "authorizationVersion", "workingDirectory", "realWorkingDirectory", "operationType", "subjectId", "claimDigest", "workspaceSelection", "workspaceOwnership", "terminalId")) {
                if (!java.util.Objects.equals(original.lease.get(field), lease.get(field))) throw new ApiException(403, "原领取的固定来源已变化");
            }
            if (original.released.get() || sequence != original.sequence + 1 || !original.active.compareAndSet(false, true)) throw new ApiException(409, "Worker 原领取不能重放、并发或跳过调用");
            original.sequence = sequence; return original;
        }
    }

    private void requireMethod(Map<String, Object> lease, String method) {
        Set<String> allowed = switch (str(lease, "operationType")) {
            case "WORKSPACE" -> Set.of("PREPARE");
            case "WORKSPACE_WRITE" -> Set.of("ACQUIRE_WRITE");
            case "CHECKPOINT_RESTORE" -> Set.of("RESTORE_CHECKPOINT");
            case "TERMINAL" -> Set.of("TERMINAL");
            case "MODEL", "SUBAGENT_MODEL", "WORKFLOW_MODEL", "WORKFLOW_GENERATION", "REVIEW" -> Set.of("RUN");
            case "VERIFICATION" -> Set.of("VERIFY");
            case "REVIEW_SNAPSHOT", "WORKFLOW_ROOT_SNAPSHOT" -> Set.of("REVIEW_COPY");
            case "SUBAGENT_SNAPSHOT", "WORKFLOW_SNAPSHOT" -> Set.of("DELEGATION_COPY");
            case "SUBAGENT_DEPENDENCIES", "WORKFLOW_INPUTS" -> Set.of("INTEGRATE", "REVIEW_COPY");
            case "ADOPT_SUBAGENT", "WORKFLOW_INTEGRATE" -> Set.of("INTEGRATE");
            default -> Set.of();
        };
        if (!allowed.contains(method)) throw new ApiException(403, "现场方法不属于原操作类型");
    }

    private void requireTarget(Map<String, Object> lease, String method, Map<String, Object> parameters) throws Exception {
        String target = str(parameters, "PREPARE".equals(method) ? "sourcePath" : "INTEGRATE".equals(method) ? "targetWorkspace" : "workspace");
        Path path = Path.of(target);
        if (!path.isAbsolute() || !ExecutionNodes.samePath(path.normalize().toString(), str(lease, "workingDirectory"))
                || !ExecutionNodes.samePath(path.toRealPath().toString(), str(lease, "realWorkingDirectory"))) throw new ApiException(403, "现场目录不属于原领取或解析目标已变化");
        if ("PREPARE".equals(method) && !str(lease, "taskId").equals(str(parameters, "taskId"))) throw new ApiException(403, "准备目录不属于原根任务");
        if ("TERMINAL".equals(method) && (!parameters.keySet().equals(Set.of("workspace", "terminalId", "recipe", "columns", "rows"))
                || str(lease, "terminalId").isBlank() || !str(lease, "terminalId").equals(str(parameters, "terminalId"))
                || map(lease, "workspaceWriteIdentity").isEmpty())) throw new ApiException(403, "终端参数不属于原意图和整次现场保护");
        if ("RESTORE_CHECKPOINT".equals(method) && (!str(lease, "taskId").equals(str(parameters, "taskId")) || !str(lease, "operationId").equals(str(parameters, "operationId"))
                || !str(lease, "taskId").equals(str(map(parameters, "preview"), "taskId")) || map(lease, "workspaceWriteIdentity").isEmpty())) throw new ApiException(403, "撤销参数不属于原根文件操作与实际写入保护");
        if ("PREPARE".equals(method) && !map(lease, "workspaceSelection").equals(map(parameters, "workspaceSelection"))) throw new ApiException(403, "准备提交与原 Worker 领取不一致");
        if ("PREPARE".equals(method) && !map(lease, "workspaceSelection").isEmpty()
                && (!(parameters.get("includeChanges") instanceof Boolean) || bool(parameters, "includeChanges", false) != bool(map(lease, "workspaceSelection"), "includeChanges", false))) throw new ApiException(403, "带入改动参数与原启动选择不一致");
        if (!map(lease, "workspaceOwnership").isEmpty()) runtime.requireWorkspaceOwnership(str(lease, "taskId"), target, obj("workspaceOwnership", lease.get("workspaceOwnership")));
        if (Set.of("REVIEW_COPY", "DELEGATION_COPY").contains(method) && !str(lease, "subjectId").equals(str(parameters, "subjectId"))) throw new ApiException(403, "副本不属于原任务或副执行");
    }

    private Map<String, Object> cancel(Map<String, Object> arguments) {
        requireBound(); String taskId = str(arguments, "taskId"); int count = 0;
        if (taskId.isBlank()) throw new IllegalArgumentException("Worker 停止缺少原任务");
        for (Call call : calls.values()) if (taskId.equals(str(call.lease, "taskId"))) { call.cancelled.set(true); count++; }
        runtime.stop(taskId); return obj("requested", true, "calls", count);
    }

    /**
     * 原 TERMINAL 调用持有整次保护，输出读取及原生清理结束后才返回。
     */
    private Map<String, Object> executeTerminal(Call call) {
        Map<String, Object> context = valueMap(call.callback("TERMINAL_CONTEXT", obj()));
        if (!str(call.parameters, "terminalId").equals(str(context, "terminalId"))) throw new ApiException(403, "终端输出上下文失去原编号");
        TerminalOutputArchive.Writer output = new TerminalOutputArchive(mapper, terminalData).create(str(context, "terminalId"), str(context, "epoch"), map(context, "owner"), integer(context, "outputLimit"));
        call.callback("TERMINAL_LAUNCH", obj());
        java.util.function.Consumer<Map<String, Object>> publish = descriptor -> call.callback("TERMINAL_OUTPUT", descriptor);
        NativePtySession terminal;
        try {
            terminal = NativePtySession.open(terminalData, Path.of(str(call.lease, "realWorkingDirectory")), str(call.parameters, "recipe"),
                Math.toIntExact(integer(call.parameters, "columns")), Math.toIntExact(integer(call.parameters, "rows")), facts -> {
                    Map<String, Object> event = identity("PROCESS_OBSERVED"); event.put("process", facts);
                    try { rpc.event("PROCESS_OBSERVED", event); } catch (Exception exception) { stop(); }
                }, output, publish);
        } catch (NativePtySession.StartupFailure failure) {
            return publishTerminalEnd(call, failure.receipt());
        } catch (java.io.IOException exception) { throw new IllegalStateException("原终端启动缺少完整身份，Worker 与原后代必须核对", exception); }
        synchronized (call.terminalAdmission) { call.terminal = terminal; call.terminalAccepting = true; }
        boolean startupAcknowledged = true;
        try { call.callback("TERMINAL_STARTED", terminal.started()); }
        catch (RuntimeException exception) { startupAcknowledged = false; call.cancelled.set(true); terminal.requestStop(); }
        Map<String, Object> result;
        try { result = terminal.execute(call::stopped, output, publish); }
        finally {
            synchronized (call.terminalAdmission) { call.terminalAccepting = false; }
            boolean interrupted = false;
            while (call.terminalControls.get() > 0) {
                try { TimeUnit.MILLISECONDS.sleep(20); }
                catch (InterruptedException exception) { interrupted = true; terminal.requestStop(); }
            }
            if (interrupted) Thread.currentThread().interrupt();
        }
        result.put("startupAcknowledged", startupAcknowledged);
        if (!startupAcknowledged) result.put("status", "UNKNOWN");
        return publishTerminalEnd(call, result);
    }

    /**
     * 原生完整收尾后发布原结束描述，启动失败和正常停止共用原回执边界。
     */
    private Map<String, Object> publishTerminalEnd(Call call, Map<String, Object> result) {
        try { call.callback("TERMINAL_OUTPUT_END", map(result, "outputEnd")); }
        catch (RuntimeException exception) { result.put("endPublicationAcknowledged", false); result.put("status", "UNKNOWN"); }
        return result;
    }

    /**
     * 控制仍定位原在途调用，原生写入前消费一次核心持久授权。
     */
    private Map<String, Object> terminalControl(Map<String, Object> request) throws Exception {
        requireBound(); Call call = calls.get(str(request, "callId"));
        if (call == null || !"TERMINAL".equals(str(call.lease, "operationType")) || call.stopped()
                || !str(call.lease, "id").equals(str(request, "workerLeaseId")) || !str(call.lease, "operationId").equals(str(request, "operationId"))
                || !str(call.lease, "claimDigest").equals(digest(str(request, "claimToken"))) || call.sequence != integer(request, "callSequence")
                || !str(call.lease, "terminalId").equals(str(request, "terminalId"))) throw new ApiException(403, "控制不属于原在途终端调用");
        Map<String, Object> action = map(request, "action");
        byte[] bytes;
        String encoded = str(request, "inputBase64"); if (encoded.length() > 21_848) throw new ApiException(413, "终端输入越过原帧边界");
        try { bytes = java.util.Base64.getDecoder().decode(encoded); }
        catch (IllegalArgumentException exception) { throw new ApiException(400, "终端输入编码无效"); }
        String kind = str(action, "kind");
        if (!str(request, "terminalId").equals(str(action, "terminalId")) || bytes.length != integer(action, "length")
                || ("INPUT".equals(kind) && (bytes.length < 1 || bytes.length > TerminalLedger.MAX_INPUT_BYTES))
                || (!"INPUT".equals(kind) && bytes.length != 0) || !Set.of("INPUT", "RESIZE", "STOP").contains(kind)) throw new ApiException(403, "终端控制事实与原输入不一致");
        byte[] facts = "INPUT".equals(kind) ? bytes : (kind + ":" + integer(action, "columns") + ":" + integer(action, "rows")).getBytes(StandardCharsets.US_ASCII);
        if (!HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(facts)).equals(str(action, "sha256"))) throw new ApiException(403, "终端控制摘要与原输入不一致");
        synchronized (call.terminalAdmission) {
            if (!call.terminalAccepting || call.terminal == null || !call.terminalRequestIds.add(str(action, "requestId"))) throw new ApiException(409, "原终端控制已结束或原编号已使用");
            call.terminalControls.incrementAndGet();
        }
        try {
            requireTarget(call.lease, "TERMINAL", call.parameters);
            call.callback("TERMINAL_AUTHORIZE", action);
            Map<String, Object> effect = obj();
            switch (kind) {
                case "INPUT" -> call.terminal.input(bytes);
                case "RESIZE" -> effect = call.terminal.resize(Math.toIntExact(integer(action, "columns")), Math.toIntExact(integer(action, "rows")));
                case "STOP" -> call.terminal.requestStop();
                default -> throw new ApiException(403, "终端控制未获支持");
            }
            return obj("callId", call.id, "workerLeaseId", call.lease.get("id"), "operationId", call.lease.get("operationId"), "terminalId", call.lease.get("terminalId"),
                "requestId", action.get("requestId"), "sha256", action.get("sha256"), "sequence", action.get("sequence"), "effect", effect,
                "executorPid", pid, "processStartedAt", startedAt, "state", "ACKNOWLEDGED");
        } finally { call.terminalControls.decrementAndGet(); }
    }

    private void awaitTerminals() {
        boolean interrupted = false;
        while (calls.values().stream().anyMatch(call -> "TERMINAL".equals(str(call.lease, "operationType")))) {
            for (Call call : calls.values()) if (call.terminal != null) call.terminal.requestStop();
            try { TimeUnit.MILLISECONDS.sleep(20); }
            catch (InterruptedException exception) { interrupted = true; }
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    private Map<String, Object> release(Map<String, Object> arguments) throws Exception {
        requireBound(); String operation = str(arguments, "operationKind") + ":" + str(arguments, "operationId");
        synchronized (claims) {
            Claim original = claims.get(operation);
            if (original == null || !str(original.lease, "id").equals(str(arguments, "workerLeaseId")) || !str(original.lease, "claimDigest").equals(digest(str(arguments, "claimToken")))) throw new ApiException(403, "释放不属于原领取");
            if (original.active.get()) throw new ApiException(409, "原领取仍有实际在途调用");
            original.released.set(true); return obj("released", true);
        }
    }

    /**
     * 原根没有实际在途调用后才返回锁关闭事实；重开核对仍使用旧进程身份。
     */
    private Map<String, Object> releaseWorkspaceWrite(Map<String, Object> arguments) {
        requireBound(); Map<String, Object> identity = map(arguments, "identity"); String taskId = str(identity, "taskId");
        if (taskId.isBlank() || calls.values().stream().anyMatch(call -> taskId.equals(str(call.lease, "taskId")))
                || claims.values().stream().anyMatch(claim -> claim.active.get() && taskId.equals(str(claim.lease, "taskId")))) throw new ApiException(409, "原根仍有实际调用，现场写入保护继续持有");
        return runtime.releaseWorkspaceWrite(identity, str(arguments, "note"));
    }

    private void requireBound() { if (stopping.get() || assignment.isEmpty() || runtime == null) throw new ApiException(409, "Worker 尚未绑定或已经停止"); }
    private Map<String, Object> identity(String state) { return obj("state", state, "protocolVersion", WorkerWire.PROTOCOL_VERSION, "workerId", assignment.get("id"), "epoch", assignment.get("epoch"), "pid", pid, "processStartedAt", startedAt); }

    private void heartbeat() {
        if (stopping.get()) return;
        Map<String, Object> pulse = identity("HEARTBEAT"); pulse.put("sequence", pulses.incrementAndGet()); pulse.put("activeCalls", calls.size());
        try { rpc.event("HEARTBEAT", pulse); } catch (Exception ignored) { stop(); }
    }

    private void stop() {
        if (!stopping.compareAndSet(false, true)) return;
        heartbeats.shutdownNow();
        for (Call call : calls.values()) call.cancelled.set(true);
        WorkspaceRuntime executor = runtime;
        if (executor != null) { try { executor.stop(""); } catch (RuntimeException ignored) { } }
        ended.countDown();
    }

    private static long integer(Map<String, Object> value, String field) {
        Object number = value.get(field);
        if (!(number instanceof Integer || number instanceof Long)) throw new IllegalArgumentException("Worker 整数来源无效");
        return ((Number) number).longValue();
    }

    private static String digest(String token) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))); }
    @SuppressWarnings("unchecked") private static Map<String, Object> valueMap(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("核心回调缺少对象回执"); return (Map<String, Object>) value; }

    private static final class Claim {
        final Map<String, Object> lease;
        final AtomicBoolean released = new AtomicBoolean();
        final AtomicBoolean active = new AtomicBoolean();
        long sequence;
        Claim(Map<String, Object> lease) { this.lease = lease; }
    }

    private final class Call {
        final String id;
        final Map<String, Object> lease;
        final Map<String, Object> parameters;
        final long sequence;
        final AtomicBoolean cancelled = new AtomicBoolean();
        final RemoteObserver observer = new RemoteObserver();
        private final ReentrantLock callbackLock = new ReentrantLock();
        private long callbacks;
        private final Object terminalAdmission = new Object();
        private final java.util.concurrent.atomic.AtomicInteger terminalControls = new java.util.concurrent.atomic.AtomicInteger();
        private final Set<String> terminalRequestIds = ConcurrentHashMap.newKeySet();
        private volatile NativePtySession terminal;
        private boolean terminalAccepting;
        Call(String id, Map<String, Object> lease, Map<String, Object> parameters, long sequence) { this.id = id; this.lease = lease; this.parameters = parameters; this.sequence = sequence; }
        boolean stopped() { return cancelled.get() || stopping.get() || !rpc.open(); }

        /**
         * 每个撤销写入边界同步核对原核心授权，停止回执先于后续文件效果。
         */
        boolean checkedStop() {
            if (stopped()) return true;
            callback("CHECK_CURRENT", obj()); return stopped();
        }

        Object callback(String action, Map<String, Object> arguments) {
            callbackLock.lock();
            try {
                var result = rpc.request("CALLBACK", obj("callId", id, "workerId", assignment.get("id"), "workerEpoch", assignment.get("epoch"), "workerLeaseId", lease.get("id"),
                    "operationId", lease.get("operationId"), "operationKind", lease.get("operationKind"), "taskId", lease.get("taskId"), "workspaceWriteLeaseId", lease.getOrDefault("workspaceWriteLeaseId", ""), "executorPid", pid,
                    "processStartedAt", startedAt, "callSequence", sequence, "callbackSequence", ++callbacks, "action", action, "arguments", arguments));
                try {
                    while (true) {
                        if (stopped() && !Set.of("MODEL_END", "RESOURCE_CLOSE", "MCP", "TOOL", "TERMINAL_OUTPUT", "TERMINAL_OUTPUT_END").contains(action)) throw new CancellationException("原 Worker 调用已停止");
                        try { return result.get(150, TimeUnit.MILLISECONDS); } catch (TimeoutException ignored) { }
                    }
                } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new CancellationException("原 Worker 回调被停止"); }
                catch (ExecutionException exception) {
                    if (exception.getCause() instanceof WorkerRpc.RemoteFailure remote) {
                        if ("VALIDATION".equals(remote.category())) throw new IllegalArgumentException(remote.getMessage());
                        if ("CANCELLED".equals(remote.category())) throw new CancellationException(remote.getMessage());
                        if ("API".equals(remote.category())) throw new ApiException(remote.status(), remote.getMessage());
                    }
                    throw new IllegalStateException("核心回调未取得完整回执，不自动重试");
                }
            } finally { callbackLock.unlock(); }
        }

        private final class RemoteObserver implements ModelCallObserver {
            private String receiptId = "";
            private final AgentToolSession tools = new AgentToolSession() {
                @Override public List<Map<String, Object>> definitions() { return objects(parameters.get("definitions")); }
                @Override public Map<String, Object> permissions() { return map(parameters, "permissions"); }
                @Override public List<Map<String, Object>> inbox() { return objects(callback("INBOX", obj())); }
                @Override public void checkCurrent() { callback("CHECK_CURRENT", obj()); }
                @Override public AutoCloseable acquireModel() { return resource("MODEL_ACQUIRE"); }
                @Override public AutoCloseable acquireTool() { return resource("TOOL_ACQUIRE"); }
                @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { return invoke(alias, arguments, "", ""); }
                @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments, String requestId, String toolCallId) {
                    return valueMap(callback("TOOL", obj("alias", alias, "arguments", arguments, "requestId", requestId, "toolCallId", toolCallId)));
                }
                private AutoCloseable resource(String action) {
                    String token = java.util.Objects.toString(callback(action, obj()), "");
                    if (token.isBlank()) throw new IllegalStateException("核心资源回执缺少原编号");
                    AtomicBoolean released = new AtomicBoolean();
                    return () -> { if (released.compareAndSet(false, true)) callback("RESOURCE_CLOSE", obj("token", token)); };
                }
            };
            @Override public boolean begin(Map<String, Object> metadata) { receiptId = ""; return Boolean.TRUE.equals(callback("MODEL_BEGIN", obj("metadata", metadata))); }
            @Override public void end(Map<String, Object> observation) { receiptId = java.util.Objects.toString(callback("MODEL_END", obj("observation", observation)), ""); }
            @Override public String lastRequestId() { return receiptId; }
            @Override public Map<String, Object> checkpointContext() { return bool(parameters, "hasCheckpoints", false) ? valueMap(callback("CHECKPOINT_CONTEXT", obj())) : obj(); }
            @Override public void checkpoint(Map<String, Object> descriptor) { callback("CHECKPOINT", obj("descriptor", descriptor)); }
            @Override public AgentToolSession tools() { return bool(parameters, "hasTools", false) ? tools : null; }
        }
    }
}
