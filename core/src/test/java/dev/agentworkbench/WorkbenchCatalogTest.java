package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkbenchCatalogTest {
    @TempDir Path directory;

    @Test
    void nativeSandboxRequestFieldsSurviveRouteParsingAndEdits() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        try (var store = new ExecutionStore(mapper, directory.resolve("sandbox-routes").toString())) {
            WorkbenchCatalog catalog = new WorkbenchCatalog(store, new WorkspaceRuntime(mapper, directory.resolve("sandbox-runtime").toString()), new PlanValidator());
            Map<String, Object> first = catalog.saveRoute(obj("name", "strict CLI", "engine", "CODEX", "requireOsSandbox", true, "osSandboxNetwork", "allow"));

            assertEquals(Boolean.TRUE, first.get("requireOsSandbox"));
            assertEquals("ALLOW", first.get("osSandboxNetwork"));
            Map<String, Object> edited = catalog.saveRoute(obj("id", first.get("id"), "name", "strict CLI renamed", "engine", "CODEX"));
            assertEquals(Boolean.TRUE, edited.get("requireOsSandbox"), "omitting a field during ordinary route editing preserves the strict request");
            assertEquals("ALLOW", edited.get("osSandboxNetwork"));
            assertEquals(Boolean.TRUE, store.get("route", str(first, "id")).get("requireOsSandbox"));
            assertThrows(ApiException.class, () -> catalog.saveRoute(obj("name", "invalid strict API", "engine", "API", "requireOsSandbox", true,
                "baseUrl", "http://127.0.0.1:19000/v1")));
            ApiException nullNetwork = assertThrows(ApiException.class, () -> catalog.saveRoute(obj("name", "null network", "engine", "CODEX", "osSandboxNetwork", null)));
            assertEquals(400, nullNetwork.status());
        }
    }

    @Test
    void lateProbeCannotReplaceConcurrentRouteEditOrRestoreItsPriorRevision() throws Exception {
        CountDownLatch probing = new CountDownLatch(1), release = new CountDownLatch(1);
        AtomicInteger probes = new AtomicInteger();
        ObjectMapper mapper = new ObjectMapper();
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, directory.resolve("runtime").toString()) {
            @Override
            public Map<String, Object> probe(Map<String, Object> route) {
                if (probes.incrementAndGet() == 1) {
                    probing.countDown();
                    try {
                        if (!release.await(15, TimeUnit.SECONDS)) throw new IllegalStateException("Probe fixture was not released");
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                        throw new IllegalStateException("Probe fixture was interrupted", exception);
                    }
                }
                return obj("available", true, "version", "observed-route-" + num(route, "version", -1), "verified", false);
            }
        };
        try (var store = new ExecutionStore(mapper, directory.resolve("store").toString()); var executor = Executors.newFixedThreadPool(2)) {
            WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, new PlanValidator());
            Map<String, Object> first = catalog.saveRoute(obj("name", "Original", "engine", "API", "model", "original-model",
                "baseUrl", "http://127.0.0.1:19000/v1", "apiKey", "original-private-key"));
            String routeId = str(first, "id");
            var pending = executor.submit(() -> catalog.probe(routeId));
            try {
                assertTrue(probing.await(5, TimeUnit.SECONDS), "the old revision must have entered the probe before editing");
                var edit = executor.submit(() -> catalog.saveRoute(obj("id", routeId, "name", "Changed", "engine", "API", "model", "changed-model",
                    "baseUrl", "http://127.0.0.1:19001/v1", "apiKey", "changed-private-key", "enabled", false)));
                Map<String, Object> second = edit.get(5, TimeUnit.SECONDS);
                assertEquals(2, num(second, "version", -1));
                release.countDown();
                ExecutionException failure = assertThrows(ExecutionException.class, () -> pending.get(5, TimeUnit.SECONDS));
                assertInstanceOf(ApiException.class, failure.getCause());
                assertEquals(409, ((ApiException) failure.getCause()).status());
                Map<String, Object> current = store.get("route", routeId);
                assertEquals(2, num(current, "version", -1));
                assertEquals("Changed", str(current, "name"));
                assertEquals("changed-model", str(current, "model"));
                assertEquals("http://127.0.0.1:19001/v1", str(current, "baseUrl"));
                assertFalse(bool(current, "enabled", true));
                assertFalse(current.containsKey("probe"));
                assertFalse(current.containsKey("probedAt"));
                assertEquals("changed-private-key", catalog.credential(routeId, 2));
                assertEquals("original-private-key", catalog.credential(routeId, 1));
                assertEquals("observed-route-2", str(catalog.probe(routeId), "version"));
                assertEquals(2, num(store.get("route", routeId), "version", -1));
                assertEquals("observed-route-2", str(map(store.get("route", routeId), "probe"), "version"));
                assertTrue(store.list("operation").isEmpty());
                assertTrue(store.list("modelRequest").isEmpty());
            } finally { release.countDown(); }
        }
    }

}
