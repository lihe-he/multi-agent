package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/**
 * 固定账本夹具只验证阶段投影和拒绝边界，不启动 Worker、CLI 或模型请求。
 */
class WorkflowStageInputsTest {
    private static final String CREDENTIAL = "fixture-context-credential-987654321";
    private static final String ESCAPED_CREDENTIAL = "fixture-\"credential\"\\segment\nfinal";
    private static final String QUOTED_CONTEXT = "Quoted \"evidence\"\\marker\n";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkbenchCatalog catalog;
    private WorkflowStageInputs projection;
    private Map<String, Object> lease;
    private String workflowId;
    private String attemptId;
    private String runId;
    private String inputOperationId;
    private String operationId;

    @BeforeEach
    void open() throws Exception {
        openFixture(List.of("**"), List.of("baseline"));
    }

    private void openFixture(List<String> scope, List<String> declaredInputs) throws Exception {
        store = new ExecutionStore(mapper, directory.toString());
        catalog = new WorkbenchCatalog(store, mock(WorkspaceRuntime.class), new PlanValidator());
        projection = new WorkflowStageInputs(store, catalog, mapper);
        catalog.saveRoute(obj("name", "Redaction credential fixture", "engine", "API", "provider", "fixture", "model", "fixture",
            "baseUrl", "http://127.0.0.1:18999/v1", "apiKey", CREDENTIAL));
        catalog.saveRoute(obj("name", "Escaped credential fixture", "engine", "API", "provider", "fixture", "model", "fixture",
            "baseUrl", "http://127.0.0.1:18999/v1", "apiKey", ESCAPED_CREDENTIAL));
        store.put("project", obj("id", "project"));
        Map<String, Object> route = obj("id", "route", "version", 1, "name", "Original route", "engine", "API", "enabled", true,
            "provider", "fixture", "model", "fixture", "baseUrl", "http://127.0.0.1:18999/v1", "apiKey", "private-route-secret");
        store.put("route", route);
        store.put("agentDefinition", obj("id", "agent", "version", 1, "projectId", "", "routeId", "", "name", "Original actor", "role", "researcher",
            "instructions", "Private actor instructions", "capabilities", List.of("read", "search"), "scope", scope, "maxCalls", 2, "enabled", true));
        Map<String, Object> node = WorkflowDslTest.node("source", List.of()); node.putAll(obj("scope", scope, "inputs", declaredInputs));
        Map<String, Object> version = map(catalog.workflows().save(obj("name", "Stage input fixture", "projectId", "project", "dsl", WorkflowDslTest.graph(List.of(node)))), "version");
        Map<String, Object> task = obj("id", "task", "projectId", "project", "state", "RUNNING", "goal", "Undeclared root goal", "locks", obj(),
            "context", new ArrayList<>(List.of(obj("id", "private-memory", "kind", "FACT", "content", "Undeclared project memory", "version", 1),
                obj("id", "project-rules", "kind", "RULE", "source", "AGENTS.md", "version", "rules-version", "content", "Preserve original comments", "sourcePath", "C:/private/host/repository"),
                obj("id", "ordinary-rule", "kind", "RULE", "source", "AGENTS.md", "version", 1, "content", "Ordinary memory is not a project rule"),
                obj("id", "credential-memory", "kind", "FACT", "version", 1, "content", QUOTED_CONTEXT + CREDENTIAL,
                    "escapedContent", QUOTED_CONTEXT + ESCAPED_CREDENTIAL,
                    "details", obj("array", List.of(ESCAPED_CREDENTIAL, 7, true, obj("deep", ESCAPED_CREDENTIAL)))))),
            "execution", obj(), "routeSnapshot", route, "contract", obj("confirmed", true, "permissions", List.of("read", "search"), "scope", List.of("**")),
            "budget", obj("maxCalls", 24, "reserveCalls", 4, "usedCalls", 0), "collaboration", obj("enabled", false, "maxActive", 6),
            "mcpAuthorization", obj("version", 1, "bindings", List.of()), "taskExecutionId", "execution", "dispatchActionId", "dispatch", "workspaceWriteLeaseId", "write-lease");
        task.put("workflow", catalog.workflows().snapshot(task, obj("definitionId", version.get("definitionId"), "version", 1, "digest", version.get("digest")))); store.put("task", task);
        store.put("taskExecution", obj("id", "execution", "taskId", "task", "state", "RUNNING", "dispatchActionId", "dispatch", "ownerCoreSessionId", store.coreSessionId()));
        store.put("dispatchAction", obj("id", "dispatch", "taskExecutionId", "execution", "state", "CLAIMED", "dispatchCount", 1, "ownerCoreSessionId", store.coreSessionId()));
        WorkflowLedger ledger = new WorkflowLedger(store, catalog.workflows(), mapper);
        Map<String, Object> workflow = ledger.start("task", "baseline-version"); workflowId = str(workflow, "id");
        store.put("operation", obj("id", "prepare", "taskId", "task", "kind", "WORKFLOW_ROOT_SNAPSHOT", "state", "ACKNOWLEDGED"));
        ledger.prepared(workflowId, "C:/private/base-copy", "baseline-version", "prepare");
        Map<String, Object> fixedNode = objects(map(map(task, "workflow"), "dsl").get("nodes")).getFirst();
        Map<String, Object> attempt = ledger.queue(workflowId, fixedNode, List.of()); attemptId = str(attempt, "id"); runId = str(attempt, "agentRunId");
        ledger.setWorkspace(attemptId, obj("workspace", "C:/private/node-copy", "baseWorkspace", "C:/private/prepared-copy", "inputVersion", "input-version"));
        Map<String, Object> run = store.get("agentRun", runId);
        Map<String, Object> inputOperation = store.beginOperation("task", str(run, "stage"), "WORKFLOW_INPUTS", "C:/private/node-copy", runId);
        inputOperationId = str(inputOperation, "id");
        Map<String, Object> inputs = obj("baseline", obj("version", "baseline-version"));
        if (declaredInputs.contains("context")) inputs.put("context", objects(task.get("context")).stream().filter(item -> !"project-rules".equals(str(item, "id"))).toList());
        Map<String, Object> facts = obj("workspace", "C:/private/node-copy", "baseWorkspace", "C:/private/prepared-copy", "inputVersion", "input-version",
            "inputReceipts", List.of(), "inputs", inputs, "promptInputProjectionVersion", 1,
            "projectRuleSources", List.of(obj("source", "AGENTS.md", "version", "rules-version")));
        inputOperation.putAll(facts); inputOperation.putAll(obj("state", "ACKNOWLEDGED", "workerLeaseId", "input-lease", "workerId", "worker", "workerEpoch", 1)); store.put("operation", inputOperation);
        Map<String, Object> inputLease = sourceLease("input-lease", inputOperation, "WORKFLOW_INPUTS");
        inputLease.putAll(obj("state", "RELEASED", "stopState", "CONFIRMED")); store.put("workerLease", inputLease);
        ledger.inputReady(attemptId, facts, inputOperationId);
        run = store.get("agentRun", runId);
        Map<String, Object> operation = store.beginOperation("task", str(run, "stage"), "WORKFLOW_MODEL", "C:/private/node-copy", runId); operationId = str(operation, "id");
        operation.putAll(obj("state", "DISPATCHED", "workerLeaseId", "model-lease", "workerId", "worker", "workerEpoch", 1)); store.put("operation", operation);
        lease = sourceLease("model-lease", operation, "WORKFLOW_MODEL"); store.put("workerLease", lease);
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> sourceLease(String id, Map<String, Object> operation, String type) {
        Map<String, Object> result = obj("id", id, "operationKind", "operation", "operationId", operation.get("id"), "operationType", type,
            "taskId", "task", "projectId", "project", "agentRunId", runId, "workerId", "worker", "epoch", 1, "claimDigest", "private-claim-digest",
            "state", "ACTIVE", "expiresAt", Instant.now().plusSeconds(300).toString());
        ExecutionStore.copyTaskExecutionSource(result, store.get("agentRun", runId)); return result;
    }

    private void change(String kind, String id, String field, Object value) {
        Map<String, Object> record = store.get(kind, id); record.put(field, value); store.put(kind, record);
    }

    private void rejected() { assertThrows(ApiException.class, () -> projection.read(lease)); }

    @Test
    void onlyDeclaredFrozenInputsAndExactProjectRulesAreProjectedWithoutWrites() throws Exception {
        long event = store.latestEvent(); Map<String, Object> task = store.get("task", "task"), original = store.get("workflowNodeAttempt", attemptId);
        Map<String, Object> result = projection.read(lease);
        assertEquals(Set.of("identity", "node", "inputs", "projectRules"), result.keySet());
        assertEquals(Set.of("projectId", "taskId", "workflowRunId", "workflowNodeId", "workflowNodeAttemptId", "agentRunId", "inputVersion", "promptInputProjectionVersion"), map(result, "identity").keySet());
        assertEquals(Set.of("id", "kind", "role", "goal", "outputs", "acceptance", "termination"), map(result, "node").keySet());
        assertEquals(obj("baseline", obj("version", "baseline-version")), result.get("inputs"));
        assertEquals(List.of(obj("source", "AGENTS.md", "version", "rules-version", "content", "Preserve original comments")), result.get("projectRules"));
        String json = mapper.writeValueAsString(result);
        for (String secret : List.of("private-route-secret", "Undeclared root goal", "Undeclared project memory", "C:/private", "private-claim-digest", "Private actor instructions", "Ordinary memory is not a project rule")) assertFalse(json.contains(secret), secret);
        map(map(result, "inputs"), "baseline").put("version", "caller-mutated");
        assertEquals(obj("baseline", obj("version", "baseline-version")), projection.read(lease).get("inputs"));
        assertEquals(original, store.get("workflowNodeAttempt", attemptId)); assertEquals(task, store.get("task", "task")); assertEquals(event, store.latestEvent());
        assertTrue(store.list("modelRequest").isEmpty());
    }

    @Test
    void nonWorkflowOperationCannotReadAnotherAttempt() {
        lease.put("operationType", "MODEL"); rejected();
    }

    @Test
    void narrowReadScopeCannotAuthorizeUnattributedRawText() throws Exception {
        store.close(); store = null; directory = directory.resolve("narrow"); openFixture(List.of("src/**"), List.of("baseline"));
        ApiException failure = assertThrows(ApiException.class, () -> projection.read(lease));
        assertEquals(403, failure.status()); assertTrue(failure.getMessage().contains("读取范围"));
    }

    @Test
    void explicitlyDeclaredContextUsesOnlyFrozenTaskRecords() throws Exception {
        store.close(); store = null; directory = directory.resolve("context"); openFixture(List.of("**"), List.of("baseline", "context"));
        store.put("memory", obj("id", "private-memory", "projectId", "project", "status", "ACTIVE", "version", 2, "content", "Unselected live memory"));
        Map<String, Object> result = projection.read(lease);
        List<Map<String, Object>> context = objects(map(result, "inputs").get("context"));
        assertEquals(List.of("Undeclared project memory", "Ordinary memory is not a project rule", QUOTED_CONTEXT + "[REDACTED]"), context.stream().map(item -> str(item, "content")).toList());
        assertFalse(mapper.writeValueAsString(result).contains("Unselected live memory"));
        assertEquals(1, objects(result.get("projectRules")).size());
    }

    @Test
    void declaredContextRedactsKnownCredentialsAndPreservesJsonEscapes() throws Exception {
        store.close(); store = null; directory = directory.resolve("redacted"); openFixture(List.of("**"), List.of("baseline", "context"));
        Map<String, Object> result = projection.read(lease);
        Map<String, Object> item = objects(map(result, "inputs").get("context")).stream().filter(value -> "credential-memory".equals(str(value, "id"))).findFirst().orElseThrow();
        assertEquals(QUOTED_CONTEXT + "[REDACTED]", item.get("content"));
        assertFalse(mapper.writeValueAsString(result).contains(CREDENTIAL));
        assertEquals(QUOTED_CONTEXT + CREDENTIAL, objects(store.get("task", "task").get("context")).stream()
            .filter(value -> "credential-memory".equals(str(value, "id"))).findFirst().orElseThrow().get("content"));
    }

    @Test
    void credentialsContainingQuotesBackslashesAndNewlinesAreRedactedAtEveryDepth() throws Exception {
        store.close(); store = null; directory = directory.resolve("escaped-credential"); openFixture(List.of("**"), List.of("baseline", "context"));
        Map<String, Object> result = projection.read(lease);
        Map<String, Object> item = objects(map(result, "inputs").get("context")).stream().filter(value -> "credential-memory".equals(str(value, "id"))).findFirst().orElseThrow();
        assertEquals(QUOTED_CONTEXT + "[REDACTED]", item.get("escapedContent"));
        assertEquals(obj("array", List.of("[REDACTED]", 7, true, obj("deep", "[REDACTED]"))), item.get("details"));
        String encodedCredential = mapper.writeValueAsString(ESCAPED_CREDENTIAL);
        assertFalse(mapper.writeValueAsString(result).contains(encodedCredential.substring(1, encodedCredential.length() - 1)));
        Map<String, Object> original = objects(store.get("task", "task").get("context")).stream().filter(value -> "credential-memory".equals(str(value, "id"))).findFirst().orElseThrow();
        assertEquals(QUOTED_CONTEXT + ESCAPED_CREDENTIAL, original.get("escapedContent"));
        assertEquals(obj("array", List.of(ESCAPED_CREDENTIAL, 7, true, obj("deep", ESCAPED_CREDENTIAL))), original.get("details"));
    }

    @Test
    void changedCallerClaimOrStoredLeaseIsRejected() {
        lease.put("claimDigest", "another-claim"); rejected();
    }

    @Test
    void expiredAndReleasedLeaseCannotReadFrozenInputs() {
        change("workerLease", "model-lease", "expiresAt", Instant.EPOCH.toString()); rejected();
        change("workerLease", "model-lease", "expiresAt", Instant.now().plusSeconds(300).toString());
        change("workerLease", "model-lease", "state", "RELEASED"); rejected();
    }

    @Test
    void changedGoalOrAuthorizationInvalidatesOriginalWorkflowInput() {
        change("task", "task", "goal", "A later root goal"); rejected();
    }

    @Test
    void oldWorkflowGenerationAndEndedAttemptCannotRead() {
        change("task", "task", "workflowRunId", "another-generation"); rejected();
        change("task", "task", "workflowRunId", workflowId);
        change("workflowNodeAttempt", attemptId, "endedAt", now()); rejected();
    }

    @Test
    void mismatchedRunRouteAndPermissionsCannotBeSubstituted() {
        change("agentRun", runId, "routeVersion", 2); rejected();
        change("agentRun", runId, "routeVersion", 1);
        change("agentRun", runId, "permissions", obj("capabilities", List.of("read"), "readScopeLayers", List.of(List.of("src/**")), "writeScopeLayers", List.of(List.of("**")))); rejected();
    }

    @Test
    void acknowledgedInputOperationMustMatchTheFrozenProjection() {
        change("operation", inputOperationId, "inputs", obj("baseline", obj("version", "another-version"))); rejected();
    }

    @Test
    void undeclaredInputInjectionIsRejectedEvenWhenBothInputRecordsAreChanged() {
        Map<String, Object> changed = obj("baseline", obj("version", "baseline-version"), "goal", "Undeclared root goal");
        change("workflowNodeAttempt", attemptId, "inputs", changed); change("operation", inputOperationId, "inputs", changed); rejected();
    }

    @Test
    void missingProjectionMarkerAndUnknownInputWorkerReceiptAreRejected() {
        change("workflowNodeAttempt", attemptId, "promptInputProjectionVersion", 0); rejected();
        change("workflowNodeAttempt", attemptId, "promptInputProjectionVersion", 1);
        change("workerLease", "input-lease", "stopState", "UNKNOWN"); rejected();
    }

    @Test
    void staleExecutionSourceAndStoppedModelOperationAreRejected() {
        change("operation", operationId, "taskExecutionId", "previous-execution"); rejected();
        change("operation", operationId, "taskExecutionId", "execution");
        change("operation", operationId, "state", "ACKNOWLEDGED"); rejected();
    }

    @Test
    void routeOrActorRevocationStopsFurtherReads() {
        change("route", "route", "enabled", false); rejected();
        change("route", "route", "enabled", true);
        change("agentDefinition", "agent", "enabled", false); rejected();
    }
}
