package dev.agentworkbench;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import dev.agentworkbench.mcp.McpAccessRegistry;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class LocalAccessFilterTest {
    private static final String TOKEN = "test-token-with-more-than-24-characters";

    @Test
    void tokenIsRequiredEvenOnLoopback() throws Exception {
        var filter = new LocalAccessFilter(TOKEN); var request = new MockHttpServletRequest("GET", "/api/state");
        request.setRemoteAddr("127.0.0.1"); var response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain()); assertEquals(401, response.getStatus());
    }

    @Test
    void arbitraryWebOriginCannotReachLocalApi() throws Exception {
        var filter = new LocalAccessFilter(TOKEN); var request = new MockHttpServletRequest("POST", "/api/tasks");
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", "Bearer " + TOKEN); request.addHeader("Origin", "https://other.example");
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain()); assertEquals(403, response.getStatus());
    }

    @Test
    void authenticatedLocalClientCanProceed() throws Exception {
        var filter = new LocalAccessFilter(TOKEN); var request = new MockHttpServletRequest("GET", "/api/state");
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", "Bearer " + TOKEN); request.addHeader("Origin", "http://127.0.0.1:5173");
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain); assertNotNull(chain.getRequest()); assertEquals(200, response.getStatus());
    }

    @Test
    void electronFileOriginRequiresTheSameToken() throws Exception {
        var filter = new LocalAccessFilter(TOKEN); var request = new MockHttpServletRequest("GET", "/api/state");
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Origin", "null"); request.addHeader("Authorization", "Bearer " + TOKEN);
        var response = new MockHttpServletResponse(); var chain = new MockFilterChain();
        filter.doFilter(request, response, chain); assertNotNull(chain.getRequest()); assertEquals("null", response.getHeader("Access-Control-Allow-Origin"));
    }

    @Test
    void fileOriginWithoutTokenStillFails() throws Exception {
        var filter = new LocalAccessFilter(TOKEN); var request = new MockHttpServletRequest("GET", "/api/state");
        request.setRemoteAddr("127.0.0.1"); request.addHeader("Origin", "null");
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain()); assertEquals(401, response.getStatus());
    }

    @Test
    void scopedMcpTokenCanReadOnlyItsExactProjectRpc() throws Exception {
        var registry = new McpAccessRegistry(); var grant = registry.issue("project-a");
        var filter = new LocalAccessFilter(TOKEN, registry);
        assertEquals(200, invoke(filter, "POST", "/api/mcp/rpc/project-a", str(grant, "token")));
        assertEquals(401, invoke(filter, "POST", "/api/mcp/rpc/project-b", str(grant, "token")));
        assertEquals(401, invoke(filter, "POST", "/api/mcp/rpc/project-a/extra", str(grant, "token")));
    }

    @Test
    void scopedMcpTokenCannotCallDesktopReadOrMutationApis() throws Exception {
        var registry = new McpAccessRegistry(); var grant = registry.issue("project-a");
        var filter = new LocalAccessFilter(TOKEN, registry);
        assertEquals(401, invoke(filter, "GET", "/api/state", str(grant, "token")));
        assertEquals(401, invoke(filter, "POST", "/api/tasks", str(grant, "token")));
        assertEquals(401, invoke(filter, "POST", "/api/shutdown", str(grant, "token")));
    }

    @Test
    void scopedMcpTokenCannotMintOrRevokeOtherGrants() throws Exception {
        var registry = new McpAccessRegistry(); var grant = registry.issue("project-a");
        var filter = new LocalAccessFilter(TOKEN, registry);
        assertEquals(401, invoke(filter, "POST", "/api/mcp/access", str(grant, "token")));
        assertEquals(401, invoke(filter, "DELETE", "/api/mcp/access/" + str(grant, "id"), str(grant, "token")));
        assertEquals(200, invoke(filter, "POST", "/api/mcp/access", TOKEN));
    }

    @Test
    void revokedAndRestartedMcpTokensAreRejected() throws Exception {
        var registry = new McpAccessRegistry(); var grant = registry.issue("project-a");
        var filter = new LocalAccessFilter(TOKEN, registry); registry.revoke(str(grant, "id"));
        assertEquals(401, invoke(filter, "POST", "/api/mcp/rpc/project-a", str(grant, "token")));
        var next = registry.issue("project-a");
        assertEquals(401, invoke(new LocalAccessFilter(TOKEN, new McpAccessRegistry()), "POST", "/api/mcp/rpc/project-a", str(next, "token")));
    }

    @Test
    void mcpGrantListingNeverReturnsSecretMaterial() {
        var registry = new McpAccessRegistry(); var issued = registry.issue("project-a");
        assertTrue(str(issued, "token").startsWith("mcp_"));
        assertFalse(registry.list().toString().contains(str(issued, "token")));
        assertFalse(registry.list().getFirst().containsKey("token")); assertFalse(registry.list().getFirst().containsKey("tokenHash"));
    }

    private int invoke(LocalAccessFilter filter, String method, String path, String token) throws Exception {
        var request = new MockHttpServletRequest(method, path); request.setRemoteAddr("127.0.0.1"); request.addHeader("Authorization", "Bearer " + token);
        var response = new MockHttpServletResponse(); filter.doFilter(request, response, new MockFilterChain()); return response.getStatus();
    }
}
