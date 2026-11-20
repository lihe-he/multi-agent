package dev.agentworkbench.mcp;

import dev.agentworkbench.ApiException;
import org.junit.jupiter.api.Test;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;
import static dev.agentworkbench.Json.obj;
import static org.junit.jupiter.api.Assertions.*;

class StageInputMcpRegistryTest {
    private static final Duration LIFETIME = Duration.ofMinutes(1);

    @Test
    void tokenIsIndependentOpaqueAndRedactedByDefault() throws Exception {
        StageInputMcpRegistry registry = new StageInputMcpRegistry();
        var handle = registry.open(obj("invocationId", "private-invocation"), () -> obj("goal", "current"), LIFETIME);
        assertTrue(handle.token().matches("stage_[A-Za-z0-9_-]{43}"));
        assertEquals("/api/mcp/stage-input/" + handle.id(), handle.endpoint());
        assertFalse(handle.toString().contains(handle.token()));
        assertFalse(handle.toString().contains("private-invocation"));
        Object grant = grants(registry).get(handle.id());
        Field field = grant.getClass().getDeclaredField("tokenHash"); field.setAccessible(true);
        byte[] stored = (byte[]) field.get(grant);
        assertEquals(32, stored.length);
        assertArrayEquals(MessageDigest.getInstance("SHA-256").digest(handle.token().getBytes(StandardCharsets.UTF_8)), stored);
        assertFalse(grant.toString().contains(handle.token()));
    }

    @Test
    void exactBearerPathAndMethodAreRequiredWithoutCallingSupplier() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); AtomicInteger calls = new AtomicInteger();
        var handle = registry.open(obj("lease", "one"), () -> obj("count", calls.incrementAndGet()), LIFETIME);
        String authorization = bearer(handle);
        assertTrue(registry.permits(authorization, handle.endpoint(), "POST"));
        assertTrue(registry.permits(authorization, handle.endpoint(), "GET"));
        for (String method : List.of("post", "get", "DELETE", "PUT", "HEAD", "OPTIONS")) {
            assertFalse(registry.permits(authorization, handle.endpoint(), method));
        }
        for (String path : List.of(handle.endpoint() + "/", handle.endpoint() + "?q=1", handle.endpoint() + "/child",
            "/api/mcp/rpc/" + handle.id(), "/api/mcp/stage-input/", handle.endpoint().replace("stage-input", "stage%2dinput"))) {
            assertFalse(registry.permits(authorization, path, "POST"));
        }
        for (String invalid : List.of(handle.token(), "bearer " + handle.token(), "Bearer mcp_" + handle.token(),
            authorization + " ", authorization + "\n", "Bearer stage_" + "x".repeat(43))) {
            assertFalse(registry.permits(invalid, handle.endpoint(), "POST"));
        }
        assertFalse(registry.permits(null, handle.endpoint(), "POST"));
        assertFalse(registry.permits(authorization, null, "POST"));
        assertFalse(registry.permits(authorization, handle.endpoint(), null));
        assertEquals(0, calls.get());
    }

    @Test
    void everyReadUsesFreshSupplierDataAndDoesNotExposeStoredIdentity() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); AtomicInteger calls = new AtomicInteger();
        var handle = registry.open(obj("credential", "identity-secret"), () -> obj("revision", calls.incrementAndGet()), LIFETIME);
        assertEquals(obj("revision", 1), registry.read(bearer(handle), handle.id()));
        assertEquals(obj("revision", 2), registry.read(bearer(handle), handle.id()));
        assertEquals(2, calls.get());
        assertFalse(registry.read(bearer(handle), handle.id()).containsKey("credential"));
    }

    @Test
    void identityAndReturnedDataAreDeepImmutableSnapshots() throws Exception {
        StageInputMcpRegistry registry = new StageInputMcpRegistry();
        List<Object> sourceList = new ArrayList<>(List.of("original"));
        Map<String, Object> identity = obj("nested", obj("items", sourceList));
        Map<String, Object> payload = obj("items", new ArrayList<>(List.of("first")));
        var handle = registry.open(identity, () -> payload, LIFETIME);
        sourceList.set(0, "changed"); identity.put("added", true);
        Object grant = grants(registry).get(handle.id());
        Field field = grant.getClass().getDeclaredField("identity"); field.setAccessible(true);
        Map<?, ?> saved = (Map<?, ?>) field.get(grant);
        assertFalse(saved.containsKey("added"));
        assertEquals(List.of("original"), ((Map<?, ?>) saved.get("nested")).get("items"));
        assertThrows(UnsupportedOperationException.class, saved::clear);
        assertThrows(UnsupportedOperationException.class, () -> ((List<?>) ((Map<?, ?>) saved.get("nested")).get("items")).clear());
        Map<String, Object> result = registry.read(bearer(handle), handle.id());
        ((List<?>) payload.get("items")).clear();
        assertEquals(List.of("first"), result.get("items"));
        assertThrows(UnsupportedOperationException.class, result::clear);
    }

    @Test
    void expiryAtDeadlineRevocationAndRestartInvalidateTokens() {
        MutableClock clock = new MutableClock(); StageInputMcpRegistry registry = new StageInputMcpRegistry(clock);
        var expired = registry.open(obj(), () -> obj(), LIFETIME);
        clock.advance(LIFETIME);
        assertFalse(registry.permits(bearer(expired), expired.endpoint(), "GET"));
        assertUnavailable(() -> registry.read(bearer(expired), expired.id()));
        var revoked = registry.open(obj(), () -> obj(), LIFETIME);
        registry.revoke(revoked.id()); registry.revoke(revoked.id()); registry.revoke(null);
        assertFalse(registry.permits(bearer(revoked), revoked.endpoint(), "POST"));
        assertUnavailable(() -> registry.read(bearer(revoked), revoked.id()));
        var current = registry.open(obj(), () -> obj(), LIFETIME);
        StageInputMcpRegistry restarted = new StageInputMcpRegistry(clock);
        assertFalse(restarted.permits(bearer(current), current.endpoint(), "POST"));
        assertUnavailable(() -> restarted.read(bearer(current), current.id()));
    }

    @Test
    void crossGrantTokenCannotReadAnotherInvocation() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); AtomicInteger calls = new AtomicInteger();
        var first = registry.open(obj("invocation", "one"), () -> obj("one", true), LIFETIME);
        var second = registry.open(obj("invocation", "two"), () -> obj("calls", calls.incrementAndGet()), LIFETIME);
        assertFalse(registry.permits(bearer(first), second.endpoint(), "POST"));
        assertUnavailable(() -> registry.read(bearer(first), second.id()));
        assertUnavailable(() -> registry.read("Bearer platform-secret", second.id()));
        assertEquals(0, calls.get());
    }

    @Test
    void supplierFailureIsGenericAndPermanentlyRevokesTheGrant() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); AtomicInteger calls = new AtomicInteger();
        var handle = registry.open(obj("identity", "private-identity"), () -> {
            calls.incrementAndGet(); throw new IllegalStateException("private-identity stage_secret provider-key");
        }, LIFETIME);
        ApiException error = assertUnavailable(() -> registry.read(bearer(handle), handle.id()));
        assertNull(error.getCause()); assertFalse(error.toString().contains("provider-key"));
        assertFalse(error.toString().contains("private-identity")); assertFalse(error.toString().contains(handle.token()));
        assertFalse(registry.permits(bearer(handle), handle.endpoint(), "POST"));
        assertUnavailable(() -> registry.read(bearer(handle), handle.id())); assertEquals(1, calls.get());
    }

    @Test
    void nullOrUnsupportedSupplierDataFailsClosed() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry();
        var empty = registry.open(obj(), () -> null, LIFETIME);
        assertUnavailable(() -> registry.read(bearer(empty), empty.id()));
        var invalid = registry.open(obj(), () -> obj("mutable", new AtomicInteger()), LIFETIME);
        assertUnavailable(() -> registry.read(bearer(invalid), invalid.id()));
    }

    @Test
    void expiryDuringSupplierExecutionIsRecheckedBeforeReturning() {
        MutableClock clock = new MutableClock(); StageInputMcpRegistry registry = new StageInputMcpRegistry(clock);
        var handle = registry.open(obj(), () -> { clock.advance(LIFETIME); return obj("secret", "must-not-return"); }, LIFETIME);
        assertUnavailable(() -> registry.read(bearer(handle), handle.id()));
        assertFalse(registry.permits(bearer(handle), handle.endpoint(), "POST"));
    }

    @Test
    void revokeDoesNotWaitForSupplierHoldingAnInvocationCallbackLock() throws Exception {
        StageInputMcpRegistry registry = new StageInputMcpRegistry();
        ReentrantLock callbackLock = new ReentrantLock(); CountDownLatch entered = new CountDownLatch(1);
        var handle = registry.open(obj(), () -> {
            entered.countDown(); callbackLock.lock();
            try { return obj("secret", "must-not-return"); } finally { callbackLock.unlock(); }
        }, LIFETIME);
        callbackLock.lock();
        try (var executor = Executors.newFixedThreadPool(2)) {
            Future<Map<String, Object>> reading = executor.submit(() -> registry.read(bearer(handle), handle.id()));
            try {
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                executor.submit(() -> registry.revoke(handle.id())).get(5, TimeUnit.SECONDS);
            } finally { callbackLock.unlock(); }
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> reading.get(5, TimeUnit.SECONDS));
            assertInstanceOf(ApiException.class, failure.getCause());
            assertEquals("Stage input unavailable", failure.getCause().getMessage());
        }
    }

    @Test
    void supplierCanRevokeItsOwnGrantWithoutReturningData() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); AtomicReference<String> id = new AtomicReference<>();
        var handle = registry.open(obj(), () -> { registry.revoke(id.get()); return obj("secret", "must-not-return"); }, LIFETIME);
        id.set(handle.id()); assertUnavailable(() -> registry.read(bearer(handle), handle.id()));
    }

    @Test
    void concurrentOpenNeverExceeds256ActiveGrants() throws Exception {
        StageInputMcpRegistry registry = new StageInputMcpRegistry(); CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        try (var executor = Executors.newFixedThreadPool(16)) {
            for (int index = 0; index < 300; index++) {
                results.add(executor.submit(() -> {
                    assertTrue(start.await(5, TimeUnit.SECONDS));
                    try { registry.open(obj(), () -> obj(), LIFETIME); return true; }
                    catch (ApiException exception) { assertEquals(409, exception.status()); return false; }
                }));
            }
            start.countDown(); int accepted = 0;
            for (Future<Boolean> result : results) { if (result.get(10, TimeUnit.SECONDS)) { accepted++; } }
            assertEquals(256, accepted); assertEquals(256, grants(registry).size());
        }
    }

    @Test
    void revokedAndExpiredEntriesReleaseCapacity() {
        MutableClock clock = new MutableClock(); StageInputMcpRegistry registry = new StageInputMcpRegistry(clock);
        StageInputMcpRegistry.Handle first = registry.open(obj(), () -> obj(), LIFETIME);
        for (int index = 1; index < 256; index++) { registry.open(obj(), () -> obj(), LIFETIME); }
        assertEquals(409, assertThrows(ApiException.class, () -> registry.open(obj(), () -> obj(), LIFETIME)).status());
        registry.revoke(first.id()); assertNotNull(registry.open(obj(), () -> obj(), LIFETIME));
        clock.advance(LIFETIME); assertNotNull(registry.open(obj(), () -> obj(), LIFETIME));
    }

    @Test
    void invalidLifetimeOrIdentityCannotCreateAGrant() {
        StageInputMcpRegistry registry = new StageInputMcpRegistry();
        for (Duration lifetime : List.of(Duration.ZERO, Duration.ofSeconds(-1), Duration.ofSeconds(Long.MAX_VALUE))) {
            assertEquals(400, assertThrows(ApiException.class, () -> registry.open(obj(), () -> obj(), lifetime)).status());
        }
        assertEquals(400, assertThrows(ApiException.class, () -> registry.open(null, () -> obj(), LIFETIME)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> registry.open(obj(), null, LIFETIME)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> registry.open(obj(), () -> obj(), null)).status());
        Map<String, Object> cyclic = new LinkedHashMap<>(); cyclic.put("cycle", cyclic);
        assertEquals(400, assertThrows(ApiException.class, () -> registry.open(cyclic, () -> obj(), LIFETIME)).status());
        assertEquals(400, assertThrows(ApiException.class, () -> registry.open(obj("mutable", new AtomicInteger()), () -> obj(), LIFETIME)).status());
    }

    private static String bearer(StageInputMcpRegistry.Handle handle) { return "Bearer " + handle.token(); }

    private static ApiException assertUnavailable(org.junit.jupiter.api.function.Executable action) {
        ApiException error = assertThrows(ApiException.class, action);
        assertEquals(403, error.status()); assertEquals("Stage input unavailable", error.getMessage()); return error;
    }

    private static Map<?, ?> grants(StageInputMcpRegistry registry) throws Exception {
        Field field = StageInputMcpRegistry.class.getDeclaredField("grants"); field.setAccessible(true); return (Map<?, ?>) field.get(registry);
    }

    private static final class MutableClock extends Clock {
        private final AtomicReference<Instant> now = new AtomicReference<>(Instant.parse("2026-11-20T00:00:00Z"));
        private void advance(Duration duration) { now.updateAndGet(value -> value.plus(duration)); }
        @Override public ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(ZoneId zone) { return this; }
        @Override public Instant instant() { return now.get(); }
    }
}
