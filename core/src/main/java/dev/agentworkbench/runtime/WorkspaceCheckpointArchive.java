package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;

/**
 * 文件先完整落盘再发布引用；撤销只处理两份原检查点之间的改动，不重放执行。
 */
final class WorkspaceCheckpointArchive {
    private static final int MAX_FILE = 256_000;
    private static final int MAX_MANIFEST = 16 * 1024 * 1024;
    private static final long MAX_CONTENT = 64L * 1024 * 1024;
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");
    private final WorkspaceRuntime runtime;
    private final ObjectMapper mapper;
    private final Path data;

    WorkspaceCheckpointArchive(WorkspaceRuntime runtime, ObjectMapper mapper, Path data) {
        this.runtime = runtime; this.mapper = mapper; this.data = data;
    }

    /**
     * 捕获固定 HEAD、暂存字节及实际文件；排除项和限额不冒充可恢复内容。
     */
    Map<String, Object> capture(String workspace, String taskId, Map<String, Object> metadata) {
        return capture(workspace, taskId, metadata, null);
    }

    Map<String, Object> capture(String workspace, String taskId, Map<String, Object> metadata, Map<String, Object> ownership) {
        String checkpointId = UUID.randomUUID().toString();
        try {
            Owner originalOwner = owner(workspace, taskId, ownership); Path root = originalOwner.root();
            GitState originalGit = gitState(root);
            verifyOwner(originalOwner, taskId, originalGit);
            String head = originalGit.head();
            byte[] originalIndex = indexBytesAt(originalGit.index());
            String indexListing = runtime.git(root, "ls-files", "-t", "--stage", "--cached", "--others", "--exclude-standard", "-z");
            Listing originalListing = listing(indexListing); Map<String, String> entries = originalListing.entries();
            WorkspaceContentSnapshot captured = runtime.contentSnapshot(root.toString(), originalGit.objectFormat(), modes(entries), originalListing.paths());
            List<Map<String, Object>> files = new ArrayList<>(), staged = new ArrayList<>();
            Set<String> capturedBlobs = new TreeSet<>();
            long storedBytes = 0;
            for (WorkspaceContentSnapshot.File file : captured.files().values().stream().sorted(java.util.Comparator.comparing(WorkspaceContentSnapshot.File::path)).toList()) {
                if (files.size() >= 20_000) throw new ApiException(409, "检查点文件清单超过 20000 项，未保存不完整清单。");
                String state = !file.exists() ? "ABSENT" : runtime.previewExcluded(file.path()) ? "EXCLUDED"
                    : file.bytes() > MAX_FILE || storedBytes + file.bytes() > MAX_CONTENT ? "LIMIT" : "STORED";
                Map<String, Object> facts = obj("path", file.path(), "exists", file.exists(), "state", state, "bytes", file.bytes(), "sha256", file.sha256(),
                    "executable", file.executable(), "indexEntry", entries.getOrDefault(file.path(), ""));
                if (file.exists()) {
                    Path path = file(root, file.path());
                    Map<String, Object> before = stamp(path);
                    facts.put("stamp", before); facts.put("permissions", permissions(path));
                    if ("STORED".equals(state)) {
                        byte[] bytes = runtime.readPreviewFile(root.toString(), file);
                        facts.put("blob", putCapturedBlob(bytes, capturedBlobs)); storedBytes += bytes.length;
                    }
                    if (!before.equals(stamp(path))) throw conflict("文件在检查点捕获期间变化：" + file.path());
                } else facts.put("permissions", List.of());
                files.add(facts);
            }
            Set<String> savedObjects = new TreeSet<>();
            boolean stagedComplete = true;
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                String[] fields = entry.getValue().split(" ");
                if (runtime.previewExcluded(entry.getKey())) { stagedComplete = false; continue; }
                savedObjects.add(fields[1]);
            }
            Map<String, Long> batch = new LinkedHashMap<>(); long batchBytes = 0;
            for (Map.Entry<String, Long> entry : runtime.checkpointGitBlobSizes(root, List.copyOf(savedObjects)).entrySet()) {
                long size = entry.getValue();
                if (size > MAX_FILE || storedBytes + size > MAX_CONTENT) { stagedComplete = false; continue; }
                if (batch.size() >= GitBlobBatch.MAX_OBJECTS || batchBytes + size + GitBlobBatch.MAX_HEADER + 1 > GitBlobBatch.MAX_BYTES) {
                    archiveStaged(root, batch, staged, capturedBlobs); batch.clear(); batchBytes = 0;
                }
                batch.put(entry.getKey(), size); batchBytes += size + GitBlobBatch.MAX_HEADER + 1; storedBytes += size;
            }
            if (!batch.isEmpty()) archiveStaged(root, batch, staged, capturedBlobs);
            String shared = originalGit.sharedIndex();
            if (!shared.isBlank()) stagedComplete = false;
            GitState finalGit = gitState(root);
            String finalIndexListing = runtime.git(root, "ls-files", "-t", "--stage", "--cached", "--others", "--exclude-standard", "-z");
            Listing finalListing = listing(finalIndexListing);
            if (!originalGit.equals(finalGit) || !Arrays.equals(originalIndex, indexBytesAt(finalGit.index()))
                    || !indexListing.equals(finalIndexListing) || !captured.version().equals(runtime.checkpointFingerprint(root.toString(), modes(finalListing.entries()), finalListing.paths())))
                throw conflict("检查点捕获期间 HEAD、暂存区或文件发生变化，未发布检查点。");
            Owner finalOwner = owner(root.toString(), taskId, ownership);
            if (!originalOwner.equals(finalOwner)) throw conflict("检查点捕获期间原准备归属发生变化。");
            verifyOwner(finalOwner, taskId, finalGit);
            Map<String, Object> manifest = obj("schemaVersion", 1, "id", checkpointId, "taskId", taskId, "workspace", root.toString(), "createdAt", now(),
                "captureExecutorPid", ProcessHandle.current().pid(), "captureProcessStartedAt", ProcessHandle.current().info().startInstant().map(Object::toString).orElse(""),
                "head", head, "objectFormat", captured.objectFormat(), "version", captured.version(), "files", files,
                "index", obj("present", originalIndex != null, "sha256", indexHash(originalIndex), "blob", originalIndex == null ? "" : putCapturedBlob(originalIndex, capturedBlobs),
                    "entries", entries, "stagedObjects", staged, "restorable", stagedComplete, "sharedIndex", !shared.isBlank()), "metadata", metadata,
                "status", files.stream().anyMatch(value -> Set.of("EXCLUDED", "LIMIT").contains(str(value, "state"))) || !stagedComplete ? "PARTIAL" : "READY");
            byte[] bytes = mapper.writeValueAsBytes(manifest);
            if (bytes.length > MAX_MANIFEST) throw new ApiException(409, "检查点清单超过保存上限，未发布引用。");
            Path destination = manifestPath(taskId, checkpointId, true);
            writeNew(destination, bytes);
            String digest = hash(bytes);
            read(taskId, checkpointId, digest);
            return obj("schemaVersion", 1, "id", checkpointId, "taskId", taskId, "workspace", root.toString(), "createdAt", manifest.get("createdAt"),
                "captureExecutorPid", manifest.get("captureExecutorPid"), "captureProcessStartedAt", manifest.get("captureProcessStartedAt"),
                "head", head, "version", captured.version(), "indexVersion", indexHash(originalIndex), "status", manifest.get("status"), "manifestSha256", digest,
                "fileCount", files.size(), "storedBytes", storedBytes, "excludedCount", files.stream().filter(value -> "EXCLUDED".equals(str(value, "state"))).count(),
                "limitedCount", files.stream().filter(value -> "LIMIT".equals(str(value, "state"))).count(), "metadata", metadata);
        } catch (IOException exception) { throw new IllegalStateException("检查点文件未完整保存，不会发布可恢复引用。", exception); }
    }

    private void archiveStaged(Path root, Map<String, Long> objects, List<Map<String, Object>> staged, Set<String> capturedBlobs) throws IOException {
        for (Map.Entry<String, byte[]> entry : runtime.checkpointGitBlobs(root, objects).entrySet())
            staged.add(obj("objectId", entry.getKey(), "bytes", (long) entry.getValue().length, "blob", putCapturedBlob(entry.getValue(), capturedBlobs)));
    }

    /**
     * 读取原摘要绑定的清单及全部可用对象；目录被替换或对象损坏立即拒绝。
     */
    Map<String, Object> read(String taskId, String checkpointId, String expectedDigest) {
        if (!expectedDigest.matches("[0-9a-f]{64}")) throw new ApiException(400, "无效检查点摘要。");
        try {
            byte[] bytes = readRegular(manifestPath(taskId, checkpointId, false), MAX_MANIFEST);
            if (!hash(bytes).equals(expectedDigest)) throw conflict("原检查点清单摘要不一致。");
            Map<String, Object> manifest = mapper.readValue(bytes, new TypeReference<>() {});
            if (num(manifest, "schemaVersion", 0) != 1 || !taskId.equals(str(manifest, "taskId")) || !checkpointId.equals(str(manifest, "id"))
                    || !str(manifest, "version").matches("[0-9a-f]{64}") || !Set.of("sha1", "sha256").contains(str(manifest, "objectFormat")))
                throw conflict("原检查点身份或协议不一致。");
            Map<String, BlobFacts> verified = new TreeMap<>();
            Set<String> paths = new TreeSet<>();
            for (Map<String, Object> value : objects(manifest.get("files"))) {
                relative(str(value, "path"));
                if (!paths.add(str(value, "path")) || !Set.of("STORED", "ABSENT", "EXCLUDED", "LIMIT").contains(str(value, "state"))) throw conflict("检查点包含无效或重复文件。");
                if ("STORED".equals(str(value, "state"))) {
                    String digest = str(value, "blob"); BlobFacts content = verifiedBlob(digest, MAX_FILE, str(manifest, "objectFormat"), verified);
                    if (!digest.equals(str(value, "sha256")) || content.bytes() != longValue(value, "bytes")) throw conflict("检查点文件对象与清单不一致。");
                }
            }
            Map<String, Object> index = map(manifest, "index");
            if (bool(index, "present", false)) {
                String digest = str(index, "blob"); verifiedBlob(digest, MAX_MANIFEST, str(manifest, "objectFormat"), verified);
                if (!digest.equals(str(index, "sha256"))) throw conflict("检查点暂存区对象损坏。");
            }
            for (Map<String, Object> value : objects(index.get("stagedObjects"))) {
                BlobFacts content = verifiedBlob(str(value, "blob"), MAX_FILE, str(manifest, "objectFormat"), verified);
                if (content.bytes() != longValue(value, "bytes") || !content.gitHash().equals(str(value, "objectId")))
                    throw conflict("检查点暂存文件对象损坏。");
            }
            return manifest;
        } catch (IOException exception) { throw new ApiException(409, "检查点清单或内容无法完整读取，请保留现场并核对归档。"); }
    }

    /**
     * 单次清单按原摘要读取每个对象一次，各引用仍分别核对长度、限额和 Git 哈希。
     */
    private BlobFacts verifiedBlob(String digest, int maximum, String format, Map<String, BlobFacts> verified) throws IOException {
        BlobFacts facts = verified.get(digest);
        if (facts == null) {
            byte[] bytes = blob(digest, maximum);
            facts = new BlobFacts(bytes.length, gitHash(format, bytes)); verified.put(digest, facts);
        }
        if (facts.bytes() > maximum) throw conflict("检查点对象超过此引用的完整读取上限。");
        return facts;
    }

    private record BlobFacts(int bytes, String gitHash) { }

    /**
     * 预览按原保存边界核对反向改动；没有受影响的用户后续文件不会加入写入计划。
     */
    Map<String, Object> preview(String workspace, String taskId, Map<String, Object> target, Map<String, Object> source) {
        Path root = owned(workspace, taskId);
        Map<String, Object> to = read(taskId, str(target, "id"), str(target, "manifestSha256"));
        Map<String, Object> from = read(taskId, str(source, "id"), str(source, "manifestSha256"));
        requireSameWorkspace(root, from, to);
        Map<String, Object> current = capture(root.toString(), taskId, obj("kind", "UNDO_PREFLIGHT"));
        Map<String, Object> now = read(taskId, str(current, "id"), str(current, "manifestSha256"));
        List<Map<String, Object>> changes = new ArrayList<>(), conflicts = new ArrayList<>();
        if (!str(from, "head").equals(str(to, "head")) || !str(from, "head").equals(str(now, "head"))) conflicts.add(obj("path", "HEAD", "reason", "提交已变化，撤销不会移动 HEAD。"));
        Map<String, Map<String, Object>> before = files(from), desired = files(to), actual = files(now);
        Set<String> paths = new TreeSet<>(before.keySet()); paths.addAll(desired.keySet());
        for (String path : paths) {
            Map<String, Object> start = before.getOrDefault(path, absent(path)), end = desired.getOrDefault(path, absent(path)), live = actual.getOrDefault(path, absent(path));
            if (logical(start).equals(logical(end))) continue;
            String action = !bool(end, "exists", false) ? "DELETE" : !bool(start, "exists", false) ? "CREATE" : workState(start).equals(workState(end)) ? "INDEX" : "MODIFY";
            changes.add(obj("path", path, "action", action, "beforeSha256", start.get("sha256"), "afterSha256", end.get("sha256"), "bytes", end.getOrDefault("bytes", 0)));
            if (changes.size() > 100) throw new ApiException(409, "单次撤销超过 100 个文件，请选择更近的检查点。");
            if (Set.of("EXCLUDED", "LIMIT").contains(str(start, "state")) || Set.of("EXCLUDED", "LIMIT").contains(str(end, "state")))
                conflicts.add(obj("path", path, "reason", "受影响内容被排除或超过保存上限，不能宣称恢复完整检查点。"));
            else if (!logical(start).equals(logical(live))) conflicts.add(obj("path", path, "reason", "文件或暂存内容有后续修改，保留当前内容。"));
        }
        boolean indexChanges = !str(map(from, "index"), "sha256").equals(str(map(to, "index"), "sha256"));
        if (indexChanges && !str(map(from, "index"), "sha256").equals(str(map(now, "index"), "sha256")))
            conflicts.add(obj("path", "暂存区", "reason", "原保存边界后暂存区已变化，保留全部当前暂存状态。"));
        if (indexChanges && !bool(map(to, "index"), "restorable", false)) conflicts.add(obj("path", "暂存区", "reason", "目标暂存区包含排除对象、超限内容或共享 index，不能完整恢复。"));
        return obj("schemaVersion", 1, "taskId", taskId, "workspace", root.toString(), "target", target, "source", source, "current", current,
            "changes", changes, "conflicts", conflicts, "indexChanges", indexChanges, "canRestore", conflicts.isEmpty(), "createdAt", now());
    }

    /**
     * 原 Worker 持有根保护后执行预览；部分文件效果写入原日志，失败不自动回滚。
     */
    Map<String, Object> restore(String workspace, String taskId, String operationId, Map<String, Object> preview, BooleanSupplier cancelled) {
        Path root = owned(workspace, taskId);
        if (!bool(preview, "canRestore", false) || !taskId.equals(str(preview, "taskId")) || !root.toString().equals(str(preview, "workspace"))) throw conflict("撤销预览存在冲突或不属于当前现场。");
        Map<String, Object> targetRecord = map(preview, "target"), sourceRecord = map(preview, "source"), currentRecord = map(preview, "current");
        Map<String, Object> target = read(taskId, str(targetRecord, "id"), str(targetRecord, "manifestSha256"));
        Map<String, Object> source = read(taskId, str(sourceRecord, "id"), str(sourceRecord, "manifestSha256"));
        Map<String, Object> current = read(taskId, str(currentRecord, "id"), str(currentRecord, "manifestSha256"));
        requireSameWorkspace(root, source, target); requireSameWorkspace(root, current, target);
        Map<String, Map<String, Object>> originals = files(current), desired = files(target);
        List<String> paths = objects(preview.get("changes")).stream().map(value -> str(value, "path")).toList();
        if (paths.size() > 100 || new TreeSet<>(paths).size() != paths.size()) throw conflict("撤销清单无效。");
        Path journal = journalPath(taskId, operationId);
        if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) throw conflict("原撤销操作已有日志，不能重复执行或改写原结果。");
        List<String> written = new ArrayList<>();
        FileChannel indexLock = null; Path lockPath = null; String lockKey = "";
        boolean effects = false;
        Map<String, Object> progress = obj("schemaVersion", 1, "operationId", operationId, "taskId", taskId, "workspace", root.toString(), "state", "PREPARED",
            "target", targetRecord, "source", sourceRecord, "current", currentRecord, "paths", paths, "writtenPaths", written, "createdAt", now());
        try {
            checkCancelled(cancelled);
            requireCurrent(root, current);
            Map<String, Object> indexStamp = indexStamp(root);
            for (String path : paths) requireFile(root, path, originals.getOrDefault(path, absent(path)));
            if (bool(preview, "indexChanges", false)) {
                Path index = indexPath(root); lockPath = index.resolveSibling(index.getFileName() + ".lock");
                indexLock = FileChannel.open(lockPath, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
                lockKey = WorkspaceFileIdentity.key(lockPath);
                if (!indexStamp.equals(indexStamp(root))) throw conflict("取得暂存锁期间 index 已变化。");
            }
            writeNew(journal, mapper.writeValueAsBytes(progress));
            checkCancelled(cancelled); requireCurrent(root, current);
            if (bool(preview, "indexChanges", false)) {
                for (Map<String, Object> object : objects(map(target, "index").get("stagedObjects"))) {
                    checkCancelled(cancelled);
                    runtime.writeCheckpointGitBlob(root, blob(str(object, "blob"), MAX_FILE), str(object, "objectId"));
                }
            }
            for (String path : paths) {
                checkCancelled(cancelled);
                if (!str(current, "head").equals(runtime.git(root, "rev-parse", "HEAD").strip()) || !indexStamp.equals(indexStamp(root))) throw conflict("撤销期间 HEAD 或暂存区发生变化。");
                Map<String, Object> before = originals.getOrDefault(path, absent(path)), after = desired.getOrDefault(path, absent(path));
                requireFile(root, path, before);
                if (workState(before).equals(workState(after))) continue;
                progress.putAll(obj("state", "APPLYING", "activePath", path)); saveProgress(journal, progress);
                Path destination = file(root, path);
                effects = true;
                if (!bool(after, "exists", false)) { Files.delete(destination); forceDirectory(destination.getParent()); }
                else install(root, path, before, after);
                requireLogicalFile(root, path, after);
                written.add(path); progress.put("writtenPaths", List.copyOf(written)); saveProgress(journal, progress);
            }
            if (bool(preview, "indexChanges", false)) {
                checkCancelled(cancelled);
                if (!indexStamp.equals(indexStamp(root)) || !lockKey.equals(WorkspaceFileIdentity.key(lockPath))) throw conflict("暂存锁或原 index 身份发生变化。");
                Map<String, Object> index = map(target, "index");
                if (!bool(index, "present", false)) throw conflict("不能用缺失的目标 index 覆盖现有暂存区。");
                byte[] bytes = blob(str(index, "blob"), MAX_MANIFEST);
                writeChannel(indexLock, bytes);
                progress.putAll(obj("state", "APPLYING_INDEX")); saveProgress(journal, progress);
                effects = true;
                Files.move(lockPath, indexPath(root), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                forceDirectory(lockPath.getParent());
                indexLock.close(); indexLock = null;
                if (!str(index, "sha256").equals(indexHash(indexBytes(root)))) throw conflict("暂存区替换后的原摘要无法核验。");
            }
            checkCancelled(cancelled);
            for (String path : paths) requireLogicalFile(root, path, desired.getOrDefault(path, absent(path)));
            if (!str(current, "head").equals(runtime.git(root, "rev-parse", "HEAD").strip())) throw conflict("撤销结束时 HEAD 已变化。");
            Map<String, Object> result = capture(root.toString(), taskId, obj("kind", "UNDO_RESULT", "operationId", operationId, "targetCheckpointId", targetRecord.get("id")));
            Map<String, Object> completed = read(taskId, str(result, "id"), str(result, "manifestSha256"));
            Map<String, Map<String, Object>> finalFiles = files(completed);
            Set<String> allPaths = new TreeSet<>(originals.keySet()); allPaths.addAll(finalFiles.keySet());
            for (String path : allPaths) if (!paths.contains(path) && !logical(originals.getOrDefault(path, absent(path))).equals(logical(finalFiles.getOrDefault(path, absent(path)))))
                throw conflict("撤销期间未参与计划的文件发生变化，请核对现场：" + path);
            progress.putAll(obj("state", "APPLIED", "completedAt", now(), "result", result)); progress.remove("activePath"); saveProgress(journal, progress);
            return obj("state", "ACKNOWLEDGED", "changedPaths", paths, "writtenPaths", written, "resultVersion", result.get("version"), "resultCheckpoint", result,
                "targetCheckpointId", targetRecord.get("id"), "journalSha256", hash(readRegular(journal, MAX_MANIFEST)), "headUnchanged", true);
        } catch (Exception exception) {
            progress.putAll(obj("state", effects ? "UNKNOWN" : "REJECTED", "completedAt", now(), "writtenPaths", List.copyOf(written), "effectsStarted", effects));
            try { if (Files.exists(journal, LinkOption.NOFOLLOW_LINKS)) saveProgress(journal, progress); } catch (Exception ignored) { }
            if (effects) throw new IllegalStateException("撤销在效果发生后未取得完整回执，保留原日志、预算和当前文件，禁止自动重放。", exception);
            if (exception instanceof ApiException api) throw api;
            if (exception instanceof CancellationException cancellation) throw cancellation;
            throw new ApiException(409, "撤销尚未写入文件，原现场保留；请重新核对预览与暂存锁。");
        } finally {
            if (indexLock != null) try { indexLock.close(); } catch (IOException ignored) { }
            if (lockPath != null && !lockKey.isBlank()) try {
                if (Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && lockKey.equals(WorkspaceFileIdentity.key(lockPath))) Files.delete(lockPath);
            } catch (IOException ignored) { }
        }
    }

    private void requireCurrent(Path root, Map<String, Object> captured) throws IOException {
        if (!str(captured, "version").equals(runtime.fingerprint(root.toString())) || !str(captured, "head").equals(runtime.git(root, "rev-parse", "HEAD").strip())
                || !str(map(captured, "index"), "sha256").equals(indexHash(indexBytes(root)))) throw conflict("文件、HEAD 或暂存区在预览后变化，请重新生成预览。");
    }

    /**
     * 用户确认预览前只读重查，排队不能把已过期的内容变成写入授权。
     */
    void validatePreview(String workspace, String taskId, Map<String, Object> preview) {
        Path root = owned(workspace, taskId);
        Map<String, Object> record = map(preview, "current");
        Map<String, Object> current = read(taskId, str(record, "id"), str(record, "manifestSha256"));
        if (!taskId.equals(str(preview, "taskId")) || !root.toString().equals(str(preview, "workspace"))) throw conflict("撤销预览不属于原任务。");
        try {
            requireCurrent(root, current);
            Map<String, Map<String, Object>> files = files(current);
            for (Map<String, Object> value : objects(preview.get("changes"))) requireFile(root, str(value, "path"), files.getOrDefault(str(value, "path"), absent(str(value, "path"))));
        } catch (IOException exception) { throw conflict("原撤销预览现场无法完整核验。"); }
    }

    private void requireSameWorkspace(Path root, Map<String, Object> left, Map<String, Object> right) {
        if (!root.toString().equals(str(left, "workspace")) || !root.toString().equals(str(right, "workspace")) || !str(left, "taskId").equals(str(right, "taskId")))
            throw conflict("检查点不属于同一任务的原现场。");
    }

    private void requireFile(Path root, String path, Map<String, Object> expected) throws IOException {
        Path actual = file(root, path);
        if (bool(expected, "exists", false)) {
            if (!sameStamp(map(expected, "stamp"), stamp(actual))) throw conflict("文件身份在预览后变化：" + path);
        } else if (Files.exists(actual, LinkOption.NOFOLLOW_LINKS)) throw conflict("原不存在目标已出现：" + path);
        requireLogicalFile(root, path, expected);
    }

    private void requireLogicalFile(Path root, String path, Map<String, Object> expected) throws IOException {
        Path actual = file(root, path);
        if (!bool(expected, "exists", false)) {
            if (Files.exists(actual, LinkOption.NOFOLLOW_LINKS)) throw conflict("删除目标出现新的内容：" + path);
            return;
        }
        byte[] bytes = readRegular(actual, MAX_FILE);
        if (!hash(bytes).equals(str(expected, "sha256")) || bytes.length != longValue(expected, "bytes") || !permissions(actual).equals(strings(expected.get("permissions"))))
            throw conflict("文件内容或权限无法核验：" + path);
    }

    private void install(Path root, String path, Map<String, Object> before, Map<String, Object> after) throws IOException {
        Path destination = file(root, path);
        createProjectParents(root, destination.getParent());
        byte[] bytes = blob(str(after, "blob"), MAX_FILE);
        Path temporary = Files.createTempFile(destination.getParent(), ".agent-undo-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                writeChannel(channel, bytes);
                if (!WINDOWS) {
                    // 保持原写通道打开，恢复只读权限后仍能同步同一文件的内容与元数据。
                    Files.setPosixFilePermissions(temporary, strings(after.get("permissions")).stream().map(PosixFilePermission::valueOf).collect(java.util.stream.Collectors.toSet()));
                    channel.force(true);
                }
            }
            requireFile(root, path, before);
            if (bool(before, "exists", false)) Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            else Files.createLink(destination, temporary);
            forceDirectory(destination.getParent());
        } finally { if (Files.deleteIfExists(temporary)) forceDirectory(temporary.getParent()); }
    }

    private Map<String, Map<String, Object>> files(Map<String, Object> manifest) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (Map<String, Object> file : objects(manifest.get("files"))) result.put(str(file, "path"), file);
        return result;
    }

    private Map<String, Object> absent(String path) { return obj("path", path, "exists", false, "state", "ABSENT", "bytes", 0, "sha256", "", "indexEntry", "", "permissions", List.of(), "executable", false); }

    private Map<String, Object> workState(Map<String, Object> file) {
        return obj("exists", bool(file, "exists", false), "sha256", str(file, "sha256"), "permissions", strings(file.get("permissions")), "executable", bool(file, "executable", false));
    }

    private Map<String, Object> logical(Map<String, Object> file) {
        Map<String, Object> result = workState(file); result.put("indexEntry", str(file, "indexEntry")); return result;
    }

    /**
     * Git 状态前缀区分暂存头和任意未跟踪文件名，含制表符的路径不能伪装成暂存记录。
     */
    private Listing listing(String output) {
        Map<String, String> entries = new TreeMap<>(); Set<String> paths = new TreeSet<>();
        for (String record : output.split("\0")) {
            if (record.isEmpty()) continue;
            if (record.length() < 3 || record.charAt(1) != ' ') throw conflict("检查点成员清单缺少 Git 状态标记。");
            char type = record.charAt(0); record = record.substring(2);
            if (type == '?') {
                relative(record); if (!paths.add(record)) throw conflict("检查点包含重复文件路径。");
                continue;
            }
            if ("HSMRC".indexOf(type) < 0) throw conflict("检查点成员状态无效。");
            int delimiter = record.indexOf('\t');
            if (delimiter < 0) throw conflict("暂存清单格式无效。");
            String header = record.substring(0, delimiter), path = record.substring(delimiter + 1);
            relative(path);
            if (!header.matches("100(?:644|755) [0-9a-f]{40}(?:[0-9a-f]{24})? 0") || entries.put(path, header) != null || !paths.add(path))
                throw conflict("检查点暂不支持未合并、链接、子模块或重复暂存项。");
        }
        return new Listing(entries, paths);
    }

    private record Listing(Map<String, String> entries, Set<String> paths) { }

    private Map<String, String> modes(Map<String, String> entries) {
        Map<String, String> result = new TreeMap<>(); entries.forEach((path, entry) -> result.put(path, entry.substring(0, 6))); return result;
    }

    private Path owned(String workspace, String taskId) {
        return owned(workspace, taskId, null);
    }

    private Path owned(String workspace, String taskId, Map<String, Object> ownership) {
        try {
            Owner original = owner(workspace, taskId, ownership);
            if (original.legacy()) verifyOwner(original, taskId, gitState(original.root()));
            else runtime.requireWorkspaceOwnership(taskId, original.root().toString(), map(original.saved(), "facts"));
            return original.root();
        } catch (IOException exception) { throw conflict("原任务现场不可读取或解析目标变化。"); }
    }

    private Owner owner(String workspace, String taskId, Map<String, Object> ownership) throws IOException {
        runtime.requireId(taskId);
        Path base = data.toRealPath(), expected = base.resolve("workspaces").resolve(taskId).resolve("work");
        if (!WorkspaceCheckpointOwnership.matches(expected, workspace)) throw conflict("检查点仅属于本任务的隔离现场。");
        safeDirectory(expected, false);
        Path selection = expected.getParent().resolve("selection.json");
        if (Files.exists(selection, LinkOption.NOFOLLOW_LINKS)) {
            Map<String, Object> saved = mapper.readValue(readRegular(selection, 1_000_000), new TypeReference<>() {});
            if (ownership != null && !map(map(saved, "facts"), "workspaceOwnership").equals(ownership)) throw conflict("原任务归属与现场选择回执不一致。");
            if (map(map(saved, "facts"), "workspaceOwnership").isEmpty()) throw conflict("所选现场缺少原 Git 归属。");
            return new Owner(expected, saved, false);
        } else {
            if (ownership != null && !ownership.isEmpty()) throw conflict("原任务的现场选择归属回执已丢失。");
            Map<String, Object> saved = mapper.readValue(readRegular(expected.getParent().resolve("preparation.json"), 1_000_000), new TypeReference<>() {});
            return new Owner(expected, saved, true);
        }
    }

    private void verifyOwner(Owner owner, String taskId, GitState observed) throws IOException {
        WorkspaceCheckpointOwnership.verify(owner.root(), taskId, owner.saved(), owner.legacy(), observed.directory(), observed.common(), observed.head());
    }

    private record Owner(Path root, Map<String, Object> saved, boolean legacy) { }

    private Path file(Path root, String name) throws IOException {
        Path result = root.resolve(relative(name)).normalize();
        if (!result.startsWith(root) || result.equals(root)) throw conflict("检查点路径越界。");
        Path current = root;
        for (Path component : root.relativize(result)) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (attributes.isSymbolicLink() || attributes.isOther() || !current.toRealPath().equals(current)) throw conflict("检查点文件路径包含外国解析目标。");
                if (!current.equals(result) && !attributes.isDirectory()) throw conflict("检查点父路径不是目录。");
            }
        }
        return result;
    }

    private Path relative(String name) {
        if (name == null || name.isBlank() || name.indexOf('\0') >= 0 || name.contains(":")) throw conflict("无效检查点相对路径。");
        Path relative = Path.of(name);
        if (relative.isAbsolute()) throw conflict("检查点不接受绝对文件路径。");
        for (Path part : relative) if (Set.of("..", ".git").contains(part.toString().toLowerCase(java.util.Locale.ROOT))) throw conflict("检查点不能访问 Git 内部或父目录。");
        if (!relative.normalize().equals(relative)) throw conflict("检查点路径必须为规范相对路径。");
        return relative;
    }

    private Path indexPath(Path root) throws IOException {
        String[] fields = gitFields(runtime.git(root, "rev-parse", "--path-format=absolute", "--git-path", "index", "--absolute-git-dir"), 2);
        Path path = Path.of(fields[0]).normalize().toAbsolutePath();
        Path expected = Path.of(fields[1]).toRealPath().resolve("index");
        if (!path.equals(expected) || !path.getParent().toRealPath().equals(path.getParent())) throw conflict("原 Git index 解析目标变化。");
        return path;
    }

    /**
     * 同一 Git 回执读取固定元数据，捕获前后仍逐项比较 HEAD、格式、index 和共享状态。
     */
    private GitState gitState(Path root) throws IOException {
        String[] fields = gitFields(runtime.git(root, "rev-parse", "--path-format=absolute", "--show-object-format", "HEAD", "--absolute-git-dir", "--git-common-dir", "--git-path", "index", "--shared-index-path"), 6, true);
        if (!Set.of("sha1", "sha256").contains(fields[0]) || !fields[1].matches(fields[0].equals("sha1") ? "[0-9a-f]{40}" : "[0-9a-f]{64}")) throw conflict("Git 对象格式或 HEAD 回执无效。");
        Path directory = Path.of(fields[2]).toRealPath(), common = Path.of(fields[3]).toRealPath(), index = Path.of(fields[4]).toAbsolutePath().normalize();
        if (!index.equals(directory.resolve("index")) || !index.getParent().toRealPath().equals(index.getParent())) throw conflict("原 Git index 解析目标变化。");
        return new GitState(fields[1], fields[0], index, fields[5], directory, common);
    }

    private String[] gitFields(String output, int count) { return gitFields(output, count, false); }

    private String[] gitFields(String output, int count, boolean optionalLast) {
        String[] fields = output.replace("\r\n", "\n").split("\n", -1);
        boolean absent = optionalLast && fields.length == count && fields[count - 1].isEmpty();
        if (absent) {
            // Git 未启用 split index 时不输出末项，不能将它与缺失的必需字段混淆。
            fields = Arrays.copyOf(fields, count + 1); fields[count] = "";
        }
        if (fields.length != count + 1 || !fields[count].isEmpty()) throw conflict("Git 元数据缺少完整字段或包含额外输出。");
        for (int index = 0; index < count; index++) if (fields[index].indexOf('\r') >= 0 || (fields[index].isBlank() && !(absent && index == count - 1))) throw conflict("Git 元数据字段无效。");
        return Arrays.copyOf(fields, count);
    }

    private record GitState(String head, String objectFormat, Path index, String sharedIndex, Path directory, Path common) { }

    private byte[] indexBytes(Path root) throws IOException { return indexBytesAt(indexPath(root)); }
    private byte[] indexBytesAt(Path path) throws IOException { return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? readRegular(path, MAX_MANIFEST) : null; }
    private String indexHash(byte[] bytes) { return bytes == null ? "MISSING" : hash(bytes); }
    private Map<String, Object> indexStamp(Path root) throws IOException { Path path = indexPath(root); return Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? stamp(path) : obj("exists", false); }

    private Map<String, Object> stamp(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile() || !path.toRealPath().equals(path)) throw conflict("原文件不是可核验的普通文件。");
        return obj("fileKey", WorkspaceFileIdentity.key(path), "bytes", attributes.size(), "modifiedAt", attributes.lastModifiedTime().toString(), "permissions", permissions(path));
    }

    /**
     * JSON 读回的小整数类型可不同，原文件身份和字节数仍逐字段严格核对。
     */
    private boolean sameStamp(Map<String, Object> expected, Map<String, Object> actual) {
        return str(expected, "fileKey").equals(str(actual, "fileKey")) && longValue(expected, "bytes") == longValue(actual, "bytes")
            && str(expected, "modifiedAt").equals(str(actual, "modifiedAt")) && strings(expected.get("permissions")).equals(strings(actual.get("permissions")));
    }

    private List<String> permissions(Path path) throws IOException { return WINDOWS ? List.of() : Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS).stream().map(Enum::name).sorted().toList(); }

    /**
     * 本次相同字节只发布一次，清单落盘后仍独立读回全部引用，不跨捕获复用核验。
     */
    private String putCapturedBlob(byte[] bytes, Set<String> capturedBlobs) throws IOException {
        String digest = hash(bytes);
        if (capturedBlobs.add(digest)) return putBlob(bytes);
        return digest;
    }

    private String putBlob(byte[] bytes) throws IOException {
        String digest = hash(bytes); Path path = blobPath(digest, true);
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) { if (!Arrays.equals(bytes, blob(digest, Math.max(MAX_MANIFEST, bytes.length)))) throw conflict("已有内容对象与原摘要不一致。"); return digest; }
        Path temporary = Files.createTempFile(path.getParent(), ".checkpoint-", ".tmp");
        try {
            try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { writeChannel(channel, bytes); }
            try { Files.createLink(path, temporary); }
            catch (java.nio.file.FileAlreadyExistsException concurrent) { if (!Arrays.equals(bytes, blob(digest, Math.max(MAX_MANIFEST, bytes.length)))) throw conflict("并发内容对象不一致。"); }
            forceDirectory(path.getParent());
        } finally { Files.deleteIfExists(temporary); }
        return digest;
    }

    private byte[] blob(String digest, int maximum) throws IOException {
        byte[] bytes = readRegular(blobPath(digest, false), maximum);
        if (!hash(bytes).equals(digest)) throw conflict("检查点内容对象损坏。");
        return bytes;
    }

    byte[] manifestBytes(String taskId, String id, String digest) {
        read(taskId, id, digest);
        try {
            byte[] bytes = readRegular(manifestPath(taskId, id, false), MAX_MANIFEST);
            if (!hash(bytes).equals(digest)) throw conflict("导出读取时原检查点清单摘要变化。");
            return bytes;
        } catch (IOException exception) { throw conflict("检查点清单读取失败。"); }
    }
    byte[] blobBytes(String digest) { try { return blob(digest, MAX_MANIFEST); } catch (IOException exception) { throw conflict("检查点对象读取失败。"); } }

    /**
     * 撤销进度按原操作读取；缺失日志与已观察文件效果分别保留，不补造回执。
     */
    Map<String, Object> journal(String taskId, String operationId) {
        runtime.requireId(taskId); uuid(operationId);
        try {
            Path directory = data.toRealPath().resolve("checkpoints").resolve("restores").resolve(taskId).resolve(operationId);
            if (!optionalDirectory(directory) || !Files.exists(directory.resolve("manifest.json"), LinkOption.NOFOLLOW_LINKS)) return obj("journalPresent", false);
            byte[] bytes = readRegular(directory.resolve("manifest.json"), MAX_MANIFEST);
            Map<String, Object> progress = mapper.readValue(bytes, new TypeReference<>() {});
            if (num(progress, "schemaVersion", 0) != 1 || !taskId.equals(str(progress, "taskId")) || !operationId.equals(str(progress, "operationId"))
                    || !Set.of("PREPARED", "APPLYING", "APPLYING_INDEX", "APPLIED", "REJECTED", "UNKNOWN").contains(str(progress, "state"))) throw conflict("原撤销日志身份或状态无效。");
            return obj("journalPresent", true, "sha256", hash(bytes), "content", bytes, "progress", progress);
        } catch (IOException exception) { throw conflict("原撤销进度日志无法完整读取。"); }
    }

    private Path blobPath(String digest, boolean create) throws IOException {
        if (!digest.matches("[0-9a-f]{64}")) throw conflict("无效内容对象标识。");
        Path directory = data.toRealPath().resolve("checkpoints").resolve("objects").resolve(digest.substring(0, 2));
        safeDirectory(directory, create); return directory.resolve(digest);
    }

    private Path manifestPath(String taskId, String id, boolean create) throws IOException {
        runtime.requireId(taskId); uuid(id);
        Path directory = data.toRealPath().resolve("checkpoints").resolve("tasks").resolve(taskId).resolve(id);
        safeDirectory(directory, create); return directory.resolve("manifest.json");
    }

    private Path journalPath(String taskId, String id) { runtime.requireId(taskId); uuid(id); try { Path directory = data.toRealPath().resolve("checkpoints").resolve("restores").resolve(taskId).resolve(id); safeDirectory(directory, true); return directory.resolve("manifest.json"); } catch (IOException exception) { throw conflict("撤销日志目录不可读取。"); } }

    private void uuid(String id) { if (id == null || !id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new ApiException(400, "无效检查点或操作编号。"); }

    private void safeDirectory(Path directory, boolean create) throws IOException {
        Path base = data.toRealPath();
        if (!directory.startsWith(base)) throw conflict("检查点目录越界。");
        Path current = base;
        for (Path component : base.relativize(directory)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS) && create) { Files.createDirectory(current); forceDirectory(current.getParent()); }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw conflict("检查点父目录缺失或被重定向。");
        }
    }

    private boolean optionalDirectory(Path directory) throws IOException {
        Path base = data.toRealPath();
        if (!directory.startsWith(base)) throw conflict("撤销日志目录越界。");
        Path current = base;
        for (Path component : base.relativize(directory)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) return false;
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw conflict("撤销日志父目录被重定向。");
        }
        return true;
    }

    private void createProjectParents(Path root, Path parent) throws IOException {
        Path current = root;
        for (Path component : root.relativize(parent)) {
            current = current.resolve(component);
            if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) { Files.createDirectory(current); forceDirectory(current.getParent()); }
            if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw conflict("撤销目标父目录发生变化。");
        }
    }

    private byte[] readRegular(Path path, int maximum) throws IOException {
        Map<String, Object> before = stamp(path);
        if (longValue(before, "bytes") > maximum) throw conflict("检查点内容超过完整读取上限。");
        byte[] bytes;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { bytes = input.readNBytes(maximum + 1); }
        if (bytes.length > maximum || bytes.length != longValue(before, "bytes") || !before.equals(stamp(path))) throw conflict("检查点读取期间原文件发生变化。");
        return bytes;
    }

    private void writeNew(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) { writeChannel(channel, bytes); }
        forceDirectory(path.getParent());
    }
    private void writeChannel(FileChannel channel, byte[] bytes) throws IOException { channel.truncate(0); channel.position(0); ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true); }
    private void saveProgress(Path path, Map<String, Object> progress) throws IOException { runtime.writeMetadataAtomic(path, mapper.writeValueAsBytes(progress)); forceDirectory(path.getParent()); }

    /**
     * POSIX 目录项在发布原引用前同步；Windows 文件由各次 force 保证完整落盘。
     */
    private void forceDirectory(Path path) throws IOException {
        if (!WINDOWS) try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { channel.force(true); }
    }
    private void checkCancelled(BooleanSupplier cancelled) { if (cancelled.getAsBoolean()) throw new CancellationException("撤销已取消，原记录继续保留。"); }
    private ApiException conflict(String message) { return new ApiException(409, message); }
    private long longValue(Map<String, Object> value, String field) { Object number = value.get(field); if (!(number instanceof Number)) throw conflict("检查点数值字段无效。"); return ((Number) number).longValue(); }
    private String hash(byte[] bytes) { try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
    private String gitHash(String format, byte[] bytes) { try { MessageDigest digest = MessageDigest.getInstance(format.equals("sha1") ? "SHA-1" : "SHA-256"); digest.update(("blob " + bytes.length + "\0").getBytes(java.nio.charset.StandardCharsets.US_ASCII)); return HexFormat.of().formatHex(digest.digest(bytes)); } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); } }
}
