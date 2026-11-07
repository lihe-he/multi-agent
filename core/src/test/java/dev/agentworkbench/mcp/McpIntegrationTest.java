package dev.agentworkbench.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ExecutionStore;
import dev.agentworkbench.WorkbenchCatalog;
import dev.agentworkbench.ResourceManager;
import dev.agentworkbench.WorkbenchEngine;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class McpIntegrationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkbenchCatalog catalog;
    private McpManager manager;
    private HttpServer http;
    private final AtomicInteger calls = new AtomicInteger();
    private final AtomicInteger definition = new AtomicInteger(1);
    private final AtomicBoolean disconnect = new AtomicBoolean();
    private final AtomicBoolean stream = new AtomicBoolean();
    private volatile CountDownLatch callEntered;
    private volatile CountDownLatch releaseCall;

    @BeforeEach
    void setup() throws Exception {
        store = new ExecutionStore(mapper, directory.resolve("store").toString());
        store.put("project", obj("id", "p", "name", "Project P")); store.put("project", obj("id", "q", "name", "Project Q"));
        catalog = mock(WorkbenchCatalog.class); when(catalog.redact(anyString())).thenAnswer(invocation -> invocation.getArgument(0));
        manager = new McpManager(store, mapper, catalog);
        http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/mcp", exchange -> {
            if ("DELETE".equals(exchange.getRequestMethod())) { exchange.sendResponseHeaders(204, -1); exchange.close(); return; }
            Map<String, Object> request = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() { });
            String method = str(request, "method");
            if (method.startsWith("notifications/")) { exchange.sendResponseHeaders(202, -1); exchange.close(); return; }
            Map<String, Object> result;
            switch (method) {
                case "initialize" -> { result = obj("protocolVersion", "2025-06-18", "capabilities", obj("tools", obj()), "serverInfo", obj("name", "fixture", "version", "1")); exchange.getResponseHeaders().set("Mcp-Session-Id", "fixture-session"); }
                case "tools/list" -> result = obj("tools", List.of(obj("name", "echo", "description", "fixture definition " + definition.get(), "inputSchema", obj("type", "object"), "annotations", obj("readOnlyHint", true))));
                case "tools/call" -> {
                    calls.incrementAndGet();
                    if (callEntered != null) {
                        callEntered.countDown();
                        try {
                            if (!releaseCall.await(10, TimeUnit.SECONDS)) { exchange.close(); return; }
                        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); exchange.close(); return; }
                    }
                    if (disconnect.get()) { exchange.close(); return; }
                    result = obj("content", List.of(obj("type", "text", "text", mapper.writeValueAsString(map(map(request, "params"), "arguments")))), "isError", false);
                }
                default -> result = obj();
            }
            String payload = mapper.writeValueAsString(obj("jsonrpc", "2.0", "id", request.get("id"), "result", result));
            if (stream.get()) { payload = "event: message\ndata: " + payload + "\n\n"; exchange.getResponseHeaders().set("Content-Type", "text/event-stream"); }
            else { exchange.getResponseHeaders().set("Content-Type", "application/json"); }
            byte[] bytes = payload.getBytes(StandardCharsets.UTF_8); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        });
        http.start();
    }

    @AfterEach
    void close() throws Exception { if (manager != null) manager.stopObservers(); if (http != null) { http.stop(0); } if (store != null) { store.close(); } }

    private Map<String, Object> httpConfiguration() {
        return obj("name", "HTTP fixture", "transport", "HTTP", "url", "http://127.0.0.1:" + http.getAddress().getPort() + "/mcp", "enabled", true, "projectIds", List.of("p"), "allowedTools", List.of("echo"));
    }

    private Map<String, Object> callBody(Map<String, Object> discovery, String requestId) {
        return obj("projectId", "p", "name", "echo", "arguments", obj("text", "你好"), "definitionVersion", discovery.get("definitionVersion"), "requestId", requestId);
    }

    @Test
    void httpHandshakeCallAndRequestDeduplicationUseRealEndpoint() {
        var server = manager.save(httpConfiguration()); String id = str(server, "id");
        var discovery = manager.probe(id); assertTrue(bool(discovery, "available", false));
        var input = callBody(discovery, "request-1");
        var first = manager.call(id, input); var second = manager.call(id, input);
        assertEquals("ACKNOWLEDGED", str(first, "state")); assertTrue(bool(second, "deduplicated", false)); assertEquals(1, calls.get());
        input.put("arguments", obj("text", "changed")); assertThrows(ApiException.class, () -> manager.call(id, input)); assertEquals(1, calls.get());
    }

    @Test
    void httpSseResponsesAreConsumed() {
        stream.set(true); var server = manager.save(httpConfiguration()); String id = str(server, "id");
        var discovery = manager.probe(id); assertTrue(bool(discovery, "available", false), discovery::toString);
        assertEquals("ACKNOWLEDGED", str(manager.call(id, callBody(discovery, "sse-1")), "state")); assertEquals(1, calls.get());
    }

    @Test
    void changedToolDefinitionCannotReusePinnedBinding() {
        var server = manager.save(httpConfiguration()); String id = str(server, "id"); var discovery = manager.probe(id);
        definition.incrementAndGet(); ApiException error = assertThrows(ApiException.class, () -> manager.call(id, callBody(discovery, "changed")));
        assertEquals(409, error.status()); assertEquals(0, calls.get());
    }

    @Test
    void peerReadOnlyHintDoesNotAuthorizeProjectOrTool() {
        var server = manager.save(httpConfiguration()); String id = str(server, "id"); var discovery = manager.probe(id);
        var outside = callBody(discovery, "outside"); outside.put("projectId", "q");
        assertEquals(403, assertThrows(ApiException.class, () -> manager.call(id, outside)).status());
        var tool = callBody(discovery, "other-tool"); tool.put("name", "unapproved");
        assertEquals(403, assertThrows(ApiException.class, () -> manager.call(id, tool)).status()); assertEquals(0, calls.get());
    }

    @Test
    void lostResponseIsUnknownAndSameRequestIsNeverReplayed() {
        var server = manager.save(httpConfiguration()); String id = str(server, "id"); var discovery = manager.probe(id); disconnect.set(true);
        var request = callBody(discovery, "lost"); var result = manager.call(id, request);
        assertEquals("UNKNOWN", str(result, "state")); assertEquals(1, calls.get());
        assertThrows(ApiException.class, () -> manager.call(id, request)); assertEquals(1, calls.get());
    }

    @Test
    void fixedTaskActionAtomicallyConsumesBudgetAndDeduplicates() {
        var saved = manager.save(httpConfiguration()); String serverId = str(saved, "id");
        var discovery = manager.probe(serverId);
        Map<String, Object> selection = obj("serverId", serverId, "name", "echo", "configVersion", saved.get("version"),
            "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", obj("text", "confirmed"), "timeoutMs", 5000);
        Map<String, Object> authorization = McpTaskPolicy.snapshot(store, "p", obj("engine", "API"), List.of(selection), 1);
        Map<String, Object> task = obj("id", "task-fixed", "projectId", "p", "state", "RUNNING", "mcpAuthorization", authorization,
            "mcpBudget", McpTaskPolicy.budget(obj("maxCalls", 2, "maxDurationMs", 10000), Map.of()));
        store.createTask(task);
        var first = manager.invokeFixedTool("task-fixed", "IMPLEMENT", str(objects(authorization.get("bindings")).getFirst(), "alias"), () -> false);
        var second = manager.invokeFixedTool("task-fixed", "IMPLEMENT", str(objects(authorization.get("bindings")).getFirst(), "alias"), () -> false);
        assertEquals("ACKNOWLEDGED", str(first, "state")); assertTrue(bool(second, "deduplicated", false)); assertEquals(1, calls.get());
        assertEquals(1, num(map(store.get("task", "task-fixed"), "mcpBudget"), "usedCalls", 0));
        assertEquals("CONFIRMED", str(manager.operations(serverId).getFirst(), "stopState"));
    }

    @Test
    void unknownFixedTaskActionIsPersistedAndNeverReplayed() {
        var saved = manager.save(httpConfiguration()); String serverId = str(saved, "id"); var discovery = manager.probe(serverId);
        Map<String, Object> selection = obj("serverId", serverId, "name", "echo", "configVersion", saved.get("version"),
            "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", obj("text", "confirmed"), "timeoutMs", 5000);
        Map<String, Object> authorization = McpTaskPolicy.snapshot(store, "p", obj("engine", "API"), List.of(selection), 1);
        store.createTask(obj("id", "task-unknown", "projectId", "p", "state", "RUNNING", "mcpAuthorization", authorization,
            "mcpBudget", McpTaskPolicy.budget(obj("maxCalls", 2, "maxDurationMs", 10000), Map.of())));
        disconnect.set(true);
        String alias = str(objects(authorization.get("bindings")).getFirst(), "alias");
        assertEquals("UNKNOWN", str(manager.invokeFixedTool("task-unknown", "IMPLEMENT", alias, () -> false), "state"));
        assertEquals("UNKNOWN", str(manager.invokeFixedTool("task-unknown", "IMPLEMENT", alias, () -> false), "state"));
        assertEquals(1, calls.get()); assertEquals("UNKNOWN", str(store.find("mcpOperation", store.list("mcpOperation").getFirst().get("id").toString()), "state"));
        assertEquals(1, num(map(store.get("task", "task-unknown"), "mcpBudget"), "usedCalls", 0));
    }

    private String fixedTask(String taskId, Map<String, Object> saved, Map<String, Object> discovery) {
        var selection = obj("serverId", saved.get("id"), "name", "echo", "configVersion", saved.get("version"),
            "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", obj("text", "confirmed"), "timeoutMs", 5000);
        var authorization = McpTaskPolicy.snapshot(store, "p", obj("engine", "API"), List.of(selection), 1);
        store.createTask(obj("id", taskId, "projectId", "p", "state", "RUNNING", "mcpAuthorization", authorization,
            "mcpBudget", McpTaskPolicy.budget(obj("maxCalls", 2, "maxDurationMs", 10000), Map.of())));
        return str(objects(authorization.get("bindings")).getFirst(), "alias");
    }

    @Test
    void cancellingRealStdioTreeConfirmsLocalStopButKeepsEffectUnknown() throws Exception {
        Path marker = directory.resolve("stdio-pids.json"), script = directory.resolve("hanging-tree.mjs");
        Files.writeString(script, """
            import readline from 'node:readline';
            import {spawn} from 'node:child_process';
            import {writeFileSync} from 'node:fs';
            const io=readline.createInterface({input:process.stdin});
            io.on('line',line=>{
              const req=JSON.parse(line); if(req.id===undefined)return;
              let result;
              if(req.method==='initialize')result={protocolVersion:'2025-06-18',capabilities:{tools:{}},serverInfo:{name:'tree',version:'1'}};
              else if(req.method==='tools/list')result={tools:[{name:'echo',inputSchema:{type:'object'}}]};
              else if(req.method==='tools/call'){
                const child=spawn(process.execPath,['-e','setInterval(()=>{},1000)'],{stdio:'ignore'});
                setTimeout(()=>writeFileSync(process.argv[2],JSON.stringify([process.pid,child.pid])),300);
                return;
              }
              process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:req.id,result})+'\\n');
            });
            """);
        var saved = manager.save(obj("name", "Tree fixture", "transport", "STDIO", "command", "node", "args", List.of(script.toString(), marker.toString()),
            "enabled", true, "projectIds", List.of("p"), "allowedTools", List.of("echo")));
        String serverId = str(saved, "id"), alias = fixedTask("stdio-cancel", saved, manager.probe(serverId));
        AtomicBoolean cancelled = new AtomicBoolean();
        List<Long> pids = new java.util.ArrayList<>();
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var invocation = executor.submit(() -> manager.invokeFixedTool("stdio-cancel", "IMPLEMENT", alias, cancelled::get));
            try {
                long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(4);
                while (!Files.exists(marker) && System.nanoTime() < deadline) Thread.sleep(20);
                assertTrue(Files.exists(marker), "The real tool and its child must start before cancellation");
                pids.addAll(mapper.readValue(Files.readString(marker), new TypeReference<List<Long>>() { }));
                assertEquals(2, pids.size());
                cancelled.set(true);
                assertEquals("UNKNOWN", str(invocation.get(3, TimeUnit.SECONDS), "state"));
                assertTrue(pids.stream().noneMatch(pid -> ProcessHandle.of(pid).map(ProcessHandle::isAlive).orElse(false)), "Parent and observed child must stop");
                var operation = manager.operations(serverId).getFirst();
                assertEquals("CONFIRMED", str(operation, "stopState"));
                assertEquals("UNKNOWN", str(operation, "dispatchState"));
                assertEquals("UNKNOWN", str(manager.invokeFixedTool("stdio-cancel", "IMPLEMENT", alias, () -> false), "state"));
                assertEquals(1, num(map(store.get("task", "stdio-cancel"), "mcpBudget"), "usedCalls", -1));
            } finally {
                cancelled.set(true);
                for (long pid : pids) ProcessHandle.of(pid).filter(ProcessHandle::isAlive).ifPresent(ProcessHandle::destroyForcibly);
            }
        }
    }

    @Test
    void unconfirmedStopRetainsCapacityAndBlocksReconciliationDespiteAcknowledgedResult() throws Exception {
        assertUnconfirmedStop(false);
    }

    @Test
    void unconfirmedStopBeforeDispatchAlsoBlocksReconciliationAndKeepsNotSentReceipt() throws Exception {
        assertUnconfirmedStop(true);
    }

    @Test
    void recoveryPreservesReceivedResultWhileRequiringMissingStopConfirmation() {
        store.put("mcpOperation", obj("id", "crash-after-receipt", "state", "ACKNOWLEDGED", "dispatchState", "ACKNOWLEDGED",
            "stopState", "UNCONFIRMED", "result", obj("content", List.of(obj("type", "text", "text", "Saved receipt")))));
        manager.recover();
        var recovered = store.get("mcpOperation", "crash-after-receipt");
        assertEquals("UNKNOWN", str(recovered, "state")); assertEquals("UNCONFIRMED", str(recovered, "stopState"));
        assertEquals("ACKNOWLEDGED", str(recovered, "dispatchState"));
        assertTrue(recovered.containsKey("result")); assertTrue(bool(recovered, "canReconcile", false));
    }

    private void assertUnconfirmedStop(boolean definitionChanged) throws Exception {
        ResourceManager resources = new ResourceManager(); AtomicBoolean stopped = new AtomicBoolean(true);
        manager = new McpManager(store, mapper, catalog, resources, (server, token, json, cancelled, timeout) -> {
            McpTransport delegate = McpTransport.open(httpConfiguration(), token, json, cancelled, timeout);
            return new McpTransport() {
                @Override public Map<String, Object> request(String method, Map<String, Object> params) throws Exception { return delegate.request(method, params); }
                @Override public void notify(String method, Map<String, Object> params) throws Exception { delegate.notify(method, params); }
                @Override public boolean stopConfirmed() { return stopped.get(); }
                @Override public void close() { delegate.close(); }
            };
        });
        var saved = manager.save(obj("name", "Controlled stop fixture", "transport", "STDIO", "command", "fixture", "args", List.of(),
            "enabled", true, "projectIds", List.of("p"), "allowedTools", List.of("echo")));
        String serverId = str(saved, "id"), alias = fixedTask("retained", saved, manager.probe(serverId));
        if (definitionChanged) definition.incrementAndGet();
        stopped.set(false);
        try {
            var outcome = manager.invokeFixedTool("retained", "IMPLEMENT", alias, () -> false);
            assertEquals("UNKNOWN", str(outcome, "state"));
            var operation = manager.operations(serverId).getFirst();
            assertEquals("UNCONFIRMED", str(operation, "stopState"));
            assertFalse(bool(operation, "canReconcile", true));
            assertEquals(definitionChanged ? "NOT_SENT" : "ACKNOWLEDGED", str(operation, "dispatchState"));
            assertEquals(definitionChanged ? 0 : 1, calls.get());
            if (!definitionChanged) assertTrue(operation.containsKey("result"), "A received result must remain available during cleanup uncertainty");
            assertEquals(1, resources.snapshot().get("process")); assertEquals(1, resources.snapshot().get("tool"));
            assertEquals(409, assertThrows(ApiException.class, () -> manager.probe(serverId)).status());
            store.editTask("retained", task -> task.put("state", "UNKNOWN"), "TEST", "Unknown stop");
            WorkbenchEngine engine = new WorkbenchEngine(store, catalog, mock(WorkspaceRuntime.class), resources, mapper, manager);
            var reconciliation = obj("processesStopped", true, "sideEffectsChecked", true, "note", "User assertion cannot override a live local transport");
            assertEquals(409, assertThrows(ApiException.class, () -> engine.action("retained", "reconcile", reconciliation)).status());
            assertEquals("UNKNOWN", str(store.get("task", "retained"), "state"));
            assertEquals("UNKNOWN", str(manager.operations(serverId).getFirst(), "state"));
            stopped.set(true);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
            while (resources.snapshot().get("process") != 0 && System.nanoTime() < deadline) Thread.sleep(20);
            assertEquals(0, resources.snapshot().get("process"), "Capacity must be released after observed exit without a new dispatch");
            manager.requireTaskStopped("retained");
            assertEquals(0, resources.snapshot().get("process")); assertEquals(0, resources.snapshot().get("tool"));
            assertEquals("CONFIRMED", str(manager.operations(serverId).getFirst(), "stopState"));
            assertEquals("PAUSED", str(engine.action("retained", "reconcile", reconciliation), "state"));
            assertEquals(definitionChanged ? "REJECTED" : "UNKNOWN", str(manager.invokeFixedTool("retained", "IMPLEMENT", alias, () -> false), "state"));
            assertEquals(definitionChanged ? 0 : 1, calls.get(), "Reconciliation must not replay the old action");
        } finally { stopped.set(true); manager.requireTaskStopped("retained"); }
    }

    @Test
    void fixedActionWaitingForServiceCanCancelWithoutBudgetOrDispatch() throws Exception {
        var saved = manager.save(httpConfiguration()); String serverId = str(saved, "id"); var discovery = manager.probe(serverId);
        String alias = fixedTask("waiting-task", saved, discovery);
        callEntered = new CountDownLatch(1); releaseCall = new CountDownLatch(1);
        AtomicBoolean cancelled = new AtomicBoolean(); CountDownLatch waiting = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var holder = executor.submit(() -> manager.call(serverId, callBody(discovery, "holding-service")));
                assertTrue(callEntered.await(3, TimeUnit.SECONDS));
                var waiter = executor.submit(() -> manager.invokeFixedTool("waiting-task", "IMPLEMENT", alias, () -> {
                    waiting.countDown(); return cancelled.get();
                }));
                assertTrue(waiting.await(2, TimeUnit.SECONDS), "Waiting must check cancellation while the service remains busy");
                cancelled.set(true);
                assertEquals("CANCELLED", str(waiter.get(2, TimeUnit.SECONDS), "state"));
                assertFalse(holder.isDone(), "Cancellation must not wait for the unrelated call");
                assertEquals(1, calls.get());
                assertEquals(0, num(map(store.get("task", "waiting-task"), "mcpBudget"), "usedCalls", -1));
                assertTrue(store.list("mcpOperation").stream().noneMatch(item -> "waiting-task".equals(str(item, "taskId"))));
                releaseCall.countDown(); assertEquals("ACKNOWLEDGED", str(holder.get(3, TimeUnit.SECONDS), "state"));
            } finally { releaseCall.countDown(); }
        }
    }

    @Test
    void concurrentFixedActionReturnsOneReceiptAndConsumesBudgetOnce() throws Exception {
        var saved = manager.save(httpConfiguration()); String serverId = str(saved, "id"); var discovery = manager.probe(serverId);
        String alias = fixedTask("concurrent-task", saved, discovery);
        callEntered = new CountDownLatch(1); releaseCall = new CountDownLatch(1);
        CountDownLatch waiting = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var first = executor.submit(() -> manager.invokeFixedTool("concurrent-task", "IMPLEMENT", alias, () -> false));
                assertTrue(callEntered.await(3, TimeUnit.SECONDS));
                var second = executor.submit(() -> manager.invokeFixedTool("concurrent-task", "IMPLEMENT", alias, () -> {
                    waiting.countDown(); return false;
                }));
                assertTrue(waiting.await(2, TimeUnit.SECONDS));
                releaseCall.countDown();
                assertEquals("ACKNOWLEDGED", str(first.get(3, TimeUnit.SECONDS), "state"));
                var receipt = second.get(3, TimeUnit.SECONDS);
                assertEquals("ACKNOWLEDGED", str(receipt, "state")); assertTrue(bool(receipt, "deduplicated", false));
                assertEquals(1, calls.get()); assertEquals(1, store.list("mcpOperation").size());
                var budget = map(store.get("task", "concurrent-task"), "mcpBudget");
                assertEquals(1, num(budget, "usedCalls", -1)); assertEquals(5000, num(budget, "committedDurationMs", -1));
            } finally { releaseCall.countDown(); }
        }
    }

    @Test
    void queuedActionCannotDispatchAfterPreviousCallBecomesUnknown() throws Exception {
        var saved = manager.save(httpConfiguration()); String serverId = str(saved, "id"); var discovery = manager.probe(serverId);
        String alias = fixedTask("queued-task", saved, discovery);
        callEntered = new CountDownLatch(1); releaseCall = new CountDownLatch(1); disconnect.set(true);
        CountDownLatch waiting = new CountDownLatch(2);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            try {
                var holder = executor.submit(() -> manager.call(serverId, callBody(discovery, "lost-holder")));
                assertTrue(callEntered.await(3, TimeUnit.SECONDS));
                var waiter = executor.submit(() -> manager.invokeFixedTool("queued-task", "IMPLEMENT", alias, () -> {
                    waiting.countDown(); return false;
                }));
                assertTrue(waiting.await(2, TimeUnit.SECONDS)); releaseCall.countDown();
                assertEquals("UNKNOWN", str(holder.get(3, TimeUnit.SECONDS), "state"));
                var error = assertThrows(java.util.concurrent.ExecutionException.class, () -> waiter.get(3, TimeUnit.SECONDS));
                assertInstanceOf(ApiException.class, error.getCause()); assertEquals(409, ((ApiException) error.getCause()).status());
                assertEquals(1, calls.get());
                assertEquals(0, num(map(store.get("task", "queued-task"), "mcpBudget"), "usedCalls", -1));
            } finally { releaseCall.countDown(); }
        }
    }

    @Test
    void stdioStartsRealProcessAndExchangesJsonRpc() throws Exception {
        Path script = directory.resolve("MCP 中文 fixture.mjs");
        Files.writeString(script, """
            import readline from 'node:readline';
            const io = readline.createInterface({input:process.stdin});
            io.on('line', line => {
              const req = JSON.parse(line);
              if (req.id === undefined) return;
              let result = {};
              if (req.method === 'initialize') result = {protocolVersion:'2025-06-18',capabilities:{tools:{}},serverInfo:{name:'stdio-fixture',version:'1'}};
              if (req.method === 'tools/list') result = {tools:[{name:'echo',inputSchema:{type:'object'}}]};
              if (req.method === 'tools/call') result = {content:[{type:'text',text:JSON.stringify(req.params.arguments)}],isError:false};
              process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:req.id,result})+'\\n');
            });
            """);
        var server = manager.save(obj("name", "stdio", "transport", "STDIO", "command", "node", "args", List.of(script.toString()), "projectIds", List.of("p"), "allowedTools", List.of("echo"), "enabled", true));
        String id = str(server, "id"); var discovery = manager.probe(id);
        assertTrue(bool(discovery, "available", false), discovery::toString);
        assertEquals("ACKNOWLEDGED", str(manager.call(id, callBody(discovery, "stdio-1")), "state"));
    }

    @Test
    void projectServerCannotExposeAnotherProjectsTaskOrMutationTools() {
        store.put("task", obj("id", "foreign", "projectId", "q", "state", "COMPLETED"));
        var controller = new McpController(manager, store, catalog, mapper, new McpAccessRegistry());
        var denied = controller.rpc("p", obj("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", obj("name", "task_status", "arguments", obj("taskId", "foreign"))), "2025-06-18");
        assertTrue(denied.getBody().containsKey("error"));
        var mutation = controller.rpc("p", obj("jsonrpc", "2.0", "id", 2, "method", "tools/call", "params", obj("name", "write_file", "arguments", obj())), "2025-06-18");
        assertTrue(mutation.getBody().containsKey("error"));
        var initialization = controller.rpc("p", obj("jsonrpc", "2.0", "id", 3, "method", "initialize", "params", obj()), null);
        assertEquals("2025-06-18", str(map(initialization.getBody(), "result"), "protocolVersion"));
    }

    @Test
    void mcpCredentialsDoNotTransferToChangedServiceIdentity() {
        var configuration = httpConfiguration(); configuration.put("token", "mcp-secret");
        var first = manager.save(configuration); assertTrue(bool(first, "hasToken", false));
        configuration.remove("token"); configuration.put("id", first.get("id")); configuration.put("url", "https://other.example/mcp");
        var moved = manager.save(configuration); assertFalse(bool(moved, "hasToken", true)); assertTrue(moved.containsKey("credentialNotice"));
        assertFalse(store.get("mcpServer", str(first, "id")).toString().contains("mcp-secret"));
    }

    @Test
    void stdioCannotInheritWorkbenchControlEnvironment() throws Exception {
        Path script = directory.resolve("environment-fixture.mjs");
        Files.writeString(script, """
            import readline from 'node:readline';
            const io=readline.createInterface({input:process.stdin});
            io.on('line',line=>{
              const req=JSON.parse(line); if(req.id===undefined)return;
              const leaked=Object.keys(process.env).filter(key=>key.toUpperCase().startsWith('WORKBENCH_'));
              process.stdout.write(JSON.stringify({jsonrpc:'2.0',id:req.id,result:{leaked}})+'\\n');
            });
            """);
        String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        ProcessBuilder builder = new ProcessBuilder(executable, "-Djdk.net.unixdomain.tmpdir=" + directory, "-cp", System.getProperty("java.class.path"), EnvironmentProbe.class.getName(), script.toString());
        builder.environment().put("WORKBENCH_TOKEN", "must-not-leak-platform-token"); builder.environment().put("WORKBENCH_PORT", "18765"); builder.environment().put("WORKBENCH_DATA_DIR", "private-data-location"); builder.environment().put("WorkBench_MixedCase", "also-private");
        builder.redirectErrorStream(true); Process probe = builder.start();
        try {
            assertTrue(probe.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(probe.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, probe.exitValue(), output); assertTrue(output.contains("\"leaked\":[]"), output);
            assertFalse(output.contains("must-not-leak-platform-token"));
        } finally { if (probe.isAlive()) { probe.descendants().forEach(ProcessHandle::destroyForcibly); probe.destroyForcibly(); } }
    }

    public static class EnvironmentProbe {
        public static void main(String[] args) throws Exception {
            var server = obj("transport", "STDIO", "command", "node", "args", List.of(args[0]));
            try (McpTransport transport = McpTransport.open(server, "", new ObjectMapper())) { System.out.println(new ObjectMapper().writeValueAsString(transport.request("ping", obj()))); }
        }
    }
}
