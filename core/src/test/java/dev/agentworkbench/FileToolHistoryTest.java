package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.sql.DriverManager;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipInputStream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.ConfigurableApplicationContext;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * 真实 SQLite 与文件清单核对来源、缺失现场、稳定分页和完整任务导出。
 */
class FileToolHistoryTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private final class Fixture implements AutoCloseable {
        final Path data = Files.createDirectories(directory.resolve("data")).toRealPath();
        final Path root = Files.createDirectories(data.resolve("workspaces/root/work")).toRealPath();
        final Path child = Files.createDirectories(data.resolve("workspaces/child/work")).toRealPath();
        final ExecutionStore store = new ExecutionStore(mapper, data.toString());
        final WorkspaceRuntime runtime = spy(new WorkspaceRuntime(mapper, data.toString()));
        final WorkbenchCatalog catalog = new WorkbenchCatalog(store, runtime, new PlanValidator());
        final FileToolHistory history = new FileToolHistory(store, catalog, runtime);

        Fixture() throws Exception {
            store.put("project", obj("id", "project"));
            task("task", root); task("other", data.resolve("workspaces/other/work"));
            doAnswer(invocation -> {
                assertFalse(Thread.holdsLock(store), "Filesystem reads must not hold the SQLite store monitor");
                return invocation.callRealMethod();
            }).when(runtime).fileToolJournal(anyString());
        }

        void task(String id, Path workspace) {
            store.createTask(obj("id", id, "projectId", "project", "state", "PAUSED", "workspace", workspace.toString(), "version", "fixture-version",
                "contract", obj("scope", List.of("**")), "evidence", List.of(), "routeSnapshot", obj("engine", "API", "model", "fixture"),
                "budget", obj("maxCalls", 200, "reserveCalls", 4, "usedCalls", 0, "usedDevelopmentCalls", 0, "usedReservedCalls", 0)));
        }

        String run(String task, Path workspace, boolean delegated) {
            Map<String, Object> run = store.queueAgentRun(task, obj("id", delegated ? "subagent" : "implement", "kind", "IMPLEMENT", "role", "developer"), workspace.toString(), "fixture-version", false);
            String id = str(run, "id"); store.beginOperation(task, str(run, "stage"), "MODEL", workspace.toString(), id);
            run = store.get("agentRun", id);
            run.putAll(obj("workerId", "original-fixture-worker", "workerEpoch", 7, "executorPid", 9123, "processStartedAt", "2026-11-11T00:00:00Z",
                "taskExecutionId", "original-root", "dispatchActionId", "original-action", "workspaceWriteLeaseId", "original-write"));
            if (delegated) run.putAll(obj("subtaskId", "original-child", "definitionId", "agent-developer", "definitionVersion", 2));
            store.put("agentRun", run); return id;
        }

        String request(String task, String run, String state) {
            String id = store.beginModelRequest(task, run, false, obj("inputDigest", "original-input-digest"));
            assertFalse(id.isBlank());
            if (!state.equals("DISPATCHING")) store.finishModelRequest(id, obj("state", state));
            return id;
        }

        Path write(Path workspace, Map<String, Object> entry) throws Exception {
            Path operations = Files.createDirectories(workspace.getParent().resolve("operations"));
            Path file = operations.resolve(str(entry, "id") + ".json"); Files.write(file, mapper.writeValueAsBytes(entry)); return file;
        }

        @Override public void close() throws Exception { store.close(); }
    }

    private static String id(long sequence) { return new UUID(0L, sequence).toString(); }
    private static Map<String, Object> entry(long sequence, String tool, String request, long ordinal, String state) {
        Map<String, Object> record = obj("id", id(sequence), "tool", tool, "path", "src/中文 file.txt", "state", state, "createdAt", "2026-11-11T00:00:00Z");
        if (!request.isBlank()) record.putAll(obj("toolProtocolVersion", 2, "modelRequestId", request, "toolCallId", "original-tool-" + sequence, "toolOrdinal", ordinal, "argumentDigest", "a".repeat(64)));
        return record;
    }
    private static Map<String, Object> find(List<Map<String, Object>> records, long sequence) { return records.stream().filter(record -> id(sequence).equals(str(record, "id"))).findFirst().orElseThrow(); }

    @Test
    void bindsOnlySuccessfulSameTaskRequestsInTheOriginalRunWorkspace() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String root = fixture.run("task", fixture.root, false), child = fixture.run("task", fixture.child, true);
            String rootRequest = fixture.request("task", root, "SUCCEEDED"), childRequest = fixture.request("task", child, "SUCCEEDED");
            String pending = fixture.request("task", root, "DISPATCHING"), failed = fixture.request("task", root, "FAILED");
            String otherRun = fixture.run("other", fixture.data.resolve("workspaces/other/work"), false), otherRequest = fixture.request("other", otherRun, "SUCCEEDED");
            fixture.write(fixture.root, entry(1, "read_file", rootRequest, 1, "CONFIRMED")); fixture.write(fixture.child, entry(2, "write_file", childRequest, 2, "UNKNOWN"));
            Map<String, Object> legacy = entry(3, "delete_file", "", 0, "REJECTED"); legacy.put("source", obj("workerId", "invented-worker", "executorPid", 1)); fixture.write(fixture.root, legacy);
            fixture.write(fixture.root, entry(4, "write_file", otherRequest, 1, "CONFIRMED")); fixture.write(fixture.root, entry(5, "inspect_file", "missing-request", 1, "CONFIRMED"));
            fixture.write(fixture.root, entry(6, "read_file", childRequest, 1, "CONFIRMED")); fixture.write(fixture.root, entry(7, "read_file", pending, 1, "SENT"));
            fixture.write(fixture.root, entry(8, "read_file", failed, 1, "REJECTED")); fixture.write(fixture.root, entry(9, "run_command", rootRequest, 2, "CONFIRMED"));
            fixture.write(fixture.data.resolve("workspaces/other/work"), entry(10, "read_file", otherRequest, 1, "CONFIRMED"));
            var page = fixture.history.page("task", "", 100, ""); List<Map<String, Object>> records = objects(page.get("operations"));
            assertEquals(8, records.size()); assertEquals(6, num(page, "unattributedCount", -1)); assertEquals(2, objects(page.get("journalSources")).size());
            assertEquals("REQUEST_BOUND", str(find(records, 1), "sourceStatus")); assertEquals("REQUEST_BOUND", str(find(records, 2), "sourceStatus"));
            assertEquals("original-child", str(map(find(records, 2), "source"), "subtaskId"));
            assertEquals(9123, num(map(find(records, 1), "source"), "executorPid", 0)); assertEquals("original-write", str(map(find(records, 1), "source"), "workspaceWriteLeaseId"));
            assertEquals("LEGACY_SOURCE_UNAVAILABLE", str(find(records, 3), "sourceStatus")); assertFalse(find(records, 3).containsKey("source"));
            for (long sequence : List.of(4L, 5L, 6L, 7L, 8L)) { assertEquals("SOURCE_UNVERIFIED", str(find(records, sequence), "sourceStatus")); assertFalse(find(records, sequence).containsKey("source")); }
            assertFalse(records.toString().contains(id(10))); assertFalse(records.toString().contains("invented-worker"));
        }
    }

    @Test
    void stableLongCursorIgnoresReceiptTimeAndStateChangesAcrossReopen() throws Exception {
        String cursor, firstRequest, secondRequest; Path data;
        try (Fixture fixture = new Fixture()) {
            data = fixture.data; String run = fixture.run("task", fixture.root, false);
            firstRequest = fixture.request("task", run, "SUCCEEDED"); secondRequest = fixture.request("task", run, "SUCCEEDED");
            try (var connection = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var update = connection.prepareStatement("UPDATE entities SET rowid=? WHERE kind='modelRequest' AND id=?")) {
                update.setLong(1, 3_000_000_000L); update.setString(2, firstRequest); update.executeUpdate();
                update.setLong(1, 3_000_000_001L); update.setString(2, secondRequest); update.executeUpdate();
            }
            fixture.write(fixture.root, entry(1, "read_file", firstRequest, 1, "CONFIRMED"));
            Path earlier = fixture.write(fixture.root, entry(2, "write_file", secondRequest, 1, "SENT"));
            fixture.write(fixture.root, entry(3, "delete_file", secondRequest, 2, "UNKNOWN"));
            var latest = fixture.history.page("task", "", 1, ""); cursor = str(latest, "cursor");
            assertTrue(cursor.startsWith("3000000001:2:")); assertEquals(id(3), str(objects(latest.get("operations")).getFirst(), "id"));
            Map<String, Object> changed = entry(2, "write_file", secondRequest, 1, "CONFIRMED"); changed.put("completedAt", "2100-01-01T00:00:00Z"); fixture.write(fixture.root, changed);
            Files.setLastModifiedTime(earlier, FileTime.fromMillis(0));
            String newest = fixture.request("task", run, "SUCCEEDED"); fixture.write(fixture.root, entry(4, "inspect_file", newest, 1, "CONFIRMED"));
            List<Map<String, Object>> older = objects(fixture.history.page("task", cursor, 30, "").get("operations"));
            assertEquals(List.of(id(2), id(1)), older.stream().map(record -> str(record, "id")).toList());
            assertEquals(id(4), str(objects(fixture.history.page("task", "", 1, "").get("operations")).getFirst(), "id"));
        }
        try (var reopened = new ExecutionStore(mapper, data.toString())) {
            WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString()); FileToolHistory history = new FileToolHistory(reopened, new WorkbenchCatalog(reopened, runtime, new PlanValidator()), runtime);
            assertEquals(List.of(id(2), id(1)), objects(history.page("task", cursor, 30, "").get("operations")).stream().map(record -> str(record, "id")).toList());
            assertEquals(3_000_000_000L, ((Number) reopened.modelRequestSources("task").get(firstRequest).get("sequence")).longValue());
        }
    }

    @Test
    void originalRequestWorkspaceUsesTheSameCanonicalParentAsTheJournal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path originalAlias = directory.resolve("data/workspaces/root/work");
            String run = fixture.run("task", originalAlias, false), request = fixture.request("task", run, "SUCCEEDED");
            fixture.write(fixture.root, entry(1, "read_file", request, 1, "CONFIRMED"));
            Map<String, Object> record = fixture.history.all("task").getFirst();
            assertEquals("REQUEST_BOUND", str(record, "sourceStatus")); assertEquals(fixture.root.toString(), str(record, "journalWorkspace"));
            assertEquals(originalAlias.toString(), str(fixture.store.get("agentRun", run), "workspace"), "Reading history must preserve the original stored workspace");
        }
    }

    @Test
    void legacySharedReviewJournalFailsRatherThanMixingOtherTasks() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path review = Files.createDirectories(fixture.data.resolve("reviews/task-original")), otherReview = Files.createDirectories(fixture.data.resolve("reviews/other-original"));
            String ownRun = fixture.run("task", review, false), otherRun = fixture.run("other", otherReview, false);
            fixture.write(review, entry(101, "read_file", fixture.request("task", ownRun, "SUCCEEDED"), 1, "CONFIRMED"));
            fixture.write(otherReview, entry(201, "read_file", fixture.request("other", otherRun, "SUCCEEDED"), 1, "CONFIRMED"));
            ApiException failure = assertThrows(ApiException.class, () -> fixture.history.all("task"));
            assertEquals(409, failure.status()); assertTrue(failure.getMessage().contains("共享"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.page("other", "", 100, "")).status());
            try (var files = Files.list(review.getParent().resolve("operations"))) {
                assertEquals(2, files.count(), "Rejecting ambiguous history must preserve both original journal files");
            }
        }
    }

    @Test
    void separateActualReviewCopiesKeepHistoriesScopedAcrossTasksAndRepeatedRuns() throws Exception {
        try (Fixture fixture = new Fixture()) {
            Files.writeString(fixture.root.resolve("value.txt"), "original review fixture\n");
            git(fixture.root, "init", "--initial-branch=main"); git(fixture.root, "config", "user.name", "Review history fixture"); git(fixture.root, "config", "user.email", "review@example.invalid");
            git(fixture.root, "add", "."); git(fixture.root, "commit", "-m", "original review source");
            String version = fixture.runtime.fingerprint(fixture.root.toString());
            Path first = Path.of(fixture.runtime.reviewCopy(fixture.root.toString(), "task", version));
            Path second = Path.of(fixture.runtime.reviewCopy(fixture.root.toString(), "task", version));
            Path other = Path.of(fixture.runtime.reviewCopy(fixture.root.toString(), "other", version));
            String firstRun = fixture.run("task", first, false), secondRun = fixture.run("task", second, false), otherRun = fixture.run("other", other, false);
            fixture.write(first, entry(101, "read_file", fixture.request("task", firstRun, "SUCCEEDED"), 1, "CONFIRMED"));
            fixture.write(second, entry(102, "read_file", fixture.request("task", secondRun, "SUCCEEDED"), 1, "CONFIRMED"));
            fixture.write(other, entry(201, "read_file", fixture.request("other", otherRun, "SUCCEEDED"), 1, "CONFIRMED"));
            List<Map<String, Object>> own = fixture.history.all("task"), foreign = fixture.history.all("other");
            assertEquals(2, own.size()); assertEquals(1, foreign.size()); assertTrue(own.stream().allMatch(record -> "REQUEST_BOUND".equals(str(record, "sourceStatus"))));
            assertEquals(firstRun, str(map(find(own, 101), "source"), "agentRunId")); assertEquals(secondRun, str(map(find(own, 102), "source"), "agentRunId"));
            assertEquals(otherRun, str(map(find(foreign, 201), "source"), "agentRunId")); assertFalse(own.toString().contains(id(201))); assertFalse(foreign.toString().contains(id(101)));
            assertNotEquals(first.getParent(), second.getParent()); assertNotEquals(first.getParent(), other.getParent());
            assertEquals("work", first.getFileName().toString()); assertEquals(version, fixture.runtime.fingerprint(fixture.root.toString()));
        }
    }

    @Test
    void exportIncludesAllRootAndChildReceiptsBeyondThePageLimitAndMissingJournalSources() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.run("task", fixture.root, false); fixture.run("task", fixture.child, true);
            Path missing = fixture.data.resolve("workspaces/missing/work"); fixture.run("task", missing, false);
            for (int sequence = 1; sequence <= 125; sequence++) fixture.write(sequence <= 80 ? fixture.root : fixture.child, entry(sequence, "read_file", "", 0, "CONFIRMED"));
            var page = fixture.history.page("task", "", 1000, ""); assertEquals(100, objects(page.get("operations")).size()); assertTrue(bool(page, "hasMore", false));
            assertEquals(1, objects(page.get("journalSources")).stream().filter(journal -> !bool(journal, "journalPresent", true)).count());
            WorkbenchEngine engine = mock(WorkbenchEngine.class); doReturn("fixture-version").when(fixture.runtime).fingerprint(anyString()); doReturn("").when(fixture.runtime).exportPatch(anyString());
            WorkbenchController controller = new WorkbenchController(fixture.store, fixture.catalog, engine, fixture.runtime, new ResourceManager(), new PlanValidator(), mapper, mock(ConfigurableApplicationContext.class));
            byte[] archive = controller.export("task").getBody(); assertNotNull(archive);
            Map<String, byte[]> files = new LinkedHashMap<>();
            try (var zip = new ZipInputStream(new ByteArrayInputStream(archive), StandardCharsets.UTF_8)) { for (var file = zip.getNextEntry(); file != null; file = zip.getNextEntry()) files.put(file.getName(), zip.readAllBytes()); }
            List<Map<String, Object>> exported = mapper.readValue(files.get("file-tool-operations.json"), new TypeReference<>() {});
            List<Map<String, Object>> journals = mapper.readValue(files.get("file-tool-journal-sources.json"), new TypeReference<>() {});
            assertEquals(125, exported.size()); assertEquals(125, exported.stream().map(record -> str(record, "id")).distinct().count());
            assertEquals(3, journals.size()); assertEquals(missing.toString(), str(journals.stream().filter(journal -> !bool(journal, "journalPresent", true)).findFirst().orElseThrow(), "workspace"));
            assertEquals(45, exported.stream().filter(record -> fixture.child.toString().equals(str(record, "journalWorkspace"))).count());
            verify(engine, times(2)).requireExecutionSettled("task");
        }
    }

    @Test
    void stringRedactionHandlesJsonEscapesWithoutChangingTheOriginalJournal() throws Exception {
        try (Fixture fixture = new Fixture()) {
            String secret = "fixture-key-\"quoted\\slash";
            fixture.catalog.saveRoute(obj("name", "redaction fixture", "engine", "API", "baseUrl", "http://127.0.0.1:19000/v1", "apiKey", secret));
            Map<String, Object> original = entry(1, "write_file", "", 0, "REJECTED");
            original.put(secret, "literal top-level detail");
            original.put("error", "Rejected " + secret); original.put("fileReceipt", obj("readVersion", secret,
                "fileVersion", obj("fileKey", secret, "sha256", secret, "modifiedAt", secret, "posixPermissions", List.of(secret)), "nested", List.of(obj(secret, secret))));
            Path file = fixture.write(fixture.root, original); byte[] bytes = Files.readAllBytes(file); FileTime modified = Files.getLastModifiedTime(file);
            List<Map<String, Object>> records = fixture.history.all("task");
            assertFalse(records.toString().contains(secret)); assertEquals("Rejected [REDACTED]", str(records.getFirst(), "error"));
            assertEquals("literal top-level detail", records.getFirst().get("[REDACTED]"));
            assertEquals("[REDACTED]", str(map(map(records.getFirst(), "fileReceipt"), "fileVersion"), "fileKey"));
            assertEquals("[REDACTED]", str(map(records.getFirst(), "fileReceipt"), "readVersion"));
            assertEquals("[REDACTED]", str(map(map(records.getFirst(), "fileReceipt"), "fileVersion"), "sha256"));
            assertEquals("[REDACTED]", str(map(map(records.getFirst(), "fileReceipt"), "fileVersion"), "modifiedAt"));
            assertEquals(List.of("[REDACTED]"), map(map(records.getFirst(), "fileReceipt"), "fileVersion").get("posixPermissions"));
            assertArrayEquals(bytes, Files.readAllBytes(file)); assertEquals(modified, Files.getLastModifiedTime(file));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"a", "read", "state", "CONFIRMED", "0"})
    void shortCredentialsDoNotChangeProtocolFieldsOrGeneratedVersions(String secret) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.catalog.saveRoute(obj("name", "short credential fixture", "engine", "API", "baseUrl", "http://127.0.0.1:19000/v1", "apiKey", secret));
            String run = fixture.run("task", fixture.root, false), request = fixture.request("task", run, "SUCCEEDED"), version = "file-read-" + id(11);
            Map<String, Object> original = entry(10, "read_file", request, 1, "CONFIRMED");
            original.put("error", "Secret: " + secret); original.put("extension", obj(secret, secret));
            original.put("fileReceipt", obj("readVersion", version, "fileVersion", obj("exists", true, "sha256", "a".repeat(64), "bytes", 10, "fileKey", "NIO:(dev=abc,ino=10)", "modifiedAt", "2026-11-11T00:00:00Z")));
            Path file = fixture.write(fixture.root, original); byte[] bytes = Files.readAllBytes(file);
            Map<String, Object> record = fixture.history.all("task").getFirst();
            assertEquals(id(10), record.get("id")); assertEquals("read_file", record.get("tool")); assertEquals("CONFIRMED", record.get("state"));
            assertEquals(request, record.get("modelRequestId")); assertEquals("a".repeat(64), record.get("argumentDigest"));
            assertEquals(run, map(record, "source").get("agentRunId")); assertEquals("REQUEST_BOUND", record.get("sourceStatus"));
            assertEquals("Secret: [REDACTED]", record.get("error")); assertEquals(obj("[REDACTED]", "[REDACTED]"), record.get("extension"));
            assertEquals(version, map(record, "fileReceipt").get("readVersion"));
            assertEquals(map(map(original, "fileReceipt"), "fileVersion"), map(map(record, "fileReceipt"), "fileVersion"));
            Map<String, Object> page = fixture.history.page("task", "", 1, "CONFIRMED"); assertEquals(1, objects(page.get("operations")).size());
            assertTrue(str(page, "cursor").endsWith(":" + id(10))); assertArrayEquals(bytes, Files.readAllBytes(file));
        }
    }

    @Test
    void anOversizedCompleteJournalFailsInsteadOfReturningTheFirstRecords() throws Exception {
        try (Fixture fixture = new Fixture()) {
            for (int sequence = 1; sequence <= 135; sequence++) {
                Map<String, Object> record = entry(sequence, "read_file", "", 0, "CONFIRMED"); record.put("originalMetadata", "x".repeat(250_000));
                fixture.write(fixture.root, record);
            }
            ApiException failure = assertThrows(ApiException.class, () -> fixture.history.page("task", "", 1, ""));
            assertEquals(409, failure.status()); assertTrue(failure.getMessage().contains("32 MiB"));
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "name", "id", "oversized", "malformed" })
    void malformedJournalCannotProduceAPartialPageOrExport(String fault) throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.write(fixture.root, entry(1, "read_file", "", 0, "CONFIRMED"));
            Path folder = Files.createDirectories(fixture.root.getParent().resolve("operations"));
            Path invalid = folder.resolve(fault.equals("name") ? "foreign.json" : id(2) + ".json");
            byte[] bytes = switch (fault) {
                case "oversized" -> new byte[256_001]; case "malformed" -> "{broken".getBytes(StandardCharsets.UTF_8);
                default -> mapper.writeValueAsBytes(entry(fault.equals("id") ? 3 : 2, "read_file", "", 0, "CONFIRMED"));
            };
            Files.write(invalid, bytes);
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.page("task", "", 30, "")).status());
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.all("task")).status());
        }
    }

    @ParameterizedTest
    @ValueSource(strings = { "parent", "operations" })
    void rejectsRealForeignParentAndOperationsLinks(String target) throws Exception {
        try (Fixture fixture = new Fixture()) {
            Path outside = Files.createDirectories(directory.resolve("foreign")); fixture.write(outside.resolve("work"), entry(1, "read_file", "", 0, "CONFIRMED"));
            Path workspace;
            if (target.equals("parent")) { Path alias = fixture.data.resolve("workspaces/alias"); directoryLink(alias, outside); workspace = alias.resolve("work"); }
            else { directoryLink(fixture.root.getParent().resolve("operations"), outside.resolve("operations")); workspace = fixture.root; }
            fixture.store.editTask("task", task -> task.put("workspace", workspace.toString()), null, "");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.all("task")).status());
        }
    }

    @Test
    void rejectsOutsideScopeDuplicateIdsAndInvalidFiltersWithoutInventingSources() throws Exception {
        try (Fixture fixture = new Fixture()) {
            fixture.run("task", fixture.child, true); fixture.write(fixture.root, entry(1, "read_file", "", 0, "CONFIRMED")); fixture.write(fixture.child, entry(1, "read_file", "", 0, "CONFIRMED"));
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.all("task")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.history.page("task", "invalid", 30, "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.history.page("task", "0:99:" + id(1), 30, "")).status());
            assertEquals(400, assertThrows(ApiException.class, () -> fixture.history.page("task", "", 30, "FAKE")).status());
            fixture.store.editTask("task", task -> task.put("workspace", directory.resolve("outside/work").toString()), null, "");
            assertEquals(409, assertThrows(ApiException.class, () -> fixture.history.all("task")).status());
            assertEquals(404, assertThrows(ApiException.class, () -> fixture.history.all("nonexistent-task")).status());
        }
    }

    private void directoryLink(Path link, Path target) throws Exception {
        try { Files.createSymbolicLink(link, target); }
        catch (IOException | UnsupportedOperationException | SecurityException exception) {
            if (!System.getProperty("os.name").startsWith("Windows")) { Assumptions.assumeTrue(false, "Real directory links are unavailable"); return; }
            Process process = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            assertTrue(process.waitFor(5, TimeUnit.SECONDS), "Directory junction fixture must remain bounded");
            assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    private void git(Path workspace, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false", "-C", workspace.toString()));
        command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); fail("Actual review Git fixture must remain bounded"); }
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
    }
}
