package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 SQLite 与实际文件锁核对根领取、回滚、未知保护和原编号历史。
 */
class WorkspaceWriteLedgerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path data = directory.resolve("data"), source = Files.createDirectories(directory.resolve("source")).toRealPath();
        final ExecutionStore store = new ExecutionStore(mapper, data.toString());
        final WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString());
        final ExecutionNodes nodes = new ExecutionNodes(store, runtime);
        final LocalExecutionWorker worker = new LocalExecutionWorker(store, nodes, Clock.systemUTC(), false);
        final WorkspaceWriteLedger writes = new WorkspaceWriteLedger(store);
        final TaskDispatchQueue queue = new TaskDispatchQueue(store, writes, worker::identity);
        final Map<String, Object> project = nodes.bindImportedProject(obj("id", "project", "name", "write fixture", "path", source.toString()));
        final Map<String, Object> task = store.createTask(obj("id", "task", "projectId", "project", "state", "DRAFT", "outcome", "UNDETERMINED", "workspace", "", "version", "",
            "execution", nodes.snapshot("project", obj(), 1), "contract", obj("confirmed", false, "version", 1), "budget", obj("maxCalls", 12, "reserveCalls", 4, "usedCalls", 0), "evidence", new ArrayList<>()));

        Fixture() throws Exception { }
        Map<String, Object> claim() {
            Map<String, Object> action = queue.enqueue("task", "USER_APPROVAL", value -> map(value, "contract").put("confirmed", true));
            queue.claim(str(action, "id"), num(action, "version", 0), value -> worker.requireQueueOwner("task")); return writes.current("task");
        }
        Map<String, Object> grant() {
            Map<String, Object> lease = claim(); String operation = str(store.beginOperation("task", "prepare", "WORKSPACE", source.toString()), "id");
            worker.dispatch(operation, source.toString(), obj()); Map<String, Object> grant = runtime.acquireWorkspaceWrite(WorkspaceWriteLedger.identity(lease));
            worker.finish(operation, "ACKNOWLEDGED", obj("workspaceWriteGrant", grant)); return writes.current("task");
        }
        void releaseAndFinish(Map<String, Object> lease) {
            writes.released(str(lease, "id"), runtime.releaseWorkspaceWrite(WorkspaceWriteLedger.identity(lease), ""), "");
            store.editTask("task", value -> value.put("state", "PAUSED"), null, ""); queue.finish(str(lease, "taskExecutionId"));
        }
        void sql(String text) throws Exception {
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var statement = db.createStatement()) { statement.execute(text); }
        }
        @Override public void close() throws Exception { runtime.closeWorkspaceWrites(); worker.close(); store.close(); }
    }

    @Test
    void rootClaimAndWriteIntentShareOriginalIdentityWithoutGrantingModelPermission() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.claim(), task = fixture.store.get("task", "task");
            assertEquals("PREPARED", lease.get("state")); assertFalse(bool(lease, "protectionReleased", true));
            assertEquals(fixture.store.coreSessionId(), lease.get("ownerCoreSessionId")); assertEquals(fixture.worker.identity().get("id"), lease.get("workerId"));
            assertEquals(((Number) fixture.worker.identity().get("pid")).longValue(), ((Number) lease.get("executorPid")).longValue());
            assertEquals(fixture.store.directory().toRealPath().resolve("workspaces/task/work").toString(), lease.get("scopePath"));
            for (String kind : List.of("taskExecution", "dispatchAction")) {
                Map<String, Object> record = fixture.store.get(kind, str(lease, kind.equals("taskExecution") ? "taskExecutionId" : "dispatchActionId"));
                assertEquals(lease.get("id"), record.get("workspaceWriteLeaseId"));
            }
            assertEquals(lease.get("id"), task.get("workspaceWriteLeaseId")); assertEquals(409, assertThrows(ApiException.class, () -> fixture.writes.require(task, fixture.worker.identity(), false)).status());
            assertTrue(fixture.store.list("operation").isEmpty()); assertEquals(0, num(map(task, "budget"), "usedCalls", -1));
        }
    }

    @Test
    void failedWriteIntentRollsBackRootClaimAndLeavesTheOriginalPendingAttempt() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> action = fixture.queue.enqueue("task", "USER_APPROVAL", value -> map(value, "contract").put("confirmed", true));
            fixture.sql("CREATE TRIGGER fail_write BEFORE INSERT ON entities WHEN new.kind='workspaceWriteLease' BEGIN SELECT RAISE(ABORT,'write intent fault'); END");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.queue.claim(str(action, "id"), 1, ignored -> {})).status());
            assertEquals("PENDING", fixture.store.get("dispatchAction", str(action, "id")).get("state")); assertEquals("QUEUED", fixture.store.get("taskExecution", str(action, "taskExecutionId")).get("state"));
            assertEquals(0, num(fixture.store.get("dispatchAction", str(action, "id")), "dispatchCount", -1)); assertFalse(fixture.store.get("task", "task").containsKey("workspaceWriteLeaseId"));
            assertTrue(fixture.store.list("workspaceWriteLease").isEmpty()); assertTrue(fixture.store.list("operation").isEmpty()); assertFalse(fixture.store.healthy());
        }
    }

    @Test
    void actualGrantAndWorkerAckCommitTogetherAndCarryTheSameRoot() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(), operation = fixture.store.list("operation").getFirst();
            assertEquals("HELD", lease.get("state")); assertEquals("ACKNOWLEDGED", operation.get("state"));
            Map<String, Object> workerLease = fixture.store.get("workerLease", str(operation, "workerLeaseId"));
            assertEquals(WorkspaceWriteLedger.identity(lease), WorkspaceWriteLedger.identity(map(workerLease, "workspaceWriteIdentity")));
            assertEquals(lease.get("id"), operation.get("workspaceWriteLeaseId")); assertEquals(lease.get("id"), workerLease.get("workspaceWriteLeaseId"));
            assertEquals(WorkspaceWriteLedger.identity(lease), fixture.writes.require(fixture.store.get("task", "task"), fixture.worker.identity(), false));
            assertEquals("HELD", mapper.readValue(Files.readAllBytes(Path.of(str(lease, "scopePath")).getParent().resolve("writer.json")), Map.class).get("state"));
            fixture.releaseAndFinish(lease); assertEquals("RELEASED", fixture.writes.current("task").get("state"));
            assertTrue(bool(fixture.store.get("taskExecution", str(lease, "taskExecutionId")), "workspaceWriteProtectionReleased", false));
        }
    }

    @Test
    void savedPhysicalWorkspaceAliasUsesTheOriginalScopeAndRejectsForeignTargets() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(); Path work = Files.createDirectory(Path.of(str(lease, "scopePath"))), alias = directory.resolve("saved workspace alias");
            alias(alias, work); fixture.store.editTask("task", task -> task.put("workspace", alias.toString()), null, "");
            assertEquals(work, alias.toRealPath()); assertEquals(WorkspaceWriteLedger.identity(lease), fixture.writes.require(fixture.store.get("task", "task"), fixture.worker.identity(), false));
            fixture.releaseAndFinish(lease); Map<String, Object> next = fixture.claim(); assertNotEquals(lease.get("id"), next.get("id")); assertEquals(work.toString(), next.get("scopePath"));
            fixture.store.editTask("task", task -> task.put("workspace", fixture.source.toString()), null, "");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.writes.require(fixture.store.get("task", "task"), fixture.worker.identity(), true)).status());
            assertFalse(bool(next, "protectionReleased", true)); assertEquals(2, fixture.store.workspaceWriteLeases("task").size());
        }
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void savedWindowsShortWorkspaceKeepsItsPhysicalScopeAfterStoreReopen() throws Exception {
        Path data; Map<String, Object> originalWorker; String saved;
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(); Path work = Files.createDirectory(Path.of(str(lease, "scopePath"))); char[] shortPath = new char[32768];
            int length = com.sun.jna.platform.win32.Kernel32.INSTANCE.GetShortPathName(work.toString(), shortPath, shortPath.length);
            assertTrue(length > 0 && length < shortPath.length); saved = new String(shortPath, 0, length);
            org.junit.jupiter.api.Assumptions.assumeFalse(saved.equalsIgnoreCase(work.toString()), "The fixture volume does not expose a distinct Windows short path");
            fixture.store.editTask("task", task -> task.put("workspace", saved), null, "");
            assertEquals(WorkspaceWriteLedger.identity(lease), fixture.writes.require(fixture.store.get("task", "task"), fixture.worker.identity(), false));
            fixture.releaseAndFinish(lease); data = fixture.data; originalWorker = fixture.worker.identity();
        }
        try (var reopened = new ExecutionStore(mapper, data.toString())) {
            assertEquals(saved, reopened.get("task", "task").get("workspace"));
            Map<String, Object> intent = new WorkspaceWriteLedger(reopened).intent(reopened.get("task", "task"), obj("id", "next-root", "taskId", "task", "dispatchActionId", "next-action"), obj("id", "next-action", "taskId", "task", "taskExecutionId", "next-root"), originalWorker);
            assertEquals(data.toRealPath().resolve("workspaces/task/work").toString(), intent.get("scopePath")); assertEquals(1, reopened.workspaceWriteLeases("task").size());
        }
    }

    @Test
    void redirectedExpectedWorkspaceParentCannotCreateAWriteIntent() throws Exception {
        try (var fixture = new Fixture()) {
            Path foreign = Files.createDirectory(directory.resolve("foreign workspace parent")); alias(fixture.data.resolve("workspaces"), foreign);
            assertEquals(409, assertThrows(ApiException.class, fixture::claim).status()); assertTrue(fixture.store.list("workspaceWriteLease").isEmpty());
            assertEquals("PENDING", fixture.store.list("dispatchAction").getFirst().get("state")); assertTrue(fixture.store.list("operation").isEmpty());
            try (var files = Files.list(foreign)) { assertTrue(files.findAny().isEmpty()); }
        }
    }

    @Test
    void grantPersistenceFailureKeepsOriginalOperationAndIntentUnconfirmed() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.claim(); String operation = str(fixture.store.beginOperation("task", "prepare", "WORKSPACE", fixture.source.toString()), "id");
            fixture.worker.dispatch(operation, fixture.source.toString(), obj()); Map<String, Object> grant = fixture.runtime.acquireWorkspaceWrite(WorkspaceWriteLedger.identity(lease));
            fixture.sql("CREATE TRIGGER fail_grant BEFORE UPDATE ON entities WHEN new.kind='workspaceWriteLease' AND json_extract(new.body,'$.state')='HELD' BEGIN SELECT RAISE(ABORT,'grant receipt fault'); END");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.worker.finish(operation, "ACKNOWLEDGED", obj("workspaceWriteGrant", grant))).status());
            assertEquals("PREPARED", fixture.writes.current("task").get("state")); assertEquals("DISPATCHED", fixture.store.get("operation", operation).get("state"));
            assertEquals("ACTIVE", fixture.store.get("workerLease", str(fixture.store.get("operation", operation), "workerLeaseId")).get("state"));
            assertFalse(fixture.store.healthy()); assertTrue(Files.exists(Path.of(str(grant, "manifestPath"))));
        }
    }

    @Test
    void foreignOrInvalidGrantCannotBecomeWriteAuthority() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.claim(); String operationId = str(fixture.store.beginOperation("task", "prepare", "WORKSPACE", fixture.source.toString()), "id");
            fixture.worker.dispatch(operationId, fixture.source.toString(), obj()); Map<String, Object> grant = fixture.runtime.acquireWorkspaceWrite(WorkspaceWriteLedger.identity(lease));
            for (String field : List.of("executorPid", "lockValid", "lockPath", "fileKey")) {
                Map<String, Object> changed = new LinkedHashMap<>(grant);
                changed.put(field, switch (field) { case "executorPid" -> ProcessHandle.current().pid() + 1; case "lockValid" -> false; case "lockPath" -> fixture.source.resolve("foreign.lock").toString(); default -> ""; });
                assertEquals(403, assertThrows(ApiException.class, () -> fixture.writes.granted(fixture.store.get("operation", operationId), obj("workspaceWriteGrant", changed))).status());
            }
            assertEquals("PREPARED", fixture.writes.current("task").get("state")); assertTrue(fixture.store.healthy());
        }
    }

    @Test
    void finishingWithoutNativeReleasePreservesUnknownAndBlocksAnotherAttempt() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(); fixture.store.editTask("task", task -> { task.put("state", "PAUSED"); task.put("error", "original observation"); }, null, "");
            fixture.queue.finish(str(lease, "taskExecutionId"));
            assertEquals("UNKNOWN", fixture.store.get("taskExecution", str(lease, "taskExecutionId")).get("state")); assertEquals("UNKNOWN", fixture.writes.current("task").get("state"));
            assertTrue(str(fixture.store.get("task", "task"), "error").contains("original observation"));
            fixture.store.editTask("task", task -> task.put("state", "PAUSED"), null, "");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.queue.enqueue("task", "USER_RESUME", ignored -> {})).status());
            assertFalse(bool(fixture.writes.current("task"), "protectionReleased", true));
        }
    }

    @Test
    void checkedUnknownRemainsUnknownAndNewAttemptUsesANewWriteId() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> old = fixture.grant(); fixture.writes.unknown(str(old, "id"), "lost original receipt");
            fixture.store.editTask("task", task -> { task.put("state", "UNKNOWN"); map(task, "budget").put("usedCalls", 3); }, null, ""); fixture.queue.finish(str(old, "taskExecutionId"));
            Map<String, Object> release = fixture.runtime.releaseWorkspaceWrite(WorkspaceWriteLedger.identity(old), "process and effect checked");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.writes.released(str(old, "id"), release, "")).status());
            fixture.writes.released(str(old, "id"), release, "process and effect checked");
            fixture.queue.reconcile("task", obj("note", "process and effect checked"), task -> task.put("state", "PAUSED"));
            Map<String, Object> next = fixture.claim(); assertNotEquals(old.get("id"), next.get("id")); assertNotEquals(old.get("taskExecutionId"), next.get("taskExecutionId"));
            Map<String, Object> history = fixture.store.get("workspaceWriteLease", str(old, "id")); assertEquals("UNKNOWN", history.get("state")); assertTrue(bool(history, "protectionReleased", false));
            assertEquals("lost original receipt", history.get("reason")); assertEquals("UNKNOWN", fixture.store.get("taskExecution", str(old, "taskExecutionId")).get("state"));
            assertEquals(3, num(map(fixture.store.get("task", "task"), "budget"), "usedCalls", -1)); assertEquals(2, fixture.store.taskDispatchRecords("workspaceWriteLease", "task").size());
        }
    }

    @Test
    void foreignPreservedReceiptCannotReleaseAPreviouslyGrantedOwner() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(); fixture.runtime.closeWorkspaceWrites();
            Path manifest = Path.of(str(lease, "scopePath")).getParent().resolve("writer.json"); Map<String, Object> nativeRecord = mapper.readValue(Files.readAllBytes(manifest), new com.fasterxml.jackson.core.type.TypeReference<>() {});
            Map<String, Object> foreign = new LinkedHashMap<>(map(nativeRecord, "identity")); foreign.put("id", "foreign-write"); nativeRecord.put("identity", foreign); Files.write(manifest, mapper.writeValueAsBytes(nativeRecord));
            Map<String, Object> receipt = fixture.runtime.releaseWorkspaceWrite(WorkspaceWriteLedger.identity(lease), "checked");
            assertTrue(bool(receipt, "nativeOwnerAbsent", false)); assertEquals(409, assertThrows(ApiException.class, () -> fixture.writes.released(str(lease, "id"), receipt, "checked")).status());
            assertFalse(bool(fixture.writes.current("task"), "protectionReleased", true)); assertEquals(nativeRecord, mapper.readValue(Files.readAllBytes(manifest), new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {}));
        }
    }

    @Test
    void writeScopeAndRootUniquenessAreEnforcedBySQLite() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.claim(), duplicate = new LinkedHashMap<>(lease); duplicate.put("id", "duplicate-root");
            assertEquals(503, assertThrows(ApiException.class, () -> fixture.store.put("workspaceWriteLease", duplicate)).status());
            assertEquals(1, fixture.store.list("workspaceWriteLease").size()); assertEquals(lease.get("id"), fixture.store.list("workspaceWriteLease").getFirst().get("id"));
        }
        try (var store = new ExecutionStore(mapper, directory.resolve("scope-unique").toString())) {
            store.put("workspaceWriteLease", obj("id", "first", "taskExecutionId", "root-a", "scopeKey", "one-physical-scope", "protectionReleased", false));
            assertEquals(503, assertThrows(ApiException.class, () -> store.put("workspaceWriteLease", obj("id", "second", "taskExecutionId", "root-b", "scopeKey", "one-physical-scope", "protectionReleased", false))).status());
            assertEquals(1, store.list("workspaceWriteLease").size());
        }
    }

    @Test
    void workerLossBetweenOperationsKeepsTheEntireRootProtected() throws Exception {
        try (var fixture = new Fixture()) {
            Map<String, Object> lease = fixture.grant(); assertTrue(fixture.store.workerLeases("", "task", "ACTIVE").isEmpty());
            new WorkerLedger(fixture.store, fixture.nodes).stop(fixture.worker.identity(), Instant.now(), "lost between operations");
            assertEquals("UNKNOWN", fixture.writes.current("task").get("state")); assertEquals("UNKNOWN", fixture.store.get("task", "task").get("state"));
            assertEquals(lease.get("id"), fixture.writes.current("task").get("id")); assertFalse(bool(fixture.writes.current("task"), "protectionReleased", true));
        }
    }

    @Test
    void writeHistoryUsesStableCursorsAndKeepsOtherTasksOut() throws Exception {
        try (var fixture = new Fixture()) {
            List<String> ids = new ArrayList<>();
            for (int round = 0; round < 3; round++) { Map<String, Object> lease = fixture.grant(); ids.add(str(lease, "id")); fixture.releaseAndFinish(lease); }
            Map<String, Object> first = fixture.store.workspaceWriteLeasePage("task", 0, 1, "RELEASED"); assertTrue(bool(first, "hasMore", false)); assertEquals(ids.getLast(), objects(first.get("leases")).getFirst().get("id"));
            Map<String, Object> second = fixture.store.workspaceWriteLeasePage("task", ((Number) first.get("cursor")).longValue(), 30, "RELEASED");
            assertEquals(List.of(ids.get(1), ids.getFirst()), objects(second.get("leases")).stream().map(lease -> str(lease, "id")).toList()); assertFalse(bool(second, "hasMore", true));
            fixture.store.createTask(obj("id", "other", "projectId", "other-project")); assertTrue(objects(fixture.store.workspaceWriteLeasePage("other", 0, 30, "").get("leases")).isEmpty());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.store.workspaceWriteLeasePage("task", -1, 30, "")).status()); assertEquals(400, assertThrows(ApiException.class, () -> fixture.store.workspaceWriteLeasePage("task", 0, 30, "invented")).status());
        }
    }

    @Test
    void schemaSevenUpgradeAddsIndexesWithoutInventingOldRootWrites() throws Exception {
        Path data = directory.resolve("migration");
        try (var old = new ExecutionStore(mapper, data.toString())) {
            old.put("task", obj("id", "legacy", "budget", obj("usedCalls", 5), "workspace", "original workspace"));
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var statement = db.createStatement()) {
                statement.execute("DROP INDEX entities_workspace_write_root"); statement.execute("DROP INDEX entities_workspace_write_scope"); statement.execute("DROP INDEX entities_workspace_write_task"); statement.execute("PRAGMA user_version=7");
            }
        }
        try (var migrated = new ExecutionStore(mapper, data.toString()); var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var statement = db.createStatement(); var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next()); assertEquals(8, version.getInt(1)); assertTrue(migrated.list("workspaceWriteLease").isEmpty());
            assertEquals(5, num(map(migrated.get("task", "legacy"), "budget"), "usedCalls", -1)); assertEquals("original workspace", migrated.get("task", "legacy").get("workspace"));
        }
    }

    private static void alias(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process process = new ProcessBuilder("node", "-e", "require('node:fs').symlinkSync(process.argv[1],process.argv[2],'junction')", target.toString(), link.toString()).redirectErrorStream(true).start();
            String birth = process.info().startInstant().map(Instant::toString).orElse("");
            try { assertTrue(process.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)); assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)); }
            finally { if (process.isAlive()) { assertEquals(birth, process.info().startInstant().orElseThrow().toString()); process.destroyForcibly(); assertTrue(process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)); } }
        } else Files.createSymbolicLink(link, target);
    }
}
