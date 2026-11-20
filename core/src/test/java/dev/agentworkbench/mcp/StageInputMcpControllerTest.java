package dev.agentworkbench.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.ApiErrors;
import dev.agentworkbench.ApiException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StageInputMcpControllerTest {
    private final ObjectMapper mapper = new ObjectMapper();
    private final StageInputMcpRegistry registry = new StageInputMcpRegistry();
    private final StageInputMcpController controller = new StageInputMcpController(registry, mapper);

    @Test
    void handshakeDiscoveryAndCallRevalidateAndUseOnlyTheCurrentProjection() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var handle = registry.open(obj(), () -> obj("goal", "approved", "read", reads.incrementAndGet()), Duration.ofMinutes(1));
        for (String version : List.of("2025-06-18", "2025-03-26")) {
            Map<String, Object> initialized = body(rpc(handle, "initialize", obj("protocolVersion", version)));
            assertEquals(version, map(initialized, "result").get("protocolVersion"));
        }
        assertTrue(map(body(rpc(handle, "ping", obj())), "result").isEmpty());
        Map<String, Object> tool = objects(map(body(rpc(handle, "tools/list", obj())), "result").get("tools")).getFirst();
        assertEquals("stage_input", tool.get("name"));
        assertEquals(false, map(tool, "inputSchema").get("additionalProperties"));
        Map<String, Object> result = map(body(rpc(handle, "tools/call", obj("name", "stage_input", "arguments", obj()))), "result");
        Map<String, Object> payload = mapper.readValue(str(objects(result.get("content")).getFirst(), "text"), new TypeReference<>() { });
        assertEquals(obj("goal", "approved", "read", 5), payload);
        assertEquals(false, result.get("isError"));
        assertEquals(5, reads.get());
        String serialized = mapper.writeValueAsString(result);
        assertFalse(serialized.contains(handle.token())); assertFalse(serialized.contains(handle.id()));
    }

    @Test
    void toolCallRequiresItsOnlyNameAndAllowsOmittedOrEmptyArguments() {
        AtomicInteger reads = new AtomicInteger();
        var handle = registry.open(obj(), () -> { reads.incrementAndGet(); return obj("goal", "approved"); }, Duration.ofMinutes(1));
        List<Object> invalid = List.of(obj(), obj("arguments", obj()), obj("name", "stage_input", "arguments", null),
            obj("name", "stage_input", "arguments", List.of()), obj("name", "stage_input", "arguments", "{}"),
            obj("name", "stage_input", "arguments", obj("taskId", "other")),
            obj("name", "stage_input", "arguments", obj(), "taskId", "other"), obj("name", "project_search", "arguments", obj()),
            obj("name", "stage_input", "arguments", obj(), "_meta", "invalid"));
        for (Object params : invalid) assertRpcError(rpc(handle, "tools/call", params), -32602);
        assertRpcError(controller.rpc(handle.id(), obj("jsonrpc", "2.0", "id", 1, "method", "tools/call"), bearer(handle), null), -32602);
        for (String version : List.of("2025-06-18", "2025-03-26")) {
            for (Object params : List.of(obj("name", "stage_input"),
                    obj("name", "stage_input", "arguments", obj(), "_meta", obj("progressToken", "progress-1")))) {
                var request = obj("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", params);
                var result = map(body(controller.rpc(handle.id(), request, bearer(handle), version)), "result");
                assertEquals(false, result.get("isError"));
                assertEquals("{\"goal\":\"approved\"}", str(objects(result.get("content")).getFirst(), "text"));
            }
        }
        assertEquals(invalid.size() + 5, reads.get());
    }

    @Test
    void everyMethodProtocolFailureAndNotificationChecksTheSupplierFirst() {
        AtomicInteger reads = new AtomicInteger();
        var handle = registry.open(obj(), () -> { reads.incrementAndGet(); return obj("goal", "approved"); }, Duration.ofMinutes(1));
        assertRpcError(rpc(handle, "unknown", obj()), -32601);
        assertEquals(202, controller.rpc(handle.id(), obj("jsonrpc", "2.0", "method", "notifications/initialized"), bearer(handle), null).getStatusCode().value());
        assertRpcError(rpc(handle, "notifications/initialized", obj()), -32601);
        ResponseEntity<Map<String, Object>> unknownNotification = controller.rpc(handle.id(), obj("jsonrpc", "2.0", "method", "notifications/other"), bearer(handle), null);
        assertEquals(400, unknownNotification.getStatusCode().value()); assertRpcError(unknownNotification, -32601);
        assertEquals(202, controller.rpc(handle.id(), obj("jsonrpc", "2.0", "method", "tools/call", "params", obj("name", "other")), bearer(handle), null).getStatusCode().value());
        assertRpcError(controller.rpc(handle.id(), obj("jsonrpc", "2.0", "id", 1, "method", "ping"), bearer(handle), "unknown-version"), -32600);
        assertRpcError(rpc(handle, "initialize", obj("protocolVersion", "unknown-version")), -32602);
        assertRpcError(controller.rpc(handle.id(), obj("jsonrpc", "1.0", "id", 1, "method", "ping"), bearer(handle), null), -32600);
        assertRpcError(controller.rpc(handle.id(), null, bearer(handle), null), -32600);
        assertEquals(9, reads.get());
        registry.revoke(handle.id());
        for (String method : List.of("initialize", "ping", "tools/list", "tools/call", "unknown", "notifications/initialized")) {
            ApiException denied = assertThrows(ApiException.class, () -> rpc(handle, method, obj()));
            assertEquals(403, denied.status()); assertEquals("Stage input unavailable", denied.getMessage());
        }
        assertEquals(403, assertThrows(ApiException.class, () -> controller.rpc(handle.id(), obj("jsonrpc", "2.0", "method", "ping"), bearer(handle), "unsupported")).status());
        assertEquals(403, assertThrows(ApiException.class, () -> controller.rpc(handle.id(), obj("jsonrpc", "2.0", "method", "notifications/initialized"), bearer(handle), null)).status());
        assertEquals(9, reads.get());
    }

    @Test
    void getAuthenticatesBeforeReturningMethodNotAllowed() throws Exception {
        AtomicInteger reads = new AtomicInteger();
        var handle = registry.open(obj(), () -> { reads.incrementAndGet(); return obj("goal", "approved"); }, Duration.ofMinutes(1));
        ResponseEntity<Void> response = controller.noUnsolicitedStream(handle.id(), bearer(handle));
        assertEquals(405, response.getStatusCode().value()); assertEquals("POST", response.getHeaders().getFirst("Allow"));
        assertEquals(1, reads.get());
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrors()).build();
        mvc.perform(get(handle.endpoint()).header("Authorization", "Bearer wrong")).andExpect(status().isForbidden());
        registry.revoke(handle.id());
        mvc.perform(get(handle.endpoint()).header("Authorization", bearer(handle))).andExpect(status().isForbidden());
        assertEquals(1, reads.get());
    }

    @Test
    void supplierFailuresNeverExposeSecretsOrInternalIdentity() throws Exception {
        var identity = obj("taskId", "PRIVATE_TASK_ID", "callId", "PRIVATE_CALL_ID");
        var handle = registry.open(identity, () -> { throw new ApiException(409, "PRIVATE_SUPPLIER_FAILURE"); }, Duration.ofMinutes(1));
        MockMvc mvc = MockMvcBuilders.standaloneSetup(controller).setControllerAdvice(new ApiErrors()).build();
        String response = mvc.perform(post(handle.endpoint()).header("Authorization", bearer(handle)).contentType("application/json")
            .content(mapper.writeValueAsBytes(obj("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", obj("name", "stage_input", "arguments", obj())))))
            .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertTrue(response.contains("Stage input unavailable"));
        for (String secret : List.of(handle.token(), handle.id(), "PRIVATE_TASK_ID", "PRIVATE_CALL_ID", "PRIVATE_SUPPLIER_FAILURE")) assertFalse(response.contains(secret));
    }

    private ResponseEntity<Map<String, Object>> rpc(StageInputMcpRegistry.Handle handle, String method, Object params) {
        return controller.rpc(handle.id(), obj("jsonrpc", "2.0", "id", 1, "method", method, "params", params), bearer(handle), null);
    }

    private String bearer(StageInputMcpRegistry.Handle handle) { return "Bearer " + handle.token(); }
    private Map<String, Object> body(ResponseEntity<Map<String, Object>> response) { assertNotNull(response.getBody()); return response.getBody(); }
    private void assertRpcError(ResponseEntity<Map<String, Object>> response, int code) { assertEquals(code, map(body(response), "error").get("code")); }
}
