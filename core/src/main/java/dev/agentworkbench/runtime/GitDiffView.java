package dev.agentworkbench.runtime;

import dev.agentworkbench.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.Semaphore;
import java.util.regex.Pattern;
import static dev.agentworkbench.Json.*;

/**
 * 固定 Git 基线与捕获文件摘要组成只读视图，单文件文本来自已核验的字节副本。
 */
final class GitDiffView {
    static final int MAX_FILE_BYTES = 256_000;
    static final int MAX_CHANGED_FILES = 2_000;
    static final int MAX_ROWS = 5_000;
    private static final int MAX_LEGACY_FILES = 20;
    private static final int MAX_LEGACY_TEXT = 512_000;
    private static final Pattern HUNK = Pattern.compile("^@@ -(\\d+)(?:,(\\d+))? \\+(\\d+)(?:,(\\d+))? @@.*$");
    private final WorkspaceRuntime runtime;
    private final Path data;
    /**
     * 预览最多同时占用两个读取者，不占用根模型预算或排他写入归属。
     */
    private final Semaphore readers = new Semaphore(2);

    GitDiffView(WorkspaceRuntime runtime, Path data) {
        this.runtime = runtime; this.data = data.toAbsolutePath().normalize();
    }

    Map<String, Object> read(String workspace, String path, String expectedVersion, String expectedBase) {
        return read(workspace, path, expectedVersion, expectedBase, false);
    }

    Map<String, Object> legacy(String workspace) { return read(workspace, "", "", "", true); }

    private Map<String, Object> read(String workspace, String path, String expectedVersion, String expectedBase, boolean legacy) {
        if (!path.isEmpty() && (path.indexOf('\0') >= 0 || path.startsWith("/") || path.startsWith("\\") || path.matches("^[A-Za-z]:.*")
            || List.of(path.split("/", -1)).stream().anyMatch(segment -> segment.equals("..") || segment.equals(".") || segment.isEmpty())))
            throw new ApiException(400, "文件路径必须是变更列表中的相对路径。");
        if (!expectedVersion.isEmpty() && !expectedVersion.matches("[0-9a-f]{64}")) throw new ApiException(400, "无效内容版本。");
        if (!expectedBase.isEmpty() && !expectedBase.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw new ApiException(400, "无效 Git 基线。");
        if (!readers.tryAcquire()) throw new ApiException(429, "已有两个差异预览正在读取，请稍后刷新。");
        try {
            if (workspace.isBlank()) return legacy ? obj("diff", "", "version", "", "workspace", "", "baseline", "", "truncated", false, "consistency", "CAPTURED_FILE_DIGESTS")
                : obj("protocolVersion", 1, "workspace", "", "version", "", "baseline", "", "files", List.of(), "selectedPath", "", "preview", null,
                    "complete", true, "consistency", "CAPTURED_FILE_DIGESTS", "limits", limits());
            Path root = Path.of(workspace).toAbsolutePath().normalize();
            String baseline = runtime.git(root, "rev-parse", "HEAD").trim();
            if (!baseline.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw new ApiException(409, "工作目录没有可读取的 Git 基线。");
            if (!expectedBase.isEmpty() && !expectedBase.equals(baseline)) throw changed();
            if (!runtime.git(root, "ls-files", "--unmerged", "-z").isEmpty()) throw new ApiException(409, "工作目录仍有未解决的 Git 合并冲突，请处理后刷新。");
            WorkspaceContentSnapshot snapshot = runtime.contentSnapshot(workspace);
            if (!expectedVersion.isEmpty() && !expectedVersion.equals(snapshot.version())) throw changed();
            Map<String, Entry> before = tree(root, baseline);
            List<Map<String, Object>> files = changes(snapshot, before);
            String selected = files.stream().anyMatch(file -> path.equals(str(file, "path"))) ? path : "";
            if (!path.isEmpty() && selected.isEmpty()) throw new ApiException(404, "该文件已不在本次变更列表中，请刷新。");
            if (selected.isEmpty() && !files.isEmpty()) selected = str(files.getFirst(), "path");
            Map<String, Object> legacyResult = legacy ? legacy(workspace, root, snapshot, before, files, baseline) : null;
            Map<String, Object> preview = legacy || selected.isEmpty() ? null : preview(workspace, root, snapshot, before.get(selected), snapshot.files().get(selected), selected);
            if (!runtime.fingerprint(workspace).equals(snapshot.version()) || !runtime.git(root, "rev-parse", "HEAD").trim().equals(baseline)
                    || !runtime.git(root, "ls-files", "--unmerged", "-z").isEmpty()) throw changed();
            if (legacy) return legacyResult;
            return obj("protocolVersion", 1, "workspace", workspace, "version", snapshot.version(), "baseline", baseline, "files", files, "selectedPath", selected,
                "preview", preview, "complete", true, "consistency", "CAPTURED_FILE_DIGESTS", "limits", limits());
        } finally { readers.release(); }
    }

    private Map<String, Object> limits() { return obj("fileBytes", MAX_FILE_BYTES, "changedFiles", MAX_CHANGED_FILES, "rows", MAX_ROWS, "parallelReaders", 2); }
    private ApiException changed() { return new ApiException(409, "工作目录或 Git 基线在差异读取期间发生变化，请刷新后查看。"); }
    private record Entry(String mode, String objectId) {}

    private Map<String, Object> legacy(String workspace, Path root, WorkspaceContentSnapshot snapshot, Map<String, Entry> before, List<Map<String, Object>> files, String baseline) {
        StringBuilder diff = new StringBuilder(); boolean incomplete = false; int displayed = 0;
        for (Map<String, Object> file : files) {
            if (displayed == MAX_LEGACY_FILES) break;
            String path = str(file, "path");
            Map<String, Object> preview = preview(workspace, root, snapshot, before.get(path), snapshot.files().get(path), path);
            String chunk = legacyFile(file, preview);
            if (diff.length() + chunk.length() > MAX_LEGACY_TEXT - 120) break;
            diff.append(chunk); displayed++;
            if (!bool(preview, "complete", false)) incomplete = true;
        }
        if (displayed < files.size()) { incomplete = true; diff.append("\n[其余 ").append(files.size() - displayed).append(" 个文件未提供文本预览，请使用逐文件差异视图。]\n"); }
        return obj("diff", diff.toString(), "version", snapshot.version(), "workspace", workspace, "baseline", baseline, "truncated", incomplete,
            "consistency", "CAPTURED_FILE_DIGESTS", "changedFiles", files.size(), "displayedFiles", displayed);
    }

    private String legacyFile(Map<String, Object> file, Map<String, Object> preview) {
        String path = str(file, "path"), status = str(file, "status"), before = quote("a/" + path), after = quote("b/" + path);
        StringBuilder output = new StringBuilder("diff --git ").append(before).append(' ').append(after).append('\n');
        if (status.equals("ADDED")) output.append("new file mode ").append(str(file, "newMode")).append('\n');
        else if (status.equals("DELETED")) output.append("deleted file mode ").append(str(file, "oldMode")).append('\n');
        else if (!str(file, "oldMode").equals(str(file, "newMode"))) output.append("old mode ").append(str(file, "oldMode")).append("\nnew mode ").append(str(file, "newMode")).append('\n');
        if (!str(preview, "type").equals("TEXT")) {
            if (!str(preview, "type").equals("MODE_ONLY")) output.append('[').append(str(preview, "message")).append("]\n");
            return output.toString();
        }
        output.append("--- ").append(status.equals("ADDED") ? "/dev/null" : before).append("\n+++ ").append(status.equals("DELETED") ? "/dev/null" : after).append('\n');
        for (Map<String, Object> hunk : objects(preview.get("hunks"))) {
            output.append("@@ -").append(hunk.get("oldStart")).append(',').append(hunk.get("oldCount")).append(" +").append(hunk.get("newStart")).append(',').append(hunk.get("newCount")).append(" @@\n");
            for (Map<String, Object> line : objects(hunk.get("lines"))) {
                String kind = str(line, "kind"); output.append(kind.equals("ADD") ? '+' : kind.equals("REMOVE") ? '-' : ' ').append(str(line, "content")).append('\n');
                if (bool(line, "noNewline", false)) output.append("\\ No newline at end of file\n");
            }
        }
        return output.toString();
    }

    private String quote(String path) { return "\"" + path.replace("\\", "\\\\").replace("\"", "\\\"").replace("\t", "\\t").replace("\r", "\\r").replace("\n", "\\n") + "\""; }

    private Map<String, Entry> tree(Path root, String baseline) {
        Map<String, Entry> entries = new TreeMap<>();
        for (String record : runtime.git(root, "ls-tree", "-r", "-z", "--full-tree", baseline).split("\0")) {
            if (record.isEmpty()) continue;
            int separator = record.indexOf('\t');
            if (separator < 0) throw new IllegalStateException("Git 基线文件清单不完整。");
            String[] fields = record.substring(0, separator).split(" ");
            if (fields.length != 3 || !fields[2].matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")) throw new IllegalStateException("Git 基线文件清单无效。");
            if (!fields[1].equals("blob") || !Set.of("100644", "100755").contains(fields[0])) throw new ApiException(400, "当前预览不支持 Git 子模块或符号链接。");
            String path = record.substring(separator + 1);
            if (path.isBlank()) throw new ApiException(400, "当前内容版本不支持只有空白字符的 Git 文件名。");
            entries.put(path, new Entry(fields[0], fields[2]));
        }
        return entries;
    }

    private List<Map<String, Object>> changes(WorkspaceContentSnapshot snapshot, Map<String, Entry> before) {
        Set<String> paths = new TreeSet<>(before.keySet()); paths.addAll(snapshot.files().keySet());
        List<Map<String, Object>> changes = new ArrayList<>();
        for (String path : paths) {
            Entry old = before.get(path); WorkspaceContentSnapshot.File current = snapshot.files().get(path);
            boolean exists = current != null && current.exists();
            if (!exists && old == null) continue;
            if (exists && old != null && old.objectId.equals(current.gitBlob()) && old.mode.equals(current.effectiveMode())) continue;
            if (changes.size() == MAX_CHANGED_FILES) throw new ApiException(413, "变更文件超过 2000 项，不能以截断清单展示完整差异。");
            boolean modeOnly = exists && old != null && old.objectId.equals(current.gitBlob());
            changes.add(obj("path", path, "status", old == null ? "ADDED" : !exists ? "DELETED" : "MODIFIED", "modeOnly", modeOnly,
                "oldMode", old == null ? "000000" : old.mode, "newMode", exists ? current.effectiveMode() : "000000",
                "bytes", exists ? current.bytes() : 0, "sha256", exists ? current.sha256() : "", "gitBlob", exists ? current.gitBlob() : "",
                "baseBlob", old == null ? "" : old.objectId, "excluded", runtime.previewExcluded(path)));
        }
        return changes;
    }

    private Map<String, Object> preview(String workspace, Path root, WorkspaceContentSnapshot snapshot, Entry old, WorkspaceContentSnapshot.File current, String path) {
        boolean exists = current != null && current.exists();
        Map<String, Object> facts = obj("path", path, "version", snapshot.version(), "baseBlob", old == null ? "" : old.objectId,
            "sha256", exists ? current.sha256() : "", "beforeBytes", old == null ? 0L : null, "afterBytes", exists ? current.bytes() : 0L);
        if (runtime.previewExcluded(path)) return state(facts, "EXCLUDED", "内部或敏感文件不提供差异文本预览。", "POLICY", List.of());
        if (exists && old != null && old.objectId.equals(current.gitBlob())) { facts.put("beforeBytes", current.bytes()); return state(facts, "MODE_ONLY", "文件内容未变化，只有执行位变化。", "", List.of()); }
        long oldSize = old == null ? 0 : Long.parseLong(runtime.git(root, "cat-file", "-s", old.objectId).trim());
        if (oldSize < 0) throw new IllegalStateException("Git 文件大小无效。");
        facts.put("beforeBytes", oldSize);
        if (oldSize > MAX_FILE_BYTES || exists && current.bytes() > MAX_FILE_BYTES) return state(facts, "LIMIT", "文件超过差异文本预览大小上限，可在工作目录中查看。", "FILE_BYTES", List.of());
        byte[] before = old == null ? new byte[0] : runtime.readGitBlob(root, old.objectId, (int) oldSize);
        if (old != null && (before.length != oldSize || !blob(before, snapshot.objectFormat()).equals(old.objectId))) throw new IllegalStateException("Git 原始文件字节与固定基线不符，不能生成差异预览。");
        byte[] after = exists ? runtime.readPreviewFile(workspace, current) : new byte[0];
        if (!text(before) || !text(after)) return state(facts, "BINARY", "该文件不是可完整解码的 UTF-8 文本，保留大小与版本信息。", "", List.of());
        if (before.length == 0 && after.length == 0) return state(facts, "EMPTY", exists ? "新增空文件。" : "移除空文件。", "", List.of());
        Copies copies = null; Throwable original = null;
        try {
            Files.createDirectories(data);
            Path previews = data.toRealPath().resolve("diff-previews");
            if (!Files.exists(previews, LinkOption.NOFOLLOW_LINKS)) try { Files.createDirectory(previews); }
            catch (FileAlreadyExistsException ignored) { }
            String rootKey = directoryKey(previews, null);
            Path directory = Files.createDirectory(previews.resolve(UUID.randomUUID().toString()));
            copies = new Copies(previews, rootKey, directory, directoryKey(directory, null), new LinkedHashMap<>());
            Path first = writeCopy(copies, "before", before), second = writeCopy(copies, "after", after);
            Map<String, Object> compared = runtime.comparePreviewBytes(first, second);
            int exit = ((Number) compared.get("exitCode")).intValue(); String status = str(compared, "status");
            if (!Set.of(0, 1).contains(exit) || !Set.of("SUCCEEDED", "FAILED").contains(status)) throw new IllegalStateException("差异比较未完整收尾，不能展示本次输出。");
            if (bool(compared, "truncated", false)) return state(facts, "LIMIT", "差异输出超过预览上限，未展示截断片段。", "PATCH_BYTES", List.of());
            return parse(facts, str(compared, "stdout"), before, after);
        } catch (IOException exception) {
            original = exception; throw new IllegalStateException("差异字节副本未完整保存。", exception);
        } catch (RuntimeException exception) { original = exception; throw exception; }
        finally {
            if (copies != null) try { cleanup(copies); }
            catch (IOException | RuntimeException failure) {
                if (original != null) original.addSuppressed(failure);
                else throw new IllegalStateException("差异临时文件未完整清理。", failure);
            }
        }
    }

    private Map<String, Object> state(Map<String, Object> facts, String type, String message, String limit, List<Map<String, Object>> hunks) {
        Map<String, Object> result = new LinkedHashMap<>(facts);
        result.putAll(obj("type", type, "message", message, "limit", limit, "complete", Set.of("TEXT", "MODE_ONLY", "EMPTY").contains(type), "hunks", hunks));
        return result;
    }

    private Map<String, Object> parse(Map<String, Object> facts, String patch, byte[] before, byte[] after) {
        List<Map<String, Object>> hunks = new ArrayList<>(); List<Map<String, Object>> lines = null;
        int oldLine = 0, newLine = 0, oldRemaining = 0, newRemaining = 0, rows = 0, additions = 0, deletions = 0;
        String[] values = patch.split("\n", -1);
        for (int index = 0; index < values.length; index++) {
            String value = values[index]; var header = HUNK.matcher(value);
            if (header.matches()) {
                if (oldRemaining != 0 || newRemaining != 0) return unsupported(facts);
                oldLine = Integer.parseInt(header.group(1)); newLine = Integer.parseInt(header.group(3));
                oldRemaining = header.group(2) == null ? 1 : Integer.parseInt(header.group(2)); newRemaining = header.group(4) == null ? 1 : Integer.parseInt(header.group(4));
                lines = new ArrayList<>(); hunks.add(obj("oldStart", oldLine, "oldCount", oldRemaining, "newStart", newLine, "newCount", newRemaining, "lines", lines));
                continue;
            }
            if (lines == null) continue;
            if (value.startsWith("\\ ")) {
                if (lines.isEmpty()) return unsupported(facts);
                lines.getLast().put("noNewline", true); continue;
            }
            if (index == values.length - 1 && value.isEmpty()) continue;
            if (value.isEmpty() || !Set.of(' ', '+', '-').contains(value.charAt(0))) return unsupported(facts);
            if (++rows > MAX_ROWS) return state(facts, "LIMIT", "差异超过 5000 行，未展示截断片段。", "ROWS", List.of());
            char kind = value.charAt(0); Integer left = null, right = null;
            if (kind != '+') { left = oldLine++; oldRemaining--; }
            if (kind != '-') { right = newLine++; newRemaining--; }
            if (oldRemaining < 0 || newRemaining < 0) return unsupported(facts);
            if (kind == '+') additions++; if (kind == '-') deletions++;
            lines.add(obj("kind", kind == '+' ? "ADD" : kind == '-' ? "REMOVE" : "CONTEXT", "content", value.substring(1), "oldLine", left, "newLine", right, "noNewline", false));
        }
        if (oldRemaining != 0 || newRemaining != 0 || hunks.isEmpty() || !matchesBytes(hunks, textLines(before), textLines(after))) return unsupported(facts);
        Map<String, Object> result = state(facts, "TEXT", "", "", hunks); result.put("additions", additions); result.put("deletions", deletions); result.put("rows", rows); return result;
    }

    private record TextLines(List<String> lines, boolean newline) {}

    private TextLines textLines(byte[] bytes) {
        if (bytes.length == 0) return new TextLines(List.of(), false);
        String value = new String(bytes, StandardCharsets.UTF_8); boolean newline = value.endsWith("\n");
        List<String> lines = new ArrayList<>(List.of(value.split("\n", -1)));
        if (newline) lines.removeLast();
        return new TextLines(lines, newline);
    }

    /**
     * 行坐标、末行换行与未展示区间均匹配已捕获字节，完整差异必须能重建新文件。
     */
    private boolean matchesBytes(List<Map<String, Object>> hunks, TextLines before, TextLines after) {
        int oldCursor = 0, newCursor = 0;
        for (Map<String, Object> hunk : hunks) {
            int oldStart = ((Number) hunk.get("oldStart")).intValue() - (((Number) hunk.get("oldCount")).intValue() == 0 ? 0 : 1);
            int newStart = ((Number) hunk.get("newStart")).intValue() - (((Number) hunk.get("newCount")).intValue() == 0 ? 0 : 1);
            if (oldStart < oldCursor || newStart < newCursor || oldStart > before.lines.size() || newStart > after.lines.size() || oldStart - oldCursor != newStart - newCursor) return false;
            while (oldCursor < oldStart) if (!sameLine(before, oldCursor++, after, newCursor++)) return false;
            for (Map<String, Object> line : objects(hunk.get("lines"))) {
                String kind = str(line, "kind"), content = str(line, "content"); boolean noNewline = bool(line, "noNewline", false);
                if (!kind.equals("ADD") && (!Integer.valueOf(oldCursor + 1).equals(line.get("oldLine")) || !matchesLine(before, oldCursor++, content, noNewline))) return false;
                if (!kind.equals("REMOVE") && (!Integer.valueOf(newCursor + 1).equals(line.get("newLine")) || !matchesLine(after, newCursor++, content, noNewline))) return false;
            }
        }
        if (before.lines.size() - oldCursor != after.lines.size() - newCursor) return false;
        while (oldCursor < before.lines.size()) if (!sameLine(before, oldCursor++, after, newCursor++)) return false;
        return newCursor == after.lines.size();
    }

    private boolean sameLine(TextLines before, int oldLine, TextLines after, int newLine) {
        return matchesLine(after, newLine, before.lines.get(oldLine), oldLine == before.lines.size() - 1 && !before.newline);
    }

    private boolean matchesLine(TextLines text, int index, String content, boolean noNewline) {
        return index >= 0 && index < text.lines.size() && text.lines.get(index).equals(content) && noNewline == (index == text.lines.size() - 1 && !text.newline);
    }

    private Map<String, Object> unsupported(Map<String, Object> facts) { return state(facts, "UNSUPPORTED", "本次 Git 输出不能完整解析为文本差异，未展示不完整行。", "FORMAT", List.of()); }
    private boolean text(byte[] bytes) {
        for (byte value : bytes) if (value == 0) return false;
        try { StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)); return true; }
        catch (CharacterCodingException ignored) { return false; }
    }
    private String blob(byte[] bytes, String format) {
        try {
            MessageDigest digest = MessageDigest.getInstance(format.equals("sha1") ? "SHA-1" : "SHA-256");
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Copies(Path root, String rootKey, Path directory, String directoryKey, Map<String, String> files) {}

    private String directoryKey(Path path, String expected) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther() || !path.toRealPath().equals(path))
            throw new IllegalStateException("差异临时目录包含链接、重解析点或归属变化，保留原目录。");
        String key;
        try { key = WorkspaceFileIdentity.directoryKey(path); }
        catch (IllegalArgumentException exception) { throw new IllegalStateException("差异临时目录身份无法核验。", exception); }
        if (expected != null && !expected.equals(key)) throw new IllegalStateException("差异临时目录归属已变化，保留原目录。");
        return key;
    }

    private void requireCopies(Copies copies) throws IOException {
        if (!copies.directory.getParent().equals(copies.root) || !copies.directory.getFileName().toString().matches("[0-9a-f-]{36}"))
            throw new IllegalStateException("差异临时目录归属无法核验，保留原目录。");
        directoryKey(copies.root, copies.rootKey); directoryKey(copies.directory, copies.directoryKey);
    }

    private Path writeCopy(Copies copies, String name, byte[] bytes) throws IOException {
        requireCopies(copies);
        Path path = copies.directory.resolve(name);
        try (var channel = Files.newByteChannel(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            String key = WorkspaceFileIdentity.key(path);
            copies.files.put(name, key);
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
        }
        return path;
    }

    /**
     * 只删除本次创建的两个普通副本及其空目录，先核对绝对路径和目录归属。
     */
    private void cleanup(Copies copies) throws IOException {
        requireCopies(copies);
        for (var file : copies.files.entrySet()) {
            BasicFileAttributes attributes = Files.readAttributes(copies.directory.resolve(file.getKey()), BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile() || attributes.isSymbolicLink() || attributes.isOther() || !file.getValue().equals(WorkspaceFileIdentity.key(copies.directory.resolve(file.getKey()))))
                throw new IllegalStateException("差异字节副本已被替换，保留原文件。");
        }
        for (String name : copies.files.keySet()) { requireCopies(copies); Files.delete(copies.directory.resolve(name)); }
        requireCopies(copies); Files.delete(copies.directory);
    }
}
