package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class RootDispatchEngineTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private WorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private ExecutorService controlled;
    private CountDownLatch release;
    private Map<String, Object> project;
    private Map<String, Object> route;
    private final AtomicInteger prepared = new AtomicInteger();
    private final AtomicInteger calls = new AtomicInteger();

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString()); runtime = mock(WorkspaceRuntime.class);
        Path source = Files.createDirectories(directory.resolve("中文 仓库")), workspace = Files.createDirectories(directory.resolve("任务 现场"));
        when(runtime.inspect(anyString())).thenReturn(obj("path", source.toString(), "head", "base", "branch", "main", "dirty", false));
        when(runtime.prepare(anyString(), anyString(), anyBoolean())).thenAnswer(ignored -> { prepared.incrementAndGet(); return obj("workspace", workspace.toString(), "baseline", "base", "version", "v1"); });
        when(runtime.fingerprint(anyString())).thenReturn("v1"); when(runtime.diff(anyString())).thenReturn("");
        when(runtime.reviewCopy(anyString(), anyString(), anyString())).thenReturn(workspace.toString());
        when(runtime.verify(anyString(), anyString(), any(), any())).thenReturn(result("verified"));
        when(runtime.run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); if (!acquire.getAsBoolean()) return obj("status", "FAILED", "exitCode", -1);
            calls.incrementAndGet(); return result(((String) invocation.getArgument(3)).contains("kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "observed stage");
        });
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        project = catalog.importProject(obj("name", "root queue", "path", source.toString()));
        route = catalog.saveRoute(obj("name", "queue fixture", "engine", "API", "baseUrl", "http://127.0.0.1:18905/v1", "model", "fixture"));
    }

    @AfterEach
    void close() throws Exception {
        if (controlled != null) controlled.shutdownNow(); if (release != null) release.countDown();
        if (engine != null) engine.shutdown(); if (store != null) store.close();
    }

    private static Map<String, Object> result(String output) { return obj("status", "SUCCEEDED", "exitCode", 0, "output", output); }

    private String task() {
        return str(catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "Durable dispatch fixture", "goal", "Preserve original queue actions",
            "mode", "LEAD", "contract", obj("scope", List.of("**"), "acceptance", List.of("keep original dispatch source"), "commands", List.of("node verify.mjs")),
            "budget", obj("maxCalls", 12, "reserveCalls", 4))), "id");
    }

    private void blockExecutor() throws Exception {
        controlled = Executors.newSingleThreadExecutor(); release = new CountDownLatch(1); CountDownLatch entered = new CountDownLatch(1);
        controlled.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(3, TimeUnit.SECONDS));
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper(), null, controlled);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        fail("root queue did not reach expected boundary");
    }

    @Test
    void failedApprovalNeverRunsWorkerOrLeavesConfirmedDraft() throws Exception {
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task();
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + store.directory().resolve("workbench.sqlite")); var sql = database.createStatement()) {
            sql.execute("CREATE TRIGGER fail_dispatch BEFORE INSERT ON entities WHEN new.kind='dispatchAction' BEGIN SELECT RAISE(ABORT,'dispatch fault'); END");
        }
        assertEquals(503, assertThrows(ApiException.class, () -> engine.action(taskId, "approve", obj())).status());
        assertEquals("DRAFT", str(store.get("task", taskId), "state")); assertFalse(bool(map(store.get("task", taskId), "contract"), "confirmed", true));
        assertEquals(0, prepared.get()); assertEquals(0, calls.get()); assertTrue(store.list("operation").isEmpty()); assertTrue(store.list("taskExecution").isEmpty());
    }

    @Test
    void queuedPauseAndNewResumeRetainWithdrawnOriginalAttempt() throws Exception {
        blockExecutor(); String taskId = task(); engine.action(taskId, "approve", obj());
        Map<String, Object> original = store.get("task", taskId); String executionId = str(original, "taskExecutionId"), actionId = str(original, "dispatchActionId");
        engine.action(taskId, "pause", obj()); engine.action(taskId, "resume", obj());
        assertEquals("PAUSED", str(store.get("taskExecution", executionId), "state")); assertEquals("WITHDRAWN", str(store.get("dispatchAction", actionId), "state"));
        assertEquals(0, num(store.get("dispatchAction", actionId), "dispatchCount", -1));
        Map<String, Object> current = store.get("task", taskId); assertNotEquals(actionId, str(current, "dispatchActionId")); assertEquals(2, num(map(current, "taskExecution"), "attempt", 0));
        engine.action(taskId, "cancel", obj()); release.countDown(); controlled.shutdown(); assertTrue(controlled.awaitTermination(3, TimeUnit.SECONDS));
        assertEquals(0, prepared.get()); assertEquals(0, calls.get()); verify(runtime, never()).stop(taskId); assertTrue(store.list("workerLease").isEmpty());
        assertEquals("CANCELLED", str(store.get("task", taskId), "state"));
    }

    @Test
    void rejectedExecutorPreservesPendingWithoutControlOrExecution() throws Exception {
        controlled = Executors.newSingleThreadExecutor(); controlled.shutdown();
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper(), null, controlled);
        String taskId = task(); engine.action(taskId, "approve", obj());
        await(() -> !str(map(store.get("task", taskId), "dispatch"), "waitReason").isBlank());
        assertEquals("PENDING", str(map(store.get("task", taskId), "dispatch"), "state")); assertEquals(0, prepared.get());
        engine.action(taskId, "cancel", obj()); assertEquals("CANCELLED", str(store.get("task", taskId), "state")); verify(runtime, never()).stop(taskId);
    }

    @Test
    void cleanShutdownAndReopenExecuteOriginalUnclaimedAction() throws Exception {
        blockExecutor(); String taskId = task(); engine.action(taskId, "approve", obj());
        String actionId = str(store.get("task", taskId), "dispatchActionId"), executionId = str(store.get("task", taskId), "taskExecutionId");
        controlled.shutdownNow(); engine.shutdown(); engine = null;
        assertEquals("PENDING", str(store.get("dispatchAction", actionId), "state")); assertEquals(0, prepared.get());
        store.close(); store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString()); catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); engine.recover();
        await(() -> "SUCCEEDED".equals(str(store.get("taskExecution", executionId), "state")));
        assertEquals("AWAITING_ACCEPTANCE", str(store.get("task", taskId), "state")); assertEquals(actionId, str(store.get("task", taskId), "dispatchActionId"));
        assertEquals(1, prepared.get()); assertEquals(3, calls.get()); assertEquals(1, num(store.get("dispatchAction", actionId), "dispatchCount", 0)); assertEquals(1, store.list("taskExecution").size());
        for (String kind : List.of("operation", "workerLease", "agentRun", "modelRequest", "evidence")) {
            List<Map<String, Object>> records = store.list(kind); assertFalse(records.isEmpty(), kind);
            for (Map<String, Object> record : records) { assertEquals(executionId, str(record, "taskExecutionId"), kind); assertEquals(actionId, str(record, "dispatchActionId"), kind); }
        }
    }

    @Test
    void oldQueuedTaskWithoutDispatchRecordStillRecoversUnknown() throws Exception {
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task();
        store.editTask(taskId, task -> { task.put("state", "QUEUED"); map(task, "contract").put("confirmed", true); }, null, ""); engine.recover();
        assertEquals("UNKNOWN", str(store.get("task", taskId), "state")); assertTrue(store.list("taskExecution").isEmpty()); assertEquals(0, prepared.get());
    }

    @Test
    void changedNodeVersionBlocksPendingBeforeAnyWorkerOperation() throws Exception {
        blockExecutor(); String taskId = task(); engine.action(taskId, "approve", obj());
        Map<String, Object> execution = map(store.get("task", taskId), "execution"), node = store.get("executionNode", str(execution, "executionNodeId"));
        node.put("version", num(node, "version", 0) + 1); store.put("executionNode", node); release.countDown();
        await(() -> !str(map(store.get("task", taskId), "dispatch"), "waitReason").isBlank());
        assertEquals(0, prepared.get()); assertEquals(0, calls.get()); assertTrue(store.list("operation").isEmpty());
        assertEquals("PENDING", str(map(store.get("task", taskId), "dispatch"), "state")); engine.action(taskId, "pause", obj());
        assertEquals("PAUSED", str(store.get("task", taskId), "state"));
    }

    @Test
    void runningCancellationUsesExistingWorkerStopAndSettlesClaimedAttempt() throws Exception {
        CountDownLatch preparing = new CountDownLatch(1), finishPrepare = new CountDownLatch(1);
        doAnswer(invocation -> { prepared.incrementAndGet(); preparing.countDown(); assertTrue(finishPrepare.await(5, TimeUnit.SECONDS)); return obj("workspace", directory.resolve("任务 现场").toString(), "baseline", "base", "version", "v1"); }).when(runtime).prepare(anyString(), anyString(), anyBoolean());
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task(); engine.action(taskId, "approve", obj());
        try {
            assertTrue(preparing.await(3, TimeUnit.SECONDS)); engine.action(taskId, "cancel", obj()); verify(runtime, timeout(2000)).stop(taskId);
        } finally { finishPrepare.countDown(); }
        String executionId = str(store.get("task", taskId), "taskExecutionId"); await(() -> "CANCELLED".equals(str(store.get("taskExecution", executionId), "state")));
        assertEquals(1, num(map(store.get("task", taskId), "dispatch"), "dispatchCount", 0)); assertEquals(0, calls.get()); assertEquals(1, prepared.get());
        assertEquals("SETTLED", str(map(store.get("task", taskId), "dispatch"), "state"));
    }

    @Test
    void workerLeaseRejectsMissingOrEndedRootSource() throws Exception {
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task();
        TaskDispatchQueue queue = new TaskDispatchQueue(store); Map<String, Object> action = queue.enqueue(taskId, "USER_APPROVAL", task -> map(task, "contract").put("confirmed", true));
        queue.claim(str(action, "id"), 1, ignored -> {}); WorkerLedger ledger = new WorkerLedger(store, catalog.nodes());
        String source = str(map(store.get("task", taskId), "execution"), "sourcePath"); Map<String, Object> operation = store.beginOperation(taskId, "prepare", "WORKSPACE", source);
        Map<String, Object> lease = ledger.claim(engine.workerIdentity(), "operation", str(operation, "id"), source, "original-token", obj(), Instant.now());
        assertEquals(str(action, "id"), str(lease, "dispatchActionId")); assertEquals(str(action, "taskExecutionId"), str(lease, "taskExecutionId"));
        Map<String, Object> stale = store.beginOperation(taskId, "prepare", "WORKSPACE", source); stale.put("taskExecutionId", "wrong-root"); store.put("operation", stale);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.claim(engine.workerIdentity(), "operation", str(stale, "id"), source, "wrong-token", obj(), Instant.now())).status());
        assertNull(store.workerLeaseForOperation("operation", str(stale, "id")));
        store.editTask(taskId, task -> task.put("state", "PAUSED"), null, ""); queue.finish(str(action, "taskExecutionId"));
        Map<String, Object> late = store.beginOperation(taskId, "prepare", "WORKSPACE", source);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.claim(engine.workerIdentity(), "operation", str(late, "id"), source, "late-token", obj(), Instant.now())).status());
        assertNull(store.workerLeaseForOperation("operation", str(late, "id")));
    }

    @Test
    void mcpIntentCannotUseAnOldOrEndedRootBeforeBudgetCommit() {
        String taskId = task(); TaskDispatchQueue queue = new TaskDispatchQueue(store);
        Map<String, Object> action = queue.enqueue(taskId, "USER_APPROVAL", task -> map(task, "contract").put("confirmed", true));
        queue.claim(str(action, "id"), 1, ignored -> {}); store.editTask(taskId, task -> task.put("state", "RUNNING"), null, "");
        store.put("agentRun", obj("id", "old-run", "taskId", taskId, "taskExecutionId", "old-execution", "dispatchActionId", "old-action", "state", "RUNNING"));
        assertEquals(409, assertThrows(ApiException.class, () -> store.beginMcpOperation(taskId, obj("id", "old-mcp", "taskId", taskId, "agentRunId", "old-run"), 1, 1000)).status());
        store.editTask(taskId, task -> task.put("state", "PAUSED"), null, ""); queue.finish(str(action, "taskExecutionId")); store.editTask(taskId, task -> task.put("state", "RUNNING"), null, "");
        assertEquals(409, assertThrows(ApiException.class, () -> store.beginMcpOperation(taskId, obj("id", "late-mcp", "taskId", taskId), 1, 1000)).status());
        assertTrue(store.list("mcpOperation").isEmpty()); assertEquals(0, num(map(store.get("task", taskId), "mcpBudget"), "usedCalls", -1)); assertTrue(store.healthy());
    }

    @Test
    void awaitingAcceptanceRequiresRootReceiptBeforeAcceptOrExport() throws Exception {
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task();
        TaskDispatchQueue queue = new TaskDispatchQueue(store); Map<String, Object> action = queue.enqueue(taskId, "USER_APPROVAL", task -> map(task, "contract").put("confirmed", true));
        queue.claim(str(action, "id"), 1, ignored -> {});
        store.editTask(taskId, task -> task.putAll(obj("state", "AWAITING_ACCEPTANCE", "workspace", directory.resolve("任务 现场").toString(), "version", "v1",
            "verification", obj("status", "PASSED", "version", "v1"), "review", obj("status", "APPROVED", "version", "v1"))), null, "");
        WorkbenchController controller = new WorkbenchController(store, catalog, engine, runtime, new ResourceManager(), new PlanValidator(), new ObjectMapper(), null);
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "accept", obj())).status());
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "revalidate", obj())).status());
        assertEquals(409, assertThrows(ApiException.class, () -> controller.export(taskId)).status()); verify(runtime, never()).exportPatch(anyString());
        queue.finish(str(action, "taskExecutionId")); when(runtime.exportPatch(anyString())).thenReturn("");
        assertEquals(200, controller.export(taskId).getStatusCode().value());
        assertEquals("COMPLETED", str(engine.action(taskId, "accept", obj()), "state"));
        Map<String, Object> original = store.get("taskExecution", str(action, "taskExecutionId"));
        assertEquals("SUCCEEDED", str(original, "state")); assertEquals("AWAITING_ACCEPTANCE", str(original, "taskStateAtEnd"));
    }

    @Test
    void exportRejectsConcurrentTaskMutationInsteadOfMixedHistory() throws Exception {
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), new ObjectMapper()); String taskId = task();
        store.editTask(taskId, task -> task.putAll(obj("state", "PAUSED", "workspace", directory.resolve("任务 现场").toString(), "version", "v1")), null, "");
        WorkbenchController controller = new WorkbenchController(store, catalog, engine, runtime, new ResourceManager(), new PlanValidator(), new ObjectMapper(), null);
        when(runtime.exportPatch(anyString())).thenAnswer(ignored -> { store.editTask(taskId, task -> task.put("concurrentExportNote", "changed during patch extraction"), null, ""); return ""; });
        assertEquals(409, assertThrows(ApiException.class, () -> controller.export(taskId)).status());
        assertEquals("changed during patch extraction", str(store.get("task", taskId), "concurrentExportNote"));
    }
}
