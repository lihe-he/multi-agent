package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 原始终端字节先完整落盘，再发布有摘要的游标引用；未完成文件保留供核对。
 */
public final class TerminalOutputArchive {
    public static final int MAX_SEGMENT_BYTES = 65_536;
    public static final long MAX_OUTPUT_BYTES = 64L * 1024 * 1024;
    private static final int MAX_SEGMENTS = 131_072;
    private final Path directory;
    private final ObjectMapper json;

    public TerminalOutputArchive(ObjectMapper json, Path dataDirectory) {
        this.json = json;
        try {
            Path data = dataDirectory.toRealPath();
            directory = childDirectory(data, "terminal-output", true);
        } catch (IOException exception) { throw new IllegalStateException("终端输出目录无法固定", exception); }
    }

    public Writer create(String terminalId, String epoch, Map<String, Object> owner, long outputLimit) {
        requireId(terminalId); requireId(epoch);
        if (outputLimit < 1 || outputLimit > MAX_OUTPUT_BYTES) throw new IllegalArgumentException("终端输出限额无效");
        try {
            Path folder = directory.resolve(terminalId);
            Files.createDirectory(folder);
            folder = childDirectory(directory, terminalId, false);
            Map<String, Object> source = obj("protocolVersion", 1, "terminalId", terminalId, "epoch", epoch,
                "owner", new LinkedHashMap<>(owner), "outputLimit", outputLimit, "createdAt", now());
            save(folder.resolve("owner.json"), json.writeValueAsBytes(source));
            return new Writer(folder, source, outputLimit);
        } catch (IOException exception) { throw new IllegalStateException("终端原输出归属无法保存，不能重新创建", exception); }
    }

    /**
     * 数据引用不接受外部路径；读取时核对原 epoch、长度和完整字节摘要。
     */
    public byte[] read(String terminalId, String epoch, Map<String, Object> descriptor) {
        requireId(terminalId); requireId(epoch);
        try {
            Path folder = owned(terminalId, epoch);
            requireDescriptor(terminalId, epoch, descriptor);
            String file = fileName(descriptor);
            Map<String, Object> saved = decode(bounded(folder.resolve(file + ".json"), 16_384));
            if (!canonical(saved).equals(canonical(descriptor))) throw new IOException("终端引用与原落盘描述不一致");
            byte[] bytes = bounded(folder.resolve(file + ".bin"), (int) number(descriptor, "length"));
            if (bytes.length != number(descriptor, "length") || !sha(bytes).equals(str(descriptor, "sha256"))) throw new IOException("终端原始输出不完整或已变化");
            return bytes;
        } catch (IOException | IllegalArgumentException exception) { throw new IllegalStateException("终端输出引用无法完整核验", exception); }
    }

    /**
     * 重启只检查原文件，不填补游标、丢弃不完整数据或再次写入终端。
     */
    public Map<String, Object> inspect(String terminalId, String epoch) {
        requireId(terminalId); requireId(epoch);
        List<Map<String, Object>> descriptors = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        List<String> unreferencedFiles = new ArrayList<>();
        Map<String, Object> end = obj();
        try {
            Path folder = owned(terminalId, epoch);
            List<String> files;
            try (var entries = Files.list(folder)) { files = entries.limit(MAX_SEGMENTS * 2L + 3).map(path -> path.getFileName().toString()).sorted().toList(); }
            if (files.size() > MAX_SEGMENTS * 2 + 2) throw new IOException("终端原输出文件数超过核对边界");
            var names = new java.util.HashSet<>(files);
            for (String file : files) {
                if (file.equals("owner.json")) continue;
                if (file.equals("end.json")) {
                    try { end = decode(bounded(folder.resolve(file), 16_384)); }
                    catch (Exception exception) { gaps.add(obj("file", file, "reason", "结束描述无法完整读取")); }
                } else if (file.endsWith(".json")) {
                    try {
                        Map<String, Object> descriptor = decode(bounded(folder.resolve(file), 16_384));
                        requireDescriptor(terminalId, epoch, descriptor);
                        if (!file.equals(fileName(descriptor) + ".json")) throw new IOException("描述名称不属于原游标");
                        read(terminalId, epoch, descriptor); descriptors.add(descriptor);
                    } catch (Exception exception) { gaps.add(obj("file", file, "reason", "原输出描述或完整字节核验失败")); }
                } else if (file.endsWith(".bin") && !names.contains(file.substring(0, file.length() - 4) + ".json")) {
                    unreferencedFiles.add(file);
                } else if (!file.endsWith(".bin")) unreferencedFiles.add(file);
            }
            descriptors.sort(Comparator.comparingLong(value -> number(value, "offset")));
            long cursor = 0, observedEnd = 0; boolean contiguous = true;
            for (Map<String, Object> descriptor : descriptors) {
                if (number(descriptor, "offset") != observedEnd) { gaps.add(obj("expectedOffset", observedEnd, "actualOffset", descriptor.get("offset"), "reason", "原输出游标存在缺口或重叠")); contiguous = false; }
                observedEnd = Math.max(observedEnd, number(descriptor, "next"));
                if (contiguous) cursor = number(descriptor, "next");
            }
            boolean complete = !end.isEmpty() && number(end, "protocolVersion") == 1 && terminalId.equals(str(end, "terminalId")) && epoch.equals(str(end, "epoch"))
                && bool(end, "complete", false) && number(end, "bytes") == cursor && number(end, "segments") == descriptors.size()
                && gaps.isEmpty() && unreferencedFiles.isEmpty();
            return obj("terminalId", terminalId, "epoch", epoch, "descriptors", descriptors, "contiguousBytes", cursor, "observedEnd", observedEnd,
                "complete", complete, "end", end, "gaps", gaps, "unreferencedFiles", unreferencedFiles);
        } catch (Exception exception) {
            gaps.add(obj("reason", "原输出归属或目录无法完整核对"));
            return obj("terminalId", terminalId, "epoch", epoch, "complete", false, "descriptors", descriptors, "gaps", gaps, "unreferencedFiles", unreferencedFiles);
        }
    }

    /**
     * 导出保留已落盘但未发布或损坏的原文件；缺失归属不补建，也不提升完整性。
     */
    public Snapshot snapshot(String terminalId, String epoch, long byteLimit) {
        requireId(terminalId); requireId(epoch);
        Map<String, byte[]> files = new LinkedHashMap<>(); List<Map<String, Object>> facts = new ArrayList<>(), gaps = new ArrayList<>();
        long total = 0;
        try {
            Path folder = childDirectory(directory, terminalId, false); List<String> names;
            try (var entries = Files.list(folder)) { names = entries.limit(MAX_SEGMENTS * 2L + 4).map(path -> path.getFileName().toString()).sorted().toList(); }
            if (names.size() > MAX_SEGMENTS * 2 + 3) throw new dev.agentworkbench.ApiException(413, "终端原输出文件数超过导出边界");
            for (String name : names) {
                boolean segment = name.matches("[0-9]{20}-[0-9]{6}-[0-9a-f]{64}\\.(?:bin|json)");
                boolean unpublished = name.matches("unpublished-limit-[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.bin");
                if (!segment && !unpublished && !name.equals("owner.json") && !name.equals("end.json")) {
                    gaps.add(obj("file", name, "reason", "原目录中存在不能作为输出文件导出的条目")); continue;
                }
                byte[] bytes;
                try { bytes = bounded(folder.resolve(name), name.endsWith(".bin") ? MAX_SEGMENT_BYTES : 16_384); }
                catch (IOException exception) { gaps.add(obj("file", name, "reason", "原输出文件缺失、类型不支持或超过单文件范围")); continue; }
                if (total > byteLimit - bytes.length) throw new dev.agentworkbench.ApiException(413, "终端原输出超过本次交付包范围");
                files.put(name, bytes); total += bytes.length;
                facts.add(obj("file", name, "bytes", bytes.length, "sha256", sha(bytes), "digestSource", "EXPORT_OBSERVATION_ONLY"));
            }
        } catch (IOException exception) { gaps.add(obj("reason", "原输出目录无法完整读取，未建立替代目录或结束描述")); }
        return new Snapshot(files, obj("terminalId", terminalId, "epoch", epoch, "bytes", total, "files", facts, "rawFileGaps", gaps,
            "inspection", inspect(terminalId, epoch), "digestSource", "EXPORT_OBSERVATION_ONLY", "originalFilesRetained", true));
    }

    public record Snapshot(Map<String, byte[]> files, Map<String, Object> observation) { }

    private Path owned(String terminalId, String epoch) throws IOException {
        Path folder = childDirectory(directory, terminalId, false);
        Map<String, Object> owner = decode(bounded(folder.resolve("owner.json"), 16_384));
        if (number(owner, "protocolVersion") != 1 || !terminalId.equals(str(owner, "terminalId")) || !epoch.equals(str(owner, "epoch"))) throw new IOException("终端原输出归属已变化");
        return folder;
    }

    /**
     * 核心发布片段前读取原拥有者，避免同编号的文件元数据替代原 Worker 来源。
     */
    public Map<String, Object> ownership(String terminalId, String epoch) {
        requireId(terminalId); requireId(epoch);
        try { return decode(bounded(owned(terminalId, epoch).resolve("owner.json"), 16_384)); }
        catch (IOException | IllegalArgumentException exception) { throw new IllegalStateException("终端原输出来源不可核验", exception); }
    }

    private static Path childDirectory(Path parent, String name, boolean create) throws IOException {
        Path result = parent.resolve(name);
        if (create && !Files.exists(result, LinkOption.NOFOLLOW_LINKS)) Files.createDirectory(result);
        if (!Files.isDirectory(result, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(result) || !result.toRealPath().equals(result)) throw new IOException("终端目录不属于固定拥有路径");
        return result;
    }

    private static byte[] bounded(Path file, int maximum) throws IOException {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(file) || Files.size(file) > maximum) throw new IOException("原输出文件越过边界或已被替换");
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer bytes = ByteBuffer.allocate(maximum + 1);
            while (bytes.hasRemaining() && channel.read(bytes) >= 0) { }
            if (bytes.position() > maximum) throw new IOException("原输出文件增长越过边界");
            return java.util.Arrays.copyOf(bytes.array(), bytes.position());
        }
    }

    private static void save(Path file, byte[] bytes) throws IOException {
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer); channel.force(true);
        }
    }

    private Map<String, Object> decode(byte[] bytes) throws IOException {
        return json.readerFor(new TypeReference<Map<String, Object>>() { }).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
    }

    private static void requireId(String value) {
        if (value == null || !value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new IllegalArgumentException("终端原编号无效");
    }

    private static void requireDescriptor(String terminalId, String epoch, Map<String, Object> value) throws IOException {
        long offset = number(value, "offset"), length = number(value, "length"), next = number(value, "next");
        if (number(value, "protocolVersion") != 1 || !terminalId.equals(str(value, "terminalId")) || !epoch.equals(str(value, "epoch"))
                || offset < 0 || length < 1 || length > MAX_SEGMENT_BYTES || offset > Long.MAX_VALUE - length || next != offset + length
                || !str(value, "sha256").matches("[0-9a-f]{64}")) throw new IOException("终端输出引用无效");
    }

    private static String fileName(Map<String, Object> value) {
        return String.format(java.util.Locale.ROOT, "%020d-%06d-%s", number(value, "offset"), number(value, "length"), str(value, "sha256"));
    }

    private static Map<String, Object> canonical(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(value);
        for (String field : List.of("protocolVersion", "offset", "length", "next")) result.put(field, number(value, field));
        return result;
    }

    private static long number(Map<String, Object> value, String key) {
        Object number = value.get(key);
        if (!(number instanceof Integer || number instanceof Long)) throw new IllegalArgumentException("终端字节游标必须为整数");
        return ((Number) number).longValue();
    }

    private static String sha(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    public final class Writer {
        private final Path folder;
        private final Map<String, Object> source;
        private final long outputLimit;
        private long offset;
        private int segments;
        private boolean closed;
        private boolean failed;

        private Writer(Path folder, Map<String, Object> source, long outputLimit) { this.folder = folder; this.source = source; this.outputLimit = outputLimit; }

        /**
         * 单一读取者保存有界片段；失败后原 Writer 不再接受后续字节。
         */
        public synchronized Map<String, Object> append(byte[] bytes) {
            if (closed || failed) throw new IllegalStateException("终端原输出写入已停止");
            if (bytes.length < 1 || bytes.length > MAX_SEGMENT_BYTES) {
                failed = true; throw new IllegalStateException("终端原输出片段越过单次读取边界");
            }
            if (offset > outputLimit - bytes.length || segments >= MAX_SEGMENTS) {
                failed = true;
                try { owned(str(source, "terminalId"), str(source, "epoch")); save(folder.resolve("unpublished-limit-" + id() + ".bin"), bytes); }
                catch (IOException | RuntimeException exception) { throw new IllegalStateException("终端达到保存边界，最后已观察片段也未完整保存", exception); }
                throw new IllegalStateException("终端原输出达到保存边界，末片段独立保留，后续输出保持未确认");
            }
            Map<String, Object> descriptor = obj("protocolVersion", 1, "terminalId", source.get("terminalId"), "epoch", source.get("epoch"),
                "offset", offset, "length", bytes.length, "next", offset + bytes.length, "sha256", sha(bytes));
            try {
                if (!owned(str(source, "terminalId"), str(source, "epoch")).equals(folder)) throw new IOException("原输出目录已经变化");
                String file = fileName(descriptor);
                save(folder.resolve(file + ".bin"), bytes);
                save(folder.resolve(file + ".json"), json.writeValueAsBytes(descriptor));
                offset += bytes.length; segments++; return descriptor;
            } catch (IOException | RuntimeException exception) {
                failed = true; throw new IllegalStateException("终端原输出未完整保存，原文件保留供核对", exception);
            }
        }

        public synchronized Map<String, Object> finish(boolean complete) {
            if (closed) throw new IllegalStateException("终端原输出结束描述只能保存一次");
            closed = true;
            Map<String, Object> end = obj("protocolVersion", 1, "terminalId", source.get("terminalId"), "epoch", source.get("epoch"),
                "bytes", offset, "segments", segments, "complete", complete && !failed, "endedAt", now());
            try { owned(str(source, "terminalId"), str(source, "epoch")); save(folder.resolve("end.json"), json.writeValueAsBytes(end)); return end; }
            catch (IOException | RuntimeException exception) { throw new IllegalStateException("终端原输出结束描述未完整保存", exception); }
        }
    }
}
