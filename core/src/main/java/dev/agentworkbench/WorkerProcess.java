package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.LockSupport;
import java.util.function.Consumer;
import java.util.zip.ZipFile;
import static dev.agentworkbench.Json.*;

/**
 * 核心持有实际子进程和私有管道；出生时间、握手及原代次共同约束执行来源。
 */
final class WorkerProcess implements AutoCloseable {
    private final Path dataDirectory;
    private final Process process;
    private final String startedAt;
    private final WorkerRpc rpc;
    private final CompletableFuture<Map<String, Object>> hello = new CompletableFuture<>();
    private final AtomicBoolean lost = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();
    private final Map<String, CapturedProcess> descendants = new ConcurrentHashMap<>();
    private final ScheduledExecutorService monitor = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("workbench-worker-process-watch").unstarted(runnable));
    private volatile Map<String, Object> assignment = obj();
    private volatile Consumer<Map<String, Object>> pulses = ignored -> {};
    private volatile Consumer<String> failure = ignored -> {};
    private volatile boolean ready;
    private long pulseSequence;

    WorkerProcess(ObjectMapper mapper, String dataDirectory, WorkerRpc.Handler callbacks) {
        this.dataDirectory = Path.of(dataDirectory).toAbsolutePath().normalize();
        Process launched = null; WorkerRpc channel = null;
        try {
            Files.createDirectories(this.dataDirectory); Path nio = nioDirectory();
            ProcessBuilder builder = new ProcessBuilder(command(nio)).directory(this.dataDirectory.toFile());
            builder.environment().keySet().removeIf(key -> key.toUpperCase(Locale.ROOT).startsWith("WORKBENCH_") || Set.of("JAVA_TOOL_OPTIONS", "JDK_JAVA_OPTIONS", "_JAVA_OPTIONS").contains(key.toUpperCase(Locale.ROOT)));
            process = builder.start(); launched = process; startedAt = process.info().startInstant().map(Object::toString).orElse("");
            Thread.ofPlatform().daemon(true).name("workbench-worker-stderr").start(() -> {
                try (var errors = process.getErrorStream()) { byte[] buffer = new byte[4096]; while (errors.read(buffer) != -1) { } }
                catch (IOException ignored) { }
            });
            rpc = new WorkerRpc(new WorkerWire(mapper, process.getInputStream(), process.getOutputStream()), callbacks, this::event,
                ignored -> signalLoss("WORKER_CHANNEL_LOST"));
            channel = rpc;
            process.onExit().thenRun(() -> signalLoss("WORKER_PROCESS_EXITED"));
            Map<String, Object> actual = hello.get(10, TimeUnit.SECONDS);
            if (startedAt.isBlank() || number(actual, "pid") != process.pid() || !startedAt.equals(str(actual, "processStartedAt"))) throw new IOException("Worker 出生回执无法对应实际进程");
            monitor.scheduleWithFixedDelay(this::observe, 1, 1, TimeUnit.SECONDS);
        } catch (Exception exception) {
            monitor.shutdownNow();
            if (launched != null) {
                launched.descendants().map(CapturedProcess::capture).forEach(CapturedProcess::kill);
                if (launched.isAlive()) launched.destroyForcibly();
                try { launched.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                if (!launched.isAlive() && channel != null) channel.close();
            }
            throw new ApiException(503, "无法启动或核验独立 Worker，私有启动信息不会写入日志");
        }
    }

    Map<String, Object> processIdentity() { return obj("pid", process.pid(), "processStartedAt", startedAt, "deployment", "LOCAL_PROCESS", "parentPid", ProcessHandle.current().pid(),
        "corePid", ProcessHandle.current().pid(), "coreStartedAt", ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""), "protocolVersion", WorkerWire.PROTOCOL_VERSION,
        "controlEnvironmentPresent", false, "jvmOptionsEnvironmentPresent", false, "controlPropertiesPresent", false); }
    long pid() { return process.pid(); }
    boolean available() { return ready && !closing.get() && !lost.get() && process.isAlive() && rpc.open(); }
    boolean alive() { return process.isAlive(); }
    boolean treeStopped() { return !process.isAlive() && descendants.values().stream().noneMatch(CapturedProcess::running); }
    boolean childrenRunning() { return descendants.values().stream().anyMatch(CapturedProcess::running); }

    /**
     * 原调用失联后继续持有资源，直到实际进程及已观察子进程停止；中断不能伪造停止。
     */
    void awaitStopped() {
        boolean interrupted = false;
        while (!treeStopped()) {
            stopTree(); LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(100));
            if (Thread.interrupted()) interrupted = true;
        }
        if (interrupted) Thread.currentThread().interrupt();
    }

    void fence(String reason) { signalLoss(reason); }
    Map<String, Object> stopFacts() { return obj("executorPid", process.pid(), "processStartedAt", startedAt, "processStopped", !process.isAlive(), "observedProcessCount", descendants.size(),
        "unconfirmedProcesses", descendants.values().stream().filter(CapturedProcess::running).limit(50).map(CapturedProcess::facts).toList(), "treeStopped", treeStopped()); }

    void bind(Map<String, Object> worker, Consumer<Map<String, Object>> heartbeats, Consumer<String> disconnected) {
        assignment = new LinkedHashMap<>(worker); pulses = heartbeats; failure = disconnected;
        if (lost.get()) { disconnected.accept("WORKER_STARTUP_LOST"); throw new ApiException(503, "Worker 启动会话已失效"); }
        try {
            Map<String, Object> response = objectResult(rpc.request("BIND", obj("worker", worker, "dataDirectory", dataDirectory.toString())).get(10, TimeUnit.SECONDS));
            if (!"READY".equals(str(response, "state")) || !str(worker, "id").equals(str(response, "workerId")) || number(worker, "epoch") != number(response, "epoch")
                    || number(response, "pid") != process.pid() || !startedAt.equals(str(response, "processStartedAt")) || number(response, "protocolVersion") != WorkerWire.PROTOCOL_VERSION) throw new IOException("Worker 绑定回执失去实际来源");
            if (lost.get() || closing.get()) throw new IOException("Worker 绑定期间已失效");
            ready = true;
        } catch (Exception exception) {
            signalLoss("WORKER_BINDING_LOST");
            Throwable cause = exception instanceof java.util.concurrent.ExecutionException ? exception.getCause() : exception;
            String fact = cause instanceof WorkerRpc.RemoteFailure remote ? remote.category() + "/" + remote.status() + " · " + remote.getMessage() : cause.getClass().getSimpleName();
            throw new ApiException(503, "Worker 未取得原身份的绑定回执（" + fact + "）");
        }
    }

    CompletableFuture<Object> invoke(String callId, Map<String, Object> lease, String token, long callSequence, String method, Map<String, Object> parameters) {
        requireAvailable();
        return rpc.request("INVOKE", obj("callId", callId, "lease", lease, "claimToken", token, "callSequence", callSequence, "method", method, "parameters", parameters));
    }

    void cancel(String taskId) {
        if (!available()) return;
        rpc.request("CANCEL", obj("taskId", taskId));
    }

    CompletableFuture<Object> release(Map<String, Object> lease, String token) {
        requireAvailable();
        return rpc.request("RELEASE", obj("operationId", lease.get("operationId"), "operationKind", lease.get("operationKind"), "workerLeaseId", lease.get("id"), "claimToken", token));
    }

    void requireAvailable() { if (!available()) throw new ApiException(409, "原 Worker 进程或私有会话已经停止，不能继续派发"); }

    private void event(String name, Map<String, Object> arguments) {
        try {
            if ("HELLO".equals(name)) {
                if (hello.isDone() || number(arguments, "protocolVersion") != WorkerWire.PROTOCOL_VERSION || number(arguments, "pid") != process.pid()
                        || number(arguments, "parentPid") != ProcessHandle.current().pid() || !Boolean.FALSE.equals(arguments.get("controlEnvironmentPresent"))
                        || !Boolean.FALSE.equals(arguments.get("jvmOptionsEnvironmentPresent")) || !Boolean.FALSE.equals(arguments.get("controlPropertiesPresent"))) throw new IOException("Worker 启动身份或环境无效");
                hello.complete(new LinkedHashMap<>(arguments)); return;
            }
            if (assignment.isEmpty() || !str(assignment, "id").equals(str(arguments, "workerId")) || number(assignment, "epoch") != number(arguments, "epoch")
                    || number(arguments, "pid") != process.pid() || !startedAt.equals(str(arguments, "processStartedAt"))) throw new IOException("Worker 事件失去原进程来源");
            if ("PROCESS_OBSERVED".equals(name)) {
                Map<String, Object> facts = map(arguments, "process"); long owner = number(facts, "observedFromPid"); String ownerStart = str(facts, "observedFromStartedAt");
                if (!(owner == process.pid() && startedAt.equals(ownerStart)) && !descendants.containsKey(owner + "@" + ownerStart)) throw new IOException("子进程事实缺少原启动来源");
                long child = number(facts, "pid"); String birth = str(facts, "processStartedAt");
                if (child < 1 || child == process.pid() || child == ProcessHandle.current().pid() || descendants.size() >= 32_768) throw new IOException("子进程事实越过原会话边界");
                descendants.putIfAbsent(child + "@" + birth, new CapturedProcess(child, birth, ProcessHandle.of(child).orElse(null))); return;
            }
            if (!"HEARTBEAT".equals(name) || number(arguments, "sequence") != pulseSequence + 1) throw new IOException("Worker 心跳顺序无效");
            pulseSequence++; pulses.accept(new LinkedHashMap<>(arguments));
        } catch (Exception exception) { signalLoss("WORKER_PROTOCOL_SOURCE_INVALID"); }
    }

    private void observe() {
        if (closing.get() || lost.get()) return;
        try { captureTree(); if (!process.isAlive() || !rpc.open()) signalLoss("WORKER_PROCESS_OR_CHANNEL_LOST"); }
        catch (RuntimeException exception) { signalLoss("WORKER_PROCESS_OBSERVATION_LOST"); }
    }

    private void signalLoss(String reason) {
        if (closing.get() || !lost.compareAndSet(false, true)) return;
        ready = false; hello.completeExceptionally(new IOException("Worker 启动或管道失效"));
        try { failure.accept(reason); } catch (RuntimeException ignored) { }
        stopTree();
    }

    private void captureTree() {
        for (ProcessHandle child : process.descendants().toList()) {
            CapturedProcess captured = CapturedProcess.capture(child); descendants.putIfAbsent(captured.key(), captured);
        }
    }

    private void stopTree() {
        try { captureTree(); } catch (RuntimeException ignored) { }
        descendants.values().forEach(CapturedProcess::kill);
        if (process.isAlive()) process.destroyForcibly();
    }

    /**
     * 先结束私有输入并等待真实退出；超时只请求杀死原句柄，远端效果仍需核对。
     */
    @Override public void close() {
        if (!closing.compareAndSet(false, true)) return;
        ready = false; monitor.shutdownNow();
        try { captureTree(); } catch (RuntimeException ignored) { }
        Thread.ofVirtual().start(() -> { try { rpc.closeOutput(); } catch (IOException ignored) { } });
        try { if (!process.waitFor(5, TimeUnit.SECONDS)) stopTree(); }
        catch (InterruptedException ignored) { Thread.currentThread().interrupt(); stopTree(); }
        descendants.values().forEach(CapturedProcess::kill);
        try { process.waitFor(3, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
        if (!process.isAlive()) rpc.close();
    }

    private record CapturedProcess(long pid, String startedAt, ProcessHandle handle) {
        static CapturedProcess capture(ProcessHandle handle) { return new CapturedProcess(handle.pid(), handle.info().startInstant().map(Object::toString).orElse(""), handle); }
        String key() { return pid + "@" + startedAt; }
        boolean running() {
            if (handle == null || !handle.isAlive()) return false;
            ProcessHandle actual = ProcessHandle.of(pid).orElse(null);
            return actual != null && actual.isAlive() && (startedAt.isBlank() || startedAt.equals(actual.info().startInstant().map(Object::toString).orElse("")));
        }
        void kill() {
            if (handle != null && !startedAt.isBlank() && running()) { try { handle.destroyForcibly(); } catch (RuntimeException ignored) { } }
        }
        Map<String, Object> facts() { return obj("pid", pid, "processStartedAt", startedAt); }
    }

    private List<String> command(Path nio) throws IOException {
        boolean windows = System.getProperty("os.name", "").startsWith("Windows"); Path java = Path.of(System.getProperty("java.home"), "bin", windows ? "javaw.exe" : "java");
        if (!Files.isRegularFile(java)) java = Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java");
        String classpath = absoluteClasspath(System.getProperty("java.class.path"));
        List<String> command = new ArrayList<>(List.of(java.toString(), "-Dfile.encoding=UTF-8", "-Djdk.net.unixdomain.tmpdir=" + nio));
        if (!classpath.contains(File.pathSeparator) && bootJar(Path.of(classpath))) command.addAll(List.of("-jar", classpath, "--workbench-internal-worker"));
        else command.addAll(List.of("-cp", classpath, WorkerProcessMain.class.getName()));
        return command;
    }

    /**
     * Windows 短路径别名上的 Unix socket 连接可能失败，长目录让 JDK 使用本机回退管道。
     */
    private Path nioDirectory() throws IOException {
        Path nio = Files.createDirectories(dataDirectory.resolve("nio-tmp")).toRealPath();
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            while (nio.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8).length < 120) nio = nio.resolve("workbench-jdk-loopback");
            nio = Files.createDirectories(nio).toRealPath();
        }
        return nio;
    }

    private static String absoluteClasspath(String classpath) {
        return String.join(File.pathSeparator, java.util.Arrays.stream(classpath.split(java.util.regex.Pattern.quote(File.pathSeparator)))
            .map(entry -> Path.of(entry).toAbsolutePath().normalize().toString()).toList());
    }

    private static boolean bootJar(Path path) throws IOException {
        if (!Files.isRegularFile(path) || !path.toString().endsWith(".jar")) return false;
        try (ZipFile archive = new ZipFile(path.toFile())) { return archive.getEntry("BOOT-INF/classes/") != null; }
    }

    private static long number(Map<String, Object> value, String field) {
        Object number = value.get(field); if (!(number instanceof Integer || number instanceof Long)) throw new IllegalArgumentException("Worker 整数回执无效");
        return ((Number) number).longValue();
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> objectResult(Object value) { if (!(value instanceof Map<?, ?>)) throw new IllegalStateException("Worker 对象回执缺失"); return (Map<String, Object>) value; }
}
