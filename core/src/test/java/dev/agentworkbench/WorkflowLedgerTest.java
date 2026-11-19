package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 独立核对流程账本的续跑、未知门禁与 SQLite 原子边界，不替代实际 Worker 验收。
 */
class WorkflowLedgerTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkflowCatalog catalog;
    private WorkflowLedger ledger;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(mapper, directory.toString()); catalog = new WorkflowCatalog(store); ledger = new WorkflowLedger(store, catalog, mapper);
        store.put("project", obj("id", "project"));
        Map<String, Object> route = obj("id", "route", "version", 1, "name", "Original route", "engine", "API", "enabled", true,
            "baseUrl", "http://127.0.0.1:18999/v1", "model", "fixture"); store.put("route", route);
        store.put("agentDefinition", obj("id", "agent", "version", 1, "projectId", "", "routeId", "", "name", "Original actor", "role", "researcher",
            "instructions", "Preserve original evidence", "capabilities", List.of("read", "search"), "scope", List.of("src/**"), "maxCalls", 2, "enabled", true));
        Map<String, Object> published = catalog.save(obj("name", "Ledger fixture", "projectId", "project", "dsl", WorkflowDslTest.graph(List.of(WorkflowDslTest.node("source", List.of())))));
        Map<String, Object> task = obj("id", "task", "projectId", "project", "state", "RUNNING", "goal", "Original goal", "context", List.of(), "execution", obj(),
            "routeSnapshot", route, "locks", obj(), "contract", obj("confirmed", true, "permissions", List.of("read", "search"), "scope", List.of("src/**")),
            "budget", obj("maxCalls", 24, "reserveCalls", 4, "usedCalls", 0), "collaboration", obj("enabled", false, "maxActive", 6), "mcpAuthorization", obj("bindings", List.of()));
        Map<String, Object> version = map(published, "version");
        task.put("workflow", catalog.snapshot(task, obj("definitionId", version.get("definitionId"), "version", 1, "digest", version.get("digest")))); store.put("task", task);
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> prepared() {
        Map<String, Object> run = ledger.start("task", "original-root-version");
        store.put("operation", obj("id", "prepare", "taskId", "task", "kind", "WORKFLOW_ROOT_SNAPSHOT", "state", "ACKNOWLEDGED"));
        ledger.prepared(str(run, "id"), "original-baseline-copy", "original-root-version", "prepare"); return ledger.current(str(run, "id"));
    }

    private Map<String, Object> queue(Map<String, Object> run) {
        Map<String, Object> node = objects(map(map(store.get("task", "task"), "workflow"), "dsl").get("nodes")).getFirst();
        return ledger.queue(str(run, "id"), node, List.of());
    }

    @Test
    void pauseBeforeBaselinePublicationResumesPreparationWithTheSameGeneration() {
        Map<String, Object> first = ledger.start("task", "original-root-version"); ledger.state(str(first, "id"), "PAUSED", "Stopped before copy dispatch");
        Map<String, Object> resumed = ledger.start("task", "original-root-version");
        assertEquals(first.get("id"), resumed.get("id")); assertEquals("PREPARING", resumed.get("state")); assertEquals(1, ledger.runs("task").size());
        store.put("operation", obj("id", "prepare", "taskId", "task", "kind", "WORKFLOW_ROOT_SNAPSHOT", "state", "ACKNOWLEDGED"));
        ledger.prepared(str(first, "id"), "original-baseline-copy", "original-root-version", "prepare"); assertEquals("RUNNING", ledger.current(str(first, "id")).get("state"));
        assertTrue(store.list("modelRequest").isEmpty());
    }

    @Test
    void completedTextCannotPublishSuccessWithoutActualRequestsAndWorkerEvidence() {
        Map<String, Object> run = prepared(), attempt = queue(run);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.finish(str(attempt, "id"), "SUCCEEDED", "Claimed completion")).status());
        assertFalse(store.get("workflowNodeAttempt", str(attempt, "id")).containsKey("endedAt"));
        assertEquals("WAITING_RESOURCE", store.get("agentRun", str(attempt, "agentRunId")).get("state")); assertTrue(store.healthy());
    }

    @Test
    void unknownAttemptAndWorkflowCannotBeReplayedOrDowngradedByLateStopText() {
        Map<String, Object> run = prepared(), attempt = queue(run); ledger.finish(str(attempt, "id"), "UNKNOWN", "Original response missing");
        ledger.state(str(run, "id"), "UNKNOWN", "Original response missing"); Map<String, Object> original = ledger.current(str(run, "id"));
        ledger.state(str(run, "id"), "PAUSED", "Late root pause"); assertEquals(original, ledger.current(str(run, "id")));
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.start("task", "original-root-version")).status());
        assertEquals(409, assertThrows(ApiException.class, () -> queue(run)).status()); assertEquals(1, store.list("workflowNodeAttempt").size());
    }

    @Test
    void restartCannotAbandonAnAttemptWithoutItsOriginalStopBoundary() {
        Map<String, Object> run = prepared(); queue(run); Map<String, Object> task = store.get("task", "task");
        task.put("workflowRestartRequested", true); store.put("task", task);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.start("task", "original-root-version")).status());
        assertEquals(1, ledger.runs("task").size()); assertEquals(run.get("id"), store.get("task", "task").get("workflowRunId"));
    }

    @Test
    void changedInputOrRootVersionRequiresExplicitRestartWithoutEditingOldRun() {
        Map<String, Object> run = prepared(); ledger.state(str(run, "id"), "PAUSED", "Original pause"); Map<String, Object> original = ledger.current(str(run, "id"));
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.start("task", "later-root-version")).status());
        Map<String, Object> task = store.get("task", "task"); task.put("goal", "Later goal"); store.put("task", task);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.start("task", "original-root-version")).status());
        assertEquals(original, ledger.current(str(run, "id"))); assertTrue(store.list("workflowNodeAttempt").isEmpty());
    }

    @Test
    void callerCannotInjectAnUnrelatedAncestorIntoANodeAttempt() {
        Map<String, Object> run = prepared(), node = objects(map(map(store.get("task", "task"), "workflow"), "dsl").get("nodes")).getFirst();
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.queue(str(run, "id"), node, List.of("unrelated-node"))).status());
        assertTrue(store.list("workflowNodeAttempt").isEmpty()); assertTrue(store.list("agentRun").isEmpty());
    }

    @Test
    void failedNodePointerWriteRollsBackAgentAndWorkflowAcrossSqliteReopen() throws Exception {
        Map<String, Object> run = prepared(), attempt = queue(run), originalAgent = store.get("agentRun", str(attempt, "agentRunId"));
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_node_end BEFORE INSERT ON entities WHEN new.kind='workflowNodeAttempt' AND json_extract(new.body,'$.endedAt') IS NOT NULL BEGIN SELECT RAISE(ABORT,'injected node publication failure'); END");
        }
        assertEquals(503, assertThrows(ApiException.class, () -> ledger.finish(str(attempt, "id"), "CANCELLED", "Stopped")).status()); assertFalse(store.healthy());
        store.close(); store = null;
        try (var reopened = new ExecutionStore(mapper, directory.toString())) {
            assertEquals(attempt, reopened.get("workflowNodeAttempt", str(attempt, "id"))); assertEquals(originalAgent, reopened.get("agentRun", str(attempt, "agentRunId")));
            assertEquals("WAITING_RESOURCE", str(map(map(reopened.get("workflowRun", str(run, "id")), "nodes"), "source"), "state"));
        }
    }

    @Test
    void recoveryRetainsIncompleteAttemptAsUnknownAndDoesNotCreateAnotherRequest() {
        Map<String, Object> run = prepared(), attempt = queue(run); ledger.recover();
        assertEquals("UNKNOWN", store.get("workflowNodeAttempt", str(attempt, "id")).get("state"));
        assertEquals("UNKNOWN", ledger.current(str(run, "id")).get("state"));
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.start("task", "original-root-version")).status());
        assertEquals(1, store.list("workflowNodeAttempt").size()); assertTrue(store.list("modelRequest").isEmpty());
    }
}
