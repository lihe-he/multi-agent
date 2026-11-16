package dev.agentworkbench.runtime;

import dev.agentworkbench.ApiException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Map;
import java.util.Objects;
import static dev.agentworkbench.Json.*;

/**
 * 检查点复用同次 Git 目录回执，按原行政文件核对 worktree 双向关联、公共目录和锁。
 */
final class WorkspaceCheckpointOwnership {
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    /**
     * 只接受实际普通目录别名；Windows 短路径不能授权重解析目录。
     */
    static boolean matches(Path expected, String workspace) {
        try {
            Path supplied = Path.of(workspace).toAbsolutePath().normalize();
            if (!supplied.toRealPath().equals(expected)) return false;
            if (supplied.equals(expected)) return true;
            if (!WINDOWS) return false;
            Path current = supplied.getRoot();
            for (Path component : supplied) {
                current = current.resolve(component);
                BasicFileAttributes attributes = Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther()) return false;
            }
            return supplied.toRealPath().equals(expected);
        } catch (IOException | RuntimeException exception) { return false; }
    }

    /**
     * 捕获前后分别调用，当前 Git 结果和原选择记录都不能由缓存替代。
     */
    static void verify(Path root, String taskId, Map<String, Object> saved, boolean legacy, Path directory, Path common, String head) throws IOException {
        realDirectory(root); realDirectory(directory); realDirectory(common);
        if (!taskId.equals(str(saved, "taskId")) || !"READY".equals(str(saved, "state"))) throw conflict();
        if (!gitDirectory(root).equals(directory) || !commonDirectory(directory).equals(common)) throw conflict();
        if (legacy) {
            if (!directory.equals(root.resolve(".git")) || !common.equals(directory)) throw conflict();
            return;
        }
        Map<String, Object> ownership = map(map(saved, "facts"), "workspaceOwnership");
        if (!taskId.equals(str(ownership, "taskId")) || !directory.toString().equals(str(ownership, "gitDirectory"))
                || !common.toString().equals(str(ownership, "commonDirectory"))) throw conflict();
        switch (str(ownership, "strategy")) {
            case "CLONE" -> { if (!directory.equals(root.resolve(".git")) || !common.equals(directory)) throw conflict(); }
            case "WORKTREE" -> {
                Path source = Path.of(str(ownership, "sourcePath")).toAbsolutePath().normalize(); realDirectory(source);
                Path sourceDirectory = gitDirectory(source);
                if (!commonDirectory(sourceDirectory).equals(common)) throw conflict();
                if (!sourceDirectory.equals(common)) backlink(source, sourceDirectory, common);
                backlink(root, directory, common);
                if (str(ownership, "lockReason").isBlank() || !line(directory.resolve("locked")).equals(str(ownership, "lockReason"))
                        || !line(directory.resolve("HEAD")).equals(head)) throw conflict();
            }
            default -> throw conflict();
        }
    }

    private static void backlink(Path root, Path directory, Path common) throws IOException {
        realDirectory(common.resolve("worktrees"));
        if (!directory.getParent().equals(common.resolve("worktrees"))
                || !resolvedPath(directory, line(directory.resolve("gitdir"))).equals(root.resolve(".git"))) throw conflict();
    }

    private static Path gitDirectory(Path root) throws IOException {
        Path marker = root.resolve(".git");
        if (Files.isDirectory(marker, LinkOption.NOFOLLOW_LINKS)) { realDirectory(marker); return marker; }
        String value = line(marker);
        if (!value.startsWith("gitdir: ") || value.length() == 8) throw conflict();
        Path directory = resolvedPath(root, value.substring(8)); realDirectory(directory); return directory;
    }

    private static Path commonDirectory(Path directory) throws IOException {
        realDirectory(directory);
        Path file = directory.resolve("commondir");
        Path common = Files.exists(file, LinkOption.NOFOLLOW_LINKS) ? resolvedPath(directory, line(file)) : directory;
        realDirectory(common); return common;
    }

    private static Path resolvedPath(Path base, String text) throws IOException {
        Path path = base.resolve(Path.of(text)).toAbsolutePath().normalize();
        if (!path.toRealPath().equals(path)) throw conflict();
        return path;
    }

    private static void realDirectory(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isDirectory() || attributes.isSymbolicLink() || attributes.isOther() || !path.toRealPath().equals(path)) throw conflict();
    }

    /**
     * 元数据只读取有界单行 UTF-8，前后核对原文件身份和时间。
     */
    private static String line(Path path) throws IOException {
        BasicFileAttributes before = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!before.isRegularFile() || before.isSymbolicLink() || before.isOther() || before.size() > 32_768 || !path.toRealPath().equals(path)) throw conflict();
        String key = WorkspaceFileIdentity.key(path); ByteBuffer bytes = ByteBuffer.allocate(32_769);
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            while (bytes.hasRemaining() && channel.read(bytes) != -1) { }
        }
        BasicFileAttributes after = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (bytes.position() != before.size() || !after.isRegularFile() || after.isSymbolicLink() || after.isOther()
                || before.size() != after.size() || !Objects.equals(before.lastModifiedTime(), after.lastModifiedTime())
                || !key.equals(WorkspaceFileIdentity.key(path)) || !path.toRealPath().equals(path)) throw conflict();
        bytes.flip(); String value = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(bytes).toString();
        if (value.endsWith("\n")) value = value.substring(0, value.length() - 1);
        if (value.endsWith("\r")) value = value.substring(0, value.length() - 1);
        if (value.isBlank() || value.indexOf('\n') >= 0 || value.indexOf('\r') >= 0 || value.indexOf('\0') >= 0) throw conflict();
        return value;
    }

    private static ApiException conflict() { return new ApiException(409, "原检查点 Git 目录、双向关联或锁定归属发生变化。"); }
}
