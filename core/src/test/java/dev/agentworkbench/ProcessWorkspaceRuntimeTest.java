package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiFunction;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 原 SQLite 预算、实际 HTTP、独立文件工具和丢失回执共用真实领取，故障不会重放。
 */
class ProcessWorkspaceRuntimeTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path data = directory.resolve("core-data"), source = Files.createDirectories(directory.resolve("源项目 中文"));
        final ExecutionStore store = new ExecutionStore(mapper, data.toString());
        final ProcessWorkspaceRuntime runtime = new ProcessWorkspaceRuntime(mapper, data.toString());
        final WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, new PlanValidator());
        final ResourceManager resources = new ResourceManager();
        final AtomicInteger httpCalls = new AtomicInteger();
        final List<String> errors = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final AtomicBoolean expectDisconnect = new AtomicBoolean();
        final CountDownLatch entered = new CountDownLatch(1), released = new CountDownLatch(1);
        final java.util.concurrent.ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final String key = "worker-test-private-api-key";
        final LocalExecutionWorker worker;
        final Map<String, Object> route;
        final String workspace;
        final Map<String, Object> selection;
        final byte[] sourceIndex, sourceConfig;
        volatile BiFunction<Integer, Map<String, Object>, Map<String, Object>> response = (round, request) -> obj("role", "assistant", "content", "actual HTTP response");
        volatile Consumer<Map<String, Object>> afterObservation = ignored -> {};

        Fixture() throws Exception { this(null); }

        Fixture(String strategy) throws Exception {
            LocalExecutionWorker opened = null;
            try {
            Files.writeString(source.resolve("app.txt"), "base\n"); git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Worker Test");
            git(source, "config", "user.email", "worker@example.invalid"); git(source, "config", "core.autocrlf", "false"); git(source, "add", "."); git(source, "commit", "-m", "fixture");
            sourceIndex = Files.readAllBytes(source.resolve(".git/index")); sourceConfig = Files.readAllBytes(source.resolve(".git/config"));
            catalog.defaults(); worker = runtime.startWorker(store, catalog.nodes()); opened = worker;
            server.setExecutor(threads); server.createContext("/", exchange -> {
                try {
                    byte[] input = exchange.getRequestBody().readAllBytes(); Map<String, Object> request = mapper.readValue(input, new com.fasterxml.jackson.core.type.TypeReference<>() {});
                    String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(input));
                    Map<String, Object> original = store.list("modelRequest").stream().filter(value -> digest.equals(str(value, "inputDigest")) && "DISPATCHING".equals(str(value, "state"))).findFirst().orElseThrow();
                    assertEquals("Bearer " + key, exchange.getRequestHeaders().getFirst("Authorization")); assertEquals(worker.identity().get("id"), original.get("workerId"));
                    assertFalse(str(original, "workerLeaseId").isBlank()); assertTrue(num(store.get("agentRun", str(original, "agentRunId")), "calls", 0) > 0);
                    int round = httpCalls.incrementAndGet(); entered.countDown(); Map<String, Object> message = response.apply(round, request);
                    byte[] bytes = mapper.writeValueAsBytes(obj("id", "original-response-" + round, "model", "fixture", "choices", List.of(obj("message", message, "finish_reason", message.containsKey("tool_calls") ? "tool_calls" : "stop")), "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                    exchange.getResponseHeaders().set("x-request-id", "original-provider-" + round); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { if (!expectDisconnect.get()) errors.add(error.toString()); }
                finally { exchange.close(); }
            }); server.start();
            route = catalog.saveRoute(obj("name", "actual private route", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "apiKey", key, "enabled", true));
            Map<String, Object> project = catalog.importProject(obj("name", "worker fixture", "path", source.toString()));
            store.createTask(obj("id", "root", "projectId", project.get("id"), "state", "RUNNING", "title", "worker fixture", "goal", "original execution", "workspace", "", "evidence", new ArrayList<>(),
                "execution", catalog.nodes().snapshot(str(project, "id"), obj(), 1), "routeId", route.get("id"), "routeSnapshot", route,
                "contract", obj("scope", List.of("**"), "commands", List.of(), "acceptance", List.of("actual execution")),
                "budget", obj("maxCalls", 5, "reserveCalls", 1, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0)));
            String confirmedSource = catalog.nodes().sourcePath(store.get("task", "root"));
            selection = strategy == null ? Map.of() : runtime.selectWorkspace(confirmedSource, obj("strategy", strategy), false, 1);
            if (!selection.isEmpty()) store.editTask("root", task -> task.put("workspaceSelection", selection), null, "");
            String preparation = str(store.beginOperation("root", "prepare", "WORKSPACE", confirmedSource), "id"); worker.dispatch(preparation, confirmedSource, obj());
            Map<String, Object> prepared = selection.isEmpty() ? runtime.prepare(confirmedSource, "root", false) : runtime.prepareSelected(confirmedSource, "root", false, selection); worker.finish(preparation, "ACKNOWLEDGED", prepared);
            store.editTask("root", task -> task.putAll(prepared), null, ""); workspace = str(prepared, "workspace");
            } catch (Exception | Error failure) {
                if (opened != null) opened.close(); server.stop(0); threads.shutdownNow(); store.close(); throw failure;
            }
        }

        Map<String, Object> run() throws Exception {
            String runId = str(store.queueAgentRun("root", obj("id", "implement", "kind", "IMPLEMENT", "role", "developer"), workspace, runtime.fingerprint(workspace), false), "id");
            String operation = str(store.beginOperation("root", "implement", "MODEL", workspace, runId), "id"); worker.dispatch(operation, workspace, obj());
            AgentToolSession tools = new AgentToolSession() {
                @Override public List<Map<String, Object>> definitions() { return List.of(); }
                @Override public Map<String, Object> permissions() { return obj("capabilities", List.of("read", "write", "search"), "readScopeLayers", List.of(List.of("**")), "writeScopeLayers", List.of(List.of("**"))); }
                @Override public AutoCloseable acquireModel() {
                    ResourceManager.Lease lease = resources.acquire(Map.of("model", 1, "process", 1), cancelled::get); store.agentRunWaiting(runId, "RUNNING"); return lease;
                }
                @Override public AutoCloseable acquireTool() { return resources.acquire(Map.of("tool", 1, "process", 1), cancelled::get); }
                @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { throw new IllegalArgumentException("fixture has no team tools"); }
            };
            var trace = new RequestObservation(store, catalog, "root", runId, false, cancelled::get, new AtomicBoolean(), () -> {}, tools, 0);
            ModelCallObserver observer = new ModelCallObserver() {
                @Override public boolean begin(Map<String, Object> metadata) { return trace.begin(metadata); }
                @Override public void end(Map<String, Object> observation) { trace.end(observation); afterObservation.accept(observation); }
                @Override public AgentToolSession tools() { return tools; }
                @Override public String lastRequestId() { return trace.lastRequestId(); }
            };
            try {
                Map<String, Object> result = runtime.run(route, key, workspace, "private prompt " + key, false, List.of("**"), cancelled::get, ignored -> {}, observer);
                worker.evidence("root", operation, obj("id", id(), "stage", "implement", "kind", "AGENT", "status", "SUCCEEDED".equals(str(result, "status")) ? "PASSED" : str(result, "status"), "output", catalog.redact(str(result, "output"))));
                store.finishAgentRun(runId, "SUCCEEDED".equals(str(result, "status")) ? "SUCCEEDED" : "UNKNOWN", "actual returned result"); return result;
            } finally { worker.settleRun(runId); }
        }
        @Override public void close() throws Exception { released.countDown(); worker.close(); server.stop(0); threads.shutdownNow(); store.close(); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void actualWorkerPreparesTheFrozenSelectionAndOnlyWritesItsOwnedWorkspace(String strategy) throws Exception {
        try (var fixture = new Fixture(strategy)) {
            Map<String, Object> task = fixture.store.get("task", "root"), ownership = map(task, "workspaceOwnership");
            assertEquals(strategy, ownership.get("strategy")); assertEquals(fixture.selection, map(task, "workspaceSelection"));
            Map<String, Object> prepare = fixture.store.list("operation").getFirst();
            assertEquals(fixture.selection, map(prepare, "workspaceSelection")); assertEquals(fixture.selection, map(fixture.store.get("workerLease", str(prepare, "workerLeaseId")), "workspaceSelection"));
            assertEquals("ACKNOWLEDGED", prepare.get("state")); assertEquals(((Number) fixture.worker.identity().get("pid")).longValue(), ((Number) prepare.get("executorPid")).longValue());
            if ("WORKTREE".equals(strategy)) { assertTrue(Files.isRegularFile(Path.of(fixture.workspace).resolve(".git"))); assertEquals(fixture.source.resolve(".git").toRealPath().toString(), ownership.get("commonDirectory")); }
            else { assertTrue(Files.isDirectory(Path.of(fixture.workspace).resolve(".git"))); assertNotEquals(fixture.source.resolve(".git").toRealPath().toString(), ownership.get("commonDirectory")); }
            fixture.response = (round, request) -> round == 1 ? obj("role", "assistant", "content", null, "tool_calls", List.of(obj("id", "owned-write", "type", "function", "function", obj("name", "write_file", "arguments", "{\"path\":\"app.txt\",\"content\":\"owned task write\\n\"}"))))
                : obj("role", "assistant", "content", "owned execution complete");
            assertEquals("SUCCEEDED", fixture.run().get("status")); assertEquals("owned task write\n", Files.readString(Path.of(fixture.workspace).resolve("app.txt")));
            assertEquals("base\n", Files.readString(fixture.source.resolve("app.txt"))); assertArrayEquals(fixture.sourceIndex, Files.readAllBytes(fixture.source.resolve(".git/index"))); assertArrayEquals(fixture.sourceConfig, Files.readAllBytes(fixture.source.resolve(".git/config")));
            Map<String, Object> modelLease = fixture.store.workerLeases("", "root", "").stream().filter(lease -> "MODEL".equals(str(lease, "operationType"))).findFirst().orElseThrow();
            assertEquals(ownership, map(modelLease, "workspaceOwnership")); assertEquals(2, fixture.httpCalls.get()); assertEquals(2, num(map(fixture.store.get("task", "root"), "budget"), "usedCalls", 0));
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.runtime.prepareSelected(fixture.source.toString(), "other-task", false, fixture.selection)).status());
            assertFalse(Files.exists(fixture.data.resolve("workspaces/other-task/work"))); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @Test
    void coreCommitsEachOriginalRequestBeforeHttpAndChildWritesOnlyTheTaskWorkspace() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.response = (round, request) -> round == 1 ? obj("role", "assistant", "content", null, "tool_calls", List.of(obj("id", "original-write-tool", "type", "function", "function", obj("name", "write_file", "arguments", "{\"path\":\"app.txt\",\"content\":\"written by actual worker\\n\"}"))))
                : obj("role", "assistant", "content", "completed " + fixture.key);
            Map<String, Object> result = fixture.run(); assertEquals("SUCCEEDED", result.get("status")); assertEquals(2, fixture.httpCalls.get());
            assertEquals("base\n", Files.readString(fixture.source.resolve("app.txt"))); assertEquals("written by actual worker\n", Files.readString(Path.of(fixture.workspace).resolve("app.txt")));
            List<Map<String, Object>> requests = fixture.store.list("modelRequest"); assertEquals(2, requests.size()); assertTrue(requests.stream().allMatch(request -> "SUCCEEDED".equals(str(request, "state")) && !str(request, "id").isBlank()));
            assertEquals(2, num(map(fixture.store.get("task", "root"), "budget"), "usedCalls", 0)); assertEquals(0, fixture.resources.activeModels()); assertTrue(fixture.resources.snapshot().values().stream().allMatch(value -> value == 0));
            Map<String, Object> evidence = fixture.store.list("evidence").getFirst(); assertEquals("LOCAL_PROCESS", evidence.get("deployment"));
            assertEquals(((Number) fixture.worker.identity().get("pid")).longValue(), ((Number) evidence.get("executorPid")).longValue()); assertNotEquals(ProcessHandle.current().pid(), ((Number) evidence.get("executorPid")).longValue());
            assertFalse(requests.toString().contains(fixture.key)); assertFalse(evidence.toString().contains(fixture.key)); assertTrue(str(evidence, "output").contains("[REDACTED]"));
            assertTrue(fixture.store.workerLeases("", "root", "").stream().allMatch(lease -> !WorkerLedger.publicLease(lease).containsKey("claimDigest"))); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @Test
    void persistenceFailurePreventsActualHttpAndRestartDoesNotReplayTheOldClaim() throws Exception {
        try (var fixture = new Fixture()) {
            try (var database = DriverManager.getConnection("jdbc:sqlite:" + fixture.data.resolve("workbench.sqlite")); var sql = database.createStatement()) {
                sql.execute("CREATE TRIGGER reject_worker_request BEFORE INSERT ON entities WHEN new.kind='modelRequest' BEGIN SELECT RAISE(ABORT,'private injected persistence failure'); END");
            }
            assertThrows(RuntimeException.class, fixture::run); assertEquals(0, fixture.httpCalls.get()); assertFalse(fixture.store.healthy()); fixture.close();
            try (var reopened = new ExecutionStore(mapper, fixture.data.toString())) {
                assertEquals(0, num(map(reopened.get("task", "root"), "budget"), "usedCalls", -1)); assertTrue(reopened.list("modelRequest").isEmpty());
                var runtime = new ProcessWorkspaceRuntime(mapper, fixture.data.toString()); var catalog = new WorkbenchCatalog(reopened, runtime, new PlanValidator());
                try (var replacement = runtime.startWorker(reopened, catalog.nodes())) {
                    assertEquals("UNKNOWN", reopened.get("task", "root").get("state")); assertFalse(replacement.current("root"));
                    assertEquals(1, reopened.workerLeases("", "root", "UNKNOWN").size()); assertEquals(0, fixture.httpCalls.get());
                }
            }
        }
    }

    @Test
    void processLossKeepsTheOriginalInFlightRequestUnknownAndNeverStartsAnotherWorker() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.expectDisconnect.set(true); fixture.response = (round, request) -> { try { fixture.released.await(15, TimeUnit.SECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); } return obj("role", "assistant", "content", "late external response"); };
            var result = fixture.threads.submit(fixture::run); assertTrue(fixture.entered.await(10, TimeUnit.SECONDS)); long workerPid = ((Number) fixture.worker.identity().get("pid")).longValue();
            assertEquals(1, fixture.resources.activeModels()); ProcessHandle original = ProcessHandle.of(workerPid).orElseThrow(); original.destroyForcibly();
            assertThrows(ExecutionException.class, () -> result.get(10, TimeUnit.SECONDS)); assertFalse(original.isAlive());
            assertEquals("UNKNOWN", fixture.store.get("task", "root").get("state")); assertEquals("UNKNOWN", fixture.store.list("modelRequest").getFirst().get("state"));
            assertEquals(1, fixture.httpCalls.get()); assertEquals(1, num(map(fixture.store.get("task", "root"), "budget"), "usedCalls", 0)); assertEquals(0, fixture.resources.activeModels());
            assertEquals(1, fixture.store.workerLeases("", "root", "UNKNOWN").size()); assertEquals(1, fixture.store.list("worker").size());
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.runtime.startWorker(fixture.store, fixture.catalog.nodes())).status());
            assertEquals(workerPid, ((Number) fixture.worker.identity().get("pid")).longValue()); assertTrue(Boolean.TRUE.equals(map(fixture.store.list("worker").getFirst(), "processStopFacts").get("treeStopped")));
        }
    }

    @Test
    void receivedModelSuccessSurvivesLossOfItsCallbackReceiptWhileExecutionStaysUnknown() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.afterObservation = observation -> { if ("SUCCEEDED".equals(str(observation, "state"))) fixture.runtime.fence("FIXTURE_DROP_ORIGINAL_MODEL_END_RECEIPT"); };
            assertThrows(RuntimeException.class, fixture::run); assertEquals(1, fixture.httpCalls.get());
            Map<String, Object> request = fixture.store.list("modelRequest").getFirst(); assertEquals("SUCCEEDED", request.get("state")); assertEquals("original-response-1", request.get("responseId"));
            assertEquals("original-provider-1", request.get("providerRequestId")); assertEquals("UNKNOWN", fixture.store.get("task", "root").get("state"));
            assertEquals(1, fixture.store.workerLeases("", "root", "UNKNOWN").size()); assertEquals(0, fixture.resources.activeModels()); assertTrue(fixture.errors.isEmpty(), fixture.errors.toString());
        }
    }

    @Test
    void coreTimerCannotManufactureAChildHeartbeatOrExtendItsOriginalLease() throws Exception {
        try (var fixture = new Fixture()) {
            await(() -> "CHILD_PIPE".equals(str(fixture.store.list("worker").getFirst(), "heartbeatSource")), 8);
            Map<String, Object> before = fixture.store.list("worker").getFirst(); fixture.worker.heartbeat(); fixture.worker.heartbeat();
            Map<String, Object> after = fixture.store.list("worker").getFirst(); assertEquals(before.get("lastHeartbeatSequence"), after.get("lastHeartbeatSequence")); assertEquals(before.get("heartbeatAt"), after.get("heartbeatAt"));
            assertEquals(before.get("expiresAt"), after.get("expiresAt")); assertEquals("CHILD_PIPE", after.get("heartbeatSource"));
        }
    }

    private static void git(Path workspace, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments)); Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); assertEquals(0, process.waitFor(), output);
    }
    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds); while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50); assertTrue(condition.getAsBoolean());
    }
}
