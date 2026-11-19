package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 流程版本固定 DSL 与职责定义；执行授权仍由任务创建和运行门禁分别核验。
 */
final class WorkflowCatalog {
    private static final Set<String> EDIT_FIELDS = Set.of("id", "expectedVersion", "name", "projectId", "enabled", "dsl");
    private final ExecutionStore store;
    private final ObjectMapper mapper = new ObjectMapper();

    WorkflowCatalog(ExecutionStore store) { this.store = store; }

    void defaults() {
        Map<String, Map<String, Object>> actors = new LinkedHashMap<>();
        for (String id : List.of("agent-research", "agent-developer", "agent-reviewer")) {
            Map<String, Object> actor = store.find("agentDefinition", id);
            if (actor == null || !bool(actor, "enabled", false) || !str(actor, "projectId").isBlank()
                    || !strings(actor.get("capabilities")).contains("read")) return;
            actors.put(id, actor);
        }
        if (!strings(actors.get("agent-developer").get("capabilities")).contains("write")) return;
        for (String mode : WorkflowTemplates.MODES) if (store.find("workflowDefinition", WorkflowTemplates.id(mode)) == null) {
            save(obj("id", WorkflowTemplates.id(mode), "name", WorkflowTemplates.name(mode), "projectId", "",
                "dsl", WorkflowTemplates.dsl(mode, actors)), "BUILTIN", "");
        }
    }

    Map<String, Object> validate(Map<String, Object> input) {
        try {
            Map<String, Object> normalized = WorkflowDsl.normalize(input);
            return obj("valid", true, "errors", List.of(), "dsl", normalized,
                "order", WorkflowDsl.order(objects(normalized.get("nodes"))), "dslDigest", WorkflowDsl.digest(normalized, mapper));
        } catch (ApiException exception) { return obj("valid", false, "errors", List.of(exception.getMessage())); }
    }

    Map<String, Object> validateDefinition(Map<String, Object> input) {
        if (!Set.of("dsl", "projectId").containsAll(input.keySet()) || !(input.get("dsl") instanceof Map<?, ?>)
                || input.containsKey("projectId") && !(input.get("projectId") instanceof String)) throw new ApiException(400, "流程校验需要 DSL 对象和项目归属");
        String projectId = str(input, "projectId"); if (!projectId.isBlank()) store.get("project", projectId);
        Map<String, Object> checked = validate(map(input, "dsl"));
        if (bool(checked, "valid", false)) try { checked.put("agentDefinitions", actors(map(checked, "dsl"), projectId)); }
        catch (ApiException exception) { checked.put("valid", false); checked.put("errors", List.of(exception.getMessage())); }
        return checked;
    }

    /**
     * 编辑发布新版本，版本冲突和任一存储失败都不留下半个当前版本。
     */
    Map<String, Object> save(Map<String, Object> input) {
        return save(input, "USER", "");
    }

    Map<String, Object> saveGenerated(Map<String, Object> input, Map<String, Object> proposal, String fingerprint, boolean edited) {
        Map<String, Object> publication = new LinkedHashMap<>(proposal);
        publication.putAll(obj("state", "PUBLISHED", "publicationFingerprint", fingerprint, "publishedAt", now()));
        return save(input, edited ? "GENERATED_EDIT" : "GENERATED", str(proposal, "id"), publication);
    }

    private Map<String, Object> save(Map<String, Object> input, String source, String sourceVersionId) {
        return save(input, source, sourceVersionId, Map.of());
    }

    private Map<String, Object> save(Map<String, Object> input, String source, String sourceVersionId, Map<String, Object> publication) {
        if (input == null || !EDIT_FIELDS.containsAll(input.keySet())) throw new ApiException(400, "Workflow 编辑包含未支持的字段");
        if (!(input.get("dsl") instanceof Map<?, ?> record) || record.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new ApiException(400, "Workflow DSL 必须为对象");
        if (input.containsKey("enabled") && !(input.get("enabled") instanceof Boolean)) throw new ApiException(400, "enabled 必须为布尔值");
        String definitionId = str(input, "id").isBlank() ? id() : identifier(input, "id");
        Map<String, Object> previous = store.find("workflowDefinition", definitionId);
        int expected = expected(input, previous == null ? 0 : -1);
        if ((previous == null ? 0 : num(previous, "version", -1)) != expected) throw new ApiException(409, "Workflow 已变化，请刷新并核对版本");
        String projectId = input.containsKey("projectId") ? str(input, "projectId") : previous == null ? "" : str(previous, "projectId");
        if (input.containsKey("projectId") && !(input.get("projectId") instanceof String)) throw new ApiException(400, "projectId 必须为项目身份文字");
        if (!projectId.isBlank()) { identifier(input, "projectId"); store.get("project", projectId); }
        if (previous != null && !projectId.equals(str(previous, "projectId"))) throw new ApiException(409, "Workflow 的项目归属不能改写，请复制为新流程");
        String name = requiredText(input, "name", 160);
        Map<String, Object> normalized = WorkflowDsl.normalize(map(record));
        List<Map<String, Object>> actors = actors(normalized, projectId);
        int next = expected + 1; String versionId = definitionId + ":v" + next;
        Map<String, Object> definition = obj("id", definitionId, "version", next, "currentVersionId", versionId,
            "name", name, "projectId", projectId, "enabled", bool(input, "enabled", true), "updatedAt", now(),
            "createdAt", previous == null ? now() : previous.get("createdAt"), "builtin", "BUILTIN".equals(source) || previous != null && bool(previous, "builtin", false));
        Map<String, Object> version = obj("id", versionId, "schemaVersion", 1, "definitionId", definitionId, "version", next,
            "projectId", projectId, "name", name, "dsl", normalized, "agentDefinitions", actors, "createdAt", now(), "source", source);
        if (!sourceVersionId.isBlank()) version.put("sourceVersionId", sourceVersionId);
        version.put("digest", digest(normalized, actors));
        if (!publication.isEmpty()) {
            if ("GENERATED".equals(source) && !str(publication, "digest").equals(str(version, "digest"))) throw new ApiException(409, "候选固定的职责内容已变化，请复核后编辑");
            publication.putAll(obj("publishedDefinitionId", definitionId, "publishedVersion", next, "publishedVersionId", versionId));
        }
        store.saveWorkflowVersion(definition, version, expected, publication);
        return obj("definition", definition, "version", version);
    }

    Map<String, Object> rollback(String definitionId, Map<String, Object> input) {
        if (!input.keySet().equals(Set.of("expectedVersion", "version"))) throw new ApiException(400, "回退需要当前版本与原历史版本");
        int expected = expected(input, -1);
        if (!(input.get("version") instanceof Number number) || number.doubleValue() != number.intValue()) throw new ApiException(400, "历史版本必须为整数");
        Map<String, Object> old = version(definitionId, number.intValue()), current = store.get("workflowDefinition", definitionId);
        return save(obj("id", definitionId, "expectedVersion", expected, "name", old.get("name"), "projectId", current.get("projectId"),
            "enabled", current.get("enabled"), "dsl", old.get("dsl")), "ROLLBACK", str(old, "id"));
    }

    /**
     * 任务固定流程、职责和发送路由，节点能力严格取阶段、根授权与职责的交集。
     */
    Map<String, Object> snapshot(Map<String, Object> task, Map<String, Object> selection) {
        if (!Set.of("definitionId", "version", "digest", "allowedRouteIds").containsAll(selection.keySet())) throw new ApiException(400, "流程选择包含未支持的字段");
        String definitionId = identifier(selection, "definitionId");
        if (!(selection.get("version") instanceof Number number) || number.doubleValue() != number.intValue()) throw new ApiException(400, "请选择明确的流程版本");
        Map<String, Object> definition = store.get("workflowDefinition", definitionId), version = version(definitionId, number.intValue());
        if (!bool(definition, "enabled", false)) throw new ApiException(409, "流程已停用，请刷新后选择");
        if (!str(version, "projectId").isBlank() && !str(task, "projectId").equals(str(version, "projectId"))) throw new ApiException(403, "流程不属于当前项目");
        if (selection.containsKey("digest") && !str(selection, "digest").equals(str(version, "digest"))) throw new ApiException(409, "所选流程内容已变化，请刷新后核对");
        List<String> allowedRoutes = List.of();
        if (selection.containsKey("allowedRouteIds")) {
            if (!(selection.get("allowedRouteIds") instanceof List<?> routes) || routes.size() > 64
                    || routes.stream().anyMatch(route -> !(route instanceof String id) || id.isBlank() || id.length() > 100)) throw new ApiException(400, "流程数据发送路由需要明确身份清单");
            allowedRoutes = strings(routes);
        }
        Map<String, Object> rootRoute = map(task, "routeSnapshot"), contract = map(task, "contract");
        Map<String, Object> root = obj("capabilities", AgentPermissions.capabilities(contract.get("permissions")),
            "readScopeLayers", List.of(List.of("**")), "writeScopeLayers", List.of(AgentPermissions.scope(contract.get("scope"))));
        List<Map<String, Object>> bindings = new ArrayList<>();
        for (Map<String, Object> node : objects(map(version, "dsl").get("nodes"))) {
            Map<String, Object> actor = actor(version, node), live = store.find("agentDefinition", str(actor, "id"));
            if (live == null || !bool(live, "enabled", false) || !str(live, "projectId").isBlank() && !str(task, "projectId").equals(str(live, "projectId"))) throw new ApiException(409, "节点职责已停用或项目归属变化");
            Map<String, Object> route = str(actor, "routeId").isBlank() ? rootRoute : store.get("route", str(actor, "routeId"));
            if (!"API".equals(str(route, "engine")) || !bool(route, "enabled", false) || !BudgetPolicy.matchesLocks(route, map(task, "locks"))) throw new ApiException(409, "节点路由不支持逐请求执行或不符合根锁定");
            if (!str(rootRoute, "id").equals(str(route, "id")) && !allowedRoutes.contains(str(route, "id"))) throw new ApiException(403, "请明确确认流程向其他路由发送资料：" + str(route, "name"));
            Map<String, Object> permissions = AgentPermissions.narrow(root, actor, obj("capabilities", node.get("permissions"), "scope", node.get("scope")));
            List<String> actual = new ArrayList<>(strings(permissions.get("capabilities")));
            if (!bool(map(task, "collaboration"), "enabled", false)) actual.remove("delegate");
            if (objects(map(task, "mcpAuthorization").get("bindings")).isEmpty()) actual.remove("mcp");
            permissions.put("capabilities", actual);
            if (!actual.contains("read")) throw new ApiException(403, "节点没有根授权允许的读取能力");
            bindings.add(obj("nodeId", node.get("id"), "actor", actor, "routeSnapshot", new LinkedHashMap<>(route), "permissions", permissions));
        }
        Map<String, Object> snapshot = obj("protocolVersion", 1, "definitionId", definitionId, "version", version.get("version"),
            "versionId", version.get("id"), "name", version.get("name"), "digest", version.get("digest"), "dsl", version.get("dsl"),
            "agentDefinitions", version.get("agentDefinitions"), "bindings", bindings, "createdAt", now());
        snapshot.put("snapshotDigest", snapshotDigest(snapshot));
        return snapshot;
    }

    String snapshotDigest(Map<String, Object> snapshot) {
        return WorkflowDsl.digest(obj("definitionId", snapshot.get("definitionId"), "version", snapshot.get("version"), "digest", snapshot.get("digest"),
            "dsl", snapshot.get("dsl"), "agentDefinitions", snapshot.get("agentDefinitions"), "bindings", snapshot.get("bindings")), mapper);
    }

    Map<String, Object> binding(Map<String, Object> snapshot, String nodeId) {
        if (!str(snapshot, "snapshotDigest").equals(snapshotDigest(snapshot))) throw new ApiException(409, "任务固定流程内容已变化，停止执行并核对");
        return objects(snapshot.get("bindings")).stream().filter(binding -> nodeId.equals(str(binding, "nodeId"))).findFirst()
            .orElseThrow(() -> new ApiException(409, "流程缺少节点职责与权限快照"));
    }

    List<Map<String, Object>> definitions(String projectId) {
        if (!projectId.isBlank()) store.get("project", projectId);
        return store.list("workflowDefinition").stream().filter(definition -> projectId.isBlank()
            || str(definition, "projectId").isBlank() || projectId.equals(str(definition, "projectId"))).toList();
    }

    List<Map<String, Object>> versions(String definitionId) {
        store.get("workflowDefinition", definitionId);
        return store.list("workflowVersion").stream().filter(version -> definitionId.equals(str(version, "definitionId")))
            .sorted((left, right) -> Integer.compare(num(right, "version", 0), num(left, "version", 0))).toList();
    }

    Map<String, Object> versionPage(String definitionId, int before, int limit) {
        if (before < 0 || limit < 1 || limit > 30) throw new ApiException(400, "流程历史游标或页大小无效");
        List<Map<String, Object>> selected = versions(definitionId).stream().filter(version -> before == 0 || num(version, "version", 0) < before).toList();
        List<Map<String, Object>> page = selected.stream().limit(limit).map(version -> obj("id", version.get("id"), "definitionId", definitionId, "version", version.get("version"),
            "name", version.get("name"), "digest", version.get("digest"), "source", version.get("source"), "sourceVersionId", version.getOrDefault("sourceVersionId", ""),
            "createdAt", version.get("createdAt"), "nodeCount", objects(map(version, "dsl").get("nodes")).size())).toList();
        return obj("versions", page, "hasMore", selected.size() > page.size(), "next", page.isEmpty() ? before : num(page.getLast(), "version", 0));
    }

    Map<String, Object> exported(String definitionId, int number) {
        Map<String, Object> version = version(definitionId, number);
        return obj("formatVersion", 1, "name", version.get("name"), "dsl", version.get("dsl"), "agentDefinitions", version.get("agentDefinitions"), "digest", version.get("digest"));
    }

    /**
     * 导入只引用本机已经核对的职责版本，不能覆盖职责或将工具声明当作授权。
     */
    Map<String, Object> imported(Map<String, Object> input) {
        if (!Set.of("bundle", "projectId").equals(input.keySet()) || !(input.get("bundle") instanceof Map<?, ?>)) throw new ApiException(400, "导入需要流程文件和目标项目");
        Map<String, Object> bundle = map(input, "bundle");
        if (!Set.of("formatVersion", "name", "dsl", "agentDefinitions", "digest").equals(bundle.keySet()) || num(bundle, "formatVersion", -1) != 1
                || !(bundle.get("dsl") instanceof Map<?, ?>) || !(bundle.get("agentDefinitions") instanceof List<?>)) throw new ApiException(400, "不支持的流程文件格式");
        Map<String, Object> dsl = WorkflowDsl.normalize(map(bundle, "dsl")); List<Map<String, Object>> definitions = objects(bundle.get("agentDefinitions"));
        if (!str(bundle, "digest").equals(digest(dsl, definitions))) throw new ApiException(409, "导入文件的 DSL 或职责内容与摘要不符");
        String projectId = str(input, "projectId"); List<Map<String, Object>> local = actors(dsl, projectId);
        if (!digest(dsl, local).equals(str(bundle, "digest"))) throw new ApiException(409, "本机职责版本与导入文件不同，请先导入并核对所需职责");
        return save(obj("name", requiredText(bundle, "name", 160), "projectId", projectId, "dsl", dsl), "IMPORT", "");
    }

    /**
     * 读取历史版本时重查结构、归属和完整摘要，不能用当前定义静默替代。
     */
    Map<String, Object> version(String definitionId, int number) {
        if (number < 1) throw new ApiException(400, "Workflow 版本必须为正整数");
        Map<String, Object> definition = store.get("workflowDefinition", definitionId);
        Map<String, Object> version = store.get("workflowVersion", definitionId + ":v" + number);
        if (!definitionId.equals(str(definition, "id")) || !str(version, "id").equals(definitionId + ":v" + number)
                || !definitionId.equals(str(version, "definitionId")) || num(version, "version", -1) != number
                || !str(definition, "projectId").equals(str(version, "projectId"))) throw new ApiException(409, "Workflow 历史版本归属不完整");
        Map<String, Object> normalized;
        try { normalized = WorkflowDsl.normalize(map(version, "dsl")); }
        catch (ApiException exception) { throw new ApiException(409, "Workflow 历史结构不完整，停止使用并保留原记录"); }
        if (!(version.get("agentDefinitions") instanceof List<?> records) || records.isEmpty() || records.size() > 64
                || records.stream().anyMatch(record -> !(record instanceof Map<?, ?>))) throw new ApiException(409, "Workflow 固定职责清单不完整");
        List<Map<String, Object>> actors = objects(records);
        for (Map<String, Object> actor : actors) validActor(actor);
        if (!str(version, "digest").equals(digest(normalized, actors))) throw new ApiException(409, "Workflow 版本内容已变化，停止使用并保留原记录");
        for (Map<String, Object> node : objects(normalized.get("nodes"))) actor(version, node);
        return version;
    }

    Map<String, Object> actor(Map<String, Object> version, Map<String, Object> node) {
        return objects(version.get("agentDefinitions")).stream().filter(actor -> str(node, "definitionId").equals(str(actor, "id"))
            && num(node, "definitionVersion", -1) == num(actor, "version", -2)).findFirst()
            .orElseThrow(() -> new ApiException(409, "Workflow 缺少固定的职责定义版本"));
    }

    String digest(Map<String, Object> dsl, List<Map<String, Object>> actors) {
        return WorkflowDsl.digest(obj("dsl", dsl, "agentDefinitions", actors), mapper);
    }

    private List<Map<String, Object>> actors(Map<String, Object> normalized, String projectId) {
        Map<String, Map<String, Object>> pinned = new LinkedHashMap<>();
        for (Map<String, Object> node : objects(normalized.get("nodes"))) {
            String definitionId = str(node, "definitionId"); int number = num(node, "definitionVersion", -1);
            Map<String, Object> live = store.get("agentDefinition", definitionId);
            validActor(live);
            if (!definitionId.equals(str(live, "id"))) throw new ApiException(409, "Workflow 职责定义的存储归属不完整");
            if (!bool(live, "enabled", false)) throw new ApiException(409, "Workflow 的职责定义已停用：" + definitionId);
            Map<String, Object> definition = num(live, "version", -1) == number ? live : store.list("agentDefinitionRevision").stream()
                .map(revision -> map(revision, "record")).filter(revision -> definitionId.equals(str(revision, "id"))
                    && num(revision, "version", -1) == number).findFirst().orElseThrow(() -> new ApiException(409, "所选职责历史版本不存在：" + definitionId));
            validActor(definition);
            if ((!str(definition, "projectId").isBlank() && !projectId.equals(str(definition, "projectId")))
                    || (!str(live, "projectId").isBlank() && !projectId.equals(str(live, "projectId")))) throw new ApiException(403, "Workflow 不能引用其他项目的职责定义");
            if (!strings(definition.get("capabilities")).containsAll(strings(node.get("permissions")))) throw new ApiException(403, "节点申请了职责定义未提供的能力：" + str(node, "id"));
            if (num(node, "maxCalls", 8) > num(definition, "maxCalls", 8)) throw new ApiException(400, "节点调用上限不能增加职责定义的限制");
            pinned.putIfAbsent(definitionId + ":v" + number, definition);
        }
        return new ArrayList<>(pinned.values());
    }

    private static void validActor(Map<String, Object> actor) {
        if (!(actor.get("id") instanceof String id) || !id.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")
                || !(actor.get("version") instanceof Number version) || version.doubleValue() != version.intValue() || version.intValue() < 1
                || !(actor.get("maxCalls") instanceof Number calls) || calls.doubleValue() != calls.intValue() || calls.intValue() < 1 || calls.intValue() > 32
                || !(actor.get("enabled") instanceof Boolean) || !(actor.get("projectId") instanceof String) || !(actor.get("routeId") instanceof String)
                || !(actor.get("scope") instanceof List<?> scope) || scope.isEmpty()) throw new ApiException(409, "Workflow 固定职责身份或限制不完整");
        try {
            requiredText(actor, "name", 160); requiredText(actor, "role", 64); requiredText(actor, "instructions", 24000);
            AgentPermissions.capabilities(actor.get("capabilities")); AgentPermissions.scope(actor.get("scope"));
        } catch (ApiException exception) { throw new ApiException(409, "Workflow 固定职责内容或范围不完整"); }
    }

    private static int expected(Map<String, Object> input, int fallback) {
        if (!input.containsKey("expectedVersion") && fallback >= 0) return fallback;
        if (!(input.get("expectedVersion") instanceof Number number) || !Double.isFinite(number.doubleValue())
                || number.doubleValue() != number.longValue() || number.longValue() < 0 || number.longValue() >= Integer.MAX_VALUE) throw new ApiException(400, "expectedVersion 必须为非负整数");
        return number.intValue();
    }

    private static String identifier(Map<String, Object> input, String key) {
        String value = requiredText(input, key, 100);
        if (!value.matches("[A-Za-z0-9][A-Za-z0-9._:-]{0,99}")) throw new ApiException(400, key + " 身份格式无效");
        return value;
    }

    private static String requiredText(Map<String, Object> input, String key, int maximum) {
        if (!(input.get(key) instanceof String value) || value.isBlank() || value.length() > maximum || value.indexOf('\0') >= 0) throw new ApiException(400, key + " 必须为有效文字");
        return value.trim();
    }
}
