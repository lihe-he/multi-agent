package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.runtime.ModelCallObserver;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
@ExtendWith(CheckpointFailureEvidence.class)
class RuntimeIntegrationTest {
    @TempDir
    Path temporary;

    private final ObjectMapper json = new ObjectMapper();

    private static class ObservedCalls implements ModelCallObserver {
        final List<Map<String, Object>> inputs = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> outputs = new CopyOnWriteArrayList<>();
        @Override public boolean begin(Map<String, Object> metadata) { inputs.add(metadata); return true; }
        @Override public void end(Map<String, Object> observation) { outputs.add(observation); }
    }

    @Test
    void apiRequestObservationsSeparateRoundsAndPreserveUnknownUsage() throws Exception {
        Path root = Files.createDirectories(temporary.resolve("observed-project"));
        Files.writeString(root.resolve("read.txt"), "read result");
        AtomicInteger requests = new AtomicInteger();
        var readTool = tool("read_file", Map.of("path", "read.txt"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                int index = requests.incrementAndGet();
                Map<String, Object> message = index == 1 ? Map.of("role", "assistant", "tool_calls", List.of(readTool)) : Map.of("role", "assistant", "content", "done");
                Map<String, Object> body = new java.util.LinkedHashMap<>(Map.of("id", "response-" + index, "model", "observed-model", "choices", List.of(Map.of("message", message, "finish_reason", index == 1 ? "tool_calls" : "stop"))));
                if (index == 1) body.put("usage", Map.of("prompt_tokens", 7, "completion_tokens", 0, "prompt_tokens_details", Map.of("cached_tokens", 3), "completion_tokens_details", Map.of("reasoning_tokens", -1)));
                byte[] bytes = json.writeValueAsBytes(body);
                exchange.getResponseHeaders().add("x-request-id", "provider-" + index);
                exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ObservedCalls observed = new ObservedCalls();
            Map<String, Object> result = runtime().run(route(server), "fixture-secret", root.toString(), "private input", true, List.of("**"), () -> false, ignored -> {}, observed);
            assertEquals("SUCCEEDED", result.get("status"), result.toString());
            assertEquals(2, observed.inputs.size()); assertEquals(2, observed.outputs.size()); assertEquals(2, requests.get());
            assertEquals(1, observed.inputs.getFirst().get("round"));
            assertNotEquals(observed.inputs.getFirst().get("inputDigest"), observed.inputs.getLast().get("inputDigest"));
            assertTrue(observed.inputs.toString().contains("private input")); assertFalse(observed.inputs.toString().contains("fixture-secret"));
            assertTrue(observed.outputs.stream().allMatch(value -> value.get("state").equals("SUCCEEDED")));
            assertEquals("provider-2", observed.outputs.getLast().get("providerRequestId"));
            assertEquals("response-1", observed.outputs.getFirst().get("responseId"));
            var firstUsage = (Map<?, ?>) observed.outputs.getFirst().get("usage");
            assertEquals(0L, firstUsage.get("outputTokens")); assertEquals(3L, firstUsage.get("cachedInputTokens")); assertEquals("UNKNOWN", firstUsage.get("reasoningTokens"));
            var aggregate = (Map<?, ?>) result.get("usage");
            assertEquals("UNKNOWN", aggregate.get("inputTokens")); assertEquals(7L, aggregate.get("observedInputTokens")); assertEquals(2, aggregate.get("modelRequests"));
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"500,FAILED,HTTP_500", "invalid,FAILED,INVALID_RESPONSE", "disconnect,UNKNOWN,ExecutionException"})
    void requestFailureFactsDistinguishResponseFromUnknownTransport(String mode, String expectedState, String expectedError) throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        server.createContext("/", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes(); requests.incrementAndGet();
                if (mode.equals("disconnect")) return;
                byte[] bytes = "malformed fixture-secret body".getBytes(StandardCharsets.UTF_8);
                exchange.sendResponseHeaders(mode.equals("500") ? 500 : 200, bytes.length); exchange.getResponseBody().write(bytes);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            ObservedCalls observed = new ObservedCalls();
            var result = runtime().run(route(server), "fixture-secret", temporary.toString(), "input", true, List.of("**"), () -> false, ignored -> {}, observed);
            assertEquals(expectedState, result.get("status"));
            assertEquals(1, observed.outputs.size()); assertEquals(1, requests.get(), "failed requests are not replayed");
            assertEquals(expectedState, observed.outputs.getFirst().get("state"));
            assertEquals(expectedError, observed.outputs.getFirst().get("errorType"));
            assertFalse(observed.outputs.toString().contains("fixture-secret"), "raw invalid response bodies are excluded");
        } finally { server.stop(0); }
    }

    @Test
    void cancellingModelWaitRecordsUnknownRemoteOutcomeOnce() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1); var release = new java.util.concurrent.CountDownLatch(1);
        var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try { exchange.getRequestBody().readAllBytes(); entered.countDown(); release.await(5, java.util.concurrent.TimeUnit.SECONDS); }
            catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            finally { exchange.close(); }
        });
        server.start();
        try (var executor = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            ObservedCalls observed = new ObservedCalls();
            var work = executor.submit(() -> runtime().run(route(server), "fixture", temporary.toString(), "input", true, List.of("**"), cancelled::get, ignored -> {}, observed));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS)); cancelled.set(true);
            assertEquals("UNKNOWN", work.get(5, java.util.concurrent.TimeUnit.SECONDS).get("status"));
            assertEquals(1, observed.outputs.size()); assertEquals("LOCAL_CANCEL_WAIT", observed.outputs.getFirst().get("errorType"));
            assertEquals("UNKNOWN", observed.outputs.getFirst().get("remoteOutcome"));
        } finally { release.countDown(); server.stop(0); }
    }

    @ParameterizedTest
    @CsvSource({"CLONE", "WORKTREE"})
    void toolCheckpointUsesOriginalCoreOwnershipAfterTheSelectionRecordChanges(String strategy) throws Exception {
        Path source = repository(), data = temporary.resolve("state"); WorkspaceRuntime runtime = runtime(); String taskId = java.util.UUID.randomUUID().toString();
        byte[] sourceIndex = Files.readAllBytes(source.resolve(".git/index")); String sourceHead = git(source, "rev-parse", "HEAD").strip();
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), Json.obj("strategy", strategy, "ref", "HEAD"), false, 1);
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), taskId, false, selection), ownership = Json.map(prepared, "workspaceOwnership");
        Path work = Path.of(Json.str(prepared, "workspace")), selectionFile = work.getParent().resolve("selection.json"), manifests = data.resolve("checkpoints/tasks").resolve(taskId);
        runtime.captureCheckpoint(work.toString(), taskId, Json.obj("kind", "ORIGINAL_BOUNDARY"), ownership);
        AtomicInteger requests = new AtomicInteger(), published = new AtomicInteger(); AtomicReference<byte[]> rewrittenBytes = new AtomicReference<>(); List<Throwable> errors = new CopyOnWriteArrayList<>();
        try (ExecutionStore store = new ExecutionStore(json, data.toString())) {
            WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); String runId = java.util.UUID.randomUUID().toString();
            store.put("task", Json.obj("id", taskId, "projectId", "owner-fixture", "workspace", work.toString(), "workspaceOwnership", ownership,
                "state", "RUNNING", "plan", List.of(), "context", List.of(), "budget", Map.of()));
            store.put("agentRun", Json.obj("id", runId, "taskId", taskId, "workspace", work.toString(), "stage", "implement"));
            CheckpointLedger ledger = new CheckpointLedger(store, catalog, runtime);
            ObservedCalls observed = new ObservedCalls() {
                @Override public String lastRequestId() { return outputs.isEmpty() || !"SUCCEEDED".equals(outputs.getLast().get("state")) ? "" : "original-owner-request-" + outputs.size(); }
                @Override public Map<String, Object> checkpointContext() { return ledger.context(taskId, runId); }
                @Override public void checkpoint(Map<String, Object> descriptor) { published.incrementAndGet(); }
            };
            var readTool = tool("read_file", Map.of("path", "src/greeting.txt")); HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> {
                try {
                    exchange.getRequestBody().readAllBytes(); Map<String, Object> message;
                    if (requests.incrementAndGet() == 1) {
                        Map<String, Object> rewritten = json.readValue(Files.readAllBytes(selectionFile), new TypeReference<>() {});
                        Json.map(Json.map(rewritten, "facts"), "workspaceOwnership").put("owner", java.util.UUID.randomUUID().toString());
                        byte[] bytes = json.writeValueAsBytes(rewritten); Files.write(selectionFile, bytes); rewrittenBytes.set(bytes);
                        message = Map.of("role", "assistant", "tool_calls", List.of(readTool));
                    } else message = Map.of("role", "assistant", "content", "later model response");
                    byte[] bytes = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
                    exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
                } catch (Throwable failure) { errors.add(failure); }
                finally { exchange.close(); }
            }); server.start();
            try {
                Map<String, Object> result = runtime.run(route(server), "fixture", work.toString(), "Read the original scoped file", true, List.of("**"), () -> false, ignored -> {}, observed);
                assertEquals("WAITING_INPUT", result.get("status"), result.toString()); assertEquals(1, requests.get(), "a failed checkpoint must stop later model dispatch");
                assertEquals(0, published.get()); assertEquals(1, observed.outputs.size()); assertEquals("SUCCEEDED", observed.outputs.getFirst().get("state")); assertTrue(errors.isEmpty(), errors.toString());
                assertEquals(ownership, Json.map(ledger.context(taskId, runId), "workspaceOwnership"), "the core context retains the original stored owner");
                List<Map<String, Object>> operations = Json.objects(runtime.fileToolJournal(work.toString()).get("operations")); assertEquals(1, operations.size());
                assertEquals("CONFIRMED", operations.getFirst().get("state")); assertEquals("read_file", operations.getFirst().get("tool"));
                assertEquals("original-owner-request-1", operations.getFirst().get("modelRequestId"));
                try (var files = Files.list(manifests)) { assertEquals(1, files.count(), "a changed owner cannot publish another manifest"); }
                assertNotNull(rewrittenBytes.get()); assertArrayEquals(rewrittenBytes.get(), Files.readAllBytes(selectionFile));
                assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index"))); assertEquals(sourceHead, git(source, "rev-parse", "HEAD").strip());
                assertEquals("hello\n", Files.readString(work.resolve("src/greeting.txt"))); assertEquals("hello\n", Files.readString(source.resolve("src/greeting.txt"))); assertTrue(store.healthy());
            } finally { server.stop(0); }
        }
    }

    @Test
    void nativeRunnersUseIsolationFlagsAndKeepReadOnlyToolPolicy() throws Exception {
        Path project = Files.createDirectories(temporary.resolve("native project"));
        for (String engine : List.of("CODEX", "CLAUDE")) {
            Path captured = temporary.resolve(engine + "-args.json");
            Path cli = nativeFixture(engine, captured, true);
            ObservedCalls calls = new ObservedCalls();
            Map<String, Object> outcome = runtime().run(Map.of("engine", engine, "executable", cli.toString()), "", project.toString(), "fixture prompt", true,
                    List.of("**"), () -> false, ignored -> {}, calls);
            assertEquals("SUCCEEDED", outcome.get("status"), outcome.toString());
            String args = Files.readString(captured);
            if (engine.equals("CODEX")) {
                assertTrue(args.contains("--ignore-user-config")); assertTrue(args.contains("--ignore-rules")); assertTrue(args.contains("read-only"));
            } else {
                assertTrue(args.contains("--safe-mode")); assertTrue(args.contains("Read,Glob,Grep")); assertFalse(args.contains("Edit,Write"));
            }
            assertEquals(1, calls.inputs.size(), "配置能力探测不能消耗模型预算");
            assertEquals(1, calls.outputs.size());
            assertEquals("SUCCEEDED", calls.outputs.getFirst().get("state"));
            assertEquals("UNKNOWN", ((Map<?, ?>) calls.outputs.getFirst().get("usage")).get("modelRequests"));
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
    void naturallyExitingDescendantMustFinishBeforeVerificationReturnsSuccess() throws Exception {
        Path root = descendantFixture(true), receipts = root.getParent().resolve("receipts");
        ProcessHandle child = null;
        try {
            Map<String, Object> result = runtime().verify(root.toString(), "node parent.mjs", () -> false, ignored -> {});
            long pid = Long.parseLong(Files.readString(receipts.resolve("child-pid")));
            child = originalFixtureChild(receipts.resolve("child-pid"), pid);
            assertEquals("SUCCEEDED", result.get("status"), result.toString());
            assertFalse(child != null && child.isAlive(), "verification returned success while the original child " + pid + " was still alive");
            assertTrue(Files.exists(receipts.resolve("finished")), "the original child must finish before the command can be confirmed");
            assertTrue(Files.exists(receipts.resolve("parent-saw-exit")), "the parent receipt must follow the original child exit");
        } finally { stopFixtureChild(child == null ? recordedFixtureChild(receipts) : child); }
    }

    @Test
    void parentExitWaitsForTheOriginalChildNaturalStopBeforeConfirmingTheCommand() throws Exception {
        parentExitWaitsForOriginalChild(0, "SUCCEEDED");
    }

    @ParameterizedTest
    @CsvSource({"1,FAILED", "37,FAILED"})
    void nonzeroParentExitStillWaitsForTheOriginalChildNaturalStop(int exit, String status) throws Exception {
        parentExitWaitsForOriginalChild(exit, status);
    }

    private void parentExitWaitsForOriginalChild(int exit, String status) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("parent returns before child")), receipts = Files.createDirectories(temporary.resolve("receipts"));
        String receiptPath = json.writeValueAsString(receipts.toString());
        Files.writeString(root.resolve("child.mjs"), "import{existsSync,writeFileSync}from'node:fs';import{join}from'node:path';const receipts=" + receiptPath
            + ";setTimeout(()=>process.exit(2),15000);const poll=setInterval(()=>{if(existsSync(join(receipts,'release'))){clearInterval(poll);setTimeout(()=>{writeFileSync(join(receipts,'finished'),'original child exited');process.exit(0)},2000)}},5);\n");
        Files.writeString(root.resolve("parent.mjs"), "import{spawn}from'node:child_process';import{writeFileSync}from'node:fs';import{join}from'node:path';import{fileURLToPath}from'node:url';const receipts=" + receiptPath
            + ";const child=spawn(process.execPath,[fileURLToPath(new URL('child.mjs',import.meta.url))],{detached:true,windowsHide:true,stdio:'ignore'});writeFileSync(join(receipts,'child-pid'),String(child.pid));child.unref();setTimeout(()=>{writeFileSync(join(receipts,'release'),'parent exiting');process.exit(" + exit + ")},1000);\n");
        WorkspaceRuntime runtime = runtime();
        List<Map<String, Object>> observations = new java.util.concurrent.CopyOnWriteArrayList<>(); runtime.observeProcesses(observations::add);
        ProcessHandle child = null;
        try {
            String command = "node parent.mjs" + (System.getProperty("os.name").toLowerCase().contains("win") ? "; exit $LASTEXITCODE" : "");
            Map<String, Object> result = runtime.verify(root.toString(), command, () -> false, ignored -> {});
            long pid = Long.parseLong(Files.readString(receipts.resolve("child-pid")));
            child = originalFixtureChild(receipts.resolve("child-pid"), pid);
            Map<String, Object> original = observations.stream().filter(value -> ((Number) value.get("pid")).longValue() == pid).findFirst().orElseThrow();
            assertEquals(status, result.get("status"), result.toString());
            assertEquals(exit, result.get("exitCode"));
            assertTrue(Files.exists(receipts.resolve("finished")), "original child must finish naturally before command confirmation");
            assertTrue(ManagedProcessLedger.observedProcessStopped(pid, original.get("processStartedAt").toString()));
            assertFalse(child != null && child.isAlive());
        } finally { stopFixtureChild(child == null ? recordedFixtureChild(receipts) : child); }
    }

    @Test
    void descendantWithoutExitConfirmationRemainsUnknownAfterParentSuccess() throws Exception {
        Path root = descendantFixture(false), receipts = root.getParent().resolve("receipts");
        ProcessHandle child = null;
        try {
            Map<String, Object> result = runtime().verify(root.toString(), "node parent.mjs", () -> false, ignored -> {});
            long pid = Long.parseLong(Files.readString(receipts.resolve("child-pid")));
            child = originalFixtureChild(receipts.resolve("child-pid"), pid);
            assertEquals("UNKNOWN", result.get("status"), "original child " + pid + " alive=" + (child != null && child.isAlive()));
            assertEquals(0, result.get("exitCode"));
            assertEquals(true, result.get("truncated"));
            assertTrue(result.get("output").toString().contains(Long.toString(pid)), "unknown command evidence must identify the original unconfirmed child");
            assertFalse(Files.exists(receipts.resolve("finished")));
            if (child != null) {
                child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
                assertFalse(child.isAlive(), "the original child must stop before fixture cleanup");
            }
        } finally { stopFixtureChild(child == null ? recordedFixtureChild(receipts) : child); }
    }

    private Path descendantFixture(boolean naturalExit) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("descendant command"));
        Path receipts = Files.createDirectories(temporary.resolve("receipts"));
        String receiptPath = json.writeValueAsString(receipts.toString());
        Files.writeString(root.resolve("child.mjs"), "import {existsSync, writeFileSync} from 'node:fs'; import {join} from 'node:path';\n"
            + "const receipts = " + receiptPath + "; writeFileSync(join(receipts, 'child-pid'), String(process.pid));\n"
            + "setTimeout(() => process.exit(2), 15000);\n"
            + "const poll = setInterval(() => { if (existsSync(join(receipts, 'release'))) { clearInterval(poll); setTimeout(() => { writeFileSync(join(receipts, 'finished'), 'original child exited'); process.exit(0); }, 300); } }, 10);\n");
        Files.writeString(root.resolve("parent.mjs"), "import {spawn} from 'node:child_process'; import {writeFileSync} from 'node:fs'; import {join} from 'node:path'; import {fileURLToPath} from 'node:url';\n"
            + "const receipts = " + receiptPath + "; const child = spawn(process.execPath, [fileURLToPath(new URL('child.mjs', import.meta.url))], {cwd:" + json.writeValueAsString(temporary.getParent().toString()) + ", detached:true, windowsHide:true, stdio:'ignore'});\n"
            + (naturalExit ? "child.on('exit', code => { writeFileSync(join(receipts, 'parent-saw-exit'), String(code)); process.exit(code ?? 1); }); setTimeout(() => writeFileSync(join(receipts, 'release'), 'child may finish'), 750);\n"
                : "child.unref(); process.stdout.write('x'.repeat(600000)); setTimeout(() => process.exit(0), 750);\n"));
        return root;
    }

    private void stopFixtureChild(ProcessHandle child) throws Exception {
        if (child == null || !child.isAlive()) return;
        child.destroyForcibly(); child.onExit().get(5, java.util.concurrent.TimeUnit.SECONDS);
        assertFalse(child.isAlive(), "fixture cleanup did not confirm the original child exit");
    }

    private ProcessHandle recordedFixtureChild(Path receipts) throws Exception {
        Path receipt = receipts.resolve("child-pid");
        return Files.exists(receipt) ? originalFixtureChild(receipt, Long.parseLong(Files.readString(receipt))) : null;
    }

    /**
     * 回执写入后出生的同号进程不属于夹具，避免清理误杀复用 PID 的进程。
     */
    private ProcessHandle originalFixtureChild(Path receipt, long pid) throws Exception {
        ProcessHandle child = ProcessHandle.of(pid).orElse(null);
        if (child == null) return null;
        java.time.Instant recordedAt = Files.getLastModifiedTime(receipt).toInstant();
        return child.info().startInstant().filter(start -> !start.isAfter(recordedAt)).isPresent() ? child : null;
    }

    @Test
    void transientWindowsReceiptLockDoesNotReplayCompletedFileTool() throws Exception {
        exerciseWindowsReceiptLock(true);
    }

    @Test
    void persistentWindowsReceiptLockKeepsEffectUnknownWithoutReplay() throws Exception {
        exerciseWindowsReceiptLock(false);
    }

    /**
     * 锁住已发送的回执文件，核对工具写入后的临时共享冲突与持续无法保存边界。
     */
    private void exerciseWindowsReceiptLock(boolean transientLock) throws Exception {
        Assumptions.assumeTrue(System.getProperty("os.name").startsWith("Windows"));
        Path root = Files.createDirectories(temporary.resolve("receipt-lock").resolve("work"));
        Files.writeString(root.resolve("value.txt"), "before\n");
        AtomicInteger requests = new AtomicInteger(), tools = new AtomicInteger();
        AtomicReference<java.nio.channels.SeekableByteChannel> held = new AtomicReference<>();
        java.util.concurrent.CompletableFuture<Void> released = new java.util.concurrent.CompletableFuture<>();
        Map<String, Object> readTool = tool("read_file", Map.of("path", "value.txt"));
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            try {
                Map<String, Object> incoming = json.readValue(exchange.getRequestBody().readAllBytes(), new TypeReference<>() {});
                int round = requests.getAndIncrement();
                Map<String, Object> message;
                if (round == 0) message = Map.of("role", "assistant", "tool_calls", List.of(readTool));
                else if (round == 1) {
                    Map<?, ?> observed = ((List<?>) incoming.get("messages")).stream().filter(item -> item instanceof Map<?, ?> value && "tool".equals(value.get("role")))
                        .map(item -> (Map<?, ?>) item).findFirst().orElseThrow();
                    String expectedVersion = json.readTree(observed.get("content").toString()).get("readVersion").asText();
                    message = Map.of("role", "assistant", "tool_calls", List.of(tool("write_file", Map.of("path", "value.txt", "content", "after\n", "expectedVersion", expectedVersion))));
                } else message = Map.of("role", "assistant", "content", "completed");
                byte[] response = json.writeValueAsBytes(Map.of("choices", List.of(Map.of("message", message))));
                exchange.sendResponseHeaders(200, response.length); exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        Consumer<String> log = message -> {
            if (!message.startsWith("工具 · write_file")) return;
            tools.incrementAndGet();
            try {
                Path receipt;
                try (var files = Files.list(root.getParent().resolve("operations"))) {
                    receipt = files.filter(file -> file.toString().endsWith(".json")).filter(file -> {
                        try { var entry = json.readTree(file.toFile()); return "write_file".equals(entry.path("tool").asText()) && "SENT".equals(entry.path("state").asText()); }
                        catch (IOException exception) { throw new IllegalStateException(exception); }
                    }).findFirst().orElseThrow();
                }
                held.set(Files.newByteChannel(receipt, java.util.Set.of(java.nio.file.StandardOpenOption.READ, com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE)));
                if (transientLock) Thread.ofPlatform().daemon(true).start(() -> {
                    try { Thread.sleep(250); held.get().close(); released.complete(null); }
                    catch (Exception exception) { released.completeExceptionally(exception); }
                });
            } catch (Exception exception) { throw new IllegalStateException("无法创建真实 Windows 回执共享冲突夹具", exception); }
        };
        try {
            long started = System.nanoTime();
            Map<String, Object> result = runtime().run(route(server), "fixture", root.toString(), "Write the approved fixture once", false, List.of("**"), () -> false, log, () -> true);
            assertEquals(transientLock ? "SUCCEEDED" : "UNKNOWN", result.get("status"), result.toString());
            assertEquals("after\n", Files.readString(root.resolve("value.txt")), "the original file effect must remain intact");
            assertEquals(1, tools.get(), "metadata recovery must not replay the completed file tool");
            assertEquals(transientLock ? 3 : 2, requests.get(), "unknown receipt persistence must prevent a further model request");
            assertTrue(System.nanoTime() - started < java.util.concurrent.TimeUnit.SECONDS.toNanos(5), "metadata replacement must remain bounded");
            if (transientLock) released.get(2, java.util.concurrent.TimeUnit.SECONDS);
            try (var files = Files.list(root.getParent().resolve("operations"))) {
                List<Path> receipts = files.filter(file -> file.toString().endsWith(".json")).toList(); assertEquals(2, receipts.size());
                List<com.fasterxml.jackson.databind.JsonNode> entries = new ArrayList<>();
                for (Path receipt : receipts) entries.add(json.readTree(receipt.toFile()));
                assertEquals("CONFIRMED", entries.stream().filter(entry -> "read_file".equals(entry.path("tool").asText())).findFirst().orElseThrow().path("state").asText());
                String state = entries.stream().filter(entry -> "write_file".equals(entry.path("tool").asText())).findFirst().orElseThrow().path("state").asText();
                if (transientLock) assertEquals("CONFIRMED", state);
                else assertTrue(java.util.Set.of("SENT", "UNKNOWN").contains(state), "a missing receipt cannot be claimed as confirmed");
            }
        } finally {
            if (held.get() != null) held.get().close();
            server.stop(0);
        }
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

    @ParameterizedTest
    @CsvSource({"sha1", "sha256"})
    void fingerprintPreservesPreviouslyRecordedVersionsAcrossGitListingBoundaries(String format) throws Exception {
        Path root = Files.createDirectories(temporary.resolve("original version boundary"));
        git(root, "init", "--initial-branch=main", "--object-format=" + format);
        git(root, "config", "user.name", "Version Fixture"); git(root, "config", "user.email", "version@example.invalid");
        git(root, "config", "core.filemode", "false"); git(root, "config", "core.autocrlf", "false");
        for (String name : List.of("H tracked.txt", "S cached.txt", "deleted.txt", "mode.txt")) Files.writeString(root.resolve(name), name + " original\n");
        Files.writeString(root.resolve(".gitignore"), "ignored.txt\n"); git(root, "add", "."); git(root, "commit", "-m", "original version fixture");
        git(root, "update-index", "--assume-unchanged", "H tracked.txt"); git(root, "update-index", "--skip-worktree", "S cached.txt"); Files.delete(root.resolve("deleted.txt"));
        for (String name : List.of("H untracked.txt", "100644 fake mode.txt", "中文 path.txt", "ignored.txt")) Files.writeString(root.resolve(name), name + " original\n");
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) {
            try (var files = Files.list(root)) {
                for (Path path : files.filter(Files::isRegularFile).toList()) Files.setPosixFilePermissions(path,
                    PosixFilePermissions.fromString(path.getFileName().toString().equals("mode.txt") ? "rwxr-xr-x" : "rw-r--r--"));
            }
        }
        String object = git(root, "rev-parse", "HEAD:mode.txt").strip();
        String input = "0 " + "0".repeat(format.equals("sha1") ? 40 : 64) + "\tmode.txt\n"
            + "100644 " + object + " 1\tmode.txt\n" + "100644 " + object + " 2\tmode.txt\n" + "100755 " + object + " 3\tmode.txt\n";
        gitWithInput(root, input, "update-index", "--index-info");
        byte[] index = Files.readAllBytes(root.resolve(".git/index")); String head = git(root, "rev-parse", "HEAD").strip(); WorkspaceRuntime runtime = runtime();
        // 旧双清单实现的真实现场结果已冻结；合并读取不能改变持久化的 v2 内容版本。
        assertEquals("c209e00a5556d165d3473680e1f697f51fe0b2f390b543ae318482142fd81c76", runtime.fingerprint(root.toString()));
        Files.writeString(root.resolve("中文 path.txt"), "中文 path.txt modified\n");
        assertEquals("fd31f9bc4ae678a3151098860556e2b7327e3933c123bde922e42e1ab9d0fbf4", runtime.fingerprint(root.toString()), "same-length changes require a fresh byte read");
        assertArrayEquals(index, Files.readAllBytes(root.resolve(".git/index"))); assertEquals(head, git(root, "rev-parse", "HEAD").strip());
        assertEquals("H untracked.txt original\n", Files.readString(root.resolve("H untracked.txt")));
        assertEquals("100644 fake mode.txt original\n", Files.readString(root.resolve("100644 fake mode.txt")));
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

    @Test
    void anUninitializedGitlinkIsRejectedFromTheActualIndexBeforeWorkspaceSelection() throws Exception {
        Path source = repository(); String head = git(source, "rev-parse", "HEAD").strip();
        Files.writeString(source.resolve(".gitmodules"), "[submodule \"original nested\"]\n\tpath = nested\n\turl = ../original-nested\n");
        git(source, "add", ".gitmodules"); git(source, "update-index", "--add", "--cacheinfo", "160000," + head + ",nested");
        byte[] index = Files.readAllBytes(source.resolve(".git/index"));
        IllegalArgumentException failure = assertThrows(IllegalArgumentException.class,
            () -> runtime().selectWorkspace(source.toString(), Map.of("strategy", "CLONE"), false, 1));
        assertTrue(failure.getMessage().contains("子模块"));
        assertFalse(Files.exists(source.resolve("nested")), "an uninitialized original gitlink must still be rejected");
        assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index"))); assertEquals(head, git(source, "rev-parse", "HEAD").strip());
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
        return gitWithInput(directory, "", arguments);
    }

    private String gitWithInput(Path directory, String input, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-C", directory.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output);
        return output;
    }

    private Map<String, Object> tool(String name, Map<String, Object> arguments) throws IOException {
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
