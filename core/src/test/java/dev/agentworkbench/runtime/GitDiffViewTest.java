package dev.agentworkbench.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ExecutionStore;
import dev.agentworkbench.ManagedProcessLedger;
import dev.agentworkbench.WorkbenchController;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Git 基线、实际文件改写及原进程身份验证逐文件只读差异。
 */
class GitDiffViewTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void realHunksKeepOldAndNewCoordinatesCrlfLiteralPrefixesAndNoFinalNewline() throws Exception {
        Path root = root("coordinates"), file = root.resolve("中文 文档.txt");
        List<String> original = new ArrayList<>();
        for (int line = 1; line <= 24; line++) original.add("原文 " + line);
        Files.writeString(file, String.join("\r\n", original)); commit(root);
        List<String> changed = new ArrayList<>(original);
        changed.set(2, "---literal <script>text</script>"); changed.set(20, "+++literal addition");
        Files.writeString(file, String.join("\r\n", changed));
        WorkspaceRuntime runtime = runtime(); SourceState source = source(root);
        Map<String, Object> view = view(runtime, root, file.getFileName().toString()), preview = map(view, "preview");
        assertEquals("TEXT", str(preview, "type")); assertTrue(bool(preview, "complete", false));
        List<Map<String, Object>> hunks = objects(preview.get("hunks")), lines = lines(preview);
        assertEquals(2, hunks.size()); assertEquals(2, preview.get("additions")); assertEquals(2, preview.get("deletions"));
        Map<String, Object> third = lines.stream().filter(line -> "ADD".equals(str(line, "kind")) && Integer.valueOf(3).equals(line.get("newLine"))).findFirst().orElseThrow();
        assertEquals("---literal <script>text</script>\r", third.get("content")); assertNull(third.get("oldLine"));
        assertEquals("+++literal addition\r", lines.stream().filter(line -> "ADD".equals(str(line, "kind")) && Integer.valueOf(21).equals(line.get("newLine"))).findFirst().orElseThrow().get("content"));
        Map<String, Object> last = lines.getLast(); assertEquals(24, last.get("oldLine")); assertEquals(24, last.get("newLine")); assertTrue(bool(last, "noNewline", false));
        for (Map<String, Object> hunk : hunks) {
            List<Map<String, Object>> values = objects(hunk.get("lines"));
            assertEquals(((Number) hunk.get("oldCount")).longValue(), values.stream().filter(line -> line.get("oldLine") != null).count());
            assertEquals(((Number) hunk.get("newCount")).longValue(), values.stream().filter(line -> line.get("newLine") != null).count());
        }
        Map<String, Object> legacy = runtime.legacyDiffView(root.toString());
        assertEquals(view.get("version"), legacy.get("version")); assertTrue(str(legacy, "diff").contains("++++literal addition\r\n"));
        assertTrue(str(legacy, "diff").contains("\\ No newline at end of file")); assertFalse(bool(legacy, "truncated", true));
        assertSource(root, source); assertNoCopies();
    }

    @Test
    void addedDeletedEmptyStagedAndIgnoredFilesUseTheActualWorkingBytes() throws Exception {
        Path root = root("statuses");
        Files.writeString(root.resolve("removed.txt"), "old first\nold last"); Files.write(root.resolve("removed-empty.txt"), new byte[0]);
        Files.writeString(root.resolve("staged.txt"), "baseline\n"); Files.writeString(root.resolve(".gitignore"), "ignored.tmp\n"); commit(root);
        Files.delete(root.resolve("removed.txt")); Files.delete(root.resolve("removed-empty.txt"));
        Files.createDirectories(root.resolve("nested")); Files.writeString(root.resolve("nested/中文 new.txt"), "first\nsecond");
        Files.write(root.resolve("empty.txt"), new byte[0]); Files.writeString(root.resolve("staged.txt"), "staged bytes\n"); git(root, "add", "staged.txt");
        Files.writeString(root.resolve("staged.txt"), "actual unstaged bytes\n"); Files.writeString(root.resolve("ignored.tmp"), "ignored fixture\n");
        WorkspaceRuntime runtime = runtime(); SourceState source = source(root);
        Map<String, Object> first = view(runtime, root, "nested/中文 new.txt");
        List<Map<String, Object>> files = objects(first.get("files")); assertEquals(5, files.size());
        assertFalse(files.stream().anyMatch(file -> str(file, "path").equals("ignored.tmp")));
        assertEquals("ADDED", str(file(files, "nested/中文 new.txt"), "status"));
        Map<String, Object> added = map(first, "preview"); assertEquals("TEXT", str(added, "type"));
        assertTrue(lines(added).stream().allMatch(line -> line.get("oldLine") == null)); assertEquals(1, lines(added).getFirst().get("newLine"));
        assertTrue(bool(lines(added).getLast(), "noNewline", false));
        Map<String, Object> removed = selected(runtime, root, first, "removed.txt");
        assertEquals("DELETED", str(file(files, "removed.txt"), "status")); assertTrue(lines(removed).stream().allMatch(line -> line.get("newLine") == null));
        assertEquals(2, lines(removed).getLast().get("oldLine")); assertTrue(bool(lines(removed).getLast(), "noNewline", false));
        for (String empty : List.of("empty.txt", "removed-empty.txt")) {
            Map<String, Object> preview = selected(runtime, root, first, empty); assertEquals("EMPTY", str(preview, "type")); assertTrue(bool(preview, "complete", false));
        }
        assertEquals("actual unstaged bytes", lines(selected(runtime, root, first, "staged.txt")).stream().filter(line -> "ADD".equals(str(line, "kind"))).findFirst().orElseThrow().get("content"));
        assertSource(root, source); assertNoCopies();
    }

    @Test
    void executableOnlyChangeHasNoInventedTextRows() throws Exception {
        Path root = root("mode"), file = root.resolve("run.sh"); Files.writeString(file, "#!/bin/sh\necho baseline\n"); commit(root);
        git(root, "update-index", "--chmod=+x", "--", "run.sh");
        if (Files.getFileStore(root).supportsFileAttributeView("posix")) Files.setPosixFilePermissions(file, PosixFilePermissions.fromString("rwxr-xr-x"));
        SourceState source = source(root); WorkspaceRuntime runtime = runtime(); Map<String, Object> view = view(runtime, root, "run.sh"), preview = map(view, "preview");
        Map<String, Object> changed = objects(view.get("files")).getFirst();
        assertEquals("100644", changed.get("oldMode")); assertEquals("100755", changed.get("newMode")); assertTrue(bool(changed, "modeOnly", false));
        assertEquals("MODE_ONLY", str(preview, "type")); assertEquals(List.of(), preview.get("hunks"));
        assertTrue(str(runtime.legacyDiffView(root.toString()), "diff").contains("old mode 100644\nnew mode 100755"));
        assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"sha1", "sha256"})
    void fixedObjectFormatsRetainExactOriginalBlobBytesAndStoppedProcessIdentities(String format) throws Exception {
        Path root = root("objects", format), file = root.resolve("value.txt"); byte[] original = "原字节\r\n末行".getBytes(StandardCharsets.UTF_8);
        Files.write(file, original); commit(root); String blob = git(root, "rev-parse", "HEAD:value.txt").strip();
        Files.writeString(file, "实际修改\r\n末行"); SourceState source = source(root); WorkspaceRuntime runtime = runtime();
        List<Map<String, Object>> processes = new CopyOnWriteArrayList<>(); runtime.observeProcesses(processes::add);
        assertArrayEquals(original, runtime.readGitBlob(root, blob, original.length));
        Map<String, Object> view = view(runtime, root, "value.txt");
        assertEquals(format.equals("sha1") ? 40 : 64, str(view, "baseline").length()); assertEquals(blob, map(view, "preview").get("baseBlob"));
        assertEquals(sha(Files.readAllBytes(file)), map(view, "preview").get("sha256")); assertSource(root, source); assertNoCopies();
        assertTrue(processes.size() >= 8);
        ManagedProcessLedger.inspectProcesses(() -> {
            for (Map<String, Object> process : processes) {
                assertFalse(str(process, "processStartedAt").isBlank());
                assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) process.get("pid")).longValue(), str(process, "processStartedAt")), process.toString());
            }
            return true;
        });
        Path evidence = Files.createDirectory(Path.of(System.getProperty("jdk.net.unixdomain.tmpdir")).resolve("diff-view-evidence-" + UUID.randomUUID()));
        Files.writeString(evidence.resolve("processes.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsString(obj("format", format, "view", view,
            "originalBlobSha256", sha(original), "sourceUnchanged", true, "originalIdentitiesStopped", true, "coverage", "OBSERVED_ONLY", "processes", processes)) + "\n");
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void bothControllerInterfacesRejectARealWriteAfterComparisonInsteadOfAttachingTheLaterVersion(boolean legacy) throws Exception {
        Path root = root("controller"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "first displayed change\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                try { Files.writeString(file, "later actual change\n"); } catch (IOException exception) { throw new IllegalStateException(exception); }
                return result;
            }
        };
        String oldVersion = runtime.fingerprint(root.toString());
        try (ExecutionStore store = new ExecutionStore(mapper, data().toString())) {
            store.put("task", obj("id", "diff-task", "workspace", root.toString()));
            WorkbenchController controller = new WorkbenchController(store, null, null, runtime, null, null, mapper, null);
            ApiException conflict = assertThrows(ApiException.class, () -> { if (legacy) controller.diff("diff-task"); else controller.diffView("diff-task", "value.txt", "", ""); });
            assertEquals(409, conflict.status()); assertNotEquals(oldVersion, runtime.fingerprint(root.toString())); assertEquals("later actual change\n", Files.readString(file));
        }
        assertNoCopies();
    }

    @Test
    void actualIntermediateBytesCannotPassWhenTheSourceReturnsToItsCapturedVersion() throws Exception {
        Path root = root("aba"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root);
        String captured = "captured A\n", intermediate = "temporaryB\n"; assertEquals(captured.length(), intermediate.length()); Files.writeString(file, captured);
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override byte[] readPreviewFile(String workspace, WorkspaceContentSnapshot.File expected) {
                try {
                    Files.writeString(file, intermediate);
                    try { return super.readPreviewFile(workspace, expected); }
                    finally { Files.writeString(file, captured); }
                } catch (IOException exception) { throw new IllegalStateException(exception); }
            }
        };
        String version = runtime.fingerprint(root.toString());
        assertEquals(409, assertThrows(ApiException.class, () -> view(runtime, root, "value.txt")).status());
        assertEquals(version, runtime.fingerprint(root.toString())); assertEquals(captured, Files.readString(file)); assertNoCopies();
    }

    @Test
    void selectedFileDisappearanceKeepsAConflictAndDoesNotReturnAnOldPreview() throws Exception {
        Path root = root("disappeared"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "current\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override byte[] readPreviewFile(String workspace, WorkspaceContentSnapshot.File expected) {
                try { Files.delete(file); } catch (IOException exception) { throw new IllegalStateException(exception); }
                return super.readPreviewFile(workspace, expected);
            }
        };
        assertEquals(409, assertThrows(ApiException.class, () -> view(runtime, root, "value.txt")).status()); assertFalse(Files.exists(file)); assertNoCopies();
    }

    @Test
    void fixedHeadMovementIsDetectedEvenWhenEveryWorkingByteIsUnchanged() throws Exception {
        Path root = root("head"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root);
        Files.writeString(file, "second baseline\n"); commit(root); String prior = git(root, "rev-parse", "HEAD^").strip(); Files.writeString(file, "working bytes\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                try { git(root, "update-ref", "HEAD", prior); } catch (Exception exception) { throw new IllegalStateException(exception); }
                return result;
            }
        };
        String version = runtime.fingerprint(root.toString()); assertEquals(409, assertThrows(ApiException.class, () -> view(runtime, root, "value.txt")).status());
        assertEquals(version, runtime.fingerprint(root.toString())); assertEquals(prior, git(root, "rev-parse", "HEAD").strip()); assertNoCopies();
    }

    @Test
    void staleContentBaseUnknownPathsAndTraversalAreExplicitlyRejected() throws Exception {
        Path root = root("stale"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "current\n");
        WorkspaceRuntime runtime = runtime(); Map<String, Object> view = view(runtime, root, "value.txt"); SourceState source = source(root);
        for (String path : List.of("../value.txt", "/value.txt", "C:/value.txt", "nested//value.txt", "nested/./value.txt", "value\0.txt"))
            assertEquals(400, assertThrows(ApiException.class, () -> view(runtime, root, path)).status());
        assertEquals(404, assertThrows(ApiException.class, () -> view(runtime, root, "missing.txt")).status());
        assertEquals(400, assertThrows(ApiException.class, () -> runtime.diffView(root.toString(), "value.txt", "invalid", "")).status());
        assertEquals(409, assertThrows(ApiException.class, () -> runtime.diffView(root.toString(), "value.txt", "0".repeat(64), str(view, "baseline"))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> runtime.diffView(root.toString(), "value.txt", str(view, "version"), "0".repeat(str(view, "baseline").length()))).status());
        assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"nul", "invalid-utf8"})
    void binaryAndMalformedUtf8ReturnMetadataWithoutReplacementText(String kind) throws Exception {
        Path root = root("binary"); Files.writeString(root.resolve("value.bin"), "baseline text\n"); commit(root);
        byte[] bytes = kind.equals("nul") ? new byte[] {1, 0, 2} : new byte[] {(byte) 0xc3, 0x28}; Files.write(root.resolve("value.bin"), bytes);
        WorkspaceRuntime runtime = runtime(); SourceState source = source(root); Map<String, Object> view = view(runtime, root, "value.bin"), preview = map(view, "preview");
        assertEquals("BINARY", str(preview, "type")); assertFalse(bool(preview, "complete", true)); assertEquals(List.of(), preview.get("hunks"));
        assertEquals((long) bytes.length, preview.get("afterBytes")); assertEquals(sha(bytes), preview.get("sha256")); assertTrue(bool(view, "complete", false));
        assertTrue(bool(runtime.legacyDiffView(root.toString()), "truncated", false)); assertSource(root, source); assertNoCopies();
    }

    @Test
    void excludedPathsNeverReadOrExposeTheirContents() throws Exception {
        Path root = root("excluded"), file = root.resolve(".env"); Files.writeString(file, "excluded baseline placeholder\n"); commit(root); Files.writeString(file, "excluded current placeholder\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override byte[] readPreviewFile(String workspace, WorkspaceContentSnapshot.File expected) { throw new AssertionError("Excluded bytes must not be read into a preview"); }
            @Override byte[] readGitBlob(Path root, String objectId, int maximumBytes) { throw new AssertionError("Excluded original blob must not be previewed"); }
        };
        SourceState source = source(root); Map<String, Object> view = view(runtime, root, ".env"); assertEquals("EXCLUDED", str(map(view, "preview"), "type"));
        assertTrue(bool(objects(view.get("files")).getFirst(), "excluded", false)); assertFalse(view.toString().contains("placeholder"));
        assertFalse(str(runtime.legacyDiffView(root.toString()), "diff").contains("placeholder")); assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"before", "after", "rows", "patch"})
    void everyPreviewLimitReturnsNoPartialHunks(String kind) throws Exception {
        Path root = root("limit"), file = root.resolve("value.txt");
        String before = kind.equals("before") ? "a".repeat(GitDiffView.MAX_FILE_BYTES + 1) : kind.equals("rows") ? "old\n".repeat(3_000) : kind.equals("patch") ? "a".repeat(GitDiffView.MAX_FILE_BYTES) : "baseline\n";
        String after = kind.equals("after") ? "b".repeat(GitDiffView.MAX_FILE_BYTES + 1) : kind.equals("rows") ? "new\n".repeat(3_000) : kind.equals("patch") ? "b".repeat(GitDiffView.MAX_FILE_BYTES) : "changed\n";
        Files.writeString(file, before); commit(root); Files.writeString(file, after); WorkspaceRuntime runtime = runtime(); SourceState source = source(root);
        Map<String, Object> view = view(runtime, root, "value.txt"), preview = map(view, "preview");
        assertEquals("LIMIT", str(preview, "type")); assertEquals(kind.equals("rows") ? "ROWS" : kind.equals("patch") ? "PATCH_BYTES" : "FILE_BYTES", str(preview, "limit"));
        assertEquals(List.of(), preview.get("hunks")); assertFalse(bool(preview, "complete", true)); assertTrue(bool(view, "complete", false));
        assertSource(root, source); assertNoCopies();
    }

    @Test
    void oversizedFileListAndUnmergedIndexCannotBePresentedAsACompleteView() throws Exception {
        Path root = root("file-count");
        for (int count = 0; count <= GitDiffView.MAX_CHANGED_FILES; count++) Files.write(root.resolve("new-" + count + ".txt"), new byte[0]);
        WorkspaceRuntime runtime = runtime(); SourceState source = source(root); assertEquals(413, assertThrows(ApiException.class, () -> view(runtime, root, "")).status()); assertSource(root, source);
        Path conflict = root("conflict"), file = conflict.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(conflict);
        String blob = git(conflict, "rev-parse", "HEAD:value.txt").strip();
        gitInput(conflict, "100644 " + blob + " 1\tvalue.txt\n100644 " + blob + " 2\tvalue.txt\n100644 " + blob + " 3\tvalue.txt\n", "update-index", "--index-info");
        SourceState unmerged = source(conflict); assertEquals(409, assertThrows(ApiException.class, () -> view(runtime, conflict, "value.txt")).status()); assertSource(conflict, unmerged); assertNoCopies();
    }

    @Test
    void legacyFileOmissionsAreExplicitWhileTheOriginalDeliveryDiffRemainsAvailable() throws Exception {
        Path root = root("legacy-limit"); WorkspaceRuntime runtime = runtime(); Map<String, Object> empty = view(runtime, root, "");
        assertEquals(List.of(), empty.get("files")); assertNull(empty.get("preview")); assertTrue(bool(empty, "complete", false));
        for (int index = 0; index < 22; index++) Files.writeString(root.resolve("new-" + String.format("%02d", index) + ".txt"), "new text\n");
        SourceState source = source(root); Map<String, Object> legacy = runtime.legacyDiffView(root.toString());
        assertEquals(22, legacy.get("changedFiles")); assertEquals(20, legacy.get("displayedFiles")); assertTrue(bool(legacy, "truncated", false));
        assertTrue(str(legacy, "diff").contains("其余 2 个文件未提供文本预览"));
        assertTrue(runtime.diff(root.toString()).contains("b/new-21.txt")); assertSource(root, source); assertNoCopies();
    }

    @Test
    void twoConcurrentReadersOwnSeparateCopiesAndAThirdRequestIsBounded() throws Exception {
        Path root = root("readers"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        CountDownLatch arrived = new CountDownLatch(2), proceed = new CountDownLatch(1);
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after); arrived.countDown();
                try { assertTrue(proceed.await(20, TimeUnit.SECONDS), "Reader barrier timed out"); }
                catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new IllegalStateException(exception); }
                return result;
            }
        };
        SourceState source = source(root);
        try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
            var first = executor.submit(() -> view(runtime, root, "value.txt")); var second = executor.submit(() -> view(runtime, root, "value.txt"));
            try {
                assertTrue(arrived.await(20, TimeUnit.SECONDS)); assertEquals(429, assertThrows(ApiException.class, () -> view(runtime, root, "value.txt")).status());
                try (var directories = Files.list(data().resolve("diff-previews"))) { assertEquals(2, directories.count()); }
            } finally { proceed.countDown(); }
            assertEquals("TEXT", str(map(first.get(20, TimeUnit.SECONDS), "preview"), "type")); assertEquals("TEXT", str(map(second.get(20, TimeUnit.SECONDS), "preview"), "type"));
        }
        assertEquals("TEXT", str(map(view(runtime, root, "value.txt"), "preview"), "type")); assertSource(root, source); assertNoCopies();
    }

    @Test
    void aConflictIntroducedDuringComparisonIsRejectedDespiteUnchangedContentAndHead() throws Exception {
        Path root = root("late-conflict"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "working\n");
        String blob = git(root, "rev-parse", "HEAD:value.txt").strip();
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                try { gitInput(root, "100644 " + blob + " 1\tvalue.txt\n100644 " + blob + " 2\tvalue.txt\n100644 " + blob + " 3\tvalue.txt\n", "update-index", "--index-info"); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
                return result;
            }
        };
        String version = runtime.fingerprint(root.toString()); assertEquals(409, assertThrows(ApiException.class, () -> view(runtime, root, "value.txt")).status());
        assertEquals(version, runtime.fingerprint(root.toString())); assertNoCopies();
    }

    @Test
    void externalDiffTextconvAndBlankContextConfigurationCannotChangeThePreview() throws Exception {
        Path root = root("configuration"), file = root.resolve("value.txt");
        Files.writeString(root.resolve(".gitattributes"), "* -text\nvalue.txt diff=forbidden\n"); Files.writeString(file, "first\n\nold\n\nlast\n"); commit(root);
        Files.writeString(file, "first\n\nnew\n\nlast\n");
        git(root, "config", "diff.external", "must-never-start"); git(root, "config", "diff.forbidden.textconv", "must-never-start");
        git(root, "config", "diff.suppressBlankEmpty", "true"); git(root, "config", "diff.context", "0");
        SourceState source = source(root); Map<String, Object> preview = map(view(runtime(), root, "value.txt"), "preview");
        assertEquals("TEXT", str(preview, "type")); assertEquals(6, lines(preview).size());
        assertEquals(2, lines(preview).stream().filter(line -> "CONTEXT".equals(str(line, "kind")) && str(line, "content").isEmpty()).count());
        assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"incomplete", "unknown"})
    void incompleteNativeOutputNeverBecomesSuccessfulText(String kind) throws Exception {
        Path root = root("native-output"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                if (kind.equals("unknown")) result.put("status", "UNKNOWN");
                else result.put("stdout", str(result, "stdout").replace("+changed\n", ""));
                return result;
            }
        };
        SourceState source = source(root);
        if (kind.equals("unknown")) assertThrows(IllegalStateException.class, () -> view(runtime, root, "value.txt"));
        else { Map<String, Object> preview = map(view(runtime, root, "value.txt"), "preview"); assertEquals("UNSUPPORTED", str(preview, "type")); assertEquals(List.of(), preview.get("hunks")); }
        assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(strings = {"changed-copy", "omitted-hunk"})
    void evenWellFormedGitRowsMustReconstructTheCapturedBeforeAndAfterBytes(String kind) throws Exception {
        Path root = root("row-binding"), file = root.resolve("value.txt");
        List<String> original = new ArrayList<>(); for (int index = 1; index <= 30; index++) original.add("line " + index);
        Files.writeString(file, String.join("\n", original) + "\n"); commit(root);
        List<String> changed = new ArrayList<>(original); changed.set(2, "captured first change"); changed.set(25, "captured later change");
        Files.writeString(file, String.join("\n", changed) + "\n");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                if (kind.equals("changed-copy")) try { Files.writeString(after, Files.readString(after).replace("captured first change", "different copy bytes")); }
                catch (IOException exception) { throw new IllegalStateException(exception); }
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                if (kind.equals("omitted-hunk")) {
                    String patch = str(result, "stdout"); int first = patch.indexOf("@@ "), second = patch.indexOf("\n@@ ", first + 3);
                    assertTrue(second > first); result.put("stdout", patch.substring(0, second + 1));
                }
                return result;
            }
        };
        SourceState source = source(root); Map<String, Object> preview = map(view(runtime, root, "value.txt"), "preview");
        assertEquals("UNSUPPORTED", str(preview, "type")); assertEquals(List.of(), preview.get("hunks")); assertFalse(bool(preview, "complete", true)); assertSource(root, source); assertNoCopies();
    }

    @ParameterizedTest
    @ValueSource(booleans = {false, true})
    void cleanupPreservesReplacedFilesAndTheOriginalComparisonFailure(boolean initialFailure) throws Exception {
        Path root = root("cleanup"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        AtomicReference<Path> foreign = new AtomicReference<>();
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data().toString()) {
            @Override Map<String, Object> comparePreviewBytes(Path before, Path after) {
                Map<String, Object> result = super.comparePreviewBytes(before, after);
                try { Files.move(after, after.resolveSibling("retained-original")); Files.writeString(after, "replacement owned by someone else"); foreign.set(after); }
                catch (IOException exception) { throw new IllegalStateException(exception); }
                if (initialFailure) throw new IllegalStateException("Original comparison failure");
                return result;
            }
        };
        SourceState source = source(root); IllegalStateException failure = assertThrows(IllegalStateException.class, () -> view(runtime, root, "value.txt"));
        if (initialFailure) { assertEquals("Original comparison failure", failure.getMessage()); assertEquals(1, failure.getSuppressed().length); }
        else assertEquals("差异临时文件未完整清理。", failure.getMessage());
        assertEquals("replacement owned by someone else", Files.readString(foreign.get())); assertTrue(Files.exists(foreign.get().resolveSibling("before"))); assertSource(root, source);
    }

    @Test
    void redirectedPreviewRootCannotWriteIntoAForeignOrInternalLinkedDirectory() throws Exception {
        Path root = root("link"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        Files.createDirectories(data()); Path target = Files.createDirectory(data().resolve("foreign"));
        try { Files.createSymbolicLink(data().resolve("diff-previews"), target); }
        catch (IOException | UnsupportedOperationException exception) { Assumptions.abort("平台未授予符号链接创建权限"); }
        SourceState source = source(root); assertThrows(IllegalStateException.class, () -> view(runtime(), root, "value.txt"));
        try (var entries = Files.list(target)) { assertEquals(0, entries.count()); } assertSource(root, source);
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsJunctionCannotRedirectPreviewCopiesOrCleanup() throws Exception {
        Path root = root("junction"), file = root.resolve("value.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        Files.createDirectories(data()); Path target = Files.createDirectory(temporary.resolve("foreign-junction"));
        Process process = new ProcessBuilder("node", "-e", "require('node:fs').symlinkSync(process.argv[1], process.argv[2], 'junction')", target.toString(), data().resolve("diff-previews").toString()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); fail("Fixture junction creation timed out"); }
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        SourceState source = source(root); assertThrows(IllegalStateException.class, () -> view(runtime(), root, "value.txt"));
        try (var entries = Files.list(target)) { assertEquals(0, entries.count()); } assertSource(root, source);
    }

    @Test
    @EnabledOnOs({OS.LINUX, OS.MAC})
    void posixTabsNewlinesAndBackslashesStayLiteralAndWhitespaceOnlyNamesAreRejected() throws Exception {
        Path root = root("literal"), file = root.resolve("nested tab\tand\nline\\name.txt"); Files.writeString(file, "baseline\n"); commit(root); Files.writeString(file, "changed\n");
        SourceState source = source(root); Map<String, Object> view = view(runtime(), root, file.getFileName().toString());
        assertEquals(file.getFileName().toString(), view.get("selectedPath")); assertEquals(file.getFileName().toString(), objects(view.get("files")).getFirst().get("path")); assertSource(root, source); assertNoCopies();
        Files.writeString(root.resolve("\t\n"), "unsupported whitespace name\n"); assertEquals(400, assertThrows(ApiException.class, () -> view(runtime(), root, "")).status());
    }

    private Map<String, Object> view(WorkspaceRuntime runtime, Path root, String path) { return runtime.diffView(root.toString(), path, "", ""); }
    private Map<String, Object> selected(WorkspaceRuntime runtime, Path root, Map<String, Object> view, String path) { return map(runtime.diffView(root.toString(), path, str(view, "version"), str(view, "baseline")), "preview"); }
    private List<Map<String, Object>> lines(Map<String, Object> preview) { return objects(preview.get("hunks")).stream().flatMap(hunk -> objects(hunk.get("lines")).stream()).toList(); }
    private Map<String, Object> file(List<Map<String, Object>> files, String path) { return files.stream().filter(file -> path.equals(str(file, "path"))).findFirst().orElseThrow(); }
    private Path data() { return temporary.resolve("data"); }
    private WorkspaceRuntime runtime() { return new WorkspaceRuntime(mapper, data().toString()); }
    private Path root(String name) throws Exception { return root(name, "sha1"); }
    private Path root(String name, String format) throws Exception {
        Path root = Files.createDirectory(temporary.resolve(name + " 中文 repo")); git(root, "init", "--initial-branch=main", "--object-format=" + format);
        git(root, "config", "user.name", "Diff fixture"); git(root, "config", "user.email", "diff-fixture@example.invalid"); git(root, "config", "core.autocrlf", "false");
        Files.writeString(root.resolve(".gitattributes"), "* -text\n"); commit(root); return root;
    }
    private void commit(Path root) throws Exception { git(root, "add", "."); git(root, "commit", "-m", "fixed fixture baseline"); }
    private String git(Path root, String... arguments) throws Exception { return gitInput(root, "", arguments); }
    private String gitInput(Path root, String input, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(root.toFile()).redirectErrorStream(true).start();
        try (var stream = process.getOutputStream()) { stream.write(input.getBytes(StandardCharsets.UTF_8)); }
        AtomicReference<byte[]> bytes = new AtomicReference<>(); AtomicReference<IOException> error = new AtomicReference<>();
        Thread reader = Thread.ofVirtual().start(() -> { try { bytes.set(process.getInputStream().readAllBytes()); } catch (IOException exception) { error.set(exception); } });
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); fail("Fixture Git timed out"); }
        reader.join(3_000); assertFalse(reader.isAlive()); assertNull(error.get()); String output = new String(bytes.get(), StandardCharsets.UTF_8); assertEquals(0, process.exitValue(), output); return output;
    }
    private record SourceState(String head, byte[] index, byte[] config, Map<String, String> files) {}
    private SourceState source(Path root) throws Exception {
        Map<String, String> files = new LinkedHashMap<>();
        try (var entries = Files.walk(root)) { for (Path path : entries.filter(Files::isRegularFile).toList()) if (!path.startsWith(root.resolve(".git"))) files.put(root.relativize(path).toString(), sha(Files.readAllBytes(path))); }
        return new SourceState(git(root, "rev-parse", "HEAD"), Files.readAllBytes(root.resolve(".git/index")), Files.readAllBytes(root.resolve(".git/config")), files);
    }
    private void assertSource(Path root, SourceState before) throws Exception {
        SourceState after = source(root); assertEquals(before.head, after.head); assertArrayEquals(before.index, after.index); assertArrayEquals(before.config, after.config); assertEquals(before.files, after.files);
    }
    private String sha(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
    private void assertNoCopies() throws Exception { Path previews = data().resolve("diff-previews"); if (Files.exists(previews)) try (var entries = Files.list(previews)) { assertEquals(0, entries.count()); } }
}
