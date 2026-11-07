package dev.agentworkbench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 创建任务时固定可委派定义、运行路由和根限制，模型不能从实时配置扩充授权。
 */
public final class CollaborationPolicy {
    private CollaborationPolicy() { }

    public static Map<String, Object> snapshot(ExecutionStore store, String projectId, Map<String, Object> route, Map<String, Object> locks, Map<String, Object> requested) {
        if (requested.containsKey("enabled") && !(requested.get("enabled") instanceof Boolean)) throw new ApiException(400, "enabled 必须为布尔值");
        boolean enabled = bool(requested, "enabled", false);
        if (enabled && !"API".equals(str(route, "engine"))) throw new ApiException(400, "动态委派需要可释放逐请求容量的 API 运行器");
        int active = bound(requested, "maxActive", 6, 2, 6), depth = bound(requested, "maxDepth", 3, 1, 4), total = bound(requested, "maxTotal", 24, 1, 64);
        List<Map<String, Object>> definitions = new ArrayList<>();
        List<String> selected = identifiers(requested, "definitionIds"), allowedRoutes = identifiers(requested, "allowedRouteIds");
        if (enabled && requested.containsKey("definitionIds") && selected.isEmpty()) throw new ApiException(400, "请至少选择一个可委派定义");
        Map<String, Object> expectedVersions = map(requested, "definitionVersions");
        if (enabled && requested.containsKey("definitionVersions") && (!(requested.get("definitionVersions") instanceof Map<?, ?>) || !new java.util.HashSet<>(selected).equals(expectedVersions.keySet())
                || expectedVersions.values().stream().anyMatch(value -> !(value instanceof Number number) || number.intValue() < 1 || number.doubleValue() != number.intValue()))) throw new ApiException(400, "所选定义版本清单无效");
        for (Map<String, Object> definition : store.list("agentDefinition")) {
            if (!selected.isEmpty() && !selected.contains(str(definition, "id"))) continue;
            if (enabled && expectedVersions.containsKey(str(definition, "id")) && num(expectedVersions, str(definition, "id"), -1) != num(definition, "version", 1)) throw new ApiException(409, "副 Agent 定义已变化，请刷新并核对：" + str(definition, "name"));
            if (!bool(definition, "enabled", true) || (!str(definition, "projectId").isBlank() && !projectId.equals(str(definition, "projectId")))) continue;
            Map<String, Object> resolvedRoute = str(definition, "routeId").isBlank() ? route : store.get("route", str(definition, "routeId"));
            if (!"API".equals(str(resolvedRoute, "engine")) || !bool(resolvedRoute, "enabled", true) || !BudgetPolicy.matchesLocks(resolvedRoute, locks)) {
                if (selected.contains(str(definition, "id"))) throw new ApiException(409, "副 Agent 路由不符合任务锁定或能力：" + str(definition, "name"));
                continue;
            }
            if (!str(resolvedRoute, "id").equals(str(route, "id")) && !allowedRoutes.contains(str(resolvedRoute, "id"))) {
                if (selected.contains(str(definition, "id"))) throw new ApiException(409, "请显式确认副 Agent 的数据发送路由：" + str(definition, "name"));
                continue;
            }
            Map<String, Object> copy = new LinkedHashMap<>(definition);
            copy.put("routeSnapshot", new LinkedHashMap<>(resolvedRoute)); definitions.add(copy);
        }
        if (enabled && definitions.isEmpty()) throw new ApiException(400, "启用委派需要至少一个允许的 Agent 定义");
        if (enabled && !selected.isEmpty() && !definitions.stream().map(definition -> str(definition, "id")).toList().containsAll(selected)) throw new ApiException(404, "所选 Agent 定义不可用于此项目");
        return obj("enabled", enabled, "version", 1, "maxActive", active, "maxDepth", depth, "maxTotal", total, "spawned", 0, "definitions", definitions);
    }

    private static int bound(Map<String, Object> input, String key, int fallback, int min, int max) {
        if (input.containsKey(key) && (!(input.get(key) instanceof Number number) || number.doubleValue() != number.intValue())) throw new ApiException(400, key + " 必须为整数");
        int value = num(input, key, fallback);
        if (value < min || value > max) throw new ApiException(400, key + " 必须在 " + min + " 至 " + max + " 之间");
        return value;
    }

    private static List<String> identifiers(Map<String, Object> input, String key) {
        if (!input.containsKey(key)) return List.of();
        if (!(input.get(key) instanceof List<?> list) || list.size() > 100 || list.stream().anyMatch(value -> !(value instanceof String id) || id.isBlank() || id.length() > 100)) throw new ApiException(400, key + " 必须为有效身份清单");
        return strings(input.get(key)).stream().distinct().toList();
    }

    public static Map<String, Object> rootPermissions(Map<String, Object> task, Map<String, Object> stage) {
        List<String> phase = strings(stage.get("permissions"));
        if (phase.isEmpty()) phase = bool(stage, "readOnly", true) ? List.of("read", "search") : List.of("read", "search", "write", "verify");
        List<String> root = strings(map(task, "contract").get("permissions"));
        List<Map<String, Object>> profiles = objects(task.get("profileSnapshot")).stream().filter(profile -> str(stage, "role").equals(str(profile, "role"))).toList();
        List<String> actor = profiles.isEmpty() ? phase : strings(profiles.getFirst().get("capabilities"));
        List<String> actual = new ArrayList<>(phase.stream().filter(root::contains).filter(actor::contains).toList());
        if (!"REVIEW".equals(str(stage, "kind")) && !"PROPOSAL".equals(str(stage, "kind")) && bool(map(task, "collaboration"), "enabled", false)) actual.add("delegate");
        if (actual.contains("write") && !objects(map(task, "mcpAuthorization").get("bindings")).isEmpty()) actual.add("mcp");
        return obj("capabilities", actual.stream().distinct().toList(), "readScopeLayers", List.of(List.of("**")), "writeScopeLayers", List.of(AgentPermissions.scope(map(task, "contract").get("scope"))));
    }
}
