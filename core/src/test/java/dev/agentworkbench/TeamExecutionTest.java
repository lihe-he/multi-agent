package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.mcp.McpManager;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用实际 Git、HTTP 和调度器验证委派，不用模拟运行身份替代真实执行。
 */
class TeamExecutionTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkspaceRuntime runtime;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private ResourceManager resources;
    private HttpServer server;
    private java.util.concurrent.ExecutorService httpWorkers;
    private Path source;
    private Map<String, Object> project;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger peak = new AtomicInteger();
    private final List<String> prompts = new CopyOnWriteArrayList<>();
    private final List<String> errors = new CopyOnWriteArrayList<>();
    private final AtomicReference<Fixture> fixture = new AtomicReference<>();
    private final CountDownLatch release = new CountDownLatch(1);

    @FunctionalInterface
    private interface Fixture { Map<String, Object> respond(String prompt, List<Map<String, Object>> tools, List<String> names) throws Exception; }

    @BeforeEach
    void prepare() throws Exception {
        source = Files.createDirectories(directory.resolve("中文 source project")); Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/source.txt"), "base\n"); Files.writeString(source.resolve("outside.txt"), "outside-private-value\n");
        Files.writeString(source.resolve("verify.mjs"), "import {readFileSync} from 'node:fs'; import assert from 'node:assert/strict'; assert.match(readFileSync('src/source.txt','utf8'), /^(base|made)\\n$/); console.log('source verified');\n");
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Team Test"); git(source, "config", "user.email", "team@example.invalid"); git(source, "config", "core.autocrlf", "false"); git(source, "add", "."); git(source, "commit", "-m", "fixture");
        store = new ExecutionStore(mapper, directory.resolve("data").toString()); runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString());
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults(); resources = new ResourceManager();
        engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper); project = catalog.importProject(obj("name", "actual team", "path", source.toString()));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0); httpWorkers = Executors.newVirtualThreadPerTaskExecutor(); server.setExecutor(httpWorkers);
        server.createContext("/", exchange -> {
            try {
                byte[] original = exchange.getRequestBody().readAllBytes(); Map<String, Object> request = mapper.readValue(original, new TypeReference<>() {});
                String digest = java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(original));
                Map<String, Object> persisted = store.list("modelRequest").stream().filter(value -> digest.equals(str(value, "inputDigest")) && "DISPATCHING".equals(str(value, "state"))).findFirst().orElseThrow();
                assertFalse(str(persisted, "workerLeaseId").isBlank()); assertEquals(engine.workerIdentity().get("id"), persisted.get("workerId"));
                assertNotEquals(ProcessHandle.current().pid(), ((Number) engine.workerIdentity().get("pid")).longValue()); assertEquals("LOCAL_PROCESS", engine.workerIdentity().get("deployment"));
                assertTrue(num(store.get("agentRun", str(persisted, "agentRunId")), "calls", 0) > 0, "原请求与预算在 HTTP 到达前已经保存");
                calls.incrementAndGet(); peak.accumulateAndGet(resources.activeModels(), Math::max);
                List<Map<String, Object>> messages = objects(request.get("messages"));
                String prompt = messages.stream().filter(message -> Set.of("user", "system").contains(str(message, "role"))).map(message -> str(message, "content")).reduce("", (a, b) -> a + "\n" + b); prompts.add(prompt);
                List<Map<String, Object>> toolResults = messages.stream().filter(message -> "tool".equals(str(message, "role"))).toList();
                List<String> names = objects(request.get("tools")).stream().map(tool -> str(map(tool, "function"), "name")).toList();
                Map<String, Object> response = fixture.get().respond(prompt, toolResults, names);
                byte[] bytes = mapper.writeValueAsBytes(obj("id", "team-response-" + calls.get(), "model", "fixture", "choices", List.of(obj("message", response, "finish_reason", response.containsKey("tool_calls") ? "tool_calls" : "stop")), "usage", obj("prompt_tokens", 10, "completion_tokens", 5)));
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { errors.add(error.toString()); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        }); server.start();
    }

    @AfterEach
    void close() throws Exception {
        release.countDown(); if (engine != null) engine.shutdown(); if (server != null) server.stop(0); if (httpWorkers != null) httpWorkers.shutdownNow(); if (store != null) store.close();
    }

    private static void git(Path work, String... args) throws Exception {
        java.util.ArrayList<String> command = new java.util.ArrayList<>(List.of("git")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(work.toFile()).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
    }

    private Map<String, Object> task(String marker, int maxCalls) {
        return task(marker, maxCalls, obj());
    }

    private Map<String, Object> task(String marker, int maxCalls, Map<String, Object> options) {
        Map<String, Object> route = catalog.saveRoute(obj("name", marker, "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "enabled", true));
        Map<String, Object> request = obj("projectId", project.get("id"), "routeId", route.get("id"), "title", marker, "goal", marker, "mode", "LEAD", "locks", obj("routeId", route.get("id")),
            "contract", obj("scope", List.of("src/"), "commands", List.of("node verify.mjs"), "acceptance", List.of("scoped source is correct")), "budget", obj("maxCalls", maxCalls, "reserveCalls", 4), "collaboration", obj("enabled", true));
        request.putAll(options); return catalog.createTask(request);
    }

    private Map<String, Object> awaitTask(String taskId, Set<String> states) throws Exception {
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        while (System.nanoTime() < timeout) {
            Map<String, Object> task = store.get("task", taskId);
            if (states.contains(str(task, "state"))) return task;
            if (Set.of("UNKNOWN", "FAILED", "WAITING_INPUT").contains(str(task, "state"))) fail("Unexpected state: " + task.get("state") + " / " + task.get("error") + " / " + errors + " / children=" + store.list("subtask") + " / operations=" + store.list("operation")
                + " / roots=" + store.list("task").stream().map(root -> obj("id", root.get("id"), "state", root.get("state"), "error", root.get("error"), "evidence", root.get("evidence"))).toList());
            Thread.sleep(50);
        }
        fail("Timed out: " + store.get("task", taskId) + " / " + errors); return obj();
    }

    private Map<String, Object> call(String name, Map<String, Object> args) throws Exception {
        return obj("role", "assistant", "content", null, "tool_calls", List.of(obj("id", "team-tool-" + calls.get() + "-" + name, "type", "function", "function", obj("name", name, "arguments", mapper.writeValueAsString(args)))));
    }

    private Map<String, Object> result(List<Map<String, Object>> results, int index) throws Exception { return mapper.readValue(str(results.get(index), "content"), new TypeReference<>() {}); }
    private Map<String, Object> answer(String text) { return obj("role", "assistant", "content", text); }
    private boolean stage(String prompt, String kind) { return prompt.contains("阶段 kind=" + kind); }
    private Map<String, Object> ordinary(String prompt) { return answer(stage(prompt, "REVIEW") ? "{\"approved\":true,\"issues\":[]}" : "phase evidence"); }

    @Test
    void draftBindingSwitchReplacesRulesAndExecutesTheConfirmedSource() throws Exception {
        Files.writeString(source.resolve("AGENTS.md"), "ORIGINAL_SOURCE_RULES\n"); git(source, "add", "AGENTS.md"); git(source, "commit", "-m", "original rules");
        Map<String, Object> created = task("WORKER_DRAFT_SOURCE_SWITCH", 16); String taskId = str(created, "id");
        Path alternative = directory.resolve("alternate source"); git(directory, "-c", "core.autocrlf=false", "clone", "--", source.toString(), alternative.toString()); git(alternative, "config", "core.autocrlf", "false");
        Files.writeString(alternative.resolve("AGENTS.md"), "ALTERNATE_SOURCE_RULES\n"); git(alternative, "add", "AGENTS.md"); git(alternative, "-c", "user.name=fixture", "-c", "user.email=fixture@example.invalid", "commit", "-m", "alternate rules");
        Map<String, Object> binding = catalog.nodes().saveBinding(obj("projectId", project.get("id"), "executionNodeId", engine.workerIdentity().get("executionNodeId"), "name", "alternate source", "path", alternative.toString()));
        Map<String, Object> authorized = engine.action(taskId, "execution-authorization", obj("expectedVersion", 1, "repositoryBindingId", binding.get("id"), "bindingVersion", 1, "nodeVersion", 1));
        assertTrue(objects(authorized.get("context")).stream().anyMatch(item -> str(item, "content").contains("ALTERNATE_SOURCE_RULES")));
        assertFalse(objects(authorized.get("context")).stream().anyMatch(item -> str(item, "content").contains("ORIGINAL_SOURCE_RULES")));
        assertTrue(objects(objects(authorized.get("executionRuleHistory")).getFirst().get("rules")).stream().anyMatch(item -> str(item, "content").contains("ORIGINAL_SOURCE_RULES")));
        fixture.set((prompt, results, names) -> { assertTrue(prompt.contains("ALTERNATE_SOURCE_RULES")); assertFalse(prompt.contains("ORIGINAL_SOURCE_RULES")); return ordinary(prompt); });
        engine.action(taskId, "approve", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals("ALTERNATE_SOURCE_RULES\n", Files.readString(Path.of(str(finished, "workspace")).resolve("AGENTS.md")));
        assertEquals("ORIGINAL_SOURCE_RULES\n", Files.readString(source.resolve("AGENTS.md"))); assertEquals(alternative.toRealPath().toString(), map(finished, "execution").get("sourcePath"));
        assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void realExecutionFreezesBindingVersionsAndRequiresExplicitReauthorization() throws Exception {
        fixture.set((prompt, results, names) -> ordinary(prompt));
        Map<String, Object> created = task("WORKER_FROZEN_BINDING", 16), original = map(created, "execution"); String taskId = str(created, "id");
        Map<String, Object> binding = catalog.nodes().saveBinding(obj("id", original.get("repositoryBindingId"), "expectedVersion", 1, "projectId", project.get("id"),
            "executionNodeId", original.get("executionNodeId"), "name", "confirmed checkout v2", "path", source.toString(), "enabled", true));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "approve", obj())).status()); assertEquals(0, calls.get()); assertEquals(1, num(map(store.get("task", taskId), "execution"), "bindingVersion", 0));
        engine.action(taskId, "execution-authorization", obj("expectedVersion", 1, "repositoryBindingId", binding.get("id"), "bindingVersion", 2, "nodeVersion", 1));
        engine.action(taskId, "approve", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(2, num(map(finished, "execution"), "bindingVersion", 0)); assertEquals(1, objects(finished.get("executionHistory")).size());
        List<Map<String, Object>> leases = store.workerLeases("", taskId, ""); assertFalse(leases.isEmpty()); assertTrue(leases.stream().allMatch(lease -> "RELEASED".equals(str(lease, "state")) && num(lease, "bindingVersion", 0) == 2));
        assertTrue(leases.stream().allMatch(lease -> engine.workerIdentity().get("id").equals(lease.get("workerId")) && original.get("executionNodeId").equals(lease.get("executionNodeId"))));
        assertTrue(objects(finished.get("evidence")).stream().allMatch(evidence -> !str(evidence, "workerLeaseId").isBlank() && !str(evidence, "workerId").isBlank()));
        assertEquals("base\n", Files.readString(source.resolve("src/source.txt"))); assertTrue(store.list("operation").stream().allMatch(operation -> num(operation, "dispatchCount", 0) == 1));
        Path other = Files.createDirectories(directory.resolve("other checkout")); git(directory, "clone", "--", source.toString(), other.toString());
        Map<String, Object> alternative = catalog.nodes().saveBinding(obj("projectId", project.get("id"), "executionNodeId", original.get("executionNodeId"), "name", "another checkout", "path", other.toString()));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "execution-authorization", obj("expectedVersion", 2, "repositoryBindingId", alternative.get("id"), "bindingVersion", 1, "nodeVersion", 1))).status());
        assertEquals(original.get("sourcePath"), map(store.get("task", taskId), "execution").get("sourcePath")); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void nodeRevocationStopsTheActualChildRequestAndRestartPreservesUnknownLeases() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) { entered.countDown(); release.await(15, TimeUnit.SECONDS); return answer("held child returned"); }
            if (stage(prompt, "INVESTIGATE")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "held worker child", "goal", "WORKER_HELD_CHILD", "scope", List.of("src/")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
            }
            return ordinary(prompt);
        });
        Map<String, Object> created = task("WORKER_NODE_REVOKED", 24), identity = engine.workerIdentity(); String taskId = str(created, "id"); engine.action(taskId, "approve", obj());
        assertTrue(entered.await(15, TimeUnit.SECONDS)); Map<String, Object> child = store.list("subtask").getFirst();
        Map<String, Object> request = store.list("modelRequest").stream().filter(value -> child.get("agentRunId").equals(value.get("agentRunId"))).findFirst().orElseThrow();
        assertEquals("DISPATCHING", request.get("state")); assertTrue(store.workerLeases("", taskId, "ACTIVE").stream().anyMatch(lease -> child.get("agentRunId").equals(lease.get("agentRunId"))));
        Map<String, Object> node = catalog.nodes().localNode(); catalog.nodes().saveNode(obj("id", node.get("id"), "expectedVersion", node.get("version"), "name", node.get("name"), "enabled", false));
        awaitTask(taskId, Set.of("UNKNOWN")); int before = calls.get();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(8);
        while (("DISPATCHING".equals(str(store.get("modelRequest", str(request, "id")), "state")) || !store.workerLeases("", taskId, "ACTIVE").isEmpty()) && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(store.workerLeases("", taskId, "ACTIVE").isEmpty()); assertEquals("UNKNOWN", store.get("modelRequest", str(request, "id")).get("state"));
        release.countDown();
        assertTrue(store.workerLeases("", taskId, "UNKNOWN").stream().anyMatch(lease -> child.get("agentRunId").equals(lease.get("agentRunId")))); assertEquals(1, num(map(store.get("task", taskId), "execution"), "nodeVersion", 0));
        engine.shutdown(); engine = null; store.close(); store = new ExecutionStore(mapper, directory.resolve("data").toString());
        runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString());
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); engine = new WorkbenchEngine(store, catalog, runtime, new ResourceManager(), mapper); engine.recover();
        assertEquals(before, calls.get()); assertEquals(identity.get("executionNodeId"), engine.workerIdentity().get("executionNodeId")); assertNotEquals(identity.get("id"), engine.workerIdentity().get("id"));
        assertEquals(num(identity, "epoch", 0) + 1, num(engine.workerIdentity(), "epoch", 0)); assertEquals("UNKNOWN", store.get("task", taskId).get("state")); assertFalse(store.workerLeases("", taskId, "UNKNOWN").isEmpty());
        assertEquals("base\n", Files.readString(source.resolve("src/source.txt"))); assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "resume", obj())).status());
    }

    @Test
    void actualDelegationSharesRootBudgetAndDependencySnapshotBeforeGuardedAdoption() throws Exception {
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT") && prompt.contains("委派目标：CHILD_WRITE")) {
                assertFalse(names.contains("run_command"), "narrow file scope cannot grant command access");
                if (results.isEmpty()) return call("read_file", obj("path", "outside.txt"));
                if (results.size() == 1) { assertTrue(result(results, 0).containsKey("error")); assertFalse(str(results.getFirst(), "content").contains("outside-private-value")); return call("write_file", obj("path", "src/source.txt", "content", "made\n")); }
                return answer("Changed src/source.txt; parent must adopt and independently verify.");
            }
            if (stage(prompt, "SUBAGENT") && prompt.contains("委派目标：CHILD_READ")) {
                assertFalse(names.contains("write_file"));
                if (results.isEmpty()) return call("read_file", obj("path", "src/source.txt"));
                assertEquals("made\n", result(results, 0).get("content"), "dependent reader sees the actual prior result"); return answer("Verified dependency source content and version.");
            }
            if (stage(prompt, "IMPLEMENT")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-developer", "title", "bounded writer", "goal", "CHILD_WRITE", "scope", List.of("src/")));
                Map<String, Object> first = map(result(results, 0), "agent");
                if (results.size() == 1) return call("delegate_agent", obj("definitionId", "agent-reviewer", "title", "dependent reader", "goal", "CHILD_READ", "scope", List.of("src/"), "dependsOn", List.of(str(first, "subtaskId"))));
                if (results.size() == 2) return call("wait_agent", obj("subtaskId", map(result(results, 1), "agent").get("subtaskId")));
                if (results.size() == 3) return call("adopt_agent_changes", obj("subtaskId", first.get("subtaskId")));
                if (results.size() == 4) { assertEquals("ACKNOWLEDGED", str(result(results, 3), "state")); return call("send_team_message", obj("content", "adopted observed source", "recipient", first.get("agentRunId"))); }
                return answer("Integrated bounded writer; root verification remains required.");
            }
            return ordinary(prompt);
        });
        Map<String, Object> task = task("TEAM_FULL", 40); String taskId = str(task, "id"); engine.action(taskId, "approve", obj());
        Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals("made\n", Files.readString(Path.of(str(finished, "workspace")).resolve("src/source.txt"))); assertEquals("base\n", Files.readString(source.resolve("src/source.txt")));
        List<Map<String, Object>> children = objects(store.team(taskId).get("agents")); assertEquals(2, children.size()); assertTrue(children.stream().allMatch(child -> "SUCCEEDED".equals(str(child, "state"))));
        assertEquals(1, children.stream().filter(child -> "ADOPTED".equals(str(child, "integrationState"))).count());
        assertEquals(calls.get(), num(map(finished, "budget"), "usedCalls", 0)); assertEquals(1, store.list("task").size(), "children do not create independent root budgets");
        assertTrue(store.list("modelRequest").stream().anyMatch(request -> request.containsKey("subtaskId")));
        assertTrue(store.list("modelRequest").stream().allMatch(request -> taskId.equals(str(request, "rootTaskId"))));
        assertEquals(1, store.list("teamOperation").size()); assertEquals("ACKNOWLEDGED", str(store.list("teamOperation").getFirst(), "state"));
        assertTrue(store.list("operation").stream().anyMatch(operation -> "SUBAGENT_DEPENDENCIES".equals(str(operation, "kind")) && "ACKNOWLEDGED".equals(str(operation, "state"))));
        assertEquals(1, objects(store.teamMessages(taskId, 0, 0, 40, "").get("messages")).size());
        assertTrue(peak.get() <= 2); assertEquals(0, resources.activeModels()); assertTrue(errors.isEmpty(), errors.toString());
        engine.action(taskId, "accept", obj()); assertEquals("COMPLETED", str(store.get("task", taskId), "state"));
    }

    @Test
    void twoParentsWaitingForChildrenMakeProgressAtCapacityTwo() throws Exception {
        CountDownLatch entered = new CountDownLatch(2);
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) { entered.countDown(); assertTrue(entered.await(8, TimeUnit.SECONDS), "parents must release both model slots before children can proceed"); return answer("child observation"); }
            if (stage(prompt, "INVESTIGATE")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "barrier child", "goal", "BARRIER", "scope", List.of("src/")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
            }
            return ordinary(prompt);
        });
        Map<String, Object> first = task("PARENT_ONE", 24), second = task("PARENT_TWO", 24); engine.action(str(first, "id"), "approve", obj()); engine.action(str(second, "id"), "approve", obj());
        awaitTask(str(first, "id"), Set.of("AWAITING_ACCEPTANCE")); awaitTask(str(second, "id"), Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(0, entered.getCount()); assertEquals(2, peak.get()); assertEquals(0, resources.activeModels()); assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(2, store.list("subtask").size()); assertTrue(store.list("subtask").stream().allMatch(child -> "SUCCEEDED".equals(str(child, "state"))));
    }

    @Test
    void cancellingChildHttpWaitPreservesUnknownFactsAndDoesNotReplay() throws Exception {
        CountDownLatch childEntered = new CountDownLatch(1);
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) { childEntered.countDown(); release.await(15, TimeUnit.SECONDS); return answer("late remote result"); }
            if (stage(prompt, "INVESTIGATE") && results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "pending child", "goal", "WAIT", "scope", List.of("src/")));
            if (stage(prompt, "INVESTIGATE") && results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
            return ordinary(prompt);
        });
        Map<String, Object> task = task("CANCEL_PARENT", 24); String taskId = str(task, "id"); engine.action(taskId, "approve", obj()); assertTrue(childEntered.await(20, TimeUnit.SECONDS));
        engine.action(taskId, "cancel", obj()); awaitTask(taskId, Set.of("UNKNOWN")); int before = calls.get(); Thread.sleep(250); assertEquals(before, calls.get());
        Map<String, Object> child = store.list("subtask").getFirst(); assertEquals("UNKNOWN", str(child, "state"));
        Map<String, Object> request = store.list("modelRequest").stream().filter(value -> str(child, "id").equals(str(value, "subtaskId"))).findFirst().orElseThrow();
        assertEquals("UNKNOWN", str(request, "state")); assertEquals("UNKNOWN", str(request, "remoteOutcome")); assertEquals(0, resources.activeModels());
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "resume", obj())).status());
        engine.shutdown(); engine = null; store.close(); store = new ExecutionStore(mapper, directory.resolve("data").toString());
        runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString());
        catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); resources = new ResourceManager(); engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper); engine.recover();
        assertEquals("UNKNOWN", str(store.get("modelRequest", str(request, "id")), "state")); assertEquals("UNKNOWN", str(store.subtask(taskId, str(child, "id")), "state"));
        assertEquals(409, assertThrows(ApiException.class, () -> engine.action(taskId, "resume", obj())).status()); Thread.sleep(150); assertEquals(before, calls.get(), "reopening the store must not repeat the remote request");
    }

    @Test
    void conflictingParentEditCannotBeOverwrittenByChildAdoption() throws Exception {
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) { if (results.isEmpty()) return call("write_file", obj("path", "src/source.txt", "content", "made\n")); return answer("child change"); }
            if (stage(prompt, "IMPLEMENT")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-developer", "title", "writer", "goal", "write", "scope", List.of("src/")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
                if (results.size() == 2) return call("write_file", obj("path", "src/source.txt", "content", "base\nparent edit\n"));
                if (results.size() == 3) return call("adopt_agent_changes", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
                assertEquals("REJECTED", str(result(results, 3), "state")); return answer("conflict requires a revised integration decision");
            }
            return ordinary(prompt);
        });
        Map<String, Object> task = task("CONFLICT_PARENT", 24); String taskId = str(task, "id"); engine.action(taskId, "approve", obj());
        long timeout = System.nanoTime() + TimeUnit.SECONDS.toNanos(40);
        while (store.list("teamOperation").isEmpty() || !"REJECTED".equals(str(store.list("teamOperation").getFirst(), "state"))) { assertTrue(System.nanoTime() < timeout, errors.toString()); Thread.sleep(50); }
        engine.action(taskId, "pause", obj());
        assertEquals("base\nparent edit\n", Files.readString(Path.of(str(store.get("task", taskId), "workspace")).resolve("src/source.txt")));
        assertEquals("NOT_ADOPTED", str(store.list("subtask").getFirst(), "integrationState")); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void actualRecursiveDelegationStopsAtDepthAndCumulativeLimits() throws Exception {
        AtomicInteger rejected = new AtomicInteger();
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT") && prompt.contains("委派目标：DEPTH_TWO")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "blocked depth three", "goal", "DEPTH_THREE", "scope", List.of("src/")));
                assertEquals("REJECTED", str(result(results, 0), "state")); rejected.incrementAndGet(); return answer("depth bound confirmed");
            }
            if (stage(prompt, "SUBAGENT") && prompt.contains("委派目标：DEPTH_ONE")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "depth two", "goal", "DEPTH_TWO", "scope", List.of("src/")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
                assertEquals("SUCCEEDED", str(result(results, 1), "agentState")); return answer("observed actual grandchild evidence");
            }
            if (stage(prompt, "INVESTIGATE")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "depth one", "goal", "DEPTH_ONE", "scope", List.of("src/")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
                if (results.size() == 2) return call("delegate_agent", obj("definitionId", "agent-research", "title", "blocked replacement", "goal", "AFTER_END", "scope", List.of("src/")));
                assertEquals("REJECTED", str(result(results, 2), "state")); rejected.incrementAndGet(); return answer("completed descendants do not reset the root cumulative limit");
            }
            return ordinary(prompt);
        });
        Map<String, Object> task = task("RECURSIVE_BOUND", 40, obj("collaboration", obj("enabled", true, "maxDepth", 2, "maxTotal", 2))); String taskId = str(task, "id"); engine.action(taskId, "approve", obj());
        Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE")); List<Map<String, Object>> children = store.list("subtask");
        assertEquals(2, children.size()); assertEquals(2, rejected.get()); assertTrue(children.stream().allMatch(child -> "SUCCEEDED".equals(str(child, "state"))));
        Map<String, Object> grandchild = children.stream().filter(child -> num(child, "depth", 0) == 2).findFirst().orElseThrow(); assertFalse(str(grandchild, "parentSubtaskId").isBlank());
        assertEquals(2, num(map(finished, "collaboration"), "spawned", 0)); assertEquals(calls.get(), num(map(finished, "budget"), "usedCalls", 0)); assertEquals(0, resources.activeModels()); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void independentProposalsCannotReceiveBroadcastsOrPublishThroughHiddenTeamTools() throws Exception {
        AtomicInteger proposals = new AtomicInteger();
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "PROPOSAL")) {
                assertFalse(prompt.contains("BROADCAST_SECRET")); assertFalse(prompt.contains("OTHER_PROPOSAL_SECRET"));
                assertFalse(names.contains("delegate_agent")); assertFalse(names.contains("send_team_message")); assertFalse(names.contains("list_team_agents"));
                if (results.isEmpty()) return call("send_team_message", obj("content", "OTHER_PROPOSAL_SECRET"));
                assertTrue(result(results, 0).containsKey("error")); proposals.incrementAndGet(); return answer("independent proposal evidence");
            }
            return ordinary(prompt);
        });
        Map<String, Object> task = task("INDEPENDENT_TEAM", 40, obj("mode", "ROUNDTABLE")); String taskId = str(task, "id"); store.teamMessage(taskId, "USER", "", "before-proposals", "BROADCAST_SECRET", "", ""); engine.action(taskId, "approve", obj());
        awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE")); assertEquals(3, proposals.get()); assertEquals(1, store.list("teamMessage").size()); assertTrue(store.list("subtask").isEmpty());
        assertTrue(prompts.stream().anyMatch(prompt -> stage(prompt, "CRITIQUE") && prompt.contains("BROADCAST_SECRET")), "shared input becomes available only after independent proposals"); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void disablingDefinitionStopsPendingChildWithoutChangingItsFixedVersionOrRetrying() throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) { entered.countDown(); release.await(15, TimeUnit.SECONDS); return answer("late child response"); }
            if (stage(prompt, "INVESTIGATE") && results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-research", "title", "withdrawn child", "goal", "WITHDRAW", "scope", List.of("src/")));
            if (stage(prompt, "INVESTIGATE") && results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
            return ordinary(prompt);
        });
        Map<String, Object> task = task("WITHDRAW_DEFINITION", 24); String taskId = str(task, "id"); engine.action(taskId, "approve", obj()); assertTrue(entered.await(20, TimeUnit.SECONDS));
        Map<String, Object> update = new java.util.LinkedHashMap<>(store.get("agentDefinition", "agent-research")); update.put("expectedVersion", 1); update.put("enabled", false); catalog.saveAgentDefinition(update);
        awaitTask(taskId, Set.of("UNKNOWN")); int before = calls.get(); Thread.sleep(150); assertEquals(before, calls.get());
        Map<String, Object> child = store.list("subtask").getFirst(); assertEquals(1, num(child, "definitionVersion", 0)); assertEquals("UNKNOWN", str(child, "state")); assertEquals(2, num(store.get("agentDefinition", "agent-research"), "version", 0));
        assertTrue(objects(map(store.get("task", taskId), "collaboration").get("definitions")).stream().anyMatch(definition -> "agent-research".equals(str(definition, "id")) && bool(definition, "enabled", false) && num(definition, "version", 0) == 1)); assertEquals(0, resources.activeModels());
    }

    @Test
    void childMcpActionPreservesActualSourceAndRootReuseDoesNotDispatchAgain() throws Exception {
        AtomicInteger externalCalls = new AtomicInteger();
        McpManager manager = mcpFixture(externalCalls);
        Map<String, Object> selection = mcpSelection(manager, "MCP_CHILD_SOURCE");
        Map<String, Object> task = task("CHILD_MCP", 30, obj("contract", obj("scope", List.of("**"), "commands", List.of("node verify.mjs"), "acceptance", List.of("actual source remains correct")), "mcpBindings", List.of(selection), "mcpBudget", obj("maxCalls", 2, "maxDurationMs", 10000))); String taskId = str(task, "id");
        String alias = str(objects(map(task, "mcpAuthorization").get("bindings")).getFirst(), "alias");
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "SUBAGENT")) {
                assertTrue(names.contains(alias)); if (results.isEmpty()) return call(alias, obj()); assertEquals("ACKNOWLEDGED", str(result(results, 0), "state")); return answer("observed fixed external action");
            }
            if (stage(prompt, "IMPLEMENT")) {
                if (results.isEmpty()) return call("delegate_agent", obj("definitionId", "agent-developer", "title", "fixed MCP child", "goal", "MCP_CHILD", "scope", List.of("**")));
                if (results.size() == 1) return call("wait_agent", obj("subtaskId", map(result(results, 0), "agent").get("subtaskId")));
                if (results.size() == 2) return call(alias, obj());
                assertTrue(bool(result(results, 2), "deduplicated", false)); assertEquals(map(result(results, 0), "agent").get("agentRunId"), result(results, 2).get("sourceAgentRunId")); return answer("root observed the original child action without another dispatch");
            }
            return ordinary(prompt);
        });
        engine.action(taskId, "approve", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE")); Map<String, Object> child = store.list("subtask").getFirst(), operation = store.list("mcpOperation").getFirst();
        assertEquals(1, externalCalls.get()); assertEquals(1, num(map(finished, "mcpBudget"), "usedCalls", 0)); assertEquals(1, store.list("mcpOperation").size()); assertEquals(child.get("agentRunId"), operation.get("agentRunId")); assertEquals(child.get("id"), operation.get("subtaskId"));
        Map<String, Object> receipt = store.get("modelRequest", str(operation, "modelRequestId")); assertEquals(child.get("agentRunId"), receipt.get("agentRunId")); assertEquals("SUCCEEDED", str(receipt, "state")); assertFalse(str(operation, "toolCallId").isBlank());
        assertEquals(calls.get(), num(map(finished, "budget"), "usedCalls", 0)); manager.requireTaskStopped(taskId); assertEquals(0, resources.activeModels()); assertTrue(errors.isEmpty(), errors.toString());
    }

    @Test
    void fixedStageWithoutDynamicTeamPreservesOriginalMcpSourceAndCapacity() throws Exception {
        AtomicInteger externalCalls = new AtomicInteger(); McpManager manager = mcpFixture(externalCalls);
        Map<String, Object> selection = mcpSelection(manager, "ROOT_MCP_ORIGINAL_SOURCE");
        Map<String, Object> task = task("ROOT_FIXED_MCP", 16, obj("collaboration", obj("enabled", false), "contract", obj("scope", List.of("**"), "commands", List.of("node verify.mjs"), "acceptance", List.of("actual MCP source")),
            "mcpBindings", List.of(selection), "mcpBudget", obj("maxCalls", 2, "maxDurationMs", 10000))); String taskId = str(task, "id"), alias = str(objects(map(task, "mcpAuthorization").get("bindings")).getFirst(), "alias");
        assertTrue(str(task, "teamId").isBlank());
        fixture.set((prompt, results, names) -> {
            if (stage(prompt, "IMPLEMENT")) {
                assertTrue(names.contains(alias)); assertFalse(names.contains("delegate_agent"));
                if (results.isEmpty()) return call(alias, obj());
                assertEquals("ACKNOWLEDGED", str(result(results, 0), "state"));
                if (results.size() == 1) return call(alias, obj());
                assertTrue(bool(result(results, 1), "deduplicated", false)); return answer("original fixed MCP receipt retained");
            }
            return ordinary(prompt);
        });
        engine.action(taskId, "approve", obj()); Map<String, Object> finished = awaitTask(taskId, Set.of("AWAITING_ACCEPTANCE"));
        assertEquals(1, externalCalls.get()); assertEquals(1, store.list("mcpOperation").size()); assertEquals(1, num(map(finished, "mcpBudget"), "usedCalls", 0)); assertTrue(store.list("subtask").isEmpty());
        Map<String, Object> operation = store.list("mcpOperation").getFirst(), receipt = store.get("modelRequest", str(operation, "modelRequestId"));
        assertEquals("SUCCEEDED", receipt.get("state")); assertEquals("IMPLEMENT", store.get("agentRun", str(operation, "agentRunId")).get("kind")); assertTrue(str(operation, "teamId").isBlank());
        assertEquals(receipt.get("workerLeaseId"), operation.get("workerLeaseId")); assertEquals(engine.workerIdentity().get("id"), operation.get("workerId"));
        assertEquals("LOCAL_PROCESS", operation.get("deployment")); assertEquals(((Number) engine.workerIdentity().get("pid")).longValue(), ((Number) operation.get("executorPid")).longValue());
        assertEquals(calls.get(), num(map(finished, "budget"), "usedCalls", 0)); assertEquals(0, resources.activeModels()); manager.requireTaskStopped(taskId); assertTrue(errors.isEmpty(), errors.toString());
    }

    private McpManager mcpFixture(AtomicInteger externalCalls) throws Exception {
        server.createContext("/mcp", exchange -> {
            try {
                if ("DELETE".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); return; }
                Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {}); String method = str(request, "method");
                if (method.startsWith("notifications/")) { exchange.sendResponseHeaders(202, -1); return; }
                Map<String, Object> result;
                if (method.equals("initialize")) { exchange.getResponseHeaders().set("Mcp-Session-Id", "actual-team-mcp"); result = obj("protocolVersion", "2025-06-18", "capabilities", obj("tools", obj()), "serverInfo", obj("name", "team fixture", "version", "1")); }
                else if (method.equals("tools/list")) result = obj("tools", List.of(obj("name", "echo", "inputSchema", obj("type", "object", "properties", obj("text", obj("type", "string")), "required", List.of("text"), "additionalProperties", false))));
                else { assertEquals("tools/call", method); assertEquals(0, resources.activeModels(), "actual MCP HTTP must not retain a model slot"); externalCalls.incrementAndGet(); result = obj("content", List.of(obj("type", "text", "text", mapper.writeValueAsString(map(map(request, "params"), "arguments")))), "isError", false); }
                byte[] bytes = mapper.writeValueAsBytes(obj("jsonrpc", "2.0", "id", request.get("id"), "result", result)); exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable error) { errors.add(error.toString()); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        engine.shutdown(); runtime = new ProcessWorkspaceRuntime(mapper, directory.resolve("data").toString()); catalog = new WorkbenchCatalog(store, runtime, new PlanValidator());
        McpManager manager = new McpManager(store, mapper, catalog, resources); engine = new WorkbenchEngine(store, catalog, runtime, resources, mapper, manager);
        return manager;
    }

    private Map<String, Object> mcpSelection(McpManager manager, String text) {
        Map<String, Object> saved = manager.save(obj("name", "actual team MCP", "transport", "HTTP", "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/mcp", "projectIds", List.of(project.get("id")), "allowedTools", List.of("echo")));
        Map<String, Object> discovery = manager.probe(str(saved, "id")); assertTrue(bool(discovery, "available", false));
        return obj("serverId", saved.get("id"), "name", "echo", "configVersion", saved.get("version"), "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", obj("text", text), "timeoutMs", 5000);
    }
}
