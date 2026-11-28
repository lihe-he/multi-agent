package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class PersistentScheduleServiceTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private PersistentScheduleService service;
    private MutableClock clock;
    private String projectId = "schedule-project";
    private String routeId = "schedule-route";
    private final AtomicInteger taskSequence = new AtomicInteger();
    private final AtomicInteger approvals = new AtomicInteger();

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString());
        store.put("project", obj("id", projectId, "name", "schedule project"));
        store.put("route", obj("id", routeId, "engine", "API", "model", "fixture", "enabled", true));
        catalog = mock(WorkbenchCatalog.class);
        engine = mock(WorkbenchEngine.class);
        when(catalog.createScheduledTask(anyMap(), anyString(), anyString())).thenAnswer(call -> {
            Map<String, Object> template = call.getArgument(0);
            String occurrenceId = call.getArgument(1), date = call.getArgument(2);
            Map<String, Object> task = obj("id", "scheduled-task-" + taskSequence.incrementAndGet(), "projectId", template.get("projectId"),
                "title", str(template, "title") + " · " + date, "goal", template.get("goal"), "state", "DRAFT",
                "contract", obj("confirmed", false), "scheduleOccurrenceId", occurrenceId);
            return store.createTask(task);
        });
        doAnswer(call -> {
            approvals.incrementAndGet();
            Map<String, Object> task = store.get("task", call.getArgument(0)); task.put("state", "QUEUED"); store.put("task", task);
            return task;
        }).when(engine).action(anyString(), eq("approve"), anyMap());
        clock = new MutableClock();
        service = new PersistentScheduleService(store, catalog, engine, clock);
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> daily(String localTime) {
        return service.create(scheduleInput(localTime, taskTemplate()));
    }

    private Map<String, Object> scheduleInput(String localTime, Map<String, Object> task) {
        return obj("name", "Morning review", "timezone", "UTC", "localTime", localTime, "enabled", true, "task", task);
    }

    private Map<String, Object> taskTemplate() {
        return obj("projectId", projectId, "routeId", routeId, "title", "Daily review", "goal", "Review current changes",
            "mode", "LEAD", "contract", obj("acceptance", List.of("review evidence"), "commands", List.of("node verify.mjs")),
            "budget", obj("maxCalls", 12, "reserveCalls", 4), "locks", obj());
    }

    private void assertRejectedTemplate(Map<String, Object> task) {
        ApiException exception = assertThrows(ApiException.class, () -> service.create(scheduleInput("09:00", task)));
        assertEquals(400, exception.status());
        assertTrue(store.list("schedule").isEmpty());
    }

    @Test
    void oneLocalDateCreatesOneTaskAndSessionEvenAcrossRepeatedTicks() {
        Map<String, Object> schedule = daily("09:00");
        service.tick(Instant.parse("2026-06-03T09:02:00Z"));
        service.tick(Instant.parse("2026-06-03T09:04:00Z"));

        assertEquals(1, store.list("scheduleOccurrence").size());
        assertEquals("TRIGGERED", str(store.list("scheduleOccurrence").getFirst(), "state"));
        assertEquals(1, store.list("task").size());
        assertEquals(1, store.list("session").size());
        assertEquals(1, approvals.get());
        assertEquals(str(store.list("scheduleOccurrence").getFirst(), "id"), str(store.get("task", "scheduled-task-1"), "scheduleOccurrenceId"));

        service.tick(Instant.parse("2026-06-04T09:01:00Z"));
        assertEquals(2, store.list("scheduleOccurrence").size());
        assertEquals(2, store.list("task").size());
        assertEquals(2, store.list("session").size());
        assertNotEquals(str(store.list("task").get(0), "sessionId"), str(store.list("task").get(1), "sessionId"));
        assertEquals(2, approvals.get());
        assertEquals(schedule.get("id"), str(store.list("scheduleOccurrence").getFirst(), "scheduleId"));
    }

    @Test
    void aTriggerOlderThanFiveMinutesIsRecordedAsMissedWithoutCreatingATask() {
        daily("09:00");
        service.tick(Instant.parse("2026-06-03T09:06:00Z"));
        service.tick(Instant.parse("2026-06-03T09:07:00Z"));

        assertEquals(1, store.list("scheduleOccurrence").size());
        assertEquals("MISSED", str(store.list("scheduleOccurrence").getFirst(), "state"));
        assertEquals(0, store.list("task").size());
        assertEquals(0, approvals.get());
    }

    @Test
    void scheduleCreationRejectsInvalidContractTypesAndLimitsAndOutOfRangeBudgets() {
        Map<String, Object> task = taskTemplate();
        map(task, "contract").put("acceptance", "review evidence");
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("commands", List.of("verify", 12));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("acceptance", java.util.Collections.nCopies(31, "review"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("commands", java.util.Collections.nCopies(13, "verify"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("scope", "src/**");
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("scope", List.of("src/**", 12));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("scope", java.util.Collections.nCopies(101, "src/**"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "contract").put("scope", List.of("../outside"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "budget").put("maxCalls", 1001);
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "budget").put("maxCalls", "oops");
        assertRejectedTemplate(task);

        task = taskTemplate();
        map(task, "budget").put("reserveCalls", 12);
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("collaboration", obj("enabled", "true"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("collaboration", obj("maxActive", 7));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("collaboration", obj("maxDepth", "3"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("collaboration", obj("maxTotal", 65));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("mcpBudget", obj("maxCalls", 65));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("mcpBudget", obj("maxCalls", "oops"));
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("mcpBudget", obj("maxDurationMs", 3600001));
        assertRejectedTemplate(task);

        for (Object timeout : List.of(999, 60001, "oops")) {
            task = taskTemplate();
            task.put("mcpBindings", List.of(obj("serverId", "mcp-server", "name", "tool", "configVersion", 1,
                "definitionVersion", "definition-v1", "fixedArguments", obj(), "timeoutMs", timeout)));
            assertRejectedTemplate(task);
        }

        for (String field : List.of("contract", "budget", "locks", "workflow", "collaboration", "mcpBudget", "workspaceSelection")) {
            task = taskTemplate();
            task.put(field, "malformed");
            assertRejectedTemplate(task);
        }
        task = taskTemplate();
        task.put("mcpBindings", "malformed");
        assertRejectedTemplate(task);

        task = taskTemplate();
        task.put("mcpBindings", List.of("malformed"));
        assertRejectedTemplate(task);
        task = taskTemplate();
        task.put("workflow", obj("definitionId", "workflow", "version", "1", "digest", "digest"));
        assertRejectedTemplate(task);
        task = taskTemplate();
        task.put("workspaceSelection", obj("strategy", 7));
        assertRejectedTemplate(task);
    }

    @Test
    void concurrentOccurrenceClaimsAreUniqueInSqlite() throws Exception {
        Map<String, Object> schedule = daily("09:00");
        var pool = Executors.newFixedThreadPool(8);
        try {
            CountDownLatch start = new CountDownLatch(1);
            List<java.util.concurrent.Future<Boolean>> attempts = java.util.stream.IntStream.range(0, 32).mapToObj(index -> pool.submit(() -> {
                start.await();
                return store.createScheduleOccurrenceIfAbsent(obj("id", "claim-" + index, "scheduleId", schedule.get("id"), "occurrenceKey", "2026-06-03", "state", "PENDING"));
            })).toList();
            start.countDown();
            int winners = 0;
            for (var attempt : attempts) if (attempt.get(5, TimeUnit.SECONDS)) winners++;
            assertEquals(1, winners);
            assertEquals(1, store.list("scheduleOccurrence").size());
            assertFalse(store.createScheduleOccurrenceIfAbsent(obj("id", "changed-time-claim", "scheduleId", schedule.get("id"), "occurrenceKey", "2026-06-03", "state", "PENDING")));
        } finally { pool.shutdownNow(); }
    }

    @Test
    void scheduleEditsUseOptimisticVersionAndOnlyAffectFutureOccurrences() {
        Map<String, Object> old = daily("09:00");
        Map<String, Object> updated = service.update(str(old, "id"), obj("expectedVersion", 1, "localTime", "10:15"));
        assertEquals(2, num(updated, "version", 0));
        assertEquals("10:15", str(updated, "localTime"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.update(str(old, "id"), obj("expectedVersion", 1, "enabled", false))).status());
    }

    @Test
    void pendingOccurrenceIsRecoveredAfterScheduleIsDisabled() throws Exception {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> template = map(schedule, "task");
        Map<String, Object> occurrence = obj("id", "pending-disabled", "scheduleId", schedule.get("id"), "scheduleVersion", 1,
            "occurrenceKey", "2026-06-03", "localDate", "2026-06-03", "timezone", "UTC", "localTime", "09:00",
            "scheduledAt", "2026-06-03T09:00:00Z", "taskTemplate", template, "state", "PENDING", "createdAt", "2026-06-03T09:00:00Z", "updatedAt", "2026-06-03T09:00:00Z");
        store.put("scheduleOccurrence", occurrence);
        service.update(str(schedule, "id"), obj("expectedVersion", 1, "enabled", false));
        service.close(); store.close();
        store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString());
        service = new PersistentScheduleService(store, catalog, engine, clock);
        service.tick(Instant.parse("2026-06-04T12:00:00Z"));

        assertEquals("TRIGGERED", str(store.get("scheduleOccurrence", "pending-disabled"), "state"));
        assertEquals(1, store.list("task").size());
        verify(engine, times(1)).action(anyString(), eq("approve"), anyMap());
    }

    @Test
    void backfillCursorCompletesMoreThanOneHundredDatesWithoutRepeatingTheFirstPage() throws Exception {
        PersistentScheduleService longRange = new PersistentScheduleService(store, catalog, engine,
            Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> schedule = longRange.create(obj("name", "Long range", "timezone", "UTC", "localTime", "09:00", "enabled", true,
            "task", obj("projectId", projectId, "routeId", routeId, "title", "Long range", "goal", "Long range goal",
                "mode", "LEAD", "contract", obj("acceptance", List.of("review"), "commands", List.of("verify")), "budget", obj("maxCalls", 12, "reserveCalls", 4), "locks", obj())));
        Instant end = Instant.parse("2026-04-15T12:00:00Z");
        longRange.tick(end);
        assertEquals(100, store.list("scheduleOccurrence").size());
        longRange.close(); store.close();
        store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString());
        longRange = new PersistentScheduleService(store, catalog, engine, Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC));
        longRange.tick(end);
        assertEquals(105, store.list("scheduleOccurrence").size());
        assertTrue(store.list("scheduleOccurrence").stream().allMatch(item -> "MISSED".equals(str(item, "state"))));
        assertEquals(0, store.list("task").size());
        assertEquals("2026-04-15", str(store.scheduleRevisions(str(schedule, "id")).getFirst(), "lastScannedLocalDate"));
        longRange.close();
    }

    @Test
    void concurrentSameVersionUpdatesAllowExactlyOneWriter() throws Exception {
        Map<String, Object> schedule = daily("09:00");
        var pool = Executors.newFixedThreadPool(2);
        try {
            var first = pool.submit(() -> service.update(str(schedule, "id"), obj("expectedVersion", 1, "enabled", false)));
            var second = pool.submit(() -> assertThrows(ApiException.class, () -> service.update(str(schedule, "id"), obj("expectedVersion", 1, "enabled", false))));
            first.get(5, TimeUnit.SECONDS); second.get(5, TimeUnit.SECONDS);
        } finally { pool.shutdownNow(); }
        assertEquals(2, num(store.get("schedule", str(schedule, "id")), "version", 0));
    }

    @Test
    void dstGapIsAuditedOnlyAfterItsTransition() {
        PersistentScheduleService berlin = new PersistentScheduleService(store, catalog, engine,
            Clock.fixed(Instant.parse("2026-03-01T00:00:00Z"), ZoneOffset.UTC));
        Map<String, Object> schedule = berlin.create(obj("name", "DST gap", "timezone", "Europe/Berlin", "localTime", "02:30", "enabled", true,
            "task", obj("projectId", projectId, "routeId", routeId, "title", "DST gap", "goal", "DST gap goal", "mode", "LEAD",
                "contract", obj("acceptance", List.of("review"), "commands", List.of("verify")), "budget", obj("maxCalls", 12, "reserveCalls", 4), "locks", obj())));
        Map<String, Object> revision = store.scheduleRevisions(str(schedule, "id")).getFirst();
        revision.put("effectiveFrom", "2026-03-29T00:00:00Z");
        revision.put("lastScannedLocalDate", null);
        store.put("scheduleRevision", revision);
        berlin.tick(Instant.parse("2026-03-29T00:00:00Z"));
        assertTrue(store.list("scheduleOccurrence").isEmpty());
        berlin.tick(Instant.parse("2026-03-29T01:00:00Z"));
        assertEquals("SKIPPED_DST_GAP", str(store.list("scheduleOccurrence").getFirst(), "state"));
        assertEquals(1, store.list("scheduleOccurrence").size());
        berlin.close();
    }

    @Test
    void missedDatesUseTheConfigurationThatWasEffectiveOnThatDate() {
        Map<String, Object> schedule = daily("09:00");
        clock.set(Instant.parse("2026-06-03T10:00:00Z"));
        Map<String, Object> updated = service.update(str(schedule, "id"), obj("expectedVersion", 1, "timezone", "America/Los_Angeles", "localTime", "02:00"));
        service.tick(Instant.parse("2026-06-03T10:01:00Z"));

        Map<String, Object> occurrence = store.list("scheduleOccurrence").getFirst();
        assertEquals("2026-06-03", str(occurrence, "localDate"));
        assertEquals("UTC", str(occurrence, "timezone"));
        assertEquals("09:00", str(occurrence, "localTime"));
        assertEquals("MISSED", str(occurrence, "state"));
        assertEquals(2, num(updated, "version", 0));
        assertEquals(1, store.list("scheduleOccurrence").size());
    }

    @Test
    void changingLocalTimeCannotTriggerTwiceOnOneLocalDate() {
        Map<String, Object> schedule = daily("09:00");
        service.tick(Instant.parse("2026-06-03T09:01:00Z"));
        clock.set(Instant.parse("2026-06-03T09:02:00Z"));
        service.update(str(schedule, "id"), obj("expectedVersion", 1, "localTime", "10:00"));
        service.tick(Instant.parse("2026-06-03T10:01:00Z"));

        assertEquals(1, store.list("scheduleOccurrence").size());
        assertEquals(1, store.list("task").size());
        assertEquals(1, approvals.get());
    }

    @Test
    void failedPendingOccurrenceMovesToAttentionAndDoesNotStarveLaterEntries() {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> template = map(schedule, "task");
        for (int index = 1; index <= 2; index++) store.put("scheduleOccurrence", obj("id", "pending-" + index, "scheduleId", schedule.get("id"), "scheduleVersion", 1,
            "occurrenceKey", "2026-06-0" + index, "localDate", "2026-06-0" + index, "timezone", "UTC", "localTime", "09:00",
            "scheduledAt", "2026-06-0" + index + "T09:00:00Z", "taskTemplate", template, "state", "PENDING", "createdAt", "2026-06-0" + index + "T09:00:00Z", "updatedAt", "2026-06-0" + index + "T09:00:00Z"));
        doThrow(new ApiException(409, "controlled approval conflict")).when(engine).action("scheduled-task-1", "approve", obj());

        service.tick(Instant.parse("2026-06-04T12:00:00Z"));

        assertEquals("NEEDS_ATTENTION", str(store.get("scheduleOccurrence", "pending-1"), "state"));
        assertEquals("TRIGGERED", str(store.get("scheduleOccurrence", "pending-2"), "state"));
        assertEquals(2, store.list("task").size());
    }

    @Test
    void occurrenceRetryIsAuditedIdempotentAndCreatesOnlyOneTask() {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> occurrence = occurrence("retry-empty", schedule, taskTemplate(), 1);
        store.put("scheduleOccurrence", occurrence);

        Map<String, Object> result = service.recover(str(schedule, "id"), "retry-empty", obj("expectedVersion", 1,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000001", "reason", "依赖已恢复"));

        assertEquals("TRIGGERED", str(result, "state"));
        assertEquals("scheduled-task-1", str(result, "taskId"));
        assertEquals(3, num(result, "version", 0));
        assertEquals(1, store.list("task").size());
        assertEquals(1, approvals.get());
        assertEquals(1, store.list("scheduleOccurrenceRecovery").size());
        assertEquals("LOCAL_USER", str(store.list("scheduleOccurrenceRecovery").getFirst(), "actor"));
        assertEquals("依赖已恢复", str(store.list("scheduleOccurrenceRecovery").getFirst(), "reason"));

        Map<String, Object> replay = service.recover(str(schedule, "id"), "retry-empty", obj("expectedVersion", 1,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000001", "reason", "依赖已恢复"));
        assertEquals("TRIGGERED", str(replay, "state"));
        assertEquals(1, store.list("task").size());
        assertEquals(1, approvals.get());
        assertEquals(1, store.list("scheduleOccurrenceRecovery").size());
    }

    @Test
    void legacyOccurrenceWithoutVersionCanBeRecoveredFromItsProjectedVersion() {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> occurrence = occurrence("legacy-retry", schedule, taskTemplate(), 1);
        occurrence.remove("version");
        store.put("scheduleOccurrence", occurrence);

        Map<String, Object> projected = service.occurrences(str(schedule, "id")).getFirst();
        assertEquals(1, num(projected, "version", 0));
        Map<String, Object> recovered = service.recover(str(schedule, "id"), "legacy-retry", obj("expectedVersion", projected.get("version"),
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000009"));

        assertEquals("TRIGGERED", str(recovered, "state"));
        assertEquals(1, store.list("task").size());
    }

    @Test
    void existingDraftCannotBeRetriedButCanBeReconciledAfterExplicitTaskApproval() {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> occurrence = occurrence("retry-existing", schedule, taskTemplate(), 1);
        store.put("scheduleOccurrence", occurrence);
        Map<String, Object> linkedTask = store.createTask(obj("id", "preexisting-task", "projectId", projectId, "title", "Existing", "goal", "Existing",
            "state", "DRAFT", "scheduleOccurrenceId", "retry-existing"));

        ApiException retry = assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "retry-existing", obj("expectedVersion", 1,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000002")));
        assertEquals(409, retry.status());
        assertEquals("NEEDS_ATTENTION", str(store.get("scheduleOccurrence", "retry-existing"), "state"));
        assertEquals(1, store.list("task").size());

        ApiException draft = assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "retry-existing", obj("expectedVersion", 1,
            "action", "RECONCILE_EXISTING_TASK", "requestId", "00000000-0000-0000-0000-000000000003")));
        assertEquals(409, draft.status());
        linkedTask.put("state", "QUEUED"); store.put("task", linkedTask);
        Map<String, Object> reconciled = service.recover(str(schedule, "id"), "retry-existing", obj("expectedVersion", 1,
            "action", "RECONCILE_EXISTING_TASK", "requestId", "00000000-0000-0000-0000-000000000004", "reason", "已在任务页确认"));
        assertEquals("TRIGGERED", str(reconciled, "state"));
        assertEquals("preexisting-task", str(reconciled, "taskId"));
        assertEquals("QUEUED", str(reconciled, "taskState"));
        verify(engine, never()).action(anyString(), eq("approve"), anyMap());
    }

    @Test
    void retryWithCurrentScheduleVersionPreservesPreviousTemplateInAudit() {
        Map<String, Object> schedule = daily("09:00");
        Map<String, Object> previous = taskTemplate();
        Map<String, Object> occurrence = occurrence("retry-current", schedule, previous, 1);
        store.put("scheduleOccurrence", occurrence);
        Map<String, Object> replacement = new java.util.LinkedHashMap<>(previous);
        replacement.put("goal", "Updated goal");
        service.update(str(schedule, "id"), obj("expectedVersion", 1, "task", replacement));

        Map<String, Object> result = service.recover(str(schedule, "id"), "retry-current", obj("expectedVersion", 1,
            "action", "RETRY_CURRENT_VERSION", "requestId", "00000000-0000-0000-0000-000000000005"));

        assertEquals("TRIGGERED", str(result, "state"));
        assertEquals(2, num(result, "scheduleVersion", 0));
        assertEquals("Updated goal", str(map(result, "taskTemplate"), "goal"));
        Map<String, Object> recovery = store.list("scheduleOccurrenceRecovery").getFirst();
        assertEquals(2, num(recovery, "sourceScheduleVersion", 0));
        assertEquals(1, num(recovery, "previousScheduleVersion", 0));
        assertEquals("Review current changes", str(map(recovery, "previousTaskTemplate"), "goal"));
    }

    @Test
    void occurrenceRecoveryRejectsStaleVersionsUnknownActionsAndChangedIdempotencyPayloads() {
        Map<String, Object> schedule = daily("09:00");
        store.put("scheduleOccurrence", occurrence("recovery-guards", schedule, taskTemplate(), 1));
        assertEquals(409, assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "recovery-guards", obj("expectedVersion", 2,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000006"))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "recovery-guards", obj("expectedVersion", 1,
            "action", "UNKNOWN", "requestId", "00000000-0000-0000-0000-000000000007"))).status());
        service.recover(str(schedule, "id"), "recovery-guards", obj("expectedVersion", 1, "action", "RETRY_SNAPSHOT",
            "requestId", "00000000-0000-0000-0000-000000000008", "reason", "first"));
        assertEquals(409, assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "recovery-guards", obj("expectedVersion", 1,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000008", "reason", "changed"))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> service.recover(str(schedule, "id"), "recovery-guards", obj("expectedVersion", 2,
            "action", "RETRY_SNAPSHOT", "requestId", "00000000-0000-0000-0000-000000000008", "reason", "first"))).status());
    }

    @Test
    void idempotencyTreatsUuidLetterCaseAsTheSameRequest() {
        Map<String, Object> schedule = daily("09:00");
        store.put("scheduleOccurrence", occurrence("uuid-case", schedule, taskTemplate(), 1));
        String lower = "abcdef00-0000-0000-0000-000000000001", upper = lower.toUpperCase(java.util.Locale.ROOT);
        Map<String, Object> first = service.recover(str(schedule, "id"), "uuid-case", obj("expectedVersion", 1, "action", "RETRY_SNAPSHOT", "requestId", lower));
        Map<String, Object> replay = service.recover(str(schedule, "id"), "uuid-case", obj("expectedVersion", 1, "action", "RETRY_SNAPSHOT", "requestId", upper));
        assertEquals("TRIGGERED", str(first, "state"));
        assertEquals("TRIGGERED", str(replay, "state"));
        assertEquals(1, store.list("scheduleOccurrenceRecovery").size());
        assertEquals(1, store.list("task").size());
    }

    @Test
    void schemaTwelveUpgradesToThirteenAndAddsScheduleRecoveryIndex() throws Exception {
        store.close();
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/workbench.sqlite"));
             var statement = database.createStatement()) {
            statement.execute("DROP INDEX entities_schedule_recovery_occurrence");
            statement.execute("PRAGMA user_version=12");
        }
        store = new ExecutionStore(new ObjectMapper(), directory.resolve("data").toString());
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/workbench.sqlite"));
             var statement = database.createStatement();
             var version = statement.executeQuery("PRAGMA user_version")) {
            assertTrue(version.next());
            assertEquals(13, version.getInt(1));
        }
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("data/workbench.sqlite"));
             var statement = database.createStatement();
             var index = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='index' AND name='entities_schedule_recovery_occurrence'")) {
            assertTrue(index.next());
        }
    }

    private Map<String, Object> occurrence(String id, Map<String, Object> schedule, Map<String, Object> template, int version) {
        return obj("id", id, "schemaVersion", 1, "scheduleId", schedule.get("id"), "scheduleVersion", 1, "occurrenceKey", "2026-06-03",
            "localDate", "2026-06-03", "timezone", "UTC", "localTime", "09:00", "scheduledAt", "2026-06-03T09:00:00Z",
            "taskTemplate", new java.util.LinkedHashMap<>(template), "state", "NEEDS_ATTENTION", "version", version,
            "createdAt", "2026-06-03T09:00:00Z", "updatedAt", "2026-06-03T09:00:00Z", "message", "controlled failure");
    }

    private static final class MutableClock extends Clock {
        private final java.util.concurrent.atomic.AtomicReference<Instant> instant = new java.util.concurrent.atomic.AtomicReference<>(Instant.parse("2026-06-03T08:00:00Z"));
        void set(Instant value) { instant.set(value); }
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return instant.get(); }
    }
}
