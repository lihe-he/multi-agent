package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 原归档字节验证完整导出；大体量合成引用只用于出口限额，不冒充执行检查点。
 */
class CheckpointExportHistoryTest {
    @TempDir Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void allHistoricalReferencesExportBeyondThePageLimitAndDeduplicateOriginalObjects() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> original = fixture.capture();
            Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(original, "id"), str(original, "manifestSha256"));
            for (int number = 0; number < 35; number++) {
                String checkpointId = id(); Map<String, Object> historical = new LinkedHashMap<>(manifest);
                historical.put("id", checkpointId); historical.put("metadata", obj("kind", "HISTORICAL_EXPORT_FIXTURE", "ordinal", number));
                byte[] bytes = mapper.writeValueAsBytes(historical);
                Path path = fixture.data.resolve("checkpoints/tasks").resolve(fixture.task).resolve(checkpointId).resolve("manifest.json");
                Files.createDirectories(path.getParent()); Files.write(path, bytes);
                Map<String, Object> record = new LinkedHashMap<>(original); record.putAll(obj("id", checkpointId, "manifestSha256", hash(bytes))); fixture.store.put("checkpoint", record);
            }
            CheckpointExportHistory history = fixture.history(); CheckpointExportHistory.Snapshot snapshot = history.snapshot(fixture.task);
            assertEquals(36, snapshot.checkpoints().size());
            assertEquals(36, snapshot.files().keySet().stream().filter(path -> path.startsWith("checkpoints/tasks/")).count());
            var required = objects(manifest.get("files")).stream().filter(value -> "STORED".equals(str(value, "state"))).map(value -> str(value, "blob")).collect(Collectors.toSet());
            required.add(str(map(manifest, "index"), "blob"));
            for (Map<String, Object> staged : objects(map(manifest, "index").get("stagedObjects"))) required.add(str(staged, "blob"));
            assertEquals(required.size(), snapshot.files().keySet().stream().filter(path -> path.startsWith("checkpoints/objects/")).count());
            for (String digest : required) assertEquals(digest, hash(snapshot.files().get("checkpoints/objects/" + digest)));
            assertEquals(snapshot.files().values().stream().mapToLong(bytes -> bytes.length).sum(), snapshot.bytes());
            history.requireCurrent(fixture.task, snapshot); fixture.sourceUnchanged();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"object-corrupt", "object-missing", "manifest-corrupt", "manifest-missing"})
    void missingOrCorruptReferencedBytesRefuseTheWholeExport(String failure) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> original = fixture.capture(), manifest = fixture.runtime.checkpointManifest(fixture.task, str(original, "id"), str(original, "manifestSha256"));
            Map<String, Object> recorded = fixture.store.get("checkpoint", str(original, "id"));
            String digest = str(objects(manifest.get("files")).getFirst(), "blob");
            Path path = failure.startsWith("object") ? fixture.data.resolve("checkpoints/objects").resolve(digest.substring(0, 2)).resolve(digest)
                : fixture.data.resolve("checkpoints/tasks").resolve(fixture.task).resolve(str(original, "id")).resolve("manifest.json");
            if (failure.endsWith("missing")) Files.delete(path); else Files.write(path, new byte[] { 42 });
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history().snapshot(fixture.task)).status());
            assertEquals(recorded, fixture.store.get("checkpoint", str(original, "id"))); fixture.sourceUnchanged();
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"checkpoint", "operation", "journal", "journal-appeared"})
    void historyOrProgressChangesAfterReadRefuseMixedEvidence(String change) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Map<String, Object> original = fixture.capture(); String operation = id();
            fixture.store.put("operation", obj("id", operation, "kind", "CHECKPOINT_RESTORE", "taskId", fixture.task, "state", "UNKNOWN"));
            if (!change.equals("journal-appeared")) fixture.journal(operation, "PREPARED");
            CheckpointExportHistory history = fixture.history(); CheckpointExportHistory.Snapshot snapshot = history.snapshot(fixture.task);
            if (change.equals("checkpoint")) { Map<String, Object> record = new LinkedHashMap<>(original); record.put("label", "later reference"); fixture.store.put("checkpoint", record); }
            else if (change.equals("operation")) { Map<String, Object> record = fixture.store.get("operation", operation); record.put("state", "ACKNOWLEDGED"); fixture.store.put("operation", record); }
            else fixture.journal(operation, "APPLYING");
            assertEquals(409, assertThrows(ApiException.class, () -> history.requireCurrent(fixture.task, snapshot)).status());
            assertEquals("UNKNOWN", snapshot.operations().getFirst().get("state")); fixture.sourceUnchanged();
        }
    }

    @Test
    void originalWorkerDigestMismatchRejectsJournalBytesInsteadOfRebindingReceipt() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.capture(); String operation = id(); fixture.journal(operation, "APPLIED");
            fixture.store.put("operation", obj("id", operation, "kind", "CHECKPOINT_RESTORE", "taskId", fixture.task, "state", "ACKNOWLEDGED", "journalSha256", "a".repeat(64)));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history().snapshot(fixture.task)).status());
            assertEquals("a".repeat(64), fixture.store.get("operation", operation).get("journalSha256"));
        }
    }

    @Test
    void missingUnknownJournalIsRecordedAsMissingWithoutInventingOriginalBytes() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.capture(); String operation = id();
            fixture.store.put("operation", obj("id", operation, "kind", "CHECKPOINT_RESTORE", "taskId", fixture.task, "state", "UNKNOWN"));
            CheckpointExportHistory.Snapshot snapshot = fixture.history().snapshot(fixture.task);
            assertEquals(false, snapshot.journals().getFirst().get("journalPresent"));
            assertEquals("UNKNOWN", snapshot.journals().getFirst().get("coreOperationState"));
            assertFalse(snapshot.files().containsKey("checkpoints/restores/" + operation + "/manifest.json"));
        }
    }

    @Test
    void verifiedLargeIndexFixtureCannotProduceAnExportTruncatedAtTheByteLimit() throws Exception {
        try (ExecutionStore store = new ExecutionStore(mapper, directory.resolve("synthetic-data").toString())) {
            String task = id(); SyntheticArchives archives = new SyntheticArchives(store.directory());
            for (int number = 0; number < 9; number++) {
                byte[] bytes = new byte[16 * 1024 * 1024]; bytes[0] = (byte) number; String digest = archives.object(bytes);
                archives.publish(store, task, List.of(), obj("present", true, "sha256", digest, "blob", digest, "stagedObjects", List.of()));
            }
            CheckpointExportHistory history = new CheckpointExportHistory(store, archives, mapper);
            assertEquals(409, assertThrows(ApiException.class, () -> history.snapshot(task)).status());
            assertEquals(9, store.taskCheckpointRecords(task, "checkpoint").size()); assertTrue(archives.objectReads.get() >= 8);
        }
    }

    @Test
    void verifiedObjectCountFixtureRefusesTheWholeExportAtTheItemLimit() throws Exception {
        try (ExecutionStore store = new ExecutionStore(mapper, directory.resolve("synthetic-data").toString())) {
            String task = id(); SyntheticArchives archives = new SyntheticArchives(store.directory()); List<Map<String, Object>> files = new ArrayList<>();
            for (int number = 0; number < 100_001; number++) {
                String digest = archives.object(ByteBuffer.allocate(4).putInt(number).array());
                files.add(obj("path", "fixture-" + number, "state", "STORED", "blob", digest, "bytes", 4));
            }
            archives.publish(store, task, files, obj("present", false, "stagedObjects", List.of()));
            assertEquals(409, assertThrows(ApiException.class, () -> new CheckpointExportHistory(store, archives, mapper).snapshot(task)).status());
            assertEquals(100_000, archives.objectReads.get()); assertEquals(1, store.taskCheckpointRecords(task, "checkpoint").size());
        }
    }

    private final class Fixture implements AutoCloseable {
        final Path base = Files.createTempDirectory(directory, "original-export-"), data = Files.createDirectory(base.resolve("data")), source = Files.createDirectory(base.resolve("source"));
        final String task = id();
        final ExecutionStore store = new ExecutionStore(mapper, data.toString());
        final WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString());
        final Path work;
        final String head;
        final byte[] index;

        Fixture() throws Exception {
            Files.writeString(source.resolve("a.txt"), "original shared bytes\n"); Files.writeString(source.resolve("b.txt"), "original shared bytes\n");
            Files.write(source.resolve("binary.dat"), new byte[] { 0, -1, 13, 10 }); Files.write(source.resolve("empty.txt"), new byte[0]);
            git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Export Fixture"); git(source, "config", "user.email", "fixture@example.invalid");
            git(source, "add", "."); git(source, "commit", "-m", "original export fixture"); head = git(source, "rev-parse", "HEAD").strip(); index = Files.readAllBytes(source.resolve(".git/index"));
            Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", "CLONE"), false, 1);
            Map<String, Object> prepared = runtime.prepareSelected(source.toString(), task, false, selection);
            work = Path.of(str(prepared, "workspace")); store.put("task", obj("id", task, "state", "PAUSED", "workspace", work.toString(), "version", prepared.get("version")));
        }

        Map<String, Object> capture() throws Exception {
            Map<String, Object> descriptor = runtime.captureCheckpoint(work.toString(), task, obj("kind", "MANUAL_EXPORT_FIXTURE")); store.put("checkpoint", descriptor); return descriptor;
        }

        CheckpointExportHistory history() { return new CheckpointExportHistory(store, runtime, mapper); }

        void journal(String operation, String state) throws Exception {
            Path path = data.resolve("checkpoints/restores").resolve(task).resolve(operation).resolve("manifest.json"); Files.createDirectories(path.getParent());
            Files.write(path, mapper.writeValueAsBytes(obj("schemaVersion", 1, "taskId", task, "operationId", operation, "state", state)));
        }

        void sourceUnchanged() throws Exception { assertEquals(head, git(source, "rev-parse", "HEAD").strip()); assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index"))); }
        @Override public void close() throws Exception { store.close(); }
    }

    private final class SyntheticArchives extends WorkspaceRuntime {
        final Map<String, byte[]> manifests = new LinkedHashMap<>(), objects = new LinkedHashMap<>();
        final AtomicInteger objectReads = new AtomicInteger();

        SyntheticArchives(Path data) { super(mapper, data.toString()); }

        String object(byte[] bytes) throws Exception { String digest = hash(bytes); objects.put(digest, bytes); return digest; }

        void publish(ExecutionStore store, String task, List<Map<String, Object>> files, Map<String, Object> index) throws Exception {
            String checkpoint = id(); byte[] bytes = mapper.writeValueAsBytes(obj("schemaVersion", 1, "id", checkpoint, "taskId", task, "files", files, "index", index));
            assertTrue(bytes.length <= 16 * 1024 * 1024); manifests.put(checkpoint, bytes);
            if (store.find("task", task) == null) store.put("task", obj("id", task, "state", "PAUSED", "fixture", "SYNTHETIC_EXPORT_BOUND_ONLY"));
            store.put("checkpoint", obj("id", checkpoint, "taskId", task, "manifestSha256", hash(bytes), "metadata", obj("kind", "SYNTHETIC_EXPORT_BOUND_FIXTURE")));
        }

        @Override public byte[] checkpointManifestBytes(String task, String checkpoint, String digest) {
            byte[] bytes = manifests.get(checkpoint); try { assertEquals(digest, hash(bytes)); assertEquals(task, mapper.readValue(bytes, Map.class).get("taskId")); }
            catch (Exception exception) { throw new IllegalStateException(exception); } return bytes;
        }

        @Override public byte[] checkpointBlobBytes(String digest) {
            objectReads.incrementAndGet(); byte[] bytes = objects.get(digest);
            try { assertEquals(digest, hash(bytes)); } catch (Exception exception) { throw new IllegalStateException(exception); } return bytes;
        }
    }

    private String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }

    private static String git(Path root, String... args) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.autocrlf=false", "-C", root.toString())); command.addAll(List.of(args));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start(); String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertEquals(0, process.waitFor(), output); return output;
    }
}
