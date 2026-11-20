package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.mcp.McpAccessRegistry;
import dev.agentworkbench.mcp.StageInputMcpController;
import dev.agentworkbench.mcp.StageInputMcpRegistry;
import java.io.ByteArrayInputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

/**
 * 实际 Workflow Worker 的在途调用才能读取阶段投影，令牌不进入模型、事件或导出。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class WorkflowStageMcpExecutionTest {
    private static final String DESKTOP_TOKEN = "stage-input-desktop-token-more-than-24-characters";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private final CapturingRegistry registry = new CapturingRegistry();
    private final McpAccessRegistry projectGrants = new McpAccessRegistry();
    private final CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    private final List<String> modelBodies = new CopyOnWriteArrayList<>(), errors = new CopyOnWriteArrayList<>();
    private final AtomicReference<Map<String, Object>> heldRequest = new AtomicReference<>();
    private ExecutionStore store;
    private ProcessWorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private ResourceManager resources;
    private HttpServer server;
    private ExecutorService httpWorkers;
    private MockMvc mvc;
    private Path source;
    private Map<String, Object> project, route;

    @BeforeEach
    void open() throws Exception {
        source = Files.createDirectories(directory.resolve("中文 stage input source"));
        Files.writeString(source.resolve("source.txt"), "base\n");
        Files.writeString(source.resolve("verify.mjs"), "import assert from 'node:assert/strict'; import {readFileSync} from 'node:fs'; assert.equal(readFileSync('source.txt','utf8'), 'base\\n'); console.log('stage input source verified');\n");
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Stage Input Fixture");
        git(source, "config", "user.email", "stage-input@example.invalid"); git(source, "config", "core.autocrlf", "false");
        git(source, "add", "."); git(source, "commit", "-m", "original stage input fixture");
        store = new ExecutionStore(mapper, directory.resolve("data").toString());
        runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString(), registry);
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults(); resources = new ResourceManager();
        engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper);
        project = catalog.importProject(obj("name", "Stage input fixture", "path", source.toString()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpWorkers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            try {
                byte[] bytes = exchange.getRequestBody().readAllBytes(); modelBodies.add(new String(bytes, StandardCharsets.UTF_8));
                Map<String, Object> payload = mapper.readValue(bytes, new TypeReference<>() { });
                String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
                Map<String, Object> request = store.list("modelRequest").stream()
                    .filter(value -> digest.equals(str(value, "inputDigest")) && "DISPATCHING".equals(str(value, "state"))).findFirst().orElseThrow();
                String prompt = objects(payload.get("messages")).stream().filter(message -> Set.of("system", "user").contains(str(message, "role")))
                    .map(message -> str(message, "content")).reduce("", (left, right) -> left + "\n" + right);
                if (prompt.contains("\n节点：inspect\n")) {
                    assertEquals("LOCAL_PROCESS", engine.workerIdentity().get("deployment"));
                    assertNotEquals(ProcessHandle.current().pid(), ((Number) engine.workerIdentity().get("pid")).longValue());
                    assertEquals(engine.workerIdentity().get("id"), request.get("workerId")); assertFalse(str(request, "workerLeaseId").isBlank());
                    assertFalse(objects(payload.get("tools")).stream().anyMatch(tool -> "stage_input".equals(str(map(tool, "function"), "name"))));
                    heldRequest.set(request); entered.countDown();
                    assertTrue(release.await(90, TimeUnit.SECONDS), "The test must release the original model response");
                }
                String answer = prompt.contains("阶段 kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "Actual stage input evidence";
                byte[] response = mapper.writeValueAsBytes(obj("id", "stage-input-fixture", "model", "fixture", "choices",
                    List.of(obj("message", obj("role", "assistant", "content", answer), "finish_reason", "stop")),
                    "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } catch (Throwable failure) {
                errors.add(failure.getClass().getSimpleName());
                try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { }
            } finally { exchange.close(); }
        });
        server.start();
        route = catalog.saveRoute(obj("name", "Stage input model", "engine", "API", "model", "fixture", "provider", "fixture", "enabled", true,
            "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1"));
        catalog.saveAgentDefinition(obj("id", "stage-reader", "name", "Stage reader", "role", "researcher", "instructions", "Retain approved stage input evidence.",
            "capabilities", List.of("read", "search"), "scope", List.of("**"), "maxCalls", 2, "enabled", true, "routeId", ""));
        mvc = MockMvcBuilders.standaloneSetup(new StageInputMcpController(registry, mapper)).setControllerAdvice(new ApiErrors())
            .addFilters(new LocalAccessFilter(DESKTOP_TOKEN, projectGrants, registry)).build();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown(); if (engine != null) engine.shutdown(); if (server != null) server.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow(); if (store != null) store.close();
    }

    @Test
    void originalWorkerCanReadItsStageWhileHeldAndCompletionRevokesWithoutLeakingTheToken() throws Exception {
        String taskId = beginHeldTask("ORIGINAL_STAGE_INPUT_GOAL"); var handle = onlyHandle();
        assertDoesNotThrow(() -> registry.readers.getFirst().get());
        Map<String, Object> direct = registry.read(bearer(handle), handle.id());
        assertEquals(taskId, map(direct, "identity").get("taskId"));
        assertEquals(heldRequest.get().get("workflowNodeAttemptId"), map(direct, "identity").get("workflowNodeAttemptId"));
        assertEquals("ORIGINAL_STAGE_INPUT_GOAL", map(direct, "inputs").get("goal"));
        assertEquals(Set.of("identity", "node", "inputs", "projectRules"), direct.keySet());
        MvcResult initialized = rpc(handle, "initialize", obj("protocolVersion", "2025-03-26"));
        assertEquals(200, initialized.getResponse().getStatus()); assertEquals("2025-03-26", map(response(initialized), "result").get("protocolVersion"));
        assertEquals(202, request(handle.endpoint(), bearer(handle), obj("jsonrpc", "2.0", "method", "notifications/initialized")).getResponse().getStatus());
        assertEquals(200, rpc(handle, "tools/list", obj()).getResponse().getStatus());
        MvcResult called = rpc(handle, "tools/call", obj("name", "stage_input", "arguments", obj(), "_meta", obj("progressToken", "fixture-progress")));
        assertEquals(200, called.getResponse().getStatus()); Map<String, Object> result = map(response(called), "result");
        Map<String, Object> payload = mapper.readValue(str(objects(result.get("content")).getFirst(), "text"), new TypeReference<>() { });
        assertEquals(direct, payload); assertEquals(1, registry.handles.size());
        assertEquals(401, request(handle.endpoint(), "Bearer " + DESKTOP_TOKEN, ping()).getResponse().getStatus());
        assertEquals(401, request(handle.endpoint(), "Bearer " + str(projectGrants.issue(str(project, "id")), "token"), ping()).getResponse().getStatus());
        assertEquals(401, request("/api/state", bearer(handle), ping()).getResponse().getStatus());
        assertFalse(mapper.writeValueAsString(registry.identities.getFirst()).contains(handle.token()));
        release.countDown(); awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE")); assertDenied(handle);
        assertEquals(1, store.list("workflowNodeAttempt").size()); assertEquals("SUCCEEDED", store.list("workflowNodeAttempt").getFirst().get("state"));
        assertNoSecretInPublishedEvidence(taskId, handle); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void changedInputsRevokeTheOriginalGrantAndRestoringThemCannotReviveIt() throws Exception {
        String taskId = beginHeldTask("FIXED_STAGE_INPUT_GOAL"); var handle = onlyHandle();
        Map<String, Object> original = store.list("workflowNodeAttempt").getFirst();
        Map<String, Object> changed = mapper.convertValue(original, new TypeReference<>() { });
        map(changed, "inputs").put("goal", "CHANGED_STAGE_INPUT_GOAL"); store.put("workflowNodeAttempt", changed); assertDenied(handle);
        store.put("workflowNodeAttempt", original); assertDenied(handle);
        release.countDown(); awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(1, registry.handles.size()); assertNoSecretInPublishedEvidence(taskId, handle);
    }

    @Test
    void revokedActorCannotReadAnAlreadyIssuedStageGrant() throws Exception {
        String taskId = beginHeldTask("REVOKED_STAGE_ACTOR"); var handle = onlyHandle();
        Map<String, Object> original = store.get("agentDefinition", "stage-reader");
        Map<String, Object> revoked = new LinkedHashMap<>(original); revoked.put("enabled", false); store.put("agentDefinition", revoked);
        assertDenied(handle); store.put("agentDefinition", original); assertDenied(handle); release.countDown();
        awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE", "UNKNOWN", "PAUSED", "WAITING_INPUT", "FAILED"));
        assertEquals(1, store.list("workflowNodeAttempt").size()); assertEquals(1, registry.handles.size());
    }

    @Test
    void cancellationRevokesTheGrantBeforeTheHeldProviderResponseReturns() throws Exception {
        String taskId = beginHeldTask("CANCELLED_STAGE_INPUT"); var handle = onlyHandle();
        assertEquals(200, rpc(handle, "ping", obj()).getResponse().getStatus()); engine.action(taskId, "pause", obj()); assertDenied(handle);
        assertEquals(1, release.getCount()); release.countDown(); awaitTask(taskId, Set.of("UNKNOWN", "PAUSED"));
        assertDenied(handle); assertEquals(1, store.list("workflowNodeAttempt").size());
    }

    private String beginHeldTask(String goal) throws Exception {
        Map<String, Object> node = obj("id", "inspect", "kind", "INVESTIGATE", "title", "Inspect original stage", "role", "researcher", "goal", "Read fixed approved stage input",
            "definitionId", "stage-reader", "definitionVersion", 1, "dependencies", List.of(), "inputs", List.of("goal", "contract", "context", "baseline"),
            "outputs", List.of("inspect:result"), "scope", List.of("**"), "permissions", List.of("read", "search"), "maxCalls", 2, "timeoutSeconds", 120,
            "acceptance", List.of("Original input is retained"), "termination", "Completion or original root cancellation");
        Map<String, Object> published = catalog.workflows().save(obj("name", goal, "projectId", project.get("id"), "dsl",
            obj("dslVersion", 1, "inputs", List.of("goal", "contract", "context", "baseline"), "maxParallel", 1, "nodes", List.of(node))));
        Map<String, Object> version = map(published, "version");
        Map<String, Object> task = catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", goal, "goal", goal, "mode", "LEAD",
            "contract", obj("scope", List.of("**"), "commands", List.of("node verify.mjs"), "acceptance", List.of("Source and original stage input are verified")),
            "budget", obj("maxCalls", 16, "reserveCalls", 4), "collaboration", obj("enabled", false), "workflow",
            obj("definitionId", version.get("definitionId"), "version", version.get("version"), "digest", version.get("digest"),
                "rootRouteVersion", route.get("version"), "allowedRouteVersions", obj())));
        String taskId = str(task, "id"); engine.action(taskId, "approve", obj());
        assertTrue(entered.await(90, TimeUnit.SECONDS), "The original Workflow request must reach actual HTTP before bridge access");
        assertNotNull(heldRequest.get()); assertEquals("DISPATCHING", heldRequest.get().get("state"));
        assertEquals(taskId, heldRequest.get().get("taskId")); assertEquals(1, registry.handles.size()); return taskId;
    }

    private Map<String, Object> awaitTask(String taskId, Set<String> states) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(180);
        while (System.nanoTime() < deadline) {
            Map<String, Object> task = store.get("task", taskId);
            if (states.contains(str(task, "state"))) {
                try { engine.requireExecutionSettled(taskId); return task; }
                catch (ApiException exception) { if (exception.status() != 409) throw exception; }
            }
            if (Set.of("FAILED", "UNKNOWN", "WAITING_INPUT").contains(str(task, "state")) && !states.contains(str(task, "state"))) {
                fail("Unexpected Workflow state: " + str(task, "state") + " / " + str(task, "error") + " / fixture=" + errors);
            }
            Thread.sleep(30);
        }
        fail("Original Workflow did not reach a settled state"); return obj();
    }

    private void assertDenied(StageInputMcpRegistry.Handle handle) throws Exception {
        int status = rpc(handle, "tools/call", obj("name", "stage_input", "arguments", obj())).getResponse().getStatus();
        assertTrue(status == 401 || status == 403, "Original stage input must be denied");
        assertFalse(registry.permits(bearer(handle), handle.endpoint(), "POST"));
    }

    private void assertNoSecretInPublishedEvidence(String taskId, StageInputMcpRegistry.Handle handle) throws Exception {
        for (String body : modelBodies) assertFalse(body.contains(handle.token()));
        long cursor = 0;
        while (true) {
            Map<String, Object> page = store.events(taskId, cursor, 200); assertFalse(mapper.writeValueAsString(page).contains(handle.token()));
            if (!bool(page, "hasMore", false)) break; cursor = ((Number) page.get("cursor")).longValue();
        }
        var controller = new WorkbenchController(store, catalog, engine, runtime, resources, new PlanValidator(), mapper, null);
        byte[] archive = controller.export(taskId).getBody(); assertNotNull(archive);
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) {
            while (zip.getNextEntry() != null) {
                assertFalse(new String(zip.readAllBytes(), StandardCharsets.UTF_8).contains(handle.token())); zip.closeEntry();
            }
        }
    }

    private StageInputMcpRegistry.Handle onlyHandle() { assertEquals(1, registry.handles.size()); return registry.handles.getFirst(); }
    private String bearer(StageInputMcpRegistry.Handle handle) { return "Bearer " + handle.token(); }
    private Map<String, Object> ping() { return obj("jsonrpc", "2.0", "id", 1, "method", "ping"); }
    private MvcResult rpc(StageInputMcpRegistry.Handle handle, String method, Object params) throws Exception {
        return request(handle.endpoint(), bearer(handle), obj("jsonrpc", "2.0", "id", 1, "method", method, "params", params));
    }
    private MvcResult request(String endpoint, String authorization, Map<String, Object> body) throws Exception {
        return mvc.perform(post(endpoint).with(request -> { request.setRemoteAddr("127.0.0.1"); return request; })
            .header("Authorization", authorization).header("MCP-Protocol-Version", "2025-03-26").contentType("application/json").content(mapper.writeValueAsBytes(body))).andReturn();
    }
    private Map<String, Object> response(MvcResult result) throws Exception {
        return mapper.readValue(result.getResponse().getContentAsByteArray(), new TypeReference<>() { });
    }

    private static void git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, child.waitFor(), output);
    }

    private static final class CapturingRegistry extends StageInputMcpRegistry {
        private final List<Handle> handles = new CopyOnWriteArrayList<>();
        private final List<Map<String, Object>> identities = new CopyOnWriteArrayList<>();
        private final List<Supplier<Map<String, Object>>> readers = new CopyOnWriteArrayList<>();
        @Override
        public Handle open(Map<String, Object> identity, Supplier<Map<String, Object>> reader, Duration lifetime) {
            Handle handle = super.open(identity, reader, lifetime); identities.add(new LinkedHashMap<>(identity)); readers.add(reader); handles.add(handle); return handle;
        }
    }
}
