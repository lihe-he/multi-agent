package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 SQLite 同事务验证终端与根队列，夹具不启动 shell 或绕过 Worker 原生验收。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class TerminalQueueTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path data;
    private ExecutionStore store;
    private TaskDispatchQueue queue;
    private TerminalLedger ledger;
    private String taskId;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), data.toString()); queue = new TaskDispatchQueue(store); ledger = new TerminalLedger(store);
        taskId = task();
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private String task() throws Exception {
        String identifier = id(); Path work = Files.createDirectories(data.resolve("workspaces").resolve(identifier).resolve("work")).toRealPath();
        store.put("task", obj("id", identifier, "projectId", "terminal-queue-fixture", "sessionId", "session", "state", "PAUSED", "outcome", "UNDETERMINED",
            "workspace", work.toString(), "workspaceOwnership", obj("owner", identifier, "workspace", work.toString()),
            "execution", obj("version", 1, "executionNodeId", "local", "bindingVersion", 1), "contract", obj("confirmed", true, "version", 1),
            "budget", obj("usedCalls", 7, "maxCalls", 20), "policyVersion", "terminal-queue-fixture"));
        return identifier;
    }

    private Map<String, Object> terminal(String identifier) { return ledger.newSession(store.get("task", identifier), "SYSTEM_CMD", 80, 24); }

    private Map<String, Object> enqueue(String identifier, Map<String, Object> terminal) {
        return queue.enqueue(identifier, "USER_TERMINAL", task -> task.put("pendingTerminalId", terminal.get("id")), Map.of("terminalSession", List.of(terminal)));
    }

    private void sql(String statement) throws Exception {
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var command = database.createStatement()) { command.execute(statement); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "taskExecution", "dispatchAction", "terminalSession", "event"})
    void anyStartTransactionFailureRetainsIdleTaskWithoutTerminalOrRoot(String failedWrite) throws Exception {
        Map<String, Object> original = store.get("task", taskId), terminal = terminal(taskId);
        if ("event".equals(failedWrite)) sql("CREATE TRIGGER fail_terminal_queue BEFORE INSERT ON events WHEN new.type='QUEUED' BEGIN SELECT RAISE(ABORT,'terminal event fault'); END");
        else sql("CREATE TRIGGER fail_terminal_queue BEFORE INSERT ON entities WHEN new.kind='" + failedWrite + "' BEGIN SELECT RAISE(ABORT,'terminal record fault'); END");
        assertEquals(503, assertThrows(ApiException.class, () -> enqueue(taskId, terminal)).status());
        assertFalse(store.healthy()); assertEquals(original, store.get("task", taskId));
        assertTrue(store.list("terminalSession").isEmpty()); assertTrue(store.list("taskExecution").isEmpty()); assertTrue(store.list("dispatchAction").isEmpty());
        assertTrue(objects(store.events(taskId, 0, 100).get("events")).isEmpty());
        store.close(); store = new ExecutionStore(new ObjectMapper(), data.toString());
        assertEquals(original, store.get("task", taskId)); assertTrue(store.list("terminalSession").isEmpty());
    }

    @Test
    void concurrentStartsReserveOneOriginalTerminalAndNoModelBudget() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var pending = java.util.stream.IntStream.range(0, 8).mapToObj(index -> executor.submit(() -> {
                Map<String, Object> terminal = terminal(taskId); start.await();
                try { enqueue(taskId, terminal); return true; }
                catch (ApiException exception) { assertEquals(409, exception.status()); return false; }
            })).toList();
            start.countDown(); int started = 0; for (var future : pending) if (future.get(10, TimeUnit.SECONDS)) started++;
            assertEquals(1, started);
        }
        Map<String, Object> task = store.get("task", taskId), terminal = store.get("terminalSession", str(task, "pendingTerminalId"));
        assertEquals(1, store.list("terminalSession").size()); assertEquals(1, store.list("taskExecution").size()); assertEquals(1, store.list("dispatchAction").size());
        assertEquals(task.get("taskExecutionId"), terminal.get("taskExecutionId")); assertEquals(task.get("dispatchActionId"), terminal.get("dispatchActionId"));
        assertEquals("QUEUED", terminal.get("state")); assertFalse(bool(terminal, "nativeLaunchAttempted", true));
        assertEquals(7, num(map(task, "budget"), "usedCalls", -1));
    }

    @Test
    void unclaimedRestartRetainsOriginalIdsAndAllowsOneOriginalClaim() throws Exception {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        String originalCore = store.coreSessionId(); store.close(); store = new ExecutionStore(new ObjectMapper(), data.toString());
        queue = new TaskDispatchQueue(store); ledger = new TerminalLedger(store); queue.recover(); ledger.recover();
        assertNotEquals(originalCore, store.coreSessionId());
        Map<String, Object> restored = store.get("terminalSession", str(terminal, "id"));
        assertEquals("QUEUED", restored.get("state")); assertFalse(bool(restored, "nativeLaunchAttempted", true));
        assertEquals(action.get("id"), restored.get("dispatchActionId"));
        Map<String, Object> execution = queue.claim(str(action, "id"), 1, ignored -> {}); assertFalse(execution.isEmpty());
        assertEquals(restored.get("taskExecutionId"), execution.get("id")); assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("duplicate claim")).isEmpty());
        assertEquals(1, num(store.get("dispatchAction", str(action, "id")), "dispatchCount", -1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "CANCELLED"})
    void withdrawingUnclaimedTerminalClosesOriginalIntentWithoutNativeLaunch(String state) {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        assertTrue(queue.withdraw(taskId, state, "explicit stop before claim"));
        Map<String, Object> stopped = store.get("terminalSession", str(terminal, "id"));
        assertEquals("CANCELLED", stopped.get("state")); assertTrue(bool(stopped, "withdrawnBeforeLaunch", false)); assertFalse(bool(stopped, "nativeLaunchAttempted", true));
        assertFalse(store.get("task", taskId).containsKey("pendingTerminalId"));
        assertEquals("WITHDRAWN", store.get("dispatchAction", str(action, "id")).get("state"));
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("withdrawn launch")).isEmpty());
        assertEquals(0, store.activeTerminalCount());
    }

    @Test
    void claimedRestartKeepsUnknownOriginalShellIntentAndCannotReplay() throws Exception {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        assertFalse(queue.claim(str(action, "id"), 1, ignored -> {}).isEmpty());
        store.close(); store = new ExecutionStore(new ObjectMapper(), data.toString()); queue = new TaskDispatchQueue(store); ledger = new TerminalLedger(store);
        queue.recover(); ledger.recover();
        assertEquals("UNKNOWN", store.get("terminalSession", str(terminal, "id")).get("state"));
        assertEquals("UNKNOWN", store.get("task", taskId).get("state"));
        assertEquals("UNKNOWN", store.get("dispatchAction", str(action, "id")).get("state"));
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("unknown replay")).isEmpty());
        assertEquals(1, store.list("terminalSession").size()); assertEquals(7, num(map(store.get("task", taskId), "budget"), "usedCalls", -1));
    }

    @Test
    void changedOwnedWorkspaceOrOriginalTerminalRefusesClaim() throws Exception {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        Path replacement = Files.createDirectories(data.resolve("other-work")).toRealPath();
        store.editTask(taskId, task -> task.put("workspace", replacement.toString()), null, "");
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("changed workspace")).isEmpty());
        store.editTask(taskId, task -> task.put("workspace", terminal.get("workspace")), null, "");
        Map<String, Object> replaced = store.get("terminalSession", str(terminal, "id")); replaced.put("taskExecutionId", id()); store.put("terminalSession", replaced);
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("changed terminal source")).isEmpty());
        assertEquals(0, num(store.get("dispatchAction", str(action, "id")), "dispatchCount", -1)); assertTrue(store.healthy());
    }

    @Test
    void foreignAndSplitTerminalIntentsCannotEnterRootQueue() throws Exception {
        String foreign = task(); Map<String, Object> terminal = terminal(foreign);
        assertEquals(409, assertThrows(ApiException.class, () -> enqueue(taskId, terminal)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> queue.enqueue(taskId, "USER_TERMINAL", task -> task.put("pendingTerminalId", terminal.get("id")))).status());
        assertEquals("PAUSED", store.get("task", taskId).get("state")); assertTrue(store.list("taskExecution").isEmpty()); assertTrue(store.list("terminalSession").isEmpty());
    }

    @Test
    void fourQueuedTerminalsReserveCapacityUntilAnOriginalIntentIsWithdrawn() throws Exception {
        Map<String, Object> first = enqueue(taskId, terminal(taskId));
        for (int index = 1; index < TaskDispatchQueue.MAX_ACTIVE_TERMINALS; index++) { String next = task(); enqueue(next, terminal(next)); }
        String fifth = task(); Map<String, Object> terminal = terminal(fifth);
        assertEquals(409, assertThrows(ApiException.class, () -> enqueue(fifth, terminal)).status());
        assertEquals("PAUSED", store.get("task", fifth).get("state")); assertEquals(4, store.activeTerminalCount()); assertTrue(store.healthy());
        assertTrue(queue.withdraw(taskId, "PAUSED", "release queued capacity")); assertEquals("WITHDRAWN", store.get("dispatchAction", str(first, "id")).get("state"));
        enqueue(fifth, terminal); assertEquals(4, store.activeTerminalCount()); assertEquals(5, store.list("terminalSession").size());
    }

    @Test
    void schemaNineUpgradeRetainsOriginalTaskWithoutInventingTerminalOrRoot() throws Exception {
        Map<String, Object> original = store.get("task", taskId); store.close();
        for (String index : List.of("entities_terminal_active_task", "entities_terminal_task", "entities_terminal_start_request", "entities_terminal_action_request", "entities_terminal_action_sequence",
                "entities_terminal_action_state", "entities_terminal_output_offset", "entities_terminal_output_next")) sql("DROP INDEX " + index);
        sql("PRAGMA user_version=9"); store = new ExecutionStore(new ObjectMapper(), data.toString());
        assertEquals(original, store.get("task", taskId)); assertTrue(store.list("terminalSession").isEmpty()); assertTrue(store.list("taskExecution").isEmpty());
        assertTrue(store.list("terminalAction").isEmpty()); assertTrue(store.list("terminalOutputSegment").isEmpty());
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var command = database.createStatement()) {
            try (var version = command.executeQuery("PRAGMA user_version")) { assertTrue(version.next()); assertEquals(10, version.getInt(1)); }
            try (var indexes = command.executeQuery("SELECT count(*) FROM sqlite_master WHERE type='index' AND name LIKE 'entities_terminal_%'")) { assertTrue(indexes.next()); assertEquals(8, indexes.getInt(1)); }
        }
    }

    @Test
    void originalRootCannotSettleWhileTerminalLifecycleReceiptIsMissing() {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        Map<String, Object> execution = queue.claim(str(action, "id"), 1, ignored -> {}); assertFalse(execution.isEmpty());
        store.editTask(taskId, task -> task.put("state", "PAUSED"), null, ""); queue.finish(str(execution, "id"));
        assertEquals("UNKNOWN", store.get("terminalSession", str(terminal, "id")).get("state"));
        assertEquals("UNKNOWN", store.get("taskExecution", str(execution, "id")).get("state"));
        assertEquals("UNKNOWN", store.get("dispatchAction", str(action, "id")).get("state"));
        assertEquals("UNKNOWN", store.get("task", taskId).get("state"));
    }

    @Test
    void humanReconciliationRetainsUnknownTerminalAndClearsOnlyItsOriginalPendingPointer() {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal);
        queue.claim(str(action, "id"), 1, ignored -> {}); queue.recover(); ledger.recover();
        String terminalId = str(terminal, "id"); assertTrue(store.hasUnknownTerminalEffects(taskId));
        queue.reconcile(taskId, obj("note", "original shell and external effects checked"), task -> task.put("state", "PAUSED"));
        Map<String, Object> original = store.get("terminalSession", terminalId);
        assertEquals("UNKNOWN", original.get("state")); assertTrue(original.containsKey("reconciliation")); assertTrue(bool(original, "inputFenced", false));
        assertFalse(store.get("task", taskId).containsKey("pendingTerminalId")); assertFalse(store.hasUnknownTerminalEffects(taskId)); assertFalse(store.hasUnknownTaskExecution(taskId));
        Map<String, Object> resumed = queue.enqueue(taskId, "USER_RESUME", ignored -> {});
        assertFalse(resumed.containsKey("terminalId")); assertEquals(1, store.list("terminalSession").size()); assertFalse(store.get("task", taskId).containsKey("pendingTerminalId"));
        assertEquals("UNKNOWN", store.get("taskExecution", str(action, "taskExecutionId")).get("state")); assertEquals(7, num(map(store.get("task", taskId), "budget"), "usedCalls", -1));
    }

    @Test
    void failedTerminalReconciliationRollsBackRootAnnotationsAndOriginalTaskPointer() throws Exception {
        Map<String, Object> terminal = terminal(taskId), action = enqueue(taskId, terminal); queue.claim(str(action, "id"), 1, ignored -> {}); queue.recover(); ledger.recover();
        Map<String, Object> originalTask = store.get("task", taskId), originalTerminal = store.get("terminalSession", str(terminal, "id"));
        sql("CREATE TRIGGER fail_terminal_reconciliation BEFORE UPDATE ON entities WHEN new.kind='terminalSession' AND json_extract(new.body,'$.reconciliation') IS NOT NULL BEGIN SELECT RAISE(ABORT,'terminal reconciliation fault'); END");
        assertEquals(503, assertThrows(ApiException.class, () -> queue.reconcile(taskId, obj("note", "checked original effects"), task -> task.put("state", "PAUSED"))).status());
        assertEquals(originalTask, store.get("task", taskId)); assertEquals(originalTerminal, store.get("terminalSession", str(terminal, "id")));
        assertFalse(store.get("taskExecution", str(action, "taskExecutionId")).containsKey("reconciliation")); assertFalse(store.get("dispatchAction", str(action, "id")).containsKey("reconciliation"));
        assertTrue(store.hasUnknownTerminalEffects(taskId)); assertFalse(store.healthy());
    }
}
