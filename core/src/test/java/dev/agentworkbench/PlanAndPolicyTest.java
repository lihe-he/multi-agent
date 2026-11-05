package dev.agentworkbench;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class PlanAndPolicyTest {
    private final PlanValidator validator = new PlanValidator();

    private Map<String, Object> stage(String id, List<String> dependencies) {
        return obj("id", id, "title", id, "role", "developer", "dependencies", dependencies, "inputs", List.of(), "outputs", List.of(id + ":result"), "acceptance", List.of("evidence"), "termination", "bounded", "permissions", List.of("read"), "resources", List.of());
    }

    private Map<String, Object> request(Map<String, Object>... stages) { return obj("stages", List.of(stages), "permissions", List.of("read", "write"), "maxRoles", 6); }

    @Test
    void rejectsCycleBeforeDispatch() {
        var result = validator.validate(request(stage("a", List.of("b")), stage("b", List.of("a"))));
        assertFalse(bool(result, "valid", true));
        assertTrue(strings(result.get("errors")).stream().anyMatch(message -> message.contains("循环")));
    }

    @Test
    void rejectsMissingArtifactAndDelegatedPermission() {
        var stage = stage("a", List.of()); stage.put("inputs", List.of("missing:artifact")); stage.put("permissions", List.of("shell-admin"));
        var result = validator.validate(request(stage));
        assertFalse(bool(result, "valid", true)); assertEquals(2, strings(result.get("errors")).size());
    }

    @Test
    void rejectsParallelExclusiveWritesButAllowsOrderedHandoff() {
        var a = stage("a", List.of()); var b = stage("b", List.of());
        a.put("resources", List.of("write:database")); b.put("resources", List.of("write:database"));
        assertFalse(bool(validator.validate(request(a, b)), "valid", true));
        b.put("dependencies", List.of("a")); b.put("inputs", List.of("a:result"));
        assertTrue(bool(validator.validate(request(a, b)), "valid", false));
    }

    @Test
    void developmentCannotBorrowVerificationReserve() {
        var budget = obj("maxCalls", 5, "reserveCalls", 2, "usedCalls", 0);
        assertTrue(BudgetPolicy.acquire(budget, false)); assertTrue(BudgetPolicy.acquire(budget, false)); assertTrue(BudgetPolicy.acquire(budget, false));
        assertFalse(BudgetPolicy.acquire(budget, false)); assertEquals(3, num(budget, "usedCalls", -1));
        assertTrue(BudgetPolicy.acquire(budget, true)); assertTrue(BudgetPolicy.acquire(budget, true));
        assertFalse(BudgetPolicy.acquire(budget, true)); assertFalse(BudgetPolicy.acquire(budget, false));
        assertEquals(5, num(budget, "usedCalls", -1));
    }

    @Test
    void previouslyUsedReserveIsNotChargedTwice() {
        var budget = obj("maxCalls", 8, "reserveCalls", 3, "usedCalls", 4, "usedReservedCalls", 2);
        assertTrue(BudgetPolicy.acquire(budget, false)); assertTrue(BudgetPolicy.acquire(budget, false)); assertTrue(BudgetPolicy.acquire(budget, false));
        assertFalse(BudgetPolicy.acquire(budget, false)); assertTrue(BudgetPolicy.acquire(budget, true));
        assertEquals(8, num(budget, "usedCalls", -1));
    }

    @Test
    void hardLocksCannotSilentlyFallBack() {
        var route = obj("id", "route1", "engine", "API", "model", "chosen", "provider", "local");
        assertTrue(BudgetPolicy.matchesLocks(route, obj("model", "chosen", "routeId", "route1")));
        assertFalse(BudgetPolicy.matchesLocks(route, obj("model", "different")));
        assertFalse(BudgetPolicy.matchesLocks(route, obj("provider", "other")));
    }

    @Test
    void cancelledResourceWaitHoldsNoPartialReservations() throws Exception {
        ResourceManager resources = new ResourceManager(); AtomicBoolean cancelled = new AtomicBoolean();
        try (var occupied = resources.acquire(Map.of("model", 2), () -> false)) {
            CompletableFuture<Boolean> waiting = CompletableFuture.supplyAsync(() -> {
                try (var ignored = resources.acquire(Map.of("model", 1, "write:db", 1), cancelled::get)) { return false; }
                catch (CancellationException exception) { return true; }
            });
            Thread.sleep(80);
            try (var db = resources.acquire(Map.of("write:db", 1), () -> false)) { assertEquals(1, resources.snapshot().get("write:db")); }
            cancelled.set(true); assertTrue(waiting.get(2, TimeUnit.SECONDS)); assertEquals(2, resources.activeModels());
        }
        assertEquals(0, resources.activeModels());
    }

    @Test
    void impossibleResourceClaimIsRejectedInsteadOfWaitingForever() {
        assertThrows(ApiException.class, () -> new ResourceManager().acquire(Map.of("model", 3), () -> false));
    }
}
