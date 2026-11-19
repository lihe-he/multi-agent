package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkflowCatalogTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private WorkflowCatalog workflows;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), directory.toString()); workflows = new WorkflowCatalog(store);
        store.put("project", obj("id", "one")); store.put("project", obj("id", "two")); store.put("agentDefinition", definition(1, "", "Original instructions"));
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> definition(int version, String project, String instructions) {
        return obj("id", "agent", "version", version, "projectId", project, "routeId", "", "name", "Original agent", "role", "researcher",
            "instructions", instructions, "capabilities", List.of("read", "search"), "scope", List.of("src/**"), "maxCalls", 2, "enabled", true);
    }

    private Map<String, Object> edit(String name, String project) {
        return obj("name", name, "projectId", project, "dsl", WorkflowDslTest.graph(List.of(WorkflowDslTest.node("source", List.of()))));
    }

    @Test
    void staleEditorCannotOverwritePublishedVersionAndInputMutationCannotChangeSavedBytes() {
        Map<String, Object> input = edit("First", "one"), saved = workflows.save(input); String definitionId = str(map(saved, "definition"), "id");
        objects(map(input, "dsl").get("nodes")).getFirst().put("goal", "Later input mutation");
        assertEquals("Inspect source", str(objects(map(workflows.version(definitionId, 1), "dsl").get("nodes")).getFirst(), "goal"));
        Map<String, Object> stale = edit("Stale", "one"); stale.putAll(obj("id", definitionId, "expectedVersion", 0));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.save(stale)).status());
        assertEquals(1, workflows.versions(definitionId).size()); assertEquals("First", str(store.get("workflowDefinition", definitionId), "name"));
        assertTrue(store.list("modelRequest").isEmpty()); assertTrue(store.list("operation").isEmpty()); assertTrue(store.healthy());
    }

    @Test
    void pinnedActorVersionSurvivesEditAndWorkflowReopenWithoutUsingLatestInstructions() throws Exception {
        Map<String, Object> saved = workflows.save(edit("Original", "one")); String definitionId = str(map(saved, "definition"), "id");
        store.saveAgentDefinition(definition(2, "", "Changed live instructions"), store.get("agentDefinition", "agent"));
        Map<String, Object> second = edit("Second", "one"); second.putAll(obj("id", definitionId, "expectedVersion", 1)); workflows.save(second);
        store.close(); store = null;
        try (var reopened = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            WorkflowCatalog restored = new WorkflowCatalog(reopened);
            assertEquals(List.of(2, 1), restored.versions(definitionId).stream().map(version -> num(version, "version", 0)).toList());
            for (int version : List.of(1, 2)) {
                Map<String, Object> snapshot = restored.version(definitionId, version);
                assertEquals("Original instructions", str(objects(snapshot.get("agentDefinitions")).getFirst(), "instructions"));
                assertEquals(1, num(objects(snapshot.get("agentDefinitions")).getFirst(), "version", 0));
            }
        }
    }

    @Test
    void nodeCannotIncreaseActorCapabilitiesCallsOrImportAnotherProjectsDefinition() {
        Map<String, Object> input = edit("Denied", "one"), node = objects(map(input, "dsl").get("nodes")).getFirst();
        node.put("kind", "IMPLEMENT"); node.put("permissions", List.of("read", "write"));
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.save(input)).status());
        node.put("permissions", List.of("read")); node.put("maxCalls", 3);
        assertEquals(400, assertThrows(ApiException.class, () -> workflows.save(input)).status());
        node.put("maxCalls", 2); store.put("agentDefinition", definition(1, "two", "Other project instructions"));
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.save(input)).status());
        assertTrue(store.list("workflowDefinition").isEmpty()); assertTrue(store.list("workflowVersion").isEmpty());
    }

    @Test
    void failedCurrentPointerWriteRollsBackTheNewVersionAcrossActualSqliteReopen() throws Exception {
        Map<String, Object> saved = workflows.save(edit("First", "one")); String definitionId = str(map(saved, "definition"), "id");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite")); var sql = connection.createStatement()) {
            sql.execute("CREATE TRIGGER reject_workflow_pointer BEFORE INSERT ON entities WHEN new.kind='workflowDefinition' AND json_extract(new.body,'$.version')=2 BEGIN SELECT RAISE(ABORT,'injected pointer persistence failure'); END");
        }
        Map<String, Object> second = edit("Second", "one"); second.putAll(obj("id", definitionId, "expectedVersion", 1));
        assertEquals(503, assertThrows(ApiException.class, () -> workflows.save(second)).status()); assertFalse(store.healthy());
        store.close(); store = null;
        try (var reopened = new ExecutionStore(new ObjectMapper(), directory.toString())) {
            assertEquals(1, num(reopened.get("workflowDefinition", definitionId), "version", 0));
            assertEquals("First", str(reopened.get("workflowDefinition", definitionId), "name"));
            assertEquals(1, new WorkflowCatalog(reopened).versions(definitionId).size());
            assertNull(reopened.find("workflowVersion", definitionId + ":v2"));
        }
    }

    @Test
    void storedDslAndActorCorruptionAreReportedWithoutRepairingOrReplacingHistory() {
        Map<String, Object> saved = workflows.save(edit("First", "one")); String definitionId = str(map(saved, "definition"), "id");
        Map<String, Object> version = store.get("workflowVersion", definitionId + ":v1");
        objects(map(version, "dsl").get("nodes")).getFirst().put("goal", "Changed persisted body"); store.put("workflowVersion", version);
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.version(definitionId, 1)).status());
        assertEquals("Changed persisted body", str(objects(map(store.get("workflowVersion", definitionId + ":v1"), "dsl").get("nodes")).getFirst(), "goal"));
        version.put("agentDefinitions", List.of("not an actor record")); store.put("workflowVersion", version);
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.version(definitionId, 1)).status());
        assertEquals(List.of("not an actor record"), store.get("workflowVersion", definitionId + ":v1").get("agentDefinitions"));
    }

    @Test
    void projectOwnershipIsImmutableAndProjectListsKeepGlobalAndLocalDefinitionsSeparate() {
        Map<String, Object> first = workflows.save(edit("One", "one")); String definitionId = str(map(first, "definition"), "id");
        workflows.save(edit("Two", "two")); workflows.save(edit("Global", ""));
        assertEquals(java.util.Set.of("One", "Global"), workflows.definitions("one").stream().map(definition -> str(definition, "name")).collect(java.util.stream.Collectors.toSet()));
        Map<String, Object> moved = edit("Moved", "two"); moved.putAll(obj("id", definitionId, "expectedVersion", 1));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.save(moved)).status());
        assertEquals("one", str(store.get("workflowDefinition", definitionId), "projectId")); assertEquals(1, workflows.versions(definitionId).size());
    }

    private Map<String, Object> root() {
        Map<String, Object> route = obj("id", "route", "version", 1, "name", "Root", "engine", "API", "enabled", true,
            "baseUrl", "http://127.0.0.1:18999/v1", "model", "fixture"); store.put("route", route);
        return obj("id", "task", "projectId", "one", "routeSnapshot", route, "locks", obj(),
            "contract", obj("permissions", List.of("read", "search", "write", "verify", "delegate", "mcp"), "scope", List.of("src/**")),
            "collaboration", obj("enabled", false), "mcpAuthorization", obj("bindings", List.of()));
    }

    @Test
    void taskSnapshotPinsOriginalActorAndScopeWithoutAddingDelegationCommandsOrMcp() {
        Map<String, Object> actor = definition(1, "", "Original instructions");
        actor.put("capabilities", List.of("read", "search", "write", "verify", "delegate", "mcp")); store.put("agentDefinition", actor);
        Map<String, Object> input = edit("Scoped implementation", "one"), node = objects(map(input, "dsl").get("nodes")).getFirst();
        node.put("kind", "IMPLEMENT"); node.put("permissions", actor.get("capabilities"));
        Map<String, Object> published = workflows.save(input), version = map(published, "version"), task = root();
        Map<String, Object> snapshot = workflows.snapshot(task, obj("definitionId", version.get("definitionId"), "version", 1, "digest", version.get("digest")));
        Map<String, Object> binding = workflows.binding(snapshot, "source"), permissions = map(binding, "permissions");
        assertEquals(java.util.Set.of("read", "search", "write"), new java.util.HashSet<>(strings(permissions.get("capabilities"))));
        assertTrue(AgentPermissions.permits(permissions, "write", "src/app.java")); assertFalse(AgentPermissions.permits(permissions, "read", "outside.txt"));
        actor.put("instructions", "Later live instructions"); actor.put("version", 2); store.saveAgentDefinition(actor, store.get("agentDefinition", "agent"));
        assertEquals("Original instructions", str(map(binding, "actor"), "instructions")); assertEquals(1, num(map(binding, "actor"), "version", 0));
        map(binding, "permissions").put("capabilities", List.of("read", "write", "mcp"));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.binding(snapshot, "source")).status());
    }

    @Test
    void otherRouteRequiresExplicitConfirmationAndStillHonorsRootLocks() {
        Map<String, Object> actor = definition(1, "", "Other route actor"); actor.put("routeId", "other"); store.put("agentDefinition", actor);
        store.put("route", obj("id", "other", "version", 1, "name", "Other", "engine", "API", "enabled", true, "model", "fixture", "baseUrl", "http://127.0.0.1:19000/v1"));
        Map<String, Object> version = map(workflows.save(edit("Other route", "one")), "version"), task = root();
        Map<String, Object> selection = obj("definitionId", version.get("definitionId"), "version", 1);
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("allowedRouteIds", List.of("other"));
        assertEquals("other", str(map(workflows.binding(workflows.snapshot(task, selection), "source"), "routeSnapshot"), "id"));
        task.put("locks", obj("routeId", "route"));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
    }

    @Test
    void rollbackPublishesANewVersionAndKeepsTheOriginalHistory() {
        Map<String, Object> first = workflows.save(edit("First", "one")); String definitionId = str(map(first, "definition"), "id");
        Map<String, Object> second = edit("Second", "one"); second.putAll(obj("id", definitionId, "expectedVersion", 1));
        objects(map(second, "dsl").get("nodes")).getFirst().put("goal", "Changed goal"); workflows.save(second);
        Map<String, Object> rolled = map(workflows.rollback(definitionId, obj("expectedVersion", 2, "version", 1)), "version");
        assertEquals(3, num(rolled, "version", 0)); assertEquals("ROLLBACK", rolled.get("source"));
        assertEquals("Inspect source", str(objects(map(rolled, "dsl").get("nodes")).getFirst(), "goal"));
        assertEquals("Changed goal", str(objects(map(workflows.version(definitionId, 2), "dsl").get("nodes")).getFirst(), "goal"));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.rollback(definitionId, obj("expectedVersion", 2, "version", 1))).status());
    }
}
