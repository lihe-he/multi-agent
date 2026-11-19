package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用实际 Git、独立 Worker 和 HTTP 模型夹具核对动态 DAG、权限与原节点续跑。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class WorkflowExecutionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private ResourceManager resources;
    private HttpServer server;
    private ExecutorService httpWorkers;
    private Path source;
    private Map<String, Object> project;
    private Map<String, Object> rootRoute;
    private Map<String, Object> otherRoute;
    private final AtomicInteger calls = new AtomicInteger();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> observed = new CopyOnWriteArrayList<>();
    private final AtomicReference<Fixture> fixture = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @FunctionalInterface
    private interface Fixture { Map<String, Object> respond(String prompt, List<Map<String, Object>> results, List<String> names) throws Exception; }

    @BeforeEach
    void open() throws Exception {
        source = Files.createDirectories(directory.resolve("中文 Workflow source")); Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/source.txt"), "base\n"); Files.writeString(source.resolve("outside.txt"), "OUTSIDE_PRIVATE_VALUE\n");
        Files.writeString(source.resolve("verify.mjs"), "import assert from 'node:assert/strict'; import {readFileSync} from 'node:fs'; assert.match(readFileSync('src/source.txt','utf8'), /^(base|done)\\n$/); console.log('actual workflow source verified');\n");
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Workflow Fixture"); git(source, "config", "user.email", "workflow@example.invalid");
        git(source, "config", "core.autocrlf", "false"); git(source, "add", "."); git(source, "commit", "-m", "original workflow fixture");
        store = new ExecutionStore(mapper, directory.resolve("data").toString()); runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString());
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults(); resources = new ResourceManager();
        engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper); project = catalog.importProject(obj("name", "Workflow fixture", "path", source.toString()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); httpWorkers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            try {
                byte[] bytes = exchange.getRequestBody().readAllBytes(); Map<String, Object> payload = mapper.readValue(bytes, new TypeReference<>() {});
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
                Map<String, Object> request = store.list("modelRequest").stream().filter(value -> digest.equals(str(value, "inputDigest")) && "DISPATCHING".equals(str(value, "state"))).findFirst().orElseThrow();
                assertEquals("LOCAL_PROCESS", engine.workerIdentity().get("deployment"));
                assertNotEquals(ProcessHandle.current().pid(), ((Number) engine.workerIdentity().get("pid")).longValue());
                assertEquals(engine.workerIdentity().get("id"), request.get("workerId")); assertFalse(str(request, "workerLeaseId").isBlank());
                assertTrue(num(store.get("agentRun", str(request, "agentRunId")), "calls", 0) > 0);
                calls.incrementAndGet(); observed.add(obj("requestId", request.get("id"), "nodeId", request.get("workflowNodeId"), "runId", request.get("agentRunId"), "activeModels", resources.activeModels()));
                List<Map<String, Object>> messages = objects(payload.get("messages"));
                String prompt = messages.stream().filter(message -> Set.of("system", "user").contains(str(message, "role"))).map(message -> str(message, "content")).reduce("", (a, b) -> a + "\n" + b);
                List<Map<String, Object>> results = messages.stream().filter(message -> "tool".equals(str(message, "role"))).toList();
                List<String> names = objects(payload.get("tools")).stream().map(tool -> str(map(tool, "function"), "name")).toList();
                Map<String, Object> response = fixture.get().respond(prompt, results, names);
                byte[] responseBytes = mapper.writeValueAsBytes(obj("id", "workflow-response-" + calls.get(), "model", "fixture", "choices",
                    List.of(obj("message", response, "finish_reason", response.containsKey("tool_calls") ? "tool_calls" : "stop")), "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                exchange.sendResponseHeaders(200, responseBytes.length); exchange.getResponseBody().write(responseBytes);
            } catch (Throwable error) { errors.add(error.toString()); try { exchange.sendResponseHeaders(500, -1); } catch (Exception ignored) { } }
            finally { exchange.close(); }
        }); server.start();
        rootRoute = route("Root"); otherRoute = route("Other confirmed route");
        actor("left-actor", "researcher", "", false); actor("right-actor", "reviewer", str(otherRoute, "id"), false); actor("writer-actor", "developer", "", true);
    }

    @AfterEach
    void close() throws Exception {
        release.countDown(); if (engine != null) engine.shutdown(); if (server != null) server.stop(0);
        if (httpWorkers != null) httpWorkers.shutdownNow(); if (store != null) store.close();
    }

    private Map<String, Object> route(String name) {
        return catalog.saveRoute(obj("name", name, "engine", "API", "model", "fixture", "provider", "fixture",
            "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "enabled", true));
    }

    private void actor(String id, String role, String routeId, boolean write) {
        catalog.saveAgentDefinition(obj("id", id, "name", id, "role", role, "instructions", "Retain original evidence and follow the confirmed goal.",
            "capabilities", write ? List.of("read", "search", "write") : List.of("read", "search"), "scope", List.of("src/**"), "maxCalls", 4, "enabled", true, "routeId", routeId));
    }

    private Map<String, Object> node(String id, String actor, List<String> dependencies, boolean write) {
        Map<String, Object> definition = store.get("agentDefinition", actor);
        return obj("id", id, "kind", write ? "IMPLEMENT" : "INVESTIGATE", "title", "Node " + id, "role", definition.get("role"), "goal", "Complete " + id + " with original evidence",
            "definitionId", actor, "definitionVersion", 1, "dependencies", dependencies,
            "inputs", dependencies.isEmpty() ? List.of("goal", "contract", "context", "baseline") : dependencies.stream().map(dependency -> dependency + ":result").toList(),
            "outputs", List.of(id + ":result"), "scope", List.of("src/**"), "permissions", write ? List.of("read", "search", "write") : List.of("read", "search"),
            "maxCalls", 4, "timeoutSeconds", 60, "acceptance", List.of("Actual versioned output is retained"), "termination", "Completion, root cancellation or deadline");
    }

    private Map<String, Object> task(String goal, List<Map<String, Object>> nodes) {
        Map<String, Object> published = catalog.workflows().save(obj("name", goal, "projectId", project.get("id"), "dsl",
            obj("dslVersion", 1, "inputs", List.of("goal", "contract", "context", "baseline"), "maxParallel", 2, "nodes", nodes)));
        Map<String, Object> version = map(published, "version");
        return catalog.createTask(obj("projectId", project.get("id"), "routeId", rootRoute.get("id"), "title", goal, "goal", goal, "mode", "LEAD",
            "contract", obj("scope", List.of("src/**"), "commands", List.of("node verify.mjs"), "acceptance", List.of("Source is correct and the workflow evidence is complete")),
            "budget", obj("maxCalls", 24, "reserveCalls", 4), "collaboration", obj("enabled", false), "workflow",
            obj("definitionId", version.get("definitionId"), "version", version.get("version"), "digest", version.get("digest"), "allowedRouteIds", List.of(otherRoute.get("id")))));
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
                fail("Unexpected workflow state: " + task.get("state") + " / " + task.get("error") + " / fixture=" + errors + " / nodes=" + store.list("workflowNodeAttempt"));
            }
            Thread.sleep(30);
        }
        fail("Original workflow deadline expired: " + store.get("task", taskId) + " / nodes=" + store.list("workflowNodeAttempt") + " / fixture=" + errors); return obj();
    }

    private Map<String, Object> answer(String text) { return obj("role", "assistant", "content", text); }
    private boolean node(String prompt, String id) { return prompt.contains("\n节点：" + id + "\n"); }
    private Map<String, Object> ordinary(String prompt) { return answer(prompt.contains("阶段 kind=REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "Actual stage evidence"); }
    private Map<String, Object> result(List<Map<String, Object>> results, int index) throws Exception { return mapper.readValue(str(results.get(index), "content"), new TypeReference<>() {}); }
    private Map<String, Object> call(String name, Map<String, Object> args) throws Exception {
        return obj("role", "assistant", "content", null, "tool_calls", List.of(obj("id", "workflow-tool-" + calls.get() + "-" + name,
            "type", "function", "function", obj("name", name, "arguments", mapper.writeValueAsString(args)))));
    }
    private Map<String, Object> write(String prompt, List<Map<String, Object>> results) throws Exception {
        if (results.isEmpty()) return call("read_file", obj("path", "src/source.txt"));
        if (results.size() == 1) return call("write_file", obj("path", "src/source.txt", "content", "done\n", "expectedVersion", str(result(results, 0), "readVersion")));
        assertFalse(result(results, 1).containsKey("error")); return answer("Actual source changed to done; original read and write versions retained.");
    }

    @Test
    void forwardDiamondRunsTwoActualModelsAndJoinsOnlyOriginalAncestorEvidence() throws Exception {
        CountDownLatch roots = new CountDownLatch(2); AtomicInteger overlap = new AtomicInteger();
        fixture.set((prompt, results, names) -> {
            if (node(prompt, "left") || node(prompt, "right")) {
                roots.countDown(); assertTrue(roots.await(10, TimeUnit.SECONDS), "Both ready nodes must reach actual HTTP while the other is held");
                overlap.accumulateAndGet(resources.activeModels(), Math::max);
                assertFalse(prompt.contains("LEFT_ONLY_NODE_EVIDENCE")); assertFalse(prompt.contains("RIGHT_ONLY_NODE_EVIDENCE"));
                return answer(node(prompt, "left") ? "LEFT_ONLY_NODE_EVIDENCE" : "RIGHT_ONLY_NODE_EVIDENCE");
            }
            if (node(prompt, "join")) {
                assertTrue(prompt.contains("LEFT_ONLY_NODE_EVIDENCE")); assertTrue(prompt.contains("RIGHT_ONLY_NODE_EVIDENCE"));
                assertFalse(names.contains("delegate_agent")); assertFalse(names.contains("run_command")); return write(prompt, results);
            }
            return ordinary(prompt);
        });
        Map<String, Object> created = task("FORWARD_DIAMOND", List.of(node("join", "writer-actor", List.of("left", "right"), true), node("left", "left-actor", List.of(), false), node("right", "right-actor", List.of(), false)));
        String taskId = str(created, "id"); engine.action(taskId, "approve", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(2, overlap.get()); assertEquals("done\n", Files.readString(Path.of(str(finished, "workspace")).resolve("src/source.txt"))); assertEquals("base\n", Files.readString(source.resolve("src/source.txt")));
        List<Map<String, Object>> attempts = store.list("workflowNodeAttempt"); assertEquals(3, attempts.size()); assertTrue(attempts.stream().allMatch(attempt -> "SUCCEEDED".equals(str(attempt, "state"))));
        assertEquals(3, attempts.stream().map(attempt -> str(attempt, "workspace")).distinct().count());
        Map<String, Object> join = attempts.stream().filter(attempt -> "join".equals(str(attempt, "nodeId"))).findFirst().orElseThrow();
        assertEquals(Set.of("left:result", "right:result"), map(join, "inputs").keySet()); assertEquals("INTEGRATED", join.get("integrationState"));
        assertTrue(observed.stream().filter(value -> value.get("nodeId") != null).allMatch(value -> !str(store.get("modelRequest", str(value, "requestId")), "workflowNodeAttemptId").isBlank()));
        assertEquals("PASSED", str(map(finished, "verification"), "status")); assertEquals("APPROVED", str(map(finished, "review"), "status"));
        engine.action(taskId, "accept", obj()); assertEquals("COMPLETED", str(store.get("task", taskId), "state")); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void pauseOfAnInFlightRequestKeepsUnknownAndBlocksResumeWithoutReplayingAnyNode() throws Exception {
        CountDownLatch held = new CountDownLatch(1); AtomicBoolean holding = new AtomicBoolean(true); AtomicInteger firstCalls = new AtomicInteger();
        fixture.set((prompt, results, names) -> {
            if (node(prompt, "first")) { firstCalls.incrementAndGet(); return answer("ORIGINAL_FIRST_NODE_OUTPUT"); }
            if (node(prompt, "writer")) {
                assertTrue(prompt.contains("ORIGINAL_FIRST_NODE_OUTPUT"));
                if (holding.get()) { held.countDown(); release.await(20, TimeUnit.SECONDS); return answer("Late original response after stop"); }
                return write(prompt, results);
            }
            return ordinary(prompt);
        });
        String taskId = str(task("RESUME_ORIGINAL_NODES", List.of(node("first", "left-actor", List.of(), false), node("writer", "writer-actor", List.of("first"), true))), "id");
        engine.action(taskId, "approve", obj()); assertTrue(held.await(90, TimeUnit.SECONDS), "The original writer request must be observed after baseline and node preparation");
        engine.action(taskId, "pause", obj()); Map<String, Object> paused = awaitTask(taskId, Set.of("UNKNOWN"));
        String workflowId = str(paused, "workflowRunId"); int used = num(map(paused, "budget"), "usedCalls", 0); assertEquals(1, firstCalls.get());
        Map<String, Object> original = store.list("workflowNodeAttempt").stream().filter(attempt -> "first".equals(str(attempt, "nodeId"))).findFirst().orElseThrow();
        assertEquals("SUCCEEDED", original.get("state"));
        assertTrue(store.list("modelRequest").stream().anyMatch(request -> "UNKNOWN".equals(str(request, "state")) && "UNKNOWN".equals(str(request, "remoteOutcome"))));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "resume", obj())).status());
        assertEquals(workflowId, store.get("task", taskId).get("workflowRunId")); assertEquals(1, firstCalls.get());
        assertEquals(original, store.get("workflowNodeAttempt", str(original, "id")));
        assertEquals(List.of(1), store.list("workflowNodeAttempt").stream().filter(attempt -> "writer".equals(str(attempt, "nodeId"))).map(attempt -> num(attempt, "attempt", 0)).toList());
        assertEquals(used, num(map(store.get("task", taskId), "budget"), "usedCalls", 0));
        assertEquals("base\n", Files.readString(source.resolve("src/source.txt"))); assertTrue(errors.isEmpty(), errors.toString()); release.countDown();
    }

    @Test
    void pauseBeforeTheNextModelRequestReusesOnlyTheCompletedNodeAndKeepsTheRootBudget() throws Exception {
        AtomicInteger firstCalls = new AtomicInteger(); actor("waiting-writer", "developer", str(otherRoute, "id"), true);
        fixture.set((prompt, results, names) -> {
            if (node(prompt, "first")) { firstCalls.incrementAndGet(); return answer("ORIGINAL_FIRST_NODE_OUTPUT"); }
            if (node(prompt, "writer")) { assertTrue(prompt.contains("ORIGINAL_FIRST_NODE_OUTPUT")); return write(prompt, results); }
            return ordinary(prompt);
        });
        String taskId = str(task("RESUME_BEFORE_REQUEST", List.of(node("first", "left-actor", List.of(), false), node("writer", "waiting-writer", List.of("first"), true))), "id");
        try (var blockedRoute = resources.acquire(Map.of("route:" + str(otherRoute, "id"), 1), () -> false)) {
            engine.action(taskId, "approve", obj()); long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90); boolean prepared = false;
            while (System.nanoTime() < deadline) {
                Map<String, Object> writer = store.list("workflowNodeAttempt").stream().filter(attempt -> "writer".equals(str(attempt, "nodeId"))).findFirst().orElse(null);
                if (writer != null && !str(writer, "inputOperationId").isBlank()) {
                    Map<String, Object> run = store.get("agentRun", str(writer, "agentRunId"));
                    if (store.list("operation").stream().anyMatch(operation -> str(run, "id").equals(str(operation, "agentRunId")) && "WORKFLOW_MODEL".equals(str(operation, "kind")) && "DISPATCHED".equals(str(operation, "state")))) {
                        assertEquals(0, num(run, "calls", -1)); prepared = true; break;
                    }
                }
                Thread.sleep(30);
            }
            assertTrue(prepared, "The second node must reach resource waiting without an HTTP model request"); engine.action(taskId, "pause", obj());
            Map<String, Object> paused = awaitTask(taskId, Set.of("PAUSED")); assertEquals(1, firstCalls.get());
            assertEquals(1, num(map(paused, "budget"), "usedCalls", 0)); assertEquals(1, store.list("modelRequest").size());
        }
        Map<String, Object> original = store.list("workflowNodeAttempt").stream().filter(attempt -> "first".equals(str(attempt, "nodeId"))).findFirst().orElseThrow();
        String workflowId = str(store.get("task", taskId), "workflowRunId"); engine.action(taskId, "resume", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(workflowId, finished.get("workflowRunId")); assertEquals(1, firstCalls.get()); assertEquals(original, store.get("workflowNodeAttempt", str(original, "id")));
        assertEquals(List.of(1, 2), store.list("workflowNodeAttempt").stream().filter(attempt -> "writer".equals(str(attempt, "nodeId"))).map(attempt -> num(attempt, "attempt", 0)).sorted().toList());
        assertTrue(num(map(finished, "budget"), "usedCalls", 0) > 1); assertEquals("done\n", Files.readString(Path.of(str(finished, "workspace")).resolve("src/source.txt")));
        assertEquals("base\n", Files.readString(source.resolve("src/source.txt"))); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void readOnlyNodeCannotReadOutsideItsScopeOrWriteEvenWhenTheModelRequestsIt() throws Exception {
        fixture.set((prompt, results, names) -> {
            if (node(prompt, "inspect")) {
                assertFalse(names.contains("write_file")); assertFalse(names.contains("delegate_agent"));
                if (results.isEmpty()) return call("read_file", obj("path", "outside.txt"));
                if (results.size() == 1) { assertTrue(result(results, 0).containsKey("error")); assertFalse(str(results.getFirst(), "content").contains("OUTSIDE_PRIVATE_VALUE")); return call("write_file", obj("path", "src/source.txt", "content", "bad", "expectedVersion", "absent")); }
                assertTrue(result(results, 1).containsKey("error")); return answer("Actual scope and read-only denials were observed.");
            }
            return ordinary(prompt);
        });
        String taskId = str(task("READ_ONLY_WORKFLOW", List.of(node("inspect", "left-actor", List.of(), false))), "id"); engine.action(taskId, "approve", obj());
        Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals("base\n", Files.readString(Path.of(str(finished, "workspace")).resolve("src/source.txt"))); assertEquals("OUTSIDE_PRIVATE_VALUE\n", Files.readString(source.resolve("outside.txt")));
        Map<String, Object> attempt = store.list("workflowNodeAttempt").getFirst(); assertEquals(str(attempt, "inputVersion"), str(attempt, "resultVersion"));
        assertEquals("NOT_INTEGRATED", attempt.get("integrationState")); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void draftGenerationUsesOneRootRequestAndPublishesEditedCandidateOnlyOnce() throws Exception {
        Map<String, Object> created = task("GENERATE_DRAFT", List.of(node("inspect", "left-actor", List.of(), false)));
        String taskId = str(created, "id"); Map<String, Object> originalSnapshot = map(created, "workflow");
        Map<String, Object> dsl = map(originalSnapshot, "dsl");
        String originalModelOutput = mapper.writeValueAsString(obj("name", "Proposed workflow", "dsl", dsl));
        fixture.set((prompt, results, names) -> {
            assertTrue(prompt.contains("阶段 kind=WORKFLOW_GENERATION"));
            assertFalse(names.contains("write_file")); assertFalse(names.contains("run_command")); assertFalse(names.contains("delegate_agent"));
            return answer("```json\r\n" + originalModelOutput + "\r\n```");
        });
        Map<String, Object> request = obj("requestId", id(), "maxCalls", 1, "instructions", "Suggest a read-only workflow", "definitionIds", List.of("left-actor"),
            "expectedVersion", "", "expectedSnapshotDigest", originalSnapshot.get("snapshotDigest"), "expectedRunId", "");
        engine.action(taskId, "generate-workflow", request);
        Map<String, Object> returned = awaitTask(taskId, Set.of("DRAFT"));
        assertEquals(originalSnapshot, map(returned, "workflow")); assertEquals(created.get("plan"), returned.get("plan"));
        assertFalse(bool(map(returned, "contract"), "confirmed", true)); assertTrue(str(returned, "pendingWorkflowGenerationId").isBlank());
        assertEquals(1, calls.get()); assertEquals(1, num(map(returned, "budget"), "usedCalls", 0));
        assertEquals("SUCCEEDED", str(map(returned, "taskExecution"), "state")); assertTrue(store.list("workflowNodeAttempt").isEmpty());
        assertEquals("DRAFT", store.get("taskExecution", str(returned, "taskExecutionId")).get("taskStateAtEnd"));
        Map<String, Object> proposal = store.get("workflowProposal", str(returned, "latestWorkflowProposalId"));
        assertEquals("VALIDATED", proposal.get("state"));
        assertEquals("```json\r\n" + originalModelOutput + "\r\n```", str(store.get("evidence", str(proposal, "evidenceId")), "output"));
        engine.action(taskId, "generate-workflow", request); assertEquals(1, store.list("workflowGeneration").size());
        Map<String, Object> conflicting = new java.util.LinkedHashMap<>(request); conflicting.put("maxCalls", 2);
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "generate-workflow", conflicting)).status());
        assertEquals(409, assertThrows(ApiException.class, () -> engine.publishWorkflowProposal(taskId, str(proposal, "id"), obj("name", "Edited", "expectedSourceDigest", "wrong"))).status());
        Map<String, Object> edited = obj("dslVersion", 1, "inputs", dsl.get("inputs"), "maxParallel", 1, "nodes", dsl.get("nodes"));
        Map<String, Object> publication = obj("name", "Edited", "expectedSourceDigest", proposal.get("sourceDigest"), "dsl", edited);
        Map<String, Object> first = engine.publishWorkflowProposal(taskId, str(proposal, "id"), publication);
        assertEquals("GENERATED_EDIT", str(map(first, "version"), "source"));
        assertEquals(first, engine.publishWorkflowProposal(taskId, str(proposal, "id"), publication));
        Map<String, Object> later = catalog.workflows().save(obj("id", map(first, "definition").get("id"), "expectedVersion", 1, "name", "Later version", "projectId", project.get("id"), "dsl", edited));
        assertEquals(2, num(map(later, "version"), "version", 0));
        assertEquals(map(first, "version"), map(engine.publishWorkflowProposal(taskId, str(proposal, "id"), publication), "version"));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.publishWorkflowProposal(taskId, str(proposal, "id"), obj("name", "Different", "expectedSourceDigest", proposal.get("sourceDigest"), "dsl", edited))).status());
        assertEquals(proposal.get("dsl"), store.get("workflowProposal", str(proposal, "id")).get("dsl"));
        assertEquals(originalSnapshot, map(store.get("task", taskId), "workflow"));
        assertEquals(1, calls.get()); assertTrue(errors.isEmpty(), errors.toString());
    }

    private static void git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(arguments));
        Process child = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        String output = new String(child.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); assertEquals(0, child.waitFor(), output);
    }
}
