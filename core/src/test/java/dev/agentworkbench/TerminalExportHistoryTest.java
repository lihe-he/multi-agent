package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 原输出、未发布字节和 UNKNOWN 分别导出；输入动作与控制摘要不进入交付记录。
 */
class TerminalExportHistoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final ExecutionStore store = new ExecutionStore(mapper, directory.toString());
        final String taskId = id(), terminalId = id(), epoch = id();
        final Map<String, Object> source = obj("workerId", "original-output-fixture", "workerLeaseId", id(), "taskExecutionId", id());
        final TerminalOutputArchive archive = new TerminalOutputArchive(mapper, store.directory());
        final TerminalOutputArchive.Writer writer;
        final TerminalExportHistory history = new TerminalExportHistory(store, mapper);

        Fixture() throws Exception {
            store.put("task", obj("id", taskId, "state", "UNKNOWN"));
            store.put("terminalSession", obj("id", terminalId, "taskId", taskId, "epoch", epoch, "state", "UNKNOWN", "coreSessionId", store.coreSessionId(), "source", source,
                "outputIntegrity", "UNCONFIRMED", "outputEnd", 0, "controllerDigest", "PRIVATE_CONTROLLER_DIGEST"));
            store.put("terminalAction", obj("id", terminalId + ":" + id(), "taskId", taskId, "terminalId", terminalId, "dataBase64", "PRIVATE_INPUT_LEDGER_BYTES"));
            writer = archive.create(terminalId, epoch, obj("terminalId", terminalId, "taskId", taskId, "coreSessionId", store.coreSessionId(), "source", source), TerminalOutputArchive.MAX_OUTPUT_BYTES);
        }

        Map<String, Object> append(String text, boolean publish) {
            Map<String, Object> descriptor = writer.append(text.getBytes(StandardCharsets.UTF_8));
            if (publish) { Map<String, Object> record = new java.util.LinkedHashMap<>(descriptor); record.putAll(obj("id", terminalId + ":" + descriptor.get("offset"), "taskId", taskId)); store.put("terminalOutputSegment", record); }
            return descriptor;
        }

        Path folder() { return store.directory().resolve("terminal-output").resolve(terminalId); }
        @Override public void close() throws Exception { store.close(); }
    }

    @Test
    void completeOriginalBytesCanBeExportedWithoutHealingUnknownOrIncludingInputLedger() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.append("OUTPUT_MAY_ECHO_INPUT 中文\r\n", true); fixture.writer.finish(true);
            TerminalExportHistory.Snapshot snapshot = fixture.history.snapshot(fixture.taskId); fixture.history.requireCurrent(fixture.taskId, snapshot);
            assertEquals("UNKNOWN", snapshot.sessions().getFirst().get("state")); assertEquals("UNCONFIRMED", snapshot.sessions().getFirst().get("outputIntegrity"));
            Map<String, Object> observation = snapshot.outputSources().getFirst(); assertTrue(bool(map(observation, "inspection"), "complete", false)); assertTrue(bool(observation, "ownerMatchesOriginalWorker", false));
            assertFalse(bool(observation, "inputLedgerIncluded", true)); assertFalse(bool(observation, "controllerDigestsIncluded", true));
            String records = mapper.writeValueAsString(List.of(snapshot.sessions(), snapshot.outputSources()));
            assertFalse(records.contains("PRIVATE_CONTROLLER_DIGEST")); assertFalse(records.contains("PRIVATE_INPUT_LEDGER_BYTES"));
            assertEquals(4, snapshot.files().size()); assertTrue(snapshot.files().values().stream().anyMatch(bytes -> new String(bytes, StandardCharsets.UTF_8).contains("OUTPUT_MAY_ECHO_INPUT 中文")));
        }
    }

    @Test
    void unacknowledgedMetadataKeepsTheOriginalForcedSegmentAndMissingEndSeparate() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.append("original saved but unpublished tail\n", false);
            TerminalExportHistory.Snapshot snapshot = fixture.history.snapshot(fixture.taskId); Map<String, Object> observation = snapshot.outputSources().getFirst();
            assertEquals(1, objects(observation.get("unpublishedDescriptors")).size()); assertTrue(objects(observation.get("publishedReferences")).isEmpty());
            assertFalse(bool(map(observation, "inspection"), "complete", true)); assertTrue(map(map(observation, "inspection"), "end").isEmpty());
            assertEquals(3, snapshot.files().size()); assertFalse(Files.exists(fixture.folder().resolve("end.json"))); fixture.history.requireCurrent(fixture.taskId, snapshot);
        }
    }

    @Test
    void missingOwnerRetainsRawBytesAndOnlyTheOriginalRecordedUncommittedPrefix() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.append("original forced bytes\n", true); Files.move(fixture.folder().resolve("owner.json"), directory.resolve("original-owner.json"));
            byte[] prefix = "actual uncommitted prefix\n".getBytes(StandardCharsets.UTF_8);
            Map<String, Object> terminal = fixture.store.get("terminalSession", fixture.terminalId);
            terminal.put("result", obj("status", "UNKNOWN", "uncommittedOutput", obj("retainedPrefixBytes", prefix.length, "retainedPrefixBase64", Base64.getEncoder().encodeToString(prefix),
                "retainedPrefixSha256", HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(prefix)), "bytes", prefix.length + 1, "truncated", true, "prefixDurability", "ORIGINAL_NATIVE_RESULT_IF_ACKNOWLEDGED")));
            fixture.store.put("terminalSession", terminal); TerminalExportHistory.Snapshot snapshot = fixture.history.snapshot(fixture.taskId);
            Map<String, Object> observation = snapshot.outputSources().getFirst(); assertFalse(bool(observation, "ownerMatchesOriginalWorker", true)); assertFalse(objects(observation.get("publishedReferenceGaps")).isEmpty());
            assertFalse(bool(map(observation, "inspection"), "complete", true)); assertArrayEquals(prefix, snapshot.files().get("terminals/" + fixture.terminalId + "/uncommitted-prefix.bin"));
            assertTrue(bool(map(observation, "uncommittedPrefix"), "truncated", false)); assertFalse(Files.exists(fixture.folder().resolve("owner.json"))); assertFalse(Files.exists(fixture.folder().resolve("end.json")));
            fixture.history.requireCurrent(fixture.taskId, snapshot);
        }
    }

    @Test
    void corruptedOriginalSegmentIsRetainedAsObservationAndChangesRejectAnOldSnapshot() throws Exception {
        try (var fixture = new Fixture()) {
            fixture.append("original complete output\n", true); fixture.writer.finish(true);
            Path bytes;
            try (var files = Files.list(fixture.folder())) { bytes = files.filter(file -> file.getFileName().toString().endsWith(".bin")).findFirst().orElseThrow(); }
            Files.writeString(bytes, "changed original bytes\n"); TerminalExportHistory.Snapshot snapshot = fixture.history.snapshot(fixture.taskId);
            assertFalse(bool(map(snapshot.outputSources().getFirst(), "inspection"), "complete", true)); assertFalse(objects(snapshot.outputSources().getFirst().get("publishedReferenceGaps")).isEmpty());
            assertTrue(snapshot.files().values().stream().anyMatch(file -> new String(file, StandardCharsets.UTF_8).equals("changed original bytes\n")));
            fixture.history.requireCurrent(fixture.taskId, snapshot); Files.writeString(bytes, "later external change\n");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.requireCurrent(fixture.taskId, snapshot)).status());
            assertEquals("UNKNOWN", fixture.store.get("terminalSession", fixture.terminalId).get("state"));
        }
    }
}
