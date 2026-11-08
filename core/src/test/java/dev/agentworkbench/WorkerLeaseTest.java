package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerLeaseTest {
    @TempDir Path directory;

    private final class Fixture implements AutoCloseable {
        final Path data = directory.resolve("data");
        final Path source = Files.createDirectories(directory.resolve("source"));
        final ExecutionStore store = new ExecutionStore(new ObjectMapper(), data.toString());
        final ExecutionNodes nodes = new ExecutionNodes(store, new WorkspaceRuntime(new ObjectMapper(), data.toString()));
        final MutableClock clock = new MutableClock();
        final LocalExecutionWorker worker = new LocalExecutionWorker(store, nodes, clock, false);
        final Map<String, Object> project = nodes.bindImportedProject(obj("id", "project", "name", "local", "path", source.toString()));
        final Map<String, Object> execution = nodes.snapshot("project", obj(), 1);
        final Map<String, Object> task = store.createTask(obj("id", "task", "projectId", "project", "state", "RUNNING", "workspace", source.toString(), "execution", execution, "evidence", new ArrayList<>()));

        Fixture() throws Exception { }
        String operation() { return str(store.beginOperation("task", "verify", "VERIFICATION", "check"), "id"); }
        Map<String, Object> lease(String operationId) { return store.get("workerLease", str(store.get("operation", operationId), "workerLeaseId")); }
        void trigger(String sql) throws Exception { try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var command = db.createStatement()) { command.execute(sql); } }
        @Override public void close() throws Exception { worker.close(); store.close(); }
    }

    @Test
    void dispatchAndReceiptHaveOneOwnerAndCannotBypassTheWorkerGateway() throws Exception {
        try (var fixture = new Fixture()) {
            String operation = fixture.operation(); fixture.worker.dispatch(operation, fixture.source.toString(), obj("version", "v1"));
            Map<String, Object> lease = fixture.lease(operation);
            assertEquals("ACTIVE", lease.get("state")); assertEquals(fixture.worker.identity().get("pid"), ProcessHandle.current().pid());
            assertEquals(1, num(fixture.store.get("operation", operation), "dispatchCount", 0)); assertFalse(WorkerLedger.publicLease(lease).containsKey("claimDigest"));
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.store.markOperation(operation, "ACKNOWLEDGED", obj())).status());
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.store.recordEvidence("task", operation, obj("id", "forged", "status", "PASSED"))).status());
            fixture.worker.evidence("task", operation, obj("id", "proof", "stage", "verify", "status", "PASSED", "output", "actual result", "version", "v1"));
            assertEquals("ACKNOWLEDGED", fixture.store.get("operation", operation).get("state")); assertEquals("RELEASED", fixture.lease(operation).get("state"));
            assertEquals("CONFIRMED", fixture.lease(operation).get("stopState")); assertEquals(lease.get("id"), fixture.store.get("evidence", "proof").get("workerLeaseId"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.worker.dispatch(operation, fixture.source.toString(), obj())).status());
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.store.markOperation(operation, "UNKNOWN", obj())).status()); assertTrue(fixture.store.healthy());
        }
    }

    @Test
    void expiryStopsOwnershipKeepsLateObservationUnknownAndFreshIdsCannotReplay() throws Exception {
        try (var fixture = new Fixture()) {
            String operation = fixture.operation(); fixture.worker.dispatch(operation, fixture.source.toString(), obj());
            fixture.store.editTask("task", task -> task.put("error", "original execution failure"), null, "");
            fixture.clock.advance(31_000); fixture.worker.heartbeat();
            assertFalse(fixture.worker.current("task")); assertEquals("UNKNOWN", fixture.store.get("task", "task").get("state"));
            assertTrue(str(fixture.store.get("task", "task"), "error").contains("original execution failure"));
            assertEquals("UNKNOWN", fixture.lease(operation).get("state")); assertEquals("UNKNOWN", fixture.lease(operation).get("stopState"));
            assertThrows(ApiException.class, () -> fixture.worker.requireStopped("task"));
            fixture.worker.evidence("task", operation, obj("id", "late", "stage", "verify", "status", "PASSED", "output", "received after expiration"));
            assertEquals("UNKNOWN", fixture.store.get("evidence", "late").get("status")); assertEquals("PASSED", fixture.store.get("evidence", "late").get("observedStatus"));
            assertEquals("received after expiration", fixture.store.get("evidence", "late").get("output")); assertEquals("UNKNOWN", fixture.store.get("operation", operation).get("state")); fixture.worker.requireStopped("task");
            try (var replacement = new LocalExecutionWorker(fixture.store, fixture.nodes, fixture.clock, false)) {
                assertEquals(num(fixture.worker.identity(), "epoch", 0) + 1, num(replacement.identity(), "epoch", 0));
                String fresh = fixture.operation(); assertEquals(409, assertThrows(ApiException.class, () -> replacement.dispatch(fresh, fixture.source.toString(), obj())).status());
                assertEquals(0, num(fixture.store.get("operation", fresh), "dispatchCount", -1)); assertEquals(1, fixture.store.workerLeases("", "task", "UNKNOWN").size());
                fixture.store.markOperation(operation, "RECONCILED", obj("reconciledBy", "USER", "note", "process and effect checked"));
                fixture.store.editTask("task", value -> value.put("state", "RUNNING"), null, "");
                String resumed = fixture.operation(); replacement.dispatch(resumed, fixture.source.toString(), obj()); replacement.finish(resumed, "ACKNOWLEDGED", obj("result", "new execution"));
                assertEquals("RECONCILED", fixture.lease(operation).get("state")); assertEquals("ACKNOWLEDGED", fixture.store.get("operation", resumed).get("state"));
            }
        }
    }

    @Test
    void wrongTokenAndEpochCannotConfirmAnotherExecution() throws Exception {
        try (var fixture = new Fixture()) {
            WorkerLedger ledger = new WorkerLedger(fixture.store, fixture.nodes); String operation = fixture.operation();
            ledger.claim(fixture.worker.identity(), "operation", operation, fixture.source.toString(), "test-owner-token", obj(), fixture.clock.instant());
            assertEquals(403, assertThrows(ApiException.class, () -> ledger.receipt(fixture.worker.identity(), "operation", operation, "wrong-token", "ACKNOWLEDGED", fixture.clock.instant())).status());
            Map<String, Object> stale = fixture.worker.identity(); stale.put("epoch", num(stale, "epoch", 0) + 1);
            assertEquals(403, assertThrows(ApiException.class, () -> ledger.receipt(stale, "operation", operation, "test-owner-token", "ACKNOWLEDGED", fixture.clock.instant())).status());
            assertEquals("ACTIVE", fixture.lease(operation).get("state")); assertEquals("DISPATCHED", fixture.store.get("operation", operation).get("state")); assertTrue(fixture.store.healthy());
        }
    }

    @Test
    void monotonicExpiryAndClockRollbackCannotExtendOldOwnership() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.worker.close(); java.util.concurrent.atomic.AtomicLong elapsed = new java.util.concurrent.atomic.AtomicLong();
            try (var worker = new LocalExecutionWorker(fixture.store, fixture.nodes, fixture.clock, false, elapsed::get)) {
                String operation = fixture.operation(); worker.dispatch(operation, fixture.source.toString(), obj());
                elapsed.addAndGet(java.util.concurrent.TimeUnit.SECONDS.toNanos(31));
                assertFalse(worker.current("task")); worker.evidence("task", operation, obj("id", "clock-late", "status", "PASSED", "output", "late original output"));
                assertEquals("UNKNOWN", fixture.store.get("evidence", "clock-late").get("status")); assertEquals("PASSED", fixture.store.get("evidence", "clock-late").get("observedStatus"));
                assertEquals("UNKNOWN", fixture.lease(operation).get("state")); worker.requireStopped("task"); worker.heartbeat();
                assertFalse(worker.current("task")); assertNotEquals("RUNNING", fixture.store.get("worker", str(worker.identity(), "id")).get("state"));
                fixture.store.markOperation(operation, "RECONCILED", obj("reconciledBy", "USER", "note", "elapsed ownership checked"));
                fixture.store.editTask("task", value -> value.put("state", "RUNNING"), null, "");
            }
            try (var worker = new LocalExecutionWorker(fixture.store, fixture.nodes, fixture.clock, false, elapsed::get)) {
                String operation = fixture.operation(); worker.dispatch(operation, fixture.source.toString(), obj());
                elapsed.addAndGet(java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(100)); fixture.clock.advance(-3_600_000);
                assertFalse(worker.current("task")); worker.evidence("task", operation, obj("id", "rollback-late", "status", "PASSED", "output", "output after wall clock rollback"));
                assertEquals("UNKNOWN", fixture.store.get("evidence", "rollback-late").get("status")); assertEquals("PASSED", fixture.store.get("evidence", "rollback-late").get("observedStatus"));
                assertEquals("UNKNOWN", fixture.lease(operation).get("state")); worker.requireStopped("task"); worker.heartbeat();
                assertNotEquals("RUNNING", fixture.store.get("worker", str(worker.identity(), "id")).get("state"));
            }
        }
    }

    @Test
    void claimPersistenceFailureRollsBackDispatchBeforeAnyExecution() throws Exception {
        try (var fixture = new Fixture()) {
            String operation = fixture.operation();
            fixture.trigger("CREATE TRIGGER fail_claim BEFORE INSERT ON entities WHEN NEW.kind='workerLease' BEGIN SELECT RAISE(ABORT,'injected lease failure'); END");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.worker.dispatch(operation, fixture.source.toString(), obj())).status());
            assertEquals("PREPARED", fixture.store.get("operation", operation).get("state")); assertTrue(fixture.store.list("workerLease").isEmpty()); assertFalse(fixture.store.healthy());
        }
    }

    @Test
    void evidenceAndLeaseReleaseRollBackTogether() throws Exception {
        try (var fixture = new Fixture()) {
            String operation = fixture.operation(); fixture.worker.dispatch(operation, fixture.source.toString(), obj());
            fixture.trigger("CREATE TRIGGER fail_release BEFORE UPDATE ON entities WHEN NEW.kind='workerLease' AND json_extract(NEW.body,'$.state')='RELEASED' BEGIN SELECT RAISE(ABORT,'injected receipt failure'); END");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.worker.evidence("task", operation, obj("id", "rollback-proof", "stage", "verify", "status", "PASSED"))).status());
            assertNull(fixture.store.find("evidence", "rollback-proof")); assertTrue(objects(fixture.store.get("task", "task").get("evidence")).isEmpty());
            assertEquals("DISPATCHED", fixture.store.get("operation", operation).get("state")); assertEquals("ACTIVE", fixture.lease(operation).get("state")); assertFalse(fixture.store.healthy());
        }
    }

    @Test
    void workerIdentityPersistsByNodeAndRestartNeverReusesAClaim() throws Exception {
        String nodeId, workerId, operationId;
        try (var fixture = new Fixture()) {
            nodeId = str(fixture.worker.identity(), "executionNodeId"); workerId = str(fixture.worker.identity(), "id"); operationId = fixture.operation();
            fixture.worker.dispatch(operationId, fixture.source.toString(), obj());
        }
        try (var store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString())) {
            ExecutionNodes nodes = new ExecutionNodes(store, new WorkspaceRuntime(new ObjectMapper(), directory.resolve("data").toString()));
            try (var replacement = new LocalExecutionWorker(store, nodes)) {
                assertEquals(nodeId, replacement.identity().get("executionNodeId")); assertNotEquals(workerId, replacement.identity().get("id"));
                assertEquals(2, num(replacement.identity(), "epoch", 0)); assertEquals("UNKNOWN", store.get("operation", operationId).get("state")); assertEquals("UNKNOWN", store.get("task", "task").get("state"));
                assertEquals(1, store.workerLeases("", "task", "UNKNOWN").size()); assertFalse(replacement.current("task"));
            }
        }
    }

    @Test
    void wrongDirectoryAndRevokedConfigurationAreRejectedBeforeDispatch() throws Exception {
        try (var fixture = new Fixture()) {
            String operation = fixture.operation(); Path other = Files.createDirectories(directory.resolve("other"));
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.worker.dispatch(operation, other.toString(), obj())).status()); assertTrue(fixture.store.list("workerLease").isEmpty());
            Map<String, Object> node = fixture.nodes.localNode(); fixture.nodes.saveNode(obj("id", node.get("id"), "expectedVersion", 1, "name", "renamed", "enabled", true));
            assertFalse(fixture.worker.current("task"));
            String revoked = fixture.operation(); assertEquals(409, assertThrows(ApiException.class, () -> fixture.worker.dispatch(revoked, fixture.source.toString(), obj())).status());
            assertEquals("REJECTED", fixture.store.get("operation", revoked).get("state")); assertEquals(1, num(fixture.execution, "nodeVersion", 0));
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.nodes.saveNode(obj("id", node.get("id"), "expectedVersion", 2.5, "name", "invalid"))).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.nodes.saveNode(obj("id", node.get("id"), "expectedVersion", 2, "name", "invalid", "enabled", "true"))).status()); assertTrue(fixture.store.healthy());
        }
    }

    @Test
    void bindingVersionsAndProjectNodeBoundariesAreValidatedAgainstRealGit() throws Exception {
        try (var fixture = new Fixture()) {
            Path repository = Files.createDirectories(directory.resolve("repository")); Files.writeString(repository.resolve("example.txt"), "one\n");
            git(repository, "init", "--initial-branch=main"); git(repository, "add", "example.txt"); git(repository, "-c", "user.name=fixture", "-c", "user.email=fixture@localhost", "-c", "core.hooksPath=", "commit", "-m", "baseline");
            Map<String, Object> binding = fixture.nodes.saveBinding(obj("projectId", "project", "executionNodeId", fixture.worker.identity().get("executionNodeId"), "name", "second checkout", "path", repository.toString()));
            Map<String, Object> snapshot = fixture.nodes.snapshot("project", obj("repositoryBindingId", binding.get("id"), "bindingVersion", 1, "nodeVersion", 1), 2);
            assertEquals(repository.toRealPath().toString(), snapshot.get("sourcePath")); assertNotEquals(fixture.execution.get("repositoryBindingId"), snapshot.get("repositoryBindingId"));
            fixture.nodes.saveBinding(obj("id", binding.get("id"), "expectedVersion", 1, "projectId", "project", "executionNodeId", snapshot.get("executionNodeId"), "name", "version two", "path", repository.toString()));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.nodes.snapshot("project", obj("repositoryBindingId", binding.get("id"), "bindingVersion", 1), 3)).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.nodes.saveBinding(obj("projectId", "project", "executionNodeId", snapshot.get("executionNodeId"), "name", "relative", "path", "relative"))).status());
            fixture.nodes.bindImportedProject(obj("id", "other-project", "name", "other", "path", fixture.source.toString()));
            assertEquals(403, assertThrows(ApiException.class, () -> fixture.nodes.snapshot("other-project", obj("repositoryBindingId", binding.get("id")), 1)).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.nodes.saveNode(obj("id", snapshot.get("executionNodeId"), "expectedVersion", 1, "name", "spoof", "epoch", 99))).status()); assertTrue(fixture.store.healthy());
        }
    }

    private static void git(Path cwd, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git")); command.addAll(List.of(args)); Process process = new ProcessBuilder(command).directory(cwd.toFile()).redirectErrorStream(true).start();
        String output = new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); assertEquals(0, process.waitFor(), output);
    }

    private static final class MutableClock extends Clock {
        private Instant time = Instant.parse("2026-11-07T12:00:00Z");
        void advance(long millis) { time = time.plusMillis(millis); }
        @Override public ZoneId getZone() { return ZoneId.of("UTC"); }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return time; }
    }
}
