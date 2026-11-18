package dev.agentworkbench.runtime;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ManagedProcessLedger;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static dev.agentworkbench.Json.*;

/**
 * 原 shell、已观察子进程、输出读取和原生清理各自收尾，整次调用才返回。
 */
public final class NativePtySession {
    private static final Object START_GATE = new Object();
    private static final int MAX_PENDING_PUBLICATIONS = 64;
    private static final int MAX_UNCOMMITTED_PREFIX_BYTES = 65_536;
    private final PtyProcess process;
    private final long originalPid;
    private final ProcessHandle original;
    private final String birth;
    private final Thread nativeWaiter;
    private final NativePtyBundle bundle;
    private final Path workspace;
    private final String workspaceFileKey;
    private final InputStream outputInput;
    private final Consumer<Map<String, Object>> observations;
    private final Map<String, Map<String, Object>> children = new ConcurrentHashMap<>();
    private final Map<Long, String> childBirths = new ConcurrentHashMap<>();
    private final ReentrantLock inputGate = new ReentrantLock();
    private final AtomicBoolean stopRequested = new AtomicBoolean();
    private final AtomicBoolean executing = new AtomicBoolean();
    private final AtomicReference<Throwable> readerFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> storageFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> endReceiptFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> streamsCloseFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> workspaceIdentityFailure = new AtomicReference<>();
    private final AtomicReference<Throwable> publicationFailure = new AtomicReference<>();
    private final ArrayBlockingQueue<Map<String, Object>> pendingPublications = new ArrayBlockingQueue<>(MAX_PENDING_PUBLICATIONS);
    private final AtomicBoolean readerCompleted = new AtomicBoolean();
    private final AtomicLong publishedBytes = new AtomicLong();
    private final AtomicLong publishedSegments = new AtomicLong();
    private final AtomicLong observedOutputBytes = new AtomicLong();
    private final AtomicLong savedOutputBytes = new AtomicLong();
    private final AtomicLong uncommittedOutputBytes = new AtomicLong();
    private final ByteArrayOutputStream uncommittedPrefix = new ByteArrayOutputStream(MAX_UNCOMMITTED_PREFIX_BYTES);
    private final long observationStartedNanos = System.nanoTime();
    private final List<Map<String, Object>> readerTimeline = java.util.Collections.synchronizedList(new java.util.ArrayList<>());
    private final AtomicLong readerTimelineEvents = new AtomicLong();
    private final Map<String, Object> started;
    private volatile boolean enumerationFailed;
    private volatile boolean streamsClosed;
    private volatile boolean nativeEofObserved;
    private volatile Thread reader;
    private volatile Thread publisher;

    private NativePtySession(PtyProcess process, ProcessHandle original, String birth, Thread nativeWaiter, NativePtyBundle bundle, Path workspace, String workspaceFileKey,
            InputStream outputInput, Consumer<Map<String, Object>> observations, Map<String, Object> started) {
        this.process = process; this.original = original; this.birth = birth; this.nativeWaiter = nativeWaiter;
        this.originalPid = process.pid();
        this.workspace = workspace; this.workspaceFileKey = workspaceFileKey;
        this.bundle = bundle; this.observations = observations; this.started = started;
        this.outputInput = java.util.Objects.requireNonNull(outputInput);
    }

    /**
     * 固定系统方案不接受客户端命令、环境或另一目录；字节库核验先于原生启动。
     */
    public static NativePtySession open(Path data, Path workspace, String recipe, int columns, int rows, Consumer<Map<String, Object>> observations,
            TerminalOutputArchive.Writer output, Consumer<Map<String, Object>> publish) throws IOException {
        return open(data, workspace, recipe, columns, rows, observations, output, publish, PtyProcessBuilder::start);
    }

    @FunctionalInterface
    interface PtyStarter {
        PtyProcess start(PtyProcessBuilder builder) throws IOException;
    }

    static NativePtySession open(Path data, Path workspace, String recipe, int columns, int rows, Consumer<Map<String, Object>> observations,
            TerminalOutputArchive.Writer output, Consumer<Map<String, Object>> publish, PtyStarter starter) throws IOException {
        java.util.Objects.requireNonNull(output); java.util.Objects.requireNonNull(publish);
        java.util.Objects.requireNonNull(starter);
        dimensions(columns, rows);
        if (!workspace.isAbsolute() || !Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || !workspace.toRealPath().equals(workspace)) throw new IOException("终端现场不是原规范目录");
        String workspaceFileKey = WorkspaceFileIdentity.directoryKey(workspace);
        boolean windows = System.getProperty("os.name", "").startsWith("Windows");
        if (!(windows ? "SYSTEM_CMD" : "SYSTEM_SH").equals(recipe)) throw new ApiException(400, "当前系统不支持所选终端方案");
        Path executable = windows ? Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toRealPath() : Path.of("/bin/sh").toRealPath();
        String[] command = windows ? new String[] {executable.toString(), "/D", "/Q", "/K", "chcp 65001>nul"} : new String[] {executable.toString(), "-i"};
        NativePtyBundle bundle = NativePtyBundle.current(data); PtyProcess process;
        Map<String, Object> facts = obj("pid", 0L, "processStartedAt", "", "backend", "", "recipe", recipe,
            "workingDirectory", workspace.toString(), "workingDirectoryFileKey", workspaceFileKey, "columns", columns, "rows", rows,
            "nativeBundle", bundle.facts(), "createdAt", now(), "outputReadOwnership", windows ? "DUPLICATED_NONINHERITABLE_ORIGINAL_PIPE_HANDLE" : "ORIGINAL_OWNED_UNIX_STREAM");
        Thread waiter = null; Throwable waiterFailure = null;
        synchronized (START_GATE) {
            Set<Thread> before = Thread.getAllStackTraces().keySet();
            String expected = windows ? "WinConPtyProcess WaitFor " + String.join(" ", command) : "PtyProcess Reaper for " + Arrays.toString(command);
            try {
                process = starter.start(new PtyProcessBuilder(command).setDirectory(workspace.toString()).setEnvironment(environment(windows))
                    .setUseWinConPty(windows).setInitialColumns(columns).setInitialRows(rows).setRedirectErrorStream(true)
                    .setUnixOpenTtyToPreserveOutputAfterTermination(true));
                if (process == null) throw new IOException("发布库未交还原终端进程对象");
            } catch (Throwable failure) { throw constructionFailure(output, facts, failure); }
            try { waiter = Thread.getAllStackTraces().keySet().stream().filter(thread -> !before.contains(thread) && thread.getName().equals(expected)).findFirst().orElse(null); }
            catch (Throwable failure) { waiterFailure = failure; }
        }
        NativePtySession session = null;
        ProcessHandle handle = null; String birth = "";
        try {
            facts.putAll(obj("pid", process.pid(), "backend", process.getClass().getName(),
                "cleanupKind", waiter == null ? windows ? "UNCONFIRMED_NATIVE_THREAD" : "OWNED_UNIX_STREAMS" : "ORIGINAL_NATIVE_THREAD",
                "nativeCleanupThreadId", waiter == null ? 0L : waiter.threadId()));
            if (waiterFailure != null) throw waiterFailure;
            handle = ProcessHandle.of(process.pid()).orElseThrow(() -> new IOException("原终端进程身份缺失"));
            birth = handle.info().startInstant().orElseThrow(() -> new IOException("原终端进程出生时间缺失")).toString();
            facts.put("processStartedAt", birth);
            InputStream input = windows ? OwnedConPtyInput.open(process.getInputStream()) : process.getInputStream();
            session = new NativePtySession(process, handle, birth, waiter, bundle, workspace, workspaceFileKey, input, observations, facts);
            if (windows && (!facts.get("backend").equals("com.pty4j.windows.conpty.WinConPtyProcess") || waiter == null))
                throw new IOException("原终端未取得已验证 ConPTY 后端和清理线程");
            if (!windows && !facts.get("backend").equals("com.pty4j.unix.UnixPtyProcess")) throw new IOException("原终端后端未获支持");
            observations.accept(observation(handle, ProcessHandle.current()));
            session.requireWorkspaceIdentity();
            return session;
        } catch (Throwable failure) {
            if (session == null) {
                facts.put("outputReadOwnership", "ORIGINAL_STREAM_FALLBACK_AFTER_STARTUP_FAILURE");
                session = new NativePtySession(process, handle, birth, waiter, bundle, workspace, workspaceFileKey, process.getInputStream(), observations, facts);
            }
            session.requestStop();
            Map<String, Object> receipt = session.execute(() -> true, output, publish);
            receipt.putAll(obj("status", "UNKNOWN", "startupAcknowledged", false, "nativeObjectReturned", true,
                "startupFailure", failureFacts(failure), "startupCleanupBeforeReturn", true));
            throw new StartupFailure(failure, receipt);
        }
    }

    /**
     * 已创建原生进程的启动失败保留原输出和完整收尾回执，调用者不得重建原 shell。
     */
    public static final class StartupFailure extends IOException {
        private final Map<String, Object> receipt;
        private StartupFailure(Throwable failure, Map<String, Object> receipt) {
            super(bool(receipt, "nativeObjectReturned", false) ? "原生启动后身份或来源核验失败，原输出与收尾回执保留待核对" : "发布库未交还原终端对象，原进程和输出仍需核对", failure); this.receipt = receipt;
        }
        public Map<String, Object> receipt() {
            return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(receipt, new com.fasterxml.jackson.core.type.TypeReference<>() { });
        }
    }

    /**
     * 发布库未返回对象时只保存已知字节与未知边界，不推断原生身份、EOF 或停止成功。
     */
    private static StartupFailure constructionFailure(TerminalOutputArchive.Writer output, Map<String, Object> facts, Throwable failure) {
        Map<String, Object> end;
        try { end = output.finish(false); }
        catch (Throwable closeFailure) {
            failure.addSuppressed(closeFailure);
            end = obj("complete", false, "endReceiptMissing", true);
        }
        Map<String, Object> receipt = obj("status", "UNKNOWN", "exitCode", -1, "nativeProcess", facts, "outputEnd", end,
            "startupAcknowledged", false, "nativeObjectReturned", false, "startupCleanupBeforeReturn", false, "startupFailure", failureFacts(failure),
            "originalProcessStopped", false, "ownedNativeProcessStopped", false, "observedChildrenStopped", false,
            "outputReaderCompleted", false, "outputPublisherCompleted", false, "nativeCleanupThreadCompleted", false, "ownedStreamsClosed", false,
            "nativeEofObserved", false, "outputPublicationGap", true, "processCoverage", "NATIVE_OBJECT_NOT_RETURNED; ORIGINAL_PROCESS_AND_NATIVE_RESOURCES_UNCONFIRMED",
            "requiresOriginalWorkerAndNativeProcessReconciliation", true, "endedAt", now());
        return new StartupFailure(failure, receipt);
    }

    public Map<String, Object> started() { return new com.fasterxml.jackson.databind.ObjectMapper().convertValue(started, new com.fasterxml.jackson.core.type.TypeReference<>() { }); }

    public void input(byte[] bytes) throws IOException {
        if (bytes.length < 1 || bytes.length > 16_384) throw new ApiException(413, "终端输入长度越过边界");
        byte[] originalBytes = bytes.clone(); inputGate.lock();
        try { requireLive(); process.getOutputStream().write(originalBytes); process.getOutputStream().flush(); }
        finally { inputGate.unlock(); }
    }

    public Map<String, Object> resize(int columns, int rows) throws IOException {
        dimensions(columns, rows); inputGate.lock();
        try {
            requireLive(); process.setWinSize(new WinSize(columns, rows)); WinSize actual = process.getWinSize();
            return obj("columns", actual.getColumns(), "rows", actual.getRows());
        } finally { inputGate.unlock(); }
    }

    /**
     * 停止不等待可能堵塞的原输入写入；只对原出生身份请求停止。
     */
    public void requestStop() {
        stopRequested.set(true); observeChildren(); stopObserved();
    }

    /**
     * 单一平台线程保存原始输出，发布失联后继续留存可保存的尾部并停止原进程。
     */
    public Map<String, Object> execute(BooleanSupplier cancelled, TerminalOutputArchive.Writer output, Consumer<Map<String, Object>> publish) {
        if (!executing.compareAndSet(false, true)) throw new ApiException(409, "原终端调用只能观察一次完整生命周期");
        reader = Thread.ofPlatform().name("workbench-terminal-output-" + originalPid).unstarted(() -> readOutput(output));
        publisher = Thread.ofPlatform().name("workbench-terminal-publisher-" + originalPid).unstarted(() -> publishOutput(publish));
        publisher.start(); reader.start();
        boolean interrupted = false, cancelledObserved = false;
        try {
            while (true) {
                observeChildren();
                try { requireWorkspaceIdentity(); }
                catch (IOException exception) { requestStop(); }
                if (cancelled.getAsBoolean()) { cancelledObserved = true; requestStop(); }
                if (readerFailure.get() != null || storageFailure.get() != null || publicationFailure.get() != null || stopRequested.get()) stopObserved();
                boolean rootStopped = rootStopped();
                if (rootStopped && !childrenStopped()) stopChildren();
                if (rootStopped && childrenStopped() && !reader.isAlive() && !publisher.isAlive() && !inputGate.isLocked() && (nativeWaiter == null || !nativeWaiter.isAlive())) break;
                try { TimeUnit.MILLISECONDS.sleep(40); }
                catch (InterruptedException exception) { interrupted = true; cancelledObserved = true; requestStop(); }
            }
            try {
                process.getOutputStream().close(); process.getErrorStream().close();
                streamsClosed = !(outputInput instanceof OwnedConPtyInput owned) || owned.closed();
            } catch (IOException exception) { streamsCloseFailure.compareAndSet(null, exception); }
            int exit = process.exitValue();
            boolean outputComplete = nativeEofObserved && readerFailure.get() == null && storageFailure.get() == null && publicationFailure.get() == null;
            Map<String, Object> end;
            try { end = output.finish(outputComplete); }
            catch (RuntimeException exception) { endReceiptFailure.compareAndSet(null, exception); outputComplete = false; end = obj("complete", false, "endReceiptMissing", true); }
            String status = !outputComplete || !streamsClosed || enumerationFailed || workspaceIdentityFailure.get() != null || birth.isBlank() ? "UNKNOWN" : cancelledObserved || stopRequested.get() ? "CANCELLED" : "EXITED";
            return obj("status", status, "exitCode", exit, "nativeProcess", started(), "outputEnd", end, "outputReaderCompleted", !reader.isAlive(),
                "originalProcessStopped", !birth.isBlank() && rootStopped(), "ownedNativeProcessStopped", !process.isAlive(),
                "observedChildrenStopped", childrenStopped(), "observedChildren", children.values().stream().map(LinkedHashMap::new).toList(),
                "processCoverage", "OBSERVED_ONLY", "processEnumerationFailed", enumerationFailed,
                "nativeCleanupThreadCompleted", nativeWaiter == null ? !System.getProperty("os.name", "").startsWith("Windows") : !nativeWaiter.isAlive(),
                "ownedStreamsClosed", streamsClosed, "stopRequested", stopRequested.get(), "cancelledObserved", cancelledObserved,
                "readerFailureType", type(readerFailure.get()), "publicationFailureType", type(publicationFailure.get()),
                "outputStorageFailureType", type(storageFailure.get()), "outputStorageFailure", failureFacts(storageFailure.get()),
                "outputEndReceiptFailureType", type(endReceiptFailure.get()), "outputEndReceiptFailure", failureFacts(endReceiptFailure.get()),
                "streamsCloseFailure", failureFacts(streamsCloseFailure.get()),
                "workingDirectoryIdentityVerified", workspaceIdentityFailure.get() == null, "workingDirectoryIdentityFailure", failureFacts(workspaceIdentityFailure.get()),
                "nativeEofObserved", nativeEofObserved, "observedOutputBytes", observedOutputBytes.get(), "savedOutputBytes", savedOutputBytes.get(),
                "uncommittedOutput", uncommittedOutput(),
                "outputPublisherCompleted", !publisher.isAlive(), "publishedOutputBytes", publishedBytes.get(), "publishedOutputSegments", publishedSegments.get(),
                "publicationQueueLimit", MAX_PENDING_PUBLICATIONS, "outputPublicationGap", publicationFailure.get() != null,
                "readerTimeline", List.copyOf(readerTimeline), "readerTimelineEvents", readerTimelineEvents.get(), "readerTimelineLimit", 128,
                "readerFailure", failureFacts(readerFailure.get()), "publicationFailure", failureFacts(publicationFailure.get()), "endedAt", now());
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * 原读取不等待核心发布回调；有界引用队列溢出后继续保留原尾部并停止输入。
     */
    private void readOutput(TerminalOutputArchive.Writer output) {
        try (InputStream input = outputInput) {
            byte[] buffer = new byte[TerminalOutputArchive.MAX_SEGMENT_BYTES]; int count;
            while (true) {
                long reading = System.nanoTime(); readerTiming("READ_START", reading, 0);
                count = input.read(buffer); readerTiming("READ_RETURN", reading, count);
                if (count < 0) { nativeEofObserved = true; break; }
                if (count == 0) continue;
                observedOutputBytes.addAndGet(count);
                if (storageFailure.get() != null) { retainUncommitted(buffer, count); continue; }
                long saving = System.nanoTime(); readerTiming("RAW_SAVE_START", saving, count);
                Map<String, Object> descriptor;
                try { descriptor = output.append(Arrays.copyOf(buffer, count)); }
                catch (RuntimeException failure) {
                    storageFailure.compareAndSet(null, failure); readerTiming("STORAGE_FAILURE", saving, count);
                    retainUncommitted(buffer, count); requestStop(); continue;
                }
                savedOutputBytes.set(((Number) descriptor.get("next")).longValue());
                readerTiming("RAW_SAVED", saving, count);
                if (publicationFailure.get() == null && !pendingPublications.offer(java.util.Collections.unmodifiableMap(new LinkedHashMap<>(descriptor)))) {
                    publicationFailure.compareAndSet(null, new IllegalStateException("原终端输出发布队列越过边界，已保存字节保留待核对")); requestStop();
                }
            }
        } catch (Throwable failure) { readerTiming("READER_FAILURE", System.nanoTime(), -1); readerFailure.compareAndSet(null, failure); requestStop(); }
        finally { readerCompleted.set(true); }
    }

    /**
     * 发布按原游标顺序逐项确认，读取、全部发布和原生资源收尾后才封存完整结束。
     */
    private void publishOutput(Consumer<Map<String, Object>> publish) {
        boolean interrupted = false;
        try {
            while (!readerCompleted.get() || !pendingPublications.isEmpty()) {
                if (publicationFailure.get() != null) { pendingPublications.clear(); return; }
                Map<String, Object> descriptor = pendingPublications.poll(40, TimeUnit.MILLISECONDS);
                if (descriptor == null) continue;
                publish.accept(descriptor);
                publishedBytes.set(((Number) descriptor.get("next")).longValue()); publishedSegments.incrementAndGet();
            }
        } catch (Throwable failure) {
            interrupted = failure instanceof InterruptedException;
            publicationFailure.compareAndSet(null, failure); requestStop(); pendingPublications.clear();
        } finally { if (interrupted) Thread.currentThread().interrupt(); }
    }

    /**
     * 有界时间线仅保留读取和落盘耗时，不保存终端输入或输出内容。
     */
    private void readerTiming(String stage, long started, int bytes) {
        long timestamp = System.nanoTime(); readerTimelineEvents.incrementAndGet();
        synchronized (readerTimeline) {
            if (readerTimeline.size() == 128) readerTimeline.removeFirst();
            readerTimeline.add(obj("stage", stage, "elapsedNanos", timestamp - observationStartedNanos, "durationNanos", timestamp - started, "bytes", bytes));
        }
    }

    private void requireLive() throws IOException {
        requireWorkspaceIdentity();
        if (stopRequested.get() || readerFailure.get() != null || storageFailure.get() != null || publicationFailure.get() != null || !process.isAlive()
                || rootStopped()) throw new IOException("原终端已经停止或需核对，未写入新输入");
        bundle.verify();
    }

    /**
     * 每次输入、尺寸修改和生命周期观测都核对原目录文件编号，替换后停止原出生。
     */
    private void requireWorkspaceIdentity() throws IOException {
        Throwable originalFailure = workspaceIdentityFailure.get();
        if (originalFailure != null) throw new IOException("原终端现场目录身份已经失去确认", originalFailure);
        try {
            if (!workspaceFileKey.equals(WorkspaceFileIdentity.directoryKey(workspace))) throw new IOException("原终端现场目录文件编号已经变化");
        } catch (IOException | RuntimeException failure) {
            workspaceIdentityFailure.compareAndSet(null, failure); requestStop();
            throw new IOException("原终端现场目录无法核验，未写入新输入", failure);
        }
    }

    /**
     * 落盘失败后继续排空原管道，内存仅保留有界未提交前缀及完整已观察字节计数。
     */
    private void retainUncommitted(byte[] bytes, int length) {
        uncommittedOutputBytes.addAndGet(length);
        int retained = Math.min(length, MAX_UNCOMMITTED_PREFIX_BYTES - uncommittedPrefix.size());
        if (retained > 0) uncommittedPrefix.write(bytes, 0, retained);
    }

    private Map<String, Object> uncommittedOutput() {
        byte[] prefix = uncommittedPrefix.toByteArray();
        String digest;
        try { digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(prefix)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        return obj("bytes", uncommittedOutputBytes.get(), "startsAfterSavedBytes", savedOutputBytes.get(), "retainedPrefixBytes", prefix.length,
            "retainedPrefixSha256", digest, "retainedPrefixBase64", Base64.getEncoder().encodeToString(prefix), "prefixLimit", MAX_UNCOMMITTED_PREFIX_BYTES,
            "truncated", uncommittedOutputBytes.get() > prefix.length, "prefixDurability", "ORIGINAL_NATIVE_RESULT_IF_ACKNOWLEDGED", "completeDescriptorCommitted", false);
    }

    private void observeChildren() {
        if (original == null || birth.isBlank()) { enumerationFailed = true; return; }
        try {
            for (ProcessHandle child : ManagedProcessLedger.originalDescendants(original, birth)) {
                String start = child.info().startInstant().map(Object::toString).orElse("");
                Map<String, Object> facts = obj("pid", child.pid(), "processStartedAt", start, "observedFromPid", original.pid(), "observedFromStartedAt", birth);
                if (ignoreUnverifiableDuplicate(child.pid(), start, childBirths)) continue;
                if (!start.isBlank()) children.remove(child.pid() + ":");
                if (children.putIfAbsent(child.pid() + ":" + start, facts) == null) observations.accept(new LinkedHashMap<>(facts));
                if (start.isBlank()) enumerationFailed = true;
            }
        } catch (RuntimeException exception) { enumerationFailed = true; }
    }

    /**
     * 已记录可靠出生身份的 PID 忽略后续空值观察；首次未知身份仍保留失败边界。
     */
    static boolean ignoreUnverifiableDuplicate(long pid, String birth, Map<Long, String> observedBirths) {
        if (birth.isBlank()) return observedBirths.containsKey(pid);
        observedBirths.put(pid, birth);
        return false;
    }

    private void stopObserved() {
        stopChildren();
        if (!rootStopped()) {
            if (birth.isBlank()) process.destroy();
            else ManagedProcessLedger.stopObservedProcess(originalPid, birth);
        }
    }

    private boolean rootStopped() {
        return birth.isBlank() ? !process.isAlive() : ManagedProcessLedger.observedProcessStopped(originalPid, birth);
    }

    private void stopChildren() {
        for (Map<String, Object> child : children.values()) ManagedProcessLedger.stopObservedProcess(((Number) child.get("pid")).longValue(), str(child, "processStartedAt"));
    }

    private boolean childrenStopped() {
        return ManagedProcessLedger.inspectProcesses(() -> children.values().stream()
            .allMatch(child -> ManagedProcessLedger.observedProcessStopped(((Number) child.get("pid")).longValue(), str(child, "processStartedAt"))));
    }

    private static Map<String, Object> observation(ProcessHandle handle, ProcessHandle parent) {
        return obj("pid", handle.pid(), "processStartedAt", handle.info().startInstant().map(Object::toString).orElse(""),
            "observedFromPid", parent.pid(), "observedFromStartedAt", parent.info().startInstant().map(Object::toString).orElse(""));
    }

    private static Map<String, String> environment(boolean windows) {
        Map<String, String> environment = new LinkedHashMap<>();
        for (String key : List.of("SystemRoot", "SystemDrive", "ComSpec", "PATH", "PATHEXT", "TEMP", "TMP", "USERPROFILE", "APPDATA", "LOCALAPPDATA", "HOMEDRIVE", "HOMEPATH", "HOME", "USER", "LOGNAME", "TMPDIR")) {
            String value = System.getenv(key); if (value != null) environment.put(key, value);
        }
        environment.put("TERM", "xterm-256color"); environment.put("COLORTERM", "truecolor");
        if (!windows) {
            String locale = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("mac") ? "en_US.UTF-8" : "C.UTF-8";
            environment.put("LANG", locale); environment.put("LC_ALL", locale);
        }
        return environment;
    }

    private static void dimensions(int columns, int rows) { if (columns < 2 || columns > 500 || rows < 1 || rows > 300) throw new ApiException(400, "终端尺寸越过边界"); }
    private static String type(Throwable failure) { return failure == null ? "" : failure.getClass().getSimpleName(); }

    /**
     * 保留原读取或发布异常的有限诊断，不用随后成功覆盖原失败事实。
     */
    private static Map<String, Object> failureFacts(Throwable failure) {
        if (failure == null) return obj();
        String message = java.util.Objects.toString(failure.getMessage(), "");
        return obj("type", failure.getClass().getName(), "message", message.substring(0, Math.min(message.length(), 2400)),
            "stack", Arrays.stream(failure.getStackTrace()).limit(32).map(Object::toString).toList());
    }
}
