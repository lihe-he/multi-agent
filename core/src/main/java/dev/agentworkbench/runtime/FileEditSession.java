package dev.agentworkbench.runtime;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFilePermission;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import static dev.agentworkbench.Json.*;

/**
 * 每次 API 执行的读回执绑定实际文件版本，过期或别的执行回执不能授权覆盖。
 */
final class FileEditSession {
    private static final int MAX_TEXT = 256_000;
    private static final long MAX_INSPECTION = 32L * 1024 * 1024;
    private static final ReentrantLock[] FILE_GATES = gates();
    private final Map<Path, ReadReceipt> reads = new LinkedHashMap<>();
    private final Set<String> conflicts = new LinkedHashSet<>();

    /**
     * 完整 UTF-8 读取成功后才发出可用于覆盖的回执。
     */
    Map<String, Object> read(Path path, String name, String operationId) throws IOException {
        ReentrantLock gate = gate(path); gate.lock();
        try {
            Captured captured = capture(path, true, name);
            String content;
            try {
                content = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(captured.content)).toString();
            } catch (java.nio.charset.CharacterCodingException exception) { throw new IllegalArgumentException("文件不是完整 UTF-8 文本；请用 inspect_file 读取版本，不能授权文本覆盖。"); }
            ReadReceipt receipt = remember(path, captured.version, true, operationId);
            conflicts.remove(name);
            return obj("path", name, "content", content, "readVersion", receipt.id, "fileVersion", captured.version.json(), "sourceOperationId", operationId);
        } finally { gate.unlock(); }
    }

    /**
     * 二进制文件只读元数据和有界摘要可授权删除，不能代替完整文本读取授权覆盖。
     */
    Map<String, Object> inspect(Path path, String name, String operationId) throws IOException {
        ReentrantLock gate = gate(path); gate.lock();
        try {
            Version version = Files.exists(path, LinkOption.NOFOLLOW_LINKS) ? capture(path, false, name).version : null;
            ReadReceipt receipt = remember(path, version, false, operationId);
            conflicts.remove(name);
            return obj("path", name, "exists", version != null, "readVersion", receipt.id, "fileVersion", facts(version), "contentRead", false, "sourceOperationId", operationId);
        } finally { gate.unlock(); }
    }

    /**
     * 先核对读回执，再核对临时内容完成后的现场；新文件创建不能替换已出现的文件。
     */
    Map<String, Object> write(Path path, String name, byte[] content, String expected, String operationId) throws IOException {
        if (content.length > MAX_TEXT) throw new IllegalArgumentException("单次写入超过 256 KB。");
        ReentrantLock gate = gate(path); gate.lock();
        try {
            boolean present = Files.exists(path, LinkOption.NOFOLLOW_LINKS);
            Version original = null;
            if (present) original = requireRead(path, name, expected, true);
            else requireAbsent(path, name, expected);
            ReadReceipt priorRead = reads.get(key(path));
            Files.createDirectories(path.getParent());
            Path temporary = Files.createTempFile(path.getParent(), ".agent-file-", ".tmp");
            try {
                try (FileChannel channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) {
                    ByteBuffer bytes = ByteBuffer.wrap(content);
                    while (bytes.hasRemaining()) channel.write(bytes);
                    channel.force(true);
                }
                if (original != null && original.permissions != null) Files.setPosixFilePermissions(temporary, original.permissions);
                String writtenKey = WorkspaceFileIdentity.key(temporary);
                if (original == null) {
                    try { Files.createLink(path, temporary); }
                    catch (FileAlreadyExistsException exception) { throw conflict(name, "新文件目标已出现，不能按 ABSENT 覆盖；请重新读取。"); }
                } else {
                    requireSame(original, capture(path, false, name).version, name);
                    Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
                }
                Version written;
                try {
                    written = capture(path, false, name).version;
                    if (!written.fileKey.equals(writtenKey) || !written.sha256.equals(hash(content))) throw new IOException("写入后原文件身份或内容已变化，效果保持 UNKNOWN。");
                } catch (IllegalArgumentException exception) { throw new IOException("写入效果已发生，但最终文件回执无法核验。", exception); }
                ReadReceipt receipt = remember(path, written, true, operationId);
                conflicts.remove(name);
                return obj("written", name, "path", name, "bytes", content.length, "created", original == null, "expectedReadVersion", expected,
                    "beforeVersion", facts(original), "fileVersion", written.json(), "readVersion", receipt.id, "sourceReadOperationId", priorRead == null ? "" : priorRead.operationId, "sourceOperationId", operationId);
            } finally { Files.deleteIfExists(temporary); }
        } finally { gate.unlock(); }
    }

    /**
     * 删除同样要求当前同文件的读回执；删除后出现的新文件保留并报告未知。
     */
    Map<String, Object> delete(Path path, String name, String expected, String operationId) throws IOException {
        ReentrantLock gate = gate(path); gate.lock();
        try {
            Version original = requireRead(path, name, expected, false);
            ReadReceipt priorRead = reads.get(key(path));
            Files.delete(path);
            if (Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw new IOException("删除后目标再次出现，保留当前文件并将原效果标记 UNKNOWN。");
            ReadReceipt receipt = remember(path, null, false, operationId);
            conflicts.remove(name);
            return obj("deleted", name, "path", name, "expectedReadVersion", expected, "beforeVersion", original.json(), "fileVersion", facts(null),
                "readVersion", receipt.id, "sourceReadOperationId", priorRead.operationId, "sourceOperationId", operationId);
        } finally { gate.unlock(); }
    }

    boolean hasConflicts() { return !conflicts.isEmpty(); }
    List<String> conflicts() { return List.copyOf(conflicts); }

    static String requestedVersion(Object value) {
        if (value == null || "".equals(value)) return "";
        if (value instanceof String text && ("ABSENT".equals(text) || text.matches("file-read-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"))) return text;
        return "INVALID";
    }

    static String argumentDigest(String arguments) { return hash(arguments.getBytes(StandardCharsets.UTF_8)); }

    private Version requireRead(Path path, String name, String expected, boolean contentRequired) throws IOException {
        ReadReceipt receipt = reads.get(key(path));
        if (receipt == null || !receipt.id.equals(expected) || receipt.version == null || (contentRequired && !receipt.contentRead)) {
            throw conflict(name, contentRequired ? "覆盖已有文件需要当前执行中该文件完整 read_file 返回的 expectedVersion。" : "删除文件需要当前执行中该文件 read_file 或 inspect_file 返回的 expectedVersion。");
        }
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) throw conflict(name, "读取后文件已消失，不能继续原修改；请重新读取。");
        Version current = capture(path, false, name).version;
        requireSame(receipt.version, current, name);
        return current;
    }

    private void requireAbsent(Path path, String name, String expected) {
        ReadReceipt receipt = reads.get(key(path));
        if (receipt != null && receipt.version != null) throw conflict(name, "原读取的文件已消失，不能改为新文件创建；请重新核对。");
        if (!expected.isBlank() && !"ABSENT".equals(expected) && (receipt == null || !receipt.id.equals(expected))) throw conflict(name, "新文件缺少当前执行的不存在回执，不能使用别的文件或执行版本。");
    }

    private void requireSame(Version expected, Version actual, String name) {
        if (!expected.equals(actual)) throw conflict(name, "文件在读取后已变化，保留当前内容；请重新读取后决定修改。");
    }

    private ReadReceipt remember(Path path, Version version, boolean contentRead, String operationId) {
        ReadReceipt receipt = new ReadReceipt("file-read-" + UUID.randomUUID(), version, contentRead, operationId);
        reads.put(key(path), receipt); return receipt;
    }

    private VersionConflict conflict(String name, String message) {
        conflicts.add(name); return new VersionConflict(message);
    }

    private Captured capture(Path path, boolean text, String name) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile()) throw new IllegalArgumentException("只允许读取或修改单个普通文件。");
        long limit = text ? MAX_TEXT : MAX_INSPECTION;
        if (before.size() > limit) throw new IllegalArgumentException(text ? "文件超过 256 KB，请通过搜索定位必要部分。" : "文件超过 32 MiB，无法取得本次有界版本回执。");
        String fileKey = WorkspaceFileIdentity.key(path);
        Set<PosixFilePermission> permissions = permissions(path);
        boolean executable = Files.isExecutable(path);
        MessageDigest digest = digest();
        ByteArrayOutputStream content = text ? new ByteArrayOutputStream((int) before.size()) : null;
        long size = 0;
        try (var input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] buffer = new byte[8192];
            for (int count; (count = input.read(buffer)) >= 0;) {
                size += count;
                if (size > limit) throw conflict(name, "文件读取期间超过版本回执上限，请重新核对。");
                digest.update(buffer, 0, count);
                if (content != null) content.write(buffer, 0, count);
            }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!after.isRegularFile() || size != before.size() || size != after.size() || !before.lastModifiedTime().equals(after.lastModifiedTime())
                || !fileKey.equals(WorkspaceFileIdentity.key(path)) || executable != Files.isExecutable(path) || !Objects.equals(permissions, permissions(path))) {
            throw conflict(name, "文件读取期间身份或内容属性已变化，请重新读取。");
        }
        return new Captured(new Version(HexFormat.of().formatHex(digest.digest()), fileKey, size, after.lastModifiedTime(), executable, permissions), content == null ? null : content.toByteArray());
    }

    private static Set<PosixFilePermission> permissions(Path path) throws IOException {
        try { return Set.copyOf(Files.getPosixFilePermissions(path, LinkOption.NOFOLLOW_LINKS)); }
        catch (UnsupportedOperationException exception) { return null; }
    }

    private static Map<String, Object> facts(Version version) { return version == null ? obj("exists", false) : version.json(); }
    private static Path key(Path path) { return path.toAbsolutePath().normalize(); }
    private static ReentrantLock gate(Path path) { return FILE_GATES[Math.floorMod(key(path).hashCode(), FILE_GATES.length)]; }
    private static ReentrantLock[] gates() {
        ReentrantLock[] result = new ReentrantLock[64];
        for (int index = 0; index < result.length; index++) result[index] = new ReentrantLock();
        return result;
    }
    private static MessageDigest digest() {
        try { return MessageDigest.getInstance("SHA-256"); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 文件版本不可用。", exception); }
    }
    private static String hash(byte[] content) { return HexFormat.of().formatHex(digest().digest(content)); }

    /**
     * 只在实际文件效果发生前抛出，调用层保留拒绝并要求处理原冲突。
     */
    static final class VersionConflict extends IllegalArgumentException {
        VersionConflict(String message) { super(message); }
    }

    private record ReadReceipt(String id, Version version, boolean contentRead, String operationId) { }
    private record Captured(Version version, byte[] content) { }

    /**
     * 内容摘要与文件身份、时间及权限共同表示此读回执观察到的文件版本。
     */
    private record Version(String sha256, String fileKey, long bytes, FileTime modifiedAt, boolean executable, Set<PosixFilePermission> permissions) {
        Map<String, Object> json() {
            Map<String, Object> result = obj("exists", true, "sha256", sha256, "fileKey", fileKey, "bytes", bytes, "modifiedAt", modifiedAt.toString(), "executable", executable);
            if (permissions != null) result.put("posixPermissions", permissions.stream().map(Enum::name).sorted().toList());
            return result;
        }
    }
}
