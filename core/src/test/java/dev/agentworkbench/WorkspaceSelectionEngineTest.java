package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实仓库和 SQLite 核对启动选择、根队列与准备租约；模型夹具只记录预算领取。
 */
class WorkspaceSelectionEngineTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicInteger prepared = new AtomicInteger(), calls = new AtomicInteger();
    private Path source, data;
    private ExecutionStore store;
    private WorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private Map<String, Object> project, route;
    private ExecutorService controlled;
    private CountDownLatch release;

    @BeforeEach
    void open() throws Exception {
        data = directory.resolve("data"); source = Files.createDirectories(directory.resolve("中文 源仓库")).toRealPath();
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Fixture"); git(source, "config", "user.email", "fixture@localhost");
        git(source, "config", "core.autocrlf", "false"); Files.writeString(source.resolve("AGENTS.md"), "main rules\n"); Files.writeString(source.resolve("app.txt"), "main content\n");
        git(source, "add", "."); git(source, "commit", "-m", "main fixture"); git(source, "checkout", "-b", "feature");
        Files.writeString(source.resolve("AGENTS.md"), "feature rules\n"); Files.writeString(source.resolve("app.txt"), "feature content\n");
        git(source, "add", "."); git(source, "commit", "-m", "feature fixture"); git(source, "checkout", "main");
        store = new ExecutionStore(mapper, data.toString()); runtime = spy(new WorkspaceRuntime(mapper, data.toString()));
        doAnswer(invocation -> { prepared.incrementAndGet(); return invocation.callRealMethod(); }).when(runtime).prepareSelected(anyString(), anyString(), anyBoolean(), anyMap());
        doReturn(result("verified")).when(runtime).verify(anyString(), anyString(), any(), any());
        doAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); if (!acquire.getAsBoolean()) return obj("status", "FAILED", "exitCode", -1);
            calls.incrementAndGet(); return result(((String) invocation.getArgument(3)).contains("kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "observed stage");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        project = catalog.importProject(obj("name", "selected source", "path", source.toString()));
        route = catalog.saveRoute(obj("name", "budget fixture", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:18905/v1", "enabled", true));
    }

    @AfterEach
    void close() throws Exception {
        if (controlled != null) controlled.shutdownNow(); if (release != null) release.countDown();
        if (engine != null) engine.shutdown(); if (store != null) store.close();
    }

    private static Map<String, Object> result(String output) { return obj("status", "SUCCEEDED", "exitCode", 0, "output", output); }

    private String task(String strategy, String ref, boolean includeChanges) {
        return str(catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "Fixed workspace", "goal", "Preserve selected source and original execution",
            "mode", "LEAD", "workspaceSelection", obj("strategy", strategy, "ref", ref), "includeChanges", includeChanges,
            "contract", obj("scope", List.of("**"), "acceptance", List.of("fixed commit"), "commands", List.of("node verify.mjs")),
            "budget", obj("maxCalls", 12, "reserveCalls", 4))), "id");
    }

    private void engine() { engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), mapper); }

    private void blockExecutor() throws Exception {
        controlled = Executors.newSingleThreadExecutor(); release = new CountDownLatch(1); CountDownLatch entered = new CountDownLatch(1);
        controlled.submit(() -> { entered.countDown(); try { release.await(); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } });
        assertTrue(entered.await(3, TimeUnit.SECONDS)); engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), mapper, null, controlled);
    }

    @Test
    void explicitReselectionKeepsOldRulesAttemptSelectionAndCumulativeBudget() throws Exception {
        blockExecutor(); String taskId = task("WORKTREE", "refs/heads/feature", false);
        Map<String, Object> selection = map(store.get("task", taskId), "workspaceSelection");
        assertEquals("feature rules\n", objects(store.get("task", taskId).get("context")).stream().filter(item -> "project-rules".equals(str(item, "id"))).findFirst().orElseThrow().get("content"));
        Map<String, Object> options = catalog.nodes().workspaceOptions(str(project, "repositoryBindingId"));
        assertEquals(project.get("repositoryBindingId"), options.get("repositoryBindingId")); assertEquals(1, options.get("bindingVersion"));
        store.editTask(taskId, value -> map(value, "budget").putAll(obj("usedCalls", 3, "usedDevelopmentCalls", 2, "usedReservedCalls", 1)), null, "");
        Map<String, Object> budget = new java.util.LinkedHashMap<>(map(store.get("task", taskId), "budget"));
        engine.action(taskId, "approve", obj()); Map<String, Object> original = store.get("task", taskId);
        String executionId = str(original, "taskExecutionId"), actionId = str(original, "dispatchActionId");
        assertEquals(selection, map(store.get("taskExecution", executionId), "workspaceSelection")); assertEquals(selection, map(map(store.get("dispatchAction", actionId), "authorization"), "workspaceSelection"));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "workspace-selection", obj("expectedVersion", 1, "workspaceSelection", obj("strategy", "CLONE")))).status());
        engine.action(taskId, "pause", obj()); engine.action(taskId, "workspace-selection", obj("expectedVersion", 1, "workspaceSelection", obj("strategy", "CLONE", "ref", "HEAD")));
        Map<String, Object> changed = store.get("task", taskId); assertEquals("PAUSED", changed.get("state")); assertEquals(2, num(map(changed, "workspaceSelection"), "version", 0));
        assertEquals(selection, map(objects(changed.get("workspaceSelectionHistory")).getFirst(), "selection")); assertEquals(budget, map(changed, "budget"));
        assertEquals("feature rules\n", objects(objects(changed.get("workspaceSelectionHistory")).getFirst().get("rules")).getFirst().get("content"));
        assertEquals(selection, map(store.get("taskExecution", executionId), "workspaceSelection")); assertEquals("WITHDRAWN", store.get("dispatchAction", actionId).get("state"));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "workspace-selection", obj("expectedVersion", 1, "workspaceSelection", obj("strategy", "WORKTREE")))).status());
        engine.action(taskId, "resume", obj()); Map<String, Object> second = store.get("taskExecution", str(store.get("task", taskId), "taskExecutionId"));
        assertEquals(2, num(second, "attempt", 0)); assertEquals(map(changed, "workspaceSelection"), map(second, "workspaceSelection")); assertEquals(selection, map(store.get("taskExecution", executionId), "workspaceSelection"));
        engine.action(taskId, "cancel", obj()); release.countDown(); assertEquals(0, prepared.get()); assertEquals(0, calls.get());
    }

    @ParameterizedTest
    @ValueSource(strings = {"HEAD", "CONTENT", "INDEX"})
    void sourceChangeAfterApprovalSettlesOriginalAttemptWithoutAWorkerOperation(String change) throws Exception {
        blockExecutor(); if ("CONTENT".equals(change)) Files.writeString(source.resolve("app.txt"), "dirty before\n");
        String taskId = task("WORKTREE", "HEAD", "CONTENT".equals(change)); engine.action(taskId, "approve", obj());
        String executionId = str(store.get("task", taskId), "taskExecutionId"), actionId = str(store.get("task", taskId), "dispatchActionId");
        switch (change) {
            case "HEAD" -> { Files.writeString(source.resolve("new.txt"), "moved source\n"); git(source, "add", "."); git(source, "commit", "-m", "moved source"); }
            case "CONTENT" -> Files.writeString(source.resolve("app.txt"), "dirty after\n");
            case "INDEX" -> git(source, "update-index", "--assume-unchanged", "app.txt");
            default -> fail("invalid fixture");
        }
        release.countDown(); await(() -> "WAITING_INPUT".equals(str(store.get("taskExecution", executionId), "state")));
        assertEquals("WAITING_INPUT", store.get("task", taskId).get("state")); assertEquals("SETTLED", store.get("dispatchAction", actionId).get("state"));
        assertEquals(1, num(store.get("dispatchAction", actionId), "dispatchCount", 0)); assertEquals(1, store.list("taskExecution").size());
        assertEquals(0, prepared.get()); assertEquals(0, calls.get()); assertTrue(store.list("operation").isEmpty()); assertTrue(store.list("workerLease").isEmpty());
        assertEquals(0, num(map(store.get("task", taskId), "budget"), "usedCalls", -1)); assertFalse(Files.exists(data.resolve("workspaces").resolve(taskId).resolve("work")));
        assertFalse(Files.exists(data.resolve("workspaces").resolve(taskId).resolve("selection.json"))); assertEquals(1, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size());
    }

    @Test
    void pendingActionRejectsASelectionChangedOutsideItsFrozenAuthorization() throws Exception {
        blockExecutor(); String taskId = task("CLONE", "HEAD", false); engine.action(taskId, "approve", obj());
        String actionId = str(store.get("task", taskId), "dispatchActionId"); Map<String, Object> original = map(store.get("task", taskId), "workspaceSelection");
        store.editTask(taskId, value -> map(value, "workspaceSelection").put("strategy", "WORKTREE"), null, ""); release.countDown();
        await(() -> str(store.get("dispatchAction", actionId), "waitReason").contains("冻结授权"));
        assertEquals("PENDING", store.get("dispatchAction", actionId).get("state")); assertEquals(0, num(store.get("dispatchAction", actionId), "dispatchCount", -1));
        assertEquals(original, map(map(store.get("dispatchAction", actionId), "authorization"), "workspaceSelection")); assertTrue(store.list("operation").isEmpty()); assertTrue(store.list("workerLease").isEmpty());
        engine.action(taskId, "cancel", obj()); assertEquals(0, prepared.get()); assertEquals(0, calls.get());
    }

    @Test
    void readyPreparationWithMissingTaskReceiptKeepsItsSourceRulesAndOwnerAfterSourceMoves() throws Exception {
        engine(); String taskId = task("WORKTREE", "HEAD", false); Map<String, Object> original = store.get("task", taskId), selection = map(original, "workspaceSelection");
        Map<String, Object> ready = runtime.prepareSelected(source.toString(), taskId, false, selection);
        Files.writeString(source.resolve("AGENTS.md"), "new source rules\n"); git(source, "add", "."); git(source, "commit", "-m", "new source rules");
        Path other = directory.resolve("another source"); git(directory, "clone", "--", source.toString(), other.toString());
        Map<String, Object> binding = catalog.nodes().saveBinding(obj("projectId", project.get("id"), "executionNodeId", map(original, "execution").get("executionNodeId"), "name", "other", "path", other.toString(), "enabled", true));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "workspace-selection", obj("expectedVersion", 1, "workspaceSelection", obj("strategy", "CLONE")))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "execution-authorization", obj("expectedVersion", 1, "repositoryBindingId", binding.get("id")))).status());
        engine.action(taskId, "execution-authorization", obj("expectedVersion", 1));
        Map<String, Object> renewed = store.get("task", taskId); assertEquals(selection, map(renewed, "workspaceSelection")); assertEquals(original.get("context"), renewed.get("context"));
        engine.action(taskId, "approve", obj()); await(() -> "SUCCEEDED".equals(str(store.get("taskExecution", str(store.get("task", taskId), "taskExecutionId")), "state")));
        Map<String, Object> completed = store.get("task", taskId); assertTrue(bool(completed, "recoveredPreparation", false)); assertEquals(ready.get("workspaceOwnership"), completed.get("workspaceOwnership"));
        assertEquals(2, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size()); assertEquals(selection.get("baseline"), completed.get("baseline"));
        assertEquals("main rules\n", Files.readString(Path.of(str(completed, "workspace")).resolve("AGENTS.md"))); assertEquals(3, calls.get());
        assertEquals(ready.get("workspaceOwnership"), store.get("taskExecution", str(completed, "taskExecutionId")).get("workspaceOwnership"));
    }

    @Test
    void preparedWorkspaceAndUnsettledRootCannotChangeSelectionOrExecutionAuthorization() throws Exception {
        engine(); String taskId = task("WORKTREE", "HEAD", false); Map<String, Object> selection = map(store.get("task", taskId), "workspaceSelection");
        Map<String, Object> ready = runtime.prepareSelected(source.toString(), taskId, false, selection);
        store.editTask(taskId, value -> { value.putAll(ready); value.put("state", "PAUSED"); map(value, "contract").put("confirmed", true); }, null, "");
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "workspace-selection", obj("expectedVersion", 1, "workspaceSelection", obj("strategy", "CLONE")))).status());
        TaskDispatchQueue queue = new TaskDispatchQueue(store); Map<String, Object> action = queue.enqueue(taskId, "USER_RESUME", ignored -> {}); queue.claim(str(action, "id"), 1, ignored -> {});
        store.editTask(taskId, value -> value.put("state", "PAUSED"), null, "");
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "execution-authorization", obj("expectedVersion", 1))).status());
        assertEquals(selection, map(store.get("task", taskId), "workspaceSelection")); assertEquals(1, num(map(store.get("task", taskId), "execution"), "version", 0));
        queue.finish(str(action, "taskExecutionId")); assertEquals(0, calls.get());
    }

    @Test
    void preparationOperationAndWorkerLeaseKeepTheOriginalSelectionAndRejectBorrowing() throws Exception {
        engine(); String taskId = task("WORKTREE", "HEAD", false); TaskDispatchQueue queue = new TaskDispatchQueue(store);
        Map<String, Object> action = queue.enqueue(taskId, "USER_APPROVAL", value -> map(value, "contract").put("confirmed", true)); queue.claim(str(action, "id"), 1, ignored -> {});
        Map<String, Object> selection = map(store.get("task", taskId), "workspaceSelection"), operation = store.beginOperation(taskId, "prepare", "WORKSPACE", source.toString());
        WorkerLedger ledger = new WorkerLedger(store, catalog.nodes()); Map<String, Object> lease = ledger.claim(engine.workerIdentity(), "operation", str(operation, "id"), source.toString(), "original-token", obj(), Instant.now());
        assertEquals(selection, map(operation, "workspaceSelection")); assertEquals(selection, map(lease, "workspaceSelection")); assertEquals(str(action, "taskExecutionId"), lease.get("taskExecutionId"));
        Map<String, Object> wrong = store.beginOperation(taskId, "prepare", "WORKSPACE", source.toString()); map(wrong, "workspaceSelection").put("strategy", "CLONE"); store.put("operation", wrong);
        assertEquals(403, assertThrows(ApiException.class, () -> ledger.claim(engine.workerIdentity(), "operation", str(wrong, "id"), source.toString(), "wrong-token", obj(), Instant.now())).status());
        assertNull(store.workerLeaseForOperation("operation", str(wrong, "id")));
        Map<String, Object> stale = store.beginOperation(taskId, "prepare", "WORKSPACE", source.toString());
        store.editTask(taskId, value -> map(value, "workspaceSelection").put("strategy", "CLONE"), null, "");
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.claim(engine.workerIdentity(), "operation", str(stale, "id"), source.toString(), "stale-token", obj(), Instant.now())).status());
        assertNull(store.workerLeaseForOperation("operation", str(stale, "id"))); assertEquals(selection, map(lease, "workspaceSelection")); assertEquals(0, prepared.get());
        assertTrue(store.healthy());
    }

    private void await(BooleanSupplier condition) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
        while (System.nanoTime() < deadline) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        fail("workspace selection did not reach the expected root boundary");
    }

    private String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); throw new IllegalStateException("Fixture Git timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.exitValue(), output); return output;
    }
}
