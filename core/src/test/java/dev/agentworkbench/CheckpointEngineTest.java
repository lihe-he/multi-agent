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
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Git、HTTP 和原 Worker 验证撤销事务；受控排队只延迟执行器，不替代文件效果。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class CheckpointEngineTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path data = directory.resolve("data"), source = Files.createDirectories(directory.resolve("中文 原仓库")).toRealPath();
        final Path evidence = Files.createDirectories(Path.of("target", "checkpoint-evidence-" + UUID.randomUUID()));
        final AtomicInteger modelCalls = new AtomicInteger(), restores = new AtomicInteger();
        final List<String> errors = new java.util.concurrent.CopyOnWriteArrayList<>();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final java.util.concurrent.ExecutorService httpThreads = Executors.newVirtualThreadPerTaskExecutor();
        final byte[] sourceIndex, sourceConfig;
        final String sourceHead, taskId;
        final boolean full;
        java.util.concurrent.ExecutorService workers;
        CountDownLatch queueGate;
        ExecutionStore store;
        ProcessWorkspaceRuntime runtime;
        WorkbenchCatalog catalog;
        WorkbenchEngine engine;
        ResourceManager resources;
        WorkbenchController controller;
        String fault = "";
        Map<String, Object> originalReturned = obj();
        boolean heldDuringRestore;

        Fixture(String strategy, boolean full) throws Exception {
            this.full = full;
            Files.writeString(source.resolve("app.txt"), "original app\n"); Files.writeString(source.resolve("later.txt"), "original later\n");
            Files.write(source.resolve("binary.dat"), new byte[] { 0, -1, 13, 10 });
            Files.writeString(source.resolve("verify.mjs"), "import {readFileSync} from 'node:fs';if(!['original app\\n','agent app\\n'].includes(readFileSync('app.txt','utf8')))process.exit(2);console.log('ORIGINAL_CHECKPOINT_VERIFICATION '+process.pid);\n");
            git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Checkpoint Fixture"); git(source, "config", "user.email", "checkpoint@example.invalid");
            git(source, "add", "."); git(source, "commit", "-m", "original checkpoint fixture");
            sourceIndex = Files.readAllBytes(source.resolve(".git/index")); sourceConfig = Files.readAllBytes(source.resolve(".git/config")); sourceHead = git(source, "rev-parse", "HEAD").strip();
            server.setExecutor(httpThreads); server.createContext("/v1/chat/completions", exchange -> {
                try {
                    Map<String, Object> request = mapper.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() {}); int number = modelCalls.incrementAndGet();
                    List<Map<String, Object>> messages = objects(request.get("messages")); String prompt = messages.stream().map(message -> str(message, "content")).reduce("", (left, right) -> left + right);
                    List<Map<String, Object>> tools = messages.stream().filter(message -> "tool".equals(str(message, "role"))).toList();
                    Map<String, Object> message = obj("role", "assistant", "content", prompt.contains("kind=REVIEW")
                        ? "{\"approved\":true,\"issues\":[{\"title\":\"original review suggestion\",\"severity\":\"suggestion\",\"evidence\":\"app.txt at original reviewed version\"}]}" : "original stage finished");
                    if (prompt.contains("kind=IMPLEMENT") && tools.size() < 2) {
                        String name = tools.isEmpty() ? "read_file" : "write_file"; Map<String, Object> arguments = obj("path", "app.txt");
                        if (!tools.isEmpty()) {
                            Map<String, Object> read = mapper.readValue(str(tools.getFirst(), "content"), new TypeReference<>() {});
                            assertEquals("original app\n", read.get("content")); arguments.putAll(obj("content", "agent app\n", "expectedVersion", read.get("readVersion")));
                        }
                        message = obj("role", "assistant", "content", null, "tool_calls", List.of(obj("id", "checkpoint-tool-" + number, "type", "function", "function", obj("name", name, "arguments", mapper.writeValueAsString(arguments)))));
                    }
                    byte[] response = mapper.writeValueAsBytes(obj("id", "checkpoint-response-" + number, "model", "fixture", "choices", List.of(obj("message", message, "finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop")), "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                    exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
                } catch (Throwable failure) { errors.add(failure.toString()); }
                finally { exchange.close(); }
            }); server.start(); openCore();
            try {
                Map<String, Object> project = catalog.importProject(obj("name", "original checkpoint source", "path", source.toString()));
                Map<String, Object> route = catalog.saveRoute(obj("name", "checkpoint local HTTP", "engine", "API", "model", "fixture", "enabled", true, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
                taskId = str(catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "Actual checkpoint root", "goal", "Preserve original receipts and file versions", "mode", "LEAD",
                    "workspaceSelection", obj("strategy", strategy, "ref", "HEAD"), "contract", obj("scope", List.of("**"), "acceptance", List.of("original file receipts"), "commands", List.of("node verify.mjs")), "budget", obj("maxCalls", 16, "reserveCalls", 4))), "id");
                if (!full) store.editTask(taskId, task -> task.put("plan", objects(task.get("plan")).stream().filter(stage -> "BASELINE".equals(str(stage, "kind"))).toList()), null, "");
                engine.action(taskId, "approve", obj()); settled();
                assertEquals(full ? "AWAITING_ACCEPTANCE" : "FAILED", task().get("state")); assertTrue(errors.isEmpty(), errors.toString());
            } catch (Exception | AssertionError failure) {
                // 构造失败也保存原记录并核对 Worker 停止，原失败继续向测试报告传播。
                try { closeCore(); } catch (Exception cleanup) { failure.addSuppressed(cleanup); }
                server.stop(0); httpThreads.shutdownNow(); throw failure;
            }
        }

        void openCore() throws Exception {
            store = new ExecutionStore(mapper, data.toString()); workers = Executors.newSingleThreadExecutor();
            runtime = new ProcessWorkspaceRuntime(mapper, data.toString()) {
                @Override public Map<String, Object> restoreCheckpoint(String workspace, String task, String operation, Map<String, Object> preview, BooleanSupplier cancelled) {
                    restores.incrementAndGet(); heldDuringRestore = !lockAvailable(data.resolve("workspaces").resolve(task).resolve("writer.lock"));
                    if ("BEFORE_FILE_CHANGED".equals(fault)) try { Files.writeString(Path.of(workspace).resolve("app.txt"), "later user change\n"); } catch (Exception exception) { throw new IllegalStateException(exception); }
                    BooleanSupplier stop = "PARTIAL_CANCEL".equals(fault) ? () -> {
                        if (cancelled.getAsBoolean()) return true;
                        try { return !strings(map(checkpointRestoreJournal(task, operation), "progress").get("writtenPaths")).isEmpty(); }
                        catch (RuntimeException ignored) { return false; }
                    } : cancelled;
                    Map<String, Object> result = super.restoreCheckpoint(workspace, task, operation, preview, stop); originalReturned = result;
                    if ("RETURN_LOST".equals(fault)) throw new IllegalStateException("controlled loss after actual Worker returned");
                    if ("OWNER_LOST".equals(fault)) {
                        Map<String, Object> binding = store.get("repositoryBinding", str(map(task(), "execution"), "repositoryBindingId")); binding.put("version", num(binding, "version", 1) + 1); store.put("repositoryBinding", binding);
                    }
                    if (Set.of("ACK_FAULT", "PUBLISH_FAULT").contains(fault)) try {
                        String condition = "ACK_FAULT".equals(fault) ? "new.kind='operation' AND json_extract(new.body,'$.kind')='CHECKPOINT_RESTORE' AND json_extract(new.body,'$.state')='ACKNOWLEDGED'"
                            : "new.kind='checkpointRestorePreview' AND json_extract(new.body,'$.state')='APPLIED'";
                        sql("CREATE TRIGGER checkpoint_fault BEFORE UPDATE ON entities WHEN " + condition + " BEGIN SELECT RAISE(ABORT,'original restore metadata fault'); END");
                    } catch (Exception exception) { throw new IllegalStateException(exception); }
                    return result;
                }
            };
            catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults(); resources = new ResourceManager();
            engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper, null, workers);
            controller = new WorkbenchController(store, catalog, engine, runtime, resources, new PlanValidator(), mapper, null);
        }

        Map<String, Object> task() { return store.get("task", taskId); }
        Path work() { return Path.of(str(task(), "workspace")); }
        Map<String, Object> save() { return controller.checkpoint(taskId, obj()); }
        Map<String, Object> preview(String target) { return controller.previewCheckpointRestore(taskId, obj("checkpointId", target)); }
        Map<String, Object> restore(Map<String, Object> preview) { return controller.checkpointRestore(taskId, obj("previewId", preview.get("id"))); }
        Map<String, Object> undoOperation() { return store.list("operation").stream().filter(value -> "CHECKPOINT_RESTORE".equals(str(value, "kind"))).findFirst().orElseThrow(); }
        Map<String, Object> undoRecord(Map<String, Object> preview) { return store.get("checkpointRestorePreview", str(preview, "id")); }
        Map<String, Object> journal() { return map(runtime.checkpointRestoreJournal(taskId, str(undoOperation(), "id")), "progress"); }

        Map<String, Object> implementationStart() {
            return store.taskCheckpointRecords(taskId, "checkpoint").stream()
                .filter(value -> "STAGE_START".equals(str(map(value, "metadata"), "kind")) && "implement".equals(str(map(value, "metadata"), "stage")))
                .findFirst().orElseThrow();
        }

        Map<String, Object> changedPreview() throws Exception {
            Map<String, Object> target = save(); Files.writeString(work().resolve("app.txt"), "agent app\n"); Files.delete(work().resolve("binary.dat")); save(); return preview(str(target, "id"));
        }

        void settled() throws Exception {
            await(() -> {
                Map<String, Object> execution = store.find("taskExecution", str(task(), "taskExecutionId"));
                if (execution == null || !execution.containsKey("endedAt")) return false;
                try { engine.requireExecutionSettled(taskId); return true; } catch (ApiException exception) { return false; }
            }, 90);
        }

        void blockQueue() throws Exception {
            queueGate = new CountDownLatch(1); CountDownLatch entered = new CountDownLatch(1);
            workers.submit(() -> { entered.countDown(); try { queueGate.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } });
            assertTrue(entered.await(3, TimeUnit.SECONDS));
        }

        void sql(String statement) throws Exception { try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var command = database.createStatement()) { command.execute(statement); } }

        void reopen() throws Exception {
            closeCore(); sql("DROP TRIGGER IF EXISTS checkpoint_fault"); fault = ""; openCore(); engine.recover();
        }

        void reconcile() {
            for (Map<String, Object> session : objects(engine.processRecovery().get("sessions"))) if (!bool(session, "reconciled", false)) {
                assertTrue(bool(session, "observedProcessesStopped", false), session.toString());
                engine.processRecoveryAction(str(session, "id"), "reconcile", obj("processesStopped", true, "coverageChecked", true, "note", "original Worker and observed Git processes independently checked"));
            }
            engine.action(taskId, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "original journal, app, index and HEAD inspected"));
        }

        Map<String, byte[]> archive(String name) throws Exception {
            byte[] bytes = controller.export(taskId).getBody(); assertNotNull(bytes); Files.write(evidence.resolve(name + ".zip"), bytes); Map<String, byte[]> entries = new LinkedHashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(bytes), StandardCharsets.UTF_8)) {
                for (var entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) assertNull(entries.put(entry.getName(), zip.readAllBytes()), entry.getName());
            }
            return entries;
        }

        void sourceUnchanged() throws Exception {
            assertEquals(sourceHead, git(source, "rev-parse", "HEAD").strip()); assertEquals("main", git(source, "branch", "--show-current").strip());
            assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(sourceConfig, Files.readAllBytes(source.resolve(".git/config")));
            assertEquals("original app\n", Files.readString(source.resolve("app.txt"))); assertEquals("", git(source, "status", "--porcelain=v1", "-uall"));
        }

        void snapshot(String label) throws Exception {
            Map<String, Object> facts = obj("worker", engine.workerIdentity(), "healthy", store.healthy(), "modelCalls", modelCalls.get(), "restoreInvocations", restores.get(), "modelErrors", errors,
                "sourceHead", sourceHead, "sourceIndex", sourceIndex, "sourceConfig", sourceConfig, "originalReturned", originalReturned, "actualRootLockHeldDuringRestore", heldDuringRestore);
            for (String kind : List.of("task", "checkpoint", "checkpointRestorePreview", "operation", "taskExecution", "dispatchAction", "workspaceWriteLease", "agentRun", "modelRequest", "processSession")) facts.put(kind, store.list(kind));
            Files.write(evidence.resolve(label + "-" + store.coreSessionId() + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(facts));
        }

        void closeCore() throws Exception {
            if (engine == null) return;
            Map<String, Object> original = engine.workerIdentity(); snapshot("before-close");
            try { engine.shutdown(); } finally { engine = null; store.close(); if (queueGate != null) queueGate.countDown(); }
            await(() -> ManagedProcessLedger.observedProcessStopped(((Number) original.get("pid")).longValue(), str(original, "processStartedAt")), 8);
            Files.write(evidence.resolve("stopped-" + original.get("pid") + ".json"), mapper.writeValueAsBytes(obj("identity", original, "originalStopped", true, "observedAt", now())));
        }

        @Override public void close() throws Exception { try { closeCore(); } finally { server.stop(0); httpThreads.shutdownNow(); } }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void actualFullPlanToolSnapshotsAndUndoRetainOriginalBudgetHistoryAndRootOwnership(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy, true)) {
            List<Map<String, Object>> checkpoints = fixture.store.taskCheckpointRecords(fixture.taskId, "checkpoint");
            List<Map<String, Object>> tools = checkpoints.stream().filter(value -> "TOOL_COMPLETED".equals(str(map(value, "metadata"), "kind"))).toList();
            assertEquals(2, tools.size()); assertEquals(5, fixture.modelCalls.get());
            for (Map<String, Object> checkpoint : tools) {
                Map<String, Object> metadata = map(checkpoint, "metadata"), request = fixture.store.get("modelRequest", str(metadata, "modelRequestId"));
                assertEquals("SUCCEEDED", request.get("state"));
                assertEquals(((Number) fixture.engine.workerIdentity().get("pid")).longValue(), ((Number) checkpoint.get("captureExecutorPid")).longValue());
                assertEquals(request.get("workspaceWriteLeaseId"), map(metadata, "source").get("workspaceWriteLeaseId"));
                assertEquals(2 + tools.indexOf(checkpoint), num(map(metadata, "budgetSnapshot"), "usedCalls", -1));
            }
            Map<String, Object> target = fixture.implementationStart();
            Map<String, Object> budget = new LinkedHashMap<>(map(fixture.task(), "budget")); List<Map<String, Object>> evidence = objects(fixture.task().get("evidence"));
            assertEquals(1, objects(fixture.task().get("issues")).size()); assertTrue(evidence.toString().contains("original review suggestion"));
            String oldExecution = str(fixture.task(), "taskExecutionId"); Files.writeString(fixture.work().resolve("later.txt"), "unrelated user edit\n");
            Map<String, Object> preview = fixture.preview(str(target, "id")); assertEquals(true, preview.get("canRestore")); fixture.restore(preview); fixture.settled();
            assertEquals("PAUSED", fixture.task().get("state")); assertEquals("original app\n", Files.readString(fixture.work().resolve("app.txt"))); assertEquals("unrelated user edit\n", Files.readString(fixture.work().resolve("later.txt")));
            assertEquals(budget, map(fixture.task(), "budget")); assertEquals(5, fixture.modelCalls.get()); assertEquals(evidence, objects(fixture.task().get("evidence")));
            assertFalse(strings(fixture.task().get("invalidatedEvidence")).isEmpty()); assertEquals("NOT_RUN", map(fixture.task(), "verification").get("status")); assertEquals("NOT_RUN", map(fixture.task(), "review").get("status"));
            assertTrue(objects(fixture.task().get("issues")).isEmpty()); assertTrue(objects(fixture.task().get("evidence")).toString().contains("original review suggestion"));
            assertNotEquals(oldExecution, fixture.task().get("taskExecutionId")); assertEquals(2, fixture.store.taskDispatchRecords("taskExecution", fixture.taskId).size());
            assertEquals("SUCCEEDED", fixture.store.get("taskExecution", oldExecution).get("state")); assertEquals("APPLIED", fixture.undoRecord(preview).get("state")); assertTrue(fixture.heldDuringRestore);
            assertEquals("ACKNOWLEDGED", fixture.undoOperation().get("state")); assertEquals("RELEASED", fixture.store.get("workspaceWriteLease", str(fixture.task(), "workspaceWriteLeaseId")).get("state"));
            Map<String, byte[]> archive = fixture.archive("actual-full-undo"); assertTrue(archive.keySet().stream().anyMatch(path -> path.startsWith("checkpoints/objects/")));
            assertTrue(archive.keySet().stream().anyMatch(path -> path.startsWith("checkpoints/restores/"))); assertEquals("PAUSED", decodeArchive(archive.get("task.json")).get("state")); fixture.sourceUnchanged();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"expired", "changed", "new-boundary"})
    void staleConfirmationProducesZeroNewRootOperationsAndNoUndoFileWrites(String change) throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            Map<String, Object> preview = fixture.changedPreview();
            if (change.equals("expired")) { Map<String, Object> value = fixture.undoRecord(preview); value.put("expiresAt", Instant.now().minusSeconds(1).toString()); fixture.store.put("checkpointRestorePreview", value); }
            if (change.equals("changed")) Files.writeString(fixture.work().resolve("app.txt"), "later user change\n");
            if (change.equals("new-boundary")) fixture.save();
            int originalRoots = fixture.store.list("taskExecution").size(), originalOperations = fixture.store.list("operation").size();
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.restore(preview)).status()); assertTrue(fixture.store.healthy());
            assertEquals(originalRoots, fixture.store.list("taskExecution").size()); assertEquals(originalOperations, fixture.store.list("operation").size()); assertEquals(0, fixture.restores.get());
            assertEquals("PREVIEW", fixture.undoRecord(preview).get("state")); assertEquals(change.equals("changed") ? "later user change\n" : "agent app\n", Files.readString(fixture.work().resolve("app.txt"))); assertFalse(Files.exists(fixture.work().resolve("binary.dat")));
        }
    }

    @Test
    void originalWorkerRejectsChangedFilesBeforeAnyUndoEffectWithoutClaimingSuccess() throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            Map<String, Object> preview = fixture.changedPreview(); fixture.fault = "BEFORE_FILE_CHANGED"; fixture.restore(preview); fixture.settled();
            assertEquals("PAUSED", fixture.task().get("state")); assertEquals("REJECTED", fixture.undoOperation().get("state")); assertEquals("REJECTED", fixture.undoRecord(preview).get("state"));
            assertEquals(false, fixture.undoOperation().get("effectsStarted")); assertEquals("later user change\n", Files.readString(fixture.work().resolve("app.txt"))); assertFalse(Files.exists(fixture.work().resolve("binary.dat")));
            assertEquals(false, fixture.runtime.checkpointRestoreJournal(fixture.taskId, str(fixture.undoOperation(), "id")).get("journalPresent")); assertEquals(1, fixture.restores.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"RETURN_LOST", "OWNER_LOST", "ACK_FAULT", "PUBLISH_FAULT"})
    void actualAppliedFilesWithMissingCoreConfirmationKeepOriginalReceiptAndNeverReplay(String fault) throws Exception {
        try (var fixture = new Fixture("CLONE", true)) {
            assertEquals("agent app\n", Files.readString(fixture.work().resolve("app.txt")));
            Map<String, Object> preview = fixture.preview(str(fixture.implementationStart(), "id")), budget = new LinkedHashMap<>(map(fixture.task(), "budget"));
            String recordedVersion = str(fixture.task(), "version");
            fixture.fault = fault; fixture.restore(preview);
            if (fault.endsWith("FAULT")) await(() -> !fixture.store.healthy(), 60); else fixture.settled();
            assertEquals("original app\n", Files.readString(fixture.work().resolve("app.txt"))); assertArrayEquals(new byte[] { 0, -1, 13, 10 }, Files.readAllBytes(fixture.work().resolve("binary.dat")));
            assertEquals("APPLIED", fixture.journal().get("state")); assertEquals("ACKNOWLEDGED", fixture.originalReturned.get("state")); fixture.reopen();
            assertEquals("UNKNOWN", fixture.task().get("state")); assertEquals("UNKNOWN", fixture.undoRecord(preview).get("state")); assertEquals(1, fixture.restores.get()); assertEquals(budget, map(fixture.task(), "budget"));
            if (!fault.equals("RETURN_LOST")) assertEquals("ACKNOWLEDGED", map(map(fixture.undoRecord(preview), "observedReceipt"), "result").get("state"));
            assertEquals(fault.equals("PUBLISH_FAULT") ? "ACKNOWLEDGED" : "UNKNOWN", fixture.undoOperation().get("state"));
            Map<String, byte[]> archive = fixture.archive("unknown-" + fault.toLowerCase()); Map<String, Object> manifest = decodeArchive(archive.get("manifest.json"));
            assertEquals(true, manifest.get("unknownRestoreEffects")); assertEquals(false, manifest.get("workspaceMatchesRecordedVersion")); assertEquals(fixture.runtime.fingerprint(fixture.work().toString()), manifest.get("workspaceVersion"));
            assertEquals(recordedVersion, manifest.get("version")); assertNotEquals(recordedVersion, manifest.get("workspaceVersion")); assertEquals(5, fixture.modelCalls.get());
            List<Map<String, Object>> sources = objects(mapper.readValue(archive.get("checkpoint-restore-journal-sources.json"), Object.class)); assertEquals("APPLIED", sources.getFirst().get("localProgressState"));
            assertTrue(archive.containsKey("checkpoints/tasks/" + str(map(fixture.originalReturned, "resultCheckpoint"), "id") + "/manifest.json"));
            fixture.reconcile(); assertEquals("PAUSED", fixture.task().get("state")); assertEquals("UNKNOWN", fixture.undoRecord(preview).get("state")); assertTrue(fixture.undoRecord(preview).containsKey("reconciliation"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.restore(preview)).status()); assertEquals(1, fixture.restores.get()); fixture.sourceUnchanged();
        }
    }

    @Test
    void realPartialCancellationRetainsChangedAndUnchangedPathsAsUnknown() throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            for (int index = 0; index < 8; index++) Files.writeString(fixture.work().resolve("partial-" + index + ".txt"), "original\n");
            Map<String, Object> target = fixture.save(); for (int index = 0; index < 8; index++) Files.writeString(fixture.work().resolve("partial-" + index + ".txt"), "agent\n"); fixture.save();
            Map<String, Object> preview = fixture.preview(str(target, "id")); fixture.fault = "PARTIAL_CANCEL"; fixture.restore(preview); fixture.settled();
            assertEquals("UNKNOWN", fixture.task().get("state")); assertEquals("UNKNOWN", fixture.undoRecord(preview).get("state")); assertEquals("UNKNOWN", fixture.journal().get("state"));
            List<String> written = strings(fixture.journal().get("writtenPaths")); assertFalse(written.isEmpty()); assertTrue(written.size() < 8, written.toString());
            for (int index = 0; index < 8; index++) { String path = "partial-" + index + ".txt"; assertEquals(written.contains(path) ? "original\n" : "agent\n", Files.readString(fixture.work().resolve(path))); }
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.engine.action(fixture.taskId, "resume", obj())).status()); fixture.archive("actual-partial-unknown");
            fixture.reopen(); assertEquals(1, fixture.restores.get()); assertEquals("UNKNOWN", fixture.task().get("state")); fixture.reconcile(); assertEquals(1, fixture.restores.get()); assertEquals("UNKNOWN", fixture.undoRecord(preview).get("state"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"preview", "execution", "action", "event"})
    void consumingPreviewAndEnqueueingRootRollbackInTheSameRealTransaction(String failure) throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            Map<String, Object> preview = fixture.changedPreview(); fixture.blockQueue();
            if (failure.equals("preview")) fixture.sql("CREATE TRIGGER checkpoint_fault BEFORE UPDATE ON entities WHEN new.kind='checkpointRestorePreview' AND json_extract(new.body,'$.state')='QUEUED' BEGIN SELECT RAISE(ABORT,'preview queue fault'); END");
            else if (failure.equals("event")) fixture.sql("CREATE TRIGGER checkpoint_fault BEFORE INSERT ON events WHEN new.type='QUEUED' BEGIN SELECT RAISE(ABORT,'queue event fault'); END");
            else fixture.sql("CREATE TRIGGER checkpoint_fault BEFORE INSERT ON entities WHEN new.kind='" + (failure.equals("execution") ? "taskExecution" : "dispatchAction") + "' BEGIN SELECT RAISE(ABORT,'queue record fault'); END");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.restore(preview)).status()); assertEquals("PREVIEW", fixture.undoRecord(preview).get("state"));
            assertEquals(1, fixture.store.list("taskExecution").size()); assertEquals(1, fixture.store.list("dispatchAction").size()); assertEquals("FAILED", fixture.task().get("state")); assertEquals(0, fixture.restores.get());
            fixture.reopen(); assertEquals("PREVIEW", fixture.undoRecord(preview).get("state")); assertEquals(0, fixture.restores.get());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"pause", "cancel", "restart-pause"})
    void originalUnclaimedUndoCanBeStoppedAndItsPreviewCannotReplay(String action) throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            Map<String, Object> preview = fixture.changedPreview(); fixture.blockQueue(); fixture.restore(preview);
            assertEquals("QUEUED", fixture.undoRecord(preview).get("state")); Map<String, Object> dispatch = fixture.store.get("dispatchAction", str(fixture.task(), "dispatchActionId"));
            assertEquals("PENDING", dispatch.get("state")); assertEquals(0, num(dispatch, "dispatchCount", -1)); assertEquals(409, assertThrows(ApiException.class, () -> fixture.restore(preview)).status());
            if (action.equals("restart-pause")) { fixture.reopen(); assertEquals(dispatch.get("id"), fixture.task().get("dispatchActionId")); assertEquals("QUEUED", fixture.undoRecord(preview).get("state")); }
            fixture.engine.action(fixture.taskId, action.equals("cancel") ? "cancel" : "pause", obj());
            assertEquals("CANCELLED", fixture.undoRecord(preview).get("state")); assertEquals("WITHDRAWN", fixture.store.get("dispatchAction", str(dispatch, "id")).get("state")); assertEquals(0, fixture.restores.get());
            assertEquals("agent app\n", Files.readString(fixture.work().resolve("app.txt"))); assertFalse(Files.exists(fixture.work().resolve("binary.dat")));
            if (!action.equals("cancel")) assertEquals(409, assertThrows(ApiException.class, () -> fixture.restore(preview)).status());
        }
    }

    @Test
    void foreignCheckpointAndForgedFieldsAreRejectedWithoutDegradingStorage() throws Exception {
        try (var fixture = new Fixture("CLONE", false)) {
            Map<String, Object> original = fixture.save(), foreign = new LinkedHashMap<>(original); foreign.put("id", id()); foreign.put("taskId", "foreign-task"); fixture.store.put("checkpoint", foreign);
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.preview(str(foreign, "id"))).status()); assertTrue(fixture.store.healthy());
            assertEquals(404, assertThrows(ApiException.class, () -> fixture.preview(id())).status()); assertTrue(fixture.store.healthy());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.controller.checkpoint(fixture.taskId, obj("workspace", fixture.source.toString()))).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.controller.previewCheckpointRestore(fixture.taskId, obj("checkpointId", original.get("id"), "version", version()))).status()); assertEquals(0, fixture.restores.get());
        }
    }

    private Map<String, Object> decodeArchive(byte[] bytes) throws Exception { return mapper.readValue(bytes, new TypeReference<>() {}); }
    private String version() { return "a".repeat(64); }
    private static boolean lockAvailable(Path path) {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.WRITE); var lock = channel.tryLock()) { return lock != null; }
        catch (Exception exception) { return false; }
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        assertTrue(condition.getAsBoolean(), "original checkpoint boundary did not arrive within " + seconds + " seconds");
    }
    private static String git(Path workspace, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false", "-C", workspace.toString())); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertTrue(process.waitFor(20, TimeUnit.SECONDS), command.toString()); assertEquals(0, process.exitValue(), command + "\n" + output); return output;
    }
}
