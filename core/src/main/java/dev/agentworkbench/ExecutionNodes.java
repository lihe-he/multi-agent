package dev.agentworkbench;

import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 项目身份与节点上的绝对路径分开；任务只使用已固定版本的仓库绑定。
 */
final class ExecutionNodes {
    private final ExecutionStore store;
    private final WorkspaceRuntime runtime;

    ExecutionNodes(ExecutionStore store, WorkspaceRuntime runtime) { this.store = store; this.runtime = runtime; }

    Map<String, Object> localNode() {
        synchronized (store) {
            Map<String, Object> settings = store.find("executionSettings", "local");
            if (settings != null) return store.get("executionNode", str(settings, "executionNodeId"));
            Map<String, Object> node = obj("id", id(), "name", "本机执行节点", "version", 1, "protocolVersion", 1,
                "type", "LOCAL", "deployment", "CORE_PROCESS", "platform", System.getProperty("os.name"), "architecture", System.getProperty("os.arch"),
                "enabled", true, "epoch", 0, "createdAt", now(), "updatedAt", now(),
                "supportedOperations", List.of("GIT_SNAPSHOT", "MODEL_HTTP", "NATIVE_CLI", "VERIFICATION", "SUBAGENT_ADOPTION"));
            store.commitRecords(Map.of("executionNode", List.of(node), "executionSettings", List.of(obj("id", "local", "executionNodeId", node.get("id")))), null, null, null);
            return node;
        }
    }

    Map<String, Object> bindImportedProject(Map<String, Object> project) {
        synchronized (store) {
            if (!str(project, "repositoryBindingId").isBlank()) return project;
            Map<String, Object> node = localNode();
            Map<String, Object> binding = obj("id", id(), "projectId", project.get("id"), "executionNodeId", node.get("id"), "version", 1,
                "name", project.get("name"), "path", canonical(str(project, "path")), "enabled", true, "createdAt", now(), "updatedAt", now());
            project.put("repositoryBindingId", binding.get("id")); project.put("executionNodeId", node.get("id"));
            store.commitRecords(Map.of("repositoryBinding", List.of(binding), "project", List.of(project)), null, null, null);
            return project;
        }
    }

    Map<String, Object> saveNode(Map<String, Object> input) {
        fields(input, Set.of("id", "expectedVersion", "name", "enabled"));
        synchronized (store) {
            Map<String, Object> node = store.get("executionNode", required(input, "id", 100));
            if (!str(node, "id").equals(str(localNode(), "id"))) throw new ApiException(400, "本版本只配置当前本机节点");
            version(input, num(node, "version", 1));
            node.putAll(obj("name", required(input, "name", 160), "enabled", bool(input, "enabled", true), "version", num(node, "version", 1) + 1, "updatedAt", now()));
            return store.put("executionNode", node);
        }
    }

    Map<String, Object> saveBinding(Map<String, Object> input) {
        fields(input, Set.of("id", "expectedVersion", "projectId", "executionNodeId", "name", "path", "enabled"));
        String projectId = required(input, "projectId", 100), nodeId = required(input, "executionNodeId", 100);
        if (!nodeId.equals(str(localNode(), "id"))) throw new ApiException(400, "仓库路径只能由当前本机节点核验");
        store.get("project", projectId);
        String path = required(input, "path", 4096);
        if (!Path.of(path).isAbsolute()) throw new ApiException(400, "仓库绑定需要该节点上的绝对路径");
        Map<String, Object> inspected = runtime.inspect(path);
        String actual = canonical(str(inspected, "path"));
        synchronized (store) {
            String bindingId = str(input, "id").isBlank() ? id() : str(input, "id");
            Map<String, Object> previous = store.find("repositoryBinding", bindingId);
            if (previous != null) {
                version(input, num(previous, "version", 1));
                if (!projectId.equals(str(previous, "projectId")) || !nodeId.equals(str(previous, "executionNodeId"))) throw new ApiException(409, "已有绑定不能变更项目或节点身份，请新建绑定");
            } else if (input.containsKey("expectedVersion")) throw new ApiException(409, "仓库绑定不存在，请刷新后重试");
            Map<String, Object> duplicate = store.list("repositoryBinding").stream().filter(binding -> !bindingId.equals(str(binding, "id"))
                && nodeId.equals(str(binding, "executionNodeId")) && projectId.equals(str(binding, "projectId")) && samePath(actual, str(binding, "path"))).findFirst().orElse(null);
            if (duplicate != null) throw new ApiException(409, "该项目已有相同节点与目录的仓库绑定");
            Map<String, Object> binding = obj("id", bindingId, "projectId", projectId, "executionNodeId", nodeId, "name", required(input, "name", 160),
                "path", actual, "version", previous == null ? 1 : num(previous, "version", 1) + 1, "enabled", bool(input, "enabled", true),
                "createdAt", previous == null ? now() : previous.get("createdAt"), "updatedAt", now(), "head", inspected.get("head"), "branch", inspected.get("branch"));
            return store.put("repositoryBinding", binding);
        }
    }

    Map<String, Object> snapshot(String projectId, Map<String, Object> input, int authorizationVersion) {
        if (input.containsKey("repositoryBindingId") && (!(input.get("repositoryBindingId") instanceof String value) || value.isBlank() || value.length() > 100)) throw new ApiException(400, "repositoryBindingId 必须为绑定身份字符串");
        synchronized (store) {
            Map<String, Object> project = bindImportedProject(store.get("project", projectId));
            String bindingId = str(input, "repositoryBindingId").isBlank() ? str(project, "repositoryBindingId") : str(input, "repositoryBindingId");
            Map<String, Object> binding = store.get("repositoryBinding", bindingId), node = store.get("executionNode", str(binding, "executionNodeId"));
            if (!projectId.equals(str(binding, "projectId"))) throw new ApiException(403, "仓库绑定不属于当前项目");
            if (input.containsKey("bindingVersion") && integer(input, "bindingVersion") != num(binding, "version", 1)) throw new ApiException(409, "仓库绑定版本已变化，请刷新后确认");
            if (input.containsKey("nodeVersion") && integer(input, "nodeVersion") != num(node, "version", 1)) throw new ApiException(409, "执行节点版本已变化，请刷新后确认");
            if (!bool(binding, "enabled", false) || !bool(node, "enabled", false) || !str(node, "id").equals(str(localNode(), "id"))) throw new ApiException(409, "仓库绑定或本机执行节点未启用");
            return obj("version", authorizationVersion, "protocolVersion", 1, "executionNodeId", node.get("id"), "nodeVersion", node.get("version"), "nodeName", node.get("name"),
                "repositoryBindingId", bindingId, "bindingVersion", binding.get("version"), "bindingName", binding.get("name"), "sourcePath", binding.get("path"), "deployment", "CORE_PROCESS", "createdAt", now());
        }
    }

    void requireCurrent(Map<String, Object> task) {
        Map<String, Object> execution = map(task, "execution");
        if (execution.isEmpty()) return;
        Map<String, Object> node = store.get("executionNode", str(execution, "executionNodeId")), binding = store.get("repositoryBinding", str(execution, "repositoryBindingId"));
        if (!str(task, "projectId").equals(str(binding, "projectId")) || !str(node, "id").equals(str(binding, "executionNodeId")) || !str(node, "id").equals(str(localNode(), "id"))) throw new ApiException(403, "任务执行归属与节点或项目不一致");
        if (!bool(node, "enabled", false) || !bool(binding, "enabled", false) || num(node, "version", 0) != num(execution, "nodeVersion", -1)
                || num(binding, "version", 0) != num(execution, "bindingVersion", -1) || !samePath(str(binding, "path"), str(execution, "sourcePath"))) throw new ApiException(409, "执行节点或仓库绑定已变更，请核对后更新任务执行授权");
        if (!Files.isDirectory(Path.of(str(execution, "sourcePath")))) throw new ApiException(409, "已授权仓库目录不可用");
        if (!samePath(str(execution, "sourcePath"), canonical(str(execution, "sourcePath")))) throw new ApiException(409, "仓库目录解析目标已变化，请核对后重新绑定");
    }

    String sourcePath(Map<String, Object> task) {
        requireCurrent(task);
        return map(task, "execution").isEmpty() ? str(store.get("project", str(task, "projectId")), "path") : str(map(task, "execution"), "sourcePath");
    }

    static String canonical(String path) {
        try { return Path.of(path).toRealPath().toString(); }
        catch (Exception exception) { throw new ApiException(409, "节点上的仓库目录无法解析，请检查目录与权限"); }
    }

    static boolean samePath(String left, String right) { return WorkbenchCatalog.sameProjectPath(left, right, System.getProperty("os.name", "").startsWith("Windows")); }

    private static void fields(Map<String, Object> input, Set<String> allowed) {
        if (!allowed.containsAll(input.keySet())) throw new ApiException(400, "节点或绑定包含不支持的字段");
        if (input.containsKey("enabled") && !(input.get("enabled") instanceof Boolean)) throw new ApiException(400, "enabled 必须为布尔值");
        for (String field : allowed) if (!Set.of("enabled", "expectedVersion").contains(field) && input.containsKey(field) && !(input.get(field) instanceof String)) throw new ApiException(400, field + " 必须为字符串");
    }

    private static void version(Map<String, Object> input, int expected) { if (integer(input, "expectedVersion") != expected) throw new ApiException(409, "配置版本已变化，请刷新后编辑"); }
    private static int integer(Map<String, Object> input, String field) {
        if (!(input.get(field) instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1) throw new ApiException(400, field + " 必须为正整数");
        return number.intValue();
    }
}
