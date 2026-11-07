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

class ModelRequestStoreTest {
    @TempDir Path directory;

    private String running(ExecutionStore store, String taskId) {
        store.put("project", obj("id", "project"));
        store.createTask(obj("id", taskId, "projectId", "project", "evidence", new ArrayList<>(),
            "routeSnapshot", obj("engine", "API", "model", "fixture", "apiKey", "never-copy-key"),
            "budget", obj("maxCalls", 3, "reserveCalls", 1, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0)));
        var run = store.queueAgentRun(taskId, obj("id", "implement", "role", "developer"), "workspace", "version", false);
        store.beginOperation(taskId, "implement", "MODEL", "workspace", str(run, "id"));
        return str(run, "id");
    }

    @Test
    void requestAndBudgetCommitAtomicallyOrRollbackTogether() throws Exception {
        String runId;
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            runId = running(store, "task");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_request BEFORE INSERT ON entities WHEN new.kind='modelRequest' BEGIN SELECT RAISE(ABORT,'injected request persistence failure'); END");
            }
            assertEquals(503, assertThrows(ApiException.class, () -> store.beginModelRequest("task", runId, false, Map.of())).status());
            assertFalse(store.healthy());
        } finally { store.close(); }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            assertEquals(0, num(map(reopened.get("task", "task"), "budget"), "usedCalls", -1));
            assertEquals(0, num(reopened.get("agentRun", runId), "calls", -1));
            assertTrue(reopened.list("modelRequest").isEmpty());
        } finally { reopened.close(); }
    }

    @Test
    void requestFactsSurviveReopenAndMissingReceiptStaysUnknown() throws Exception {
        String requestId;
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            String runId = running(store, "task");
            requestId = store.beginModelRequest("task", runId, false, obj("inputDigest", "digest", "apiKey", "never-copy-key", "input", "private prompt"));
            store.finishModelRequest(requestId, obj("state", "SUCCEEDED", "responseId", "response-1", "usage", obj("inputTokens", 0, "outputTokens", "UNKNOWN"), "apiKey", "never-copy-key"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishModelRequest(requestId, obj("state", "FAILED"))).status());
            String pending = store.beginModelRequest("task", runId, false, Map.of());
            assertEquals("", store.beginModelRequest("task", runId, false, Map.of()), "development reserve cannot be consumed");
            store.finishAgentRun(runId, "UNKNOWN", "restart");
            assertEquals("UNKNOWN", str(store.get("modelRequest", pending), "state"));
            assertEquals(2, num(store.get("agentRun", runId), "calls", 0));
            assertFalse(store.list("modelRequest").toString().contains("never-copy-key"));
            assertFalse(store.list("modelRequest").toString().contains("private prompt"));
        } finally { store.close(); }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            var request = reopened.get("modelRequest", requestId);
            assertEquals("SUCCEEDED", str(request, "state"));
            assertEquals(0, num(map(request, "usage"), "inputTokens", -1));
            assertEquals("UNKNOWN", str(map(request, "usage"), "outputTokens"));
            assertTrue(request.containsKey("elapsedMs"));
        } finally { reopened.close(); }
    }

    @Test
    void scopedLiteralFiltersAndStablePaginationKeepHistory() throws Exception {
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            String runId = running(store, "task"), otherRun = running(store, "other");
            String firstId = store.beginModelRequest("task", runId, false, Map.of());
            store.finishModelRequest(firstId, obj("state", "FAILED", "reason", "中文 HTTP_429 100%_literal"));
            String secondId = store.beginModelRequest("task", runId, false, Map.of());
            var newest = store.modelRequestPage("task", "", 0, 1, "", "");
            assertEquals(secondId, objects(newest.get("requests")).getFirst().get("id"));
            store.finishModelRequest(secondId, obj("state", "SUCCEEDED"));
            var older = store.modelRequestPage("task", "", ((Number) newest.get("cursor")).longValue(), 1, "", "");
            assertEquals(firstId, objects(older.get("requests")).getFirst().get("id"));
            assertFalse(bool(older, "hasMore", true));
            assertEquals(1, objects(store.modelRequestPage("task", runId, 0, 1000, "FAILED", "http_429 100%_literal").get("requests")).size());
            assertTrue(objects(store.modelRequestPage("other", "", 0, 30, "", "").get("requests")).isEmpty());
            assertEquals(404, assertThrows(ApiException.class, () -> store.modelRequestPage("task", otherRun, 0, 30, "", "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> store.modelRequestPage("task", "", -1, 30, "", "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> store.modelRequestPage("task", "", 0, 30, "bad", "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> store.modelRequestPage("task", "", 0, 30, "", "x".repeat(201))).status());
        } finally { store.close(); }
    }
}
