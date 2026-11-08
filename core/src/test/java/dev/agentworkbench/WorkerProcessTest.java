package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实子 JVM、Node 命令和管道关闭验证进程身份、领取与停止，不以启动回执替代执行。
 */
class WorkerProcessTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path work = Files.createDirectories(directory.resolve("真实 Worker 中文 workspace"));
        final Path data = directory.resolve("private-worker-data");
        final List<Map<String, Object>> heartbeats = new CopyOnWriteArrayList<>();
        final List<Map<String, Object>> callbacks = new CopyOnWriteArrayList<>();
        final List<String> losses = new CopyOnWriteArrayList<>();
        final WorkerProcess process = new WorkerProcess(mapper, data.toString(), (method, arguments) -> {
            assertEquals("CALLBACK", method); callbacks.add(arguments); return true;
        });
        final Map<String, Object> identity = process.processIdentity();
        final String token = "test-only-original-claim";

        Fixture() throws Exception {
            identity.putAll(obj("id", "actual-worker", "executionNodeId", "node", "epoch", 1)); process.bind(identity, heartbeats::add, losses::add);
        }
        Map<String, Object> lease(String operation) throws Exception {
            return obj("id", "lease-" + operation, "operationKind", "operation", "operationId", operation, "taskId", "root", "projectId", "project", "agentRunId", "",
                "workerId", identity.get("id"), "executionNodeId", identity.get("executionNodeId"), "epoch", identity.get("epoch"), "repositoryBindingId", "binding", "bindingVersion", 1,
                "authorizationVersion", 1, "workingDirectory", work.toString(), "realWorkingDirectory", work.toRealPath().toString(), "operationType", "VERIFICATION", "subjectId", "root",
                "claimDigest", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(token.getBytes(StandardCharsets.UTF_8))));
        }
        Map<String, Object> verify(Map<String, Object> lease, String call, long sequence, String command) throws Exception {
            return object(process.invoke(call, lease, token, sequence, "VERIFY", obj("workspace", work.toString(), "command", command)).get(15, TimeUnit.SECONDS));
        }
        @Override public void close() { process.close(); assertTrue(process.treeStopped(), () -> process.stopFacts().toString()); }
    }

    @Test
    void actualJavaIdentityHasAnIndependentHeartbeatAndNoCoreEnvironment() throws Exception {
        try (var fixture = new Fixture()) {
            assertNotEquals(ProcessHandle.current().pid(), fixture.process.pid());
            assertEquals(ProcessHandle.current().pid(), ((Number) fixture.identity.get("parentPid")).longValue());
            assertEquals("LOCAL_PROCESS", fixture.identity.get("deployment"));
            assertEquals(fixture.identity.get("processStartedAt"), ProcessHandle.of(fixture.process.pid()).orElseThrow().info().startInstant().orElseThrow().toString());
            assertEquals(false, fixture.identity.get("controlEnvironmentPresent")); assertEquals(false, fixture.identity.get("jvmOptionsEnvironmentPresent"));
            assertEquals(false, fixture.identity.get("controlPropertiesPresent")); assertFalse(Files.exists(fixture.data.resolve("workbench.sqlite")));
            await(() -> !fixture.heartbeats.isEmpty(), 8);
            Map<String, Object> heartbeat = fixture.heartbeats.getFirst();
            assertEquals(1, ((Number) heartbeat.get("sequence")).longValue()); assertEquals(fixture.process.pid(), ((Number) heartbeat.get("pid")).longValue());
            assertEquals(fixture.identity.get("processStartedAt"), heartbeat.get("processStartedAt")); assertTrue(fixture.losses.isEmpty());
        }
    }

    @Test
    void originalClaimExecutesNodeOnceRejectsReplayAndPreservesLiteralOutput() throws Exception {
        try (var fixture = new Fixture()) {
            Files.writeString(fixture.work.resolve("verify.mjs"), "import fs from 'node:fs'; fs.appendFileSync('dispatch-count.txt','1'); console.log('原始进程回执 😀 <script>literal</script>'); console.log(JSON.stringify({pid:process.pid,parent:process.ppid,coreEnvironment:Object.keys(process.env).some(k=>k.startsWith('WORKBENCH_'))}));\n");
            Map<String, Object> lease = fixture.lease("verify"); Map<String, Object> response = fixture.verify(lease, "original-call", 1, "node verify.mjs");
            Map<String, Object> result = map(response, "value"); assertEquals("SUCCEEDED", result.get("status")); assertTrue(str(result, "output").contains("原始进程回执 😀 <script>literal</script>"));
            assertTrue(str(result, "output").contains("\"coreEnvironment\":false")); assertEquals("1", Files.readString(fixture.work.resolve("dispatch-count.txt")));
            assertEquals(fixture.process.pid(), ((Number) response.get("executorPid")).longValue()); assertEquals(lease.get("id"), response.get("workerLeaseId"));
            WorkerRpc.RemoteFailure duplicate = assertInstanceOf(WorkerRpc.RemoteFailure.class, assertThrows(ExecutionException.class, () -> fixture.verify(lease, "different-call-same-sequence", 1, "node verify.mjs")).getCause());
            assertEquals(409, duplicate.status()); assertEquals("1", Files.readString(fixture.work.resolve("dispatch-count.txt")));
            fixture.process.release(lease, fixture.token).get(5, TimeUnit.SECONDS);
            assertThrows(ExecutionException.class, () -> fixture.verify(lease, "after-release", 2, "node verify.mjs")); assertEquals("1", Files.readString(fixture.work.resolve("dispatch-count.txt")));
            assertFalse(fixture.callbacks.isEmpty()); assertEquals("CHECK_CURRENT", fixture.callbacks.getFirst().get("action")); assertTrue(fixture.losses.isEmpty());
        }
    }

    @Test
    void wrongNonceTargetAndOperationCannotDispatchACommand() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.lease("bad-token"); Map<String, Object> parameters = obj("workspace", fixture.work.toString(), "command", "node -e \"require('fs').writeFileSync('should-not-exist','x')\"");
            WorkerRpc.RemoteFailure tokenError = assertInstanceOf(WorkerRpc.RemoteFailure.class, assertThrows(ExecutionException.class,
                () -> fixture.process.invoke("bad-token", lease, "wrong-private-token", 1, "VERIFY", parameters).get(5, TimeUnit.SECONDS)).getCause());
            assertEquals(403, tokenError.status());
            Map<String, Object> target = new java.util.LinkedHashMap<>(parameters); target.put("workspace", directory.toString());
            assertThrows(ExecutionException.class, () -> fixture.process.invoke("bad-target", fixture.lease("bad-target"), fixture.token, 1, "VERIFY", target).get(5, TimeUnit.SECONDS));
            Map<String, Object> wrongKind = fixture.lease("bad-operation"); wrongKind.put("operationType", "MODEL");
            assertThrows(ExecutionException.class, () -> fixture.process.invoke("bad-operation", wrongKind, fixture.token, 1, "VERIFY", parameters).get(5, TimeUnit.SECONDS));
            assertFalse(Files.exists(fixture.work.resolve("should-not-exist"))); assertTrue(fixture.callbacks.isEmpty()); assertTrue(fixture.losses.isEmpty());
        }
    }

    @Test
    void closingPrivateInputStopsTheActualInFlightCommandTree() throws Exception {
        try (var fixture = new Fixture()) {
            Files.writeString(fixture.work.resolve("waiting.mjs"), "import fs from 'node:fs'; fs.writeFileSync('actual-node-pid.txt',String(process.pid)); setInterval(()=>{},1000);\n");
            var result = fixture.process.invoke("waiting-call", fixture.lease("waiting"), fixture.token, 1, "VERIFY", obj("workspace", fixture.work.toString(), "command", "node waiting.mjs"));
            await(() -> Files.exists(fixture.work.resolve("actual-node-pid.txt")), 10);
            long pid = Long.parseLong(Files.readString(fixture.work.resolve("actual-node-pid.txt"))); ProcessHandle actual = ProcessHandle.of(pid).orElseThrow();
            assertTrue(actual.isAlive()); assertNotEquals(ProcessHandle.current().pid(), pid); fixture.process.close();
            await(() -> !actual.isAlive(), 5); assertFalse(fixture.process.alive()); assertTrue(fixture.process.treeStopped());
            assertTrue(result.isDone() || result.get(5, TimeUnit.SECONDS) != null);
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(50);
        assertTrue(condition.getAsBoolean(), "真实进程条件未在有界时间内达到");
    }
    @SuppressWarnings("unchecked") private static Map<String, Object> object(Object value) { return (Map<String, Object>) value; }
}
