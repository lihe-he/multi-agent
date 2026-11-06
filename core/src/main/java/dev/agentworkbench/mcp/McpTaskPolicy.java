package dev.agentworkbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ExecutionStore;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 固定参数动作的授权快照；外部只读声明不扩大阶段权限。
 */
public final class McpTaskPolicy {
    private static final ObjectMapper JSON = new ObjectMapper().enable(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS);
    private static final Set<String> KEYS = Set.of("type", "properties", "required", "additionalProperties", "items", "enum", "const", "minimum", "maximum", "minLength", "maxLength", "minItems", "maxItems", "description", "title", "default", "examples", "$schema");
    private McpTaskPolicy() { }

    public static String canonical(Object value) {
        try { return JSON.writeValueAsString(value); } catch (Exception error) { throw new ApiException(400, "工具参数不是可保存的 JSON"); }
    }

    public static String hash(Object value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical(value).getBytes(StandardCharsets.UTF_8))); }
        catch (Exception error) { throw new IllegalStateException(error); }
    }

    public static Map<String, Object> candidates(ExecutionStore store, String projectId) {
        store.get("project", projectId);
        List<Map<String, Object>> tools = new ArrayList<>();
        for (Map<String, Object> server : store.list("mcpServer")) {
            if (!strings(server.get("projectIds")).contains(projectId)) { continue; }
            Map<String, Object> discovery = map(server, "discovery");
            for (Map<String, Object> tool : objects(discovery.get("tools"))) {
                String reason = "";
                if (!bool(server, "enabled", false)) { reason = "服务已停用"; }
                else if (!strings(server.get("allowedTools")).contains(str(tool, "name"))) { reason = "工具未授权"; }
                else if (!"AVAILABLE".equals(str(server, "health")) || str(discovery, "definitionVersion").isBlank()) { reason = "请重新探测服务"; }
                else { try { validateSchema(map(tool, "inputSchema"), 0); } catch (ApiException error) { reason = error.getMessage(); } }
                tools.add(obj("serverId", server.get("id"), "serverName", server.get("name"), "identity", server.get("identity"), "configVersion", server.get("version"),
                    "definitionVersion", discovery.get("definitionVersion"), "name", tool.get("name"), "description", tool.getOrDefault("description", ""), "inputSchema", tool.get("inputSchema"),
                    "available", reason.isBlank(), "disabledReason", reason, "mode", "FIXED_ARGUMENTS", "allowedStageKinds", List.of("IMPLEMENT", "REPAIR")));
            }
        }
        return obj("tools", tools, "limitations", List.of("仅 API 实现/修复阶段调用用户确认的固定参数", "只读阶段不开放外部 MCP", "同一任务固定动作最多自动派发一次", "不支持动态参数、原生 CLI MCP 注入或自动重试"));
    }

    public static Map<String, Object> snapshot(ExecutionStore store, String projectId, Map<String, Object> route, Object raw, int version) {
        List<Map<String, Object>> requested = objects(raw);
        if (requested.size() > 16 || (!requested.isEmpty() && !"API".equals(str(route, "engine")))) { throw new ApiException(400, "固定 MCP 动作最多 16 项，且仅 API 运行器支持"); }
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> item : requested) {
            String serverId = required(item, "serverId", 100), name = required(item, "name", 200);
            Map<String, Object> server = store.get("mcpServer", serverId), discovery = map(server, "discovery");
            if (!bool(server, "enabled", false) || !strings(server.get("projectIds")).contains(projectId) || !strings(server.get("allowedTools")).contains(name)) { throw new ApiException(403, "MCP 项目或动作未授权"); }
            if (num(item, "configVersion", -1) != num(server, "version", -2) || str(item, "definitionVersion").isBlank() || !str(item, "definitionVersion").equals(str(discovery, "definitionVersion")) || !"AVAILABLE".equals(str(server, "health"))) { throw new ApiException(409, "MCP 配置或定义已变化，请重新探测并确认动作"); }
            Map<String, Object> tool = objects(discovery.get("tools")).stream().filter(value -> name.equals(str(value, "name"))).findFirst().orElseThrow(() -> new ApiException(409, "固定工具不在已发现定义中"));
            if (!(item.get("fixedArguments") instanceof Map<?, ?>)) { throw new ApiException(400, "fixedArguments 必须为完整 JSON 对象"); }
            Map<String, Object> args = map(item, "fixedArguments");
            if (canonical(args).getBytes(StandardCharsets.UTF_8).length > 16384) { throw new ApiException(400, "固定工具参数超过 16 KB"); }
            rejectCredentials(args);
            validateSchema(map(tool, "inputSchema"), 0); validateValue(map(tool, "inputSchema"), args, "$", 0);
            int timeout = num(item, "timeoutMs", 30000);
            if (timeout < 1000 || timeout > 60000) { throw new ApiException(400, "工具超时范围为 1000 至 60000 毫秒"); }
            String actionKey = hash(obj("serverId", serverId, "name", name, "arguments", args));
            if (bindings.stream().anyMatch(value -> actionKey.equals(str(value, "actionKey")))) { throw new ApiException(400, "相同固定动作不能重复授权"); }
            bindings.add(obj("serverId", serverId, "serverName", server.get("name"), "name", name, "identity", server.get("identity"), "configVersion", server.get("version"),
                "definitionVersion", discovery.get("definitionVersion"), "fixedArguments", args, "inputSchema", tool.get("inputSchema"), "timeoutMs", timeout,
                "actionKey", actionKey, "alias", "mcp_" + actionKey.substring(0, 40), "allowedStageKinds", List.of("IMPLEMENT", "REPAIR"), "description", tool.getOrDefault("description", "")));
        }
        return obj("version", version, "mode", "FIXED_ARGUMENTS", "projectId", projectId, "bindings", bindings, "createdAt", now());
    }

    public static Map<String, Object> budget(Map<String, Object> input, Map<String, Object> previous) {
        int calls = num(input, "maxCalls", num(previous, "maxCalls", 8)), duration = num(input, "maxDurationMs", num(previous, "maxDurationMs", 240000));
        int used = num(previous, "usedCalls", 0), committed = num(previous, "committedDurationMs", 0);
        if (calls < 1 || calls > 64 || duration < 1000 || duration > 3600000 || calls < used || duration < committed) { throw new ApiException(400, "工具预算越界或低于已登记消耗"); }
        return obj("maxCalls", calls, "maxDurationMs", duration, "usedCalls", used, "committedDurationMs", committed);
    }

    public static void assertCurrent(ExecutionStore store, Map<String, Object> task) {
        Map<String, Object> auth = map(task, "mcpAuthorization");
        for (Map<String, Object> binding : objects(auth.get("bindings"))) {
            Map<String, Object> live = store.find("mcpServer", str(binding, "serverId"));
            if (live == null || !bool(live, "enabled", false) || !str(binding, "identity").equals(str(live, "identity")) || num(binding, "configVersion", -1) != num(live, "version", -2)
                || !strings(live.get("projectIds")).contains(str(task, "projectId")) || !strings(live.get("allowedTools")).contains(str(binding, "name"))
                || !str(binding, "definitionVersion").equals(str(map(live, "discovery"), "definitionVersion"))) { throw new ApiException(409, "MCP 授权、服务身份或定义变化，需要重新确认固定动作"); }
        }
    }

    private static void rejectCredentials(Object value) {
        if (value instanceof Map<?, ?> values) {
            for (var entry : values.entrySet()) {
                if (entry.getKey().toString().toLowerCase(java.util.Locale.ROOT).matches(".*(api.?key|token|password|secret|authorization|credential).*")) { throw new ApiException(400, "固定参数不能保存凭据，请使用服务独立会话凭据"); }
                rejectCredentials(entry.getValue());
            }
        } else if (value instanceof List<?> values) { values.forEach(McpTaskPolicy::rejectCredentials); }
    }

    private static void validateSchema(Map<String, Object> schema, int depth) {
        if (depth > 16 || !KEYS.containsAll(schema.keySet()) || !Set.of("object", "array", "string", "number", "integer", "boolean", "null").contains(str(schema, "type"))) { throw new ApiException(400, "本轮不能自动验证该工具 schema，请使用手动调用"); }
        if (schema.containsKey("additionalProperties") && !(schema.get("additionalProperties") instanceof Boolean)) { throw new ApiException(400, "本轮仅支持布尔 additionalProperties"); }
        for (Object child : map(schema, "properties").values()) { validateSchema(map(child), depth + 1); }
        if ("array".equals(str(schema, "type"))) { validateSchema(map(schema, "items"), depth + 1); }
    }

    private static void validateValue(Map<String, Object> schema, Object value, String path, int depth) {
        if (depth > 16) { throw new ApiException(400, "固定参数嵌套过深"); }
        boolean type = switch (str(schema, "type")) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "number" -> value instanceof Number;
            case "integer" -> value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue());
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> false;
        };
        if (!type) { throw new ApiException(400, path + " 类型不符合工具定义"); }
        if (schema.get("enum") instanceof List<?> values && values.stream().noneMatch(item -> canonical(item).equals(canonical(value)))) { throw new ApiException(400, path + " 不在工具枚举范围"); }
        if (schema.containsKey("const") && !canonical(schema.get("const")).equals(canonical(value))) { throw new ApiException(400, path + " 与固定常量不符"); }
        if (value instanceof Map<?, ?>) {
            Map<String, Object> object = map(value), properties = map(schema, "properties");
            if (!object.keySet().containsAll(strings(schema.get("required")))) { throw new ApiException(400, path + " 缺少必要工具参数"); }
            for (var entry : object.entrySet()) {
                if (properties.containsKey(entry.getKey())) { validateValue(map(properties.get(entry.getKey())), entry.getValue(), path + "." + entry.getKey(), depth + 1); }
                else if (Boolean.FALSE.equals(schema.get("additionalProperties"))) { throw new ApiException(400, path + " 包含未定义参数"); }
            }
        } else if (value instanceof List<?> values) {
            bounds(values.size(), schema, "minItems", "maxItems", path);
            for (Object item : values) { validateValue(map(schema, "items"), item, path + "[]", depth + 1); }
        } else if (value instanceof String text) { bounds(text.codePointCount(0, text.length()), schema, "minLength", "maxLength", path); }
        else if (value instanceof Number number) { bounds(number.doubleValue(), schema, "minimum", "maximum", path); }
    }

    private static void bounds(double value, Map<String, Object> schema, String minimum, String maximum, String path) {
        if ((schema.get(minimum) instanceof Number min && value < min.doubleValue()) || (schema.get(maximum) instanceof Number max && value > max.doubleValue())) { throw new ApiException(400, path + " 超出工具参数范围"); }
    }
}
