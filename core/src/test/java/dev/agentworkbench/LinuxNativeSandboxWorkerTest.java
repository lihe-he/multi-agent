package dev.agentworkbench;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import dev.agentworkbench.runtime.LinuxBubblewrapSandboxPolicy;
import dev.agentworkbench.runtime.NativeSandboxPolicy;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 真实独立 Worker 使用平台策略执行 CLI 夹具，核验原请求、预算和实际文件及网络效果。
 */
@EnabledOnOs(OS.LINUX)
@Timeout(90)
class LinuxNativeSandboxWorkerTest {
    private static final String ORIGINAL = "original workspace bytes 中文\n";
    private static final String ARTIFACT = "actual sandbox artifact 中文\n";
    private static final String SESSION_ID = "019ac0d5-486f-7aa2-9434-3c9f7b9ae0b2";
    private static final String CLI_SOURCE = """
        #!/usr/bin/python3
        import http.client, json, os, pathlib, subprocess, sys
        config = json.loads(pathlib.Path('fixture.json').read_text(encoding='utf-8'))
        help_probe = '--help' in sys.argv[1:]
        allowed_environment = {'PATH', 'HOME', 'TMPDIR', 'PWD', 'XDG_CONFIG_HOME', 'XDG_CACHE_HOME',
            'XDG_DATA_HOME', 'LANG', 'LC_ALL', 'NO_COLOR', 'GIT_TERMINAL_PROMPT', 'PYTHONIOENCODING'}
        result = {'home': os.environ.get('HOME'), 'path': os.environ.get('PATH'),
            'temporary': os.environ.get('TMPDIR'), 'workingDirectory': os.getcwd(),
            'extraEnvironment': sorted(set(os.environ) - allowed_environment)}
        private_marker = pathlib.Path(os.environ['HOME']) / 'original-launch-marker'
        result['homeMarkerExisted'] = private_marker.exists()
        private_marker.write_text('private launch', encoding='utf-8')
        result['boundaries'] = []
        for name in config['forbidden']:
            path = pathlib.Path(name)
            item = {}
            try:
                path.read_bytes()
                item['readDenied'] = False
            except OSError:
                item['readDenied'] = True
            try:
                path.write_bytes(b'escaped sandbox write')
                item['writeDenied'] = False
                item['privateShadow'] = path.read_bytes() == b'escaped sandbox write'
                path.unlink()
            except OSError:
                item['writeDenied'] = True
            result['boundaries'].append(item)
        try:
            pathlib.Path('help-created.txt' if help_probe else 'artifact.txt').write_text(config['artifact'], encoding='utf-8')
            result['writeAllowed'] = True
        except OSError:
            result['writeAllowed'] = False
        if not help_probe:
            prompt = sys.stdin.read()
            result['promptReceived'] = prompt == config['prompt']
        connection = http.client.HTTPConnection('127.0.0.1', config['port'], timeout=2)
        try:
            connection.request('GET', '/probe/' + ('help' if help_probe else 'model'))
            response = connection.getresponse()
            result['networkAllowed'] = response.status == 200 and response.read() == b'original server response'
        except OSError:
            result['networkAllowed'] = False
        finally:
            connection.close()
        if help_probe:
            if (result['writeAllowed'] or result['extraEnvironment'] or result['homeMarkerExisted']
                    or result['home'] != '/workbench-home'
                    or result['networkAllowed'] != config['networkAllowed']
                    or not all(item['readDenied'] and (item['writeDenied'] or item.get('privateShadow', False)) for item in result['boundaries'])):
                sys.exit(4)
            print('--ignore-user-config --ignore-rules')
            sys.exit(0)
        if config.get('awaitCancellation', False):
            print(json.dumps({'type': 'thread.started', 'thread_id': config['session']}), flush=True)
            print(json.dumps({'type': 'turn.started'}), flush=True)
            child_source = "import pathlib,time; pathlib.Path('cancel-child-ready.txt').write_text('original child waiting'); time.sleep(30); pathlib.Path('late-artifact.txt').write_text('must never be published')"
            child = subprocess.Popen([sys.executable, '-c', child_source, 'sandbox-cancel-delayed-child'])
            child.wait()
            sys.exit(6)
        child_source = '''import json, pathlib, sys
        result = {}
        try:
            pathlib.Path(sys.argv[1]).read_bytes()
            result['hostReadDenied'] = False
        except OSError:
            result['hostReadDenied'] = True
        try:
            pathlib.Path('child-artifact.txt').write_text('original child effect', encoding='utf-8')
            result['writeAllowed'] = True
        except OSError:
            result['writeAllowed'] = False
        print(json.dumps(result))'''
        child = subprocess.run([sys.executable, '-c', child_source, config['forbidden'][0]],
            capture_output=True, text=True, timeout=5)
        if child.returncode != 0:
            sys.exit(5)
        result['child'] = json.loads(child.stdout)
        print(json.dumps({'type': 'thread.started', 'thread_id': config['session']}))
        print(json.dumps({'type': 'turn.started'}))
        print(json.dumps({'type': 'item.completed', 'item': {'id': 'item-1', 'type': 'agent_message',
            'text': json.dumps(result)}}))
        if config['terminal']:
            print(json.dumps({'type': 'turn.completed', 'usage': {'input_tokens': 1,
                'cached_input_tokens': 0, 'output_tokens': 1}}))
        """;
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @ParameterizedTest
    @CsvSource({"true,DENY", "false,DENY", "true,ALLOW", "false,ALLOW"})
    void originalWorkerEnforcesBothLaunchesAndPersistsOneSessionRequest(boolean readOnly, String network) throws Exception {
        String requestId;
        Path data;
        try (var fixture = new Fixture(readOnly, network, true)) {
            data = fixture.data;
            Map<String, Object> result = fixture.run();
            assertEquals("SUCCEEDED", result.get("status"), result.toString());
            assertEquals(0, num(result, "exitCode", -1));
            assertEquals(Boolean.TRUE, result.get("outputComplete"));
            JsonNode output = mapper.readTree(str(result, "output"));
            assertEquals("/workbench-home", output.path("home").asText());
            assertEquals("/usr/bin:/bin", output.path("path").asText());
            assertEquals("/tmp", output.path("temporary").asText());
            assertEquals(fixture.workspace, output.path("workingDirectory").asText());
            assertTrue(output.path("extraEnvironment").isEmpty(), output.toString());
            assertFalse(output.path("homeMarkerExisted").asBoolean(), "The help process and model process must not share HOME state");
            assertTrue(output.path("promptReceived").asBoolean());
            assertEquals(!readOnly, output.path("writeAllowed").asBoolean());
            assertEquals("ALLOW".equals(network), output.path("networkAllowed").asBoolean());
            assertEquals(3, output.path("boundaries").size());
            for (JsonNode boundary : output.path("boundaries")) {
                assertTrue(boundary.path("readDenied").asBoolean(), output.toString());
                // 私有 /tmp 中可创建同名影子文件；真实宿主原字节在进程返回后独立核对。
                assertTrue(boundary.path("writeDenied").asBoolean() || boundary.path("privateShadow").asBoolean(), output.toString());
            }
            assertTrue(output.path("child").path("hostReadDenied").asBoolean());
            assertEquals(!readOnly, output.path("child").path("writeAllowed").asBoolean());
            assertEquals("ALLOW".equals(network) ? List.of("/probe/help", "/probe/model") : List.of(), fixture.connections);
            assertEquals(Map.of("state", "ENFORCED", "policyId", "linux-bwrap-workspace-v1", "workspace", fixture.workspace,
                "fileAccess", readOnly ? "READ_ONLY" : "WORKSPACE_WRITE", "networkAccess", network), result.get("osSandbox"));
            assertFalse(Files.exists(Path.of(fixture.workspace).resolve("help-created.txt")), "The real help probe must remain read-only");
            assertEquals(!readOnly, Files.exists(Path.of(fixture.workspace).resolve("artifact.txt")));
            assertEquals(!readOnly, Files.exists(Path.of(fixture.workspace).resolve("child-artifact.txt")));
            if (!readOnly) assertEquals(ARTIFACT, Files.readString(Path.of(fixture.workspace).resolve("artifact.txt")));
            fixture.assertOriginalFiles();
            requestId = fixture.assertRequest("SUCCEEDED", "TURN_COMPLETED");
            assertEquals("ACKNOWLEDGED", fixture.store.get("operation", fixture.modelOperation).get("state"));
            assertEquals(fixture.worker.identity().get("id"), fixture.store.get("modelRequest", requestId).get("workerId"));
            assertNotEquals(ProcessHandle.current().pid(), ((Number) fixture.store.get("modelRequest", requestId).get("executorPid")).longValue());
            assertTrue(fixture.logs.stream().anyMatch(text -> text.contains("结构化回执")));
            assertFalse(fixture.logs.toString().contains(SESSION_ID));
            assertFalse(fixture.logs.toString().contains(fixture.prompt));
            assertFalse(fixture.logs.toString().contains("promptReceived"));
        }
        try (var reopened = new ExecutionStore(mapper, data.toString())) {
            assertEquals("SUCCEEDED", reopened.get("modelRequest", requestId).get("state"));
            assertEquals("SUCCEEDED", map(reopened.get("modelRequest", requestId), "nativeReceipt").get("executionState"));
            assertEquals(1, reopened.list("modelRequest").size());
            assertEquals(1, num(map(reopened.get("task", "root"), "budget"), "usedCalls", -1));
        }
    }

    @Test
    void missingNativeTerminalPreservesActualEffectsAndCommittedSessionAsUnknown() throws Exception {
        try (var fixture = new Fixture(false, "DENY", false)) {
            Map<String, Object> result = fixture.run();
            assertEquals("UNKNOWN", result.get("status"), result.toString());
            assertEquals("", result.get("output"));
            assertEquals(ARTIFACT, Files.readString(Path.of(fixture.workspace).resolve("artifact.txt")));
            fixture.assertRequest("UNKNOWN", "MISSING_TURN_TERMINAL");
            assertEquals("UNKNOWN", fixture.store.get("operation", fixture.modelOperation).get("state"));
            fixture.assertOriginalFiles();
        }
    }

    @Test
    void rejectedHostExecutableNeverReceivesThePromptOrCommitsModelBudget() throws Exception {
        try (var fixture = new Fixture(false, "DENY", true)) {
            Path hostExecutable = directory.resolve("host-private").resolve("outside-cli.py");
            Files.writeString(hostExecutable, "#!/usr/bin/python3\nfrom pathlib import Path\nPath(" + mapper.writeValueAsString(directory.resolve("outside-started.txt").toString()) + ").write_text('started')\n");
            Files.setPosixFilePermissions(hostExecutable, PosixFilePermissions.fromString("rwx------"));
            fixture.route.put("executable", hostExecutable.toString());
            assertThrows(IllegalArgumentException.class, fixture::run);
            assertTrue(fixture.store.list("modelRequest").isEmpty());
            assertEquals(0, num(map(fixture.store.get("task", "root"), "budget"), "usedCalls", -1));
            assertFalse(Files.exists(directory.resolve("outside-started.txt")));
            assertTrue(fixture.connections.isEmpty());
            fixture.assertOriginalFiles();
        }
    }

    @Test
    void cancellingOriginalCallStopsItsActualDescendantBeforeAnyDelayedWrite() throws Exception {
        try (var fixture = new Fixture(false, "DENY", true);
             var threads = Executors.newVirtualThreadPerTaskExecutor()) {
            Path configuration = Path.of(fixture.workspace).resolve("fixture.json");
            var config = (com.fasterxml.jackson.databind.node.ObjectNode) mapper.readTree(Files.readAllBytes(configuration));
            config.put("awaitCancellation", true);
            Files.write(configuration, mapper.writeValueAsBytes(config));
            Path started = Path.of(fixture.workspace).resolve("cancel-child-ready.txt");
            Path delayed = Path.of(fixture.workspace).resolve("late-artifact.txt");
            var running = threads.submit(fixture::run);
            try {
                await(() -> Files.exists(started), 10);
                ProcessHandle executor = ProcessHandle.of(((Number) fixture.worker.identity().get("pid")).longValue()).orElseThrow();
                ProcessHandle child = executor.descendants()
                    .filter(process -> process.info().commandLine().orElse("").contains("sandbox-cancel-delayed-child"))
                    .findFirst().orElseThrow(() -> new AssertionError("The actual waiting descendant must have a host process identity"));
                String birth = child.info().startInstant().orElseThrow().toString();
                assertTrue(child.isAlive());
                assertNotEquals(ProcessHandle.current().pid(), child.pid());
                assertFalse(Files.exists(delayed));
                assertEquals(1, fixture.store.list("modelRequest").size());
                assertEquals("DISPATCHING", fixture.store.list("modelRequest").getFirst().get("state"));

                fixture.cancelled.set(true);
                ExecutionException cancelled = assertThrows(ExecutionException.class, () -> running.get(20, TimeUnit.SECONDS));
                assertInstanceOf(CancellationException.class, cancelled.getCause());
                await(() -> !originalAlive(child, birth), 5);
                assertFalse(Files.exists(delayed), "The original child must stop before its delayed write");
                assertEquals(1, fixture.store.list("modelRequest").size());
                Map<String, Object> request = fixture.store.list("modelRequest").getFirst();
                assertEquals("UNKNOWN", request.get("state"));
                assertEquals("CLI_INTERRUPTED", request.get("errorType"));
                assertEquals("UNKNOWN", request.get("remoteOutcome"));
                assertEquals("CLI_SESSION", request.get("unit"));
                assertEquals("UNKNOWN", map(request, "usage").get("cost"));
                assertEquals("UNKNOWN", map(request, "nativeReceipt").get("state"));
                assertEquals("", str(request, "outputPreview"));
                assertEquals(fixture.worker.identity().get("id"), request.get("workerId"));
                assertEquals("UNKNOWN", fixture.store.get("operation", fixture.modelOperation).get("state"));
                assertEquals("UNKNOWN", fixture.store.get("workerLease", str(request, "workerLeaseId")).get("state"));
                assertEquals(1, num(map(fixture.store.get("task", "root"), "budget"), "usedCalls", -1));
                fixture.assertOriginalFiles();
            } finally {
                fixture.cancelled.set(true);
                fixture.runtime.stop("root");
            }
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path data = Files.createDirectories(directory.resolve("core-data"));
        final Path source = Files.createDirectories(directory.resolve("source 中文"));
        final Path hostSecret = Files.writeString(Files.createDirectories(directory.resolve("host-private")).resolve("secret.txt"), "host-private-" + UUID.randomUUID());
        final Path coreSecret = Files.writeString(data.resolve("core-secret.txt"), "core-private-" + UUID.randomUUID());
        final byte[] hostBytes = Files.readAllBytes(hostSecret), coreBytes = Files.readAllBytes(coreSecret);
        final List<String> connections = new CopyOnWriteArrayList<>(), logs = new CopyOnWriteArrayList<>();
        final AtomicBoolean cancelled = new AtomicBoolean();
        final HttpServer server;
        final String prompt = "original-private-prompt-" + UUID.randomUUID();
        final ExecutionStore store;
        final ProcessWorkspaceRuntime runtime;
        final WorkbenchCatalog catalog;
        final LocalExecutionWorker worker;
        final Map<String, Object> route;
        final boolean readOnly;
        final String workspace;
        String modelOperation = "";

        Fixture(boolean readOnly, String network, boolean terminal) throws Exception {
            this.readOnly = readOnly;
            requireAvailable(source, data);
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            LocalExecutionWorker opened = null;
            ExecutionStore openedStore = null;
            try {
            Files.writeString(source.resolve("fixture-native.py"), CLI_SOURCE, StandardCharsets.UTF_8);
            Files.setPosixFilePermissions(source.resolve("fixture-native.py"), PosixFilePermissions.fromString("rwx------"));
            Files.writeString(source.resolve("input.txt"), ORIGINAL);
            server.createContext("/probe/", exchange -> {
                connections.add(exchange.getRequestURI().getPath());
                byte[] bytes = "original server response".getBytes(StandardCharsets.UTF_8);
                try { exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes); }
                finally { exchange.close(); }
            });
            Files.write(source.resolve("fixture.json"), mapper.writeValueAsBytes(obj("forbidden", List.of(hostSecret.toString(), coreSecret.toString(), data.resolve("workbench.sqlite").toString()),
                "port", server.getAddress().getPort(), "prompt", prompt, "artifact", ARTIFACT, "networkAllowed", "ALLOW".equals(network), "terminal", terminal, "session", SESSION_ID)));
            git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Sandbox Test"); git(source, "config", "user.email", "sandbox@example.invalid");
            git(source, "config", "core.autocrlf", "false"); git(source, "add", "."); git(source, "commit", "-m", "original sandbox fixture");
            store = new ExecutionStore(mapper, data.toString());
            openedStore = store;
            runtime = new ProcessWorkspaceRuntime(mapper, data.toString());
            catalog = new WorkbenchCatalog(store, runtime, new PlanValidator());
                server.start();
                catalog.defaults(); worker = runtime.startWorker(store, catalog.nodes()); opened = worker;
                Map<String, Object> project = catalog.importProject(obj("name", "sandbox worker fixture", "path", source.toString()));
                route = new java.util.LinkedHashMap<>(catalog.saveRoute(obj("name", "strict original CLI", "engine", "CODEX", "executable", "fixture-native.py",
                    "requireOsSandbox", true, "osSandboxNetwork", network, "enabled", true)));
                store.createTask(obj("id", "root", "projectId", project.get("id"), "state", "RUNNING", "title", "sandbox worker fixture", "goal", "original sandbox execution", "workspace", "", "evidence", new ArrayList<>(),
                    "execution", catalog.nodes().snapshot(str(project, "id"), obj(), 1), "routeId", route.get("id"), "routeSnapshot", route,
                    "contract", obj("scope", List.of("**"), "commands", List.of(), "acceptance", List.of("actual sandbox execution")),
                    "budget", obj("maxCalls", 3, "reserveCalls", 1, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0)));
                String confirmed = catalog.nodes().sourcePath(store.get("task", "root"));
                String preparation = str(store.beginOperation("root", "prepare", "WORKSPACE", confirmed), "id");
                worker.dispatch(preparation, confirmed, obj());
                Map<String, Object> prepared = runtime.prepare(confirmed, "root", false);
                worker.finish(preparation, "ACKNOWLEDGED", prepared);
                store.editTask("root", task -> task.putAll(prepared), null, "");
                workspace = str(prepared, "workspace");
                route.put("executable", Path.of(workspace).resolve("fixture-native.py").toString());
            } catch (Exception | Error failure) {
                if (opened != null) opened.close(); server.stop(0); if (openedStore != null) openedStore.close(); throw failure;
            }
        }

        Map<String, Object> run() {
            String runId = str(store.queueAgentRun("root", obj("id", "implement", "kind", "IMPLEMENT", "role", "developer"), workspace, runtime.fingerprint(workspace), false), "id");
            modelOperation = str(store.beginOperation("root", "implement", "MODEL", workspace, runId), "id");
            worker.dispatch(modelOperation, workspace, obj());
            var observer = new RequestObservation(store, catalog, "root", runId, false, cancelled::get, new AtomicBoolean(), () -> {}, null, 0);
            try {
                Map<String, Object> result = runtime.run(route, "", workspace, prompt, readOnly, List.of("**"), cancelled::get, logs::add, observer);
                worker.evidence("root", modelOperation, obj("id", id(), "stage", "implement", "kind", "AGENT", "status", "SUCCEEDED".equals(str(result, "status")) ? "PASSED" : str(result, "status"), "output", catalog.redact(str(result, "output"))));
                store.finishAgentRun(runId, "SUCCEEDED".equals(str(result, "status")) ? "SUCCEEDED" : "UNKNOWN", "actual sandbox returned result");
                return result;
            } finally { worker.settleRun(runId); }
        }

        String assertRequest(String state, String reason) {
            assertEquals(1, store.list("modelRequest").size(), "The isolated help command cannot consume a model session");
            Map<String, Object> request = store.list("modelRequest").getFirst();
            assertEquals(state, request.get("state"));
            assertEquals("CLI_SESSION", request.get("unit"));
            assertEquals("UNKNOWN", request.get("remoteOutcome"));
            assertEquals("UNKNOWN", map(request, "usage").get("modelRequests"));
            assertEquals("UNKNOWN", map(request, "usage").get("cost"));
            assertEquals(reason, map(request, "nativeReceipt").get("reason"));
            assertEquals(state, map(request, "nativeReceipt").get("executionState"));
            assertFalse(request.toString().contains(SESSION_ID));
            assertFalse(request.toString().contains(new String(hostBytes, StandardCharsets.UTF_8)));
            assertFalse(request.toString().contains(new String(coreBytes, StandardCharsets.UTF_8)));
            assertEquals(1, num(map(store.get("task", "root"), "budget"), "usedCalls", -1));
            return str(request, "id");
        }

        void assertOriginalFiles() throws Exception {
            assertEquals(ORIGINAL, Files.readString(source.resolve("input.txt")));
            assertEquals(ORIGINAL, Files.readString(Path.of(workspace).resolve("input.txt")));
            assertFalse(Files.exists(source.resolve("artifact.txt")));
            assertFalse(Files.exists(source.resolve("help-created.txt")));
            assertArrayEquals(hostBytes, Files.readAllBytes(hostSecret));
            assertArrayEquals(coreBytes, Files.readAllBytes(coreSecret));
            assertEquals("", git(source, "status", "--porcelain=v1"));
        }

        @Override public void close() throws Exception {
            try { worker.close(); }
            finally { server.stop(0); store.close(); }
        }
    }

    /**
     * 强制 Linux 验收不能把缺失 Python、命名空间或 seccomp 能力当作条件跳过。
     */
    private static void requireAvailable(Path workspace, Path data) {
        boolean required = Boolean.getBoolean("workbench.require-linux-sandbox");
        boolean python = Files.isExecutable(Path.of("/usr/bin/python3"));
        if (required) assertTrue(python, "Mandatory native Worker integration needs /usr/bin/python3");
        assumeTrue(python, "The real native Worker fixture needs /usr/bin/python3");
        var policy = new LinuxBubblewrapSandboxPolicy(data);
        var decision = policy.prepare(new NativeSandboxPolicy.Request(workspace, NativeSandboxPolicy.FileAccess.READ_ONLY,
            NativeSandboxPolicy.NetworkAccess.DENY), List.of("/usr/bin/python3", "-c", "print('ready')"));
        if (required) assertEquals(NativeSandboxPolicy.Status.ENFORCED, decision.status(), decision.reason());
        assumeTrue(decision.status() == NativeSandboxPolicy.Status.ENFORCED, "Real native Worker sandbox unavailable: " + decision.reason());
    }

    private static boolean originalAlive(ProcessHandle process, String birth) {
        return process.isAlive() && process.info().startInstant().map(start -> birth.equals(start.toString())).orElse(true);
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "The original sandbox process condition did not arrive within its bounded wait");
    }

    private static String git(Path workspace, String... arguments) throws Exception {
        var command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false"));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true).start();
        try {
            boolean ended = process.waitFor(20, TimeUnit.SECONDS);
            if (!ended) { process.destroyForcibly(); process.waitFor(3, TimeUnit.SECONDS); }
            assertTrue(ended, "Sandbox fixture Git exceeded its bounded wait");
            String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), output);
            return output;
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }
}
