package dev.agentworkbench;

import dev.agentworkbench.mcp.McpAccessRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
 * 验证真实 HTTP 映射、原本机认证和解析边界；不代替原生 Worker 生命周期验收。
 */
class TerminalControllerTest {
    private static final String TOKEN = "terminal-http-fixture-token-with-more-than-24-characters";
    private WorkbenchEngine engine;
    private MockMvc mvc;
    private McpAccessRegistry grants;

    @BeforeEach
    void open() {
        engine = mock(WorkbenchEngine.class); grants = new McpAccessRegistry();
        mvc = MockMvcBuilders.standaloneSetup(new TerminalController(engine)).setControllerAdvice(new ApiErrors()).addFilters(new LocalAccessFilter(TOKEN, grants)).build();
    }

    private MockHttpServletRequestBuilder local(MockHttpServletRequestBuilder request) {
        return request.with(value -> { value.setRemoteAddr("127.0.0.1"); return value; });
    }

    private MockHttpServletRequestBuilder authenticated(MockHttpServletRequestBuilder request) { return local(request).header("Authorization", "Bearer " + TOKEN); }

    @Test
    void everyReadAndControlRouteRequiresTheOriginalCoreBearerHeader() throws Exception {
        for (MockHttpServletRequestBuilder request : List.of(post("/api/tasks/task-a/terminals"), get("/api/tasks/task-a/terminals"), get("/api/terminals/terminal-a"),
                get("/api/terminals/terminal-a/output").param("epoch", id()), post("/api/terminals/terminal-a/control"), post("/api/terminals/terminal-a/actions"),
                get("/api/terminals/terminal-a/actions"), get("/api/terminals/terminal-a/actions/" + id()))) {
            mvc.perform(local(request).param("token", TOKEN).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(engine);
    }

    @Test
    void projectScopedMcpTokenCannotReadOutputOrAcquireTerminalControl() throws Exception {
        String scoped = str(grants.issue("project-a"), "token");
        for (MockHttpServletRequestBuilder request : List.of(get("/api/terminals/terminal-a/output").param("epoch", id()), post("/api/terminals/terminal-a/control"), post("/api/tasks/task-a/terminals"))) {
            mvc.perform(local(request).header("Authorization", "Bearer " + scoped).contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(status().isUnauthorized());
        }
        verifyNoInteractions(engine);
    }

    @Test
    void arbitraryWebOriginAndRemotePeerCannotReadTheOriginalInputObservation() throws Exception {
        mvc.perform(authenticated(get("/api/terminals/terminal-a/actions/" + id())).header("Origin", "https://other.example")).andExpect(status().isForbidden());
        mvc.perform(get("/api/terminals/terminal-a/actions/" + id()).header("Authorization", "Bearer " + TOKEN).with(request -> { request.setRemoteAddr("192.0.2.1"); return request; })).andExpect(status().isForbidden());
        verifyNoInteractions(engine);
    }

    @Test
    void originalActionLookupIsAReadWithoutControlSecretOrDispatch() throws Exception {
        String requestId = id(); Map<String, Object> original = obj("terminalId", "terminal-a", "requestId", requestId, "state", "UNKNOWN", "sequence", 7, "dispatchCount", 1, "nativeAuthorizationCount", 1, "length", 13, "sha256", "a".repeat(64));
        when(engine.terminalActionObservation("terminal-a", requestId)).thenReturn(original);
        mvc.perform(authenticated(get("/api/terminals/terminal-a/actions/" + requestId))).andExpect(status().isOk()).andExpect(jsonPath("$.state").value("UNKNOWN"))
            .andExpect(jsonPath("$.dispatchCount").value(1)).andExpect(jsonPath("$.controlSecret").doesNotExist()).andExpect(jsonPath("$.dataBase64").doesNotExist()).andExpect(header().string("Cache-Control", "no-store"));
        verify(engine).terminalActionObservation("terminal-a", requestId); verifyNoMoreInteractions(engine);
    }

    @Test
    void longOutputCursorIsForwardedWithoutNarrowingOrNativeControl() throws Exception {
        String epoch = id(); when(engine.terminalOutput("terminal-a", epoch, Long.MAX_VALUE, 3)).thenReturn(obj("epoch", epoch, "offset", Long.MAX_VALUE, "next", Long.MAX_VALUE, "length", 0));
        mvc.perform(authenticated(get("/api/terminals/terminal-a/output")).param("epoch", epoch).param("after", Long.toString(Long.MAX_VALUE)).param("limit", "3")).andExpect(status().isOk());
        verify(engine).terminalOutput("terminal-a", epoch, Long.MAX_VALUE, 3); verifyNoMoreInteractions(engine);
    }

    @Test
    void malformedBodyAndMissingOrInvalidCursorStayClientErrors() throws Exception {
        for (String body : List.of("[]", "{", "null")) mvc.perform(authenticated(post("/api/terminals/terminal-a/actions")).contentType(MediaType.APPLICATION_JSON).content(body)).andExpect(status().isBadRequest());
        mvc.perform(authenticated(get("/api/terminals/terminal-a/output"))).andExpect(status().isBadRequest());
        mvc.perform(authenticated(get("/api/terminals/terminal-a/output")).param("epoch", id()).param("after", "1.5")).andExpect(status().isBadRequest());
        verifyNoInteractions(engine);
    }

    @Test
    void authenticatedOversizeJsonIsRejectedBeforeParsingOrDispatch() throws Exception {
        mvc.perform(authenticated(post("/api/terminals/terminal-a/actions")).contentType(MediaType.APPLICATION_JSON).content("x".repeat(TerminalRequestBody.MAX_BYTES + 1))).andExpect(status().isPayloadTooLarge());
        verifyNoInteractions(engine);
    }

    @Test
    void bodyLimitAlsoChecksStreamsWithoutContentLength() throws Exception {
        byte[] bytes = new byte[TerminalRequestBody.MAX_BYTES + 1];
        var request = new org.springframework.mock.web.MockHttpServletRequest("POST", "/api/terminals/terminal-a/actions") {
            @Override public long getContentLengthLong() { return -1; }
        };
        request.setContent(bytes); assertThrows(TerminalRequestBody.TooLarge.class, () -> TerminalRequestBody.bounded(request));
    }
}
