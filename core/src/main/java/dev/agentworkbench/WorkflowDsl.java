package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import static dev.agentworkbench.Json.*;

/**
 * Workflow 定义只描述有界节点和依赖；必要验证、独立审查与人工验收由根任务保留。
 */
final class WorkflowDsl {
    static final int VERSION = 1;
    static final Set<String> KINDS = Set.of("INVESTIGATE", "DESIGN", "PROPOSAL", "CRITIQUE", "SYNTHESIS", "IMPLEMENT");
    private static final Set<String> ROOT_FIELDS = Set.of("dslVersion", "inputs", "maxParallel", "nodes");
    private static final Set<String> NODE_FIELDS = Set.of("id", "kind", "title", "role", "goal", "definitionId", "definitionVersion",
        "dependencies", "inputs", "outputs", "scope", "permissions", "maxCalls", "timeoutSeconds", "acceptance", "termination");
    static final Set<String> ROOT_INPUTS = Set.of("goal", "contract", "context", "baseline");
    private static final Set<String> RESERVED_IDS = Set.of("baseline", "workflow", "verify", "review", "delivery", "prepare");

    private WorkflowDsl() { }

    /**
     * 完整检查后生成独立的规范定义，输入对象后续变化不影响已固定内容。
     */
    static Map<String, Object> normalize(Map<String, Object> input) {
        fields(input, ROOT_FIELDS, "Workflow");
        int version = integer(input, "dslVersion", -1, VERSION, VERSION);
        int parallel = integer(input, "maxParallel", 2, 1, 6);
        List<String> initial = list(input, "inputs", 4, 128, false);
        if (!ROOT_INPUTS.containsAll(initial)) throw bad("Workflow 输入只能引用目标、完成约定、固定上下文或代码基线");
        if (!(input.get("nodes") instanceof List<?> raw) || raw.isEmpty() || raw.size() > 64) throw bad("Workflow 需要 1 至 64 个节点");
        List<Map<String, Object>> nodes = new ArrayList<>();
        Set<String> ids = new HashSet<>(), roles = new HashSet<>(), products = new HashSet<>();
        for (Object value : raw) {
            if (!(value instanceof Map<?, ?> record) || record.keySet().stream().anyMatch(key -> !(key instanceof String))) throw bad("Workflow 节点必须为对象");
            Map<String, Object> node = map(record); fields(node, NODE_FIELDS, "Workflow 节点");
            String id = text(node, "id", 64), kind = text(node, "kind", 32), role = text(node, "role", 64);
            if (!id.matches("[A-Za-z][A-Za-z0-9_-]{0,63}") || RESERVED_IDS.contains(id) || !ids.add(id)) throw bad("节点编号无效、重复或占用了根门禁：" + id);
            if (!KINDS.contains(kind)) throw bad("Workflow 节点类型不受支持：" + kind);
            roles.add(role); if (roles.size() > 6) throw bad("Workflow 角色不能超过根任务的六角色上限");
            List<String> permissions = AgentPermissions.capabilities(list(node, "permissions", 6, 32, true));
            if (!permissions.contains("read")) throw bad(id + " 需要读取已有现场的能力");
            if (!"IMPLEMENT".equals(kind) && permissions.stream().anyMatch(permission -> Set.of("write", "verify", "mcp").contains(permission))) throw bad(id + " 的只读阶段不能申请写入、命令或 MCP 能力");
            if ("PROPOSAL".equals(kind) && permissions.contains("delegate")) throw bad("独立提案不共享团队委派能力");
            List<String> scope = AgentPermissions.scope(list(node, "scope", 100, 300, true));
            List<String> dependencies = list(node, "dependencies", 64, 64, false);
            List<String> inputs = list(node, "inputs", 64, 128, false), outputs = list(node, "outputs", 16, 128, true);
            for (String output : outputs) {
                product(output);
                if (ROOT_INPUTS.contains(output) || !products.add(output)) throw bad("节点输出遮蔽了根输入或提供方不唯一：" + output);
            }
            for (String name : inputs) product(name);
            String definition = text(node, "definitionId", 100);
            if (!definition.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) throw bad("Agent 定义编号无效");
            nodes.add(obj("id", id, "kind", kind, "title", text(node, "title", 180), "role", role, "goal", text(node, "goal", 12000),
                "definitionId", definition, "definitionVersion", integer(node, "definitionVersion", -1, 1, Integer.MAX_VALUE),
                "dependencies", dependencies, "inputs", inputs, "outputs", outputs, "scope", scope, "permissions", permissions,
                "maxCalls", integer(node, "maxCalls", 8, 1, 32), "timeoutSeconds", integer(node, "timeoutSeconds", 900, 1, 900),
                "acceptance", list(node, "acceptance", 16, 4000, true), "termination", text(node, "termination", 4000)));
        }
        Map<String, Map<String, Object>> byId = byId(nodes);
        for (Map<String, Object> node : nodes) for (String dependency : strings(node.get("dependencies"))) {
            if (!byId.containsKey(dependency) || dependency.equals(str(node, "id"))) throw bad(str(node, "id") + " 的依赖不存在或指向自身：" + dependency);
        }
        List<String> order = order(nodes);
        for (String id : order) {
            Set<String> available = new HashSet<>(initial);
            for (String ancestor : ancestors(id, byId)) available.addAll(strings(byId.get(ancestor).get("outputs")));
            for (String name : strings(byId.get(id).get("inputs"))) if (!available.contains(name)) throw bad(id + " 缺少实际前置输入提供方：" + name);
        }
        return obj("dslVersion", version, "inputs", initial, "maxParallel", parallel, "nodes", nodes);
    }

    /**
     * 同时就绪时保留声明顺序；运行器仍需核验节点终态和实际资源容量。
     */
    static List<String> order(List<Map<String, Object>> nodes) {
        LinkedHashSet<String> remaining = new LinkedHashSet<>(byId(nodes).keySet());
        Set<String> completed = new HashSet<>(); List<String> ordered = new ArrayList<>();
        while (!remaining.isEmpty()) {
            List<String> ready = nodes.stream().filter(node -> remaining.contains(str(node, "id"))
                && completed.containsAll(strings(node.get("dependencies")))).map(node -> str(node, "id")).toList();
            if (ready.isEmpty()) throw bad("Workflow 存在循环或无法满足的依赖");
            ordered.addAll(ready); completed.addAll(ready); remaining.removeAll(ready);
        }
        return ordered;
    }

    static Set<String> ancestors(String id, Map<String, Map<String, Object>> nodes) {
        Set<String> result = new LinkedHashSet<>(); List<String> pending = new ArrayList<>(strings(nodes.get(id).get("dependencies")));
        for (int index = 0; index < pending.size(); index++) {
            String dependency = pending.get(index);
            if (result.add(dependency)) pending.addAll(strings(nodes.get(dependency).get("dependencies")));
        }
        return result;
    }

    static Map<String, Map<String, Object>> byId(List<Map<String, Object>> nodes) {
        Map<String, Map<String, Object>> result = new LinkedHashMap<>();
        for (Map<String, Object> node : nodes) if (result.putIfAbsent(str(node, "id"), node) != null) throw bad("Workflow 节点编号重复");
        return result;
    }

    /**
     * 摘要绑定全部规范参数，不依赖 JSON 对象字段的声明顺序。
     */
    static String digest(Map<String, Object> normalized, ObjectMapper mapper) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(mapper.writeValueAsString(canonical(normalized)).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException("Workflow 定义摘要无法保存", exception); }
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> record) {
            Map<String, Object> result = new TreeMap<>(); record.forEach((key, item) -> result.put(String.valueOf(key), canonical(item))); return result;
        }
        if (value instanceof List<?> list) return list.stream().map(WorkflowDsl::canonical).toList();
        return value;
    }

    private static void fields(Map<String, Object> record, Set<String> allowed, String name) {
        if (record == null || !allowed.containsAll(record.keySet())) throw bad(name + " 含有未支持的字段，不能提供主机路径、脚本或额外授权");
    }

    private static String text(Map<String, Object> record, String key, int maximum) {
        if (!(record.get(key) instanceof String value) || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) throw bad(key + " 必须为有效文字，最多 " + maximum + " 字符");
        return value.trim();
    }

    private static int integer(Map<String, Object> record, String key, int fallback, int minimum, int maximum) {
        Object value = record.get(key);
        if (!record.containsKey(key) && fallback >= minimum && fallback <= maximum) return fallback;
        if (!(value instanceof Number number) || !Double.isFinite(number.doubleValue()) || number.doubleValue() != number.longValue()
                || number.longValue() < minimum || number.longValue() > maximum) throw bad(key + " 必须为 " + minimum + " 至 " + maximum + " 的整数");
        return number.intValue();
    }

    private static List<String> list(Map<String, Object> record, String key, int maximumItems, int maximumLength, boolean required) {
        if (!record.containsKey(key) && !required) return List.of();
        if (!(record.get(key) instanceof List<?> values) || values.size() > maximumItems || (required && values.isEmpty())
                || values.stream().anyMatch(value -> !(value instanceof String text) || text.isBlank() || text.length() > maximumLength || text.indexOf('\0') >= 0)) throw bad(key + " 必须为有界文字清单");
        List<String> result = values.stream().map(value -> ((String) value).trim()).toList();
        if (new HashSet<>(result).size() != result.size()) throw bad(key + " 不能包含重复项");
        return result;
    }

    private static void product(String name) {
        if (!name.matches("[A-Za-z][A-Za-z0-9_.:-]{0,127}")) throw bad("输入输出只能使用逻辑产物名称：" + name);
    }

    private static ApiException bad(String message) { return new ApiException(400, message); }
}
