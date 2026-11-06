package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 使用真实 Git 和本地协议测试端点验证隔离与工具权限，不代表外部模型兼容验收。
 */
class RuntimeIntegrationTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void nativeRunnersUseIsolationFlagsAndKeepReadOnlyToolPolicy() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("native project"));
        for (String engine : List.of("CODEX", "CLAUDE")) {
            Path captured = temporary.resolve(engine + "-args.json");
            Path cli = nativeFixture(engine, captured, true);
            AtomicInteger calls = new AtomicInteger();
            Map<String, Object> outcome = runtime().run(Map.of("engine", engine, "executable", cli.toString()), "", project.toString(), "fixture prompt", true,
                    List.of("**"), () -> false, ignored -> {}, () -> { calls.incrementAndGet(); return true; });
            assertEquals("SUCCEEDED", outcome.get("status"), outcome.toString());
            String args = Files.readString(captured);
            if (engine.equals("CODEX")) {
                assertTrue(args.contains("--ignore-user-config")); assertTrue(args.contains("--ignore-rules")); assertTrue(args.contains("read-only"));
            } else {
                assertTrue(args.contains("--safe-mode")); assertTrue(args.contains("Read,Glob,Grep")); assertFalse(args.contains("Edit,Write"));
            }
            assertEquals(1, calls.get(), "配置能力探测不能消耗模型预算");
        }
    }

    @Test
    void oldNativeCliIsRejectedBeforePromptDispatchAndBudgetCharge() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("legacy project"));
        for (String engine : List.of("CODEX", "CLAUDE")) {
            Path captured = temporary.resolve(engine + "-legacy-args.json");
            Path cli = nativeFixture(engine + "-legacy", captured, false);
            AtomicInteger calls = new AtomicInteger();
            assertThrows(IllegalArgumentException.class, () -> runtime().run(Map.of("engine", engine, "executable", cli.toString()), "", project.toString(), "must not be sent", false,
                    List.of("**"), () -> false, ignored -> {}, () -> { calls.incrementAndGet(); return true; }));
            assertEquals(0, calls.get()); assertFalse(Files.exists(captured));
        }
    }

    private Path nativeFixture(String name, Path captured, boolean isolated) throws Exception {
        boolean windows = System.getProperty("os.name").toLowerCase().contains("win");
        Path script = temporary.resolve(name + (windows ? ".ps1" : ".sh"));
        String help = isolated ? "--ignore-user-config --ignore-rules --safe-mode" : "legacy options";
        if (windows) {
            Files.writeString(script, "if ($args -contains '--help') { Write-Output '" + help + "'; exit 0 }\n"
                    + "[System.IO.File]::WriteAllText('" + captured.toString().replace("'", "''") + "', ($args | ConvertTo-Json -Compress))\n"
                    + "$null = [Console]::In.ReadToEnd()\nWrite-Output '{\"result\":\"fixture done\"}'\n");
        } else {
            Files.writeString(script, "#!/bin/sh\nfor arg in \"$@\"; do if [ \"$arg\" = '--help' ]; then printf '%s\\n' '" + help + "'; exit 0; fi; done\n"
                    + "printf '%s\\n' \"$@\" > '" + captured.toString().replace("'", "'\\''") + "'\ncat >/dev/null\nprintf '%s\\n' '{\"result\":\"fixture done\"}'\n");
            Files.setPosixFilePermissions(script, PosixFilePermissions.fromString("rwx------"));
        }
        return script;
    }

    @Test
    void prepareKeepsOriginalStagedAndUnstagedChanges() throws Exception {
        Path source = repository();
        Files.writeString(source.resolve("src/greeting.txt"), "staged\n");
        git(source, "add", "src/greeting.txt");
        Files.writeString(source.resolve("src/greeting.txt"), "unstaged\n");
        Files.writeString(source.resolve("src/staged-new.txt"), "new staged file\n");
        git(source, "add", "src/staged-new.txt");
        Files.writeString(source.resolve("src/new.txt"), "untracked\n");
        String status = git(source, "status", "--porcelain=v1", "-uall");
        String staged = git(source, "diff", "--cached", "--binary");
        WorkspaceRuntime runtime = runtime();

        assertThrows(IllegalArgumentException.class, () -> runtime.prepare(source.toString(), "reject-dirty", false));
        Map<String, Object> prepared = runtime.prepare(source.toString(), "include-dirty", true);
        Path work = Path.of(prepared.get("workspace").toString());

        assertNotEquals(source, work);
        assertEquals("unstaged\n", Files.readString(work.resolve("src/greeting.txt")));
        assertEquals("new staged file\n", Files.readString(work.resolve("src/staged-new.txt")));
        assertEquals("untracked\n", Files.readString(work.resolve("src/new.txt")));
        assertEquals(status, git(source, "status", "--porcelain=v1", "-uall"));
        assertEquals(staged, git(source, "diff", "--cached", "--binary"));
        assertEquals(Boolean.TRUE, prepared.get("sourceDirty"));
        assertEquals("", git(work, "remote").strip());
    }

    @Test
    void fingerprintTracksChangesAndReviewSnapshotMatchesExactContent() throws Exception {
        Path source = repository();
        WorkspaceRuntime runtime = runtime();
        Map<String, Object> prepared = runtime.prepare(source.toString(), "versions", false);
        String workspace = prepared.get("workspace").toString();
        Path work = Path.of(workspace);
        String original = runtime.fingerprint(workspace);

        Files.writeString(work.resolve("src/greeting.txt"), "hello agent\n");
        String modified = runtime.fingerprint(workspace);
        assertNotEquals(original, modified);
        Files.writeString(work.resolve("src/new.txt"), "new result\n");
        String withNewFile = runtime.fingerprint(workspace);
        assertNotEquals(modified, withNewFile);
        assertTrue(runtime.diff(workspace).contains("hello agent"));
        assertTrue(runtime.diff(workspace).contains("src/new.txt"));

        String review = runtime.reviewCopy(workspace, "versions", withNewFile);
        assertNotEquals(workspace, review);
        assertEquals(withNewFile, runtime.fingerprint(review));
        Files.delete(work.resolve("src/greeting.txt"));
        assertNotEquals(withNewFile, runtime.fingerprint(workspace));
        assertThrows(IllegalStateException.class, () -> runtime.reviewCopy(workspace, "stale", withNewFile));
        assertEquals("hello\n", Files.readString(source.resolve("src/greeting.txt")));
    }

    @Test
    void exportedPatchReproducesNewBinaryAndTextWithoutTrailingNewline() throws Exception {
        Path source = repository();
        WorkspaceRuntime runtime = runtime();
        String workspace = runtime.prepare(source.toString(), "patch-source", false).get("workspace").toString();
        Path work = Path.of(workspace);
        byte[] binary = new byte[] { 0, 1, 2, 3, 10, 13, 42, 127, (byte) 128, (byte) 255, 0 };
        Files.writeString(work.resolve("src/greeting.txt"), "modified without trailing newline");
        Files.writeString(work.resolve("src/new report.txt"), "new text without trailing newline");
        Files.write(work.resolve("src/new asset.bin"), binary);
        String patch = runtime.exportPatch(workspace);
        assertTrue(patch.contains("GIT binary patch"));
        assertTrue(patch.contains("No newline at end of file"));
        Path patchFile = temporary.resolve("delivery.patch");
        Files.writeString(patchFile, patch, StandardCharsets.UTF_8);

        String destination = runtime.prepare(source.toString(), "patch-destination", false).get("workspace").toString();
        git(Path.of(destination), "apply", "--binary", "--", patchFile.toString());

        assertEquals(runtime.fingerprint(workspace), runtime.fingerprint(destination));
        assertArrayEquals(binary, Files.readAllBytes(Path.of(destination).resolve("src/new asset.bin")));
        assertEquals("new text without trailing newline", Files.readString(Path.of(destination).resolve("src/new report.txt")));
        assertEquals("", git(source, "status", "--porcelain").strip());
    }

    @Test
    void exportedPatchRejectsSensitiveChangesAndTruncatedContent() throws Exception {
        Path source = repository();
        WorkspaceRuntime runtime = runtime();
        String workspace = runtime.prepare(source.toString(), "patch-rejection", false).get("workspace").toString();
        Path work = Path.of(workspace);
        Files.writeString(work.resolve(".env"), "TEST_FIXTURE=value");
        assertThrows(IllegalArgumentException.class, () -> runtime.exportPatch(workspace));
        Files.delete(work.resolve(".env"));
        Files.writeString(work.resolve("src/large.txt"), "large line\n".repeat(60000));
        assertThrows(IllegalStateException.class, () -> runtime.exportPatch(workspace));
    }

    @Test
    void fingerprintCannotConfuseBinaryFileContentWithAnotherFileEntry() throws Exception {
        Path source = repository();
        WorkspaceRuntime runtime = runtime();
        Files.write(source.resolve("a"), new byte[] { 'x', 0, 'b', 0, 2, 'y' });
        String oneFile = runtime.fingerprint(source.toString());
        Files.writeString(source.resolve("a"), "x");
        Files.writeString(source.resolve("b"), "y");
        String twoFiles = runtime.fingerprint(source.toString());

        assertNotEquals(oneFile, twoFiles, "File contents must not be ambiguous with the next file's path and type bytes");
    }

    @Test
    void fingerprintIncludesExecutableModeChangesRepresentedByGitPatch() throws Exception {
        Path source = repository();
        git(source, "config", "core.filemode", "false");
        WorkspaceRuntime runtime = runtime();
        String before = runtime.fingerprint(source.toString());
        git(source, "update-index", "--chmod=+x", "src/greeting.txt");

        assertTrue(runtime.exportPatch(source.toString()).contains("new mode 100755"));
        assertNotEquals(before, runtime.fingerprint(source.toString()), "A changed executable mode is a different deliverable even with identical content");
    }

    @Test
    void fingerprintIncludesPosixExecutablePermissionChangesWithoutStaging() throws Exception {
        Assumptions.assumeTrue(Files.getFileStore(temporary).supportsFileAttributeView("posix"), "Requires real POSIX executable permissions");
        Path source = repository();
        Path file = source.resolve("src/greeting.txt");
        git(source, "config", "core.filemode", "true");
        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rw-r--r--"));
        assertFalse(Files.isExecutable(file));
        WorkspaceRuntime runtime = runtime();
        String before = runtime.fingerprint(source.toString());

        Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));

        assertTrue(Files.isExecutable(file));
        assertTrue(git(source, "ls-files", "--stage", "--", "src/greeting.txt").startsWith("100644 "));
        assertTrue(runtime.exportPatch(source.toString()).contains("new mode 100755"));
        assertNotEquals(before, runtime.fingerprint(source.toString()), "A real unstaged executable permission change must invalidate the deliverable version");
    }

    @Test
    void apiToolGatewayRejectsTraversalScopeEscapeAndGitMetadata() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("tool-project"));
        Files.createDirectories(root.resolve("src"));
        Files.createDirectories(root.resolve(".git"));
        Files.writeString(root.resolve(".git/config"), "original config");
        Path outside = temporary.resolve("outside.txt");
        Files.writeString(outside, "original outside");
        List<Map<String, Object>> commands = List.of(
                tool("read_file", Map.of("path", "../outside.txt")),
                tool("write_file", Map.of("path", "../outside.txt", "content", "escaped")),
                tool("write_file", Map.of("path", outside.toString(), "content", "absolute escape")),
                tool("write_file", Map.of("path", "docs/blocked.txt", "content", "scope escape")),
                tool("write_file", Map.of("path", ".git/config", "content", "metadata changed")),
                tool("write_file", Map.of("path", "src/allowed.txt", "content", "allowed")));

        List<Map<String, Object>> results = exerciseTools(root, false, List.of("src/**"), commands);
        assertEquals(6, results.size());
        for (int index = 0; index < 5; index++) assertTrue(results.get(index).containsKey("error"), "Forbidden operation " + index + " must be rejected");
        assertFalse(results.get(5).containsKey("error"));
        assertEquals("allowed", Files.readString(root.resolve("src/allowed.txt")));
        assertEquals("original outside", Files.readString(outside));
        assertEquals("original config", Files.readString(root.resolve(".git/config")));
        assertFalse(Files.exists(root.resolve("docs/blocked.txt")));
    }

    @Test
    void readOnlyExecutionRejectsInjectedWriteAndCommandTools() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("read-only"));
        Files.writeString(root.resolve("file.txt"), "untouched");
        List<Map<String, Object>> results = exerciseTools(root, true, List.of("**"), List.of(
                tool("write_file", Map.of("path", "file.txt", "content", "changed")),
                tool("delete_file", Map.of("path", "file.txt")),
                tool("run_command", Map.of("command", "echo changed"))));

        assertEquals(3, results.size());
        assertTrue(results.stream().allMatch(result -> result.containsKey("error")));
        assertEquals("untouched", Files.readString(root.resolve("file.txt")));
    }

    @Test
    void apiToolGatewayRejectsSymbolicLinksOrWindowsJunctions() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("symlink-project/src")).getParent();
        Path outside = Files.createDirectories(temporary.resolve("private"));
        Files.writeString(outside.resolve("secret.txt"), "private sample");
        try {
            Files.createSymbolicLink(root.resolve("src/link"), outside);
        } catch (IOException | UnsupportedOperationException | SecurityException error) {
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", root.resolve("src/link").toString(), outside.toString())
                        .redirectErrorStream(true).start();
                String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                Assumptions.assumeTrue(process.waitFor() == 0, "Neither symbolic links nor directory junctions are available: " + output);
            } else {
                Assumptions.assumeTrue(false, "Creating symbolic links is unavailable in this environment: " + error.getClass().getSimpleName());
            }
        }
        List<Map<String, Object>> results = exerciseTools(root, false, List.of("src/**"), List.of(
                tool("read_file", Map.of("path", "src/link/secret.txt")),
                tool("write_file", Map.of("path", "src/link/secret.txt", "content", "changed"))));

        assertTrue(results.stream().allMatch(result -> result.containsKey("error")));
        assertEquals("private sample", Files.readString(outside.resolve("secret.txt")));
    }

    @Test
    void exhaustedBudgetDoesNotSendModelRequest() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("budget-project"));
        AtomicInteger received = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> { received.incrementAndGet(); exchange.sendResponseHeaders(500, -1); exchange.close(); });
        server.start();
        try {
            Map<String, Object> result = runtime().run(route(server), "fixture", root.toString(), "No budget is available", false,
                    List.of("**"), () -> false, message -> {}, () -> false);
            assertEquals(0, received.get());
            assertEquals("FAILED", result.get("status"));
        } finally {
            server.stop(0);
        }
    }

    @Test
    void unknownNestedCommandStopsModelLoopAndRemainsUnknownInOperationLedger() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("unknown-command"));
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        Map<String, Object> command = tool("run_command", Map.of("command", "echo changed"));
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                Map<String, Object> message = requests.getAndIncrement() == 0
                        ? Map.of("role", "assistant", "tool_calls", List.of(command))
                        : Map.of("role", "assistant", "content", "The model incorrectly claims everything passed");
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        WorkspaceRuntime executor = new WorkspaceRuntime(json, temporary.resolve("state").toString()) {
            @Override
            public Map<String, Object> verify(String workspace, String commandText, BooleanSupplier cancelled, Consumer<String> log) {
                return Map.of("exitCode", 0, "status", "UNKNOWN", "output", "A child process has not confirmed its stop");
            }
        };
        try {
            Map<String, Object> result = executor.run(route(server), "fixture", root.toString(), "Run the approved check", false,
                    List.of("**"), () -> false, message -> {}, () -> true);
            assertEquals("UNKNOWN", result.get("status"));
            assertEquals(1, requests.get(), "No further model request may turn an unknown tool effect into success");
            try (var entries = Files.list(root.getParent().resolve("operations"))) {
                List<Path> operations = entries.filter(file -> file.toString().endsWith(".json")).toList();
                assertEquals(1, operations.size());
                assertEquals("UNKNOWN", json.readTree(operations.getFirst().toFile()).get("state").asText());
            }
        } finally {
            server.stop(0);
        }
    }

    @Test
    void fixedMcpAliasIsExposedWithoutArgumentsAndReturnsThroughModelLoop() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("fixed-mcp-loop"));
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<String, Object>> exposed = new AtomicReference<>();
        Map<String, Object> fixedCall = tool("mcp_fixed-action", Map.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, Object> incoming = json.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                List<Map<String, Object>> tools = (List<Map<String, Object>>) incoming.get("tools");
                tools.stream().filter(item -> "mcp_fixed-action".equals(String.valueOf(((Map<String, Object>) item.get("function")).get("name"))))
                    .findFirst().ifPresent(exposed::set);
                Map<String, Object> message = requests.getAndIncrement() == 0
                    ? Map.of("role", "assistant", "tool_calls", List.of(fixedCall))
                    : Map.of("role", "assistant", "content", "Completed");
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        AtomicInteger dispatches = new AtomicInteger();
        try {
            List<Map<String, Object>> bindings = List.of(Map.of("alias", "mcp_fixed-action", "serverName", "fixture", "name", "write_note", "description", "fixed"));
            Map<String, Object> result = runtime().runWithMcp(route(server), "fixture", root.toString(), "Use the confirmed action", false, List.of("**"),
                () -> false, ignored -> {}, () -> true, bindings, alias -> { dispatches.incrementAndGet(); return Map.of("state", "ACKNOWLEDGED", "result", Map.of("content", "ok")); });
            assertEquals("SUCCEEDED", result.get("status"));
            assertEquals(1, dispatches.get());
            assertEquals(2, requests.get());
            assertEquals(List.of(), ((Map<String, Object>) ((Map<String, Object>) exposed.get().get("function")).get("parameters")).get("required"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"UNKNOWN,UNKNOWN", "CANCELLED,CANCELLED", "REJECTED,WAITING_INPUT"})
    void terminalFixedMcpResultStopsFurtherModelRequests(String operationState, String expectedStatus) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("unknown-fixed-mcp-loop"));
        AtomicInteger requests = new AtomicInteger();
        Map<String, Object> fixedCall = tool("mcp_fixed-action", Map.of());
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "tool_calls", List.of(fixedCall))))));
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            Map<String, Object> result = runtime().runWithMcp(route(server), "fixture", root.toString(), "Use the confirmed action", false, List.of("**"),
                () -> false, ignored -> {}, () -> true, List.of(Map.of("alias", "mcp_fixed-action", "serverName", "fixture", "name", "write_note")),
                alias -> Map.of("state", operationState, "error", "action requires attention"));
            assertEquals(expectedStatus, result.get("status")); assertEquals(1, requests.get());
        } finally { server.stop(0); }
    }

    private WorkspaceRuntime runtime() {
        return new WorkspaceRuntime(json, temporary.resolve("state").toString());
    }

    private Path repository() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("中文 source project"));
        Files.createDirectories(source.resolve("src"));
        Files.writeString(source.resolve("src/greeting.txt"), "hello\n");
        git(source, "init");
        git(source, "config", "user.name", "Runtime Test");
        git(source, "config", "user.email", "runtime@example.invalid");
        git(source, "add", ".");
        git(source, "commit", "-m", "Create test fixture");
        return source;
    }

    private String git(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Map<String, Object> tool(String name, Map<String, Object> arguments) throws Exception {
        return Map.of("id", "fixture-" + name + "-" + arguments.hashCode(), "type", "function",
                "function", Map.of("name", name, "arguments", json.writeValueAsString(arguments)));
    }

    private Map<String, Object> route(HttpServer server) {
        return Map.of("engine", "API", "model", "fixture-model", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "allowedCommands", List.of("echo changed"));
    }

    /**
     * 通过真实 HTTP 模型协议注入工具请求，读取下一轮送回的工具结果。
     */
    private List<Map<String, Object>> exerciseTools(Path root, boolean readOnly, List<String> scope, List<Map<String, Object>> requests) throws Exception {
        List<Map<String, Object>> results = new CopyOnWriteArrayList<>();
        AtomicInteger count = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, Object> incoming = json.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                Map<String, Object> message;
                if (count.getAndIncrement() == 0) {
                    message = Map.of("role", "assistant", "tool_calls", requests);
                } else {
                    for (Object raw : (List<?>) incoming.get("messages")) {
                        if (raw instanceof Map<?, ?> value && "tool".equals(value.get("role"))) {
                            results.add(json.readValue(value.get("content").toString(), new TypeReference<>() {}));
                        }
                    }
                    message = Map.of("role", "assistant", "content", "Test fixture completed");
                }
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally {
                exchange.close();
            }
        });
        server.start();
        try {
            Map<String, Object> result = runtime().run(route(server), "fixture", root.toString(), "Execute the fixture tools", readOnly,
                    scope, () -> false, message -> {}, () -> true);
            assertEquals("SUCCEEDED", result.get("status"), result.toString());
            assertEquals(2, count.get());
            return results;
        } finally {
            server.stop(0);
        }
    }
}
