package dev.agentworkbench.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.CheckpointFailureEvidence;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(CheckpointFailureEvidence.class)
class WorkspaceCheckpointArchiveTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path temporary;

    @Test
    void actualHundredsOfObjectsCrossBatchBoundariesWithoutChangingOriginalBytesOrIndex() throws Exception {
        Fixture fixture = fixture("CLONE");
        for (int index = 0; index < 260; index++) Files.writeString(fixture.work.resolve("unique-" + index + ".txt"), "original object " + index + "\n" + "x".repeat(8192));
        fixture.git("add", "."); byte[] index = fixture.index();
        java.util.concurrent.atomic.AtomicInteger started = new java.util.concurrent.atomic.AtomicInteger();
        fixture.runtime.observeProcesses(value -> { if (((Number) value.get("observedFromPid")).longValue() == ProcessHandle.current().pid()) started.incrementAndGet(); });
        Map<String, Object> descriptor = fixture.capture(); int processStarts = started.get();
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(descriptor, "id"), str(descriptor, "manifestSha256"));
        assertEquals("READY", descriptor.get("status")); assertEquals(264, objects(manifest.get("files")).size());
        assertEquals(264, objects(map(manifest, "index").get("stagedObjects")).size()); assertTrue(processStarts < 80, "actual top-level Git starts: " + processStarts);
        for (Map<String, Object> original : objects(manifest.get("files")))
            assertArrayEquals(Files.readAllBytes(fixture.work.resolve(str(original, "path"))), fixture.runtime.checkpointBlobBytes(str(original, "blob")));
        assertArrayEquals(index, fixture.index()); assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void legacyReadyCloneUsesTheActualGitDirectoryAcrossWindowsPathSeparators() throws Exception {
        Fixture fixture = fixture("LEGACY"); Map<String, Object> descriptor = fixture.capture();
        assertEquals("READY", descriptor.get("status"));
        assertEquals(fixture.work.toRealPath().toString(), descriptor.get("workspace"));
        assertEquals(fixture.head, descriptor.get("head"));
    }

    @Test
    void legacyPreparationWithUnresolvedDataPathPublishesAndRecoversTheCanonicalOwnedWorkspace() throws Exception {
        Fixture fixture = fixture("LEGACY_ALIAS"); byte[] index = fixture.index();
        assertEquals(fixture.work.toRealPath().toString(), fixture.prepared.get("workspace"));
        Map<String, Object> descriptor = fixture.capture();
        assertEquals("READY", descriptor.get("status")); assertEquals(fixture.work.toRealPath().toString(), descriptor.get("workspace"));
        Map<String, Object> recovered = fixture.runtime.prepare(fixture.source.toString(), fixture.task, false);
        assertEquals(true, recovered.get("recoveredPreparation")); assertEquals(descriptor.get("workspace"), recovered.get("workspace"));
        Path oldPath = temporary.resolve(temporary.toRealPath().relativize(fixture.work.toRealPath()));
        assertTrue(fixture.runtime.checkpointWorkspaceMatches(str(descriptor, "workspace"), oldPath.toString()));
        assertFalse(fixture.runtime.checkpointWorkspaceMatches(str(descriptor, "workspace"), fixture.source.toString()));
        assertEquals(descriptor.get("workspace"), fixture.runtime.captureCheckpoint(oldPath.toString(), fixture.task, obj("kind", "OLD_TASK_PATH")).get("workspace"));
        assertArrayEquals(index, fixture.index()); assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void aStageLikeUntrackedNameKeepsItsWholeLiteralPathAndBytes() throws Exception {
        Fixture fixture = fixture("CLONE");
        String name = "100644 " + fixture.head + " 0" + (System.getProperty("os.name", "").startsWith("Windows") ? " space.txt" : "\tpretended.txt");
        byte[] content = "literal untracked filename\n".getBytes(java.nio.charset.StandardCharsets.UTF_8), index = fixture.index();
        Files.write(fixture.work.resolve(name), content);
        Map<String, Object> descriptor = fixture.capture(), manifest = fixture.runtime.checkpointManifest(fixture.task, str(descriptor, "id"), str(descriptor, "manifestSha256"));
        assertEquals("READY", descriptor.get("status")); assertEquals(5, objects(manifest.get("files")).size());
        Map<String, Object> original = file(manifest, name); assertEquals("", original.get("indexEntry"));
        assertArrayEquals(content, fixture.runtime.checkpointBlobBytes(str(original, "blob"))); assertArrayEquals(index, fixture.index());
        assertEquals(fixture.runtime.fingerprint(fixture.work.toString()), descriptor.get("version"));
    }

    @ParameterizedTest
    @ValueSource(strings = { "BACKLINK", "COMMON", "LOCK", "HEAD" })
    void changedWorktreeAdministrativeOwnershipCannotPublishANewCapture(String changed) throws Exception {
        Fixture fixture = fixture("WORKTREE"); fixture.capture(); byte[] index = fixture.index();
        Path directory = Path.of(str(map(fixture.prepared, "workspaceOwnership"), "gitDirectory"));
        Path metadata = directory.resolve(switch (changed) { case "BACKLINK" -> "gitdir"; case "COMMON" -> "commondir"; case "LOCK" -> "locked"; case "HEAD" -> "HEAD"; default -> throw new AssertionError(); });
        String replacement = switch (changed) {
            case "BACKLINK" -> fixture.source.resolve(".git").toString() + "\n";
            case "COMMON" -> fixture("CLONE").source.resolve(".git").toString() + "\n";
            case "LOCK" -> "foreign worktree owner\n";
            case "HEAD" -> "ref: " + fixture.sourceGit("symbolic-ref", "HEAD").strip() + "\n";
            default -> throw new AssertionError();
        };
        Files.writeString(metadata, replacement); byte[] replaced = Files.readAllBytes(metadata);
        Path manifests = fixture.data.resolve("checkpoints/tasks").resolve(fixture.task); long count;
        try (var files = Files.list(manifests)) { count = files.count(); }
        assertEquals(409, assertThrows(ApiException.class, fixture::capture).status());
        try (var files = Files.list(manifests)) { assertEquals(count, files.count()); }
        assertArrayEquals(replaced, Files.readAllBytes(metadata)); assertArrayEquals(index, Files.readAllBytes(directory.resolve("index")));
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt"))); assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void originalRequestedOwnershipRejectsARewrittenSelectionBeforePublishingAnyManifest() throws Exception {
        Fixture fixture = fixture("CLONE"); Map<String, Object> ownership = map(fixture.prepared, "workspaceOwnership");
        assertEquals("READY", fixture.runtime.captureCheckpoint(fixture.work.toString(), fixture.task, obj("kind", "MANUAL"), ownership).get("status"));
        Path selection = fixture.work.getParent().resolve("selection.json"); ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> saved = mapper.readValue(Files.readAllBytes(selection), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        map(map(saved, "facts"), "workspaceOwnership").put("sourcePath", fixture.source.resolve("foreign source").toString());
        byte[] rewritten = mapper.writeValueAsBytes(saved), originalIndex = fixture.index(); Files.write(selection, rewritten);
        Path manifests = fixture.data.resolve("checkpoints/tasks").resolve(fixture.task);
        long count;
        try (var files = Files.list(manifests)) { count = files.count(); }
        assertEquals(409, assertThrows(ApiException.class,
            () -> fixture.runtime.captureCheckpoint(fixture.work.toString(), fixture.task, obj("kind", "MANUAL"), ownership)).status());
        try (var files = Files.list(manifests)) { assertEquals(count, files.count()); }
        assertArrayEquals(rewritten, Files.readAllBytes(selection)); assertArrayEquals(originalIndex, fixture.index());
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt"))); assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void sha256GitObjectsKeepOriginalBinaryAndIndexWhenUndoing() throws Exception {
        Fixture fixture = fixture("SHA256"); byte[] originalIndex = fixture.index(); Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "sha256 staged\n"); fixture.git("add", "a.txt");
        Map<String, Object> source = fixture.capture(); fixture.restore(fixture.preview(target, source));
        assertArrayEquals(originalIndex, fixture.index()); assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(target, "id"), str(target, "manifestSha256"));
        assertEquals("sha256", manifest.get("objectFormat")); assertEquals(64, str(manifest, "head").length());
        assertArrayEquals(new byte[] { 0, 1, -1, 13, 10 }, fixture.runtime.checkpointBlobBytes(str(file(manifest, "binary.dat"), "blob")));
    }

    @Test
    void originalBinaryAndEmptyBytesRemainReadableAfterReopeningTheArchive() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> saved = fixture.capture();
        WorkspaceRuntime reopened = new WorkspaceRuntime(new ObjectMapper(), fixture.data.toString());
        Map<String, Object> manifest = reopened.checkpointManifest(fixture.task, str(saved, "id"), str(saved, "manifestSha256"));
        assertEquals("READY", saved.get("status"));
        Map<String, Object> binary = file(manifest, "binary.dat"), empty = file(manifest, "empty.txt");
        assertArrayEquals(new byte[] { 0, 1, -1, 13, 10 }, reopened.checkpointBlobBytes(str(binary, "blob")));
        assertArrayEquals(new byte[0], reopened.checkpointBlobBytes(str(empty, "blob")));
        assertEquals(saved.get("version"), manifest.get("version"));
        assertEquals(fixture.head, manifest.get("head"));
        assertEquals("MANUAL", map(manifest, "metadata").get("kind"));
    }

    @Test
    void undoRestoresOriginalIndexBinaryDeletionAndAdditionsWhileKeepingUnrelatedLaterEdits() throws Exception {
        Fixture fixture = fixture("CLONE");
        byte[] originalIndex = fixture.index();
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "staged\r\n"); fixture.git("add", "a.txt");
        Files.writeString(fixture.work.resolve("a.txt"), "working without newline");
        Files.delete(fixture.work.resolve("binary.dat"));
        Path added = fixture.work.resolve("new/note.txt"); Files.createDirectory(added.getParent()); Files.writeString(added, "new");
        Map<String, Object> source = fixture.capture();
        Files.writeString(fixture.work.resolve("b.txt"), "user edits after the saved boundary\n");
        Map<String, Object> preview = fixture.preview(target, source);
        assertEquals(true, preview.get("canRestore"));
        Map<String, Object> result = fixture.restore(preview);
        assertEquals("ACKNOWLEDGED", result.get("state"));
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertEquals("user edits after the saved boundary\n", Files.readString(fixture.work.resolve("b.txt")));
        assertArrayEquals(new byte[] { 0, 1, -1, 13, 10 }, Files.readAllBytes(fixture.work.resolve("binary.dat")));
        assertFalse(Files.exists(added));
        assertArrayEquals(originalIndex, fixture.index());
        assertEquals(fixture.head, fixture.git("rev-parse", "HEAD").strip());
        assertEquals("", fixture.sourceGit("status", "--porcelain"));
        assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void changedAffectedUserFileBlocksTheWholePreviewWithoutTouchingOtherFiles() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent a\n");
        Files.writeString(fixture.work.resolve("b.txt"), "agent b\n");
        Map<String, Object> source = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "new user a\n");
        byte[] index = fixture.index();
        Map<String, Object> preview = fixture.preview(target, source);
        assertEquals(false, preview.get("canRestore"));
        assertTrue(objects(preview.get("conflicts")).stream().anyMatch(value -> "a.txt".equals(value.get("path"))));
        assertThrows(ApiException.class, () -> fixture.restore(preview));
        assertEquals("new user a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertEquals("agent b\n", Files.readString(fixture.work.resolve("b.txt")));
        assertArrayEquals(index, fixture.index());
    }

    @Test
    void unrelatedLaterStagingIsPreservedWhenTheUndoDoesNotChangeIndex() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent change\n");
        Map<String, Object> source = fixture.capture();
        Files.writeString(fixture.work.resolve("b.txt"), "user staged\n"); fixture.git("add", "b.txt");
        byte[] staged = fixture.index();
        Map<String, Object> preview = fixture.preview(target, source);
        assertEquals(false, preview.get("indexChanges"));
        assertEquals(true, preview.get("canRestore"));
        fixture.restore(preview);
        assertArrayEquals(staged, fixture.index());
        assertEquals("user staged\n", fixture.git("show", ":b.txt"));
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
    }

    @Test
    void laterStagingBlocksAnIndexRestoringUndoAndRetainsTheFullCurrentIndex() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent staged\n"); fixture.git("add", "a.txt");
        Map<String, Object> source = fixture.capture();
        Files.writeString(fixture.work.resolve("b.txt"), "later staged\n"); fixture.git("add", "b.txt");
        byte[] index = fixture.index();
        Map<String, Object> preview = fixture.preview(target, source);
        assertEquals(false, preview.get("canRestore"));
        assertThrows(ApiException.class, () -> fixture.restore(preview));
        assertArrayEquals(index, fixture.index());
        assertEquals("later staged\n", fixture.git("show", ":b.txt"));
        assertEquals("agent staged\n", Files.readString(fixture.work.resolve("a.txt")));
    }

    @Test
    void sameLengthEditWithRestoredMtimeAfterPreviewStillRejectsBeforeAnyUndoWrites() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent\n");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        FileTime time = Files.getLastModifiedTime(fixture.work.resolve("a.txt"));
        Files.writeString(fixture.work.resolve("a.txt"), "later\n"); Files.setLastModifiedTime(fixture.work.resolve("a.txt"), time);
        ApiException failure = assertThrows(ApiException.class, () -> fixture.restore(preview));
        assertEquals(409, failure.status());
        assertEquals("later\n", Files.readString(fixture.work.resolve("a.txt")));
        assertEquals("original b\n", Files.readString(fixture.work.resolve("b.txt")));
    }

    @Test
    void headMovementAfterPreviewCannotBeUndoneByMovingTheBranch() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent\n");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        fixture.git("add", "a.txt"); fixture.git("commit", "-m", "user commit");
        String newHead = fixture.git("rev-parse", "HEAD").strip();
        assertThrows(ApiException.class, () -> fixture.restore(preview));
        assertEquals(newHead, fixture.git("rev-parse", "HEAD").strip());
        assertEquals("agent\n", Files.readString(fixture.work.resolve("a.txt")));
    }

    @Test
    void corruptContentOrManifestCannotProduceARecoverableSnapshot() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> saved = fixture.capture();
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), str(saved, "manifestSha256"));
        String digest = str(file(manifest, "a.txt"), "blob");
        Path object = fixture.data.resolve("checkpoints/objects").resolve(digest.substring(0, 2)).resolve(digest);
        Files.write(object, "corrupted\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        assertThrows(ApiException.class, () -> fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), str(saved, "manifestSha256")));
        assertThrows(ApiException.class, fixture::capture);
        assertThrows(ApiException.class, () -> fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), "0".repeat(64)));
    }

    @Test
    void repeatedBlobReferencesStillCheckEachDeclaredFileLength() throws Exception {
        Fixture fixture = fixture("CLONE"); byte[] index = fixture.index(); Map<String, Object> saved = fixture.capture();
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), str(saved, "manifestSha256"));
        Map<String, Object> original = file(manifest, "a.txt"), second = file(manifest, "b.txt");
        second.putAll(obj("blob", original.get("blob"), "sha256", original.get("sha256"), "bytes", ((Number) original.get("bytes")).longValue() + 1));
        String changedDigest = rewriteFixtureManifest(fixture, str(saved, "id"), manifest);
        assertEquals(409, assertThrows(ApiException.class, () -> fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), changedDigest)).status());
        assertArrayEquals(index, fixture.index()); assertEquals("original b\n", Files.readString(fixture.work.resolve("b.txt")));
    }

    @Test
    void reusedIndexBlobCannotBypassTheStagedObjectReadLimit() throws Exception {
        Fixture fixture = fixture("CLONE"); byte[] originalIndex = fixture.index(); Map<String, Object> saved = fixture.capture();
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), str(saved, "manifestSha256"));
        byte[] content = new byte[256_001]; String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        Path object = fixture.data.resolve("checkpoints/objects").resolve(digest.substring(0, 2)).resolve(digest);
        Files.createDirectories(object.getParent()); Files.write(object, content);
        MessageDigest gitHash = MessageDigest.getInstance("SHA-1");
        gitHash.update(("blob " + content.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        String objectId = HexFormat.of().formatHex(gitHash.digest(content));
        map(manifest, "index").putAll(obj("blob", digest, "sha256", digest, "stagedObjects", List.of(obj("blob", digest, "bytes", content.length, "objectId", objectId))));
        String changedDigest = rewriteFixtureManifest(fixture, str(saved, "id"), manifest);
        assertEquals(409, assertThrows(ApiException.class, () -> fixture.runtime.checkpointManifest(fixture.task, str(saved, "id"), changedDigest)).status());
        assertArrayEquals(originalIndex, fixture.index()); assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void changedExcludedAndOversizedContentsBlockRestoreInsteadOfBeingSilentlySkipped() throws Exception {
        Fixture fixture = fixture("CLONE");
        Files.writeString(fixture.work.resolve(".env"), "SECRET=before\n");
        Files.write(fixture.work.resolve("large.dat"), new byte[256_001]);
        Map<String, Object> target = fixture.capture();
        assertEquals("PARTIAL", target.get("status"));
        Files.writeString(fixture.work.resolve(".env"), "SECRET=after\n");
        byte[] large = new byte[256_001]; large[0] = 42; Files.write(fixture.work.resolve("large.dat"), large);
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        assertEquals(false, preview.get("canRestore"));
        assertTrue(objects(preview.get("conflicts")).stream().anyMatch(value -> ".env".equals(value.get("path"))));
        assertTrue(objects(preview.get("conflicts")).stream().anyMatch(value -> "large.dat".equals(value.get("path"))));
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(target, "id"), str(target, "manifestSha256"));
        assertFalse(file(manifest, ".env").containsKey("blob"));
        assertFalse(file(manifest, "large.dat").containsKey("blob"));
        assertEquals("SECRET=after\n", Files.readString(fixture.work.resolve(".env")));
    }

    @Test
    void actualLockedWorktreeUndoRetainsTheSourceHeadIndexAndSharedConfiguration() throws Exception {
        Fixture fixture = fixture("WORKTREE");
        Path sourceIndex = fixture.source.resolve(".git/index"), sourceConfig = fixture.source.resolve(".git/config");
        byte[] originalIndex = Files.readAllBytes(sourceIndex), config = Files.readAllBytes(sourceConfig);
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "worktree staged\n"); fixture.git("add", "a.txt");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        fixture.restore(preview);
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertArrayEquals(originalIndex, Files.readAllBytes(sourceIndex));
        assertArrayEquals(config, Files.readAllBytes(sourceConfig));
        fixture.runtime.requireWorkspaceOwnership(fixture.task, fixture.work.toString(), fixture.prepared);
        assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void indexFlagsRoundTripAsOriginalBytesRatherThanBeingNormalizedAway() throws Exception {
        Fixture fixture = fixture("CLONE");
        byte[] original = fixture.index(); Map<String, Object> target = fixture.capture();
        fixture.git("update-index", "--assume-unchanged", "b.txt");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        assertEquals(true, preview.get("indexChanges"));
        fixture.restore(preview);
        assertArrayEquals(original, fixture.index());
        assertTrue(fixture.git("ls-files", "-v", "b.txt").startsWith("H "));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void diffAndExportAfterActualUndoPreserveTheRestoredIndexBytes(String strategy) throws Exception {
        Fixture fixture = fixture(strategy);
        fixture.git("config", "diff.autoRefreshIndex", "true");
        byte[] sourceIndex = Files.readAllBytes(fixture.source.resolve(".git/index"));
        byte[] sourceConfig = Files.readAllBytes(fixture.source.resolve(".git/config"));
        byte[] original = fixture.index(); Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent change\n");
        Files.write(fixture.work.resolve("binary.dat"), new byte[] { 0, 9, -1 });
        fixture.git("update-index", "--assume-unchanged", "b.txt");
        Map<String, Object> source = fixture.capture();
        Files.writeString(fixture.work.resolve("later.txt"), "later unrelated user file\n");
        Map<String, Object> preview = fixture.preview(target, source);
        assertEquals(true, preview.get("canRestore")); assertEquals(true, preview.get("indexChanges"));
        assertEquals("ACKNOWLEDGED", fixture.restore(preview).get("state"));
        assertArrayEquals(original, fixture.index());
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertArrayEquals(new byte[] { 0, 1, -1, 13, 10 }, Files.readAllBytes(fixture.work.resolve("binary.dat")));
        assertTrue(fixture.runtime.diff(fixture.work.toString()).contains("later.txt"));
        assertArrayEquals(original, fixture.index(), "Viewing restored files must retain the original index stat cache and flags");
        assertTrue(fixture.runtime.exportPatch(fixture.work.toString()).contains("later unrelated user file"));
        assertArrayEquals(original, fixture.index(), "Exporting restored files must retain the original index bytes");
        assertEquals("true", fixture.git("config", "--local", "--get", "diff.autoRefreshIndex").strip());
        assertArrayEquals(sourceIndex, Files.readAllBytes(fixture.source.resolve(".git/index")));
        assertArrayEquals(sourceConfig, Files.readAllBytes(fixture.source.resolve(".git/config")));
        assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
        Path statOnly = Files.createDirectory(temporary.resolve("stat-only-" + strategy));
        fixture.runtime.git(statOnly, "init"); fixture.runtime.git(statOnly, "config", "user.name", "Checkpoint Test");
        fixture.runtime.git(statOnly, "config", "user.email", "test@example.invalid");
        Path unchangedSensitive = statOnly.resolve(".env"); Files.writeString(unchangedSensitive, "UNCHANGED_FIXTURE=value\n");
        fixture.runtime.git(statOnly, "add", ".env"); fixture.runtime.git(statOnly, "commit", "-m", "unchanged sensitive fixture");
        byte[] statOnlyIndex = Files.readAllBytes(statOnly.resolve(".git/index"));
        Files.setLastModifiedTime(unchangedSensitive, FileTime.fromMillis(1_600_000_000_000L));
        assertEquals("", fixture.runtime.exportPatch(statOnly.toString()), "A timestamp change alone must not expose or reject unchanged sensitive content");
        assertArrayEquals(statOnlyIndex, Files.readAllBytes(statOnly.resolve(".git/index")));
        fixture.runtime.git(statOnly, "update-index", "--split-index");
        byte[] splitIndex = Files.readAllBytes(statOnly.resolve(".git/index"));
        Path sharedIndex = statOnly.resolve(fixture.runtime.git(statOnly, "rev-parse", "--shared-index-path").strip()).normalize();
        byte[] sharedBytes = Files.readAllBytes(sharedIndex);
        assertEquals("", fixture.runtime.exportPatch(statOnly.toString()));
        assertArrayEquals(splitIndex, Files.readAllBytes(statOnly.resolve(".git/index")));
        assertArrayEquals(sharedBytes, Files.readAllBytes(sharedIndex));
    }

    @Test
    void actualSplitIndexIsRecordedAndCannotBePresentedAsACompleteIndexRestore() throws Exception {
        Fixture fixture = fixture("CLONE");
        fixture.git("update-index", "--split-index");
        assertFalse(fixture.git("rev-parse", "--shared-index-path").isBlank());
        Map<String, Object> target = fixture.capture();
        Map<String, Object> manifest = fixture.runtime.checkpointManifest(fixture.task, str(target, "id"), str(target, "manifestSha256"));
        assertEquals("PARTIAL", target.get("status"));
        assertEquals(true, map(manifest, "index").get("sharedIndex"));
        assertEquals(false, map(manifest, "index").get("restorable"));
        fixture.git("update-index", "--no-split-index");
        byte[] current = fixture.index();
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        assertEquals("READY", source.get("status"));
        assertEquals(true, preview.get("indexChanges"));
        assertEquals(false, preview.get("canRestore"));
        assertEquals(409, assertThrows(ApiException.class, () -> fixture.restore(preview)).status());
        assertArrayEquals(current, fixture.index());
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    @Test
    void cancellationAfterOneActualWriteKeepsPartialEffectsUnknownAndCannotReplayTheOriginalOperation() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "agent a\n"); Files.writeString(fixture.work.resolve("b.txt"), "agent b\n");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        String operation = UUID.randomUUID().toString();
        assertThrows(IllegalStateException.class, () -> fixture.runtime.restoreCheckpoint(fixture.work.toString(), fixture.task, operation, preview,
            () -> { try { return Files.readString(fixture.work.resolve("a.txt")).equals("original a\n"); } catch (Exception exception) { throw new IllegalStateException(exception); } }));
        assertEquals("original a\n", Files.readString(fixture.work.resolve("a.txt")));
        assertEquals("agent b\n", Files.readString(fixture.work.resolve("b.txt")));
        Path journal = fixture.data.resolve("checkpoints/restores").resolve(fixture.task).resolve(operation).resolve("manifest.json");
        Map<?, ?> result = new ObjectMapper().readValue(Files.readAllBytes(journal), Map.class);
        assertEquals("UNKNOWN", result.get("state")); assertEquals(List.of("a.txt"), result.get("writtenPaths"));
        byte[] rawJournal = Files.readAllBytes(journal);
        assertThrows(ApiException.class, () -> fixture.runtime.restoreCheckpoint(fixture.work.toString(), fixture.task, operation, preview, () -> false));
        assertArrayEquals(rawJournal, Files.readAllBytes(journal));
    }

    @Test
    void anExistingForeignIndexLockPreventsWritesAndIsPreserved() throws Exception {
        Fixture fixture = fixture("CLONE");
        Map<String, Object> target = fixture.capture();
        Files.writeString(fixture.work.resolve("a.txt"), "staged a\n"); fixture.git("add", "a.txt");
        Map<String, Object> source = fixture.capture(), preview = fixture.preview(target, source);
        Path lock = Path.of(fixture.git("rev-parse", "--absolute-git-dir").strip()).resolve("index.lock");
        Files.writeString(lock, "foreign lock\n");
        assertThrows(ApiException.class, () -> fixture.restore(preview));
        assertEquals("foreign lock\n", Files.readString(lock));
        assertEquals("staged a\n", Files.readString(fixture.work.resolve("a.txt")));
    }

    @Test
    void checkpointsFromAnotherTaskOrAnOutsideDirectoryCannotAuthorizeUndo() throws Exception {
        Fixture first = fixture("CLONE"), second = fixture("CLONE");
        Map<String, Object> a = first.capture(), b = second.capture();
        assertThrows(ApiException.class, () -> first.preview(a, b));
        assertThrows(ApiException.class, () -> first.runtime.captureCheckpoint(first.source.toString(), first.task, obj()));
        assertThrows(ApiException.class, () -> first.runtime.checkpointManifest(first.task, "../outside", "0".repeat(64)));
    }

    @Test
    void fileExecutionPermissionsAreRestoredAlongWithTheBytes() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        Fixture fixture = fixture("CLONE");
        Path path = fixture.work.resolve("a.txt"); Set<PosixFilePermission> original = Files.getPosixFilePermissions(path);
        Map<String, Object> target = fixture.capture();
        Set<PosixFilePermission> changed = new java.util.HashSet<>(original); changed.add(PosixFilePermission.OWNER_EXECUTE); Files.setPosixFilePermissions(path, changed);
        Files.writeString(path, "executable agent\n");
        Map<String, Object> source = fixture.capture();
        fixture.restore(fixture.preview(target, source));
        assertEquals(original, Files.getPosixFilePermissions(path));
        assertEquals("original a\n", Files.readString(path));
    }

    @Test
    void readOnlyTargetPermissionsAreRestoredWithoutReopeningTheFileForWrite() throws Exception {
        org.junit.jupiter.api.Assumptions.assumeFalse(System.getProperty("os.name", "").startsWith("Windows"));
        Fixture fixture = fixture("CLONE");
        Path path = fixture.work.resolve("a.txt");
        Set<PosixFilePermission> original = Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ);
        Files.setPosixFilePermissions(path, original);
        Map<String, Object> target = fixture.capture();
        Files.setPosixFilePermissions(path, Set.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE));
        Files.writeString(path, "later writable agent\n");
        Map<String, Object> source = fixture.capture();
        Map<String, Object> result = fixture.restore(fixture.preview(target, source));
        assertEquals("ACKNOWLEDGED", result.get("state"));
        assertEquals("original a\n", Files.readString(path));
        assertEquals(original, Files.getPosixFilePermissions(path));
        assertEquals(fixture.head, fixture.sourceGit("rev-parse", "HEAD").strip());
    }

    private Map<String, Object> file(Map<String, Object> manifest, String path) { return objects(manifest.get("files")).stream().filter(value -> path.equals(value.get("path"))).findFirst().orElseThrow(); }

    /**
     * 夹具显式改写清单并提供新摘要，验证各引用的结构检查不能依赖旧摘要拒绝。
     */
    private String rewriteFixtureManifest(Fixture fixture, String checkpointId, Map<String, Object> manifest) throws Exception {
        byte[] bytes = new ObjectMapper().writeValueAsBytes(manifest);
        Files.write(fixture.data.resolve("checkpoints/tasks").resolve(fixture.task).resolve(checkpointId).resolve("manifest.json"), bytes);
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    }

    private Fixture fixture(String strategy) throws Exception {
        Path base = Files.createTempDirectory(temporary, "fixture-");
        Path source = Files.createDirectory(base.resolve("source")).toRealPath(), data = Files.createDirectory(base.resolve("data")).toRealPath();
        WorkspaceRuntime runtime = new WorkspaceRuntime(new ObjectMapper(), strategy.equals("LEGACY_ALIAS") ? base.resolve("data").toString() : data.toString());
        if (strategy.equals("SHA256")) runtime.git(source, "init", "--object-format=sha256"); else runtime.git(source, "init");
        runtime.git(source, "config", "user.name", "Checkpoint Test"); runtime.git(source, "config", "user.email", "test@example.invalid");
        Files.writeString(source.resolve("a.txt"), "original a\n"); Files.writeString(source.resolve("b.txt"), "original b\n");
        Files.write(source.resolve("binary.dat"), new byte[] { 0, 1, -1, 13, 10 }); Files.write(source.resolve("empty.txt"), new byte[0]);
        runtime.git(source, "add", "."); runtime.git(source, "commit", "-m", "baseline");
        String task = UUID.randomUUID().toString(), head = runtime.git(source, "rev-parse", "HEAD").strip();
        boolean legacy = strategy.startsWith("LEGACY");
        Map<String, Object> selection = legacy ? obj() : runtime.selectWorkspace(source.toString(), obj("strategy", strategy.equals("SHA256") ? "CLONE" : strategy), false, 1);
        Map<String, Object> prepared = legacy ? runtime.prepare(source.toString(), task, false) : runtime.prepareSelected(source.toString(), task, false, selection);
        Path work = Path.of(str(prepared, "workspace"));
        runtime.git(work, "config", "user.name", "Checkpoint Test"); runtime.git(work, "config", "user.email", "test@example.invalid");
        return new Fixture(runtime, data, source, work, task, head, prepared);
    }

    private record Fixture(WorkspaceRuntime runtime, Path data, Path source, Path work, String task, String head, Map<String, Object> prepared) {
        Map<String, Object> capture() { return runtime.captureCheckpoint(work.toString(), task, obj("kind", "MANUAL", "sessionId", "session-original")); }
        Map<String, Object> preview(Map<String, Object> target, Map<String, Object> source) { return runtime.previewCheckpointRestore(work.toString(), task, target, source); }
        Map<String, Object> restore(Map<String, Object> preview) { return runtime.restoreCheckpoint(work.toString(), task, UUID.randomUUID().toString(), preview, () -> false); }
        String git(String... arguments) { return runtime.git(work, arguments); }
        String sourceGit(String... arguments) { return runtime.git(source, arguments); }
        byte[] index() throws Exception { Path path = work.resolve(git("rev-parse", "--git-path", "index").strip()).normalize(); return Files.readAllBytes(path); }
    }
}
