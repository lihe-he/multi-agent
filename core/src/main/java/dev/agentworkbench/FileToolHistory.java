package dev.agentworkbench;

import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 原本机文件清单关联同任务模型请求；没有原来源的旧记录仍保留并明确标记。
 */
final class FileToolHistory {
    private static final Set<String> STATES = Set.of("PREPARED", "SENT", "CONFIRMED", "REJECTED", "UNKNOWN", "CANCELLED");
    private static final Set<String> FILE_TOOLS = Set.of("read_file", "inspect_file", "write_file", "delete_file");
    private static final String UUID_PATTERN = "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}";
    private enum Fields { OPERATION, SOURCE, RECEIPT, VERSION, JOURNAL }
    private static final Map<Fields, Set<String>> PROTOCOL_FIELDS = Map.of(
        Fields.OPERATION, Set.of("id", "tool", "path", "state", "createdAt", "completedAt", "toolProtocolVersion", "modelRequestId", "toolCallId", "toolOrdinal", "argumentDigest",
            "expectedReadVersion", "fileReceipt", "error", "errorCode", "errorType", "failedReceiptPhase", "toolReturned", "journalWorkspace", "sourceSequence", "sourceToolOrdinal", "sourceStatus", "source"),
        Fields.SOURCE, Set.of("agentRunId", "stage", "sequence", "subtaskId", "workerId", "workerEpoch", "executorPid", "processStartedAt", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId",
            "routeId", "routeVersion", "engine", "model", "definitionId", "definitionVersion"),
        Fields.RECEIPT, Set.of("path", "written", "deleted", "bytes", "created", "exists", "contentRead", "readVersion", "expectedReadVersion", "sourceOperationId", "sourceReadOperationId", "fileVersion", "beforeVersion"),
        Fields.VERSION, Set.of("exists", "sha256", "fileKey", "bytes", "modifiedAt", "executable", "posixPermissions"),
        Fields.JOURNAL, Set.of("workspace", "journalPresent", "operationCount"));
    private static final Set<String> POSIX_PERMISSIONS = java.util.Arrays.stream(java.nio.file.attribute.PosixFilePermission.values()).map(Enum::name).collect(java.util.stream.Collectors.toUnmodifiableSet());
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final WorkspaceRuntime runtime;

    FileToolHistory(ExecutionStore store, WorkbenchCatalog catalog, WorkspaceRuntime runtime) {
        this.store = store; this.catalog = catalog; this.runtime = runtime;
    }

    Map<String, Object> page(String taskId, String before, int requestedLimit, String state) {
        if (!state.isBlank() && !STATES.contains(state)) throw new ApiException(400, "无效文件工具状态。");
        Map<String, Object> boundary = before.isBlank() ? null : cursor(before);
        Snapshot snapshot = snapshot(taskId);
        List<Map<String, Object>> all = snapshot.operations;
        int limit = Math.max(1, Math.min(100, requestedLimit));
        List<Map<String, Object>> selected = all.stream().filter(record -> state.isBlank() || state.equals(str(record, "state")))
            .filter(record -> boundary == null || order().compare(record, boundary) < 0).sorted(order().reversed()).toList();
        List<Map<String, Object>> records = selected.subList(0, Math.min(limit, selected.size()));
        String next = records.isEmpty() ? before : encode(records.getLast());
        return obj("operations", records, "cursor", next, "hasMore", selected.size() > records.size(), "total", all.size(),
            "unattributedCount", all.stream().filter(record -> !"REQUEST_BOUND".equals(str(record, "sourceStatus"))).count(), "journalSources", snapshot.journals,
            "sourceScope", "MODEL_REQUEST_AND_LOCAL_TOOL_JOURNAL");
    }

    /**
     * 文件读取在存储锁外执行；导出保留全部根与副执行清单，不使用界面分页上限。
     */
    List<Map<String, Object>> all(String taskId) { return snapshot(taskId).operations; }

    Snapshot snapshot(String taskId) {
        Map<String, Object> task = store.get("task", taskId);
        List<Map<String, Object>> runs = store.list("agentRun").stream().filter(run -> taskId.equals(str(run, "taskId"))).toList();
        Set<String> workspaces = new LinkedHashSet<>();
        if (!str(task, "workspace").isBlank()) workspaces.add(str(task, "workspace"));
        for (Map<String, Object> run : runs) if (!str(run, "workspace").isBlank()) workspaces.add(str(run, "workspace"));
        List<Map<String, Object>> records = new ArrayList<>(), journals = new ArrayList<>();
        Set<String> directories = new LinkedHashSet<>(), ids = new LinkedHashSet<>();
        long totalBytes = 0;
        for (String workspace : workspaces) {
            Map<String, Object> journal;
            try { journal = runtime.fileToolJournal(workspace); }
            catch (IllegalArgumentException exception) { throw new ApiException(409, exception.getMessage()); }
            catch (IllegalStateException exception) { throw new ApiException(409, "原文件工具清单无法完整读取，请核对现场和文件权限。"); }
            String actual = str(journal, "workspace");
            if (!directories.add(actual)) continue;
            totalBytes += longValue(journal, "journalBytes");
            if (totalBytes > 32L * 1024 * 1024) throw new ApiException(409, "任务文件工具清单超过32 MiB完整读取上限，不能截断导出。");
            journals.add(obj("workspace", actual, "journalPresent", bool(journal, "journalPresent", false), "operationCount", objects(journal.get("operations")).size()));
            for (Map<String, Object> entry : objects(journal.get("operations"))) {
                if (!FILE_TOOLS.contains(str(entry, "tool"))) continue;
                if (!ids.add(str(entry, "id"))) throw new ApiException(409, "原文件工具编号出现在不同清单中，需要核对。");
                if (records.size() >= 50_000) throw new ApiException(409, "任务文件工具清单超过完整读取上限，不能截断导出。");
                Map<String, Object> record = new LinkedHashMap<>(entry); record.put("journalWorkspace", actual); records.add(record);
            }
        }
        Map<String, Map<String, Object>> sources = store.modelRequestSources(taskId);
        for (Map<String, Object> record : records) {
            Map<String, Object> source = sources.get(str(record, "modelRequestId"));
            Map<String, Object> request = source == null ? obj() : map(source, "request");
            Map<String, Object> run = runs.stream().filter(value -> str(value, "id").equals(str(request, "agentRunId"))).findFirst().orElse(obj());
            boolean matches = source != null && "SUCCEEDED".equals(str(request, "state")) && sameWorkspace(str(run, "workspace"), str(record, "journalWorkspace"));
            long ordinal = longValue(record, "toolOrdinal");
            record.put("sourceSequence", matches ? source.get("sequence") : 0L);
            record.put("sourceToolOrdinal", ordinal >= 1 && ordinal <= 16 ? ordinal : 0L);
            record.put("sourceStatus", matches ? "REQUEST_BOUND" : str(record, "modelRequestId").isBlank() ? "LEGACY_SOURCE_UNAVAILABLE" : "SOURCE_UNVERIFIED");
            record.remove("source");
            if (matches) {
                Map<String, Object> facts = new LinkedHashMap<>();
                for (String field : List.of("agentRunId", "stage", "sequence", "subtaskId", "workerId", "workerEpoch", "executorPid", "processStartedAt", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "routeId", "routeVersion", "engine", "model", "definitionId", "definitionVersion")) {
                    if (request.containsKey(field)) facts.put(field, request.get(field));
                }
                record.put("source", facts);
            }
        }
        for (Map<String, Object> record : records) redact(record, Fields.OPERATION);
        for (Map<String, Object> journal : journals) redact(journal, Fields.JOURNAL);
        return new Snapshot(records.stream().sorted(order().reversed()).toList(), journals);
    }

    /**
     * 先在实际字符串上脱敏，包含引号或反斜杠的凭据也不能因 JSON 转义而漏出。
     */
    private void redact(Map<String, Object> value, Fields fields) {
        Map<String, Object> redacted = redactFields(value, fields);
        value.clear(); value.putAll(redacted);
    }

    /**
     * 固定协议键和格式有效的版本事实保留；自由文本、未知扩展及无效版本仍须脱敏。
     */
    private Map<String, Object> redactFields(Map<?, ?> value, Fields fields) {
        Map<String, Object> result = new LinkedHashMap<>();
        value.forEach((key, item) -> {
            String field = key.toString();
            boolean known = PROTOCOL_FIELDS.get(fields).contains(field);
            result.put(known ? field : catalog.redact(field), known ? redactField(fields, field, item) : redactValue(item));
        });
        return result;
    }

    private Object redactField(Fields fields, String field, Object value) {
        Fields nested = fields == Fields.OPERATION && field.equals("source") ? Fields.SOURCE
            : fields == Fields.OPERATION && field.equals("fileReceipt") ? Fields.RECEIPT
            : fields == Fields.RECEIPT && Set.of("fileVersion", "beforeVersion").contains(field) ? Fields.VERSION : null;
        if (nested != null && value instanceof Map<?, ?> entries) return redactFields(entries, nested);
        if (fields == Fields.VERSION && field.equals("posixPermissions") && value instanceof List<?> permissions) {
            return permissions.stream().map(item -> item instanceof String text && POSIX_PERMISSIONS.contains(text) ? item : redactValue(item)).toList();
        }
        if (value instanceof String text && protocolText(field, text)) return text;
        return redactValue(value);
    }

    private static boolean protocolText(String field, String text) {
        return switch (field) {
            case "id", "modelRequestId", "agentRunId", "subtaskId", "workerId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "routeId", "sourceOperationId", "sourceReadOperationId" -> text.matches(UUID_PATTERN);
            case "tool" -> FILE_TOOLS.contains(text);
            case "state" -> STATES.contains(text);
            case "sourceStatus" -> Set.of("REQUEST_BOUND", "LEGACY_SOURCE_UNAVAILABLE", "SOURCE_UNVERIFIED").contains(text);
            case "argumentDigest", "sha256" -> text.matches("[0-9a-f]{64}");
            case "readVersion", "expectedReadVersion" -> Set.of("", "ABSENT", "INVALID").contains(text) || text.matches("file-read-" + UUID_PATTERN);
            case "fileKey" -> text.matches("WINDOWS:[0-9a-f]{1,16}:[0-9a-f]{32}") || text.matches("NIO:\\(dev=[0-9a-f]+,ino=[0-9]+\\)");
            case "createdAt", "completedAt", "processStartedAt", "modifiedAt" -> validTime(text);
            case "engine" -> Set.of("API", "CODEX", "CLAUDE").contains(text);
            case "errorCode" -> text.equals("FILE_VERSION_CONFLICT");
            case "failedReceiptPhase" -> Set.of("PREPARE_METADATA", "SEND_METADATA", "TOOL_EXECUTION", "CONFIRM_METADATA").contains(text);
            default -> false;
        };
    }

    private static boolean validTime(String text) {
        try { java.time.Instant.parse(text); return true; }
        catch (java.time.format.DateTimeParseException exception) { return false; }
    }

    private Object redactValue(Object value) {
        if (value instanceof String text) return catalog.redact(text);
        if (value instanceof Map<?, ?> entries) {
            Map<String, Object> result = new LinkedHashMap<>();
            entries.forEach((field, item) -> result.put(catalog.redact(field.toString()), redactValue(item)));
            return result;
        }
        if (value instanceof List<?> items) return items.stream().map(this::redactValue).toList();
        return value;
    }

    private static boolean sameWorkspace(String stored, String actual) {
        if (stored.isBlank()) return false;
        try {
            Path original = Path.of(stored).toAbsolutePath().normalize(), journal = Path.of(actual).toAbsolutePath().normalize();
            return original.equals(journal) || original.getParent() != null && original.getParent().toRealPath().resolve(original.getFileName()).equals(journal);
        }
        catch (java.io.IOException | RuntimeException exception) { return false; }
    }

    private static Comparator<Map<String, Object>> order() {
        return Comparator.<Map<String, Object>>comparingLong(record -> longValue(record, "sourceSequence"))
            .thenComparingLong(record -> longValue(record, "sourceToolOrdinal")).thenComparing(record -> str(record, "id"));
    }
    private static String encode(Map<String, Object> record) { return longValue(record, "sourceSequence") + ":" + longValue(record, "sourceToolOrdinal") + ":" + str(record, "id"); }
    private static long longValue(Map<String, Object> record, String field) {
        try { return Long.parseLong(str(record, field)); }
        catch (NumberFormatException exception) { return 0L; }
    }
    private static Map<String, Object> cursor(String text) {
        if (!text.matches("[0-9]{1,19}:[0-9]{1,2}:[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new ApiException(400, "无效文件工具游标。");
        String[] values = text.split(":");
        try {
            long ordinal = Long.parseLong(values[1]);
            if (ordinal > 16) throw new ApiException(400, "无效文件工具游标。");
            return obj("sourceSequence", Long.parseLong(values[0]), "sourceToolOrdinal", ordinal, "id", values[2]);
        }
        catch (NumberFormatException exception) { throw new ApiException(400, "无效文件工具游标。"); }
    }

    record Snapshot(List<Map<String, Object>> operations, List<Map<String, Object>> journals) { }
}
