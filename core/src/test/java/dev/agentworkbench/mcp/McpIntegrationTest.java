package dev.agentworkbench.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ExecutionStore;
import dev.agentworkbench.WorkbenchCatalog;
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
    void close() throws Exception { if (http != null) { http.stop(0); } if (store != null) { store.close(); } }

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
