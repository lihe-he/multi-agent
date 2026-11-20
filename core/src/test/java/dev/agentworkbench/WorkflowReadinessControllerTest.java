package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.mcp.McpAccessRegistry;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 使用真实控制器、目录与 SQLite 验证路由检查的 HTTP 边界；不启动 Worker 或模型请求。
 */
class WorkflowReadinessControllerTest {
    private static final String TOKEN = "workflow-readiness-http-fixture-token-over-24-characters";
    private static final List<String> PATHS = List.of("/api/workflows/readiness", "/api/tasks/task/workflow-readiness");
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private WorkbenchCatalog catalog;
    private WorkbenchEngine engine;
    private WorkspaceRuntime runtime;
    private ResourceManager resources;
    private ConfigurableApplicationContext application;
    private McpAccessRegistry grants;
    private MockMvc mvc;
    private Map<String, Object> selection;
    private Map<String, Object> originalTask;
    private long originalEvent;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(mapper, directory.toString());
        engine = mock(WorkbenchEngine.class); runtime = mock(WorkspaceRuntime.class); resources = mock(ResourceManager.class);
        application = mock(ConfigurableApplicationContext.class); grants = new McpAccessRegistry();
        PlanValidator validator = new PlanValidator(); catalog = new WorkbenchCatalog(store, runtime, validator);
        store.put("project", obj("id", "one")); store.put("project", obj("id", "two"));
        Map<String, Object> rootRoute = route("root", 1), otherRoute = route("other", 3);
        store.put("route", rootRoute); store.put("route", otherRoute);
        for (String actorId : List.of("agent", "alternate")) store.put("agentDefinition", obj("id", actorId, "version", 1,
            "projectId", "", "routeId", actorId.equals("agent") ? "" : "other", "name", actorId, "role", "researcher",
            "instructions", "Inspect assigned source", "capabilities", List.of("read", "search"), "scope", List.of("src/**"), "maxCalls", 2, "enabled", true));
        Map<String, Object> source = WorkflowDslTest.node("source", List.of()); source.put("inputs", List.of("goal", "context"));
        Map<String, Object> consumer = WorkflowDslTest.node("consumer", List.of("source"));
        consumer.putAll(obj("definitionId", "alternate", "inputs", List.of("source:result", "contract")));
        Map<String, Object> version = map(catalog.workflows().save(obj("name", "HTTP readiness", "projectId", "one",
            "dsl", WorkflowDslTest.graph(List.of(source, consumer)))), "version");
        selection = obj("definitionId", version.get("definitionId"), "version", version.get("version"), "digest", version.get("digest"));
        store.put("task", obj("id", "task", "projectId", "one", "routeSnapshot", rootRoute, "locks", obj(), "goal", "private task goal",
            "context", List.of(obj("content", "private task context")), "budget", obj("usedCalls", 2, "reserveCalls", 1),
            "contract", obj("permissions", List.of("read", "search"), "scope", List.of("src/**"), "acceptance", List.of("private task contract"))));
        originalTask = store.get("task", "task"); originalEvent = store.latestEvent();
        mvc = MockMvcBuilders.standaloneSetup(new WorkbenchController(store, catalog, engine, runtime, resources, validator, mapper, application))
            .setControllerAdvice(new ApiErrors()).addFilters(new LocalAccessFilter(TOKEN, grants)).build();
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> route(String routeId, int version) {
        return obj("id", routeId, "version", version, "name", routeId + " route", "engine", "API", "enabled", true,
            "provider", routeId + " provider", "model", routeId + " model v" + version, "baseUrl", "http://127.0.0.1:18999/v1", "apiKey", "private route credential");
    }

    private Map<String, Object> draft() { return obj("projectId", "one", "routeId", "root", "locks", obj(), "selection", selection); }

    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.with(value -> { value.setRemoteAddr("127.0.0.1"); return value; });
    }

    private MockHttpServletRequestBuilder authenticated(String path, Object body) throws Exception {
        return local(post(path)).header("Authorization", "Bearer " + TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body));
    }

    private void assertNoExecutionWrites() {
        assertEquals(originalTask, store.get("task", "task")); assertEquals(originalEvent, store.latestEvent());
        for (String kind : List.of("operation", "teamOperation", "modelRequest", "agentRun", "workflowRun", "workflowNodeAttempt", "taskExecution", "workerLease"))
            assertTrue(store.list(kind).isEmpty(), kind + " must remain empty after readiness");
        verifyNoInteractions(engine, runtime, resources, application);
    }

    @Test
    void bothPostMappingsRequireTheCoreBearerAndRejectProjectScopedMcpTokens() throws Exception {
        String scoped = str(grants.issue("one"), "token");
        for (String path : PATHS) {
            Object body = path.equals(PATHS.getFirst()) ? draft() : selection;
            mvc.perform(local(post(path)).param("token", TOKEN).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isUnauthorized()).andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(local(post(path)).header("Authorization", "Bearer " + scoped).contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsBytes(body)))
                .andExpect(status().isUnauthorized());
        }
        assertNoExecutionWrites();
    }

    @Test
    void remotePeersAndArbitraryOriginsCannotInspectEitherReadinessMapping() throws Exception {
        for (String path : PATHS) {
            Object body = path.equals(PATHS.getFirst()) ? draft() : selection;
            mvc.perform(authenticated(path, body).header("Origin", "https://other.example")).andExpect(status().isForbidden());
            mvc.perform(authenticated(path, body).with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })).andExpect(status().isForbidden());
        }
        assertNoExecutionWrites();
    }

    @Test
    void authenticatedJsonShowsExactRecipientsAndDeclaredInputsWithoutExecutionOrPrivateContent() throws Exception {
        for (String path : PATHS) {
            String json = mvc.perform(authenticated(path, path.equals(PATHS.getFirst()) ? draft() : selection)).andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.rootRoute.id").value("root")).andExpect(jsonPath("$.rootRoute.version").value(1))
                .andExpect(jsonPath("$.nodes[0].route.model").value("root model v1")).andExpect(jsonPath("$.nodes[0].inputs[0]").value("goal"))
                .andExpect(jsonPath("$.nodes[0].rootInputs[1]").value("context")).andExpect(jsonPath("$.nodes[1].inputs[0]").value("source:result"))
                .andExpect(jsonPath("$.nodes[1].rootInputs[0]").value("contract")).andExpect(jsonPath("$.nodes[1].route.id").value("other"))
                .andExpect(jsonPath("$.extraRoutes[0].version").value(3)).andExpect(jsonPath("$.extraRoutes[0].provider").value("other provider"))
                .andExpect(jsonPath("$.blockers").isEmpty()).andExpect(jsonPath("$.rootRoute.apiKey").doesNotExist()).andReturn().getResponse().getContentAsString();
            for (String secret : List.of("private task goal", "private task context", "private task contract", "private route credential")) assertFalse(json.contains(secret));
        }
        assertNoExecutionWrites();
    }

    @Test
    void taskMappingUsesItsFixedRootRevisionWhileDraftMappingUsesCurrentRoute() throws Exception {
        store.put("route", route("root", 2)); originalEvent = store.latestEvent();
        mvc.perform(authenticated(PATHS.get(1), selection)).andExpect(status().isOk()).andExpect(jsonPath("$.rootRoute.version").value(1))
            .andExpect(jsonPath("$.nodes[0].route.model").value("root model v1"));
        mvc.perform(authenticated(PATHS.getFirst(), draft())).andExpect(status().isOk()).andExpect(jsonPath("$.rootRoute.version").value(2))
            .andExpect(jsonPath("$.nodes[0].route.model").value("root model v2"));
        assertNoExecutionWrites();
    }

    @Test
    void malformedJsonAndInvalidSelectionTypesRemainClientErrorsOnBothMappings() throws Exception {
        for (String path : PATHS) {
            for (String body : List.of("[]", "{", "null", "\"not an object\"")) mvc.perform(local(post(path)).header("Authorization", "Bearer " + TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
            for (Object version : List.of("1", 1.5, true, 0)) {
                Map<String, Object> invalid = new java.util.LinkedHashMap<>(selection); invalid.put("version", version);
                Object body = path.equals(PATHS.getFirst()) ? obj("projectId", "one", "routeId", "root", "selection", invalid) : invalid;
                mvc.perform(authenticated(path, body)).andExpect(status().isBadRequest());
            }
            Map<String, Object> missingDigest = new java.util.LinkedHashMap<>(selection); missingDigest.remove("digest");
            Object body = path.equals(PATHS.getFirst()) ? obj("projectId", "one", "routeId", "root", "selection", missingDigest) : missingDigest;
            mvc.perform(authenticated(path, body)).andExpect(status().isBadRequest());
        }
        assertNoExecutionWrites();
    }

    @Test
    void projectAndDigestBoundariesRejectCallerOverridesWithoutChangingStoredTask() throws Exception {
        Map<String, Object> wrongProject = draft(); wrongProject.put("projectId", "two");
        mvc.perform(authenticated(PATHS.getFirst(), wrongProject)).andExpect(status().isForbidden());
        Map<String, Object> override = new java.util.LinkedHashMap<>(selection); override.put("projectId", "two"); override.put("routeId", "other");
        mvc.perform(authenticated(PATHS.get(1), override)).andExpect(status().isBadRequest());
        Map<String, Object> stale = new java.util.LinkedHashMap<>(selection); stale.put("digest", "0".repeat(64));
        mvc.perform(authenticated(PATHS.get(1), stale)).andExpect(status().isConflict());
        mvc.perform(authenticated(PATHS.getFirst(), obj("projectId", "one", "routeId", "root", "selection", stale))).andExpect(status().isConflict());
        mvc.perform(authenticated("/api/tasks/missing/workflow-readiness", selection)).andExpect(status().isNotFound());
        Map<String, Object> unknown = draft(); unknown.put("goal", "caller injection");
        mvc.perform(authenticated(PATHS.getFirst(), unknown)).andExpect(status().isBadRequest());
        assertNoExecutionWrites();
    }
}
