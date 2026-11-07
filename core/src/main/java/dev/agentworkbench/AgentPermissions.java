package dev.agentworkbench;

import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 能力取交集，范围按多层约束逐层匹配，不能把两个 glob 的并集当作委派授权。
 */
public final class AgentPermissions {
    public static final Set<String> CAPABILITIES = Set.of("read", "search", "write", "verify", "delegate", "mcp");
    private AgentPermissions() { }

    public static List<String> capabilities(Object value) {
        if (!(value instanceof List<?> list) || list.stream().anyMatch(item -> !(item instanceof String)) || !CAPABILITIES.containsAll(list)) {
            throw new ApiException(400, "Agent 能力清单无效");
        }
        return strings(value).stream().distinct().sorted().toList();
    }

    public static List<String> scope(Object value) {
        if (value == null) return List.of("**");
        if (!(value instanceof List<?> list) || list.isEmpty() || list.size() > 100 || list.stream().anyMatch(item -> !(item instanceof String))) throw new ApiException(400, "委派范围需要 1 至 100 项");
        List<String> patterns = new ArrayList<>();
        for (String raw : strings(value)) {
            String pattern = raw.replace('\\', '/');
            if (pattern.isBlank() || pattern.length() > 300 || pattern.startsWith("/") || pattern.contains(":") || List.of(pattern.split("/", -1)).contains("..")) throw new ApiException(400, "范围必须为项目内相对路径或 glob");
            try { FileSystems.getDefault().getPathMatcher("glob:" + pattern); }
            catch (IllegalArgumentException exception) { throw new ApiException(400, "范围 glob 无效"); }
            patterns.add(pattern);
        }
        return patterns.stream().distinct().toList();
    }

    public static boolean has(Map<String, Object> policy, String capability) { return strings(policy.get("capabilities")).contains(capability); }

    public static boolean permits(Map<String, Object> policy, String kind, String name) {
        String normalized = name.replace('\\', '/');
        if (normalized.startsWith("/") || normalized.contains(":") || List.of(normalized.split("/", -1)).contains("..")) return false;
        for (List<String> layer : layers(policy, kind)) {
            if (layer.stream().noneMatch(pattern -> pattern.equals("**") || pattern.equals(".") || pattern.equals(normalized)
                    || (pattern.endsWith("/") && normalized.startsWith(pattern))
                    || FileSystems.getDefault().getPathMatcher("glob:" + pattern).matches(Path.of(normalized)))) return false;
        }
        return true;
    }

    public static boolean unrestricted(Map<String, Object> policy, String kind) {
        return layers(policy, kind).stream().allMatch(layer -> layer.contains("**") || layer.contains("."));
    }

    @SuppressWarnings("unchecked")
    public static List<List<String>> layers(Map<String, Object> policy, String kind) {
        return policy.get(kind + "ScopeLayers") instanceof List<?> list ? (List<List<String>>) list : List.of(List.of("**"));
    }

    public static Map<String, Object> narrow(Map<String, Object> parent, Map<String, Object> definition, Map<String, Object> request) {
        List<String> declared = capabilities(definition.get("capabilities"));
        List<String> asked = request.containsKey("capabilities") ? capabilities(request.get("capabilities")) : declared;
        List<String> actual = new ArrayList<>(declared.stream().filter(asked::contains).filter(capability -> has(parent, capability)).toList());
        List<List<String>> read = new ArrayList<>(layers(parent, "read")), write = new ArrayList<>(layers(parent, "write"));
        List<String> requestedScope = scope(request.get("scope")), definedScope = scope(definition.get("scope"));
        read.add(definedScope); read.add(requestedScope); write.add(definedScope); write.add(requestedScope);
        Map<String, Object> narrowed = obj("capabilities", actual, "readScopeLayers", read, "writeScopeLayers", write);
        if (!actual.contains("write") || !unrestricted(narrowed, "read") || !unrestricted(narrowed, "write")) actual.removeIf(capability -> Set.of("verify", "mcp").contains(capability));
        return narrowed;
    }

    public static void requireTool(Map<String, Object> policy, String name, Map<String, Object> args) {
        String capability = switch (name) {
            case "list_files", "read_file" -> "read";
            case "search_files" -> "search";
            case "write_file", "delete_file" -> "write";
            case "run_command" -> "verify";
            default -> "";
        };
        if (capability.isBlank() || !has(policy, capability)) throw new IllegalArgumentException("Agent 实际权限不允许工具：" + name);
        if (Set.of("read_file", "write_file", "delete_file").contains(name) && !permits(policy, name.equals("read_file") ? "read" : "write", str(args, "path"))) throw new IllegalArgumentException("文件不在 Agent 委派范围内");
        if (name.equals("run_command") && (!has(policy, "write") || !unrestricted(policy, "read") || !unrestricted(policy, "write"))) throw new IllegalArgumentException("命令不能强制细粒度文件范围，请交回根验证阶段");
    }
}
