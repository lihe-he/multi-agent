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

    private Map<String, Object> versionSelection(Map<String, Object> version) {
        return obj("definitionId", version.get("definitionId"), "version", version.get("version"), "digest", version.get("digest"));
    }

    private Map<String, Object> otherRoute(String actorId) {
        Map<String, Object> actor = definition(1, "", "Other route actor");
        actor.putAll(obj("id", actorId, "routeId", "other"));
        store.put("agentDefinition", actor);
        return store.put("route", obj("id", "other", "version", 1, "name", "Other", "engine", "API", "enabled", true,
            "provider", "other-provider", "model", "fixture", "baseUrl", "http://127.0.0.1:19000/v1"));
    }

    private Map<String, Map<String, Object>> builtinActors() {
        Map<String, Map<String, Object>> actors = new java.util.LinkedHashMap<>();
        for (String role : List.of("research", "developer", "reviewer")) {
            Map<String, Object> actor = definition(1, "", "Original built-in " + role + " instructions");
            actor.putAll(obj("id", "agent-" + role, "role", role.equals("research") ? "researcher" : role,
                "capabilities", role.equals("developer") ? List.of("read", "search", "write", "verify", "delegate", "mcp") : List.of("read", "search")));
            store.put("agentDefinition", actor);
            actors.put(str(actor, "id"), actor);
        }
        return actors;
    }

    private Map<String, Object> builtinFirstVersion(String mode, Map<String, Map<String, Object>> actors) {
        Map<String, Object> published = workflows.save(obj("id", WorkflowTemplates.id(mode), "name", WorkflowTemplates.name(mode),
            "projectId", "", "dsl", WorkflowTemplates.dsl(mode, actors)));
        Map<String, Object> definition = map(published, "definition"), version = map(published, "version");
        definition.put("builtin", true);
        version.put("source", "BUILTIN");
        store.put("workflowDefinition", definition);
        store.put("workflowVersion", version);
        return store.get("workflowVersion", str(version, "id"));
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
        Map<String, Object> other = otherRoute("agent");
        Map<String, Object> version = map(workflows.save(edit("Other route", "one")), "version"), task = root();
        Map<String, Object> selection = versionSelection(version);
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("allowedRouteIds", List.of("other"));
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.remove("allowedRouteIds");
        selection.put("allowedRouteVersions", obj("other", 1));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("rootRouteVersion", 1);
        selection.remove("digest");
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("digest", version.get("digest"));
        Map<String, Object> binding = workflows.binding(workflows.snapshot(task, selection), "source");
        assertEquals(other, map(binding, "routeSnapshot"));
        assertEquals(List.of("read", "search"), strings(map(binding, "permissions").get("capabilities")));
        assertFalse(AgentPermissions.permits(map(binding, "permissions"), "read", "outside.txt"));
        selection.put("allowedRouteVersions", obj("other", 1, "unused", 1));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("allowedRouteVersions", obj("other", 1));
        task.put("locks", obj("routeId", "route"));
        Map<String, Object> preview = workflows.readiness(task, versionSelection(version));
        assertTrue(strings(objects(preview.get("nodes")).getFirst().get("blockers")).stream().anyMatch(reason -> reason.contains("根锁定")));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
    }

    @Test
    void readinessShowsDeclaredInputsAndRoutesWithoutWritingOrExposingTaskContent() {
        otherRoute("alternate-actor");
        Map<String, Object> source = WorkflowDslTest.node("source", List.of());
        source.put("inputs", List.of("goal", "context"));
        Map<String, Object> consumer = WorkflowDslTest.node("consumer", List.of("source"));
        consumer.putAll(obj("definitionId", "alternate-actor", "inputs", List.of("source:result", "contract")));
        Map<String, Object> version = map(workflows.save(obj("name", "Readiness", "projectId", "one", "dsl", WorkflowDslTest.graph(List.of(source, consumer)))), "version");
        Map<String, Object> selection = versionSelection(version), task = root();
        task.putAll(obj("goal", "private goal fixture", "context", List.of(obj("content", "private memory fixture")), "budget", obj("usedCalls", 2, "reserveCalls", 1)));
        store.put("task", task);
        Map<String, Object> before = store.get("task", "task");
        Map<String, Object> preview = workflows.readiness(task, selection);
        assertEquals("route", str(map(preview, "rootRoute"), "id"));
        assertEquals(1, num(map(preview, "rootRoute"), "version", -1));
        assertTrue(strings(preview.get("blockers")).isEmpty());
        Map<String, Map<String, Object>> nodes = WorkflowDsl.byId(objects(preview.get("nodes")));
        assertEquals("Node consumer", str(nodes.get("consumer"), "title"));
        assertEquals("route", str(map(nodes.get("source"), "route"), "id"));
        assertEquals(List.of("goal", "context"), strings(nodes.get("source").get("rootInputs")));
        assertEquals(List.of("source:result", "contract"), strings(nodes.get("consumer").get("inputs")));
        assertEquals(List.of("contract"), strings(nodes.get("consumer").get("rootInputs")));
        assertEquals("other", str(map(nodes.get("consumer"), "route"), "id"));
        assertEquals(List.of(map(nodes.get("consumer"), "route")), objects(preview.get("extraRoutes")));
        assertEquals("other-provider", str(objects(preview.get("extraRoutes")).getFirst(), "provider"));
        assertFalse(preview.toString().contains("private goal fixture"));
        assertFalse(preview.toString().contains("private memory fixture"));
        Map<String, Object> otherProject = obj("projectId", "two", "routeSnapshot", task.get("routeSnapshot"));
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.readiness(otherProject, selection)).status());
        assertEquals(before, task);
        assertEquals(before, store.get("task", "task"));
        assertTrue(store.list("operation").isEmpty());
        assertTrue(store.list("modelRequest").isEmpty());
        assertTrue(store.list("agentRun").isEmpty());
        assertTrue(store.list("workflowRun").isEmpty());
    }

    @Test
    void readinessReportsUnsupportedNativeRoutesAndSelectionCannotEnableThem() {
        Map<String, Object> other = otherRoute("agent");
        Map<String, Object> version = map(workflows.save(edit("Native blocked", "one")), "version"), task = root();
        other.putAll(obj("version", 2, "engine", "CODEX", "executable", "private-native-executable", "baseUrl", ""));
        store.put("route", other);
        Map<String, Object> selection = versionSelection(version), preview = workflows.readiness(task, selection);
        Map<String, Object> node = objects(preview.get("nodes")).getFirst();
        assertEquals("CODEX", str(map(node, "route"), "engine"));
        assertTrue(strings(node.get("blockers")).stream().anyMatch(reason -> reason.contains("原生 CLI")));
        assertTrue(strings(preview.get("blockers")).stream().anyMatch(reason -> reason.contains("Node source") && reason.contains("原生 CLI")));
        assertFalse(preview.toString().contains("private-native-executable"));
        selection.putAll(obj("rootRouteVersion", 1, "allowedRouteVersions", obj("other", 2)));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
    }

    @Test
    void readinessRequiresExactVersionAndDigestAndCannotGrantSendingAuthorization() {
        otherRoute("agent");
        Map<String, Object> version = map(workflows.save(edit("Fixed readiness input", "one")), "version"), task = root();
        Map<String, Object> selection = versionSelection(version);
        assertDoesNotThrow(() -> workflows.readiness(task, selection));
        selection.put("digest", "stale-definition-digest");
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.readiness(task, selection)).status());
        selection.remove("digest");
        assertEquals(400, assertThrows(ApiException.class, () -> workflows.readiness(task, selection)).status());
        selection.putAll(obj("digest", version.get("digest"), "allowedRouteVersions", obj("other", 1)));
        assertEquals(400, assertThrows(ApiException.class, () -> workflows.readiness(task, selection)).status());
        assertEquals(403, assertThrows(ApiException.class, () -> workflows.snapshot(task, versionSelection(version))).status());
        assertTrue(store.list("task").isEmpty());
        assertTrue(store.list("operation").isEmpty());
        assertTrue(store.list("modelRequest").isEmpty());
    }

    @Test
    void recipientVersionConfirmationRejectsStringsFractionsAndUnknownRecipients() {
        otherRoute("agent");
        Map<String, Object> version = map(workflows.save(edit("Version types", "one")), "version"), task = root();
        Map<String, Object> selection = versionSelection(version);
        selection.putAll(obj("rootRouteVersion", 1, "allowedRouteVersions", obj("other", 1)));
        for (Object invalid : List.of("1", 1.5, 0, -1, true)) {
            selection.put("allowedRouteVersions", obj("other", invalid));
            assertEquals(400, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        }
        selection.put("allowedRouteVersions", obj("other", 1));
        for (Object invalid : List.of("1", 1.5, 0, -1, true)) {
            selection.put("rootRouteVersion", invalid);
            assertEquals(400, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        }
        selection.putAll(obj("rootRouteVersion", 1, "allowedRouteVersions", obj("other", 1, "route", 1)));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("allowedRouteVersions", obj("other", 1));
        selection.put("digest", "stale-definition-digest");
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        assertTrue(store.list("operation").isEmpty());
        assertTrue(store.list("modelRequest").isEmpty());
    }

    @Test
    void staleAlternateVersionCannotChangePreviouslyPinnedWorkflow() {
        Map<String, Object> other = otherRoute("agent");
        Map<String, Object> version = map(workflows.save(edit("Route edit", "one")), "version"), task = root();
        Map<String, Object> selection = versionSelection(version);
        selection.putAll(obj("rootRouteVersion", 1, "allowedRouteVersions", obj("other", 1)));
        Map<String, Object> pinned = workflows.snapshot(task, selection);
        task.put("workflow", pinned);
        store.put("task", task);
        other.putAll(obj("version", 2, "model", "fixture-v2", "baseUrl", "http://127.0.0.1:19001/v1"));
        store.put("route", other);
        Map<String, Object> preview = workflows.readiness(task, versionSelection(version));
        assertEquals(2, num(objects(preview.get("extraRoutes")).getFirst(), "version", -1));
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        Map<String, Object> saved = map(store.get("task", "task"), "workflow");
        assertEquals(pinned, saved);
        assertEquals(1, num(map(workflows.binding(saved, "source"), "routeSnapshot"), "version", -1));
        selection.put("allowedRouteVersions", obj("other", 2));
        selection.put("rootRouteVersion", 2);
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
        selection.put("rootRouteVersion", 1);
        assertEquals(other, map(workflows.binding(workflows.snapshot(task, selection), "source"), "routeSnapshot"));
        assertEquals(pinned, map(store.get("task", "task"), "workflow"));
    }

    @Test
    void explicitRootUsesOriginalTaskRouteRevisionAndStillRequiresLiveEnablement() {
        Map<String, Object> actor = definition(1, "", "Actor explicitly names the root route");
        actor.put("routeId", "route");
        store.put("agentDefinition", actor);
        Map<String, Object> version = map(workflows.save(edit("Explicit root", "one")), "version"), task = root();
        Map<String, Object> original = map(task, "routeSnapshot"), live = new java.util.LinkedHashMap<>(original);
        live.putAll(obj("version", 2, "model", "new-live-model", "baseUrl", "http://127.0.0.1:19002/v1"));
        store.put("route", live);
        Map<String, Object> selection = versionSelection(version), preview = workflows.readiness(task, selection);
        assertEquals(List.of(), preview.get("extraRoutes"));
        assertEquals(map(preview, "rootRoute"), map(objects(preview.get("nodes")).getFirst(), "route"));
        assertEquals(1, num(map(preview, "rootRoute"), "version", -1));
        assertEquals(original, map(workflows.binding(workflows.snapshot(task, selection), "source"), "routeSnapshot"));
        selection.put("allowedRouteIds", List.of());
        assertEquals(original, map(workflows.binding(workflows.snapshot(task, selection), "source"), "routeSnapshot"));
        live.put("enabled", false);
        store.put("route", live);
        assertFalse(strings(workflows.readiness(task, versionSelection(version)).get("blockers")).isEmpty());
        assertEquals(409, assertThrows(ApiException.class, () -> workflows.snapshot(task, selection)).status());
    }

    @Test
    void defaultsUpgradeOnlyImplementationInputsAcrossAllThreeBuiltinsAndKeepVersionOneSnapshots() {
        Map<String, Map<String, Object>> actors = builtinActors();
        Map<String, Map<String, Object>> originals = new java.util.LinkedHashMap<>(), tasks = new java.util.LinkedHashMap<>();
        for (String mode : WorkflowTemplates.MODES) {
            Map<String, Object> original = builtinFirstVersion(mode, actors), task = root();
            task.put("id", "task-" + mode.toLowerCase(java.util.Locale.ROOT));
            task.put("workflow", workflows.snapshot(task, versionSelection(original)));
            store.put("task", task);
            originals.put(mode, original);
            tasks.put(mode, task);
        }
        workflows.defaults();
        for (String mode : WorkflowTemplates.MODES) {
            String definitionId = WorkflowTemplates.id(mode);
            Map<String, Object> first = originals.get(mode), second = workflows.version(definitionId, 2);
            assertEquals(2, num(store.get("workflowDefinition", definitionId), "version", -1));
            assertEquals(first, workflows.version(definitionId, 1));
            assertEquals("BUILTIN", str(second, "source"));
            assertEquals(str(first, "id"), str(second, "sourceVersionId"));
            assertEquals(first.get("agentDefinitions"), second.get("agentDefinitions"));
            assertNotEquals(first.get("digest"), second.get("digest"));
            Map<String, Map<String, Object>> originalNodes = WorkflowDsl.byId(objects(map(first, "dsl").get("nodes")));
            for (Map<String, Object> node : objects(map(second, "dsl").get("nodes"))) {
                Map<String, Object> oldNode = originalNodes.get(str(node, "id"));
                if ("IMPLEMENT".equals(str(node, "kind"))) {
                    assertFalse(strings(oldNode.get("inputs")).contains("goal"));
                    assertFalse(strings(oldNode.get("inputs")).contains("contract"));
                    List<String> expected = new java.util.ArrayList<>(strings(oldNode.get("inputs")));
                    expected.addAll(List.of("goal", "contract"));
                    assertEquals(expected, strings(node.get("inputs")));
                    Map<String, Object> oldRemainder = new java.util.LinkedHashMap<>(oldNode), newRemainder = new java.util.LinkedHashMap<>(node);
                    oldRemainder.remove("inputs");
                    newRemainder.remove("inputs");
                    assertEquals(oldRemainder, newRemainder);
                } else assertEquals(oldNode, node);
            }
            Map<String, Object> originalTask = tasks.get(mode), storedTask = store.get("task", str(originalTask, "id"));
            assertEquals(originalTask, storedTask);
            assertEquals(1, num(map(storedTask, "workflow"), "version", -1));
            assertEquals(first.get("digest"), map(storedTask, "workflow").get("digest"));
            assertEquals(first.get("dsl"), map(storedTask, "workflow").get("dsl"));
            assertDoesNotThrow(() -> workflows.binding(map(storedTask, "workflow"), "implement"));
        }
        List<Map<String, Object>> pointers = store.list("workflowDefinition"), versions = store.list("workflowVersion");
        workflows.defaults();
        assertEquals(pointers, store.list("workflowDefinition"));
        assertEquals(versions, store.list("workflowVersion"));
        assertTrue(store.list("operation").isEmpty());
        assertTrue(store.list("modelRequest").isEmpty());
    }

    @Test
    void defaultsKeepEditedAndDisabledBuiltinPointersAndTheirExistingVersionBytes() {
        Map<String, Map<String, Object>> actors = builtinActors();
        for (String mode : WorkflowTemplates.MODES) builtinFirstVersion(mode, actors);
        String editedId = WorkflowTemplates.id("LEAD"), disabledId = WorkflowTemplates.id("PIPELINE"), publishedId = WorkflowTemplates.id("ROUNDTABLE");
        Map<String, Object> edited = workflows.version(editedId, 1), editedDsl = map(edited, "dsl");
        objects(editedDsl.get("nodes")).getFirst().put("goal", "User-maintained built-in goal");
        edited.put("digest", workflows.digest(editedDsl, objects(edited.get("agentDefinitions"))));
        store.put("workflowVersion", edited);
        Map<String, Object> disabled = store.get("workflowDefinition", disabledId);
        disabled.put("enabled", false);
        store.put("workflowDefinition", disabled);
        Map<String, Object> previous = workflows.version(publishedId, 1);
        workflows.save(obj("id", publishedId, "expectedVersion", 1, "name", "User-published workflow", "projectId", "", "dsl", previous.get("dsl")));
        List<Map<String, Object>> pointers = store.list("workflowDefinition"), versions = store.list("workflowVersion");
        workflows.defaults();
        assertEquals(pointers, store.list("workflowDefinition"));
        assertEquals(versions, store.list("workflowVersion"));
        assertEquals("User-maintained built-in goal", str(objects(map(workflows.version(editedId, 1), "dsl").get("nodes")).getFirst(), "goal"));
        assertFalse(bool(store.get("workflowDefinition", disabledId), "enabled", true));
        assertEquals("USER", str(workflows.version(publishedId, 2), "source"));
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
