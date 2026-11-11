package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 实际核心、独立 Worker、Git、HTTP 与 Node 核对根保护和数据库故障窗口。
 */
class WorkspaceWriteEngineTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path data = directory.resolve("data"), source = Files.createDirectories(directory.resolve("中文 源仓库")).toRealPath();
        final AtomicInteger httpCalls = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
        final java.util.concurrent.ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final ReleaseGate releaseGate;
        final String taskId;
        final byte[] sourceIndex, sourceConfig;
        final String sourceHead;
        ExecutionStore store;
        ProcessWorkspaceRuntime runtime;
        WorkbenchCatalog catalog;
        WorkbenchEngine engine;
        ResourceManager resources;
        volatile boolean blocked;

        Fixture(String strategy, boolean blocked) throws Exception { this(strategy, blocked, null); }

        Fixture(String strategy, boolean blocked, ReleaseGate releaseGate) throws Exception {
            this.blocked = blocked; this.releaseGate = releaseGate;
            Files.writeString(source.resolve("app.txt"), "original source\n");
            Files.writeString(source.resolve("verify.mjs"), "import {readFileSync} from 'node:fs';\n"
                + "if(readFileSync('app.txt','utf8')!=='original source\\n')process.exit(2);\n"
                + "console.log('ORIGINAL_NODE_VERIFICATION '+JSON.stringify({pid:process.pid,parentPid:process.ppid}));\n");
            git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Workspace Fixture"); git(source, "config", "user.email", "workspace@example.invalid");
            git(source, "add", "."); git(source, "commit", "-m", "original fixture");
            sourceIndex = Files.readAllBytes(source.resolve(".git/index")); sourceConfig = Files.readAllBytes(source.resolve(".git/config")); sourceHead = git(source, "rev-parse", "HEAD").trim();
            server.setExecutor(httpThreads); server.createContext("/", exchange -> {
                try {
                    Map<String, Object> request = mapper.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
                    int round = httpCalls.incrementAndGet(); entered.countDown();
                    if (this.blocked && round == 1 && !release.await(90, TimeUnit.SECONDS)) throw new IllegalStateException("original model fixture gate timed out");
                    String prompt = objects(request.get("messages")).stream().map(message -> str(message, "content")).reduce("", (left, right) -> left + right);
                    String content = prompt.contains("kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "actual original model response";
                    byte[] response = mapper.writeValueAsBytes(obj("id", "original-response-" + round, "model", "fixture", "choices", List.of(obj("message", obj("role", "assistant", "content", content), "finish_reason", "stop")), "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } catch (Throwable failure) { errors.add(failure.toString()); }
                finally { exchange.close(); }
            }); server.start(); openCore();
            Map<String, Object> project = catalog.importProject(obj("name", "original write source", "path", source.toString()));
            Map<String, Object> route = catalog.saveRoute(obj("name", "local HTTP fixture", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "enabled", true));
            taskId = str(catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "Original root workspace writer", "goal", "Keep original root ownership and receipts", "mode", "LEAD",
                "workspaceSelection", obj("strategy", strategy, "ref", "HEAD"), "contract", obj("scope", List.of("**"), "acceptance", List.of("preserved root receipts"), "commands", List.of("node verify.mjs")),
                "budget", obj("maxCalls", 12, "reserveCalls", 4))), "id");
        }

        void openCore() throws Exception {
            store = new ExecutionStore(mapper, data.toString()); runtime = new ProcessWorkspaceRuntime(mapper, data.toString()) {
                @Override public Map<String, Object> releaseWorkspaceWrite(Map<String, Object> identity, String note) {
                    if (releaseGate != null && note.isBlank()) releaseGate.enter(store, identity);
                    return super.releaseWorkspaceWrite(identity, note);
                }
            };
            catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults(); resources = new ResourceManager();
            engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper);
        }

        Map<String, Object> task() { return store.get("task", taskId); }
        Map<String, Object> lease() { return store.get("workspaceWriteLease", str(task(), "workspaceWriteLeaseId")); }
        Path manifest() { return data.resolve("workspaces").resolve(taskId).resolve("writer.json"); }
        Path selection() { return manifest().resolveSibling("selection.json"); }

        void fault(String state) throws Exception {
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = database.createStatement()) {
                sql.execute("CREATE TRIGGER reject_write_receipt BEFORE UPDATE ON entities WHEN new.kind='workspaceWriteLease' AND json_extract(new.body,'$.state')='" + state + "' BEGIN SELECT RAISE(ABORT,'original workspace receipt fault'); END");
            }
        }

        void reopenAfterFault() throws Exception {
            closeCore();
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = database.createStatement()) { sql.execute("DROP TRIGGER reject_write_receipt"); }
            openCore(); engine.recover();
        }

        void closeCore() throws Exception {
            if (engine == null) return;
            Map<String, Object> original = engine.workerIdentity();
            snapshot("before-core-close");
            try { engine.shutdown(); }
            finally { engine = null; store.close(); }
            await(() -> ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")), 8);
            if (releaseGate != null && releaseGate.evidence != null) Files.write(releaseGate.evidence.resolve("original-worker-stopped.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj("identity", original,
                "originalStopped", ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")), "observedAt", now())));
        }

        void reconcile() {
            for (Map<String, Object> session : objects(engine.processRecovery().get("sessions"))) {
                assertTrue(bool(session, "observedProcessesStopped", false));
                engine.processRecoveryAction(str(session, "id"), "reconcile", obj("processesStopped", true, "coverageChecked", true, "note", "original fixture JVM and unrecorded processes checked"));
            }
            engine.action(taskId, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "original source, READY owner and external effects checked"));
        }

        void settled() throws Exception { boundary(() -> "SUCCEEDED".equals(str(store.get("taskExecution", str(task(), "taskExecutionId")), "state"))); }

        void boundary(BooleanSupplier condition) throws Exception {
            await(() -> {
                if (condition.getAsBoolean()) return true;
                Map<String, Object> root = store.find("taskExecution", str(task(), "taskExecutionId"));
                if (root != null && root.containsKey("endedAt")) {
                    if (condition.getAsBoolean()) return true;
                    fail("original root ended before the expected boundary: " + task());
                }
                return false;
            }, 90);
        }

        void snapshot(String name) throws Exception {
            Map<String, Object> facts = obj("worker", engine.workerIdentity(), "healthy", store.healthy(), "httpCalls", httpCalls.get(), "modelErrors", errors);
            for (String kind : List.of("task", "workspaceWriteLease", "taskExecution", "dispatchAction", "operation", "modelRequest", "processSession")) facts.put(kind, store.list(kind));
            Files.write(directory.resolve(name + "-" + store.coreSessionId() + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(facts));
        }

        void sourceUnchanged() throws Exception {
            assertEquals(sourceHead, git(source, "rev-parse", "HEAD").trim()); assertEquals("main", git(source, "branch", "--show-current").trim());
            assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(sourceConfig, Files.readAllBytes(source.resolve(".git/config")));
            assertEquals("original source\n", Files.readString(source.resolve("app.txt"))); assertEquals("", git(source, "status", "--porcelain=v1", "-uall"));
        }

        Map<String, Object> archive(String name) throws Exception {
            WorkbenchController controller = new WorkbenchController(store, catalog, engine, runtime, resources, new PlanValidator(), mapper, null);
            byte[] bytes = controller.export(taskId).getBody(); assertNotNull(bytes); Files.write(directory.resolve(name + ".zip"), bytes);
            Map<String, Object> contents = new LinkedHashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) if (entry.getName().endsWith(".json")) contents.put(entry.getName(), mapper.readValue(zip.readAllBytes(), Object.class));
            }
            assertFalse(contents.toString().contains("claimDigest")); return contents;
        }

        @Override public void close() throws Exception {
            if (releaseGate != null) releaseGate.permit.countDown();
            release.countDown(); try { closeCore(); } finally { server.stop(0); httpThreads.shutdownNow(); }
        }
    }

    /**
     * 只延迟生产 native release；心跳、私有管道和数据库记录仍来自原 Worker。
     */
    private final class ReleaseGate {
        final String action;
        final CountDownLatch entered = new CountDownLatch(1), observed = new CountDownLatch(1), permit = new CountDownLatch(1);
        volatile boolean storeHeld;
        volatile long originalSequence, observedSequence;
        volatile Path evidence;

        ReleaseGate(String action) { this.action = action; }

        void enter(ExecutionStore store, Map<String, Object> identity) {
            try {
                storeHeld = Thread.holdsLock(store); originalSequence = sequence(store, identity); entered.countDown();
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(7);
                while (System.nanoTime() < deadline && (observedSequence = sequence(store, identity)) <= originalSequence) Thread.sleep(25);
                List<Map<String, Object>> readers = Thread.getAllStackTraces().entrySet().stream().filter(entry -> entry.getKey().getName().startsWith("workbench-worker-rpc-"))
                    .map(entry -> obj("threadId", entry.getKey().threadId(), "name", entry.getKey().getName(), "state", entry.getKey().getState().name(), "stack", java.util.Arrays.stream(entry.getValue()).map(Object::toString).toList())).toList();
                evidence = Files.createDirectories(Path.of("target", "workspace-write-release-gate-" + store.coreSessionId()).toAbsolutePath());
                byte[] facts = mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj("identity", identity, "storeMonitor", Integer.toHexString(System.identityHashCode(store)),
                    "storeMonitorHeld", storeHeld, "originalHeartbeatSequence", originalSequence, "observedHeartbeatSequence", observedSequence, "rootThreadId", Thread.currentThread().threadId(),
                    "rootStack", java.util.Arrays.stream(Thread.currentThread().getStackTrace()).map(Object::toString).toList(), "rpcReaders", readers));
                Files.write(directory.resolve("release-gate-" + action + ".json"), facts); Files.write(evidence.resolve("release-gate-" + action + ".json"), facts);
                observed.countDown();
                if (!permit.await(30, TimeUnit.SECONDS)) throw new IllegalStateException("original native release gate timed out");
            } catch (Exception exception) { throw new IllegalStateException("original release gate could not record its boundary", exception); }
        }

        private long sequence(ExecutionStore store, Map<String, Object> identity) { return ((Number) store.get("worker", str(identity, "workerId")).getOrDefault("lastHeartbeatSequence", 0)).longValue(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"cancel", "pause"})
    void realHeartbeatAdvancesDuringReleaseAndLateStopSettlesTheOriginalRoot(String action) throws Exception {
        ReleaseGate gate = new ReleaseGate(action);
        try (var fixture = new Fixture("CLONE", false, gate)) {
            fixture.engine.action(fixture.taskId, "approve", obj()); assertTrue(gate.entered.await(90, TimeUnit.SECONDS)); assertTrue(gate.observed.await(9, TimeUnit.SECONDS));
            assertFalse(gate.storeHeld, "native release must not wait while owning the global store monitor");
            assertTrue(gate.observedSequence > gate.originalSequence, "the original Worker heartbeat must reach its persisted sequence before native release");
            Map<String, Object> original = fixture.lease(); assertEquals("HELD", original.get("state")); assertFalse(fixture.store.get("taskExecution", str(original, "taskExecutionId")).containsKey("endedAt"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "accept", obj())).status());
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.archive("premature-release")).status());
            assertEquals(action.equals("cancel") ? "CANCELLING" : "PAUSING", fixture.engine.action(fixture.taskId, action, obj()).get("state"));
            assertFalse(lockAvailable(fixture.manifest().resolveSibling("writer.lock"), "late-stop-held-probe")); gate.permit.countDown();
            String finalState = action.equals("cancel") ? "CANCELLED" : "PAUSED";
            fixture.boundary(() -> finalState.equals(str(fixture.store.get("taskExecution", str(original, "taskExecutionId")), "state")));
            assertEquals(finalState, fixture.task().get("state")); assertEquals("RELEASED", fixture.lease().get("state")); assertTrue(bool(fixture.lease(), "protectionReleased", false));
            assertEquals(WorkspaceWriteLedger.identity(original), WorkspaceWriteLedger.identity(map(map(fixture.lease(), "release"), "identity")));
            assertTrue(lockAvailable(fixture.manifest().resolveSibling("writer.lock"), "late-stop-released-probe")); assertEquals(3, fixture.httpCalls.get()); assertEquals(3, num(map(fixture.task(), "budget"), "usedCalls", -1));
            Map<String, Object> contents = fixture.archive("released-after-" + action); assertEquals(finalState, objects(contents.get("task-executions.json")).getFirst().get("state"));
            Files.copy(directory.resolve("released-after-" + action + ".zip"), gate.evidence.resolve("released-after-" + action + ".zip"));
            Files.write(gate.evidence.resolve("settled-root.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj("task", fixture.task(), "writeLease", fixture.lease(),
                "execution", fixture.store.get("taskExecution", str(original, "taskExecutionId")), "worker", fixture.engine.workerIdentity(), "httpCalls", fixture.httpCalls.get(), "modelErrors", fixture.errors)));
            fixture.engine.requireExecutionSettled(fixture.taskId); assertTrue(fixture.runtime.available()); assertEquals(0, fixture.resources.activeModels()); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void actualHttpAndVerificationKeepTheOriginalJvmLockUntilRootReceipt(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy, true)) {
            fixture.engine.action(fixture.taskId, "approve", obj()); fixture.boundary(() -> fixture.entered.getCount() == 0);
            Map<String, Object> original = fixture.lease(), identity = WorkspaceWriteLedger.identity(original);
            assertEquals("HELD", original.get("state")); assertFalse(bool(original, "protectionReleased", true));
            assertNotEquals(ProcessHandle.current().pid(), ((Number) original.get("executorPid")).longValue());
            assertEquals(((Number) fixture.engine.workerIdentity().get("pid")).longValue(), ((Number) original.get("executorPid")).longValue()); assertEquals(identity, WorkspaceWriteLedger.identity(map(map(original, "grant"), "identity")));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.runtime.releaseWorkspaceWrite(identity, "attempted in-flight release")).status());
            assertFalse(lockAvailable(fixture.manifest().resolveSibling("writer.lock"), "held-probe"));
            Map<String, Object> request = fixture.store.list("modelRequest").getFirst(); assertEquals(original.get("id"), request.get("workspaceWriteLeaseId")); assertEquals("DISPATCHING", request.get("state"));
            fixture.release.countDown(); fixture.settled();
            Map<String, Object> released = fixture.lease(); assertEquals("RELEASED", released.get("state")); assertTrue(bool(released, "protectionReleased", false));
            assertEquals(identity, WorkspaceWriteLedger.identity(map(map(released, "release"), "identity"))); assertTrue(lockAvailable(fixture.manifest().resolveSibling("writer.lock"), "released-probe"));
            assertEquals(3, fixture.httpCalls.get()); assertEquals(3, num(map(fixture.task(), "budget"), "usedCalls", -1));
            assertTrue(fixture.store.list("evidence").stream().anyMatch(value -> str(value, "output").contains("ORIGINAL_NODE_VERIFICATION")), "real Node verification receipt must be preserved");
            for (String kind : List.of("operation", "workerLease", "agentRun", "modelRequest", "evidence")) {
                assertFalse(fixture.store.list(kind).isEmpty(), kind);
                for (Map<String, Object> record : fixture.store.list(kind)) assertEquals(original.get("id"), record.get("workspaceWriteLeaseId"), kind);
            }
            Map<String, Object> archive = fixture.archive("released-root"); assertEquals(1, objects(archive.get("workspace-write-leases.json")).size());
            assertTrue(bool(objects(archive.get("task-executions.json")).getFirst(), "workspaceWriteProtectionReleased", false));
            assertEquals(0, fixture.resources.activeModels()); fixture.sourceUnchanged(); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void realGrantedLockWithMissingSqliteAckRequiresReconciliationBeforeANewRoot(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy, false)) {
            fixture.fault("HELD"); fixture.engine.action(fixture.taskId, "approve", obj()); fixture.boundary(() -> !fixture.store.healthy());
            Map<String, Object> original = fixture.lease(), savedReady = read(fixture.selection());
            assertEquals("PREPARED", original.get("state")); assertTrue(map(original, "grant").isEmpty()); assertEquals("HELD", read(fixture.manifest()).get("state")); assertEquals("READY", savedReady.get("state"));
            assertEquals("DISPATCHED", fixture.store.list("operation").getFirst().get("state")); assertEquals("ACTIVE", fixture.store.list("workerLease").getFirst().get("state"));
            assertEquals(0, fixture.httpCalls.get()); assertEquals(0, num(map(fixture.task(), "budget"), "usedCalls", -1));
            fixture.reopenAfterFault(); assertEquals("UNKNOWN", fixture.task().get("state")); assertEquals("UNKNOWN", fixture.lease().get("state"));
            assertEquals(original.get("id"), fixture.lease().get("id")); assertEquals("HELD", read(fixture.manifest()).get("state"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "resume", obj())).status()); assertEquals(0, fixture.httpCalls.get());
            assertEquals("UNKNOWN", objects(fixture.archive("missing-grant-ack").get("workspace-write-leases.json")).getFirst().get("state"));
            fixture.reconcile(); Map<String, Object> reconciled = fixture.lease(); assertEquals("UNKNOWN", reconciled.get("state")); assertTrue(bool(reconciled, "protectionReleased", false));
            fixture.engine.action(fixture.taskId, "resume", obj()); fixture.settled();
            assertNotEquals(original.get("id"), fixture.lease().get("id")); assertEquals(2, num(fixture.lease(), "attempt", 0)); assertEquals(3, fixture.httpCalls.get());
            assertEquals(savedReady, read(fixture.selection())); assertTrue(bool(fixture.task(), "recoveredPreparation", false));
            assertOldAndNewRoot(fixture, original, 0, "grant-ack-recovered"); fixture.sourceUnchanged(); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void realReleasedLockWithMissingSqliteReceiptDoesNotReplayAnyOldModel(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy, false)) {
            fixture.fault("RELEASED"); fixture.engine.action(fixture.taskId, "approve", obj()); fixture.boundary(() -> !fixture.store.healthy());
            Map<String, Object> original = fixture.lease(), budget = new LinkedHashMap<>(map(fixture.task(), "budget"));
            assertEquals("HELD", original.get("state")); assertFalse(bool(original, "protectionReleased", true)); assertEquals("RELEASED", read(fixture.manifest()).get("state"));
            assertTrue(lockAvailable(fixture.manifest().resolveSibling("writer.lock"), "missing-release-probe")); assertEquals(3, fixture.httpCalls.get());
            assertEquals("RUNNING", fixture.store.get("taskExecution", str(fixture.task(), "taskExecutionId")).get("state"));
            fixture.reopenAfterFault(); assertEquals("UNKNOWN", fixture.task().get("state")); assertEquals("UNKNOWN", fixture.lease().get("state"));
            assertEquals(budget, map(fixture.task(), "budget")); assertEquals(3, fixture.httpCalls.get());
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "resume", obj())).status());
            fixture.archive("missing-release-receipt"); fixture.reconcile(); fixture.engine.action(fixture.taskId, "resume", obj()); fixture.settled();
            assertNotEquals(original.get("id"), fixture.lease().get("id")); assertEquals(budget, map(fixture.task(), "budget")); assertEquals(3, fixture.httpCalls.get());
            assertEquals(1, fixture.store.list("operation").stream().filter(operation -> "WORKSPACE_WRITE".equals(str(operation, "kind"))).count());
            assertOldAndNewRoot(fixture, original, 3, "release-receipt-recovered"); fixture.sourceUnchanged(); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    private void assertOldAndNewRoot(Fixture fixture, Map<String, Object> original, int originalCalls, String archiveName) throws Exception {
        Map<String, Object> contents = fixture.archive(archiveName); List<Map<String, Object>> leases = objects(contents.get("workspace-write-leases.json")), executions = objects(contents.get("task-executions.json"));
        assertEquals(2, leases.size()); assertEquals(2, executions.size());
        Map<String, Object> oldLease = leases.stream().filter(lease -> str(original, "id").equals(str(lease, "id"))).findFirst().orElseThrow();
        assertEquals("UNKNOWN", oldLease.get("state")); assertTrue(bool(oldLease, "protectionReleased", false)); assertFalse(map(oldLease, "reconciliation").isEmpty());
        Map<String, Object> oldRoot = executions.stream().filter(execution -> str(original, "taskExecutionId").equals(str(execution, "id"))).findFirst().orElseThrow();
        assertEquals("UNKNOWN", oldRoot.get("state")); assertFalse(map(oldRoot, "reconciliation").isEmpty()); assertEquals(originalCalls, num(map(oldRoot, "budgetAtEnd"), "usedCalls", -1));
        assertEquals(3, num(map(fixture.task(), "budget"), "usedCalls", -1)); assertEquals(0, fixture.resources.activeModels());
    }

    private boolean lockAvailable(Path lock, String name) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        Path output = directory.resolve(name + ".json"), error = directory.resolve(name + ".stderr");
        Process probe = new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=2", "-cp", System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")), LockProbe.class.getName(), lock.toString())
            .redirectOutput(output.toFile()).redirectError(error.toFile()).start(); String birth = probe.info().startInstant().orElseThrow().toString();
        try {
            assertTrue(probe.waitFor(10, TimeUnit.SECONDS)); assertEquals(0, probe.exitValue(), Files.readString(error)); Map<String, Object> result = read(output);
            assertEquals(probe.pid(), ((Number) result.get("pid")).longValue()); assertEquals(birth, result.get("processStartedAt")); return bool(result, "acquired", false);
        } finally { if (probe.isAlive()) { assertEquals(birth, probe.info().startInstant().orElseThrow().toString()); probe.destroyForcibly(); assertTrue(probe.waitFor(5, TimeUnit.SECONDS)); } }
    }

    private Map<String, Object> read(Path path) throws Exception { return mapper.readValue(Files.readAllBytes(path), new TypeReference<>() {}); }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(25); }
        assertTrue(condition.getAsBoolean(), "actual root did not reach its original recorded boundary");
    }

    private static String git(Path workspace, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(15, TimeUnit.SECONDS)) { process.destroyForcibly(); assertTrue(process.waitFor(5, TimeUnit.SECONDS)); fail("original fixture Git timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.exitValue(), output); return output;
    }

    /**
     * 第二 JVM 只核验原锁路径的实际竞争，不根据核心文字状态推断锁已释放。
     */
    public static final class LockProbe {
        public static void main(String[] arguments) throws Exception {
            try (var channel = FileChannel.open(Path.of(arguments[0]), StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                System.out.println(new ObjectMapper().writeValueAsString(obj("pid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().map(Instant::toString).orElseThrow(), "acquired", lock != null)));
            }
        }
    }
}
