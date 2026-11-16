package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.io.IOException;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 交付包按原摘要保存检查点和撤销进度，包括尚未发布成功引用的实际结果。
 */
final class CheckpointExportHistory {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final ExecutionStore store;
    private final WorkspaceRuntime runtime;
    private final ObjectMapper mapper;

    CheckpointExportHistory(ExecutionStore store, WorkspaceRuntime runtime, ObjectMapper mapper) {
        this.store = store; this.runtime = runtime; this.mapper = mapper;
    }

    record Snapshot(List<Map<String, Object>> checkpoints, List<Map<String, Object>> restores, List<Map<String, Object>> operations,
            List<Map<String, Object>> journals, Map<String, byte[]> files, long bytes) { }

    Snapshot snapshot(String taskId) {
        List<Map<String, Object>> checkpoints, restores, operations;
        synchronized (store) {
            checkpoints = store.taskCheckpointRecords(taskId, "checkpoint");
            restores = store.taskCheckpointRecords(taskId, "checkpointRestorePreview");
            operations = operations(taskId);
        }
        Map<String, Map<String, Object>> references = new LinkedHashMap<>();
        for (Map<String, Object> checkpoint : checkpoints) reference(taskId, references, checkpoint);
        for (Map<String, Object> restore : restores) {
            for (String field : List.of("target", "source", "current")) reference(taskId, references, map(restore, field));
            reference(taskId, references, map(map(restore, "result"), "resultCheckpoint"));
            reference(taskId, references, map(map(map(restore, "observedReceipt"), "result"), "resultCheckpoint"));
        }
        Map<String, byte[]> files = new LinkedHashMap<>(); List<Map<String, Object>> journals = new ArrayList<>();
        long total = 0;
        for (Map<String, Object> operation : operations) {
            String operationId = str(operation, "id"); Map<String, Object> journal = runtime.checkpointRestoreJournal(taskId, operationId);
            boolean present = bool(journal, "journalPresent", false);
            Map<String, Object> source = obj("operationId", operationId, "journalPresent", present, "coreOperationState", operation.get("state"));
            if (present) {
                byte[] bytes = (byte[]) journal.get("content"); String digest = str(journal, "sha256");
                if (!hash(bytes).equals(digest)) throw new ApiException(409, "撤销日志字节与本次读取摘要不一致。");
                if (!str(operation, "journalSha256").isBlank() && !str(operation, "journalSha256").equals(digest)) throw new ApiException(409, "原撤销回执的日志摘要变化，不能导出混合证据。");
                Map<String, Object> progress = map(journal, "progress");
                source.putAll(obj("sha256", digest, "bytes", bytes.length, "localProgressState", progress.get("state"),
                    "digestSource", str(operation, "journalSha256").isBlank() ? "EXPORT_OBSERVATION_ONLY" : "ORIGINAL_WORKER_RECEIPT"));
                total = add(files, "checkpoints/restores/" + operationId + "/manifest.json", bytes, total);
                for (String field : List.of("target", "source", "current", "result")) reference(taskId, references, map(progress, field));
            }
            reference(taskId, references, map(operation, "resultCheckpoint")); journals.add(source);
        }
        for (Map<String, Object> reference : references.values()) {
            String checkpointId = str(reference, "id");
            byte[] bytes = runtime.checkpointManifestBytes(taskId, checkpointId, str(reference, "manifestSha256"));
            total = add(files, "checkpoints/tasks/" + checkpointId + "/manifest.json", bytes, total);
            Map<String, Object> manifest;
            try { manifest = mapper.readValue(bytes, new TypeReference<>() {}); }
            catch (IOException exception) { throw new ApiException(409, "原检查点清单无法完整解析。"); }
            for (Map<String, Object> file : objects(manifest.get("files"))) if ("STORED".equals(str(file, "state"))) total = object(files, str(file, "blob"), total);
            Map<String, Object> index = map(manifest, "index");
            if (bool(index, "present", false)) total = object(files, str(index, "blob"), total);
            for (Map<String, Object> staged : objects(index.get("stagedObjects"))) total = object(files, str(staged, "blob"), total);
        }
        return new Snapshot(checkpoints, restores, operations, journals, files, total);
    }

    /**
     * 导出结束再核对 SQLite 原记录和日志，未知操作仍保持两侧独立状态。
     */
    void requireCurrent(String taskId, Snapshot snapshot) {
        synchronized (store) {
            if (!snapshot.checkpoints.equals(store.taskCheckpointRecords(taskId, "checkpoint"))
                    || !snapshot.restores.equals(store.taskCheckpointRecords(taskId, "checkpointRestorePreview")) || !snapshot.operations.equals(operations(taskId)))
                throw new ApiException(409, "导出期间检查点或撤销记录变化，请重试。");
        }
        for (Map<String, Object> original : snapshot.journals) {
            Map<String, Object> current = runtime.checkpointRestoreJournal(taskId, str(original, "operationId"));
            if (bool(original, "journalPresent", false) != bool(current, "journalPresent", false)
                    || (bool(original, "journalPresent", false) && !str(original, "sha256").equals(str(current, "sha256")))) throw new ApiException(409, "导出期间原撤销进度变化，请保留现场并重新读取。");
        }
    }

    private List<Map<String, Object>> operations(String taskId) {
        return store.list("operation").stream().filter(value -> taskId.equals(str(value, "taskId")) && "CHECKPOINT_RESTORE".equals(str(value, "kind"))).toList();
    }

    private void reference(String taskId, Map<String, Map<String, Object>> references, Map<String, Object> value) {
        if (value.isEmpty()) return;
        if (!taskId.equals(str(value, "taskId")) || !str(value, "manifestSha256").matches("[0-9a-f]{64}")) throw new ApiException(409, "检查点原引用不属于任务或缺少固定摘要。");
        Map<String, Object> previous = references.putIfAbsent(str(value, "id"), value);
        if (previous != null && !str(previous, "manifestSha256").equals(str(value, "manifestSha256"))) throw new ApiException(409, "同一检查点编号包含不同原摘要。");
        if (references.size() > 50_000) throw new ApiException(409, "检查点引用超过完整导出上限。");
    }

    private long object(Map<String, byte[]> files, String digest, long total) {
        String path = "checkpoints/objects/" + digest;
        if (files.containsKey(path)) return total;
        return add(files, path, runtime.checkpointBlobBytes(digest), total);
    }

    private long add(Map<String, byte[]> files, String path, byte[] bytes, long total) {
        if (files.containsKey(path)) return total;
        if (total + bytes.length > MAX_BYTES || files.size() >= 100_000) throw new ApiException(409, "检查点内容超过128 MiB或100000项完整导出上限，不能截断交付包。");
        files.put(path, bytes); return total + bytes.length;
    }

    private String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
