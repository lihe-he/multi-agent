package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Base64;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 交付包保存原终端输出、未发布片段和缺口事实，排除控制摘要及输入动作历史。
 */
final class TerminalExportHistory {
    private static final long MAX_BYTES = 128L * 1024 * 1024;
    private final ExecutionStore store;
    private final TerminalOutputArchive output;
    private final ObjectMapper mapper;

    TerminalExportHistory(ExecutionStore store, ObjectMapper mapper) {
        this.store = store; this.mapper = mapper; this.output = new TerminalOutputArchive(mapper, store.directory());
    }

    record Snapshot(List<Map<String, Object>> sessions, List<Map<String, Object>> outputSources, Map<String, byte[]> files, long bytes,
            List<Map<String, Object>> originalSessions, List<Map<String, Object>> originalPublishedReferences) { }

    Snapshot snapshot(String taskId) {
        List<Map<String, Object>> originals, published;
        synchronized (store) {
            store.get("task", taskId); originals = sessions(taskId); published = references(originals);
        }
        Map<String, byte[]> files = new LinkedHashMap<>(); List<Map<String, Object>> sources = new ArrayList<>(); long total = 0;
        for (Map<String, Object> terminal : originals) {
            String terminalId = str(terminal, "id"), epoch = str(terminal, "epoch");
            TerminalOutputArchive.Snapshot raw = output.snapshot(terminalId, epoch, MAX_BYTES - total);
            for (var entry : raw.files().entrySet()) total = add(files, "terminals/" + terminalId + "/output/" + entry.getKey(), entry.getValue(), total);
            Map<String, Object> source = new LinkedHashMap<>(raw.observation());
            List<Map<String, Object>> originalReferences = published.stream().filter(reference -> terminalId.equals(str(reference, "terminalId"))).toList();
            List<Map<String, Object>> referenceGaps = new ArrayList<>(), unpublished = new ArrayList<>();
            boolean ownerMatches = false;
            try {
                Map<String, Object> owner = output.ownership(terminalId, epoch);
                Map<String, Object> expected = obj("terminalId", terminalId, "taskId", taskId, "coreSessionId", terminal.get("coreSessionId"), "source", map(terminal, "source"));
                ownerMatches = mapper.valueToTree(expected).equals(mapper.valueToTree(map(owner, "owner")));
            } catch (RuntimeException exception) { referenceGaps.add(obj("reason", "原输出拥有者无法完整核对")); }
            if (!ownerMatches) referenceGaps.add(obj("reason", "原输出文件来源与终端原 Worker 领取尚未一致确认"));
            for (Map<String, Object> reference : originalReferences) {
                try { output.read(terminalId, epoch, segment(reference)); }
                catch (RuntimeException exception) { referenceGaps.add(obj("recordId", reference.get("id"), "offset", reference.get("offset"), "reason", "已发布引用的原字节无法完整核验，未填补游标")); }
            }
            for (Map<String, Object> descriptor : objects(map(source, "inspection").get("descriptors"))) {
                boolean found = originalReferences.stream().anyMatch(reference -> mapper.valueToTree(segment(reference)).equals(mapper.valueToTree(segment(descriptor))));
                if (!found) unpublished.add(descriptor);
            }
            Map<String, Object> result = map(terminal, "result"), prefix = map(result, "uncommittedOutput");
            if (!prefix.isEmpty()) {
                try {
                    String encoded = str(prefix, "retainedPrefixBase64");
                    if (encoded.length() > 87_384) throw new IllegalArgumentException("原内存前缀超过范围");
                    byte[] bytes = Base64.getDecoder().decode(encoded);
                    if (bytes.length > 65_536 || bytes.length != ((Number) prefix.get("retainedPrefixBytes")).longValue() || !hash(bytes).equals(str(prefix, "retainedPrefixSha256"))) throw new IllegalArgumentException("原内存前缀与回执不一致");
                    if (bytes.length > 0) total = add(files, "terminals/" + terminalId + "/uncommitted-prefix.bin", bytes, total);
                    source.put("uncommittedPrefix", obj("availableInOriginalRecord", true, "bytes", bytes.length, "sha256", hash(bytes), "durability", prefix.get("prefixDurability"),
                        "observedBytes", prefix.get("bytes"), "truncated", prefix.get("truncated"), "source", "ORIGINAL_WORKER_RESULT_STORED_IN_TERMINAL_SESSION"));
                } catch (IllegalArgumentException | NullPointerException | ClassCastException exception) { referenceGaps.add(obj("reason", "原 Worker 未提交前缀无法按原回执核验，未补造完整输出")); }
            } else source.put("uncommittedPrefix", obj("availableInOriginalRecord", false));
            source.putAll(obj("originalSource", terminal.getOrDefault("source", obj()), "coreSessionId", terminal.getOrDefault("coreSessionId", ""), "terminalState", terminal.get("state"),
                "recordedOutputIntegrity", terminal.get("outputIntegrity"), "publishedEnd", terminal.get("outputEnd"), "publishedReferences", originalReferences,
                "ownerMatchesOriginalWorker", ownerMatches, "publishedReferenceGaps", referenceGaps, "unpublishedDescriptors", unpublished,
                "inputLedgerIncluded", false, "controllerDigestsIncluded", false, "rawOutputMayEchoInput", true));
            sources.add(source);
        }
        return new Snapshot(originals.stream().map(TerminalLedger::visible).toList(), sources, files, total, originals, published);
    }

    /**
     * 组包后再核对原数据库记录和每份输出文件，外部变化不能与旧证据混合发布。
     */
    void requireCurrent(String taskId, Snapshot original) {
        synchronized (store) {
            List<Map<String, Object>> current = sessions(taskId);
            if (!original.originalSessions().equals(current) || !original.originalPublishedReferences().equals(references(current))) throw new ApiException(409, "导出期间原终端记录或游标变化，请重新读取");
        }
        Snapshot current = snapshot(taskId);
        if (!mapper.valueToTree(original.outputSources()).equals(mapper.valueToTree(current.outputSources())) || !original.files().keySet().equals(current.files().keySet())) throw new ApiException(409, "导出期间原终端文件或缺口变化，请重新读取");
        for (var file : original.files().entrySet()) if (!MessageDigest.isEqual(file.getValue(), current.files().get(file.getKey()))) throw new ApiException(409, "导出期间原终端输出字节变化，不能发布混合交付包");
    }

    private List<Map<String, Object>> sessions(String taskId) { return store.list("terminalSession").stream().filter(terminal -> taskId.equals(str(terminal, "taskId"))).toList(); }
    private List<Map<String, Object>> references(List<Map<String, Object>> sessions) {
        java.util.Set<String> identifiers = sessions.stream().map(terminal -> str(terminal, "id")).collect(java.util.stream.Collectors.toSet());
        return store.list("terminalOutputSegment").stream().filter(segment -> identifiers.contains(str(segment, "terminalId"))).toList();
    }
    private static Map<String, Object> segment(Map<String, Object> reference) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (String field : List.of("protocolVersion", "terminalId", "epoch", "offset", "length", "next", "sha256")) result.put(field, reference.get(field)); return result;
    }
    private static long add(Map<String, byte[]> files, String path, byte[] bytes, long total) {
        if (total > MAX_BYTES - bytes.length) throw new ApiException(413, "终端输出超过本次交付包范围，请另存原输出目录");
        if (files.putIfAbsent(path, bytes) != null) throw new ApiException(409, "终端交付文件编号发生冲突"); return total + bytes.length;
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
