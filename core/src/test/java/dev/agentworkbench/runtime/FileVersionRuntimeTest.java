package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 HTTP 工具循环核对读取后人工修改、原读来源、权限及效果回执故障。
 */
class FileVersionRuntimeTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sameSizeAndRestoredMtimeCannotAuthorizeAStaleOverwrite() throws Exception {
        Path root = root("stale"), path = root.resolve("中文 文档.txt");
        Files.writeString(path, "用户原文"); FileTime originalTime = Files.getLastModifiedTime(path);
        AtomicReference<Map<String, Object>> read = new AtomicReference<>(), rejected = new AtomicReference<>();
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-original", "read_file", obj("path", path.getFileName().toString())));
            case 2 -> {
                read.set(observation(incoming, "read-original"));
                assertEquals("用户原文", str(read.get(), "content"));
                Files.writeString(path, "用户修改"); Files.setLastModifiedTime(path, originalTime);
                yield calls(tool("stale-write", "write_file", obj("path", path.getFileName().toString(), "content", "Agent覆盖", "expectedVersion", str(read.get(), "readVersion"))));
            }
            case 3 -> { rejected.set(observation(incoming, "stale-write")); yield done(); }
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("WAITING_INPUT", str(run.result, "status")); assertEquals("用户修改", Files.readString(path));
        assertEquals("FILE_VERSION_CONFLICT", str(rejected.get(), "code")); assertEquals(3, run.requests);
        List<Map<String, Object>> operations = operations(root); assertEquals(2, operations.size());
        Map<String, Object> original = operation(operations, "read-original"), refusal = operation(operations, "stale-write");
        assertEquals("CONFIRMED", str(original, "state")); assertEquals("REJECTED", str(refusal, "state"));
        assertEquals(str(read.get(), "readVersion"), str(refusal, "expectedReadVersion"));
        assertEquals(run.requestIds.get(1), str(refusal, "modelRequestId")); assertEquals(2, ((Number) refusal.get("toolProtocolVersion")).intValue());
        assertFalse(operations.toString().contains("用户原文"), "file text must not be copied into the operation journal");
        assertTrue(run.responses.stream().allMatch(value -> "SUCCEEDED".equals(str(value, "state"))), "tool refusal cannot rewrite successful HTTP receipts");
    }

    @Test
    void aFreshReadRecoversWithoutErasingTheRejectedOriginalCall() throws Exception {
        Path root = root("reread"), path = root.resolve("value.txt"); Files.writeString(path, "initial");
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("first-read", "read_file", obj("path", "value.txt")));
            case 2 -> {
                Map<String, Object> original = observation(incoming, "first-read"); Files.writeString(path, "later user content");
                yield calls(tool("old-write", "write_file", obj("path", "value.txt", "content", "old agent draft", "expectedVersion", str(original, "readVersion"))));
            }
            case 3 -> {
                assertEquals("FILE_VERSION_CONFLICT", str(observation(incoming, "old-write"), "code"));
                yield calls(tool("fresh-read", "read_file", obj("path", "value.txt")));
            }
            case 4 -> {
                Map<String, Object> fresh = observation(incoming, "fresh-read"); assertEquals("later user content", str(fresh, "content"));
                yield calls(tool("fresh-write", "write_file", obj("path", "value.txt", "content", str(fresh, "content") + "\nagent addition", "expectedVersion", str(fresh, "readVersion"))));
            }
            case 5 -> done();
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("SUCCEEDED", str(run.result, "status")); assertEquals("later user content\nagent addition", Files.readString(path));
        List<Map<String, Object>> operations = operations(root); assertEquals(4, operations.size());
        assertEquals("REJECTED", str(operation(operations, "old-write"), "state"));
        Map<String, Object> freshRead = operation(operations, "fresh-read"), freshWrite = operation(operations, "fresh-write");
        assertEquals("CONFIRMED", str(freshWrite, "state"));
        assertEquals(str(freshRead, "id"), str(map(freshWrite, "fileReceipt"), "sourceReadOperationId"));
        assertEquals(str(map(freshRead, "fileReceipt"), "readVersion"), str(freshWrite, "expectedReadVersion"));
    }

    @Test
    void missingForgedOtherFileAndMetadataOnlyReceiptsCannotOverwriteText() throws Exception {
        Path root = root("invalid");
        for (String name : List.of("a.txt", "b.txt", "c.txt")) Files.writeString(root.resolve(name), "original " + name);
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-a", "read_file", obj("path", "a.txt")), tool("read-b", "read_file", obj("path", "b.txt")), tool("inspect-c", "inspect_file", obj("path", "c.txt")));
            case 2 -> calls(
                tool("wrong-file", "write_file", obj("path", "b.txt", "content", "changed", "expectedVersion", str(observation(incoming, "read-a"), "readVersion"))),
                tool("metadata-write", "write_file", obj("path", "c.txt", "content", "changed", "expectedVersion", str(observation(incoming, "inspect-c"), "readVersion"))),
                tool("forged-write", "write_file", obj("path", "a.txt", "content", "changed", "expectedVersion", "file-read-00000000-0000-0000-0000-000000000000")),
                tool("missing-write", "write_file", obj("path", "a.txt", "content", "changed")));
            case 3 -> {
                for (String id : List.of("wrong-file", "metadata-write", "forged-write", "missing-write")) assertEquals("FILE_VERSION_CONFLICT", str(observation(incoming, id), "code"));
                yield done();
            }
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("WAITING_INPUT", str(run.result, "status"));
        for (String name : List.of("a.txt", "b.txt", "c.txt")) assertEquals("original " + name, Files.readString(root.resolve(name)));
        assertEquals(4, operations(root).stream().filter(value -> "REJECTED".equals(str(value, "state"))).count());
    }

    @Test
    void aReceiptFromAnotherApiExecutionCannotBeReused() throws Exception {
        Path root = root("another-run"), path = root.resolve("value.txt"); Files.writeString(path, "original");
        AtomicReference<String> originalRead = new AtomicReference<>();
        Run first = execute(root, (round, incoming) -> {
            if (round == 1) return calls(tool("old-run-read", "read_file", obj("path", "value.txt")));
            originalRead.set(str(observation(incoming, "old-run-read"), "readVersion")); return done();
        });
        assertEquals("SUCCEEDED", str(first.result, "status"));
        Run next = execute(root, (round, incoming) -> round == 1
            ? calls(tool("new-run-write", "write_file", obj("path", "value.txt", "content", "changed", "expectedVersion", originalRead.get()))) : done());
        assertEquals("WAITING_INPUT", str(next.result, "status")); assertEquals("original", Files.readString(path));
        assertEquals("CONFIRMED", str(operation(operations(root), "old-run-read"), "state"));
        assertEquals("REJECTED", str(operation(operations(root), "new-run-write"), "state"));
    }

    @Test
    void binaryDeleteRequiresTheSameCurrentVersionAndCanRecoverThroughInspection() throws Exception {
        Path root = root("binary"), path = root.resolve("value.bin"); Files.write(path, new byte[] { (byte) 0xff, (byte) 0xfe, 0 });
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("inspect-old", "inspect_file", obj("path", "value.bin")));
            case 2 -> {
                Map<String, Object> old = observation(incoming, "inspect-old"); Files.write(path, new byte[] { 1, 2, 3 });
                yield calls(tool("delete-old", "delete_file", obj("path", "value.bin", "expectedVersion", str(old, "readVersion"))));
            }
            case 3 -> {
                assertEquals("FILE_VERSION_CONFLICT", str(observation(incoming, "delete-old"), "code")); assertArrayEquals(new byte[] { 1, 2, 3 }, Files.readAllBytes(path));
                yield calls(tool("inspect-fresh", "inspect_file", obj("path", "value.bin")));
            }
            case 4 -> calls(tool("delete-fresh", "delete_file", obj("path", "value.bin", "expectedVersion", str(observation(incoming, "inspect-fresh"), "readVersion"))));
            case 5 -> done();
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("SUCCEEDED", str(run.result, "status")); assertFalse(Files.exists(path));
        Map<String, Object> deletion = map(operation(operations(root), "delete-fresh"), "fileReceipt");
        assertFalse(bool(map(deletion, "fileVersion"), "exists", true));
        assertEquals(str(operation(operations(root), "inspect-fresh"), "id"), str(deletion, "sourceReadOperationId"));
    }

    @Test
    void absentCreationAndAcknowledgedOwnWritesDoNotNeedAnUnrelatedRead() throws Exception {
        Path root = root("create");
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("create", "write_file", obj("path", "nested/new.txt", "content", "first", "expectedVersion", "ABSENT")));
            case 2 -> calls(tool("rewrite", "write_file", obj("path", "nested/new.txt", "content", "second", "expectedVersion", str(observation(incoming, "create"), "readVersion"))));
            case 3 -> calls(tool("delete", "delete_file", obj("path", "nested/new.txt", "expectedVersion", str(observation(incoming, "rewrite"), "readVersion"))));
            case 4 -> calls(tool("recreate", "write_file", obj("path", "nested/new.txt", "content", "third", "expectedVersion", str(observation(incoming, "delete"), "readVersion"))));
            case 5 -> done();
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("SUCCEEDED", str(run.result, "status")); assertEquals("third", Files.readString(root.resolve("nested/new.txt")));
        List<Map<String, Object>> operations = operations(root); assertEquals(4, operations.size());
        assertTrue(operations.stream().allMatch(value -> "CONFIRMED".equals(str(value, "state"))));
        assertEquals(str(operation(operations, "create"), "id"), str(map(operation(operations, "rewrite"), "fileReceipt"), "sourceReadOperationId"));
        assertEquals(str(operation(operations, "delete"), "id"), str(map(operation(operations, "recreate"), "fileReceipt"), "sourceReadOperationId"));
    }

    @Test
    void anAbsentDeclarationCannotOverwriteAnExistingFile() throws Exception {
        Path root = root("not-absent"); Files.writeString(root.resolve("existing.txt"), "user file");
        Run run = execute(root, (round, incoming) -> round == 1
            ? calls(tool("absent-write", "write_file", obj("path", "existing.txt", "content", "changed", "expectedVersion", "ABSENT"))) : done());
        assertEquals("WAITING_INPUT", str(run.result, "status")); assertEquals("user file", Files.readString(root.resolve("existing.txt")));
    }

    @Test
    void successfulTextReplacementPreservesPosixPermissions() throws Exception {
        Path root = root("permissions"), path = root.resolve("run.sh");
        Assumptions.assumeTrue(Files.getFileStore(root).supportsFileAttributeView("posix"));
        Files.writeString(path, "#!/bin/sh\necho before\n"); var permissions = PosixFilePermissions.fromString("rwxr-x---"); Files.setPosixFilePermissions(path, permissions);
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-script", "read_file", obj("path", "run.sh")));
            case 2 -> calls(tool("write-script", "write_file", obj("path", "run.sh", "content", "#!/bin/sh\necho after\n", "expectedVersion", str(observation(incoming, "read-script"), "readVersion"))));
            case 3 -> done();
            default -> throw new AssertionError("Unexpected model round " + round);
        });
        assertEquals("SUCCEEDED", str(run.result, "status")); assertEquals(permissions, Files.getPosixFilePermissions(path)); assertTrue(Files.isExecutable(path));
    }

    @Test
    void aLostConfirmationAfterTheRealWriteStaysUnknownWithoutReplay() throws Exception {
        Path root = root("receipt-fault"), path = root.resolve("value.txt"); Files.writeString(path, "before");
        AtomicBoolean fail = new AtomicBoolean(true);
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, temporary.resolve("runtime").toString()) {
            @Override void writeMetadataAtomic(Path target, byte[] bytes) throws IOException {
                var receipt = mapper.readTree(bytes);
                if ("write_file".equals(receipt.path("tool").asText()) && "CONFIRMED".equals(receipt.path("state").asText()) && fail.getAndSet(false)) throw new IOException("Injected confirmation failure after actual file write");
                super.writeMetadataAtomic(target, bytes);
            }
        };
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-before-fault", "read_file", obj("path", "value.txt")));
            case 2 -> calls(tool("write-before-fault", "write_file", obj("path", "value.txt", "content", "after", "expectedVersion", str(observation(incoming, "read-before-fault"), "readVersion"))));
            default -> throw new AssertionError("Unknown file receipt must stop further model requests");
        }, runtime, null);
        assertEquals("UNKNOWN", str(run.result, "status")); assertEquals("after", Files.readString(path)); assertEquals(2, run.requests);
        Map<String, Object> receipt = operation(operations(root), "write-before-fault");
        assertEquals("UNKNOWN", str(receipt, "state")); assertEquals("CONFIRM_METADATA", str(receipt, "failedReceiptPhase")); assertTrue(bool(receipt, "toolReturned", false));
        assertEquals(str(operation(operations(root), "read-before-fault"), "id"), str(map(receipt, "fileReceipt"), "sourceReadOperationId"));
    }

    @Test
    void inspectionCannotExpandTheDelegatedReadScope() throws Exception {
        Path root = root("scope"); Files.createDirectories(root.resolve("allowed")); Files.writeString(root.resolve("allowed/a.txt"), "allowed text"); Files.writeString(root.resolve("outside.txt"), "private outside text");
        AgentToolSession session = new AgentToolSession() {
            @Override public List<Map<String, Object>> definitions() { return List.of(); }
            @Override public AutoCloseable acquireModel() { return () -> {}; }
            @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { throw new AssertionError("No delegated alias is configured"); }
            @Override public Map<String, Object> permissions() { return obj("capabilities", List.of("read"), "readScopeLayers", List.of(List.of("allowed/**")), "writeScopeLayers", List.of(List.of("allowed/**"))); }
        };
        Run run = execute(root, (round, incoming) -> {
            if (round == 1) return calls(tool("outside-inspection", "inspect_file", obj("path", "outside.txt")), tool("allowed-inspection", "inspect_file", obj("path", "allowed/a.txt")));
            Map<String, Object> outside = observation(incoming, "outside-inspection"), allowed = observation(incoming, "allowed-inspection");
            assertTrue(outside.containsKey("error")); assertFalse(outside.containsKey("fileVersion")); assertFalse(outside.containsKey("readVersion"));
            assertTrue(allowed.containsKey("readVersion")); assertFalse(allowed.containsKey("content")); return done();
        }, new WorkspaceRuntime(mapper, temporary.resolve("runtime").toString()), session);
        assertEquals("SUCCEEDED", str(run.result, "status")); assertEquals("private outside text", Files.readString(root.resolve("outside.txt")));
    }

    @Test
    void aFileRemovedAfterReadingCannotTurnARejectedDeleteIntoSuccess() throws Exception {
        Path root = root("external-delete"), path = root.resolve("value.txt"); Files.writeString(path, "original");
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-before-removal", "read_file", obj("path", "value.txt")));
            case 2 -> {
                String readVersion = str(observation(incoming, "read-before-removal"), "readVersion"); Files.delete(path);
                yield calls(tool("delete-after-removal", "delete_file", obj("path", "value.txt", "expectedVersion", readVersion)));
            }
            default -> done();
        });
        assertEquals("WAITING_INPUT", str(run.result, "status")); assertFalse(Files.exists(path));
        assertEquals("FILE_VERSION_CONFLICT", str(operation(operations(root), "delete-after-removal"), "errorCode"));
    }

    @Test
    void invalidUtf8DoesNotIssueAReceiptThatAuthorizesTextReplacement() throws Exception {
        Path root = root("invalid-utf8"), path = root.resolve("value.bin"); byte[] original = { (byte) 0xc3, (byte) 0x28 }; Files.write(path, original);
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("invalid-text-read", "read_file", obj("path", "value.bin")));
            case 2 -> {
                assertFalse(observation(incoming, "invalid-text-read").containsKey("readVersion"));
                yield calls(tool("invalid-text-write", "write_file", obj("path", "value.bin", "content", "replacement")));
            }
            default -> done();
        });
        assertEquals("WAITING_INPUT", str(run.result, "status")); assertArrayEquals(original, Files.readAllBytes(path));
        assertEquals("REJECTED", str(operation(operations(root), "invalid-text-read"), "state"));
        assertFalse(operation(operations(root), "invalid-text-read").containsKey("fileReceipt"));
    }

    @Test
    void aLeaseCloseRejectionAfterTheRealWritePreservesTheEffectAsUnknown() throws Exception {
        Path root = root("lease-close"), path = root.resolve("value.txt"); Files.writeString(path, "before"); AtomicInteger toolLeases = new AtomicInteger();
        AgentToolSession session = new AgentToolSession() {
            @Override public List<Map<String, Object>> definitions() { return List.of(); }
            @Override public AutoCloseable acquireModel() { return () -> {}; }
            @Override public AutoCloseable acquireTool() {
                int ordinal = toolLeases.incrementAndGet();
                return () -> { if (ordinal == 2) throw new IllegalArgumentException("Injected close rejection after actual write"); };
            }
            @Override public Map<String, Object> invoke(String alias, Map<String, Object> arguments) { throw new AssertionError("No delegated alias is configured"); }
            @Override public Map<String, Object> permissions() { return obj("capabilities", List.of("read", "write"), "readScopeLayers", List.of(List.of("**")), "writeScopeLayers", List.of(List.of("**"))); }
        };
        Run run = execute(root, (round, incoming) -> switch (round) {
            case 1 -> calls(tool("read-before-close", "read_file", obj("path", "value.txt")));
            case 2 -> calls(tool("write-before-close", "write_file", obj("path", "value.txt", "content", "after", "expectedVersion", str(observation(incoming, "read-before-close"), "readVersion"))));
            default -> throw new AssertionError("A missing close receipt cannot replay or issue another request");
        }, new WorkspaceRuntime(mapper, temporary.resolve("runtime").toString()), session, false);
        assertEquals("UNKNOWN", str(run.result, "status")); assertEquals("after", Files.readString(path)); assertEquals(2, run.requests); assertEquals(2, toolLeases.get());
        Map<String, Object> receipt = operation(operations(root), "write-before-close");
        assertEquals("UNKNOWN", str(receipt, "state")); assertTrue(bool(receipt, "toolReturned", false));
        assertTrue(map(receipt, "fileReceipt").containsKey("readVersion")); assertEquals("TOOL_EXECUTION", str(receipt, "failedReceiptPhase"));
    }

    private Path root(String name) throws IOException { return Files.createDirectories(temporary.resolve(name).resolve("work")); }
    private Map<String, Object> tool(String id, String name, Map<String, Object> arguments) throws IOException { return obj("id", id, "type", "function", "function", obj("name", name, "arguments", mapper.writeValueAsString(arguments))); }
    @SafeVarargs private static Map<String, Object> calls(Map<String, Object>... tools) { return obj("role", "assistant", "tool_calls", List.of(tools)); }
    private static Map<String, Object> done() { return obj("role", "assistant", "content", "Fixture finished"); }

    private Map<String, Object> observation(Map<String, Object> incoming, String toolId) throws IOException {
        for (Object item : (List<?>) incoming.get("messages")) if (item instanceof Map<?, ?> message && "tool".equals(message.get("role")) && toolId.equals(message.get("tool_call_id"))) {
            return mapper.readValue(message.get("content").toString(), new TypeReference<>() {});
        }
        throw new AssertionError("Missing actual tool result " + toolId);
    }

    private List<Map<String, Object>> operations(Path root) throws IOException {
        List<Map<String, Object>> result = new ArrayList<>();
        try (var files = Files.list(root.getParent().resolve("operations"))) {
            for (Path file : files.filter(path -> path.toString().endsWith(".json")).toList()) result.add(mapper.readValue(Files.readAllBytes(file), new TypeReference<>() {}));
        }
        return result;
    }

    private static Map<String, Object> operation(List<Map<String, Object>> operations, String toolId) { return operations.stream().filter(value -> toolId.equals(str(value, "toolCallId"))).findFirst().orElseThrow(); }
    private Run execute(Path root, Script script) throws Exception { return execute(root, script, new WorkspaceRuntime(mapper, temporary.resolve("runtime").toString()), null); }

    /**
     * 请求身份来自显式本地夹具观察器，文件工具仍由原生产 API 循环执行。
     */
    private Run execute(Path root, Script script, WorkspaceRuntime runtime, AgentToolSession session) throws Exception {
        return execute(root, script, runtime, session, session != null);
    }

    private Run execute(Path root, Script script, WorkspaceRuntime runtime, AgentToolSession session, boolean readOnly) throws Exception {
        AtomicInteger requests = new AtomicInteger(); List<String> requestIds = new CopyOnWriteArrayList<>(); List<Map<String, Object>> responses = new CopyOnWriteArrayList<>();
        AtomicReference<Throwable> failure = new AtomicReference<>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, Object> incoming = mapper.readValue(exchange.getRequestBody(), new TypeReference<>() {});
                int round = requests.incrementAndGet(); Map<String, Object> message = script.reply(round, incoming);
                byte[] bytes = mapper.writeValueAsBytes(obj("id", "fixture-response-" + round, "model", "fixture", "choices", List.of(obj("message", message))));
                exchange.getResponseHeaders().add("Content-Type", "application/json"); exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } catch (Throwable exception) { failure.set(exception); exchange.sendResponseHeaders(500, -1); }
            finally { exchange.close(); }
        });
        server.start();
        ModelCallObserver observer = new ModelCallObserver() {
            private String current;
            @Override public boolean begin(Map<String, Object> metadata) { current = UUID.randomUUID().toString(); requestIds.add(current); return true; }
            @Override public void end(Map<String, Object> metadata) { responses.add(metadata); }
            @Override public String lastRequestId() { return current; }
            @Override public AgentToolSession tools() { return session; }
        };
        try {
            Map<String, Object> route = obj("engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            Map<String, Object> result = runtime.run(route, "fixture-private-key", root.toString(), "Exercise actual file preconditions", readOnly, List.of("**"), () -> false, ignored -> {}, observer);
            if (failure.get() != null) throw new AssertionError("Local HTTP fixture failed", failure.get());
            return new Run(result, List.copyOf(requestIds), List.copyOf(responses), requests.get());
        } finally { server.stop(0); }
    }

    @FunctionalInterface private interface Script { Map<String, Object> reply(int round, Map<String, Object> incoming) throws Exception; }
    private record Run(Map<String, Object> result, List<String> requestIds, List<Map<String, Object>> responses, int requests) { }
}
