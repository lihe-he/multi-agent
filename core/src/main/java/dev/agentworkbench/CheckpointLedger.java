package dev.agentworkbench;

import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Path;
import java.time.Instant;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * SQLite 只发布已核验的文件检查点；撤销预览、原执行和旧证据分别保留。
 */
final class CheckpointLedger {
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final WorkspaceRuntime runtime;

    CheckpointLedger(ExecutionStore store, WorkbenchCatalog catalog, WorkspaceRuntime runtime) {
        this.store = store; this.catalog = catalog; this.runtime = runtime;
    }

    boolean available(Map<String, Object> task) {
        if (str(task, "workspace").isBlank()) return false;
        try { return Path.of(str(task, "workspace")).toRealPath().equals(store.directory().toRealPath().resolve("workspaces").resolve(str(task, "id")).resolve("work")); }
        catch (Exception exception) { return false; }
    }

    /**
     * 兼容旧运行记录；只有实际任务隔离现场才能产生文件恢复引用。
     */
    Map<String, Object> captureIfAvailable(String taskId, String kind, String stage) {
        Map<String, Object> task = store.get("task", taskId);
        if (!available(task)) return obj();
        return capture(taskId, kind, stage, "");
    }

    Map<String, Object> capture(String taskId, String kind, String stage, String label) {
        Map<String, Object> task = store.get("task", taskId);
        if (!available(task)) throw new ApiException(409, "任务尚无可保存的实际隔离现场。");
        Map<String, Object> metadata = metadata(task, kind, stage);
        if (!label.isBlank()) metadata.put("label", catalog.redact(label));
        Map<String, Object> captured = runtime.captureCheckpoint(str(task, "workspace"), taskId, metadata, map(task, "workspaceOwnership"));
        return publish(taskId, captured, true);
    }

    /**
     * 本机工具的快照上下文来自原根与运行，子执行目录不冒充父现场。
     */
    Map<String, Object> context(String taskId, String runId) {
        Map<String, Object> task = store.get("task", taskId), run = store.get("agentRun", runId);
        if (!available(task) || !str(task, "workspace").equals(str(run, "workspace"))) return obj();
        Map<String, Object> context = metadata(task, "TOOL_COMPLETED", str(run, "stage"));
        context.put("enabled", true); context.put("taskId", taskId); context.put("workspace", task.get("workspace"));
        context.put("workspaceOwnership", new LinkedHashMap<>(map(task, "workspaceOwnership")));
        Map<String, Object> source = new LinkedHashMap<>();
        for (String field : List.of("id", "operationId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "workerId", "workerEpoch", "executorPid", "processStartedAt", "stage"))
            if (run.containsKey(field)) source.put("id".equals(field) ? "agentRunId" : field, run.get(field));
        context.put("source", source); return context;
    }

    /**
     * 原管道回调和实际捕获进程都核对后才发布工具完成快照。
     */
    void observe(String taskId, String runId, String requestId, Map<String, Object> descriptor) {
        Map<String, Object> request = store.get("modelRequest", requestId), run = store.get("agentRun", runId);
        Map<String, Object> metadata = map(descriptor, "metadata"), source = map(metadata, "source");
        if (!"SUCCEEDED".equals(str(request, "state")) || !runId.equals(str(request, "agentRunId")) || !requestId.equals(str(metadata, "modelRequestId"))
                || !runId.equals(str(source, "agentRunId")) || !str(run, "operationId").equals(str(source, "operationId"))
                || !str(run, "taskExecutionId").equals(str(source, "taskExecutionId")) || !str(run, "workspaceWriteLeaseId").equals(str(source, "workspaceWriteLeaseId")))
            throw new ApiException(403, "检查点不属于原模型回执、运行或根写入归属。");
        if ("LOCAL_PROCESS".equals(str(run, "deployment")) && (!str(run, "executorPid").equals(str(descriptor, "captureExecutorPid"))
                || !str(run, "processStartedAt").equals(str(descriptor, "captureProcessStartedAt")))) throw new ApiException(403, "文件检查点不是原 Worker 实际捕获的回执。");
        publish(taskId, descriptor, true);
    }

    Map<String, Object> publish(String taskId, Map<String, Object> descriptor, boolean latest) {
        Map<String, Object> record = checkedRecord(taskId, descriptor, latest);
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId);
            Map<String, Object> old = store.find("checkpoint", str(record, "id"));
            if (old != null) {
                if (!taskId.equals(str(old, "taskId")) || !str(old, "manifestSha256").equals(str(record, "manifestSha256"))) throw new ApiException(409, "原检查点编号发生冲突。");
                return old;
            }
            Map<String, List<Map<String, Object>>> records = new LinkedHashMap<>(); records.put("checkpoint", List.of(record));
            if (latest) { point(task, record); records.put("task", List.of(task)); }
            store.commitRecords(records, taskId, "FILE_CHECKPOINT_SAVED", "实际文件检查点已保存：" + str(map(record, "metadata"), "kind") + " · " + str(record, "version"));
            return record;
        }
    }

    private Map<String, Object> checkedRecord(String taskId, Map<String, Object> descriptor, boolean visible) {
        Map<String, Object> task = store.get("task", taskId), manifest = runtime.checkpointManifest(taskId, str(descriptor, "id"), str(descriptor, "manifestSha256"));
        if (!taskId.equals(str(descriptor, "taskId")) || !runtime.checkpointWorkspaceMatches(str(descriptor, "workspace"), str(task, "workspace")) || !str(manifest, "workspace").equals(str(descriptor, "workspace"))
                || !str(manifest, "version").equals(str(descriptor, "version")) || !str(manifest, "head").equals(str(descriptor, "head"))
                || !str(manifest, "captureExecutorPid").equals(str(descriptor, "captureExecutorPid")) || !str(manifest, "captureProcessStartedAt").equals(str(descriptor, "captureProcessStartedAt"))
                || !sameJson(map(manifest, "metadata"), map(descriptor, "metadata"))) throw new ApiException(403, "检查点描述与原文件归档或任务不一致。");
        Map<String, Object> record = new LinkedHashMap<>(descriptor);
        record.putAll(obj("projectId", task.get("projectId"), "sessionId", task.getOrDefault("sessionId", ""), "visible", visible,
            "observedAt", now(), "coreObservation", obj("taskState", task.get("state"), "taskExecutionId", task.getOrDefault("taskExecutionId", ""), "coreSessionId", store.coreSessionId())));
        return record;
    }

    /**
     * 预览保存当前现场的独立引用；显示页面不改变最新任务保存边界。
     */
    Map<String, Object> preview(String taskId, String targetId) {
        Map<String, Object> task = store.get("task", taskId);
        String sourceId = str(task, "latestCheckpointId");
        if (sourceId.isBlank()) throw new ApiException(409, "尚无文件检查点，请先保存当前现场。");
        Map<String, Object> target = store.checkpointRecord(taskId, targetId), source = store.checkpointRecord(taskId, sourceId);
        if (!bool(target, "visible", true) || number(target, "sequence") >= number(source, "sequence")) throw new ApiException(409, "请选择最新保存边界之前的检查点。");
        Map<String, Object> preview = runtime.previewCheckpointRestore(str(task, "workspace"), taskId, target, source);
        publish(taskId, map(preview, "current"), false);
        preview.putAll(obj("id", id(), "projectId", task.get("projectId"), "state", "PREVIEW", "targetCheckpointId", targetId, "sourceCheckpointId", sourceId,
            "expiresAt", Instant.now().plus(Duration.ofMinutes(15)).toString(), "taskStateAtPreview", task.get("state"), "budgetAtPreview", new LinkedHashMap<>(map(task, "budget"))));
        store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview)), taskId, "CHECKPOINT_UNDO_PREVIEW", "撤销预览已核对当前文件与原保存边界");
        return preview;
    }

    Map<String, Object> prepareUndo(String taskId, String previewId) {
        Map<String, Object> task = store.get("task", taskId), preview = store.get("checkpointRestorePreview", previewId);
        if (!taskId.equals(str(preview, "taskId"))) throw new ApiException(403, "撤销预览不属于此任务。");
        if (!"PREVIEW".equals(str(preview, "state")) || !bool(preview, "canRestore", false) || !objects(preview.get("conflicts")).isEmpty()
                || (objects(preview.get("changes")).isEmpty() && !bool(preview, "indexChanges", false))) throw new ApiException(409, "预览已使用、存在冲突或没有可撤销的改动。");
        if (!Instant.now().isBefore(Instant.parse(str(preview, "expiresAt"))) || !str(task, "latestCheckpointId").equals(str(preview, "sourceCheckpointId")))
            throw new ApiException(409, "撤销预览已过期或保存边界已变化，请重新预览。");
        runtime.requireCheckpointRestoreCurrent(str(task, "workspace"), taskId, preview);
        preview.putAll(obj("state", "QUEUED", "requestedAt", now(), "requestedBy", "USER")); return preview;
    }

    Map<String, Object> beginUndo(String taskId, String operationId) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId), preview = store.get("checkpointRestorePreview", str(task, "pendingCheckpointRestoreId"));
            if (!"QUEUED".equals(str(preview, "state")) || !taskId.equals(str(preview, "taskId"))) throw new ApiException(409, "原撤销请求尚未排队或已执行。");
            preview.putAll(obj("state", "DISPATCHED", "operationId", operationId, "taskExecutionId", task.get("taskExecutionId"), "dispatchActionId", task.get("dispatchActionId"), "startedAt", now()));
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview)), taskId, "CHECKPOINT_UNDO_DISPATCHED", "原撤销预览绑定本次根执行与文件操作");
            return preview;
        }
    }

    /**
     * 先保存原 Worker 返回值再核对归属；观测成功不代表核心已确认操作成功。
     */
    void returned(String taskId, String operationId, Map<String, Object> result) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId), preview = store.get("checkpointRestorePreview", str(task, "pendingCheckpointRestoreId"));
            Map<String, Object> operation = store.get("operation", operationId);
            if (!"DISPATCHED".equals(str(preview, "state")) || !operationId.equals(str(preview, "operationId")) || !taskId.equals(str(operation, "taskId"))) throw new ApiException(409, "撤销观测不属于原请求和操作。");
            Map<String, Object> source = new LinkedHashMap<>();
            for (String field : List.of("id", "taskId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "workerLeaseId", "workerId", "workerEpoch", "executorPid", "processStartedAt"))
                if (operation.containsKey(field)) source.put("id".equals(field) ? "operationId" : field, operation.get(field));
            preview.put("observedReceipt", obj("observedAt", now(), "source", source, "result", result, "confirmation", "WORKER_OWNERSHIP_NOT_YET_CHECKED"));
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview)), taskId, "CHECKPOINT_UNDO_RECEIPT_OBSERVED", "原文件回执已返回，核心仍需核对本次 Worker 归属");
        }
    }

    void unknown(String taskId, String reason) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId); String previewId = str(task, "pendingCheckpointRestoreId");
            if (previewId.isBlank()) return;
            Map<String, Object> preview = store.get("checkpointRestorePreview", previewId);
            if (!Set.of("QUEUED", "DISPATCHED").contains(str(preview, "state"))) return;
            preview.putAll(obj("state", "UNKNOWN", "lostAt", now(), "reason", catalog.redact(reason)));
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview)), taskId, "CHECKPOINT_UNDO_UNKNOWN", "原撤销未完整确认；保留已返回观测与实际文件日志，不自动重放");
        }
    }

    /**
     * 新现场要求重新验证；旧运行、证据、会话和累计预算不随文件撤销倒退。
     */
    void completed(String taskId, Map<String, Object> result) {
        Map<String, Object> checkpoint = checkedRecord(taskId, map(result, "resultCheckpoint"), true);
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId), preview = store.get("checkpointRestorePreview", str(task, "pendingCheckpointRestoreId"));
            Map<String, Object> target = map(preview, "target"), metadata = map(target, "metadata");
            if (!"DISPATCHED".equals(str(preview, "state")) || !str(preview, "targetCheckpointId").equals(str(result, "targetCheckpointId"))
                    || !str(checkpoint, "version").equals(str(result, "resultVersion"))) throw new ApiException(409, "撤销结束回执不属于原请求。");
            Map<String, Object> planStates = map(metadata, "planStates");
            for (Map<String, Object> stage : objects(task.get("plan"))) {
                String stageId = str(stage, "id");
                stage.put("state", Set.of("verify", "review", "delivery").contains(stageId) ? "PENDING" : "COMPLETED".equals(str(planStates, stageId)) ? "COMPLETED" : "PENDING");
            }
            List<String> invalidated = new ArrayList<>(strings(task.get("invalidatedEvidence"))), kept = strings(metadata.get("evidenceIds"));
            for (Map<String, Object> evidence : objects(task.get("evidence"))) if (!kept.contains(str(evidence, "id"))) invalidated.add(str(evidence, "id"));
            task.putAll(obj("state", "PAUSED", "version", result.get("resultVersion"), "verification", obj("status", "NOT_RUN"), "review", obj("status", "NOT_RUN"),
                "issues", List.of(), "invalidatedEvidence", invalidated.stream().distinct().toList(), "lastCheckpointRestoreId", preview.get("id")));
            task.remove("pendingCheckpointRestoreId"); task.remove("activeOperation"); task.remove("error"); task.remove("outcome");
            point(task, checkpoint);
            preview.putAll(obj("state", "APPLIED", "completedAt", now(), "result", result));
            store.commitRecords(Map.of("checkpoint", List.of(checkpoint), "checkpointRestorePreview", List.of(preview), "task", List.of(task)), taskId,
                "CHECKPOINT_UNDO_APPLIED", "文件已按原预览恢复；保留历史与预算，任务暂停等待重新验证");
        }
    }

    void rejected(String taskId, String reason) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId);
            String previewId = str(task, "pendingCheckpointRestoreId"); if (previewId.isBlank()) return;
            Map<String, Object> preview = store.get("checkpointRestorePreview", previewId);
            preview.putAll(obj("state", "REJECTED", "completedAt", now(), "reason", catalog.redact(reason)));
            task.remove("pendingCheckpointRestoreId"); task.remove("activeOperation"); task.putAll(obj("state", "PAUSED", "error", catalog.redact(reason), "updatedAt", now()));
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview), "task", List.of(task)), taskId, "CHECKPOINT_UNDO_REJECTED", "未取得可执行的原撤销前提，当前文件保留");
        }
    }

    /**
     * 已领取的未完成撤销保持未知；原 PENDING 请求仍按根队列的从未执行事实处理。
     */
    void recover() {
        for (Map<String, Object> preview : store.list("checkpointRestorePreview")) {
            if (!Set.of("QUEUED", "DISPATCHED").contains(str(preview, "state"))) continue;
            Map<String, Object> task = store.get("task", str(preview, "taskId")), action = store.find("dispatchAction", str(task, "dispatchActionId"));
            if ("QUEUED".equals(str(preview, "state")) && action != null && "PENDING".equals(str(action, "state"))) continue;
            preview.putAll(obj("state", "UNKNOWN", "lostAt", now(), "reason", "核心重启，原撤销效果需随原根核对，不能自动重放"));
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview)), str(preview, "taskId"), "CHECKPOINT_UNDO_UNKNOWN", "保留原撤销请求和实际现场，不自动恢复文件");
        }
    }

    void reconciled(String taskId, String note) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId); String previewId = str(task, "pendingCheckpointRestoreId"); if (previewId.isBlank()) return;
            Map<String, Object> preview = store.get("checkpointRestorePreview", previewId);
            preview.put("reconciliation", obj("actor", "USER", "note", catalog.redact(note), "createdAt", now()));
            if (!Set.of("APPLIED", "REJECTED", "CANCELLED").contains(str(preview, "state"))) preview.put("state", "UNKNOWN");
            task.remove("pendingCheckpointRestoreId"); task.put("updatedAt", now());
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview), "task", List.of(task)), taskId, "CHECKPOINT_UNDO_RECONCILED", "用户核对原效果，旧撤销不重放；新的文件操作需要新预览");
        }
    }

    void stopBeforeUndo(String taskId) {
        synchronized (store) {
            Map<String, Object> task = store.get("task", taskId); String previewId = str(task, "pendingCheckpointRestoreId"); if (previewId.isBlank()) return;
            Map<String, Object> preview = store.get("checkpointRestorePreview", previewId);
            if (!"QUEUED".equals(str(preview, "state"))) return;
            preview.putAll(obj("state", "CANCELLED", "completedAt", now(), "reason", "撤销操作尚未派发，根执行已停止")); task.remove("pendingCheckpointRestoreId");
            store.commitRecords(Map.of("checkpointRestorePreview", List.of(preview), "task", List.of(task)), taskId, "CHECKPOINT_UNDO_CANCELLED", "未派发的撤销已随根停止，文件未恢复");
        }
    }

    private Map<String, Object> metadata(Map<String, Object> task, String kind, String stage) {
        Map<String, Object> plan = new LinkedHashMap<>();
        for (Map<String, Object> item : objects(task.get("plan"))) plan.put(str(item, "id"), item.getOrDefault("state", "PENDING"));
        String title = objects(task.get("plan")).stream().filter(item -> stage.equals(str(item, "id"))).map(item -> str(item, "title")).findFirst().orElse(stage);
        List<Map<String, Object>> context = objects(task.get("context")).stream().map(item -> obj("id", item.get("id"), "version", item.get("version"))).toList();
        return obj("kind", kind, "stage", stage, "title", title, "taskState", task.get("state"), "sessionId", task.getOrDefault("sessionId", ""), "planStates", plan,
            "context", context, "evidenceIds", objects(task.get("evidence")).stream().map(item -> str(item, "id")).toList(), "budgetSnapshot", new LinkedHashMap<>(map(task, "budget")),
            "taskExecutionId", task.getOrDefault("taskExecutionId", ""), "dispatchActionId", task.getOrDefault("dispatchActionId", ""),
            "unknownOperationIds", store.list("operation").stream().filter(item -> str(task, "id").equals(str(item, "taskId")) && "UNKNOWN".equals(str(item, "state"))).map(item -> str(item, "id")).toList());
    }

    private void point(Map<String, Object> task, Map<String, Object> record) {
        task.putAll(obj("latestCheckpointId", record.get("id"), "lastCheckpoint", obj("id", record.get("id"), "version", record.get("version"), "stage", map(record, "metadata").getOrDefault("stage", ""),
            "createdAt", record.get("createdAt"), "workspace", record.get("workspace"), "manifestSha256", record.get("manifestSha256")), "updatedAt", now()));
    }

    private long number(Map<String, Object> value, String field) { return ((Number) value.get(field)).longValue(); }

    private boolean sameJson(Object left, Object right) {
        try {
            var mapper = new com.fasterxml.jackson.databind.ObjectMapper();
            return mapper.readTree(mapper.writeValueAsBytes(left)).equals(mapper.readTree(mapper.writeValueAsBytes(right)));
        } catch (java.io.IOException exception) { throw new IllegalStateException("检查点元数据无法完整核验。", exception); }
    }
}
