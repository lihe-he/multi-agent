package dev.agentworkbench;

import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.mcp.McpTaskPolicy;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static dev.agentworkbench.Json.*;

/**
 * 项目、授权路由、记忆及完成约定的本地管理。
 */
@Service
public class WorkbenchCatalog {
    private final ExecutionStore store;
    private final WorkspaceRuntime runtime;
    private final PlanValidator validator;
    private final Map<String, String> credentials = new ConcurrentHashMap<>();

    public WorkbenchCatalog(ExecutionStore store, WorkspaceRuntime runtime, PlanValidator validator) {
        this.store = store; this.runtime = runtime; this.validator = validator;
    }

    @PostConstruct
    void defaults() {
        if (!store.list("profile").isEmpty()) { return; }
        for (Map<String, Object> profile : List.of(
            obj("id", "builtin-lead", "name", "研发负责人", "role", "lead", "instructions", "基于证据规划工作，保留未知、失败与交接信息。不得自行降低验收条件。", "capabilities", List.of("read", "search")),
            obj("id", "builtin-architect", "name", "架构分析", "role", "architect", "instructions", "审视模块边界、兼容性与取舍。区分观察、假设、偏好并说明反证条件。", "capabilities", List.of("read", "search")),
            obj("id", "builtin-developer", "name", "实现工程师", "role", "developer", "instructions", "在授权范围内完成需求，尊重项目规则，保持测试与验收完整性。", "capabilities", List.of("read", "search", "write")),
            obj("id", "builtin-tester", "name", "质量工程师", "role", "tester", "instructions", "关注边界、失败路径和回归。检查结果必须绑定版本，不能用偶然成功覆盖失败。", "capabilities", List.of("read", "search")),
            obj("id", "builtin-reviewer", "name", "独立审查者", "role", "reviewer", "instructions", "独立核对需求、差异和验证证据。不能只依赖实现者自报。报告阻断问题及证据。", "capabilities", List.of("read", "search")))) {
            profile.put("version", 1); profile.put("builtin", true); profile.put("createdAt", now());
            store.put("profile", profile);
        }
    }

    public Map<String, Object> importProject(Map<String, Object> body) {
        String path = required(body, "path", 4096);
        Map<String, Object> inspected = runtime.inspect(path);
        String actualPath = str(inspected, "path").isBlank() ? Path.of(path).toAbsolutePath().normalize().toString() : str(inspected, "path");
        for (Map<String, Object> existing : store.list("project")) {
            if (sameProjectPath(str(existing, "path"), actualPath, System.getProperty("os.name", "").startsWith("Windows"))) { return existing; }
        }
        Map<String, Object> project = new java.util.LinkedHashMap<>(inspected);
        project.put("id", id()); project.put("path", actualPath);
        project.put("name", str(body, "name").isBlank() ? Path.of(actualPath).getFileName().toString() : required(body, "name", 160));
        project.put("indexedAt", now()); project.put("createdAt", now());
        return store.put("project", project);
    }

    public Map<String, Object> scanProject(String id) {
        Map<String, Object> project = store.get("project", id);
        String oldHead = str(project, "head");
        project.putAll(runtime.inspect(str(project, "path"))); project.put("indexedAt", now());
        store.put("project", project);
        if (!oldHead.equals(str(project, "head")) || bool(project, "dirty", false)) {
            for (Map<String, Object> memory : store.list("memory")) {
                if (id.equals(str(memory, "projectId")) && "ACTIVE".equals(str(memory, "status"))) {
                    memory.put("status", "NEEDS_REVIEW"); memory.put("updatedAt", now()); store.put("memory", memory);
                }
            }
        }
        return project;
    }

    public Map<String, Object> search(String projectId, String query) {
        Map<String, Object> project = store.get("project", projectId);
        if (query.isBlank() || query.length() > 256) { throw new ApiException(400, "请输入 1 至 256 字符的关键词"); }
        return obj("results", runtime.search(str(project, "path"), query, 100), "coverage", "当前可读取文本文件的关键词检索；未配置语义检索，忽略目录与二进制不在覆盖范围", "version", runtime.fingerprint(str(project, "path")), "indexedAt", project.get("indexedAt"));
    }

    public synchronized Map<String, Object> saveRoute(Map<String, Object> body) {
        String routeId = str(body, "id").isBlank() ? id() : str(body, "id");
        Map<String, Object> prior = store.find("route", routeId);
        String engine = required(body, "engine", 32).toUpperCase();
        if (!Set.of("CODEX", "CLAUDE", "API").contains(engine)) { throw new ApiException(400, "未知运行器类型"); }
        String baseUrl = str(body, "baseUrl").trim();
        if ("API".equals(engine)) {
            try {
                URI uri = URI.create(baseUrl);
                if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw new IllegalArgumentException(); }
                if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost()))) { throw new IllegalArgumentException(); }
            } catch (IllegalArgumentException exception) { throw new ApiException(400, "API 地址应使用 HTTPS；本地测试端点可使用 HTTP，不能含凭据或查询参数"); }
        }
        Map<String, Object> route = obj("id", routeId, "name", required(body, "name", 160), "engine", engine,
            "model", str(body, "model").trim(), "provider", str(body, "provider").trim(), "baseUrl", baseUrl,
            "executable", str(body, "executable").trim(), "enabled", bool(body, "enabled", true),
            "version", prior == null ? 1 : num(prior, "version", 0) + 1, "updatedAt", now(),
            "budgetType", "API".equals(engine) ? "REQUEST_COUNT" : "SOFT_SESSION_COUNT",
            "capabilityNote", "API".equals(engine) ? "平台工具边界；费用与实际模型行为须通过端点验证" : "可信项目中的原生 CLI；请求数、费用与进程外副作用不可完全观测");
        if (body.containsKey("apiKey")) {
            String key = str(body, "apiKey");
            if (key.isBlank()) { credentials.keySet().removeIf(identity -> identity.startsWith(routeId + ":")); }
            else { credentials.put(routeId + ":" + route.get("version"), key); }
        } else if (prior != null && sameCredentialIdentity(prior, route)) {
            String oldCredential = credential(routeId, num(prior, "version", 1));
            if (!oldCredential.isBlank()) { credentials.put(routeId + ":" + route.get("version"), oldCredential); }
        } else if (prior != null && !sameCredentialIdentity(prior, route)) {
            route.put("credentialNotice", "运行器、供应商或 API 地址已变化；旧密钥不会自动发送到新端点，请重新提供凭据");
        }
        store.put("route", route);
        return publicRoute(route);
    }

    public List<Map<String, Object>> routes() { return store.list("route").stream().map(this::publicRoute).toList(); }

    public Map<String, Object> publicRoute(Map<String, Object> route) {
        Map<String, Object> safe = new java.util.LinkedHashMap<>(route);
        safe.put("hasApiKey", !credential(str(route, "id"), num(route, "version", 1)).isBlank()); safe.put("credentialStorage", "SESSION_ONLY"); return safe;
    }

    public String credential(String routeId) {
        Map<String, Object> route = store.find("route", routeId);
        return route == null ? "" : credential(routeId, num(route, "version", 1));
    }

    public String credential(String routeId, int version) { return credentials.getOrDefault(routeId + ":" + version, ""); }

    public String redact(String text) {
        String result = text;
        for (String key : credentials.values()) { if (!key.isBlank()) { result = result.replace(key, "[REDACTED]"); } }
        return result;
    }

    static boolean sameProjectPath(String left, String right, boolean caseInsensitive) { return caseInsensitive ? left.equalsIgnoreCase(right) : left.equals(right); }

    private boolean sameCredentialIdentity(Map<String, Object> before, Map<String, Object> after) {
        return List.of("engine", "provider", "baseUrl").stream().allMatch(field -> str(before, field).equals(str(after, field)));
    }

    public Map<String, Object> probe(String routeId) {
        Map<String, Object> route = store.get("route", routeId);
        Map<String, Object> result = runtime.probe(route);
        route.put("probe", result); route.put("probedAt", now()); store.put("route", route); return result;
    }

    public Map<String, Object> saveMemory(Map<String, Object> body) {
        String projectId = required(body, "projectId", 100);
        store.get("project", projectId);
        String kind = str(body, "kind");
        if (!Set.of("FACT", "DECISION", "EXPERIENCE", "RULE", "HYPOTHESIS").contains(kind)) { throw new ApiException(400, "未知知识类型"); }
        Map<String, Object> memory = obj("id", id(), "projectId", projectId, "kind", kind, "content", required(body, "content", 24000),
            "source", str(body, "source"), "version", 1, "status", "ACTIVE", "confirmedBy", "USER", "createdAt", now(), "updatedAt", now());
        return store.put("memory", memory);
    }

    public Map<String, Object> editMemory(String memoryId, Map<String, Object> body) {
        Map<String, Object> memory = store.get("memory", memoryId);
        store.put("memoryRevision", obj("id", id(), "memoryId", memoryId, "record", new java.util.LinkedHashMap<>(memory), "createdAt", now()));
        if (body.containsKey("content")) { memory.put("content", required(body, "content", 24000)); }
        if (body.containsKey("status")) {
            String status = str(body, "status");
            if (!Set.of("ACTIVE", "DISABLED", "NEEDS_REVIEW").contains(status)) { throw new ApiException(400, "未知知识状态"); }
            memory.put("status", status);
        }
        memory.put("version", num(memory, "version", 1) + 1); memory.put("updatedAt", now());
        return store.put("memory", memory);
    }

    public Map<String, Object> saveProfile(Map<String, Object> body) {
        String profileId = str(body, "id").isBlank() ? id() : str(body, "id");
        Map<String, Object> old = store.find("profile", profileId);
        List<String> capabilities = strings(body.get("capabilities"));
        if (!Set.of("read", "search", "write", "verify").containsAll(capabilities)) { throw new ApiException(400, "职责模板不能申请未支持的能力或扩大授权"); }
        if (old != null) { store.put("profileRevision", obj("id", id(), "profileId", profileId, "record", old, "createdAt", now())); }
        Map<String, Object> profile = obj("id", profileId, "name", required(body, "name", 160), "role", required(body, "role", 64),
            "instructions", required(body, "instructions", 24000), "capabilities", capabilities,
            "version", old == null ? 1 : num(old, "version", 1) + 1, "updatedAt", now());
        return store.put("profile", profile);
    }

    public synchronized Map<String, Object> createTask(Map<String, Object> body) {
        Map<String, Object> project = store.get("project", required(body, "projectId", 100));
        String goal = required(body, "goal", 40000), title = required(body, "title", 200);
        for (Map<String, Object> task : store.list("task")) {
            if (str(task, "projectId").equals(str(project, "id")) && goal.equals(str(task, "goal")) && !Set.of("CANCELLED", "FAILED").contains(str(task, "state"))) {
                throw new ApiException(409, "已有相同目标任务，可直接复用：" + str(task, "title") + " (" + str(task, "id") + ")");
            }
        }
        Map<String, Object> route = store.get("route", required(body, "routeId", 100));
        Map<String, Object> locks = new java.util.LinkedHashMap<>(map(body, "locks"));
        if (!BudgetPolicy.matchesLocks(route, locks)) { throw new ApiException(409, "所选运行器不满足硬锁定，不能自动解除"); }
        Map<String, Object> requested = map(body, "contract");
        List<String> acceptance = strings(requested.get("acceptance")).stream().filter(item -> !item.isBlank()).toList();
        List<String> commands = strings(requested.get("commands")).stream().filter(item -> !item.isBlank()).toList();
        List<String> scope = strings(requested.get("scope"));
        if (acceptance.isEmpty() || commands.isEmpty()) { throw new ApiException(400, "请至少填写一项验收条件和一条必要验证命令"); }
        if (acceptance.size() > 30 || commands.size() > 12 || scope.size() > 100) { throw new ApiException(400, "完成约定过大，请拆分任务"); }
        if (scope.isEmpty()) { scope = List.of("**"); }
        Map<String, Object> contract = obj("id", id(), "version", 1, "scope", scope, "acceptance", acceptance, "commands", commands,
            "permissions", List.of("read", "search", "write", "verify"), "createdAt", now(), "confirmed", false);
        int max = num(map(body, "budget"), "maxCalls", 24), reserve = num(map(body, "budget"), "reserveCalls", 6);
        if (max < 2 || max > 1000 || reserve < 1 || reserve >= max) { throw new ApiException(400, "调用上限应为 2 至 1000，验证预留必须大于 0 且小于总额度"); }
        String mode = str(body, "mode").toUpperCase(), reason = "由用户指定协作模式";
        if (mode.isBlank() || "AUTO".equals(mode)) {
            if (goal.matches("(?s).*(架构|方案|比较|取舍|architecture|compare).*")) { mode = "ROUNDTABLE"; reason = "任务含方案取舍：先独立分析、交叉质疑与汇总，再实现验证"; }
            else if (goal.matches("(?s).*(流水线|迁移|按步骤|pipeline|migration).*")) { mode = "PIPELINE"; reason = "目标阶段较明确：按方案、实现、验证、审查门禁顺序交接"; }
            else { mode = "LEAD"; reason = "由负责人协调调查与实现，再执行必要验证和独立审查"; }
        }
        if (!Set.of("LEAD", "ROUNDTABLE", "PIPELINE").contains(mode)) { throw new ApiException(400, "未知协作模式"); }
        List<Map<String, Object>> plan = plan(mode);
        Map<String, Object> result = validator.validate(obj("stages", plan, "permissions", contract.get("permissions"), "maxRoles", 6, "inputs", List.of("contract")));
        if (!bool(result, "valid", false)) { throw new ApiException(400, "计划未通过检查：" + result.get("errors")); }
        List<Map<String, Object>> context = new ArrayList<>();
        for (Map<String, Object> memory : store.list("memory")) {
            if (str(project, "id").equals(str(memory, "projectId")) && "ACTIVE".equals(str(memory, "status"))) {
                context.add(obj("id", memory.get("id"), "kind", memory.get("kind"), "content", memory.get("content"), "source", memory.get("source"), "version", memory.get("version")));
            }
        }
        try {
            Path rules = Path.of(str(project, "path")).resolve("AGENTS.md");
            if (Files.isRegularFile(rules) && Files.size(rules) <= 128000) { context.add(obj("id", "project-rules", "kind", "RULE", "content", Files.readString(rules), "source", "AGENTS.md", "version", runtime.fingerprint(str(project, "path")))); }
        } catch (Exception exception) { throw new ApiException(409, "项目规则无法读取，请检查权限"); }
        Map<String, Object> task = obj("id", id(), "schemaVersion", 1, "projectId", project.get("id"), "title", title, "goal", goal,
            "mode", mode, "modeReason", reason, "state", "DRAFT", "outcome", "UNDETERMINED", "createdAt", now(), "updatedAt", now(),
            "contract", contract, "budget", obj("maxCalls", max, "reserveCalls", reserve, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0, "developmentAvailable", max - reserve, "type", route.get("budgetType")),
            "locks", locks, "routeId", route.get("id"), "routeSnapshot", route, "profileSnapshot", store.list("profile"), "policyVersion", "1.1/0.2.0-alpha.5",
            "includeChanges", bool(body, "includeChanges", false), "plan", plan, "context", context, "evidence", new ArrayList<>(), "issues", new ArrayList<>(),
            "review", obj("status", "NOT_RUN"), "planValidation", result, "workspace", "", "baseline", "", "version", "", "repairRounds", 0);
        task.put("mcpAuthorization", McpTaskPolicy.snapshot(store, str(project, "id"), route, body.get("mcpBindings"), 1));
        task.put("mcpBudget", McpTaskPolicy.budget(map(body, "mcpBudget"), Map.of()));
        String sessionId = str(body, "sessionId");
        if (!sessionId.isBlank()) { store.session(str(project, "id"), sessionId); task.put("sessionId", sessionId); }
        return store.createTask(task);
    }

    private List<Map<String, Object>> plan(String mode) {
        List<Map<String, Object>> stages = new ArrayList<>();
        stages.add(stage("baseline", "隔离基线检查", "tester", List.of(), "BASELINE", true));
        List<String> beforeImplementation;
        if ("ROUNDTABLE".equals(mode)) {
            List<String> proposals = List.of("proposal-architect", "proposal-developer", "proposal-tester");
            for (String role : List.of("architect", "developer", "tester")) { stages.add(stage("proposal-" + role, "独立提案 · " + role, role, List.of("baseline"), "PROPOSAL", true)); }
            List<String> critiques = new ArrayList<>();
            for (String role : List.of("architect", "developer", "tester")) { String id = "critique-" + role; critiques.add(id); stages.add(stage(id, "交叉质疑 · " + role, role, proposals, "CRITIQUE", true)); }
            stages.add(stage("synthesis", "汇总取舍与分歧", "lead", critiques, "SYNTHESIS", true)); beforeImplementation = List.of("synthesis");
        } else {
            String kind = "PIPELINE".equals(mode) ? "DESIGN" : "INVESTIGATE";
            stages.add(stage("analysis", "PIPELINE".equals(mode) ? "方案与交接" : "负责人调查", "lead", List.of("baseline"), kind, true));
            beforeImplementation = List.of("analysis");
        }
        stages.add(stage("implement", "完成需求实现", "developer", beforeImplementation, "IMPLEMENT", false));
        stages.add(stage("verify", "必要验证与失败对照", "tester", List.of("implement"), "VERIFY", true));
        stages.add(stage("review", "独立会话审查", "reviewer", List.of("verify"), "REVIEW", true));
        stages.add(stage("delivery", "整理成果并等待验收", "lead", List.of("review"), "DELIVERY", true));
        return stages;
    }

    private Map<String, Object> stage(String id, String title, String role, List<String> dependencies, String kind, boolean readOnly) {
        return obj("id", id, "title", title, "role", role, "dependencies", dependencies, "kind", kind, "readOnly", readOnly,
            "inputs", dependencies.isEmpty() ? List.of("contract") : dependencies.stream().map(dep -> dep + ":result").toList(),
            "outputs", List.of(id + ":result"), "acceptance", List.of("有可追溯的阶段成果，未确认项明确保留"), "termination", "完成、根取消、预算或 15 分钟工具超时",
            "resources", readOnly ? List.of() : List.of("write:delivery-workspace"), "permissions", readOnly ? List.of("read", "search") : List.of("read", "search", "write"), "state", "PENDING");
    }
}
