package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 模型生成只保存可校验的流程候选；发布和任务选择分别需要明确的用户操作。
 */
final class WorkflowProposals {
    private final ExecutionStore store;
    private final WorkflowCatalog catalog;
    private final ObjectMapper mapper;
    private final ObjectReader candidateReader;

    WorkflowProposals(ExecutionStore store, WorkflowCatalog catalog, ObjectMapper mapper) {
        this.store = store; this.catalog = catalog; this.mapper = mapper;
        this.candidateReader = mapper.readerFor(new TypeReference<Map<String, Object>>() { })
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    String requestId(Map<String, Object> body) {
        if (!(body.get("requestId") instanceof String value) || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new ApiException(400, "流程生成需要原请求编号");
        return value;
    }

    Map<String, Object> prepare(Map<String, Object> task, Map<String, Object> body) {
        if (!Set.of("requestId", "maxCalls", "instructions", "definitionIds", "expectedVersion", "expectedSnapshotDigest", "expectedRunId").equals(body.keySet())) throw new ApiException(400, "流程生成包含未支持的字段");
        String requestId = requestId(body);
        if (!(body.get("maxCalls") instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1 || number.intValue() > 8) throw new ApiException(400, "流程生成调用上限为 1 至 8 次");
        if (!(body.get("instructions") instanceof String instructions) || instructions.length() > 6000 || instructions.indexOf('\0') >= 0) throw new ApiException(400, "流程生成要求最多 6000 字符");
        if (!(body.get("definitionIds") instanceof List<?> ids) || ids.isEmpty() || ids.size() > 64 || ids.stream().anyMatch(value -> !(value instanceof String))) throw new ApiException(400, "请选择 1 至 64 个已有职责定义");
        List<String> selected = strings(ids); if (new java.util.HashSet<>(selected).size() != selected.size()) throw new ApiException(400, "生成职责清单不能重复");
        List<Map<String, Object>> definitions = new ArrayList<>();
        for (String id : selected) {
            Map<String, Object> definition = store.get("agentDefinition", id);
            if (!bool(definition, "enabled", false) || !str(definition, "projectId").isBlank() && !str(task, "projectId").equals(str(definition, "projectId"))) throw new ApiException(403, "生成职责不属于当前项目或已停用");
            definitions.add(definition);
        }
        Map<String, Object> route = map(task, "routeSnapshot");
        if (!"API".equals(str(route, "engine"))) throw new ApiException(409, "流程生成需要支持逐请求记录的 API 路由");
        Map<String, Object> inputs = input(task);
        return obj("id", str(task, "id") + ":" + requestId, "protocolVersion", 1, "requestId", requestId, "taskId", task.get("id"), "projectId", task.get("projectId"),
            "sequence", num(task, "workflowGenerationSequence", 0) + 1, "state", "QUEUED", "createdAt", now(), "inputs", inputs,
            "inputDigest", WorkflowDsl.digest(inputs, mapper), "requestFingerprint", WorkflowDsl.digest(body, mapper), "agentDefinitions", definitions, "maxCalls", number.intValue(), "instructions", instructions,
            "expectedVersion", str(body, "expectedVersion"));
    }

    private Map<String, Object> input(Map<String, Object> task) {
        Map<String, Object> contract = new LinkedHashMap<>(map(task, "contract")); contract.remove("confirmed");
        return obj("goal", task.get("goal"), "contract", contract, "context", task.get("context"), "execution", task.get("execution"), "route", task.get("routeSnapshot"),
            "locks", task.get("locks"), "workflowSnapshotDigest", map(task, "workflow").get("snapshotDigest"));
    }

    void started(String generationId, String agentRunId, String version) {
        synchronized (store) {
            Map<String, Object> generation = store.get("workflowGeneration", generationId), task = store.get("task", str(generation, "taskId"));
            if (!"QUEUED".equals(str(generation, "state")) || !generationId.equals(str(task, "pendingWorkflowGenerationId"))
                    || !str(generation, "taskExecutionId").equals(str(task, "taskExecutionId")) || !str(generation, "dispatchActionId").equals(str(task, "dispatchActionId"))
                    || !str(generation, "inputDigest").equals(WorkflowDsl.digest(input(task), mapper))
                    || !str(generation, "expectedVersion").isBlank() && !version.equals(str(generation, "expectedVersion"))) throw new ApiException(409, "原生成目标、职责或执行输入已变化，禁止重放");
            store.requireActiveTaskExecution(task, task);
            generation.putAll(obj("state", "RUNNING", "agentRunId", agentRunId, "inputVersion", version, "startedAt", now()));
            ExecutionStore.copyTaskExecutionSource(generation, task);
            store.commitRecords(Map.of("workflowGeneration", List.of(generation)), str(task, "id"), "WORKFLOW_GENERATION_STARTED", "使用固定根路由生成候选；调用计入根开发预算");
        }
    }

    String prompt(Map<String, Object> generation) throws Exception {
        return "你是多 Agent 工作台的流程规划者。阶段 kind=WORKFLOW_GENERATION。\n只调查已有项目，生成可编辑的流程数据，不能修改文件、执行命令、委派、调用 MCP、发布或运行候选。"
            + "\n根目标、完成约定、固定上下文与执行约束：" + mapper.writeValueAsString(generation.get("inputs"))
            + "\n用户的生成要求：" + str(generation, "instructions") + "\n允许引用的固定职责定义：" + mapper.writeValueAsString(generation.get("agentDefinitions"))
            + "\n最终输出一个 JSON 对象，仅包含 name 和 dsl。name 最多 160 字符。dsl 只包含 dslVersion（1）、inputs（goal/contract/context/baseline）、maxParallel（1 至 6）和 nodes（1 至 64 个）。"
            + "\n每个节点包含 id、kind、title、role、goal、definitionId、definitionVersion、dependencies、inputs、outputs、scope、permissions、maxCalls、timeoutSeconds、acceptance、termination。"
            + "\nid 使用英文字母开头的字母、数字、下划线或连字符；不能使用 baseline/workflow/verify/review/delivery/prepare。kind 为 INVESTIGATE、DESIGN、PROPOSAL、CRITIQUE、SYNTHESIS 或 IMPLEMENT。"
            + "\n依赖必须无环。输入只能引用声明的根输入或实际祖先的唯一逻辑输出。definitionId 和 definitionVersion 必须来自上面清单。权限与调用上限不能超出该职责，文件范围只能为项目内相对路径或 glob。"
            + "\n只有 IMPLEMENT 可声明 write、verify、mcp；PROPOSAL 不能 delegate，必须独立调查。每节点至少 read，timeoutSeconds 为 1 至 900。所有节点填写可核对的验收和终止条件。"
            + "\n主副调查、圆桌独立提案与交叉质疑、实现阶段可按目标组合。模型执行容量仍为二，路由与根硬锁定约束保持。节点结果不能代替根必要验证、独立审查和人工验收，这些门禁由根任务执行。"
            + "\n生成的脚本是数据，不提供主机路径、代码执行器或额外授权。只返回完整 JSON，不附带分析文字。";
    }

    /**
     * 候选来源核对已结束 Agent、全部成功请求与原 Worker 回执，解析失败也保留原证据。
     */
    Map<String, Object> record(String generationId) {
        synchronized (store) {
            Map<String, Object> generation = store.get("workflowGeneration", generationId); Source source = source(generation);
            if (!"RUNNING".equals(str(generation, "state"))) throw new ApiException(409, "原生成已经结束，不能重复发布候选");
            Map<String, Object> candidate = obj(), checked = obj(); List<String> errors = new ArrayList<>();
            try {
                String text = str(source.evidence(), "output").trim(); if (text.length() > 1024 * 1024) throw new ApiException(400, "生成输出超过 1 MB，保留原证据但不能作为候选");
                if (text.startsWith("```json") && text.endsWith("```")) {
                    int line = text.indexOf('\n');
                    if (line >= 7 && text.substring(7, line).isBlank()) text = text.substring(line + 1, text.length() - 3).trim();
                }
                candidate = candidateReader.readValue(text);
                if (candidate == null || !candidate.keySet().equals(Set.of("name", "dsl")) || !(candidate.get("name") instanceof String name) || name.isBlank() || name.length() > 160 || name.indexOf('\0') >= 0 || !(candidate.get("dsl") instanceof Map<?, ?>)) throw new ApiException(400, "候选应仅包含流程名称和 DSL 对象");
                checked = catalog.validateDefinition(obj("dsl", candidate.get("dsl"), "projectId", generation.get("projectId")));
                if (!bool(checked, "valid", false)) errors.addAll(strings(checked.get("errors")));
                else for (Map<String, Object> actor : objects(checked.get("agentDefinitions"))) {
                    Map<String, Object> original = objects(generation.get("agentDefinitions")).stream().filter(value -> str(actor, "id").equals(str(value, "id")) && num(actor, "version", -1) == num(value, "version", -2)).findFirst().orElse(null);
                    if (original == null || !WorkflowDsl.digest(actor, mapper).equals(WorkflowDsl.digest(original, mapper))) throw new ApiException(409, "候选引用了生成时未确认的职责版本");
                }
            } catch (Exception exception) { errors.add(exception instanceof ApiException ? exception.getMessage() : "模型输出不是完整的流程 JSON 对象"); }
            Map<String, Object> proposal = obj("id", id(), "protocolVersion", 1, "taskId", generation.get("taskId"), "projectId", generation.get("projectId"), "generationId", generationId,
                "sequence", generation.get("sequence"), "state", errors.isEmpty() ? "VALIDATED" : "INVALID", "errors", errors, "name", errors.isEmpty() ? str(candidate, "name") : "未通过校验的流程候选",
                "agentRunId", source.agent().get("id"), "modelRequestId", source.lastRequest().get("id"), "toolCallId", "FINAL_OUTPUT", "evidenceId", source.evidence().get("id"),
                "inputVersion", generation.get("inputVersion"), "sourceDigest", source.digest(), "createdAt", now());
            if (errors.isEmpty()) { proposal.put("dsl", checked.get("dsl")); proposal.put("agentDefinitions", checked.get("agentDefinitions")); proposal.put("digest", catalog.digest(map(checked, "dsl"), objects(checked.get("agentDefinitions")))); }
            generation.putAll(obj("state", errors.isEmpty() ? "COMPLETED" : "INVALID", "proposalId", proposal.get("id"), "endedAt", now()));
            store.commitRecords(Map.of("workflowGeneration", List.of(generation), "workflowProposal", List.of(proposal)), str(generation, "taskId"), "WORKFLOW_CANDIDATE_RECORDED", errors.isEmpty() ? "生成候选已校验，等待用户编辑与发布" : "生成候选未通过校验，保留原请求与证据");
            return proposal;
        }
    }

    /**
     * 人工发布时重查原请求、根收尾和候选摘要；重复提交只返回原发布版本。
     */
    Map<String, Object> publish(String taskId, String proposalId, Map<String, Object> body) {
        if (!Set.of("name", "expectedSourceDigest", "dsl").containsAll(body.keySet()) || !(body.get("name") instanceof String name) || name.isBlank()
                || name.length() > 160 || !(body.get("expectedSourceDigest") instanceof String) || body.containsKey("dsl") && !(body.get("dsl") instanceof Map<?, ?>)) throw new ApiException(400, "发布候选需要流程名称和原来源摘要");
        synchronized (store) {
            Map<String, Object> proposal = proposal(taskId, proposalId), generation = store.get("workflowGeneration", str(proposal, "generationId"));
            Map<String, Object> execution = store.get("taskExecution", str(generation, "taskExecutionId")), action = store.get("dispatchAction", str(generation, "dispatchActionId"));
            if (!Set.of("VALIDATED", "PUBLISHED").contains(str(proposal, "state")) || !"COMPLETED".equals(str(generation, "state"))
                    || !proposalId.equals(str(generation, "proposalId")) || !taskId.equals(str(generation, "taskId"))
                    || !str(body, "expectedSourceDigest").equals(str(proposal, "sourceDigest"))
                    || !"SUCCEEDED".equals(str(execution, "state")) || !"SETTLED".equals(str(action, "state"))
                    || !str(execution, "id").equals(str(action, "taskExecutionId")) || !str(generation, "id").equals(str(execution, "workflowGenerationId"))
                    || !str(proposal, "sourceDigest").equals(source(generation).digest())) throw new ApiException(409, "原候选、模型证据或根结束回执不完整，停止发布并核对");
            Map<String, Object> original = map(proposal, "dsl");
            if (!str(proposal, "digest").equals(catalog.digest(original, objects(proposal.get("agentDefinitions"))))) throw new ApiException(409, "原候选内容已变化");
            Map<String, Object> dsl = body.containsKey("dsl") ? WorkflowDsl.normalize(map(body, "dsl")) : original;
            String fingerprint = WorkflowDsl.digest(obj("name", name.trim(), "dsl", dsl), mapper);
            if ("PUBLISHED".equals(str(proposal, "state"))) {
                if (!fingerprint.equals(str(proposal, "publicationFingerprint"))) throw new ApiException(409, "此候选已发布；请在流程模板中编辑新版本");
                String definitionId = str(proposal, "publishedDefinitionId");
                return obj("definition", store.get("workflowDefinition", definitionId), "version", catalog.version(definitionId, num(proposal, "publishedVersion", 0)));
            }
            return catalog.saveGenerated(obj("name", name, "projectId", proposal.get("projectId"), "dsl", dsl), proposal, fingerprint, !dsl.equals(original));
        }
    }

    private Source source(Map<String, Object> generation) {
        Map<String, Object> agent = store.get("agentRun", str(generation, "agentRunId")), evidence = store.get("evidence", str(agent, "evidenceId"));
        List<Map<String, Object>> requests = store.list("modelRequest").stream().filter(request -> str(agent, "id").equals(str(request, "agentRunId"))).sorted((a, b) -> Integer.compare(num(a, "sequence", 0), num(b, "sequence", 0))).toList();
        if (!"SUCCEEDED".equals(str(agent, "state")) || !"WORKFLOW_GENERATION".equals(str(agent, "kind")) || !str(generation, "taskId").equals(str(agent, "taskId"))
                || !str(generation, "inputDigest").equals(WorkflowDsl.digest(map(generation, "inputs"), mapper)) || !bool(agent, "readOnly", false)
                || !Set.of("read", "search").containsAll(strings(map(agent, "permissions").get("capabilities")))
                || !"PASSED".equals(str(evidence, "status")) || !"WORKFLOW_GENERATION".equals(str(evidence, "kind")) || !str(agent, "id").equals(str(evidence, "agentRunId"))
                || !str(generation, "id").equals(str(evidence, "workflowGenerationId"))
                || !str(generation, "inputVersion").equals(str(evidence, "inputVersion")) || !str(generation, "inputVersion").equals(str(evidence, "version"))
                || requests.isEmpty() || requests.size() != num(agent, "calls", -1) || requests.size() > num(generation, "maxCalls", 0)
                || requests.stream().anyMatch(request -> !"SUCCEEDED".equals(str(request, "state")) || !str(generation, "taskId").equals(str(request, "taskId")))) throw new ApiException(409, "生成缺少完整成功请求或原只读现场证据");
        Map<String, Object> operation = store.get("operation", str(evidence, "operationId")), lease = store.get("workerLease", str(evidence, "workerLeaseId"));
        if (!"WORKFLOW_GENERATION".equals(str(operation, "kind")) || !"ACKNOWLEDGED".equals(str(operation, "state"))
                || !str(agent, "id").equals(str(operation, "agentRunId")) || !str(generation, "taskId").equals(str(operation, "taskId"))
                || !str(generation, "id").equals(str(operation, "workflowGenerationId")) || !str(evidence, "id").equals(str(operation, "evidenceId"))
                || !"RELEASED".equals(str(lease, "state")) || !"CONFIRMED".equals(str(lease, "stopState")) || !str(operation, "id").equals(str(lease, "operationId"))
                || !str(evidence, "workerId").equals(str(lease, "workerId"))) throw new ApiException(409, "生成缺少原 Worker 领取、证据与完整收尾");
        Map<String, Object> route = map(map(generation, "inputs"), "route");
        if (!str(route, "id").equals(str(agent, "routeId")) || num(route, "version", -1) != num(agent, "routeVersion", -2)
                || requests.stream().anyMatch(request -> !str(route, "id").equals(str(request, "routeId")) || num(route, "version", -1) != num(request, "routeVersion", -2))) throw new ApiException(409, "生成实际路由与固定根路由不一致");
        for (String key : List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId")) if (!str(generation, key).equals(str(agent, key))
                || !str(generation, key).equals(str(operation, key)) || !str(generation, key).equals(str(lease, key))
                || !str(generation, key).equals(str(evidence, key)) || requests.stream().anyMatch(request -> !str(generation, key).equals(str(request, key)))) throw new ApiException(409, "生成原根执行来源不完整");
        String digest = WorkflowDsl.digest(obj("generationId", generation.get("id"), "inputs", generation.get("inputs"), "inputDigest", generation.get("inputDigest"),
            "agentDefinitions", generation.get("agentDefinitions"), "agent", agent, "evidence", evidence, "requests", requests), mapper);
        return new Source(agent, evidence, requests.getLast(), digest);
    }

    Map<String, Object> proposal(String taskId, String proposalId) {
        store.get("task", taskId); Map<String, Object> proposal = store.get("workflowProposal", proposalId);
        if (!taskId.equals(str(proposal, "taskId"))) throw new ApiException(404, "候选不属于当前任务"); return proposal;
    }

    Map<String, Object> page(String taskId, int before, int limit) {
        store.get("task", taskId); if (before < 0 || limit < 1 || limit > 30) throw new ApiException(400, "流程候选游标或页大小无效");
        List<Map<String, Object>> selected = store.list("workflowProposal").stream().filter(proposal -> taskId.equals(str(proposal, "taskId")) && (before == 0 || num(proposal, "sequence", 0) < before))
            .sorted((a, b) -> Integer.compare(num(b, "sequence", 0), num(a, "sequence", 0))).toList();
        List<Map<String, Object>> page = selected.stream().limit(limit).map(proposal -> { Map<String, Object> value = new LinkedHashMap<>(proposal); value.remove("dsl"); value.remove("agentDefinitions"); return value; }).toList();
        return obj("proposals", page, "hasMore", selected.size() > page.size(), "next", page.isEmpty() ? before : num(page.getLast(), "sequence", 0));
    }

    List<Map<String, Object>> generations(String taskId) {
        store.get("task", taskId);
        return store.list("workflowGeneration").stream().filter(value -> taskId.equals(str(value, "taskId")))
            .sorted((a, b) -> Integer.compare(num(b, "sequence", 0), num(a, "sequence", 0))).limit(30)
            .map(value -> obj("id", value.get("id"), "requestId", value.get("requestId"), "state", value.get("state"), "sequence", value.get("sequence"),
                "agentRunId", value.getOrDefault("agentRunId", ""), "maxCalls", value.get("maxCalls"), "createdAt", value.get("createdAt"),
                "endedAt", value.getOrDefault("endedAt", ""), "reason", value.getOrDefault("reason", ""), "proposalId", value.getOrDefault("proposalId", ""))).toList();
    }

    private record Source(Map<String, Object> agent, Map<String, Object> evidence, Map<String, Object> lastRequest, String digest) { }
}
