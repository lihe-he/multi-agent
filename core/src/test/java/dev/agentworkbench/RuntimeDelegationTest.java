package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 在真实 HTTP 和 Git 现场校验范围过滤、交接冲突与执行位，不改动原项目。
 */
class RuntimeDelegationTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private WorkspaceRuntime runtime() { return new WorkspaceRuntime(mapper, directory.resolve("data").toString()); }
    private Map<String, Object> permissions(List<String> capabilities, List<String> scope) { return obj("capabilities", capabilities, "readScopeLayers", List.of(scope), "writeScopeLayers", List.of(scope)); }

    private String git(Path root, String... args) throws Exception {
        var command = new java.util.ArrayList<>(List.of("git", "-c", "core.autocrlf=false")); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.waitFor(), output); return output;
    }

    private Path repository() throws Exception {
        Path root = Files.createDirectories(directory.resolve("中文 parent repository")); Files.createDirectories(root.resolve("src"));
        Files.writeString(root.resolve("src/a.txt"), "base-a\n"); Files.writeString(root.resolve("src/z.txt"), "base-z\n");
        Files.writeString(root.resolve("src/script.sh"), "echo base\n"); Files.writeString(root.resolve("notes.txt"), "original notes\n");
        git(root, "init", "--initial-branch=main"); git(root, "config", "user.name", "Delegation Test"); git(root, "config", "user.email", "delegation@example.invalid"); git(root, "config", "core.filemode", "false");
        git(root, "add", "."); git(root, "commit", "-m", "fixture"); return root;
    }

    @Test
    void scopedToolsFilterBeforeCountingAndReleaseModelLeaseBeforeLocalTools() throws Exception {
        Path root = Files.createDirectories(directory.resolve("scoped project")), outside = Files.createDirectories(root.resolve("aaa-outside"));
        for (int index = 0; index < 2105; index++) Files.writeString(outside.resolve("file-" + index + ".txt"), "outside-private-value\n");
        Files.createDirectories(root.resolve("src")); Files.writeString(root.resolve("src/authorized.txt"), "authorized-match\n");
        AtomicInteger modelLeases = new AtomicInteger(), requests = new AtomicInteger(); List<Object> results = new CopyOnWriteArrayList<>(); List<String> errors = new CopyOnWriteArrayList<>();
        AgentToolSession session = new AgentToolSession() {
            @Override public List<Map<String, Object>> definitions() { return List.of(); }
            @Override public AutoCloseable acquireModel() { modelLeases.incrementAndGet(); return () -> modelLeases.decrementAndGet(); }
            @Override public AutoCloseable acquireTool() { assertEquals(0, modelLeases.get(), "the HTTP receipt must release the model lease before each local tool"); return () -> {}; }
            @Override public Map<String, Object> permissions() { return RuntimeDelegationTest.this.permissions(List.of("read", "search"), List.of("src/")); }
            @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { throw new AssertionError("no team tools in this fixture"); }
        };
        ModelCallObserver observer = new ModelCallObserver() {
            @Override public boolean begin(Map<String, Object> metadata) { return true; }
            @Override public void end(Map<String, Object> observation) { assertEquals("SUCCEEDED", str(observation, "state")); }
            @Override public AgentToolSession tools() { return session; }
        };
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        try (var workers = Executors.newVirtualThreadPerTaskExecutor()) {
            server.setExecutor(workers); server.createContext("/", exchange -> {
                try {
                    Map<String, Object> body = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                    assertFalse(objects(body.get("tools")).stream().anyMatch(tool -> "write_file".equals(str(map(tool, "function"), "name"))));
                    int number = requests.incrementAndGet(); Map<String, Object> answer;
                    if (number == 1) {
                        List<Map<String, Object>> calls = new java.util.ArrayList<>();
                        List<Map<String, Object>> inputs = List.of(obj("name", "list_files", "args", obj()), obj("name", "search_files", "args", obj("query", "authorized-match")), obj("name", "read_file", "args", obj("path", "aaa-outside/file-0.txt")), obj("name", "list_files", "args", obj("path", "aaa-outside")), obj("name", "write_file", "args", obj("path", "src/evil.txt", "content", "unauthorized")));
                        for (int index = 0; index < inputs.size(); index++) calls.add(obj("id", "scoped-tool-" + index, "type", "function", "function", obj("name", inputs.get(index).get("name"), "arguments", mapper.writeValueAsString(inputs.get(index).get("args")))));
                        answer = obj("role", "assistant", "tool_calls", calls);
                    } else {
                        for (Map<String, Object> message : objects(body.get("messages"))) if ("tool".equals(str(message, "role"))) results.add(mapper.readValue(str(message, "content"), Object.class));
                        answer = obj("role", "assistant", "content", "scoped tool observations");
                    }
                    byte[] bytes = mapper.writeValueAsBytes(obj("choices", List.of(obj("message", answer)), "usage", obj("prompt_tokens", 1, "completion_tokens", 1)));
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable error) { errors.add(error.toString()); exchange.sendResponseHeaders(500, -1); }
                finally { exchange.close(); }
            }); server.start();
            try {
                Map<String, Object> route = obj("engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
                Map<String, Object> result = runtime().run(route, "", root.toString(), "actual file scope fixture", false, List.of("**"), () -> false, ignored -> {}, observer);
                assertEquals("SUCCEEDED", str(result, "status"), result + " / " + errors); assertEquals(2, requests.get()); assertEquals(5, results.size()); assertEquals(0, modelLeases.get());
                assertEquals(List.of("src/authorized.txt"), ((Map<?, ?>) results.get(0)).get("files")); assertEquals(1, ((List<?>) results.get(1)).size());
                assertTrue(((Map<?, ?>) results.get(2)).containsKey("error")); assertFalse(results.get(2).toString().contains("outside-private-value"));
                assertEquals(List.of(), ((Map<?, ?>) results.get(3)).get("files")); assertTrue(((Map<?, ?>) results.get(4)).containsKey("error")); assertFalse(Files.exists(root.resolve("src/evil.txt"))); assertTrue(errors.isEmpty(), errors.toString());
            } finally { server.stop(0); }
        }
    }

    @Test
    void guardedBundlePreservesUnrelatedParentEditsAndStagedBlobWhileAdoptingGitModes() throws Exception {
        Path parent = repository(); WorkspaceRuntime runtime = runtime(); String original = runtime.fingerprint(parent.toString());
        Map<String, Object> copies = runtime.delegationCopy(parent.toString(), id(), original); Path child = Path.of(str(copies, "workspace"));
        Files.writeString(child.resolve("src/a.txt"), "made-a\n"); Files.delete(child.resolve("src/z.txt")); Files.writeString(child.resolve("src/new.txt"), "new\n");
        Files.writeString(child.resolve("src/new-exec.sh"), "echo new\n"); git(child, "add", "--", "src/new-exec.sh"); git(child, "update-index", "--chmod=+x", "--", "src/new-exec.sh"); git(child, "update-index", "--chmod=+x", "--", "src/script.sh");
        boolean posix = Files.getFileStore(parent).supportsFileAttributeView("posix");
        if (posix) { Files.setPosixFilePermissions(child.resolve("src/script.sh"), PosixFilePermissions.fromString("rwxr-xr-x")); Files.setPosixFilePermissions(child.resolve("src/new-exec.sh"), PosixFilePermissions.fromString("rwxr-xr-x")); }
        String childVersion = runtime.fingerprint(child.toString());
        Files.writeString(parent.resolve("notes.txt"), "later parent notes\n"); Files.writeString(parent.resolve("src/script.sh"), "parent staged content\n"); git(parent, "add", "--", "src/script.sh"); Files.writeString(parent.resolve("src/script.sh"), "echo base\n");
        String staged = git(parent, "show", ":src/script.sh");
        Map<String, Object> result = runtime.integrateDelegation(str(copies, "baseWorkspace"), child.toString(), parent.toString(), childVersion, permissions(List.of("write"), List.of("src/")));
        assertEquals("ACKNOWLEDGED", str(result, "state")); assertEquals("made-a\n", Files.readString(parent.resolve("src/a.txt"))); assertFalse(Files.exists(parent.resolve("src/z.txt"))); assertEquals("new\n", Files.readString(parent.resolve("src/new.txt")));
        assertEquals("later parent notes\n", Files.readString(parent.resolve("notes.txt"))); assertEquals(staged, git(parent, "show", ":src/script.sh")); assertTrue(git(parent, "ls-files", "--stage", "--", "src/script.sh").startsWith("100755 ")); assertTrue(git(parent, "ls-files", "--stage", "--", "src/new-exec.sh").startsWith("100755 "));
        if (posix) { assertTrue(Files.isExecutable(parent.resolve("src/script.sh"))); assertTrue(Files.isExecutable(parent.resolve("src/new-exec.sh"))); }
        assertEquals(childVersion, runtime.fingerprint(child.toString())); assertEquals(original, runtime.fingerprint(str(copies, "baseWorkspace"))); assertEquals(runtime.fingerprint(parent.toString()), result.get("resultVersion"));
        assertTrue(strings(result.get("changedPaths")).containsAll(List.of("src/a.txt", "src/z.txt", "src/new.txt", "src/script.sh", "src/new-exec.sh")));
    }

    @Test
    void conflictInLaterFileAndChangedChildVersionBothRejectBeforeAnyWrite() throws Exception {
        Path parent = repository(); WorkspaceRuntime runtime = runtime(); Map<String, Object> copies = runtime.delegationCopy(parent.toString(), id(), runtime.fingerprint(parent.toString())); Path child = Path.of(str(copies, "workspace"));
        Files.writeString(child.resolve("src/a.txt"), "made-a\n"); Files.writeString(child.resolve("src/z.txt"), "made-z\n"); String result = runtime.fingerprint(child.toString());
        Files.writeString(parent.resolve("src/z.txt"), "later parent edit\n"); String before = runtime.fingerprint(parent.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime.integrateDelegation(str(copies, "baseWorkspace"), child.toString(), parent.toString(), result, permissions(List.of("write"), List.of("src/")))); assertEquals(before, runtime.fingerprint(parent.toString())); assertEquals("base-a\n", Files.readString(parent.resolve("src/a.txt")));
        Files.writeString(child.resolve("src/a.txt"), "new child version\n"); assertThrows(IllegalArgumentException.class, () -> runtime.integrateDelegation(str(copies, "baseWorkspace"), child.toString(), parent.toString(), result, permissions(List.of("write"), List.of("src/")))); assertEquals(before, runtime.fingerprint(parent.toString()));
    }

    @Test
    void layeredScopeRejectsEntireBundleWhenOneFileIsOutsideTheIntersection() throws Exception {
        Path parent = repository(); WorkspaceRuntime runtime = runtime(); Map<String, Object> copies = runtime.delegationCopy(parent.toString(), id(), runtime.fingerprint(parent.toString())); Path child = Path.of(str(copies, "workspace"));
        Map<String, Object> actual = AgentPermissions.narrow(permissions(List.of("read", "write"), List.of("src/")), obj("capabilities", List.of("read", "write", "verify"), "scope", List.of("**/*.txt")), obj("scope", List.of("src/a*")));
        assertTrue(AgentPermissions.permits(actual, "write", "src/a.txt")); assertFalse(AgentPermissions.permits(actual, "write", "src/z.txt")); assertFalse(AgentPermissions.has(actual, "verify"));
        Files.writeString(child.resolve("src/a.txt"), "allowed\n"); Files.writeString(child.resolve("src/z.txt"), "outside delegated intersection\n"); String before = runtime.fingerprint(parent.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime.integrateDelegation(str(copies, "baseWorkspace"), child.toString(), parent.toString(), runtime.fingerprint(child.toString()), actual)); assertEquals(before, runtime.fingerprint(parent.toString()));
        assertThrows(IllegalArgumentException.class, () -> runtime.delegationChanges(str(copies, "baseWorkspace"), child.toString(), permissions(List.of("read"), List.of("**"))));
    }
}
