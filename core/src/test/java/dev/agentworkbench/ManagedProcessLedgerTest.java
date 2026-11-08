package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ManagedProcessLedgerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private static final class ProcessFixture implements ManagedProcessLedger.ProcessAccess {
        final Map<Long, ManagedProcessLedger.ProcessSnapshot> facts = new HashMap<>();
        final List<Long> stopped = new ArrayList<>();
        boolean unavailable;
        @Override public ManagedProcessLedger.ProcessSnapshot inspect(long pid) {
            if (unavailable) throw new IllegalStateException("process observation unavailable");
            return facts.getOrDefault(pid, new ManagedProcessLedger.ProcessSnapshot(false, ""));
        }
        @Override public void stop(long pid, String birth) { stopped.add(pid); facts.put(pid, new ManagedProcessLedger.ProcessSnapshot(false, "")); }
    }

    private ExecutionStore store(Path data) throws Exception { return new ExecutionStore(mapper, data.toString()); }
    private Map<String, Object> confirmation() { return obj("processesStopped", true, "coverageChecked", true, "note", "Original processes and the unrecorded coverage were checked separately"); }
    private void historical(ExecutionStore store, String id, long pid, String birth) {
        store.put("processSession", obj("id", id, "type", "WORKER", "ownerCorePid", 9001L, "ownerCoreStartedAt", "previous-core", "ownerCoreSessionId", "previous-owner",
            "state", "RUNNING", "launchPhase", "PID_RECORDED", "launchReceipt", "RECORDED", "pid", pid, "processStartedAt", birth, "coverage", "OBSERVED_ONLY", "taskId", "task"));
        store.put("processObservation", obj("id", id + "-root", "sessionId", id, "pid", pid, "processStartedAt", birth, "origin", "ACTUAL_START", "role", "ROOT"));
    }

    @Test
    void descendantSnapshotRequiresTheOriginalParentBeforeAndAfterEnumeration() {
        ProcessHandle parent = mock(ProcessHandle.class), child = mock(ProcessHandle.class);
        ProcessHandle.Info original = mock(ProcessHandle.Info.class), replacement = mock(ProcessHandle.Info.class);
        String birth = "2026-11-08T01:02:03.004Z";
        when(original.startInstant()).thenReturn(java.util.Optional.of(java.time.Instant.parse(birth)));
        when(replacement.startInstant()).thenReturn(java.util.Optional.of(java.time.Instant.parse(birth).plusSeconds(1)));
        when(parent.isAlive()).thenReturn(true); when(parent.info()).thenReturn(replacement);
        assertTrue(ManagedProcessLedger.originalDescendants(parent, birth).isEmpty()); verify(parent, never()).descendants();
        assertTrue(ManagedProcessLedger.originalDescendants(parent, "").isEmpty()); verify(parent, never()).descendants();
        reset(parent); when(parent.isAlive()).thenReturn(true); when(parent.info()).thenReturn(original, replacement);
        when(parent.descendants()).thenReturn(java.util.stream.Stream.of(child));
        assertTrue(ManagedProcessLedger.originalDescendants(parent, birth).isEmpty(), "A replacement parent must not authorize the newly enumerated subtree");
        reset(parent); when(parent.isAlive()).thenReturn(true); when(parent.info()).thenReturn(original);
        when(parent.descendants()).thenReturn(java.util.stream.Stream.of(child));
        assertEquals(List.of(child), ManagedProcessLedger.originalDescendants(parent, birth));
        reset(parent); when(parent.isAlive()).thenReturn(false);
        assertTrue(ManagedProcessLedger.originalDescendants(parent, birth).isEmpty()); verify(parent, never()).descendants();
    }

    @Test
    void recordedLiveProcessCannotBeOverriddenByAnAcknowledgement() throws Exception {
        ProcessFixture actual = new ProcessFixture(); actual.facts.put(41L, new ManagedProcessLedger.ProcessSnapshot(true, "original"));
        Path data = directory.resolve("live");
        try (var initial = store(data)) { historical(initial, "original-session", 41, "original"); }
        try (var reopened = store(data)) {
            var ledger = new ManagedProcessLedger(reopened, 100, "new-core", actual);
            assertEquals(409, assertThrows(ApiException.class, ledger::requireDispatch).status());
            assertEquals(409, assertThrows(ApiException.class, () -> ledger.reconcile("original-session", confirmation())).status());
            assertEquals(409, assertThrows(ApiException.class, () -> ledger.requireTaskStopped("task")).status());
            assertEquals("RUNNING", reopened.get("processSession", "original-session").get("state"));
            ledger.stop("original-session"); assertEquals(List.of(41L), actual.stopped);
            assertThrows(ApiException.class, ledger::requireDispatch, "stopping the recorded process does not prove complete historical coverage");
            ledger.reconcile("original-session", confirmation()); ledger.requireDispatch();
            assertEquals("OBSERVED_ONLY", reopened.get("processSession", "original-session").get("coverage"));
            assertEquals("original", reopened.processObservations("original-session").getFirst().get("processStartedAt"));
        }
    }

    @Test
    void reusedPidPreservesTheNewProcessAndTheOriginalBirthRecord() throws Exception {
        ProcessFixture actual = new ProcessFixture(); actual.facts.put(42L, new ManagedProcessLedger.ProcessSnapshot(true, "new-birth"));
        try (var store = store(directory.resolve("reuse"))) {
            historical(store, "reused-session", 42, "old-birth"); var ledger = new ManagedProcessLedger(store, 100, "core", actual);
            Map<String, Object> view = ledger.stop("reused-session"); assertEquals(0, actual.stopped.size());
            assertEquals("PID_REUSED", objects(view.get("recordedProcesses")).getFirst().get("observation"));
            assertEquals("old-birth", objects(view.get("recordedProcesses")).getFirst().get("processStartedAt"));
            assertEquals("new-birth", objects(view.get("recordedProcesses")).getFirst().get("currentProcessStartedAt"));
            ledger.reconcile("reused-session", confirmation()); assertTrue(actual.facts.get(42L).alive());
        }
    }

    @Test
    void missingBirthAndFailedObservationCannotBeClaimedAsStopped() throws Exception {
        ProcessFixture actual = new ProcessFixture(); actual.facts.put(43L, new ManagedProcessLedger.ProcessSnapshot(true, "known-current-birth"));
        try (var store = store(directory.resolve("unknown-birth"))) {
            historical(store, "unknown-session", 43, ""); var ledger = new ManagedProcessLedger(store, 100, "core", actual);
            assertEquals("UNVERIFIABLE", objects(ledger.detail("unknown-session").get("processes")).getFirst().get("observation"));
            ledger.stop("unknown-session"); assertTrue(actual.stopped.isEmpty());
            assertThrows(ApiException.class, () -> ledger.reconcile("unknown-session", confirmation()));
            actual.unavailable = true;
            assertEquals("OBSERVATION_FAILED", objects(ledger.detail("unknown-session").get("processes")).getFirst().get("observation"));
            assertThrows(ApiException.class, () -> ledger.reconcile("unknown-session", confirmation()));
            actual.unavailable = false; actual.facts.put(43L, new ManagedProcessLedger.ProcessSnapshot(false, ""));
            ledger.reconcile("unknown-session", confirmation()); ledger.requireDispatch();
        }
    }

    @Test
    void missingCurrentBirthIsAlsoUnverifiableAndNeverSignalled() throws Exception {
        ProcessFixture actual = new ProcessFixture(); actual.facts.put(44L, new ManagedProcessLedger.ProcessSnapshot(true, ""));
        try (var store = store(directory.resolve("missing-current-birth"))) {
            historical(store, "missing-current", 44, "original-birth"); var ledger = new ManagedProcessLedger(store, 100, "core", actual);
            ledger.stop("missing-current"); assertTrue(actual.stopped.isEmpty());
            assertThrows(ApiException.class, () -> ledger.reconcile("missing-current", confirmation()));
            assertEquals("UNVERIFIABLE", objects(ledger.detail("missing-current").get("processes")).getFirst().get("observation"));
        }
    }

    @Test
    void displayLimitsDoNotHideARecordedLiveDescendantFromTheGate() throws Exception {
        ProcessFixture actual = new ProcessFixture(); actual.facts.put(55L, new ManagedProcessLedger.ProcessSnapshot(true, "child-birth"));
        try (var store = store(directory.resolve("display-limit"))) {
            historical(store, "many-processes", 50, "root-birth");
            store.put("processObservation", obj("id", "oldest-child", "sessionId", "many-processes", "pid", 55L, "processStartedAt", "child-birth", "role", "DESCENDANT"));
            for (long pid = 1000; pid < 1060; pid++) store.put("processObservation", obj("id", "exited-" + pid, "sessionId", "many-processes", "pid", pid, "processStartedAt", "old-" + pid, "role", "DESCENDANT"));
            var ledger = new ManagedProcessLedger(store, 100, "core", actual); Map<String, Object> view = ledger.detail("many-processes");
            assertEquals(62, view.get("observedProcessCount")); assertEquals(50, objects(view.get("recordedProcesses")).size());
            assertEquals(62, ledger.taskObservations("task").size(), "Export must preserve original observations beyond the display limit");
            assertEquals(1L, view.get("unconfirmedProcessCount")); assertTrue(bool(view, "processesTruncated", false));
            assertThrows(ApiException.class, () -> ledger.reconcile("many-processes", confirmation()));
            ledger.stop("many-processes"); assertEquals(List.of(55L), actual.stopped);
        }
    }

    @Test
    void stoppingLongHistoryBoundsSystemSnapshotsWithoutSignallingUnknownOrReusedProcesses() throws Exception {
        class HistoryAccess implements ManagedProcessLedger.ProcessAccess {
            final Map<Long, ManagedProcessLedger.ProcessSnapshot> facts = new HashMap<>();
            final List<Long> stopped = new ArrayList<>();
            boolean inspecting, snapshotLoaded;
            int systemSnapshots;
            @Override public ManagedProcessLedger.Inspection inspection() {
                if (inspecting) return () -> {};
                inspecting = true; snapshotLoaded = false;
                return () -> { inspecting = false; snapshotLoaded = false; };
            }
            @Override public ManagedProcessLedger.ProcessSnapshot inspect(long pid) {
                var fact = facts.getOrDefault(pid, new ManagedProcessLedger.ProcessSnapshot(false, ""));
                if (!fact.alive() && (!inspecting || !snapshotLoaded)) { systemSnapshots++; snapshotLoaded = true; }
                return fact;
            }
            @Override public void stop(long pid, String birth) { stopped.add(pid); facts.put(pid, new ManagedProcessLedger.ProcessSnapshot(false, "")); }
        }
        HistoryAccess actual = new HistoryAccess();
        actual.facts.put(55L, new ManagedProcessLedger.ProcessSnapshot(true, "original-child"));
        actual.facts.put(56L, new ManagedProcessLedger.ProcessSnapshot(true, "replacement-child"));
        actual.facts.put(57L, new ManagedProcessLedger.ProcessSnapshot(true, ""));
        try (var store = store(directory.resolve("stop-history"))) {
            historical(store, "stop-history", 50, "root-birth");
            for (long pid = 1000; pid < 1060; pid++) store.put("processObservation", obj("id", "exited-" + pid, "sessionId", "stop-history", "pid", pid, "processStartedAt", "old-" + pid, "role", "DESCENDANT"));
            for (long pid = 55; pid <= 57; pid++) store.put("processObservation", obj("id", "child-" + pid, "sessionId", "stop-history", "pid", pid,
                "processStartedAt", pid == 55 ? "original-child" : "old-child", "role", "DESCENDANT"));
            var ledger = new ManagedProcessLedger(store, 100, "core", actual);
            Map<String, Object> view = ledger.stop("stop-history");
            assertTrue(actual.systemSnapshots <= 4, "Stopping 64 recorded identities must not enumerate the system separately for every exited PID; observed " + actual.systemSnapshots);
            assertEquals(List.of(55L), actual.stopped); assertEquals(64, view.get("observedProcessCount"));
            assertEquals(1L, view.get("unconfirmedProcessCount")); assertFalse(bool(view, "observedProcessesStopped", true));
            assertEquals("UNVERIFIABLE", objects(view.get("processes")).getFirst().get("observation"));
            assertTrue(actual.facts.get(56L).alive()); assertTrue(actual.facts.get(57L).alive());
            assertEquals(409, assertThrows(ApiException.class, () -> ledger.reconcile("stop-history", confirmation())).status());
        }
    }

    @Test
    void unattemptedLaunchAndMissingPidReceiptHaveDifferentRecoveryBoundaries() throws Exception {
        Path data = directory.resolve("launch-gaps"); String unattempted, attempted;
        try (var initial = store(data)) {
            var ledger = new ManagedProcessLedger(initial);
            unattempted = ledger.begin("WORKER", obj()).id();
            var pending = ledger.begin("WORKER", obj()); attempted = pending.id(); pending.starting();
        }
        try (var reopened = store(data)) {
            var ledger = new ManagedProcessLedger(reopened); ledger.recoverWorkers();
            assertEquals("NOT_STARTED", reopened.get("processSession", unattempted).get("launchReceipt"));
            assertEquals("CLOSED", reopened.get("processSession", unattempted).get("state"));
            assertEquals("MISSING", reopened.get("processSession", attempted).get("launchReceipt"));
            assertEquals(1, ledger.snapshot().get("pendingCount")); assertThrows(ApiException.class, ledger::requireDispatch);
            assertEquals(400, assertThrows(ApiException.class, () -> ledger.reconcile(attempted, obj("processesStopped", true, "note", "No PID was returned"))).status());
            ledger.reconcile(attempted, confirmation()); ledger.requireDispatch();
        }
    }

    @Test
    void lateObservationReopensTheGateAndPreservesPreviousReconciliations() throws Exception {
        ProcessFixture actual = new ProcessFixture();
        try (var store = store(directory.resolve("late-observation"))) {
            var ledger = new ManagedProcessLedger(store, 100, "core", actual); var tracking = ledger.begin("WORKER", obj());
            assertEquals(409, assertThrows(ApiException.class, () -> ledger.stop(tracking.id())).status());
            tracking.starting(); tracking.launched(ProcessHandle.current()); tracking.finished(true);
            ledger.reconcile(tracking.id(), confirmation()); ledger.reconcile(tracking.id(), confirmation());
            assertEquals(1, objects(store.get("processSession", tracking.id()).get("reconciliations")).size());
            assertEquals(409, assertThrows(ApiException.class, () -> ledger.reconcile(tracking.id(), obj("processesStopped", true, "coverageChecked", true, "note", "Replacement assertion"))).status());
            actual.facts.put(55L, new ManagedProcessLedger.ProcessSnapshot(true, "late-child"));
            tracking.observed(obj("pid", 55L, "processStartedAt", "late-child", "observedFromPid", ProcessHandle.current().pid(),
                "observedFromStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString()), "WORKER_PIPE");
            assertEquals("UNKNOWN", str(store.get("processSession", tracking.id()), "state")); assertThrows(ApiException.class, ledger::requireDispatch);
            assertEquals(1, objects(store.get("processSession", tracking.id()).get("reconciliations")).size());
            ledger.stop(tracking.id()); assertEquals(List.of(55L), actual.stopped);
            Map<String, Object> next = obj("processesStopped", true, "coverageChecked", true, "note", "Late child and coverage checked again");
            ledger.reconcile(tracking.id(), next); ledger.requireDispatch();
            tracking.finished(false); ledger.stop(tracking.id()); ledger.requireDispatch();
            assertEquals("RECONCILED", str(store.get("processSession", tracking.id()), "state"), "Late cleanup and repeated stop must preserve a completed coverage receipt");
            var history = objects(store.get("processSession", tracking.id()).get("reconciliations")); assertEquals(2, history.size());
            assertEquals(confirmation().get("note"), history.getFirst().get("note")); assertEquals(next.get("note"), history.getLast().get("note"));
        }
    }

    @Test
    void realProcessRemainsBlockedAcrossStoreReopenAndForeignObservationIsRejected() throws Exception {
        Path data = directory.resolve("real-reopen"), script = directory.resolve("live.mjs");
        Files.writeString(script, "setInterval(()=>{},1000);\n");
        Process original = new ProcessBuilder("node", script.toString()).start(); String sessionId, birth = original.info().startInstant().orElseThrow().toString();
        try {
            try (var initial = store(data)) {
                var ledger = new ManagedProcessLedger(initial); var tracking = ledger.begin("WORKER", obj()); sessionId = tracking.id(); tracking.starting(); tracking.launched(original.toHandle());
                assertEquals(403, assertThrows(ApiException.class, () -> tracking.observed(obj("pid", original.pid() + 1, "processStartedAt", birth,
                    "observedFromPid", 99999L, "observedFromStartedAt", "foreign"), "WORKER_PIPE")).status());
                assertEquals(1, initial.processObservations(sessionId).size());
            }
            try (var reopened = store(data)) {
                var ledger = new ManagedProcessLedger(reopened);
                assertThrows(ApiException.class, ledger::requireDispatch);
                assertThrows(ApiException.class, () -> ledger.reconcile(sessionId, confirmation())); assertTrue(original.isAlive());
                ledger.stop(sessionId); assertTrue(original.waitFor(5, TimeUnit.SECONDS)); assertFalse(original.isAlive());
                ledger.reconcile(sessionId, confirmation()); ledger.requireDispatch();
                List<Map<String, Object>> recorded = reopened.processObservations(sessionId);
                Map<String, Object> root = recorded.stream().filter(item -> "ROOT".equals(str(item, "role")) && ((Number) item.get("pid")).longValue() == original.pid()).findFirst().orElseThrow();
                assertEquals(birth, root.get("processStartedAt"));
            }
        } finally { if (original.isAlive()) original.destroyForcibly(); assertTrue(original.waitFor(5, TimeUnit.SECONDS)); }
    }

    @Test
    void currentCoreCannotBeKilledOrAcknowledgedAsAnOldStoppedProcess() throws Exception {
        try (var store = store(directory.resolve("current-core"))) {
            String birth = ProcessHandle.current().info().startInstant().orElseThrow().toString();
            historical(store, "legacy-core", ProcessHandle.current().pid(), birth); var ledger = new ManagedProcessLedger(store);
            assertEquals("CURRENT_CORE", objects(ledger.stop("legacy-core").get("processes")).getFirst().get("observation"));
            assertThrows(ApiException.class, () -> ledger.reconcile("legacy-core", confirmation())); assertTrue(ProcessHandle.current().isAlive());
        }
    }

    @Test
    void intentPersistenceFailurePreventsStartingAnActualWorker() throws Exception {
        Path data = directory.resolve("intent-failure");
        try (var store = store(data)) {
            var runtime = new ProcessWorkspaceRuntime(mapper, data.toString()); var catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.nodes().localNode();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = db.createStatement()) {
                sql.execute("CREATE TRIGGER reject_process_intent BEFORE INSERT ON entities WHEN new.kind='processSession' BEGIN SELECT RAISE(ABORT,'injected process intent failure'); END");
            }
            List<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).sorted().toList();
            assertThrows(RuntimeException.class, () -> runtime.startWorker(store, catalog.nodes())); assertFalse(store.healthy());
            assertEquals(before, ProcessHandle.current().descendants().map(ProcessHandle::pid).sorted().toList());
            assertTrue(store.list("worker").isEmpty()); assertTrue(store.list("processObservation").isEmpty()); runtime.closeProcess();
        }
    }

    @Test
    void pidReceiptFailureKeepsTheOriginalStartIntentAndStopsTheLaunchedWorker() throws Exception {
        Path data = directory.resolve("pid-failure"); String originalIntent;
        try (var store = store(data)) {
            var runtime = new ProcessWorkspaceRuntime(mapper, data.toString()); var catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.nodes().localNode();
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = db.createStatement()) {
                sql.execute("CREATE TRIGGER reject_process_pid BEFORE INSERT ON entities WHEN new.kind='processObservation' BEGIN SELECT RAISE(ABORT,'injected PID receipt failure'); END");
            }
            List<Long> before = ProcessHandle.current().descendants().map(ProcessHandle::pid).sorted().toList();
            assertThrows(RuntimeException.class, () -> runtime.startWorker(store, catalog.nodes())); assertFalse(store.healthy());
            assertEquals(before, ProcessHandle.current().descendants().map(ProcessHandle::pid).sorted().toList());
            assertEquals(1, store.list("processSession").size()); Map<String, Object> intent = store.list("processSession").getFirst(); originalIntent = str(intent, "id");
            assertEquals("START_REQUESTED", intent.get("launchPhase")); assertEquals("MISSING", intent.get("launchReceipt")); assertFalse(intent.containsKey("pid"));
            assertTrue(store.list("worker").isEmpty()); assertTrue(store.list("modelRequest").isEmpty());
        }
        try (var reopened = store(data)) {
            var ledger = new ManagedProcessLedger(reopened); ledger.recoverWorkers();
            assertThrows(ApiException.class, ledger::requireDispatch); assertEquals(originalIntent, str(objects(ledger.snapshot().get("sessions")).getFirst(), "id"));
        }
    }

    @Test
    void schemaFourMigratesProcessIndexesWithoutChangingOriginalEffects() throws Exception {
        Path data = directory.resolve("migration");
        try (var initial = store(data)) { initial.put("operation", obj("id", "original", "state", "UNKNOWN", "dispatchCount", 1, "taskId", "task")); }
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = db.createStatement()) {
            sql.execute("DROP INDEX entities_process_session_owner"); sql.execute("DROP INDEX entities_process_observation_session"); sql.execute("PRAGMA user_version=4");
        }
        try (var reopened = store(data)) {
            assertEquals("UNKNOWN", reopened.get("operation", "original").get("state")); assertEquals(1, reopened.get("operation", "original").get("dispatchCount"));
            try (var db = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var sql = db.createStatement()) {
                try (var version = sql.executeQuery("PRAGMA user_version")) { assertTrue(version.next()); assertEquals(5, version.getInt(1)); }
                try (var index = sql.executeQuery("SELECT COUNT(*) FROM sqlite_master WHERE name IN ('entities_process_session_owner','entities_process_observation_session')")) { assertTrue(index.next()); assertEquals(2, index.getInt(1)); }
            }
        }
    }

    @Test
    void realLegacyProcessBlocksTaskCheckboxUntilProcessAndCoverageAreChecked() throws Exception {
        Path data = directory.resolve("legacy-task"), script = directory.resolve("legacy-live.mjs"); Files.writeString(script, "setInterval(()=>{},1000);\n");
        Process original = new ProcessBuilder("node", script.toString()).start();
        WorkbenchEngine engine = null; ExecutionStore reopened = null;
        try {
            String birth = original.info().startInstant().orElseThrow().toString();
            try (var initial = store(data)) {
                var runtime = new dev.agentworkbench.runtime.WorkspaceRuntime(mapper, data.toString()); var nodes = new ExecutionNodes(initial, runtime); var node = nodes.localNode();
                initial.put("worker", obj("id", "original-worker", "executionNodeId", node.get("id"), "epoch", 1, "state", "RUNNING", "deployment", "LOCAL_PROCESS",
                    "pid", original.pid(), "processStartedAt", birth, "protocolVersion", 1, "expiresAt", java.time.Instant.now().plusSeconds(30).toString()));
                initial.createTask(obj("id", "old-task", "projectId", "fixture-project", "state", "UNKNOWN", "workspace", directory.toString(), "budget", obj("usedCalls", 3), "plan", List.of(), "evidence", new ArrayList<>()));
                initial.put("operation", obj("id", "old-operation", "taskId", "old-task", "state", "UNKNOWN", "workerLeaseId", "old-lease"));
                initial.put("workerLease", obj("id", "old-lease", "taskId", "old-task", "workerId", "original-worker", "executionNodeId", node.get("id"),
                    "operationKind", "operation", "operationId", "old-operation", "state", "UNKNOWN", "stopState", "UNKNOWN"));
            }
            reopened = store(data); var runtime = new ProcessWorkspaceRuntime(mapper, data.toString()); var catalog = new WorkbenchCatalog(reopened, runtime, new PlanValidator());
            engine = new WorkbenchEngine(reopened, catalog, runtime, new ResourceManager(), mapper); engine.recover();
            WorkbenchEngine active = engine;
            Map<String, Object> taskConfirmation = obj("processesStopped", true, "sideEffectsChecked", true, "note", "This declaration must not bypass the original live process");
            assertEquals(409, assertThrows(ApiException.class, () -> active.action("old-task", "reconcile", taskConfirmation)).status()); assertTrue(original.isAlive());
            assertEquals("UNKNOWN", reopened.get("task", "old-task").get("state")); assertEquals("UNKNOWN", reopened.get("workerLease", "old-lease").get("state"));
            String sessionId = "legacy-worker-original-worker"; active.processRecoveryAction(sessionId, "stop", obj());
            assertTrue(original.waitFor(5, TimeUnit.SECONDS)); assertFalse(original.isAlive());
            assertThrows(ApiException.class, () -> active.action("old-task", "reconcile", taskConfirmation));
            active.processRecoveryAction(sessionId, "reconcile", confirmation());
            assertEquals("UNKNOWN", reopened.get("operation", "old-operation").get("state")); assertEquals(3, map(reopened.get("task", "old-task"), "budget").get("usedCalls"));
            assertEquals("PAUSED", active.action("old-task", "reconcile", taskConfirmation).get("state"));
            assertEquals("RECONCILED", reopened.get("workerLease", "old-lease").get("state")); assertEquals("LEGACY_NO_LIST", reopened.get("processSession", sessionId).get("coverage"));
            assertTrue(reopened.list("modelRequest").isEmpty());
        } finally {
            if (engine != null) engine.shutdown(); if (reopened != null) reopened.close();
            if (original.isAlive()) original.destroyForcibly(); assertTrue(original.waitFor(5, TimeUnit.SECONDS));
        }
    }
}
