package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TeamRecordsTest {
    @TempDir Path directory;

    private WorkbenchCatalog catalog(ExecutionStore store) {
        WorkspaceRuntime runtime = mock(WorkspaceRuntime.class);
        when(runtime.inspect(anyString())).thenReturn(obj("path", directory.toString(), "head", "head", "branch", "main", "dirty", false, "files", List.of()));
        WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, new PlanValidator()); catalog.defaults();
        return catalog;
    }

    private Map<String, Object> root(ExecutionStore store, WorkbenchCatalog catalog, int active, int total, int depth) {
        Map<String, Object> project = catalog.importProject(obj("name", "records", "path", directory.toString()));
        Map<String, Object> route = catalog.saveRoute(obj("name", "records", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:18901/v1"));
        int number = store.list("task").size();
        Map<String, Object> task = catalog.createTask(obj("projectId", project.get("id"), "routeId", route.get("id"), "title", "bounded team " + number, "goal", "bounded team execution " + number, "mode", "LEAD",
            "contract", obj("scope", List.of("src/"), "commands", List.of("node verify.mjs"), "acceptance", List.of("actual work")), "budget", obj("maxCalls", 16, "reserveCalls", 4),
            "collaboration", obj("enabled", true, "maxActive", active, "maxTotal", total, "maxDepth", depth)));
        store.editTask(str(task, "id"), value -> value.put("state", "RUNNING"), null, "");
        Map<String, Object> run = store.queueAgentRun(str(task, "id"), obj("id", "implement", "kind", "IMPLEMENT", "role", "developer", "readOnly", false), "workspace", "version", false);
        store.beginOperation(str(task, "id"), "implement", "MODEL", "workspace", str(run, "id"));
        String requestId = store.beginModelRequest(str(task, "id"), str(run, "id"), false, Map.of()); store.finishModelRequest(requestId, obj("state", "SUCCEEDED"));
        return obj("task", task, "run", run, "requestId", requestId);
    }

    private Map<String, Object> request(String definition) { return obj("definitionId", definition, "title", "child", "goal", "inspect scope", "scope", List.of("**")); }

    private ExecutionWorker recordWorker() { ExecutionWorker worker = mock(ExecutionWorker.class); when(worker.current(anyString())).thenReturn(true); return worker; }

    private Map<String, Object> completedChild(ExecutionStore store, Map<String, Object> root) {
        String taskId = str(map(root, "task"), "id");
        Map<String, Object> child = store.enqueueSubtask(taskId, str(map(root, "run"), "id"), str(root, "requestId"), "completed-child", request("agent-developer"));
        String runId = str(child, "agentRunId"), stage = str(store.get("agentRun", runId), "stage");
        Map<String, Object> operation = store.beginOperation(taskId, stage, "SUBAGENT_MODEL", "child-workspace", runId);
        assertEquals("RUNNING", str(store.subtask(taskId, str(child, "id")), "state"));
        store.agentRunWaiting(runId, "WAITING_CHILDREN"); assertEquals("WAITING_CHILDREN", str(store.subtask(taskId, str(child, "id")), "state"));
        store.recordEvidence(taskId, str(operation, "id"), obj("id", id(), "stage", stage, "kind", "SUBAGENT", "status", "PASSED", "version", "child-version", "resultVersion", "child-version"));
        store.finishAgentRun(runId, "SUCCEEDED", "done"); store.finishSubtask(taskId, str(child, "id"), "SUCCEEDED", "done");
        return store.subtask(taskId, str(child, "id"));
    }

    @Test
    void fixedMcpSourceUsesFrozenPermissionsAndCannotAuthorizeSharedTeamTools() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> initial = root(store, catalog, 6, 24, 3);
            String taskId = str(map(initial, "task"), "id");
            store.editTask(taskId, task -> {
                task.remove("teamId"); map(task, "collaboration").put("enabled", false);
                map(task, "mcpAuthorization").put("bindings", List.of(obj("alias", "fixed-action")));
            }, null, "");
            Map<String, Object> stage = obj("id", "fixed-mcp", "kind", "IMPLEMENT", "role", "developer", "readOnly", false);
            Map<String, Object> run = store.queueAgentRun(taskId, stage, "workspace", "version", false); String runId = str(run, "id");
            assertTrue(str(run, "teamId").isBlank()); assertTrue(AgentPermissions.has(map(run, "permissions"), "mcp"));
            store.beginOperation(taskId, "fixed-mcp", "MODEL", "workspace", runId);
            String receipt = store.beginModelRequest(taskId, runId, false, obj());
            assertEquals(403, assertThrows(ApiException.class, () -> store.agentToolSource(taskId, runId, receipt, "tool-1")).status());
            store.finishModelRequest(receipt, obj("state", "SUCCEEDED"));
            Map<String, Object> source = store.agentToolSource(taskId, runId, receipt, "tool-1");
            assertEquals(runId, source.get("agentRunId")); assertEquals(receipt, source.get("modelRequestId")); assertFalse(source.containsKey("teamId"));
            assertEquals(403, assertThrows(ApiException.class, () -> store.agentToolSource(taskId, runId, str(initial, "requestId"), "tool-1")).status());
            assertEquals(403, assertThrows(ApiException.class, () -> store.agentToolSource(taskId, runId, receipt, "")).status());
            assertEquals(403, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, runId, receipt, "delegate", request("agent-research"))).status());
            store.editTask(taskId, task -> task.put("profileSnapshot", List.of(obj("id", "read-only-developer", "role", "developer", "capabilities", List.of("read", "search")))), null, "");
            assertEquals(source, store.agentToolSource(taskId, runId, receipt, "tool-1"), "an existing run keeps its original frozen grant");
            Map<String, Object> denied = store.queueAgentRun(taskId, obj("id", "denied-mcp", "kind", "IMPLEMENT", "role", "developer", "readOnly", false), "workspace", "version", false);
            String deniedRun = str(denied, "id"); store.beginOperation(taskId, "denied-mcp", "MODEL", "workspace", deniedRun);
            String deniedReceipt = store.beginModelRequest(taskId, deniedRun, false, obj()); store.finishModelRequest(deniedReceipt, obj("state", "SUCCEEDED"));
            assertEquals(403, assertThrows(ApiException.class, () -> store.agentToolSource(taskId, deniedRun, deniedReceipt, "tool-2")).status());
            store.finishAgentRun(runId, "SUCCEEDED", "completed");
            assertEquals(409, assertThrows(ApiException.class, () -> store.agentToolSource(taskId, runId, receipt, "tool-1")).status());
            assertTrue(store.list("subtask").isEmpty()); assertTrue(store.list("mcpOperation").isEmpty());
        }
    }

    @Test
    void stableIdentityCumulativeLimitsAndScopeIntersectionSurviveReopen() throws Exception {
        String taskId, firstId;
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            Map<String, Object> root = root(store, catalog(store), 2, 2, 1); taskId = str(map(root, "task"), "id"); String parent = str(map(root, "run"), "id"), receipt = str(root, "requestId");
            Map<String, Object> child = store.enqueueSubtask(taskId, parent, receipt, "create-1", request("agent-developer")); firstId = str(child, "id");
            assertEquals(firstId, store.enqueueSubtask(taskId, parent, receipt, "create-1", request("agent-developer")).get("id"));
            assertEquals(1, num(map(store.get("task", taskId), "collaboration"), "spawned", 0));
            assertEquals(1, num(map(store.get("task", taskId), "budget"), "usedCalls", 0), "creation is not a model request");
            assertTrue(AgentPermissions.has(map(child, "permissions"), "write")); assertFalse(AgentPermissions.has(map(child, "permissions"), "verify"), "definition cannot expand its parent capabilities");
            assertTrue(AgentPermissions.permits(map(child, "permissions"), "write", "src/file.java")); assertFalse(AgentPermissions.permits(map(child, "permissions"), "write", "other/file.java"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, parent, receipt, "create-2", request("agent-research"))).status());
            String childRun = str(child, "agentRunId"); store.beginOperation(taskId, str(store.get("agentRun", childRun), "stage"), "MODEL", "child-workspace", childRun);
            String childReceipt = store.beginModelRequest(taskId, childRun, false, Map.of()); store.finishModelRequest(childReceipt, obj("state", "SUCCEEDED"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, childRun, childReceipt, "recursive", request("agent-research"))).status());
            store.finishAgentRun(childRun, "SUCCEEDED", "done"); store.finishSubtask(taskId, firstId, "SUCCEEDED", "done");
            Map<String, Object> second = store.enqueueSubtask(taskId, parent, receipt, "create-2", request("agent-research"));
            store.finishAgentRun(str(second, "agentRunId"), "CANCELLED", "cancelled"); store.finishSubtask(taskId, str(second, "id"), "CANCELLED", "cancelled");
            assertEquals(409, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, parent, receipt, "create-3", request("agent-research"))).status());
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishSubtask(taskId, firstId, "FAILED", "overwrite")).status());
        }
        try (var reopened = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            assertEquals("SUCCEEDED", str(reopened.subtask(taskId, firstId), "state"));
            assertEquals(2, num(map(reopened.get("task", taskId), "collaboration"), "spawned", 0));
            assertEquals(2, num(map(reopened.get("task", taskId), "budget"), "usedCalls", 0));
        }
    }

    @Test
    void failedEnqueueRollsBackChildRunAndCumulativeRootCounter() throws Exception {
        String taskId;
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            Map<String, Object> root = root(store, catalog(store), 6, 24, 3); taskId = str(map(root, "task"), "id");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_child BEFORE INSERT ON entities WHEN new.kind='subtask' BEGIN SELECT RAISE(ABORT,'injected child persistence failure'); END");
            }
            assertEquals(503, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, str(map(root, "run"), "id"), str(root, "requestId"), "create", request("agent-research"))).status());
            assertFalse(store.healthy());
        }
        try (var reopened = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            assertTrue(reopened.list("subtask").isEmpty()); assertEquals(1, reopened.list("agentRun").size()); assertEquals(0, num(map(reopened.get("task", taskId), "collaboration"), "spawned", -1));
        }
    }

    @Test
    void messagesAndDefinitionsCannotForgeSourceOrReplaceFixedVersion() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> root = root(store, catalog, 6, 24, 3);
            String taskId = str(map(root, "task"), "id"), runId = str(map(root, "run"), "id"), receipt = str(root, "requestId");
            Map<String, Object> message = store.teamMessage(taskId, "USER", runId, "user-message-1", "literal <script>data</script>", "", "");
            assertEquals(message.get("id"), store.teamMessage(taskId, "USER", runId, "user-message-1", "literal <script>data</script>", "", "").get("id"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.teamMessage(taskId, "USER", runId, "user-message-1", "different", "", "")).status());
            String pending = store.beginModelRequest(taskId, runId, false, Map.of());
            assertEquals(403, assertThrows(ApiException.class, () -> store.teamMessage(taskId, runId, "", "forged", "data", pending, "tool")).status());
            store.finishModelRequest(pending, obj("state", "FAILED"));
            Map<String, Object> fromAgent = store.teamMessage(taskId, runId, "", "agent-message", "observed source", receipt, "send"); assertEquals(receipt, fromAgent.get("requestId"));
            assertEquals(2, objects(store.teamMessages(taskId, 0, 0, 40, "").get("messages")).size());
            Map<String, Object> old = store.get("agentDefinition", "agent-research"), edit = new java.util.LinkedHashMap<>(old); edit.put("expectedVersion", 1); edit.put("instructions", "new instructions");
            assertEquals(2, num(catalog.saveAgentDefinition(edit), "version", 0));
            assertEquals(409, assertThrows(ApiException.class, () -> catalog.saveAgentDefinition(edit)).status());
            Map<String, Object> fixed = objects(map(store.get("task", taskId), "collaboration").get("definitions")).stream().filter(value -> "agent-research".equals(str(value, "id"))).findFirst().orElseThrow();
            assertEquals(1, num(fixed, "version", 0)); assertEquals(old.get("instructions"), fixed.get("instructions"));
            Map<String, Object> changed = new java.util.LinkedHashMap<>(request("agent-research")); changed.put("scope", List.of("../escape"));
            assertEquals(400, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, runId, receipt, "invalid-scope", changed)).status());
        }
    }

    @Test
    void terminalFactsAndDispatchedAdoptionCannotBeRewrittenOrReplayed() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString()); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> root = root(store, catalog, 6, 24, 3), child = completedChild(store, root);
            String taskId = str(map(root, "task"), "id"), parent = str(map(root, "run"), "id"), receipt = str(root, "requestId"), childId = str(child, "id");
            for (String fact : List.of("workspace", "baseWorkspace", "state", "resultVersion", "integrationState")) assertEquals(409, assertThrows(ApiException.class, () -> store.updateSubtask(taskId, childId, obj(fact, "changed"))).status());
            Map<String, Object> input = obj("resultVersion", child.get("resultVersion"), "targetWorkspace", "workspace");
            Map<String, Object> operation = store.beginTeamOperation(taskId, parent, receipt, "adopt-1", "ADOPT_SUBAGENT", childId, input);
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishTeamOperation(str(operation, "id"), "ACKNOWLEDGED", obj("resultVersion", "parent-version"))).status());
            store.finishTeamOperation(str(operation, "id"), "DISPATCHED", obj());
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishTeamOperation(str(operation, "id"), "DISPATCHED", obj())).status());
            WorkspaceRuntime integrator = mock(WorkspaceRuntime.class);
            TeamCoordinator coordinator = new TeamCoordinator(store, catalog, integrator, new ResourceManager(), new ObjectMapper(), null, workers, recordWorker());
            var session = coordinator.session(taskId, parent, "workspace", map(store.get("task", taskId), "routeSnapshot"), () -> false);
            assertEquals("UNKNOWN", str(session.invoke("adopt_agent_changes", obj("subtaskId", childId), receipt, "adopt-1"), "state"));
            store.finishTeamOperation(str(operation, "id"), "UNKNOWN", obj("error", "lost receipt"));
            assertEquals("UNKNOWN", str(session.invoke("adopt_agent_changes", obj("subtaskId", childId), receipt, "adopt-1"), "state"));
            assertEquals("UNKNOWN", str(session.invoke("adopt_agent_changes", obj("subtaskId", childId), receipt, "adopt-2"), "state"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.beginTeamOperation(taskId, parent, receipt, "adopt-2", "ADOPT_SUBAGENT", childId, input)).status());
            verifyNoInteractions(integrator);
            Map<String, Object> reconciled = store.get("teamOperation", str(operation, "id")); reconciled.put("state", "RECONCILED"); reconciled.put("reconciliation", obj("actor", "USER", "note", "confirmed local files")); store.put("teamOperation", reconciled);
            Map<String, Object> adopted = store.beginTeamOperation(taskId, parent, receipt, "adopt-2", "ADOPT_SUBAGENT", childId, input);
            store.finishTeamOperation(str(adopted, "id"), "DISPATCHED", obj()); store.finishTeamOperation(str(adopted, "id"), "ACKNOWLEDGED", obj("state", "ACKNOWLEDGED", "resultVersion", "parent-version"));
            Map<String, Object> finished = store.subtask(taskId, childId); assertEquals("child-version", finished.get("resultVersion")); assertEquals("ADOPTED", finished.get("integrationState")); assertEquals(1, objects(finished.get("integrations")).size());
            assertEquals(adopted.get("id"), store.beginTeamOperation(taskId, parent, receipt, "adopt-2", "ADOPT_SUBAGENT", childId, input).get("id"));
            assertEquals(409, assertThrows(ApiException.class, () -> store.beginTeamOperation(taskId, parent, receipt, "adopt-2", "ADOPT_SUBAGENT", childId, obj("resultVersion", "changed", "targetWorkspace", "workspace"))).status());
            assertEquals(409, assertThrows(ApiException.class, () -> store.finishTeamOperation(str(adopted, "id"), "UNKNOWN", obj())).status()); assertTrue(store.healthy());
            Map<String, Object> reader = store.enqueueSubtask(taskId, parent, receipt, "readonly-child", request("agent-reviewer"));
            store.finishAgentRun(str(reader, "agentRunId"), "SUCCEEDED", "readonly observation"); store.finishSubtask(taskId, str(reader, "id"), "SUCCEEDED", "readonly observation");
            assertEquals("REJECTED", str(session.invoke("adopt_agent_changes", obj("subtaskId", reader.get("id")), receipt, "adopt-readonly"), "state"));
            assertEquals("NOT_ADOPTED", store.subtask(taskId, str(reader, "id")).get("integrationState")); verifyNoInteractions(integrator);
        }
    }

    @Test
    void firstInboxPageDoesNotSkipOlderMessagesAndIndependentProposalHasNoSharedInput() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString()); var workers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> root = root(store, catalog, 6, 24, 3);
            String taskId = str(map(root, "task"), "id"), parent = str(map(root, "run"), "id");
            for (int index = 1; index <= 45; index++) store.teamMessage(taskId, "USER", "", "message-" + index, "data " + index, "", "");
            List<Map<String, Object>> first = store.teamInbox(taskId, 0, parent); assertEquals(40, first.size()); assertEquals(1, num(first.getFirst(), "sequence", 0)); assertEquals(40, num(first.getLast(), "sequence", 0));
            assertEquals(5, store.teamInbox(taskId, 40, parent).size());
            Map<String, Object> latest = store.teamMessages(taskId, 0, 0, 40, ""); assertEquals(6, num(latest, "cursor", 0));
            store.teamMessage(taskId, "USER", "", "message-46", "new data", "", "");
            List<Map<String, Object>> older = objects(store.teamMessages(taskId, 0, num(latest, "cursor", 0), 40, "").get("messages")); assertEquals(5, older.size()); assertEquals(5, num(older.getFirst(), "sequence", 0));
            Map<String, Object> proposal = store.queueAgentRun(taskId, obj("id", "proposal", "kind", "PROPOSAL", "role", "architect", "readOnly", true), "workspace", "version", false);
            String runId = str(proposal, "id"); store.beginOperation(taskId, "proposal", "MODEL", "workspace", runId);
            String receipt = store.beginModelRequest(taskId, runId, false, obj()); store.finishModelRequest(receipt, obj("state", "SUCCEEDED"));
            assertEquals(403, assertThrows(ApiException.class, () -> store.teamMessage(taskId, runId, "", "proposal-message", "secret proposal", receipt, "send")).status());
            assertTrue(store.teamInbox(taskId, 0, runId).isEmpty());
            var session = new TeamCoordinator(store, catalog, mock(WorkspaceRuntime.class), new ResourceManager(), new ObjectMapper(), null, workers, recordWorker()).session(taskId, runId, "workspace", map(store.get("task", taskId), "routeSnapshot"), () -> false);
            assertTrue(session.definitions().isEmpty()); assertTrue(session.inbox().isEmpty()); assertEquals("REJECTED", str(session.invoke("send_team_message", obj("content", "secret proposal"), receipt, "send"), "state"));
            Map<String, Object> other = root(store, catalog, 6, 24, 3);
            assertEquals(404, assertThrows(ApiException.class, () -> store.teamMessages(taskId, 0, 0, 40, str(map(other, "run"), "id"))).status()); assertTrue(store.healthy());
            var parentSession = new TeamCoordinator(store, catalog, mock(WorkspaceRuntime.class), new ResourceManager(), new ObjectMapper(), null, workers, recordWorker()).session(taskId, parent, "workspace", map(store.get("task", taskId), "routeSnapshot"), () -> false);
            Map<String, Object> longIdMessage = parentSession.invoke("send_team_message", obj("content", "long tool identity"), str(root, "requestId"), "x".repeat(200));
            assertEquals("ACKNOWLEDGED", str(longIdMessage, "state")); assertEquals("x".repeat(200), map(longIdMessage, "message").get("toolCallId"));
            assertEquals(map(longIdMessage, "message").get("id"), map(parentSession.invoke("send_team_message", obj("content", "long tool identity"), str(root, "requestId"), "x".repeat(200)), "message").get("id"));
        }
    }

    @Test
    void adoptionReceiptAndIntegrationFactsRollBackTogetherOnPersistenceFailure() throws Exception {
        String taskId, childId, operationId;
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            Map<String, Object> root = root(store, catalog(store), 6, 24, 3), child = completedChild(store, root);
            taskId = str(map(root, "task"), "id"); childId = str(child, "id");
            Map<String, Object> operation = store.beginTeamOperation(taskId, str(map(root, "run"), "id"), str(root, "requestId"), "adopt", "ADOPT_SUBAGENT", childId, obj("resultVersion", child.get("resultVersion"), "targetWorkspace", "workspace")); operationId = str(operation, "id");
            store.finishTeamOperation(operationId, "DISPATCHED", obj());
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
                sql.execute("CREATE TRIGGER reject_integration BEFORE UPDATE ON entities WHEN new.kind='subtask' AND json_extract(new.body,'$.integrationState')='ADOPTED' BEGIN SELECT RAISE(ABORT,'injected integration persistence failure'); END");
            }
            assertEquals(503, assertThrows(ApiException.class, () -> store.finishTeamOperation(operationId, "ACKNOWLEDGED", obj("state", "ACKNOWLEDGED", "resultVersion", "parent-version"))).status()); assertFalse(store.healthy());
        }
        try (var reopened = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            assertEquals("DISPATCHED", str(reopened.get("teamOperation", operationId), "state")); assertEquals("NOT_ADOPTED", reopened.subtask(taskId, childId).get("integrationState"));
            WorkspaceRuntime runtime = mock(WorkspaceRuntime.class); WorkbenchCatalog catalog = new WorkbenchCatalog(reopened, runtime, new PlanValidator());
            WorkbenchEngine engine = new WorkbenchEngine(reopened, catalog, runtime, new ResourceManager(), new ObjectMapper());
            try { engine.recover(); assertEquals("UNKNOWN", str(reopened.get("teamOperation", operationId), "state")); assertEquals("SUCCEEDED", str(reopened.subtask(taskId, childId), "state")); verifyNoInteractions(runtime); }
            finally { engine.shutdown(); }
        }
    }

    @Test
    void selectedDefinitionsCannotWidenRouteLocksOrAcceptMalformedDelegationLimits() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> root = root(store, catalog, 6, 24, 3), task = store.get("task", str(map(root, "task"), "id")), route = map(task, "routeSnapshot");
            Map<String, Object> alternative = catalog.saveRoute(obj("name", "separately authorized", "engine", "API", "model", "fixture", "baseUrl", "http://127.0.0.1:18902/v1"));
            Map<String, Object> edit = new java.util.LinkedHashMap<>(store.get("agentDefinition", "agent-research")); edit.put("expectedVersion", 1); edit.put("routeId", alternative.get("id")); catalog.saveAgentDefinition(edit);
            Map<String, Object> selection = obj("enabled", true, "definitionIds", List.of("agent-research"), "allowedRouteIds", List.of(alternative.get("id")));
            assertEquals(409, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj("routeId", route.get("id")), selection)).status());
            assertEquals(409, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj(), obj("enabled", true, "definitionIds", List.of("agent-research")))).status());
            Map<String, Object> authorized = CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj(), selection); assertEquals(alternative.get("id"), map(objects(authorized.get("definitions")).getFirst(), "routeSnapshot").get("id"));
            Map<String, Object> stale = new java.util.LinkedHashMap<>(selection); stale.put("definitionVersions", obj("agent-research", 1)); assertEquals(409, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj(), stale)).status());
            stale.put("definitionVersions", obj("agent-research", 2)); assertEquals(2, num(objects(CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj(), stale).get("definitions")).getFirst(), "version", 0));
            for (Map<String, Object> invalid : List.of(obj("enabled", true, "definitionIds", List.of()), obj("enabled", true, "definitionIds", "agent-research"), obj("enabled", true, "maxActive", "3"), obj("enabled", true, "maxDepth", 1.5))) assertEquals(400, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), route, obj(), invalid)).status());
            assertEquals(400, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), obj("engine", "CODEX"), obj(), obj("enabled", true))).status());
            Map<String, Object> parent = obj("capabilities", List.of("read", "write", "verify", "mcp"), "readScopeLayers", List.of(List.of("**")), "writeScopeLayers", List.of(List.of("**")));
            Map<String, Object> definition = obj("capabilities", List.of("read", "write", "verify", "mcp"), "scope", List.of("**"));
            Map<String, Object> narrow = AgentPermissions.narrow(parent, definition, obj("scope", List.of("src/"))); assertTrue(AgentPermissions.has(narrow, "write")); assertFalse(AgentPermissions.has(narrow, "verify")); assertFalse(AgentPermissions.has(narrow, "mcp"));
            assertTrue(AgentPermissions.has(AgentPermissions.narrow(parent, definition, obj("scope", List.of("**"))), "mcp"));
            Map<String, Object> malformed = request("agent-developer"); malformed.put("dependsOn", "not-a-list"); assertEquals(400, assertThrows(ApiException.class, () -> store.enqueueSubtask(str(task, "id"), str(map(root, "run"), "id"), str(root, "requestId"), "malformed", malformed)).status()); assertTrue(store.healthy());
        }
    }

    @Test
    void malformedDefinitionsAndUnresolvedRootEffectsDoNotAdmitNewChildren() throws Exception {
        try (var store = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            WorkbenchCatalog catalog = catalog(store); Map<String, Object> root = root(store, catalog, 6, 24, 3), task = store.get("task", str(map(root, "task"), "id"));
            String taskId = str(task, "id"), parent = str(map(root, "run"), "id"), receipt = str(root, "requestId");
            Map<String, Object> original = store.get("agentDefinition", "agent-developer");
            for (Map<String, Object> bad : List.of(obj("maxCalls", 1.5), obj("maxCalls", "4"), obj("expectedVersion", 1.5), obj("enabled", "true"))) {
                Map<String, Object> edit = new java.util.LinkedHashMap<>(original); edit.put("expectedVersion", 1); edit.putAll(bad);
                assertEquals(400, assertThrows(ApiException.class, () -> catalog.saveAgentDefinition(edit)).status());
                assertEquals(1, num(store.get("agentDefinition", "agent-developer"), "version", 0));
            }
            assertEquals(400, assertThrows(ApiException.class, () -> CollaborationPolicy.snapshot(store, str(task, "projectId"), map(task, "routeSnapshot"), obj(), obj("enabled", "true"))).status());
            for (Object badScope : new Object[] {null, "src/", List.of(12)}) {
                Map<String, Object> body = obj("projectId", task.get("projectId"), "routeId", task.get("routeId"), "title", "malformed scope", "goal", "malformed scope input",
                    "contract", obj("scope", badScope, "acceptance", List.of("preserve explicit boundary"), "commands", List.of("node verify.mjs")));
                assertEquals(400, assertThrows(ApiException.class, () -> catalog.createTask(body)).status());
            }
            assertEquals(400, assertThrows(ApiException.class, () -> catalog.createTask(obj("collaboration", "true"))).status());
            for (Map<String, Object> bad : List.of(obj("scope", null), obj("scope", "src/"), obj("maxCalls", 1.5), obj("maxCalls", "2"), obj("dependsOn", List.of(12)))) {
                Map<String, Object> input = new java.util.LinkedHashMap<>(request("agent-developer")); input.putAll(bad);
                assertEquals(400, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, parent, receipt, "invalid-admission", input)).status());
            }
            Map<String, Object> disabled = new java.util.LinkedHashMap<>(original); disabled.put("expectedVersion", 1); disabled.put("enabled", false); catalog.saveAgentDefinition(disabled);
            assertEquals(409, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, parent, receipt, "disabled-definition", request("agent-developer"))).status());
            Map<String, Object> restored = new java.util.LinkedHashMap<>(store.get("agentDefinition", "agent-developer")); restored.put("expectedVersion", 2); restored.put("enabled", true); catalog.saveAgentDefinition(restored);
            Map<String, Object> unknown = store.beginOperation(taskId, "implement", "TEST_UNCONFIRMED", "workspace"); store.markOperation(str(unknown, "id"), "UNKNOWN", obj());
            assertTrue(store.hasUnknownEffects(taskId)); assertEquals(409, assertThrows(ApiException.class, () -> store.enqueueSubtask(taskId, parent, receipt, "fresh-id", request("agent-developer"))).status());
            assertTrue(store.list("subtask").isEmpty()); assertEquals(0, num(map(store.get("task", taskId), "collaboration"), "spawned", -1)); assertEquals(1, store.list("task").size()); assertTrue(store.healthy());
        }
    }
}
