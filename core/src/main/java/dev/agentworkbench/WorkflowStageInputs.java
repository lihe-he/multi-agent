package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 只投影原在途 Workflow 尝试的固定输入；调用存活与 Worker 身份仍由原 Invocation 核验。
 */
final class WorkflowStageInputs {
    private static final List<String> EXECUTION_SOURCE = List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId");
    private static final List<String> WORKFLOW_SOURCE = List.of("workflowRunId", "workflowNodeId", "workflowNodeAttemptId");
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final ObjectMapper mapper;
    private final WorkflowLedger ledger;

    WorkflowStageInputs(ExecutionStore store, WorkbenchCatalog catalog, ObjectMapper mapper) {
        this.store = store; this.catalog = catalog; this.mapper = mapper;
        this.ledger = new WorkflowLedger(store, catalog.workflows(), mapper);
    }

    /**
     * 身份全部从原租约和账本派生，返回独立副本且不读取实时项目记忆。
     */
    Map<String, Object> read(Map<String, Object> lease) {
        synchronized (store) {
            if (!store.healthy()) throw new ApiException(503, "持久存储不可用，停止读取阶段输入");
            if (lease == null || !"operation".equals(str(lease, "operationKind")) || !"WORKFLOW_MODEL".equals(str(lease, "operationType"))) {
                throw new ApiException(403, "阶段输入只属于原 Workflow 模型调用");
            }
            Map<String, Object> liveLease = store.get("workerLease", text(lease, "id"));
            same(lease, liveLease, List.of("id", "operationKind", "operationId", "operationType", "taskId", "projectId", "agentRunId", "workerId", "claimDigest"));
            same(lease, liveLease, EXECUTION_SOURCE); same(lease, liveLease, WORKFLOW_SOURCE);
            if (!Objects.equals(lease.get("epoch"), liveLease.get("epoch")) || num(lease, "epoch", -1) < 1 || !"ACTIVE".equals(str(liveLease, "state"))) throw invalid();
            try { if (!Instant.now().isBefore(Instant.parse(text(liveLease, "expiresAt")))) throw invalid(); }
            catch (java.time.format.DateTimeParseException exception) { throw invalid(); }

            Map<String, Object> run = store.get("agentRun", text(lease, "agentRunId")), task = store.get("task", text(lease, "taskId"));
            Map<String, Object> attempt = store.get("workflowNodeAttempt", text(run, "workflowNodeAttemptId"));
            Map<String, Object> workflow = ledger.current(text(attempt, "workflowRunId"));
            Map<String, Object> operation = store.get("operation", text(lease, "operationId"));
            if (!"RUNNING".equals(str(task, "state")) || !bool(map(task, "contract"), "confirmed", false)
                    || !"RUNNING".equals(str(workflow, "state")) || attempt.containsKey("endedAt")
                    || !WorkflowLedger.LIVE.contains(str(attempt, "state")) || !WorkflowLedger.LIVE.contains(str(run, "state"))
                    || !"DISPATCHED".equals(str(operation, "state")) || !"WORKFLOW_MODEL".equals(str(operation, "kind"))
                    || !text(operation, "id").equals(text(run, "operationId")) || store.hasUnknownEffects(text(task, "id"))) throw invalid();
            same(lease, run, List.of("taskId", "projectId")); same(lease, attempt, List.of("taskId", "projectId", "agentRunId"));
            same(lease, operation, List.of("taskId", "agentRunId")); same(run, workflow, List.of("taskId", "projectId"));
            if (!text(task, "projectId").equals(text(run, "projectId")) || !text(attempt, "id").equals(text(run, "workflowNodeAttemptId"))
                    || !text(attempt, "nodeId").equals(text(run, "workflowNodeId")) || !text(workflow, "id").equals(text(run, "workflowRunId"))
                    || !text(attempt, "inputDigest").equals(text(workflow, "inputDigest"))) throw invalid();
            for (Map<String, Object> source : List.of(lease, liveLease, run, attempt, workflow, operation)) {
                same(task, source, EXECUTION_SOURCE); store.requireActiveTaskExecution(task, source);
            }
            for (Map<String, Object> source : List.of(lease, operation)) same(run, source, WORKFLOW_SOURCE);
            if (!text(lease, "id").equals(text(operation, "workerLeaseId")) || !text(lease, "workerId").equals(text(operation, "workerId"))
                    || num(lease, "epoch", -1) != num(operation, "workerEpoch", -2)) throw invalid();
            Map<String, Object> nodeState = map(map(workflow, "nodes"), text(attempt, "nodeId"));
            if (!text(attempt, "id").equals(text(nodeState, "attemptId")) || !text(run, "id").equals(text(nodeState, "agentRunId"))) throw invalid();
            ledger.requireInput(task, workflow);

            Map<String, Object> binding = catalog.workflows().binding(map(task, "workflow"), text(attempt, "nodeId"));
            Map<String, Object> node = map(attempt, "node"), actor = map(binding, "actor"), permissions = map(binding, "permissions"), route = map(binding, "routeSnapshot");
            Map<String, Map<String, Object>> fixedNodes = WorkflowDsl.byId(objects(map(map(task, "workflow"), "dsl").get("nodes")));
            Map<String, Object> declared = fixedNodes.get(text(attempt, "nodeId"));
            if (declared == null) throw invalid();
            List<String> ancestors = WorkflowDsl.order(new ArrayList<>(fixedNodes.values())).stream()
                .filter(WorkflowDsl.ancestors(text(attempt, "nodeId"), fixedNodes)::contains).toList();
            if (!ancestors.equals(strings(attempt.get("ancestorNodeIds")))) throw invalid();
            for (Map<String, Object> source : List.of(run, lease, operation)) if (!ancestors.equals(strings(source.get("workflowAncestorNodeIds")))) throw invalid();
            if (!equalJson(declared, node) || !"CONFIRMED_WORKFLOW_DEFINITION".equals(str(attempt, "source"))
                    || !equalJson(permissions, map(attempt, "permissions")) || !equalJson(permissions, map(run, "permissions"))
                    || !AgentPermissions.has(permissions, "read") || !AgentPermissions.unrestricted(permissions, "read")) {
                throw new ApiException(403, "阶段固定输入来源或读取范围无法核验");
            }
            same(node, run, List.of("kind", "role"));
            for (Map<String, Object> source : List.of(attempt, run)) {
                if (!text(actor, "id").equals(text(source, "definitionId")) || num(actor, "version", -1) != num(source, "definitionVersion", -2)) throw invalid();
            }
            Map<String, Object> liveActor = store.get("agentDefinition", text(actor, "id")), liveRoute = store.get("route", text(route, "id"));
            if (!bool(liveActor, "enabled", false) || !str(liveActor, "projectId").isBlank() && !text(task, "projectId").equals(str(liveActor, "projectId"))
                    || !bool(liveRoute, "enabled", false) || !BudgetPolicy.matchesLocks(route, map(task, "locks"))
                    || !text(route, "id").equals(text(run, "routeId")) || num(route, "version", -1) != num(run, "routeVersion", -2)
                    || !Objects.equals(route.get("engine"), run.get("engine")) || !Objects.equals(route.get("model"), run.get("model"))
                    || !Objects.equals(route.get("provider"), run.get("provider"))) throw invalid();
            if (!text(attempt, "workspace").equals(text(run, "workspace")) || !text(attempt, "inputVersion").equals(text(run, "inputVersion"))) throw invalid();

            Map<String, Object> inputOperation = store.get("operation", text(attempt, "inputOperationId"));
            if (!"WORKFLOW_INPUTS".equals(str(inputOperation, "kind")) || !"ACKNOWLEDGED".equals(str(inputOperation, "state"))
                    || num(attempt, "promptInputProjectionVersion", -1) != 1 || num(inputOperation, "promptInputProjectionVersion", -1) != 1
                    || !(attempt.get("inputs") instanceof Map<?, ?>) || !equalJson(attempt.get("inputs"), inputOperation.get("inputs"))) throw invalid();
            same(run, inputOperation, List.of("taskId"));
            if (!text(run, "id").equals(text(inputOperation, "agentRunId"))) throw invalid();
            same(attempt, inputOperation, List.of("workspace", "baseWorkspace", "inputVersion"));
            same(run, inputOperation, EXECUTION_SOURCE); same(run, inputOperation, WORKFLOW_SOURCE);
            Map<String, Object> inputLease = store.get("workerLease", text(inputOperation, "workerLeaseId"));
            if (!"RELEASED".equals(str(inputLease, "state")) || !"CONFIRMED".equals(str(inputLease, "stopState"))
                    || !"operation".equals(str(inputLease, "operationKind")) || !"WORKFLOW_INPUTS".equals(str(inputLease, "operationType"))
                    || !text(inputOperation, "id").equals(text(inputLease, "operationId"))
                    || !text(inputOperation, "workerId").equals(text(inputLease, "workerId"))
                    || num(inputOperation, "workerEpoch", -1) != num(inputLease, "epoch", -2)) throw invalid();
            same(run, inputLease, List.of("taskId", "projectId"));
            if (!text(run, "id").equals(text(inputLease, "agentRunId"))) throw invalid();
            same(run, inputLease, EXECUTION_SOURCE); same(run, inputLease, WORKFLOW_SOURCE);
            if (!equalJson(attempt.get("inputReceipts"), inputOperation.get("inputReceipts"))) throw invalid();
            requireInputs(task, workflow, attempt, node);

            List<Map<String, Object>> rules = new ArrayList<>(), ruleSources = new ArrayList<>();
            for (Map<String, Object> item : objects(task.get("context"))) if (projectRule(item)) {
                if (!(item.get("content") instanceof String) || item.get("version") == null) throw invalid();
                rules.add(obj("source", "AGENTS.md", "version", item.get("version"), "content", item.get("content")));
                ruleSources.add(obj("source", "AGENTS.md", "version", item.get("version")));
            }
            if (!equalJson(ruleSources, attempt.get("projectRuleSources")) || !equalJson(ruleSources, inputOperation.get("projectRuleSources"))) throw invalid();
            Map<String, Object> projection = obj("identity", obj("projectId", task.get("projectId"), "taskId", task.get("id"),
                "workflowRunId", workflow.get("id"), "workflowNodeId", attempt.get("nodeId"), "workflowNodeAttemptId", attempt.get("id"),
                "agentRunId", run.get("id"), "inputVersion", attempt.get("inputVersion"), "promptInputProjectionVersion", 1),
                "node", obj("id", node.get("id"), "kind", node.get("kind"), "role", node.get("role"), "goal", node.get("goal"),
                    "outputs", node.get("outputs"), "acceptance", node.get("acceptance"), "termination", node.get("termination")),
                "inputs", attempt.get("inputs"), "projectRules", rules);
            try {
                JsonNode redacted = redactValues(mapper.valueToTree(projection));
                return mapper.convertValue(redacted, new TypeReference<Map<String, Object>>() { });
            } catch (Exception exception) { throw new ApiException(409, "阶段输入无法安全投影，停止读取"); }
        }
    }

    /**
     * 名称必须来自原声明，值必须仍对应固定根资料或完整祖先证据。
     */
    private void requireInputs(Map<String, Object> task, Map<String, Object> workflow, Map<String, Object> attempt, Map<String, Object> node) {
        Map<String, Object> inputs = map(attempt, "inputs");
        if (!inputs.keySet().equals(Set.copyOf(strings(node.get("inputs"))))) throw invalid();
        for (String name : strings(node.get("inputs"))) {
            Object expected;
            if ("context".equals(name)) expected = objects(task.get("context")).stream().filter(item -> !projectRule(item)).toList();
            else if (Set.of("goal", "contract").contains(name)) expected = task.get(name);
            else if ("baseline".equals(name)) {
                List<String> ancestors = strings(attempt.get("ancestorNodeIds"));
                Object version = ancestors.isEmpty() ? workflow.get("baselineVersion")
                    : ledger.completed(text(workflow, "id"), ancestors.getFirst()).get("baselineVersion");
                expected = obj("version", version);
            }
            else {
                Map<String, Object> handoff = map(inputs.get(name));
                String sourceNode = text(handoff, "sourceNodeId");
                if (!strings(attempt.get("ancestorNodeIds")).contains(sourceNode)) throw invalid();
                Map<String, Object> source = ledger.completed(text(workflow, "id"), sourceNode);
                if (!strings(map(source, "node").get("outputs")).contains(name)) throw invalid();
                Map<String, Object> evidence = store.get("evidence", text(source, "evidenceId")); String output = str(evidence, "output");
                expected = obj("sourceNodeId", source.get("nodeId"), "sourceAttemptId", source.get("id"), "agentRunId", source.get("agentRunId"),
                    "evidenceId", evidence.get("id"), "version", source.get("resultVersion"), "output", output.substring(0, Math.min(12000, output.length())), "outputTruncated", output.length() > 12000);
            }
            if (!equalJson(expected, inputs.get(name))) throw invalid();
        }
    }

    private static boolean projectRule(Map<String, Object> value) {
        return "project-rules".equals(str(value, "id")) && "RULE".equals(str(value, "kind")) && "AGENTS.md".equals(str(value, "source"));
    }

    /**
     * 在解码后的文本值上脱敏，保留业务键、JSON 转义和其他值的类型。
     */
    private JsonNode redactValues(JsonNode value) {
        if (value.isTextual()) return mapper.getNodeFactory().textNode(catalog.redact(value.textValue()));
        if (value instanceof ObjectNode object) {
            List<String> names = new ArrayList<>(); object.fieldNames().forEachRemaining(names::add);
            for (String name : names) object.set(name, redactValues(object.get(name)));
        } else if (value instanceof ArrayNode array) {
            for (int index = 0; index < array.size(); index++) array.set(index, redactValues(array.get(index)));
        }
        return value;
    }

    private boolean equalJson(Object left, Object right) { return Objects.equals(mapper.valueToTree(left), mapper.valueToTree(right)); }

    private static void same(Map<String, Object> expected, Map<String, Object> actual, List<String> fields) {
        for (String field : fields) if (!text(expected, field).equals(text(actual, field))) throw invalid();
    }

    private static String text(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof String text) || text.isBlank()) throw invalid();
        return text;
    }

    private static ApiException invalid() { return new ApiException(409, "阶段输入失去原 Workflow、尝试、路由或 Worker 操作来源"); }
}
