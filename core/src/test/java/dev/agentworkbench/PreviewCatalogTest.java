package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class PreviewCatalogTest {
    @TempDir Path directory;
    private ExecutionStore store;
    private PreviewCatalog previews;
    private String sessionId;

    @BeforeEach
    void open() throws Exception {
        reopen(); store.put("project", obj("id", "a")); store.put("project", obj("id", "b"));
        sessionId = str(store.createSession("a", "preview"), "id");
    }

    void reopen() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); store = new ExecutionStore(mapper, directory.resolve("core").toString());
        previews = new PreviewCatalog(store, new WorkbenchCatalog(store, new WorkspaceRuntime(mapper, directory.resolve("runtime").toString()), new PlanValidator()));
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    @Test
    void configuredScopeSurvivesRestartWithoutPersistingUrlSecrets() throws Exception {
        Map<String, Object> first = previews.get("a", sessionId);
        Map<String, Object> updated = previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173),
            "tabs", List.of(obj("id", "one", "title", "login", "url", "http://127.0.0.1:5173/callback?code=secret#token")), "activeTabId", "one"));
        assertEquals("http://127.0.0.1:5173/callback", objects(updated.get("tabs")).getFirst().get("url"));
        assertFalse(updated.toString().contains("secret")); assertEquals(2, num(updated, "version", 0));
        assertNotNull(first.get("serverId"));
        String server = str(first, "serverId");
        store.close(); store = null; reopen(); assertEquals(updated, previews.get("a", sessionId));
        assertEquals(first.get("executionNodeId"), previews.get("a", sessionId).get("executionNodeId"));
        assertEquals(server, str(previews.get("a", sessionId), "serverId"));
    }

    @Test
    void foreignProjectClosedSessionAndOldVersionCannotMutateMetadata() {
        previews.get("a", sessionId);
        assertEquals(404, assertThrows(ApiException.class, () -> previews.get("b", sessionId)).status());
        previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173)));
        assertEquals(409, assertThrows(ApiException.class, () -> previews.update("a", sessionId, obj("expectedVersion", 1))).status());
        store.editSession("a", sessionId, obj("expectedVersion", 1, "isOpen", false));
        assertEquals(409, assertThrows(ApiException.class, () -> previews.update("a", sessionId, obj("expectedVersion", 2))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> previews.reset("a", sessionId, obj("expectedVersion", 2))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> previews.authorize("a", sessionId)).status());
    }

    @Test
    void previewPurposeIsSeparateFromUiAndAuthorizationVersions() {
        Map<String, Object> initial = previews.get("a", sessionId);
        Map<String, Object> portChange = previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173)));
        assertEquals(2, num(portChange, "version", 0)); assertEquals(2, num(portChange, "authorizationVersion", 0));
        Map<String, Object> navigation = previews.update("a", sessionId, obj("expectedVersion", 2,
            "tabs", List.of(obj("id", "one", "title", "", "url", "http://127.0.0.1:5173/")), "activeTabId", "one"));
        assertEquals(3, num(navigation, "version", 0)); assertEquals(2, num(navigation, "authorizationVersion", 0));
        assertNotEquals("", initial.get("serverId"));
        assertEquals(sessionId, initial.get("id"));
        Map<String, Object> reset = previews.reset("a", sessionId, obj("expectedVersion", 3));
        assertEquals(2, num(reset, "storageGeneration", 0)); assertEquals(4, num(reset, "version", 0)); assertEquals(3, num(reset, "authorizationVersion", 0));
        assertEquals(List.of(5173), reset.get("allowedLoopbackPorts")); assertEquals(List.of(), reset.get("tabs"));
        assertFalse(reset.containsKey("cookiesCleared"));
    }

    @Test
    void malformedSchemesCredentialsTypesAndDuplicateTabsAreRejected() {
        previews.get("a", sessionId);
        for (String url : List.of("file:///C:/Windows/win.ini", "javascript:alert(1)", "data:text/html,x", "https://user:password@example.test/")) {
            assertEquals(400, assertThrows(ApiException.class, () -> previews.update("a", sessionId, obj("expectedVersion", 1,
                "tabs", List.of(obj("id", "one", "title", "", "url", url))))).status());
        }
        for (Object ports : List.of(List.of(0), List.of(65536), List.of(1.5), "5173")) assertEquals(400, assertThrows(ApiException.class,
            () -> previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", ports))).status());
        Map<String, Object> tab = obj("id", "one", "title", "", "url", "https://example.test/");
        assertEquals(400, assertThrows(ApiException.class, () -> previews.update("a", sessionId, obj("expectedVersion", 1, "tabs", List.of(tab, tab)))).status());
        assertEquals(400, assertThrows(ApiException.class, () -> previews.update("a", sessionId, obj("expectedVersion", 1, "cookies", "secret"))).status());
    }

    @Test
    void concurrentUpdatesShareOneVersionBoundary() throws Exception {
        previews.get("a", sessionId);
        try (var executor = Executors.newFixedThreadPool(2)) {
            var left = executor.submit(() -> updateVersion()); var right = executor.submit(() -> updateVersion());
            List<Integer> codes = List.of(left.get(), right.get());
            assertEquals(1, codes.stream().filter(code -> code == 200).count()); assertEquals(1, codes.stream().filter(code -> code == 409).count());
        }
        assertEquals(2, num(previews.get("a", sessionId), "version", 0));
    }

    @Test
    void revokingALoopbackPortClosesItsTabsAndAdvancesAuthorization() {
        Map<String, Object> configured = previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173),
            "tabs", List.of(obj("id", "local", "title", "Local", "url", "http://localhost:5173/"),
                obj("id", "remote", "title", "Remote", "url", "https://example.test/")), "activeTabId", "local"));
        Map<String, Object> revoked = previews.update("a", sessionId, obj("expectedVersion", configured.get("version"), "allowedLoopbackPorts", List.of()));
        assertEquals(List.of("remote"), objects(revoked.get("tabs")).stream().map(tab -> str(tab, "id")).toList());
        assertEquals("", revoked.get("activeTabId"));
        assertEquals(num(configured, "authorizationVersion", 0) + 1, num(revoked, "authorizationVersion", 0));
    }

    @Test
    void loopbackAliasesRequireExactBindingAndUrlsDropQueryAndFragment() {
        previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173)));
        List<String> hosts = List.of("127.0.0.1", "localhost", "localhost.", "a.localhost", "[::1]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]");
        int version = 2;
        for (String host : hosts) {
            Map<String, Object> saved = previews.update("a", sessionId, obj("expectedVersion", version,
                "tabs", List.of(obj("id", "one", "title", "", "url", "http://" + host + ":5173/path?code=secret#token"))));
            assertEquals("http://" + host + ":5173/path", objects(saved.get("tabs")).getFirst().get("url"));
            assertFalse(saved.toString().contains("secret")); version++;
        }
        for (String host : List.of("127.0.0.1", "localhost", "a.localhost", "[::1]", "[0:0:0:0:0:0:0:1]", "[::ffff:127.0.0.1]")) {
            int expected = version;
            assertEquals(403, assertThrows(ApiException.class, () -> previews.update("a", sessionId,
                obj("expectedVersion", expected, "tabs", List.of(obj("id", "two", "title", "", "url", "http://" + host + ":5174/"))))).status());
        }
    }

    @Test
    void ambiguousNumericHostsAreRejectedAndDefaultPathIsNormalized() {
        previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173)));
        for (String url : List.of("http://127.1:5173/", "http://2130706433:5173/", "http://0x7f000001:5173/", "http://127.00.0.1:5173/")) {
            assertEquals(400, assertThrows(ApiException.class, () -> previews.update("a", sessionId,
                obj("expectedVersion", 2, "tabs", List.of(obj("id", "one", "title", "", "url", url))))).status());
        }
        Map<String, Object> saved = previews.update("a", sessionId, obj("expectedVersion", 2,
            "tabs", List.of(obj("id", "one", "title", "", "url", "https://example.test?token=secret#code"))));
        assertEquals("https://example.test/", objects(saved.get("tabs")).getFirst().get("url"));
        assertFalse(saved.toString().contains("secret"));
    }

    private int updateVersion() {
        try { previews.update("a", sessionId, obj("expectedVersion", 1, "allowedLoopbackPorts", List.of(5173))); return 200; }
        catch (ApiException exception) { return exception.status(); }
    }
}
