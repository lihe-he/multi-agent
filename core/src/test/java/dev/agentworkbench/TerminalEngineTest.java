package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实核心、SQLite、原 Worker 和系统 PTY 验证整次根保护及不重发输入。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class TerminalEngineTest {
    @TempDir(cleanup = CleanupMode.NEVER) static Path directory;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    private final class Fixture implements AutoCloseable {
        final Path fixture = Files.createDirectory(directory.resolve("terminal-engine-" + id())).toRealPath();
        final Path data = fixture.resolve("data"), source = Files.createDirectory(fixture.resolve("中文 source work")).toRealPath();
        final String taskId, marker = id().replace("-", "");
        final byte[] sourceIndex, sourceConfig;
        final String sourceHead;
        ExecutionStore store;
        ProcessWorkspaceRuntime runtime;
        WorkbenchCatalog catalog;
        WorkbenchEngine engine;
        Map<String, Object> controller, nativeControlReceipt = obj();
        String terminalId;
        long nextSequence = 1;
        boolean loseControlReceipt;
        Path receiptLossFile;
        volatile String startupFailureBoundary = "";
        volatile Map<String, Object> originalStartupFacts = obj();
        boolean startupFailureObserved;
        volatile String outputFailureBoundary = "";
        volatile Map<String, Object> originalOutputDescriptor = obj();
        boolean outputFailureObserved;

        Fixture(String strategy) throws Exception {
            Files.writeString(source.resolve("app.txt"), "original source\n");
            Files.writeString(source.resolve("verify.mjs"), "import {readFileSync} from 'node:fs';if(readFileSync('app.txt','utf8')!=='original source\\n')process.exit(2);console.log('ORIGINAL_TERMINAL_BASELINE');\n");
            git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Terminal Fixture"); git(source, "config", "user.email", "terminal@example.invalid");
            git(source, "add", "."); git(source, "commit", "-m", "original terminal fixture");
            sourceIndex = Files.readAllBytes(source.resolve(".git/index")); sourceConfig = Files.readAllBytes(source.resolve(".git/config")); sourceHead = git(source, "rev-parse", "HEAD").strip();
            openCore();
            try {
                Map<String, Object> project = catalog.importProject(obj("name", "terminal original source", "path", source.toString()));
                Map<String, Object> route = catalog.saveRoute(obj("name", "unused terminal model route", "engine", "API", "model", "unused-fixture", "baseUrl", "http://127.0.0.1:9/v1", "enabled", true));
                taskId = str(catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "Actual terminal root", "goal", "Preserve original shell and input identities", "mode", "LEAD",
                    "workspaceSelection", obj("strategy", strategy, "ref", "HEAD"), "contract", obj("scope", List.of("**"), "acceptance", List.of("original terminal receipts"), "commands", List.of("node verify.mjs")), "budget", obj("maxCalls", 12, "reserveCalls", 4))), "id");
                store.editTask(taskId, task -> task.put("plan", objects(task.get("plan")).stream().filter(stage -> "BASELINE".equals(str(stage, "kind"))).toList()), null, "");
                engine.action(taskId, "approve", obj()); settled(); assertEquals("FAILED", task().get("state"));
                assertEquals(0, num(map(task(), "budget"), "usedCalls", -1)); assertTrue(store.list("modelRequest").isEmpty());
                Map<String, Object> disabled = store.get("route", str(route, "id")); disabled.put("enabled", false); store.put("route", disabled);
            } catch (Exception | AssertionError failure) { try { closeCore(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); } throw failure; }
        }

        void openCore() throws Exception {
            store = new ExecutionStore(mapper, data.toString()) {
                @Override synchronized void commitRecords(Map<String, List<Map<String, Object>>> records, String identifier, String eventType, String message) {
                    if ("TERMINAL_OUTPUT".equals(eventType) && !outputFailureBoundary.isBlank() && !outputFailureObserved) {
                        outputFailureObserved = true;
                        originalOutputDescriptor = mapper.convertValue(records.get("terminalOutputSegment").getFirst(), new TypeReference<>() { });
                        boolean committed = "AFTER_COMMIT_BEFORE_ACK".equals(outputFailureBoundary);
                        if (committed) super.commitRecords(records, identifier, eventType, message);
                        try {
                            Files.write(fixture.resolve("original-output-publication-failure.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                                obj("boundary", outputFailureBoundary, "recordedAt", now(), "descriptor", originalOutputDescriptor, "sqliteCommitReturned", committed)));
                        } catch (Exception exception) { throw new IllegalStateException("original output callback evidence could not be saved", exception); }
                        throw new IllegalStateException("controlled original output publication loss at " + outputFailureBoundary);
                    }
                    if ("TERMINAL_STARTED".equals(eventType) && !startupFailureBoundary.isBlank() && !startupFailureObserved) {
                        startupFailureObserved = true;
                        originalStartupFacts = mapper.convertValue(map(records.get("terminalSession").getFirst(), "nativeProcess"), new TypeReference<>() { });
                        boolean committed = "AFTER_COMMIT_BEFORE_ACK".equals(startupFailureBoundary);
                        if (committed) super.commitRecords(records, identifier, eventType, message);
                        try {
                            Files.write(fixture.resolve("original-startup-callback-failure.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                                obj("boundary", startupFailureBoundary, "recordedAt", now(), "nativeProcess", originalStartupFacts, "sqliteCommitReturned", committed)));
                        } catch (Exception exception) { throw new IllegalStateException("original startup callback evidence could not be saved", exception); }
                        throw new IllegalStateException("controlled original startup callback loss at " + startupFailureBoundary);
                    }
                    super.commitRecords(records, identifier, eventType, message);
                }
            };
            runtime = new ProcessWorkspaceRuntime(mapper, data.toString()) {
                @Override Map<String, Object> controlTerminal(String identifier, Map<String, Object> action, byte[] input) {
                    Map<String, Object> result = super.controlTerminal(identifier, action, input); nativeControlReceipt = new LinkedHashMap<>(result);
                    if (loseControlReceipt) {
                        try {
                            await(() -> Files.isRegularFile(receiptLossFile), 20);
                            assertEquals("1", Files.readString(receiptLossFile));
                            Files.write(fixture.resolve("original-native-receipt-before-controlled-loss.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(result));
                        } catch (Exception exception) { throw new IllegalStateException("original native effect fixture did not settle before controlled receipt loss", exception); }
                        throw new IllegalStateException("controlled loss after original native control receipt and actual file effect");
                    }
                    return result;
                }
            };
            catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
            engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), mapper, null, Executors.newSingleThreadExecutor());
        }

        Map<String, Object> task() { return store.get("task", taskId); }
        Path work() { return Path.of(str(task(), "workspace")); }
        Map<String, Object> terminal() { return store.get("terminalSession", terminalId); }
        Map<String, Object> nativeProcess() { return map(terminal(), "nativeProcess"); }
        Path lock() { return data.resolve("workspaces").resolve(taskId).resolve("writer.lock"); }

        Map<String, Object> start() throws Exception {
            Map<String, Object> request = obj("requestId", id(), "columns", 80, "rows", 24);
            Map<String, Object> response = engine.startTerminal(taskId, request); terminalId = str(response, "id");
            await(() -> "RUNNING".equals(str(terminal(), "state")), 30);
            assertEquals(terminalId, engine.startTerminal(taskId, request).get("id"));
            Map<String, Object> conflict = new LinkedHashMap<>(request); conflict.put("columns", 81);
            assertEquals(409, assertThrows(ApiException.class, () -> engine.startTerminal(taskId, conflict)).status());
            controller = engine.acquireTerminal(terminalId, obj("expectedGeneration", 0, "controllerId", id())); nextSequence = 1;
            snapshot("original-terminal-running"); return request;
        }

        Map<String, Object> actionBody(String kind) {
            return obj("requestId", id(), "kind", kind, "sequence", nextSequence++, "controllerId", map(controller, "terminal").get("controllerId"),
                "generation", controller.get("generation"), "controlSecret", controller.get("controlSecret"));
        }

        Map<String, Object> inputBody(String command) {
            Map<String, Object> body = actionBody("INPUT"); body.put("dataBase64", Base64.getEncoder().encodeToString(command.getBytes(StandardCharsets.UTF_8))); return body;
        }

        Map<String, Object> input(String command) { return engine.terminalAction(terminalId, inputBody(command)); }

        String powershell(String script) {
            assertFalse(script.contains("\""), "fixed original console command quoting");
            return "powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"" + script + "\"\r";
        }

        byte[] output(int frameLimit) throws Exception {
            ByteArrayOutputStream raw = new ByteArrayOutputStream(); long cursor = 0;
            for (int index = 0; index < 100_000; index++) {
                Map<String, Object> frame = engine.terminalOutput(terminalId, str(terminal(), "epoch"), cursor, frameLimit);
                assertEquals(cursor, ((Number) frame.get("offset")).longValue()); byte[] bytes = Base64.getDecoder().decode(str(frame, "dataBase64"));
                assertEquals(bytes.length, num(frame, "length", -1)); assertTrue(bytes.length <= frameLimit); raw.write(bytes);
                cursor = ((Number) frame.get("next")).longValue(); if (!bool(frame, "hasMore", false)) return raw.toByteArray();
            }
            throw new IllegalStateException("original output page traversal exceeded the fixture boundary");
        }

        String plainOutput() {
            try { return new String(output(65_536), StandardCharsets.UTF_8).replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "").replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", ""); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        }

        void stopTerminal() throws Exception {
            assertEquals("ACKNOWLEDGED", engine.terminalAction(terminalId, actionBody("STOP")).get("state")); settled();
        }

        void settled() throws Exception {
            await(() -> {
                Map<String, Object> root = store.find("taskExecution", str(task(), "taskExecutionId"));
                if (root == null || !root.containsKey("endedAt")) return false;
                try { engine.requireExecutionSettled(taskId); return true; } catch (ApiException exception) { return false; }
            }, 45);
        }

        void snapshot(String name) throws Exception {
            Map<String, Object> facts = obj("recordedAt", now(), "worker", engine.workerIdentity(), "healthy", store.healthy(), "lastNativeControlReceipt", nativeControlReceipt);
            for (String kind : List.of("task", "terminalSession", "terminalAction", "terminalOutputSegment", "taskExecution", "dispatchAction", "workspaceWriteLease", "workerLease", "operation", "modelRequest", "processSession")) facts.put(kind, store.list(kind));
            Files.write(fixture.resolve(name + "-" + store.coreSessionId() + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(facts));
        }

        void sourceUnchanged() throws Exception {
            assertEquals(sourceHead, git(source, "rev-parse", "HEAD").strip()); assertEquals("main", git(source, "branch", "--show-current").strip());
            assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(sourceConfig, Files.readAllBytes(source.resolve(".git/config")));
            assertEquals("original source\n", Files.readString(source.resolve("app.txt")));
        }

        void reconcile() {
            for (Map<String, Object> session : objects(engine.processRecovery().get("sessions"))) if (!bool(session, "reconciled", false)) {
                assertTrue(bool(session, "observedProcessesStopped", false), session.toString());
                engine.processRecoveryAction(str(session, "id"), "reconcile", obj("processesStopped", true, "coverageChecked", true, "note", "original Worker, shell and unrecorded effects checked"));
            }
            engine.action(taskId, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "original manual file and source bytes checked"));
        }

        void reopen() throws Exception { closeCore(); loseControlReceipt = false; openCore(); engine.recover(); }

        void closeCore() throws Exception {
            if (engine == null) return; Map<String, Object> original = engine.workerIdentity(); snapshot("before-core-close");
            try { engine.shutdown(); } finally { engine = null; store.close(); }
            await(() -> ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")), 10);
            Files.write(fixture.resolve("original-worker-stopped-" + original.get("pid") + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj("original", original, "originalStopped", true, "recordedAt", now())));
        }

        @Override public void close() throws Exception { closeCore(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void originalWorkerKeepsOneRealShellAndDurableInputAcrossOutputReaderDetach(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy)) {
            Map<String, Object> before = fixture.task(); fixture.start(); Map<String, Object> original = fixture.nativeProcess();
            assertFalse(lockAvailable(fixture.lock())); assertEquals(fixture.engine.workerIdentity().get("id"), map(fixture.terminal(), "source").get("workerId"));
            assertNotEquals(ProcessHandle.current().pid(), ((Number) original.get("pid")).longValue());
            String command = WINDOWS ? fixture.powershell("[IO.File]::AppendAllText('manual-count.txt','1',[Text.UTF8Encoding]::new($false));[Console]::WriteLine('PTY_" + fixture.marker + ":'+([char]0x4E2D)+([char]0x6587))")
                : "printf '1' >> manual-count.txt; printf 'PTY_" + fixture.marker + ":中文\\n'\n";
            Map<String, Object> input = fixture.inputBody(command), receipt = fixture.engine.terminalAction(fixture.terminalId, input); assertEquals("ACKNOWLEDGED", receipt.get("state"));
            assertEquals(receipt, fixture.engine.terminalAction(fixture.terminalId, input));
            await(() -> Files.isRegularFile(fixture.work().resolve("manual-count.txt")) && fixture.plainOutput().contains("PTY_" + fixture.marker + ":中文"), 20);
            assertEquals("1", Files.readString(fixture.work().resolve("manual-count.txt")));
            Map<String, Object> resized = fixture.actionBody("RESIZE"); resized.putAll(obj("columns", 101, "rows", 37));
            assertEquals("ACKNOWLEDGED", fixture.engine.terminalAction(fixture.terminalId, resized).get("state"));
            assertEquals("ACKNOWLEDGED", fixture.input(WINDOWS ? fixture.powershell("[IO.File]::WriteAllText('actual-size.txt',(([Console]::WindowHeight).ToString()+' '+[Console]::WindowWidth),[Text.UTF8Encoding]::new($false));[IO.File]::WriteAllText('actual-size.done','done',[Text.UTF8Encoding]::new($false))") : "stty size > actual-size.txt; printf 'done' > actual-size.done\n").get("state"));
            await(() -> Files.isRegularFile(fixture.work().resolve("actual-size.done")), 20); assertEquals("37 101", Files.readString(fixture.work().resolve("actual-size.txt")).strip());
            Map<String, Object> firstRead = fixture.engine.terminalOutput(fixture.terminalId, str(fixture.terminal(), "epoch"), 0, 3);
            Map<String, Object> repeatedRead = fixture.engine.terminalOutput(fixture.terminalId, str(fixture.terminal(), "epoch"), 0, 3);
            // 原字节与段身份不变，仍在运行的终端可以继续发布后续输出。
            Map<String, Object> originalFrame = new LinkedHashMap<>(firstRead), repeatedFrame = new LinkedHashMap<>(repeatedRead);
            long firstEnd = ((Number) originalFrame.remove("publishedEnd")).longValue();
            long repeatedEnd = ((Number) repeatedFrame.remove("publishedEnd")).longValue();
            assertEquals(originalFrame, repeatedFrame); assertTrue(repeatedEnd >= firstEnd, repeatedRead.toString());
            Map<String, Object> observed = fixture.engine.terminal(fixture.terminalId); assertEquals(original, map(observed, "nativeProcess"));
            assertFalse(lockAvailable(fixture.lock())); assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "resume", obj())).status());
            fixture.stopTerminal(); Map<String, Object> terminal = fixture.terminal(); assertEquals("CANCELLED", terminal.get("state")); assertEquals("PAUSED", fixture.task().get("state"));
            assertTrue(lockAvailable(fixture.lock())); assertEquals("COMPLETE", terminal.get("outputIntegrity")); assertEquals(before.get("budget"), fixture.task().get("budget"));
            assertArrayEquals(fixture.output(65_536), fixture.output(3));
            assertEquals("NOT_RUN", map(fixture.task(), "verification").get("status")); assertEquals("NOT_RUN", map(fixture.task(), "review").get("status")); assertEquals(before.get("evidence"), fixture.task().get("evidence"));
            assertTrue(fixture.store.list("modelRequest").isEmpty()); assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")));
            assertEquals(1, fixture.store.list("terminalSession").size()); assertEquals(4, fixture.store.list("terminalAction").size()); fixture.sourceUnchanged(); fixture.snapshot("original-terminal-settled");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "cancel"})
    void rootPauseAndCancelWaitForTheOriginalForegroundChildAndNativeCleanup(String action) throws Exception {
        try (var fixture = new Fixture("CLONE")) {
            fixture.start(); Map<String, Object> original = fixture.nativeProcess();
            assertEquals("ACKNOWLEDGED", fixture.input(WINDOWS ? fixture.powershell("[IO.File]::WriteAllText('child-pid.txt',([Diagnostics.Process]::GetCurrentProcess().Id).ToString());Start-Sleep -Seconds 30")
                : "sh -c 'echo $$ > child-pid.txt; sleep 30'\n").get("state"));
            await(() -> Files.isRegularFile(fixture.work().resolve("child-pid.txt")), 20); long child = Long.parseLong(Files.readString(fixture.work().resolve("child-pid.txt")).strip());
            String birth = ProcessHandle.of(child).orElseThrow().info().startInstant().orElseThrow().toString();
            await(() -> fixture.engine.taskProcessObservations(fixture.taskId).stream().anyMatch(item -> num(item, "pid", -1) == child && birth.equals(str(item, "processStartedAt"))), 8);
            fixture.engine.action(fixture.taskId, action, obj()); fixture.settled();
            assertEquals("cancel".equals(action) ? "CANCELLED" : "PAUSED", fixture.task().get("state"));
            if ("cancel".equals(action)) assertEquals("CANCELLED", fixture.task().get("outcome"));
            assertEquals("CANCELLED", fixture.terminal().get("state")); Map<String, Object> result = map(fixture.terminal(), "result");
            for (String fact : List.of("originalProcessStopped", "observedChildrenStopped", "outputReaderCompleted", "nativeCleanupThreadCompleted", "ownedStreamsClosed")) assertTrue(bool(result, fact, false), fact);
            assertTrue(ManagedProcessLedger.observedProcessStopped(child, birth)); assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")));
            assertTrue(lockAvailable(fixture.lock())); assertEquals(0, num(map(fixture.task(), "budget"), "usedCalls", -1)); fixture.sourceUnchanged(); fixture.snapshot("original-root-" + action + "-settled");
        }
    }

    @Test
    void lostNativeInputAcknowledgmentKeepsOneActualWriteAndUnknownHistoryAfterReconciliation() throws Exception {
        try (var fixture = new Fixture("CLONE")) {
            Map<String, Object> request = fixture.start(); String originalId = fixture.terminalId; Map<String, Object> original = fixture.nativeProcess(); fixture.loseControlReceipt = true;
            fixture.receiptLossFile = fixture.work().resolve("uncertain-count.txt");
            String command = WINDOWS ? fixture.powershell("[IO.File]::AppendAllText('uncertain-count.txt','1',[Text.UTF8Encoding]::new($false))") : "printf '1' >> uncertain-count.txt\n";
            Map<String, Object> body = fixture.inputBody(command); Map<String, Object> observation = fixture.engine.terminalAction(originalId, body);
            assertEquals("UNKNOWN", observation.get("state")); assertEquals("ACKNOWLEDGED", fixture.nativeControlReceipt.get("state")); fixture.settled();
            assertEquals("UNKNOWN", fixture.terminal().get("state")); assertEquals("UNKNOWN", fixture.task().get("state"));
            assertEquals(1, num(observation, "dispatchCount", -1)); assertEquals(1, num(observation, "nativeAuthorizationCount", -1));
            assertEquals("1", Files.readString(fixture.work().resolve("uncertain-count.txt")));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "resume", obj())).status());
            fixture.snapshot("original-input-unknown"); fixture.reopen(); assertEquals("UNKNOWN", fixture.terminal().get("state"));
            assertEquals(originalId, fixture.engine.startTerminal(fixture.taskId, request).get("id")); assertEquals(1, fixture.store.list("terminalSession").size());
            fixture.reconcile(); assertFalse(fixture.task().containsKey("pendingTerminalId")); assertEquals("UNKNOWN", fixture.terminal().get("state")); assertTrue(fixture.terminal().containsKey("reconciliation"));
            assertEquals("UNKNOWN", fixture.store.get("terminalAction", str(observation, "id")).get("state")); assertTrue(fixture.store.get("terminalAction", str(observation, "id")).containsKey("reconciliation"));
            fixture.start(); assertNotEquals(originalId, fixture.terminalId); assertNotEquals(original.get("pid"), fixture.nativeProcess().get("pid")); fixture.stopTerminal();
            assertEquals(2, fixture.store.list("terminalSession").size()); assertEquals("UNKNOWN", fixture.store.get("terminalSession", originalId).get("state"));
            assertEquals(0, num(map(fixture.task(), "budget"), "usedCalls", -1)); fixture.sourceUnchanged(); fixture.snapshot("original-unknown-and-new-terminal");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT_BEFORE_ACK"})
    void originalWorkerDrainsAndStopsTheStartedShellWhenStartupAcknowledgmentIsLost(String boundary) throws Exception {
        try (var fixture = new Fixture("CLONE")) {
            Map<String, Object> before = fixture.task(); fixture.startupFailureBoundary = boundary;
            Map<String, Object> request = obj("requestId", id(), "columns", 80, "rows", 24);
            fixture.terminalId = str(fixture.engine.startTerminal(fixture.taskId, request), "id"); fixture.settled();
            assertTrue(fixture.startupFailureObserved); assertFalse(fixture.originalStartupFacts.isEmpty());
            Map<String, Object> result = map(fixture.terminal(), "result"), original = map(result, "nativeProcess");
            assertEquals(fixture.originalStartupFacts, original); assertEquals("UNKNOWN", result.get("status")); assertFalse(bool(result, "startupAcknowledged", true));
            for (String fact : List.of("originalProcessStopped", "observedChildrenStopped", "outputReaderCompleted", "outputPublisherCompleted", "nativeCleanupThreadCompleted", "ownedStreamsClosed", "nativeEofObserved")) assertTrue(bool(result, fact, false), fact);
            assertEquals("UNKNOWN", fixture.terminal().get("state")); assertEquals("UNKNOWN", fixture.task().get("state"));
            assertTrue(bool(map(result, "outputEnd"), "complete", false)); assertTrue(bool(fixture.terminal(), "outputEnded", false));
            assertEquals("COMPLETE_WITH_UNCONFIRMED_INPUT_OR_NATIVE_RESULT", fixture.terminal().get("outputIntegrity"));
            assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")));
            assertEquals(fixture.terminalId, fixture.engine.startTerminal(fixture.taskId, request).get("id"));
            assertEquals(1, fixture.store.list("terminalSession").size()); assertTrue(fixture.store.list("terminalAction").isEmpty());
            assertEquals(before.get("budget"), fixture.task().get("budget")); assertTrue(fixture.store.list("modelRequest").isEmpty());
            fixture.sourceUnchanged(); fixture.snapshot("original-startup-unknown-" + boundary.toLowerCase(java.util.Locale.ROOT));
        }
    }

    /**
     * 原输出发布前后失去回执均停止原 shell，重启保留原发布游标且不补造引用。
     */
    @ParameterizedTest
    @ValueSource(strings = {"BEFORE_COMMIT", "AFTER_COMMIT_BEFORE_ACK"})
    void lostOriginalOutputPublicationAcknowledgmentRetainsDurableCursorWithoutReplayOrRepair(String boundary) throws Exception {
        try (var fixture = new Fixture("CLONE")) {
            Map<String, Object> before = fixture.task(); fixture.outputFailureBoundary = boundary;
            Map<String, Object> request = obj("requestId", id(), "columns", 80, "rows", 24);
            fixture.terminalId = str(fixture.engine.startTerminal(fixture.taskId, request), "id"); fixture.settled();
            assertTrue(fixture.outputFailureObserved); assertFalse(fixture.originalOutputDescriptor.isEmpty());
            Map<String, Object> result = map(fixture.terminal(), "result"), original = map(result, "nativeProcess");
            assertEquals("UNKNOWN", result.get("status")); assertFalse(map(result, "publicationFailure").isEmpty());
            for (String fact : List.of("originalProcessStopped", "observedChildrenStopped", "outputReaderCompleted", "outputPublisherCompleted", "nativeCleanupThreadCompleted", "ownedStreamsClosed", "nativeEofObserved")) assertTrue(bool(result, fact, false), fact);
            assertEquals("UNKNOWN", fixture.terminal().get("state")); assertEquals("UNKNOWN", fixture.task().get("state"));
            assertNotEquals("COMPLETE", fixture.terminal().get("outputIntegrity"));
            long committedEnd = "BEFORE_COMMIT".equals(boundary) ? 0L : ((Number) fixture.originalOutputDescriptor.get("next")).longValue();
            assertEquals(committedEnd, num(fixture.terminal(), "outputEnd", -1));
            assertEquals("BEFORE_COMMIT".equals(boundary) ? 0 : 1, fixture.store.list("terminalOutputSegment").size());
            byte[] originalVisibleBytes = fixture.output(3); assertEquals(committedEnd, originalVisibleBytes.length);
            assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")));
            assertEquals(fixture.terminalId, fixture.engine.startTerminal(fixture.taskId, request).get("id"));
            assertEquals(1, fixture.store.list("terminalSession").size()); assertTrue(fixture.store.list("terminalAction").isEmpty());
            assertEquals(before.get("budget"), fixture.task().get("budget")); assertTrue(fixture.store.list("modelRequest").isEmpty());
            fixture.snapshot("original-output-publication-unknown-" + boundary.toLowerCase(java.util.Locale.ROOT));
            fixture.reopen();
            assertEquals("UNKNOWN", fixture.terminal().get("state")); assertEquals(committedEnd, num(fixture.terminal(), "outputEnd", -1));
            assertArrayEquals(originalVisibleBytes, fixture.output(3)); assertEquals(1, fixture.store.list("terminalSession").size());
            assertTrue(fixture.store.list("terminalAction").isEmpty()); assertTrue(fixture.store.list("modelRequest").isEmpty());
            assertEquals(before.get("budget"), fixture.task().get("budget")); fixture.sourceUnchanged();
            fixture.snapshot("original-output-publication-after-restart-" + boundary.toLowerCase(java.util.Locale.ROOT));
        }
    }

    @Test
    void originalWorkerDrainsNativeOutputAfterTheOriginalArchiveOwnerCannotBeRead() throws Exception {
        try (var fixture = new Fixture("CLONE")) {
            fixture.start(); Map<String, Object> original = fixture.nativeProcess();
            Path producer = fixture.work().resolve("terminal-storage-producer.mjs");
            Files.writeString(producer, "import{writeFileSync,existsSync}from'node:fs';writeFileSync('storage-producer-pid.txt',String(process.pid));while(!existsSync('storage-release.txt'))await new Promise(r=>setTimeout(r,10));for(let i=0;i<2000;i++)process.stdout.write('STORAGE_" + fixture.marker + "_'+i+':'+'x'.repeat(4096)+'\\n');\n");
            assertEquals("ACKNOWLEDGED", fixture.input("node terminal-storage-producer.mjs" + (WINDOWS ? "\r" : "\n")).get("state"));
            await(() -> Files.isRegularFile(fixture.work().resolve("storage-producer-pid.txt")), 20);
            long child = Long.parseLong(Files.readString(fixture.work().resolve("storage-producer-pid.txt")).strip());
            String birth = ProcessHandle.of(child).orElseThrow().info().startInstant().orElseThrow().toString();
            await(() -> fixture.engine.taskProcessObservations(fixture.taskId).stream().anyMatch(item -> num(item, "pid", -1) == child && birth.equals(str(item, "processStartedAt"))), 8);
            Path folder = fixture.data.resolve("terminal-output").resolve(fixture.terminalId), owner = folder.resolve("owner.json"), hidden = fixture.fixture.resolve("original-storage-owner-before-failure.json");
            fixture.snapshot("original-output-before-storage-failure"); Files.move(owner, hidden); Files.writeString(fixture.work().resolve("storage-release.txt"), "release original producer\n");
            fixture.settled(); Map<String, Object> result = map(fixture.terminal(), "result");
            assertEquals("UNKNOWN", fixture.terminal().get("state")); assertEquals("UNKNOWN", fixture.task().get("state")); assertEquals("UNKNOWN", result.get("status"));
            for (String fact : List.of("originalProcessStopped", "observedChildrenStopped", "outputReaderCompleted", "outputPublisherCompleted", "nativeCleanupThreadCompleted", "ownedStreamsClosed", "nativeEofObserved")) assertTrue(bool(result, fact, false), fact);
            assertFalse(map(result, "outputStorageFailure").isEmpty()); assertTrue(map(result, "readerFailure").isEmpty());
            assertFalse(map(result, "outputEndReceiptFailure").isEmpty()); assertTrue(bool(map(result, "outputEnd"), "endReceiptMissing", false));
            Map<String, Object> uncommitted = map(result, "uncommittedOutput");
            long missing = ((Number) uncommitted.get("bytes")).longValue(); assertTrue(missing > 0);
            assertEquals(((Number) result.get("observedOutputBytes")).longValue(), ((Number) result.get("savedOutputBytes")).longValue() + missing);
            assertTrue(Base64.getDecoder().decode(str(uncommitted, "retainedPrefixBase64")).length > 0); assertTrue(Base64.getDecoder().decode(str(uncommitted, "retainedPrefixBase64")).length <= 65_536);
            assertEquals("ORIGINAL_NATIVE_RESULT_IF_ACKNOWLEDGED", uncommitted.get("prefixDurability"));
            assertFalse(Files.exists(folder.resolve("end.json"))); assertFalse(Files.exists(owner));
            assertTrue(ManagedProcessLedger.observedProcessStopped(child, birth)); assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")));
            fixture.snapshot("original-storage-failed-and-native-drained"); Files.move(hidden, owner);
            assertFalse(Files.exists(folder.resolve("end.json"))); assertEquals(1, fixture.store.list("terminalSession").size()); assertEquals(1, fixture.store.list("terminalAction").size());
            assertEquals(0, num(map(fixture.task(), "budget"), "usedCalls", -1)); fixture.sourceUnchanged(); fixture.snapshot("original-owner-restored-after-native-stop");
        }
    }

    /**
     * 退出前复制整个原测试现场，成功和失败的 shell、原字节与 SQLite 均保留。
     */
    @AfterAll
    static void preserveAllOriginalTerminalFixtures() throws Exception {
        Path source = directory.toRealPath(), target = Path.of("target").toAbsolutePath().normalize().resolve("terminal-native-evidence-" + id());
        Files.createDirectory(target); Path copied = Files.createDirectory(target.resolve("original-fixture"));
        List<Map<String, Object>> files = new java.util.ArrayList<>(); Map<String, Object> receipt = obj("originalFixture", source.toString(), "recordedAt", now(), "files", files, "completeCopy", false);
        try {
            Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path path, java.nio.file.attribute.BasicFileAttributes facts) throws java.io.IOException { Files.createDirectories(copied.resolve(source.relativize(path))); return java.nio.file.FileVisitResult.CONTINUE; }
                @Override public java.nio.file.FileVisitResult visitFile(Path path, java.nio.file.attribute.BasicFileAttributes facts) throws java.io.IOException {
                    if (!facts.isRegularFile() || facts.isSymbolicLink()) throw new java.io.IOException("Unsupported original terminal fixture type");
                    byte[] bytes = Files.readAllBytes(path); Path relative = source.relativize(path), destination = copied.resolve(relative); Files.copy(path, destination, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (!java.util.Arrays.equals(bytes, Files.readAllBytes(destination)) || !java.util.Arrays.equals(bytes, Files.readAllBytes(path))) throw new java.io.IOException("Original terminal fixture changed during preservation");
                    try { files.add(obj("file", relative.toString(), "bytes", bytes.length, "sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))); }
                    catch (java.security.NoSuchAlgorithmException exception) { throw new java.io.IOException(exception); }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            }); receipt.put("completeCopy", true);
        } catch (Exception exception) { receipt.put("copyGap", exception.toString()); throw exception; }
        finally { Files.write(target.resolve("fixture-preservation.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt)); }
    }

    private static boolean lockAvailable(Path path) {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = channel.tryLock()) { return lock != null; }
        catch (Exception exception) { return false; }
    }

    private static String git(Path path, String... args) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "-C", path.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); throw new IllegalStateException("Original fixture Git timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.exitValue(), output); return output;
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(condition.getAsBoolean(), "original terminal condition did not arrive within " + seconds + " seconds");
    }
}
