package dev.agentworkbench;

import org.springframework.stereotype.Component;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 校验可确定的依赖、产物、权限及排他资源约束。
 */
@Component
public class PlanValidator {
    public Map<String, Object> validate(Map<String, Object> request) {
        List<Map<String, Object>> stages = objects(request.get("stages"));
        List<String> errors = new ArrayList<>();
        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Set<String> allowed = new HashSet<>(strings(request.get("permissions")));
        if (stages.isEmpty() || stages.size() > 64) { errors.add("计划需要 1 至 64 个阶段"); }
        for (Map<String, Object> stage : stages) {
            String id = str(stage, "id");
            if (id.isBlank() || byId.putIfAbsent(id, stage) != null) { errors.add("阶段编号缺失或重复：" + id); }
            for (String field : List.of("title", "role")) {
                if (str(stage, field).isBlank()) { errors.add(id + " 缺少 " + field); }
            }
            if (strings(stage.get("outputs")).isEmpty()) { errors.add(id + " 缺少输出产物"); }
            if (strings(stage.get("acceptance")).isEmpty()) { errors.add(id + " 缺少验收条件"); }
            if (str(stage, "termination").isBlank()) { errors.add(id + " 缺少终止条件"); }
            if (!allowed.containsAll(strings(stage.get("permissions")))) { errors.add(id + " 申请了未授权权限"); }
        }
        long roles = stages.stream().map(stage -> str(stage, "role")).distinct().count();
        int maxRoles = Math.max(1, Math.min(6, num(request, "maxRoles", 6)));
        if (roles > maxRoles) { errors.add("活动角色超过根任务上限 " + maxRoles); }
        Map<String, Integer> colors = new HashMap<>();
        for (Map<String, Object> stage : stages) {
            String id = str(stage, "id");
            for (String dependency : strings(stage.get("dependencies"))) {
                if (!byId.containsKey(dependency)) { errors.add(id + " 的依赖不存在：" + dependency); }
            }
            visit(id, byId, colors, errors);
        }
        for (Map<String, Object> stage : stages) {
            Set<String> ancestors = ancestors(str(stage, "id"), byId, new HashSet<>());
            Set<String> outputs = new HashSet<>(strings(request.get("inputs")));
            for (String ancestor : ancestors) {
                if (byId.containsKey(ancestor)) { outputs.addAll(strings(byId.get(ancestor).get("outputs"))); }
            }
            for (String input : strings(stage.get("inputs"))) {
                if (!outputs.contains(input)) { errors.add(str(stage, "id") + " 缺少输入提供方：" + input); }
            }
            for (Map<String, Object> other : stages) {
                String a = str(stage, "id"), b = str(other, "id");
                if (a.compareTo(b) >= 0 || ancestors.contains(b) || ancestors(b, byId, new HashSet<>()).contains(a)) { continue; }
                for (String resource : strings(stage.get("resources"))) {
                    if (resource.startsWith("write:") && strings(other.get("resources")).contains(resource)) {
                        errors.add(a + " 与 " + b + " 并行申请同一写入资源：" + resource);
                    }
                }
            }
        }
        return obj("valid", errors.isEmpty(), "errors", errors.stream().distinct().toList());
    }

    private void visit(String id, Map<String, Map<String, Object>> stages, Map<String, Integer> colors, List<String> errors) {
        int color = colors.getOrDefault(id, 0);
        if (color == 1) { errors.add("循环依赖：" + id); return; }
        if (color == 2 || !stages.containsKey(id)) { return; }
        colors.put(id, 1);
        for (String dependency : strings(stages.get(id).get("dependencies"))) { visit(dependency, stages, colors, errors); }
        colors.put(id, 2);
    }

    private Set<String> ancestors(String id, Map<String, Map<String, Object>> stages, Set<String> seen) {
        if (!stages.containsKey(id)) { return seen; }
        for (String dependency : strings(stages.get(id).get("dependencies"))) {
            if (seen.add(dependency)) { ancestors(dependency, stages, seen); }
        }
        return seen;
    }
}
