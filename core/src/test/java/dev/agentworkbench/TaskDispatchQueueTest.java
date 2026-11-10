package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class TaskDispatchQueueTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private TaskDispatchQueue queue;
    private String taskId;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), directory.toString()); queue = new TaskDispatchQueue(store);
        taskId = id();
        store.put("task", obj("id", taskId, "projectId", "project", "state", "DRAFT", "outcome", "UNDETERMINED", "workspace", "", "version", "",
            "contract", obj("id", id(), "version", 1, "confirmed", false, "scope", List.of("src/**")),
            "execution", obj("version", 1, "executionNodeId", "local", "bindingVersion", 1),
            "routeId", "route", "routeSnapshot", obj("id", "route", "version", 1, "engine", "API"),
            "budget", obj("maxCalls", 12, "reserveCalls", 4, "usedCalls", 0), "policyVersion", "queue-test"));
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> enqueue() {
        return queue.enqueue(taskId, "USER_APPROVAL", task -> map(task, "contract").put("confirmed", true));
    }

    private void sql(String statement) throws Exception {
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var command = database.createStatement()) { command.execute(statement); }
    }

    @ParameterizedTest
    @ValueSource(strings = {"task", "taskExecution", "dispatchAction", "event"})
    void approvalFailureRollsBackEveryRecordAndConfirmation(String failedWrite) throws Exception {
        if ("event".equals(failedWrite)) sql("CREATE TRIGGER fail_queue BEFORE INSERT ON events WHEN new.type='QUEUED' BEGIN SELECT RAISE(ABORT,'queue event fault'); END");
        else sql("CREATE TRIGGER fail_queue BEFORE INSERT ON entities WHEN new.kind='" + failedWrite + "' BEGIN SELECT RAISE(ABORT,'queue record fault'); END");
        assertEquals(503, assertThrows(ApiException.class, this::enqueue).status());
        assertFalse(store.healthy());
        Map<String, Object> task = store.get("task", taskId);
        assertEquals("DRAFT", str(task, "state")); assertFalse(bool(map(task, "contract"), "confirmed", true));
        assertFalse(task.containsKey("taskExecutionId")); assertFalse(task.containsKey("dispatchActionId"));
        assertTrue(store.list("taskExecution").isEmpty()); assertTrue(store.list("dispatchAction").isEmpty());
        assertTrue(objects(store.events(taskId, 0, 100).get("events")).isEmpty());
    }

    @Test
    void approvalPersistsOneUnclaimedAttemptAndBothEvents() {
        Map<String, Object> action = enqueue(), task = store.get("task", taskId), execution = store.get("taskExecution", str(action, "taskExecutionId"));
        assertEquals("QUEUED", str(task, "state")); assertTrue(bool(map(task, "contract"), "confirmed", false));
        assertEquals("QUEUED", str(execution, "state")); assertEquals(1, num(execution, "attempt", 0));
        assertEquals("PENDING", str(action, "state")); assertEquals(0, num(action, "dispatchCount", -1));
        assertFalse(action.containsKey("ownerCoreSessionId")); assertFalse(execution.containsKey("startedAt"));
        assertEquals(List.of("PLAN_APPROVED", "QUEUED"), objects(store.events(taskId, 0, 100).get("events")).stream().map(event -> str(event, "type")).toList());
        assertEquals(409, assertThrows(ApiException.class, this::enqueue).status()); assertTrue(store.healthy());
    }

    @Test
    void concurrentClaimantsAcquireOriginalActionOnlyOnce() throws Exception {
        Map<String, Object> action = enqueue(); AtomicInteger admitted = new AtomicInteger(); CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(8)) {
            var futures = java.util.stream.IntStream.range(0, 16).mapToObj(index -> executor.submit(() -> {
                start.await(); return new TaskDispatchQueue(store).claim(str(action, "id"), 1, ignored -> admitted.incrementAndGet());
            })).toList();
            start.countDown(); int acquired = 0;
            for (var future : futures) if (!future.get(5, TimeUnit.SECONDS).isEmpty()) acquired++;
            assertEquals(1, acquired); assertEquals(1, admitted.get());
        }
        Map<String, Object> claimed = store.get("dispatchAction", str(action, "id"));
        assertEquals(1, num(claimed, "dispatchCount", 0)); assertEquals("CLAIMED", str(claimed, "state"));
        assertEquals(store.coreSessionId(), str(claimed, "ownerCoreSessionId"));
        assertEquals(0, num(map(store.get("task", taskId), "budget"), "usedCalls", -1));
        assertEquals(1, store.list("taskExecution").size());
    }

    @Test
    void staleVersionAndChangedAuthorityDoNotClaim() {
        Map<String, Object> action = enqueue(); AtomicInteger entered = new AtomicInteger();
        assertTrue(queue.claim(str(action, "id"), 0, ignored -> entered.incrementAndGet()).isEmpty());
        store.editTask(taskId, task -> map(task, "execution").put("version", 2), null, "");
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> entered.incrementAndGet()).isEmpty());
        assertEquals(0, entered.get()); assertEquals(0, num(store.get("dispatchAction", str(action, "id")), "dispatchCount", -1));
        assertTrue(str(store.get("dispatchAction", str(action, "id")), "waitReason").contains("授权"));
    }

    @Test
    void failedClaimTransactionLeavesPendingWithoutOwner() throws Exception {
        Map<String, Object> action = enqueue();
        sql("CREATE TRIGGER fail_claim BEFORE UPDATE ON entities WHEN new.kind='dispatchAction' AND json_extract(new.body,'$.state')='CLAIMED' BEGIN SELECT RAISE(ABORT,'claim fault'); END");
        assertEquals(503, assertThrows(ApiException.class, () -> queue.claim(str(action, "id"), 1, ignored -> {})).status());
        Map<String, Object> after = store.get("dispatchAction", str(action, "id"));
        assertEquals("PENDING", str(after, "state")); assertEquals(0, num(after, "dispatchCount", -1)); assertFalse(after.containsKey("ownerCoreSessionId"));
        assertEquals("QUEUED", str(store.get("taskExecution", str(action, "taskExecutionId")), "state"));
        assertEquals(List.of("PLAN_APPROVED", "QUEUED"), objects(store.events(taskId, 0, 100).get("events")).stream().map(event -> str(event, "type")).toList());
    }

    @Test
    void databaseVersionChangeBetweenPreflightAndClaimDoesNotOverwriteOriginalAction() {
        Map<String, Object> action = enqueue();
        Map<String, Object> execution = queue.claim(str(action, "id"), 1, ignored -> {
            try { sql("UPDATE entities SET body=json_set(body,'$.version',2) WHERE kind='dispatchAction'"); }
            catch (Exception exception) { throw new IllegalStateException(exception); }
        });
        assertTrue(execution.isEmpty()); assertTrue(store.healthy());
        Map<String, Object> after = store.get("dispatchAction", str(action, "id"));
        assertEquals(2, num(after, "version", 0)); assertEquals("PENDING", str(after, "state")); assertEquals(0, num(after, "dispatchCount", -1));
        assertFalse(after.containsKey("ownerCoreSessionId")); assertEquals("QUEUED", str(store.get("taskExecution", str(action, "taskExecutionId")), "state"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSED", "CANCELLED"})
    void withdrawalEndsNeverStartedAttemptWithoutDispatch(String terminal) {
        Map<String, Object> action = enqueue(); assertTrue(queue.withdraw(taskId, terminal, "user stop"));
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("withdrawn action entered preflight")).isEmpty());
        Map<String, Object> execution = store.get("taskExecution", str(action, "taskExecutionId")), withdrawn = store.get("dispatchAction", str(action, "id"));
        assertEquals(terminal, str(execution, "state")); assertEquals(terminal, str(execution, "taskStateAtEnd"));
        assertFalse(execution.containsKey("startedAt")); assertEquals("WITHDRAWN", str(withdrawn, "state")); assertEquals(0, num(withdrawn, "dispatchCount", -1));
    }

    @Test
    void withdrawalFailureKeepsOriginalPendingTaskAndAttempt() throws Exception {
        Map<String, Object> action = enqueue();
        sql("CREATE TRIGGER fail_withdraw BEFORE INSERT ON entities WHEN new.kind='dispatchAction' AND json_extract(new.body,'$.state')='WITHDRAWN' BEGIN SELECT RAISE(ABORT,'withdraw fault'); END");
        assertEquals(503, assertThrows(ApiException.class, () -> queue.withdraw(taskId, "CANCELLED", "user stop")).status());
        assertEquals("QUEUED", str(store.get("task", taskId), "state"));
        assertEquals("QUEUED", str(store.get("taskExecution", str(action, "taskExecutionId")), "state"));
        assertEquals("PENDING", str(store.get("dispatchAction", str(action, "id")), "state"));
    }

    @Test
    void cancellationAndClaimRacePreservesWinningBoundary() throws Exception {
        Map<String, Object> action = enqueue(); CountDownLatch start = new CountDownLatch(1);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var claim = executor.submit(() -> { start.await(); return queue.claim(str(action, "id"), 1, ignored -> {}); });
            var stop = executor.submit(() -> { start.await(); return queue.withdraw(taskId, "CANCELLED", "race cancellation"); });
            start.countDown(); boolean claimed = !claim.get(5, TimeUnit.SECONDS).isEmpty(), withdrawn = stop.get(5, TimeUnit.SECONDS);
            assertNotEquals(claimed, withdrawn);
            Map<String, Object> after = store.get("dispatchAction", str(action, "id"));
            assertEquals(claimed ? "CLAIMED" : "WITHDRAWN", str(after, "state")); assertEquals(claimed ? 1 : 0, num(after, "dispatchCount", -1));
        }
    }

    @Test
    void unclaimedRecoveryRetainsOriginalIdentityAndNewOwnerClaimsOnce() throws Exception {
        Map<String, Object> action = enqueue(); String oldSession = store.coreSessionId(), actionId = str(action, "id"), executionId = str(action, "taskExecutionId");
        store.close(); store = new ExecutionStore(new ObjectMapper(), directory.toString()); queue = new TaskDispatchQueue(store); queue.recover();
        assertTrue(queue.unclaimed(store.get("task", taskId))); assertEquals("PENDING", str(store.get("dispatchAction", actionId), "state"));
        assertEquals(oldSession, str(store.get("dispatchAction", actionId), "enqueuedCoreSessionId"));
        Map<String, Object> execution = queue.claim(actionId, 1, ignored -> {});
        assertEquals(executionId, str(execution, "id")); assertEquals(1, num(execution, "attempt", 0)); assertNotEquals(oldSession, str(execution, "ownerCoreSessionId"));
        assertTrue(queue.claim(actionId, 1, ignored -> fail("second claim")).isEmpty());
    }

    @Test
    void claimedRecoveryKeepsUnknownAndReconciliationCreatesSeparateAttempt() throws Exception {
        Map<String, Object> action = enqueue(); String executionId = str(action, "taskExecutionId"), actionId = str(action, "id"), oldSession = store.coreSessionId();
        queue.claim(actionId, 1, ignored -> {}); store.editTask(taskId, task -> map(task, "budget").put("usedCalls", 3), null, "");
        store.close(); store = new ExecutionStore(new ObjectMapper(), directory.toString()); queue = new TaskDispatchQueue(store); queue.recover();
        Map<String, Object> unknown = store.get("taskExecution", executionId);
        assertEquals("UNKNOWN", str(unknown, "state")); assertEquals(oldSession, str(unknown, "ownerCoreSessionId")); assertEquals(3, num(map(unknown, "budgetAtEnd"), "usedCalls", 0));
        String originalEnd = str(unknown, "endedAt"); queue.recover(); assertEquals(originalEnd, str(store.get("taskExecution", executionId), "endedAt"));
        assertTrue(queue.claim(actionId, 1, ignored -> fail("unknown replay")).isEmpty()); assertTrue(store.hasUnknownTaskExecution(taskId));
        assertEquals(409, assertThrows(ApiException.class, () -> queue.enqueue(taskId, "USER_RESUME", ignored -> {})).status());
        queue.reconcile(taskId, obj("note", "original processes and external effects checked"), task -> task.put("state", "PAUSED"));
        assertFalse(store.hasUnknownTaskExecution(taskId));
        Map<String, Object> next = queue.enqueue(taskId, "USER_RESUME", ignored -> {});
        assertEquals(2, num(next, "attempt", 0)); assertNotEquals(actionId, str(next, "id")); assertNotEquals(executionId, str(next, "taskExecutionId"));
        assertEquals("UNKNOWN", str(store.get("taskExecution", executionId), "state")); assertEquals("UNKNOWN", str(store.get("dispatchAction", actionId), "state"));
        assertEquals(originalEnd, str(store.get("taskExecution", executionId), "endedAt")); assertTrue(store.get("taskExecution", executionId).containsKey("reconciliation"));
        assertEquals(3, num(map(store.get("task", taskId), "budget"), "usedCalls", 0));
    }

    @Test
    void executionSuccessRecordsAwaitingAcceptanceAndCannotBeOverwritten() {
        Map<String, Object> action = enqueue(); String executionId = str(action, "taskExecutionId"); queue.claim(str(action, "id"), 1, ignored -> {});
        store.editTask(taskId, task -> { task.put("state", "AWAITING_ACCEPTANCE"); task.put("version", "content-v1"); }, null, ""); queue.finish(executionId);
        Map<String, Object> finished = store.get("taskExecution", executionId);
        assertEquals("SUCCEEDED", str(finished, "state")); assertEquals("AWAITING_ACCEPTANCE", str(finished, "taskStateAtEnd")); assertEquals("UNDETERMINED", str(finished, "taskOutcomeAtEnd"));
        store.editTask(taskId, task -> { task.put("state", "COMPLETED"); task.put("outcome", "ACCEPTED"); }, null, ""); queue.finish(executionId);
        assertEquals(finished, store.get("taskExecution", executionId));
    }

    @Test
    void pendingUnknownEffectsBlockPreflightWithoutErasingOriginalEffect() {
        Map<String, Object> action = enqueue(); store.put("operation", obj("id", "unknown-effect", "taskId", taskId, "state", "UNKNOWN"));
        assertTrue(queue.claim(str(action, "id"), 1, ignored -> fail("unknown effects entered admission")).isEmpty());
        long events = store.latestEvent(); queue.claim(str(action, "id"), 1, ignored -> fail("second admission")); assertEquals(events, store.latestEvent());
        assertEquals("UNKNOWN", str(store.get("operation", "unknown-effect"), "state")); assertEquals(0, num(store.get("dispatchAction", str(action, "id")), "dispatchCount", -1));
    }

    @Test
    void stablePagesPreserveOlderAttemptsWhenNewAttemptIsInserted() {
        for (int attempt = 0; attempt < 7; attempt++) { if (attempt == 0) enqueue(); else queue.enqueue(taskId, "USER_RESUME", ignored -> {}); queue.withdraw(taskId, "PAUSED", "page fixture"); }
        Map<String, Object> first = store.taskExecutionPage(taskId, 0, 3, ""); assertTrue(bool(first, "hasMore", false));
        Set<String> seen = new HashSet<>(); objects(first.get("executions")).forEach(item -> assertTrue(seen.add(str(item, "id"))));
        queue.enqueue(taskId, "USER_RESUME", ignored -> {});
        long cursor = ((Number) first.get("cursor")).longValue();
        while (true) { Map<String, Object> page = store.taskExecutionPage(taskId, cursor, 3, ""); objects(page.get("executions")).forEach(item -> assertTrue(seen.add(str(item, "id")))); cursor = ((Number) page.get("cursor")).longValue(); if (!bool(page, "hasMore", false)) break; }
        assertEquals(7, seen.size()); assertEquals(8, store.taskDispatchRecords("taskExecution", taskId).size());
        assertEquals(1, objects(store.dispatchActionPage(taskId, 0, 30, "PENDING").get("actions")).size());
        assertEquals(400, assertThrows(ApiException.class, () -> store.taskExecutionPage(taskId, -1, 30, "")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> store.dispatchActionPage(taskId, 0, 30, "BAD")).status()); assertTrue(store.healthy());
    }

    @Test
    void pendingScanCanPassBlockedOldActions() {
        Map<String, Object> first = enqueue(); queue.waiting(str(first, "id"), "old gate");
        String other = id(); Map<String, Object> task = store.get("task", taskId); task.put("id", other); task.put("state", "DRAFT"); task.remove("taskExecutionId"); task.remove("dispatchActionId"); store.put("task", task);
        Map<String, Object> second = queue.enqueue(other, "USER_APPROVAL", ignored -> {});
        List<Map<String, Object>> page = store.pendingDispatchActions(0, 1); assertEquals(str(first, "id"), str(page.getFirst(), "id"));
        page = store.pendingDispatchActions(((Number) page.getFirst().get("sequence")).longValue(), 1); assertEquals(str(second, "id"), str(page.getFirst(), "id"));
    }

    @Test
    void schemaFiveHistoryMigratesWithoutInventingDispatchRecords() throws Exception {
        Map<String, Object> original = store.get("task", taskId); store.close();
        sql("DROP INDEX entities_task_execution_attempt"); sql("DROP INDEX entities_dispatch_execution"); sql("DROP INDEX entities_dispatch_active_task"); sql("DROP INDEX entities_dispatch_pending"); sql("DROP INDEX entities_dispatch_task"); sql("PRAGMA user_version=5");
        store = new ExecutionStore(new ObjectMapper(), directory.toString());
        assertEquals(original, store.get("task", taskId)); assertTrue(store.list("taskExecution").isEmpty()); assertTrue(store.list("dispatchAction").isEmpty());
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var command = database.createStatement(); var version = command.executeQuery("PRAGMA user_version")) { assertTrue(version.next()); assertEquals(7, version.getInt(1)); }
    }
}
