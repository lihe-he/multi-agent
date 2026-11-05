package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class WorkbenchEngineTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private WorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private Map<String, Object> project;
    private Map<String, Object> route;
    private final AtomicReference<String> version = new AtomicReference<>("version-1");
    private final AtomicInteger changes = new AtomicInteger(1);

    @BeforeEach
    void prepare() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        store = new ExecutionStore(mapper, directory.resolve("data").toString());
        runtime = mock(WorkspaceRuntime.class);
        Path source = Files.createDirectories(directory.resolve("source"));
        Path workspace = Files.createDirectories(directory.resolve("workspace"));
        when(runtime.inspect(anyString())).thenReturn(obj("path", source.toString(), "head", "baseline-head", "branch", "main", "dirty", false, "fileCount", 1, "files", List.of("app.js"), "modules", List.of("app")));
        when(runtime.prepare(anyString(), anyString(), anyBoolean())).thenReturn(obj("workspace", workspace.toString(), "baseline", "baseline-head", "version", "version-1"));
        when(runtime.fingerprint(anyString())).thenAnswer(ignored -> version.get());
        when(runtime.diff(anyString())).thenReturn("diff --git a/app.js b/app.js\n+working\n");
        when(runtime.reviewCopy(anyString(), anyString(), anyString())).thenReturn(directory.resolve("review").toString());
        when(runtime.verify(anyString(), anyString(), any(), any())).thenReturn(success("check passed"));
        normalModel();
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), mapper);
        project = catalog.importProject(obj("name", "sample", "path", source.toString()));
        route = catalog.saveRoute(obj("name", "local endpoint", "engine", "API", "model", "fixture", "provider", "fixture", "baseUrl", "http://127.0.0.1:18901/v1", "enabled", true));
    }

    @AfterEach
    void close() throws Exception { if (engine != null) { engine.shutdown(); } if (store != null) { store.close(); } }

    private void normalModel() throws Exception {
        when(runtime.run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any())).thenAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8);
            if (!acquire.getAsBoolean()) { return obj("status", "FAILED", "exitCode", 1, "output", "budget stopped"); }
            String prompt = invocation.getArgument(3);
            if (prompt.contains("kind=IMPLEMENT") || prompt.contains("kind=REPAIR")) { version.set("version-" + changes.incrementAndGet()); }
            return success(prompt.contains("kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "stage evidence");
        });
    }

    private Map<String, Object> task(int maximum, int reserve) {
        return catalog.createTask(obj("projectId", project.get("id"), "title", "make sample work", "goal", "implement sample behavior", "mode", "LEAD", "routeId", route.get("id"),
            "contract", obj("scope", List.of("**"), "acceptance", List.of("sample works"), "commands", List.of("node check.js")), "budget", obj("maxCalls", maximum, "reserveCalls", reserve)));
    }

    private Map<String, Object> await(String id, Set<String> states) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (System.nanoTime() < deadline) {
            Map<String, Object> task = store.get("task", id);
            if (states.contains(str(task, "state"))) { return task; }
            Thread.sleep(20);
        }
        fail("Task did not reach expected state: " + store.get("task", id)); return obj();
    }

    private static Map<String, Object> success(String output) { return obj("status", "SUCCEEDED", "exitCode", 0, "output", output, "usage", obj("cost", "UNKNOWN")); }

    @Test
    void realStageContractRequiresVerificationReviewAndHumanAcceptance() throws Exception {
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        Map<String, Object> completed = await(id, Set.of("AWAITING_ACCEPTANCE", "FAILED", "UNKNOWN", "WAITING_INPUT"));
        assertEquals("AWAITING_ACCEPTANCE", str(completed, "state"), () -> completed.toString());
        assertEquals("PASSED", str(map(completed, "verification"), "status")); assertEquals("APPROVED", str(map(completed, "review"), "status"));
        assertTrue(objects(completed.get("evidence")).stream().anyMatch(item -> "BASELINE".equals(str(item, "kind"))));
        verify(runtime).reviewCopy(anyString(), eq(id), eq("version-2"));
        assertEquals("UNDETERMINED", str(completed, "outcome"));
        var accepted = engine.action(id, "accept", obj()); assertEquals("COMPLETED", str(accepted, "state")); assertEquals("SUCCESS", str(accepted, "outcome"));
    }

    @Test
    void changedContentCannotReusePriorAcceptanceEvidence() throws Exception {
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj()); await(id, Set.of("AWAITING_ACCEPTANCE"));
        version.set("manual-edit-after-review");
        ApiException rejected = assertThrows(ApiException.class, () -> engine.action(id, "accept", obj()));
        assertEquals(409, rejected.status()); assertEquals("AWAITING_ACCEPTANCE", str(store.get("task", id), "state"));
    }

    @Test
    void unknownVerificationCannotPassEvenWithZeroExitCode() throws Exception {
        when(runtime.verify(anyString(), anyString(), any(), any())).thenReturn(obj("status", "UNKNOWN", "exitCode", 0, "output", "child process still alive"));
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        Map<String, Object> result = await(id, Set.of("UNKNOWN", "FAILED")); assertEquals("UNKNOWN", str(result, "state"));
        assertEquals("UNKNOWN", str(objects(result.get("evidence")).getFirst(), "status"));
        assertThrows(ApiException.class, () -> engine.action(id, "resume", obj()));
        verify(runtime, never()).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
    }

    @Test
    void modelCrashRequiresReconciliationInsteadOfAutomaticReplay() throws Exception {
        doThrow(new IllegalStateException("connection lost after send")).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        assertEquals("UNKNOWN", str(await(id, Set.of("UNKNOWN")), "state"));
        assertThrows(ApiException.class, () -> engine.action(id, "reconcile", obj()));
        var reconciled = engine.action(id, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "operator verified process tree and filesystem"));
        assertEquals("PAUSED", str(reconciled, "state"));
        assertTrue(store.list("operation").stream().filter(operation -> id.equals(str(operation, "taskId"))).noneMatch(operation -> "DISPATCHED".equals(str(operation, "state"))));
    }

    @Test
    void cancellationPropagatesToRunningModelAndPreservesResults() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        doAnswer(invocation -> {
            BooleanSupplier cancelled = invocation.getArgument(6); BooleanSupplier acquire = invocation.getArgument(8);
            acquire.getAsBoolean(); entered.countDown();
            while (!cancelled.getAsBoolean()) { Thread.sleep(10); }
            return obj("status", "CANCELLED", "exitCode", -1, "output", "stopped");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj()); assertTrue(entered.await(3, TimeUnit.SECONDS));
        engine.action(id, "cancel", obj());
        var cancelled = await(id, Set.of("CANCELLED", "UNKNOWN")); assertEquals("CANCELLED", str(cancelled, "state"));
        assertEquals(1, num(map(cancelled, "budget"), "usedCalls", -1));
        assertTrue(objects(cancelled.get("evidence")).size() >= 2);
    }

    @Test
    void malformedReviewDoesNotCountAsApprovalAndRepairsAreBounded() throws Exception {
        doAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); assertTrue(acquire.getAsBoolean());
            String prompt = invocation.getArgument(3);
            if (prompt.contains("kind=IMPLEMENT") || prompt.contains("kind=REPAIR")) { version.set("version-" + changes.incrementAndGet()); }
            return success(prompt.contains("kind=REVIEW") ? "Looks approved, everything is fine" : "done");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        var result = await(id, Set.of("WAITING_INPUT", "FAILED", "UNKNOWN"));
        assertEquals("WAITING_INPUT", str(result, "state")); assertEquals(2, num(result, "repairRounds", -1));
        assertEquals("CHANGES_REQUESTED", str(map(result, "review"), "status"));
        assertEquals(3, objects(result.get("evidence")).stream().filter(item -> "REVIEW".equals(str(item, "kind"))).count());
    }

    @Test
    void exhaustedDevelopmentBudgetStillUsesReservedVerificationWithoutExceedingRoot() throws Exception {
        String id = str(task(2, 1), "id"); engine.action(id, "approve", obj());
        var result = await(id, Set.of("WAITING_INPUT", "FAILED", "UNKNOWN"));
        assertEquals("WAITING_INPUT", str(result, "state"), () -> result.toString());
        assertEquals(2, num(map(result, "budget"), "usedCalls", -1)); assertEquals(1, num(map(result, "budget"), "usedReservedCalls", -1));
        assertTrue(objects(result.get("evidence")).stream().anyMatch(item -> "VERIFICATION".equals(str(item, "kind"))));
        assertNotEquals("SUCCESS", str(result, "outcome"));
    }

    @Test
    void restartMarksDispatchedOperationsUnknown() throws Exception {
        String id = str(task(16, 5), "id");
        store.editTask(id, task -> task.put("state", "RUNNING"), "TEST", "simulate crash");
        var operation = store.beginOperation(id, "implement", "MODEL", "workspace");
        store.markOperation(str(operation, "id"), "DISPATCHED", obj());
        engine.recover();
        assertEquals("UNKNOWN", str(store.get("task", id), "state"));
        assertEquals("UNKNOWN", str(store.get("operation", str(operation, "id")), "state"));
        verify(runtime, never()).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
    }

    @Test
    void credentialsStaySessionOnlyAndArePinnedToRouteVersion() {
        var first = catalog.saveRoute(obj("id", route.get("id"), "name", "api", "engine", "API", "model", "m1", "baseUrl", "https://provider.example/v1", "apiKey", "old-secret"));
        var second = catalog.saveRoute(obj("id", route.get("id"), "name", "api", "engine", "API", "model", "m2", "baseUrl", "https://another.example/v1", "apiKey", "new-secret"));
        assertEquals("old-secret", catalog.credential(str(route, "id"), num(first, "version", -1)));
        assertEquals("new-secret", catalog.credential(str(route, "id"), num(second, "version", -1)));
        assertFalse(store.get("route", str(route, "id")).toString().contains("secret"));
        assertEquals("[REDACTED] [REDACTED]", catalog.redact("old-secret new-secret"));
    }

    @Test
    void changingEndpointOrProviderDoesNotInheritCredential() {
        var first = catalog.saveRoute(obj("id", route.get("id"), "name", "api", "engine", "API", "model", "m1", "provider", "provider-a", "baseUrl", "https://provider.example/v1", "apiKey", "private-key"));
        var renamed = catalog.saveRoute(obj("id", route.get("id"), "name", "renamed", "engine", "API", "model", "m2", "provider", "provider-a", "baseUrl", "https://provider.example/v1"));
        assertEquals("private-key", catalog.credential(str(route, "id"), num(renamed, "version", -1)));
        var moved = catalog.saveRoute(obj("id", route.get("id"), "name", "moved", "engine", "API", "model", "m2", "provider", "provider-b", "baseUrl", "https://other.example/v1"));
        assertEquals("", catalog.credential(str(route, "id"), num(moved, "version", -1)));
        assertEquals("private-key", catalog.credential(str(route, "id"), num(first, "version", -1)));
        assertFalse(bool(moved, "hasApiKey", true)); assertTrue(moved.containsKey("credentialNotice"));
    }

    @Test
    void nonWindowsProjectPathsRemainCaseSensitive() {
        assertFalse(WorkbenchCatalog.sameProjectPath("/repo/Case", "/repo/case", false));
        assertTrue(WorkbenchCatalog.sameProjectPath("C:\\repo\\Case", "c:\\repo\\case", true));
        assertTrue(WorkbenchCatalog.sameProjectPath("/repo/Case", "/repo/Case", false));
    }

    @Test
    void sameVersionBaselineFailureCannotBeErasedByLaterPassingVerification() throws Exception {
        AtomicInteger checks = new AtomicInteger();
        when(runtime.verify(anyString(), anyString(), any(), any())).thenAnswer(invocation -> checks.getAndIncrement() == 0
            ? obj("status", "FAILED", "exitCode", 1, "output", "flaky baseline assertion") : success("later happened to pass"));
        doAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); assertTrue(acquire.getAsBoolean());
            return success(((String) invocation.getArgument(3)).contains("kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "no content change");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        var result = await(id, Set.of("WAITING_INPUT", "FAILED", "UNKNOWN", "AWAITING_ACCEPTANCE"));
        assertEquals("WAITING_INPUT", str(result, "state")); assertEquals("FAILED", str(map(result, "verification"), "status"));
        assertEquals(2, num(result, "repairRounds", -1));
        assertTrue(objects(result.get("evidence")).stream().anyMatch(item -> "BASELINE".equals(str(item, "kind")) && "FAILED".equals(str(item, "status"))));
        assertTrue(objects(result.get("evidence")).stream().anyMatch(item -> "UNSTABLE_SAME_VERSION_BASELINE_FAILED".equals(str(item, "attribution"))));
        assertFalse(objects(result.get("evidence")).stream().anyMatch(item -> "REVIEW".equals(str(item, "kind"))));
        assertThrows(ApiException.class, () -> engine.action(id, "accept", obj()));
    }

    @Test
    void changingIndependentReviewCopyInvalidatesApproval() throws Exception {
        java.util.concurrent.atomic.AtomicBoolean reviewChanged = new java.util.concurrent.atomic.AtomicBoolean();
        when(runtime.fingerprint(anyString())).thenAnswer(invocation -> ((String) invocation.getArgument(0)).equals(directory.resolve("review").toString()) && reviewChanged.get() ? "tampered-review-content" : version.get());
        doAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); assertTrue(acquire.getAsBoolean());
            String prompt = invocation.getArgument(3);
            if (prompt.contains("kind=IMPLEMENT")) { version.set("version-2"); }
            if (prompt.contains("kind=REVIEW")) { reviewChanged.set(true); return success("{\"approved\":true,\"issues\":[]}"); }
            return success("stage completed");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        var result = await(id, Set.of("FAILED", "UNKNOWN", "AWAITING_ACCEPTANCE"));
        assertEquals("FAILED", str(result, "state")); assertEquals("INVALIDATED", str(map(result, "review"), "status"));
        assertTrue(objects(result.get("evidence")).stream().anyMatch(item -> "REVIEW_WORKSPACE_CHANGED".equals(str(item, "integrity")) && "FAILED".equals(str(item, "status"))));
        assertEquals("version-2", version.get()); assertThrows(ApiException.class, () -> engine.action(id, "accept", obj()));
    }

    @Test
    void revokedMemoryAfterReviewBlocksAcceptanceAndKeepsHistory() throws Exception {
        var memory = catalog.saveMemory(obj("projectId", project.get("id"), "kind", "RULE", "content", "Original confirmed rule", "source", "operator"));
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        var ready = await(id, Set.of("AWAITING_ACCEPTANCE")); int evidenceCount = objects(ready.get("evidence")).size();
        catalog.editMemory(str(memory, "id"), obj("status", "DISABLED"));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(id, "accept", obj())).status());
        var revalidating = engine.action(id, "revalidate", obj());
        assertEquals("PAUSED", str(revalidating, "state")); assertEquals(evidenceCount, objects(revalidating.get("evidence")).size());
        assertTrue(objects(revalidating.get("context")).stream().noneMatch(item -> str(memory, "id").equals(str(item, "id"))));
        assertFalse(strings(revalidating.get("invalidatedEvidence")).isEmpty());
        assertTrue(objects(revalidating.get("evidence")).stream().anyMatch(item -> "REVIEW".equals(str(item, "kind")) && "PASSED".equals(str(item, "status"))));
    }

    @Test
    void editedMemoryAfterReviewRequiresNewContextVersion() throws Exception {
        var memory = catalog.saveMemory(obj("projectId", project.get("id"), "kind", "DECISION", "content", "Original decision", "source", "operator"));
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj()); await(id, Set.of("AWAITING_ACCEPTANCE"));
        catalog.editMemory(str(memory, "id"), obj("content", "Corrected decision"));
        assertThrows(ApiException.class, () -> engine.action(id, "accept", obj()));
        var refreshed = engine.action(id, "revalidate", obj());
        var context = objects(refreshed.get("context")).stream().filter(item -> str(memory, "id").equals(str(item, "id"))).findFirst().orElseThrow();
        assertEquals(2, num(context, "version", -1)); assertEquals("Corrected decision", str(context, "content"));
        assertTrue(objects(refreshed.get("plan")).stream().filter(stage -> "analysis".equals(str(stage, "id"))).allMatch(stage -> "PENDING".equals(str(stage, "state"))));
    }

    @Test
    void memoryRevocationDuringFinalVersionCheckCannotRaceAcceptance() throws Exception {
        var memory = catalog.saveMemory(obj("projectId", project.get("id"), "kind", "FACT", "content", "Observed fact", "source", "operator"));
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj()); var ready = await(id, Set.of("AWAITING_ACCEPTANCE"));
        when(runtime.fingerprint(str(ready, "workspace"))).thenAnswer(invocation -> {
            catalog.editMemory(str(memory, "id"), obj("status", "DISABLED")); return version.get();
        });
        assertThrows(ApiException.class, () -> engine.action(id, "accept", obj()));
        assertEquals("AWAITING_ACCEPTANCE", str(store.get("task", id), "state"));
        assertFalse(store.get("task", id).containsKey("acceptanceRecord"));
    }
}
