package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.mcp.McpManager;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.runtime.ModelCallObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
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
    void requestOutputRedactsCredentialsBeforeTruncatingAndRestartKeepsUnknown() throws Exception {
        String secret = "trace-secret-0123456789-abcdef";
        route = catalog.saveRoute(obj("id", route.get("id"), "name", "traced", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:18901/v1", "apiKey", secret));
        doAnswer(invocation -> {
            ModelCallObserver observed = invocation.getArgument(8);
            if (!observed.begin(obj("inputBytes", 20, "inputDigest", "digest", "inputPreview", "x".repeat(5990) + secret))) return obj("status", "FAILED", "exitCode", -1);
            observed.end(obj("state", "SUCCEEDED", "outputPreview", "x".repeat(5990) + secret, "outputTruncated", true, "providerRequestId", secret, "usage", obj("inputTokens", 0, "outputTokens", "UNKNOWN")));
            return success("{\"approved\":true,\"issues\":[]}");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String taskId = str(task(16, 5), "id"); engine.action(taskId, "approve", obj());
        await(taskId, Set.of("AWAITING_ACCEPTANCE"));
        var requests = objects(store.modelRequestPage(taskId, "", 0, 30, "", "").get("requests"));
        assertEquals(3, requests.size());
        assertTrue(requests.stream().allMatch(request -> "SUCCEEDED".equals(str(request, "state"))));
        assertTrue(requests.stream().allMatch(request -> "[REDACTED]".equals(str(request, "providerRequestId"))));
        assertTrue(requests.stream().allMatch(request -> str(request, "outputPreview").length() == 6000 && !str(request, "outputPreview").contains("trace-secret")));
        assertTrue(requests.stream().allMatch(request -> str(request, "inputPreview").length() == 6000 && !str(request, "inputPreview").contains("trace-secret")));
        assertTrue(requests.stream().allMatch(request -> !bool(request, "inputTruncated", true) && !bool(request, "outputTruncated", true)));
        String runId = str(store.queueAgentRun(taskId, obj("id", "restart", "role", "lead"), "workspace", "version", false), "id");
        store.beginOperation(taskId, "restart", "MODEL", "workspace", runId);
        String pending = store.beginModelRequest(taskId, runId, false, Map.of());
        engine.recover();
        assertEquals("UNKNOWN", str(store.get("modelRequest", pending), "state"));
        assertEquals("SUCCEEDED", str(store.get("modelRequest", str(requests.getFirst(), "id")), "state"));
    }

    @Test
    void mcpReauthorizationUsesExpectedVersionAndPreservesBudget() {
        Map<String, Object> created = task(16, 5); String taskId = str(created, "id");
        Map<String, Object> updated = engine.action(taskId, "mcp-authorization", obj("expectedVersion", 1));
        assertEquals("DRAFT", str(updated, "state"));
        assertEquals(2, num(map(updated, "mcpAuthorization"), "version", 0));
        assertEquals(1, objects(updated.get("mcpAuthorizationHistory")).size());
        assertEquals(8, num(map(updated, "mcpBudget"), "maxCalls", 0));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "mcp-authorization", obj("expectedVersion", 1))).status());
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void confirmedMcpActionFlowsFromTaskThroughEngineAndPersistsOnce(boolean loseResponse) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        AtomicInteger externalCalls = new AtomicInteger();
        ResourceManager resources = new ResourceManager();
        AtomicReference<Map<String, Integer>> externalResources = new AtomicReference<>();
        HttpServer mcpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpServer.createContext("/mcp", exchange -> {
            try {
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                String method = str(request, "method");
                if (method.startsWith("notifications/")) { exchange.sendResponseHeaders(202, -1); return; }
                if ("tools/call".equals(method) && loseResponse) {
                    externalCalls.incrementAndGet(); externalResources.set(resources.snapshot()); exchange.close(); return;
                }
                Map<String, Object> result = switch (method) {
                    case "initialize" -> obj("protocolVersion", "2025-06-18", "capabilities", obj("tools", obj()), "serverInfo", obj("name", "engine-fixture", "version", "1"));
                    case "tools/list" -> obj("tools", List.of(obj("name", "write_note", "inputSchema", obj("type", "object", "properties", obj("text", obj("type", "string")), "required", List.of("text"), "additionalProperties", false))));
                    case "tools/call" -> {
                        externalCalls.incrementAndGet(); externalResources.set(resources.snapshot());
                        yield obj("content", List.of(obj("type", "text", "text", "written")), "isError", false);
                    }
                    default -> obj();
                };
                byte[] payload = mapper.writeValueAsBytes(obj("jsonrpc", "2.0", "id", request.get("id"), "result", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, payload.length); exchange.getResponseBody().write(payload);
            } finally { exchange.close(); }
        });
        mcpServer.start();
        try {
            engine.shutdown();
            McpManager manager = new McpManager(store, mapper, catalog, resources);
            Map<String, Object> saved = manager.save(obj("name", "engine fixture", "transport", "HTTP", "url", "http://127.0.0.1:" + mcpServer.getAddress().getPort() + "/mcp",
                "enabled", true, "projectIds", List.of(str(project, "id")), "allowedTools", List.of("write_note")));
            Map<String, Object> discovery = manager.probe(str(saved, "id"));
            Map<String, Object> binding = obj("serverId", saved.get("id"), "name", "write_note", "configVersion", saved.get("version"),
                "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", obj("text", "confirmed"), "timeoutMs", 5000);
            engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper, manager);
            when(runtime.runWithMcp(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any(), anyList(), any())).thenAnswer(invocation -> {
                BooleanSupplier acquire = invocation.getArgument(8);
                if (!acquire.getAsBoolean()) return obj("status", "FAILED", "exitCode", 1, "output", "budget stopped");
                WorkspaceRuntime.FixedToolInvoker invoker = invocation.getArgument(10);
                List<Map<String, Object>> bindings = invocation.getArgument(9);
                Map<String, Object> dispatched = invoker.invoke(str(bindings.getFirst(), "alias"));
                if (loseResponse) {
                    assertEquals("UNKNOWN", str(dispatched, "state"));
                    return obj("status", "UNKNOWN", "exitCode", -1, "output", "MCP response was not confirmed");
                }
                assertEquals("ACKNOWLEDGED", str(dispatched, "state"));
                assertEquals(0, resources.activeModels());
                assertTrue(acquire.getAsBoolean());
                assertEquals(1, resources.activeModels(), "The next model request must reacquire capacity");
                var duplicate = invoker.invoke(str(bindings.getFirst(), "alias"));
                assertTrue(bool(duplicate, "deduplicated", false));
                assertTrue(acquire.getAsBoolean());
                assertEquals(1, resources.activeModels());
                version.set("version-" + changes.incrementAndGet());
                return success("implementation stage completed");
            });
            Map<String, Object> created = catalog.createTask(obj("projectId", project.get("id"), "title", "MCP engine path", "goal", "run one confirmed external action",
                "mode", "LEAD", "routeId", route.get("id"), "mcpBindings", List.of(binding), "mcpBudget", obj("maxCalls", 2, "maxDurationMs", 10000),
                "contract", obj("scope", List.of("**"), "acceptance", List.of("action is acknowledged"), "commands", List.of("node check.js")),
                "budget", obj("maxCalls", 16, "reserveCalls", 5)));
            String taskId = str(created, "id"); engine.action(taskId, "approve", obj());
            Map<String, Object> completed = await(taskId, Set.of("AWAITING_ACCEPTANCE", "FAILED", "UNKNOWN", "WAITING_INPUT"));
            assertEquals(loseResponse ? "UNKNOWN" : "AWAITING_ACCEPTANCE", str(completed, "state"), completed::toString);
            assertEquals(1, externalCalls.get());
            assertEquals(0, externalResources.get().getOrDefault("model", 0));
            assertEquals(0, externalResources.get().getOrDefault("route:" + route.get("id"), 0));
            assertEquals(0, externalResources.get().getOrDefault("process", 0));
            assertEquals(1, externalResources.get().getOrDefault("tool", 0));
            assertTrue(resources.snapshot().values().stream().allMatch(count -> count == 0));
            assertEquals(1, num(map(completed, "mcpBudget"), "usedCalls", 0));
            assertEquals(5000, num(map(completed, "mcpBudget"), "committedDurationMs", 0));
            assertEquals(loseResponse ? "UNKNOWN" : "ACKNOWLEDGED", str(store.list("mcpOperation").getFirst(), "state"));
            if (loseResponse) {
                assertThrows(ApiException.class, () -> engine.action(taskId, "resume", obj()));
                assertThrows(ApiException.class, () -> engine.action(taskId, "accept", obj()));
                engine.shutdown();
                engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper, manager);
                var reconciled = engine.action(taskId, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "Fixture action observed once; transport stopped"));
                assertEquals("PAUSED", str(reconciled, "state"));
                assertEquals("RECONCILED", str(store.list("mcpOperation").getFirst(), "state"));
                engine.action(taskId, "resume", obj());
                assertEquals("UNKNOWN", str(await(taskId, Set.of("UNKNOWN", "FAILED", "AWAITING_ACCEPTANCE")), "state"));
                assertEquals(1, externalCalls.get(), "Reconciliation and resume must not replay the external action");
                assertEquals(1, num(map(store.get("task", taskId), "mcpBudget"), "usedCalls", -1));
                assertEquals("RECONCILED", str(store.list("mcpOperation").getFirst(), "state"));
            }
        } finally { mcpServer.stop(0); }
    }

    @Test
    void realStageContractRequiresVerificationReviewAndHumanAcceptance() throws Exception {
        String id = str(task(16, 5), "id"); engine.action(id, "approve", obj());
        Map<String, Object> completed = await(id, Set.of("AWAITING_ACCEPTANCE", "FAILED", "UNKNOWN", "WAITING_INPUT"));
        assertEquals("AWAITING_ACCEPTANCE", str(completed, "state"), () -> completed.toString());
        assertEquals("PASSED", str(map(completed, "verification"), "status")); assertEquals("APPROVED", str(map(completed, "review"), "status"));
        assertTrue(objects(completed.get("evidence")).stream().anyMatch(item -> "BASELINE".equals(str(item, "kind"))));
        var runs = store.list("agentRun");
        assertEquals(3, runs.size());
        assertTrue(runs.stream().allMatch(run -> "SUCCEEDED".equals(str(run, "state")) && id.equals(str(run, "rootTaskId"))));
        assertEquals(num(map(completed, "budget"), "usedCalls", -1), runs.stream().mapToInt(run -> num(run, "calls", 0)).sum());
        assertTrue(runs.stream().allMatch(run -> !str(run, "evidenceId").isBlank() && !str(run, "operationId").isBlank()));
        assertEquals(1, runs.stream().filter(run -> bool(run, "reserved", false)).count());
        verify(runtime).reviewCopy(anyString(), eq(id), eq("version-2"));
        assertEquals("UNDETERMINED", str(completed, "outcome"));
        var accepted = engine.action(id, "accept", obj()); assertEquals("COMPLETED", str(accepted, "state")); assertEquals("SUCCESS", str(accepted, "outcome"));
    }

    @Test
    void rejectedActionWaitsForInputWithoutRunningLaterStages() throws Exception {
        AtomicInteger modelCalls = new AtomicInteger();
        doAnswer(invocation -> {
            BooleanSupplier acquire = invocation.getArgument(8); assertTrue(acquire.getAsBoolean()); modelCalls.incrementAndGet();
            return invocation.<String>getArgument(3).contains("kind=IMPLEMENT")
                ? obj("status", "WAITING_INPUT", "exitCode", -1, "output", "MCP definition changed; confirm authorization")
                : success("analysis completed");
        }).when(runtime).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        String taskId = str(task(16, 5), "id"); engine.action(taskId, "approve", obj());
        var blocked = await(taskId, Set.of("WAITING_INPUT", "FAILED", "UNKNOWN", "AWAITING_ACCEPTANCE"));
        assertEquals("WAITING_INPUT", str(blocked, "state"));
        assertTrue(str(blocked, "error").contains("confirm authorization"));
        assertEquals(2, modelCalls.get());
        assertEquals(2, num(map(blocked, "budget"), "usedCalls", -1));
        assertTrue(objects(blocked.get("evidence")).stream().noneMatch(item -> Set.of("REVIEW", "VERIFICATION").contains(str(item, "kind"))));
        assertTrue(store.list("operation").stream().allMatch(item -> "ACKNOWLEDGED".equals(str(item, "state"))));
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
        Map<String, Object> reconciled = null;
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (reconciled == null && System.nanoTime() < deadline) {
            try { reconciled = engine.action(id, "reconcile", obj("processesStopped", true, "sideEffectsChecked", true, "note", "operator verified process tree and filesystem")); }
            catch (ApiException exception) {
                if (exception.status() != 409 || !exception.getMessage().contains("停止或收尾中")) throw exception;
                assertEquals("UNKNOWN", str(store.get("task", id), "state"));
                Thread.sleep(10);
            }
        }
        assertNotNull(reconciled, "人工核对须等待执行生命周期停止后完成");
        assertEquals(1, store.list("agentRun").size());
        assertEquals("UNKNOWN", str(store.list("agentRun").getFirst(), "state"));
        assertTrue(str(store.list("agentRun").getFirst(), "reason").contains("connection lost"));

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
        assertEquals("CANCELLED", str(store.list("agentRun").getFirst(), "state"));
        assertEquals(1, num(store.list("agentRun").getFirst(), "calls", 0));
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
        assertTrue(store.list("agentRun").stream().anyMatch(run -> "BUDGET_EXHAUSTED".equals(str(run, "state")) && num(run, "calls", -1) == 0));
    }

    @Test
    void restartMarksDispatchedOperationsUnknown() throws Exception {
        String id = str(task(16, 5), "id");
        store.editTask(id, task -> task.put("state", "RUNNING"), "TEST", "simulate crash");
        var run = store.queueAgentRun(id, obj("id", "implement", "kind", "IMPLEMENT", "role", "developer"), "workspace", "version-1", false);
        var operation = store.beginOperation(id, "implement", "MODEL", "workspace", str(run, "id"));
        store.markOperation(str(operation, "id"), "DISPATCHED", obj());
        engine.recover();
        assertEquals("UNKNOWN", str(store.get("task", id), "state"));
        assertEquals("UNKNOWN", str(store.get("operation", str(operation, "id")), "state"));
        assertEquals("UNKNOWN", str(store.get("agentRun", str(run, "id")), "state"));
        verify(runtime, never()).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
    }

    @Test
    void cancellingResourceWaitRecordsNoModelDispatchOrBudgetCharge() throws Exception {
        engine.shutdown();
        ResourceManager resources = new ResourceManager();
        engine = new WorkbenchEngine(store, catalog, runtime, resources, new ObjectMapper());
        try (var held = resources.acquire(Map.of("model", 2), () -> false)) {
            String taskId = str(task(16, 5), "id"); engine.action(taskId, "approve", obj());
            await(taskId, Set.of("WAITING_RESOURCE"));
            assertEquals("WAITING_RESOURCE", str(store.list("agentRun").getFirst(), "state"));
            engine.action(taskId, "cancel", obj());
            var stopped = await(taskId, Set.of("CANCELLED", "UNKNOWN"));
            assertEquals("CANCELLED", str(stopped, "state"));
            assertEquals(0, num(map(stopped, "budget"), "usedCalls", -1));
            assertEquals("CANCELLED", str(store.list("agentRun").getFirst(), "state"));
            assertFalse(store.list("agentRun").getFirst().containsKey("operationId"));
            verify(runtime, never()).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        }
    }

    @Test
    void stopRequestPublicationCannotOverwriteAFastCancellationReceipt() throws Exception {
        engine.shutdown(); store.close();
        ObjectMapper mapper = new ObjectMapper(); CountDownLatch stoppedReceipt = new CountDownLatch(1);
        java.util.concurrent.atomic.AtomicBoolean receivedBeforePublication = new java.util.concurrent.atomic.AtomicBoolean();
        store = new ExecutionStore(mapper, directory.resolve("cancel-race").toString()) {
            @Override public Map<String, Object> editTask(String id, java.util.function.Consumer<Map<String, Object>> edit, String type, String message) {
                if ("STOP_REQUESTED".equals(type)) {
                    try { receivedBeforePublication.set(stoppedReceipt.await(500, TimeUnit.MILLISECONDS)); }
                    catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                }
                Map<String, Object> value = super.editTask(id, edit, type, message);
                if ("STOPPED".equals(type)) stoppedReceipt.countDown();
                return value;
            }
        };
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        project = catalog.importProject(obj("name", "cancel race", "path", directory.resolve("source").toString()));
        route = catalog.saveRoute(obj("name", "local race endpoint", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:18901/v1", "enabled", true));
        ResourceManager resources = new ResourceManager(); engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper);
        try (var held = resources.acquire(Map.of("model", 2), () -> false)) {
            String taskId = str(task(16, 5), "id"); engine.action(taskId, "approve", obj()); await(taskId, Set.of("WAITING_RESOURCE"));
            engine.action(taskId, "cancel", obj()); Map<String, Object> result = await(taskId, Set.of("CANCELLED"));
            assertFalse(receivedBeforePublication.get());
            assertFalse(java.time.Instant.parse(str(result, "stoppedAt")).isBefore(java.time.Instant.parse(str(result, "stopRequestedAt"))));
            assertEquals(0, num(map(result, "budget"), "usedCalls", -1)); assertEquals("CANCELLED", store.list("agentRun").getFirst().get("state"));
            verify(runtime, never()).run(anyMap(), anyString(), anyString(), anyString(), anyBoolean(), anyList(), any(), any(), any());
        }
    }

    @Test
    void repeatReviewKeepsEarlierRunIdentityAndIncrementsAttempt() throws Exception {
        String taskId = str(task(16, 5), "id"); engine.action(taskId, "approve", obj());
        await(taskId, Set.of("AWAITING_ACCEPTANCE"));
        var oldReview = store.list("agentRun").stream().filter(run -> "REVIEW".equals(str(run, "kind"))).findFirst().orElseThrow();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (true) {
            engine.action(taskId, "revalidate", obj());
            try { engine.action(taskId, "resume", obj()); break; }
            catch (ApiException exception) {
                if (!exception.getMessage().contains("停止或执行中") || System.nanoTime() > deadline) throw exception;
                Thread.sleep(10);
            }
        }
        await(taskId, Set.of("AWAITING_ACCEPTANCE"));
        var reviews = store.list("agentRun").stream().filter(run -> "REVIEW".equals(str(run, "kind"))).toList();
        assertEquals(2, reviews.size());
        assertEquals(2, num(reviews.getFirst(), "attempt", 0));
        assertEquals(oldReview, store.get("agentRun", str(oldReview, "id")));
        assertEquals("SUCCEEDED", str(reviews.getFirst(), "state"));
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
