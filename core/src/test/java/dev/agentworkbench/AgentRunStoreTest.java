package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class AgentRunStoreTest {
    @TempDir Path directory;

    private void task(ExecutionStore store, String taskId, String projectId, String sessionId) {
        store.put("project", obj("id", projectId));
        store.put("session", obj("id", sessionId, "projectId", projectId));
        store.createTask(obj("id", taskId, "projectId", projectId, "sessionId", sessionId, "state", "RUNNING", "evidence", new ArrayList<>(),
            "routeId", "route", "routeSnapshot", obj("id", "route", "engine", "API", "model", "fixture", "apiKey", "must-not-copy"),
            "contract", obj("scope", List.of("src")), "budget", obj("maxCalls", 4, "reserveCalls", 1, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0)));
    }

    private Map<String, Object> queued(ExecutionStore store, String taskId) {
        return store.queueAgentRun(taskId, obj("id", "implement", "kind", "IMPLEMENT", "title", "实现", "role", "developer", "readOnly", false), "workspace", "input-version", false);
    }

    @Test
    void identitiesEvidenceAndAttemptsSurviveReopenWithoutCredentials() throws Exception {
        String runId;
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            task(store, "task", "project", "session");
            var run = queued(store, "task"); runId = str(run, "id");
            assertFalse(run.toString().contains("must-not-copy"));
            var operation = store.beginOperation("task", "implement", "MODEL", "workspace", runId);
            assertTrue(store.acquireAgentCall("task", runId, false));
            store.recordEvidence("task", str(operation, "id"), obj("id", "proof", "stage", "implement", "kind", "AGENT", "status", "PASSED", "version", "output-version"));
            store.finishAgentRun(runId, "SUCCEEDED", "completed");
            assertEquals(runId, str(store.get("evidence", "proof"), "agentRunId"));
            assertEquals("proof", str(store.get("agentRun", runId), "evidenceId"));
            assertEquals("output-version", str(store.get("agentRun", runId), "resultVersion"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishAgentRun(runId, "FAILED", "overwrite")).status());
            assertEquals(2, num(queued(store, "task"), "attempt", 0));
        } finally { store.close(); }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            assertEquals("SUCCEEDED", str(reopened.get("agentRun", runId), "state"));
            assertEquals(1, num(reopened.get("agentRun", runId), "calls", 0));
            assertEquals(1, num(map(reopened.get("task", "task"), "budget"), "usedCalls", 0));
        } finally { reopened.close(); }
    }

    @Test
    void runPagesAreScopedStableAndBounded() throws Exception {
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            task(store, "task", "project", "session"); task(store, "other", "other-project", "other-session");
            queued(store, "task"); queued(store, "task"); queued(store, "other");
            var first = store.agentRunPage("project", "session", "", 0, 1, "WAITING_RESOURCE");
            assertTrue(bool(first, "hasMore", false));
            var second = store.agentRunPage("project", "session", "", ((Number) first.get("cursor")).longValue(), 1, "WAITING_RESOURCE");
            assertFalse(bool(second, "hasMore", true));
            assertNotEquals(objects(first.get("runs")).getFirst().get("id"), objects(second.get("runs")).getFirst().get("id"));
            assertEquals(404, assertThrows(ApiException.class, () -> store.agentRunPage("project", "other-session", "", 0, 30, "")).status());
            assertEquals(403, assertThrows(ApiException.class, () -> store.agentRunPage("project", "", "other", 0, 30, "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> store.agentRunPage("project", "", "", -1, 30, "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> store.agentRunPage("project", "", "", 0, 30, "invalid")).status());
            assertTrue(objects(store.agentRunPage("project", "", "task", 0, 30, "FAILED").get("runs")).isEmpty());
        } finally { store.close(); }
    }

    @Test
    void callPersistenceFailureRollsBackRootBudgetAndRunCounter() throws Exception {
        String runId;
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            task(store, "task", "project", "session"); runId = str(queued(store, "task"), "id");
            store.beginOperation("task", "implement", "MODEL", "workspace", runId);
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER fail_run_count BEFORE UPDATE ON entities WHEN new.kind='agentRun' AND json_extract(new.body,'$.calls')>0 BEGIN SELECT RAISE(ABORT,'injected call persistence failure'); END");
            }
            assertEquals(503, assertThrows(ApiException.class, () -> store.acquireAgentCall("task", runId, false)).status());
            assertFalse(store.healthy());
        } finally { store.close(); }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            assertEquals(0, num(reopened.get("agentRun", runId), "calls", -1));
            assertEquals(0, num(map(reopened.get("task", "task"), "budget"), "usedCalls", -1));
        } finally { reopened.close(); }
    }
}
