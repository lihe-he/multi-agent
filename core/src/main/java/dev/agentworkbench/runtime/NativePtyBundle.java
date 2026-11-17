package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 固定发布包的原生字节只提取到本进程私有目录，加载前重新核对全部文件。
 */
public final class NativePtyBundle {
    private static final String PREFIX = "resources/com/pty4j/native/";
    private static final String VERSION = "0.13.4";
    private static final String RELEASE_SHA256 = "bc1bb1855a20a5f99583890ed0ed23165f2a7c5b64be037db2c46fc5533edccc";
    private static NativePtyBundle active;
    private final Path data;
    private final Path directory;
    private final String inventorySha256;
    private final String ownerSha256;
    private final List<Map<String, Object>> files;

    private NativePtyBundle(Path data, Path directory, String inventorySha256, String ownerSha256, List<Map<String, Object>> files) {
        this.data = data; this.directory = directory; this.inventorySha256 = inventorySha256; this.ownerSha256 = ownerSha256; this.files = List.copyOf(files);
    }

    /**
     * 一次 Worker 会话共用已核验目录；不清理、覆盖或采用另一进程的旧提取目录。
     */
    static synchronized NativePtyBundle current(Path dataDirectory) throws IOException {
        Path data = dataDirectory.toRealPath();
        if (active == null) {
            NativePtyBundle candidate = extract(data); candidate.activate(); active = candidate;
        }
        if (!active.data.equals(data)) throw new IOException("原生库不能在同一 Worker 内改用另一数据归属");
        if (!active.directory.toString().equals(System.getProperty("pty4j.preferred.native.folder"))) throw new IOException("原生库加载目录已离开原 Worker 归属");
        active.verify(); return active;
    }

    static NativePtyBundle extract(Path dataDirectory) throws IOException {
        Path data = dataDirectory.toRealPath();
        Path parent = checkedDirectory(data, "terminal-native", true);
        boolean windows = windows();
        Path directory = windows ? Files.createTempDirectory(parent, "worker-" + ProcessHandle.current().pid() + "-")
            : Files.createTempDirectory(parent, "worker-" + ProcessHandle.current().pid() + "-", PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
        if (!directory.toRealPath().equals(directory)) throw new IOException("原生目录解析目标变化");
        byte[] inventory;
        try (InputStream input = NativePtyBundle.class.getResourceAsStream("/terminal-native-inventory.json")) {
            if (input == null) throw new IOException("发行物缺少固定原生字节清单");
            inventory = input.readNBytes(32_769); if (inventory.length > 32_768) throw new IOException("原生清单超过读取边界");
        }
        ObjectMapper mapper = new ObjectMapper();
        Map<String, Object> manifest = mapper.readerFor(new TypeReference<Map<String, Object>>() { })
            .with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(inventory);
        if (num(manifest, "protocolVersion", 0) != 1 || !VERSION.equals(str(manifest, "version")) || !RELEASE_SHA256.equals(str(manifest, "jarSha256")))
            throw new IOException("原生清单不属于固定已核验发布包");
        String platform = platform(); List<Map<String, Object>> copied = new ArrayList<>(); var names = new java.util.HashSet<String>();
        for (Map<String, Object> file : objects(manifest.get("native"))) {
            String entry = str(file, "name");
            if (!entry.startsWith(PREFIX) || !entry.substring(PREFIX.length()).matches("[A-Za-z0-9_.-]+(?:/[A-Za-z0-9_.-]+)+")
                    || List.of(entry.split("/")).stream().anyMatch(part -> part.equals(".") || part.equals("..")) || !names.add(entry)) throw new IOException("原生清单包含无效或重复路径");
            if (!entry.substring(PREFIX.length()).startsWith(platform + "/")) continue;
            long size = number(file, "bytes"); String digest = str(file, "sha256");
            if (size < 1 || size > 4 * 1024 * 1024 || !digest.matches("[0-9a-f]{64}")) throw new IOException("原生文件摘要或长度无效");
            Path target = directory.resolve(entry.substring(PREFIX.length())).normalize();
            if (!target.startsWith(directory) || target.equals(directory)) throw new IOException("原生文件路径越界");
            Path current = directory;
            for (Path component : directory.relativize(target.getParent())) current = checkedDirectory(current, component.toString(), true);
            byte[] bytes;
            try (InputStream input = NativePtyBundle.class.getClassLoader().getResourceAsStream(entry)) {
                if (input == null) throw new IOException("固定发布包缺少原生文件");
                bytes = input.readNBytes((int) size + 1);
            }
            if (bytes.length != size || !digest.equals(hash(bytes))) throw new IOException("发布包原生字节与固定清单不一致");
            save(target, bytes);
            if (!windows && target.getFileName().toString().equals("pty4j-unix-spawn-helper"))
                Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rwx------"));
            copied.add(obj("path", entry.substring(PREFIX.length()), "bytes", size, "sha256", digest));
        }
        if (copied.isEmpty()) throw new IOException("当前系统和架构缺少已核验的原生库");
        save(directory.resolve("inventory.json"), inventory);
        byte[] owner = mapper.writeValueAsBytes(obj("protocolVersion", 1, "version", VERSION, "dataDirectory", data.toString(),
            "pid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString(), "createdAt", now(), "files", copied));
        save(directory.resolve("owner.json"), owner);
        NativePtyBundle bundle = new NativePtyBundle(data, directory, hash(inventory), hash(owner), copied); bundle.verify(); return bundle;
    }

    public Map<String, Object> facts() {
        return obj("version", VERSION, "releasedJarSha256", RELEASE_SHA256, "inventorySha256", inventorySha256,
            "ownerSha256", ownerSha256, "directory", directory.toString(), "files", files.stream().map(java.util.LinkedHashMap::new).toList());
    }

    Path directory() { return directory; }

    void verify() throws IOException {
        if (!directory.startsWith(data.resolve("terminal-native")) || !checkedDirectory(data, "terminal-native", false).equals(directory.getParent())
                || !checkedDirectory(directory.getParent(), directory.getFileName().toString(), false).equals(directory)) throw new IOException("原生目录失去原拥有归属");
        if (!hash(read(directory.resolve("inventory.json"), 32_768)).equals(inventorySha256)) throw new IOException("原生字节清单已变化");
        if (!hash(read(directory.resolve("owner.json"), 32_768)).equals(ownerSha256)) throw new IOException("原生 Worker 归属回执已变化");
        for (Map<String, Object> file : files) {
            Path path = directory.resolve(str(file, "path")); Path current = directory;
            for (Path component : directory.relativize(path.getParent())) current = checkedDirectory(current, component.toString(), false);
            byte[] bytes = read(path, Math.toIntExact(number(file, "bytes")));
            if (bytes.length != number(file, "bytes") || !hash(bytes).equals(str(file, "sha256"))) throw new IOException("原生文件已被替换或损坏");
        }
    }

    private void activate() throws IOException {
        verify();
        System.setProperty("pty4j.preferred.native.folder", directory.toString());
    }

    private static Path checkedDirectory(Path parent, String name, boolean create) throws IOException {
        if (name.isBlank() || name.equals(".") || name.equals("..") || name.contains("/") || name.contains("\\")) throw new IOException("原生目录组件无效");
        Path result = parent.resolve(name);
        if (create && !Files.exists(result, LinkOption.NOFOLLOW_LINKS)) {
            try {
                if (windows()) Files.createDirectory(result);
                else Files.createDirectory(result, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")));
            } catch (java.nio.file.FileAlreadyExistsException concurrent) {
                if (!Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS)) throw concurrent;
            }
        }
        if (!Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS) || !result.toRealPath().equals(result)) throw new IOException("原生父目录被替换或重定向");
        return result;
    }

    private static byte[] read(Path path, int maximum) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !path.toRealPath().equals(path) || Files.size(path) > maximum) throw new IOException("原生文件不是原普通文件");
        try (InputStream input = Files.newInputStream(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(maximum + 1); if (bytes.length > maximum) throw new IOException("原生文件读取期间增长"); return bytes;
        }
    }

    private static void save(Path path, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
        if (!windows()) try (FileChannel parent = FileChannel.open(path.getParent(), StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) { parent.force(true); }
    }

    private static String platform() throws IOException {
        String operatingSystem = System.getProperty("os.name", "").toLowerCase(Locale.ROOT), architecture = System.getProperty("os.arch", "").toLowerCase(Locale.ROOT);
        architecture = switch (architecture) { case "amd64", "x86_64" -> "x86-64"; case "arm64" -> "aarch64"; case "i386", "i686" -> "x86"; default -> architecture; };
        if (operatingSystem.startsWith("windows")) return "win/" + architecture;
        if (operatingSystem.startsWith("mac")) return "darwin";
        if (operatingSystem.equals("linux")) return "linux/" + architecture;
        throw new IOException("当前系统缺少支持的原生 PTY 后端");
    }

    private static boolean windows() { return System.getProperty("os.name", "").startsWith("Windows"); }
    private static long number(Map<String, Object> object, String field) {
        if (!(object.get(field) instanceof Integer || object.get(field) instanceof Long)) throw new IllegalArgumentException("原生清单整数无效");
        return ((Number) object.get(field)).longValue();
    }
    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
