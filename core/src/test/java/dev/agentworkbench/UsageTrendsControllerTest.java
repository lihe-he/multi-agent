package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.mcp.McpAccessRegistry;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * 真实控制器和存储验证项目趋势的查询、范围与桌面认证边界，不启动模型或 Worker。
 */
class UsageTrendsControllerTest {
    private static final String TOKEN = "usage-trends-http-token-at-least-24-characters";
    private static final String PATH = "/api/projects/one/usage-trends";
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();
    private ExecutionStore store;
    private MockMvc mvc;
    private McpAccessRegistry grants;
    private WorkbenchEngine engine;
    private WorkspaceRuntime runtime;
    private ResourceManager resources;
    private ConfigurableApplicationContext application;
    private long originalEvent;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(mapper, directory.toString());
        store.put("project", obj("id", "one")); store.put("project", obj("id", "two"));
        store.put("session", obj("id", "s1", "projectId", "one")); store.put("session", obj("id", "s2", "projectId", "one"));
        store.put("session", obj("id", "foreign-session", "projectId", "two"));
        store.put("task", obj("id", "t1", "projectId", "one", "sessionId", "s1")); store.put("task", obj("id", "t2", "projectId", "one", "sessionId", "s2"));
        store.put("task", obj("id", "foreign-task", "projectId", "two", "sessionId", "foreign-session"));
        store.put("modelRequest", obj("id", "original", "projectId", "one", "sessionId", "s1", "taskId", "t1", "unit", "API_REQUEST", "state", "SUCCEEDED",
            "createdAt", Instant.now().minusSeconds(3600).toString(), "usage", obj("inputTokens", 0, "outputTokens", "UNKNOWN"), "inputPreview", "private request content"));
        engine = mock(WorkbenchEngine.class); runtime = mock(WorkspaceRuntime.class); resources = mock(ResourceManager.class); application = mock(ConfigurableApplicationContext.class);
        PlanValidator validator = new PlanValidator(); WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, validator); grants = new McpAccessRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new WorkbenchController(store, catalog, engine, runtime, resources, validator, mapper, application))
            .setControllerAdvice(new ApiErrors()).addFilters(new LocalAccessFilter(TOKEN, grants)).build();
        originalEvent = store.latestEvent();
    }

    @AfterEach
    void close() throws Exception {
        if (store != null) { assertEquals(originalEvent, store.latestEvent()); assertTrue(store.list("operation").isEmpty()); assertTrue(store.list("workerLease").isEmpty()); store.close(); }
        verifyNoInteractions(engine, runtime, resources, application);
    }

    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.with(value -> { value.setRemoteAddr("127.0.0.1"); return value; });
    }

    private MockHttpServletRequestBuilder authenticated(String path) { return local(get(path)).header("Authorization", "Bearer " + TOKEN); }

    @Test
    void defaultAndAllowedWindowsReturnOnlyAggregatesWithExplicitZeroAndUnknown() throws Exception {
        for (int days : List.of(7, 30, 90)) {
            mvc.perform(authenticated(PATH).param("days", String.valueOf(days)).param("sessionId", "s1").param("taskId", "t1"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.schemaVersion").value(1)).andExpect(jsonPath("$.source").value("MODEL_REQUEST"))
                .andExpect(jsonPath("$.days").value(days)).andExpect(jsonPath("$.timeZone").value("UTC")).andExpect(jsonPath("$.buckets.length()").value(days))
                .andExpect(jsonPath("$.scope.projectId").value("one")).andExpect(jsonPath("$.scope.sessionId").value("s1")).andExpect(jsonPath("$.scope.taskId").value("t1"))
                .andExpect(jsonPath("$.summary.recordCount").value(1)).andExpect(jsonPath("$.summary.hasRecords").value(true))
                .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.inputTokens.total").value(0))
                .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.outputTokens.total").value("UNKNOWN"))
                .andExpect(jsonPath("$.summary.units.CLI_SESSION.recordCount").value(0)).andExpect(jsonPath("$.cost").value("UNKNOWN"));
        }
        String body = mvc.perform(authenticated(PATH)).andExpect(status().isOk()).andExpect(jsonPath("$.days").value(7))
            .andExpect(jsonPath("$.scope.sessionId").value("")).andExpect(jsonPath("$.scope.taskId").value(""))
            .andReturn().getResponse().getContentAsString();
        assertFalse(body.contains("private request")); assertFalse(body.contains("inputPreview")); assertEquals(1, store.list("modelRequest").size());
    }

    @Test
    void invalidWindowIs400AndForeignOrMissingScopeIs404() throws Exception {
        for (String days : List.of("0", "1", "8", "31", "91", "-7", "7.0", "bad", "99999999999999999999")) {
            mvc.perform(authenticated(PATH).param("days", days)).andExpect(status().isBadRequest());
        }
        mvc.perform(authenticated("/api/projects/missing/usage-trends")).andExpect(status().isNotFound());
        for (String session : List.of("missing", "foreign-session")) mvc.perform(authenticated(PATH).param("sessionId", session)).andExpect(status().isNotFound());
        for (String task : List.of("missing", "foreign-task")) mvc.perform(authenticated(PATH).param("taskId", task)).andExpect(status().isNotFound());
        mvc.perform(authenticated(PATH).param("sessionId", "s1").param("taskId", "t2")).andExpect(status().isNotFound());
        assertTrue(store.healthy());
    }

    @Test
    void emptyScopeRemainsDistinguishableAndNoRequestIsSynthesized() throws Exception {
        mvc.perform(authenticated(PATH).param("sessionId", "s2").param("taskId", "t2"))
            .andExpect(status().isOk()).andExpect(jsonPath("$.summary.hasRecords").value(false)).andExpect(jsonPath("$.summary.recordCount").value(0))
            .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.inputTokens.observedSum").value(0))
            .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.inputTokens.knownCount").value(0))
            .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.inputTokens.unknownCount").value(0))
            .andExpect(jsonPath("$.summary.units.API_REQUEST.tokens.inputTokens.total").value("UNKNOWN"));
        assertEquals(1, store.list("modelRequest").size());
    }

    @Test
    void projectTrendsRequireCoreBearerAndLocalOrigin() throws Exception {
        mvc.perform(local(get(PATH)).param("token", TOKEN)).andExpect(status().isUnauthorized());
        String scoped = str(grants.issue("one"), "token");
        mvc.perform(local(get(PATH)).header("Authorization", "Bearer " + scoped)).andExpect(status().isUnauthorized());
        mvc.perform(get(PATH).with(value -> { value.setRemoteAddr("192.0.2.20"); return value; }).header("Authorization", "Bearer " + TOKEN))
            .andExpect(status().isForbidden());
        mvc.perform(authenticated(PATH).header("Origin", "https://untrusted.example")).andExpect(status().isForbidden());
    }
}
