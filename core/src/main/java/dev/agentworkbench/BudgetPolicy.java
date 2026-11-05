package dev.agentworkbench;

import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 根预算包含全部模型调用；普通开发不能占用剩余审查预留。
 */
public final class BudgetPolicy {
    private BudgetPolicy() { }

    public static boolean acquire(Map<String, Object> budget, boolean verification) {
        int max = num(budget, "maxCalls", 24);
        int used = num(budget, "usedCalls", 0);
        int reserved = Math.max(0, num(budget, "reserveCalls", 6) - num(budget, "usedReservedCalls", 0));
        if (used >= max || (!verification && used + reserved >= max)) { return false; }
        budget.put("usedCalls", used + 1);
        String field = verification ? "usedReservedCalls" : "usedDevelopmentCalls";
        budget.put(field, num(budget, field, 0) + 1);
        budget.put("remainingCalls", max - used - 1);
        budget.put("developmentAvailable", Math.max(0, max - used - 1 - (verification ? Math.max(0, reserved - 1) : reserved)));
        return true;
    }

    public static boolean matchesLocks(Map<String, Object> route, Map<String, Object> locks) {
        for (String key : new String[] {"engine", "model", "provider", "routeId"}) {
            String locked = str(locks, key);
            String actual = str(route, "routeId".equals(key) ? "id" : key);
            if (!locked.isBlank() && !locked.equals(actual)) { return false; }
        }
        return true;
    }
}
