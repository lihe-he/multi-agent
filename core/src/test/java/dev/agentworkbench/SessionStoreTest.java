package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class SessionStoreTest {
    @TempDir Path directory;
    private ExecutionStore store;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), directory.toString());
        store.put("project", obj("id", "a")); store.put("project", obj("id", "b"));
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> task(String id, String sessionId) {
        return obj("id", id, "projectId", "a", "sessionId", sessionId, "title", id, "goal", "goal " + id,
            "state", "RUNNING", "budget", obj("usedCalls", 3), "evidence", List.of(obj("id", "proof")));
    }

    @Test
    void metadataAndIdempotentNotesSurviveRestart() throws Exception {
        String sessionId = str(store.createSession("a", "工作"), "id");
        var first = store.appendSessionNote("a", sessionId, "request_1", "背景与依据");
        store.editSession("a", sessionId, obj("expectedVersion", 1, "title", "重命名", "isOpen", false));
        store.close(); store = null;
        store = new ExecutionStore(new ObjectMapper(), directory.toString());
        assertEquals("重命名", str(store.session("a", sessionId), "title"));
        assertFalse(bool(store.session("a", sessionId), "isOpen", true));
        assertEquals(first, store.appendSessionNote("a", sessionId, "request_1", "背景与依据"));
        assertEquals(409, assertThrows(ApiException.class, () -> store.appendSessionNote("a", sessionId, "request_1", "other")).status());
        assertEquals(1, objects(store.sessionMessages("a", sessionId, 0, 0, 50).get("messages")).size());
        assertEquals(2, num(store.session("a", sessionId), "version", 0));
        assertTrue(store.healthy());
    }

    @Test
    void staleMetadataAndForeignProjectsCannotMutateSession() {
        String sessionId = str(store.createSession("a", "original"), "id");
        store.editSession("a", sessionId, obj("expectedVersion", 1, "title", "new"));
        assertEquals(409, assertThrows(ApiException.class, () -> store.editSession("a", sessionId, obj("expectedVersion", 1, "isOpen", false))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> store.editSession("a", sessionId, obj("expectedVersion", 2, "isOpen", "false"))).status());
        assertEquals(404, assertThrows(ApiException.class, () -> store.session("b", sessionId)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> store.appendSessionNote("b", sessionId, "x", "foreign")).status());
        var foreign = task("foreign", sessionId); foreign.put("projectId", "b");
        assertEquals(403, assertThrows(ApiException.class, () -> store.createTask(foreign)).status());
        assertNull(store.find("task", "foreign"));
        assertTrue(objects(store.sessionMessages("a", sessionId, 0, 0, 50).get("messages")).isEmpty());
        assertEquals("new", str(store.session("a", sessionId), "title")); assertTrue(store.healthy());
    }

    @Test
    void messageCursorsCoverOldAndConcurrentNewRecordsWithoutGaps() {
        String sessionId = str(store.createSession("a", "history"), "id");
        for (int i = 1; i <= 125; i++) store.appendSessionNote("a", sessionId, "n" + i, "note " + i);
        var page = store.sessionMessages("a", sessionId, 0, 0, 50);
        long latest = ((Number) page.get("latestCursor")).longValue();
        List<Map<String, Object>> old = new ArrayList<>(objects(page.get("messages")));
        for (int i = 126; i <= 137; i++) store.appendSessionNote("a", sessionId, "n" + i, "note " + i);
        while (bool(page, "hasMore", false)) {
            page = store.sessionMessages("a", sessionId, ((Number) page.get("cursor")).longValue(), 0, 50);
            old.addAll(objects(page.get("messages")));
        }
        assertEquals(125, old.size()); assertEquals(125, new HashSet<>(old.stream().map(m -> str(m, "requestId")).toList()).size());
        List<String> appended = new ArrayList<>();
        do {
            page = store.sessionMessages("a", sessionId, 0, latest, 7);
            appended.addAll(objects(page.get("messages")).stream().map(m -> str(m, "requestId")).toList());
            latest = ((Number) page.get("latestCursor")).longValue();
        } while (bool(page, "hasMore", false));
        assertEquals(List.of("n126", "n127", "n128", "n129", "n130", "n131", "n132", "n133", "n134", "n135", "n136", "n137"), appended);
        assertEquals(400, assertThrows(ApiException.class, () -> store.sessionMessages("a", sessionId, 1, 1, 50)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> store.sessionMessages("a", sessionId, -1, 0, 50)).status());
    }

    @Test
    void sessionHistoryCursorStaysStableAcrossRenameAndInsertion() {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 35; i++) ids.add(str(store.createSession("a", "s" + i), "id"));
        store.createSession("b", "foreign");
        var page = store.sessions("a", 0, 12, false);
        List<String> read = new ArrayList<>(objects(page.get("sessions")).stream().map(s -> str(s, "id")).toList());
        store.editSession("a", ids.getFirst(), obj("expectedVersion", 1, "title", "renamed"));
        String newId = str(store.createSession("a", "new"), "id");
        while (bool(page, "hasMore", false)) {
            page = store.sessions("a", ((Number) page.get("cursor")).longValue(), 12, false);
            read.addAll(objects(page.get("sessions")).stream().map(s -> str(s, "id")).toList());
        }
        assertEquals(35, read.size()); assertEquals(new HashSet<>(ids), new HashSet<>(read)); assertFalse(read.contains(newId));
    }

    @Test
    void closingTabPreservesTaskOperationsBudgetEvidenceAndEventSources() {
        String sessionId = str(store.createSession("a", "work"), "id");
        store.createTask(task("first", sessionId)); store.createTask(task("second", sessionId)); store.createTask(task("other", ""));
        var operation = store.beginOperation("first", "implement", "MODEL", "run");
        var snapshot = store.get("task", "first");
        store.editSession("a", sessionId, obj("expectedVersion", 1, "isOpen", false));
        assertEquals(snapshot, store.get("task", "first"));
        assertEquals(operation, store.get("operation", str(operation, "id")));
        assertEquals(1, objects(store.sessions("a", 0, 50, true).get("sessions")).size());
        var messages = objects(store.sessionMessages("a", sessionId, 0, 0, 50).get("messages"));
        assertEquals(List.of("first", "second"), messages.stream().map(m -> str(m, "taskId")).toList());
        var page = store.sessionEvents("a", sessionId, 0, 1);
        assertTrue(bool(page, "hasMore", false));
        var remaining = store.sessionEvents("a", sessionId, ((Number) page.get("cursor")).longValue(), 100);
        List<Map<String, Object>> events = new ArrayList<>(objects(page.get("events"))); events.addAll(objects(remaining.get("events")));
        assertTrue(events.stream().allMatch(e -> List.of("first", "second").contains(str(e, "taskId"))));
        assertTrue(events.stream().anyMatch(e -> "second".equals(str(e, "taskId"))));
    }

    @Test
    void failedGoalWriteRollsBackTaskSessionAndEventTogether() throws Exception {
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = db.createStatement()) {
            sql.execute("CREATE TRIGGER fail_goal BEFORE INSERT ON session_messages BEGIN SELECT RAISE(ABORT,'injected storage failure'); END");
        }
        assertEquals(503, assertThrows(ApiException.class, () -> store.createTask(task("broken", ""))).status());
        assertNull(store.find("task", "broken")); assertTrue(store.list("session").isEmpty());
        assertTrue(objects(store.events("broken", 0, 100).get("events")).isEmpty()); assertFalse(store.healthy());
    }

    @Test
    void legacyMigrationIsIdempotentAndDoesNotRewriteExecutionState() throws Exception {
        var legacy = task("legacy", ""); legacy.remove("sessionId"); legacy.put("state", "UNKNOWN"); legacy.put("updatedAt", "2026-11-01T00:00:00Z");
        store.put("task", legacy);
        store.put("mcpOperation", obj("id", "uncertain", "taskId", "legacy", "state", "UNKNOWN"));
        store.close(); store = null;
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = db.createStatement()) {
            sql.execute("DROP TABLE session_messages"); sql.execute("DROP INDEX entities_session_project"); sql.execute("PRAGMA user_version=1");
        }
        store = new ExecutionStore(new ObjectMapper(), directory.toString());
        store.migrateTaskSessions(); store.migrateTaskSessions();
        var migrated = store.get("task", "legacy");
        assertEquals("legacy-legacy", migrated.remove("sessionId")); assertEquals(legacy, migrated);
        store.close(); store = null; store = new ExecutionStore(new ObjectMapper(), directory.toString()); store.migrateTaskSessions();
        assertEquals(1, store.list("session").size());
        assertEquals(1, objects(store.sessionMessages("a", "legacy-legacy", 0, 0, 50).get("messages")).size());
        assertEquals("UNKNOWN", str(store.get("mcpOperation", "uncertain"), "state"));
        assertFalse(bool(store.session("a", "legacy-legacy"), "isOpen", true));
    }

    @Test
    void futureSchemaIsRejectedWithoutDowngradeAndLockIsReleased() throws Exception {
        store.close(); store = null;
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = db.createStatement()) { sql.execute("PRAGMA user_version=99"); }
        assertThrows(IllegalStateException.class, () -> new ExecutionStore(new ObjectMapper(), directory.toString()));
        try (var db = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = db.createStatement()) {
            try (var version = sql.executeQuery("PRAGMA user_version")) { assertTrue(version.next()); assertEquals(99, version.getInt(1)); }
            sql.execute("PRAGMA user_version=1");
        }
        store = new ExecutionStore(new ObjectMapper(), directory.toString());
        assertNotNull(store.createSession("a", "compatible migration"));
    }
}
