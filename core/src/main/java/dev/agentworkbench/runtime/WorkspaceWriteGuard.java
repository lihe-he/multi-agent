package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import static dev.agentworkbench.Json.*;

/**
 * 原 Worker 持有整次根执行的文件锁；关闭进程不删除尚未核对的写入意图。
 */
public final class WorkspaceWriteGuard implements AutoCloseable {
    private final ObjectMapper mapper;
    private final Path data;
    private final MetadataWriter metadata;
    private final ReentrantLock gate = new ReentrantLock();
    private final Map<String, Holder> held = new LinkedHashMap<>();

    public WorkspaceWriteGuard(ObjectMapper mapper, Path data, MetadataWriter metadata) {
        this.mapper = mapper; this.data = data; this.metadata = metadata;
    }

    /**
     * 文件锁与持久意图都匹配原根身份后才授予；旧 HELD 不因 OS 锁空闲而覆盖。
     */
    public Map<String, Object> acquire(Map<String, Object> identity) {
        gate.lock();
        try {
            requireExecutor(identity);
            Holder previous = held.get(str(identity, "id"));
            if (previous != null) { require(previous, identity); return new LinkedHashMap<>(previous.record); }
            Path parent = parent(identity, true), lockPath = parent.resolve("writer.lock"), manifest = parent.resolve("writer.json");
            Path work = parent.resolve("work");
            if (Files.exists(work, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(parent.resolve("selection.json"), LinkOption.NOFOLLOW_LINKS)
                    && !Files.isRegularFile(parent.resolve("preparation.json"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("已有现场缺少原准备回执，不会建立新的写入归属");
            for (String name : List.of("selection.json", "preparation.json")) if (Files.exists(parent.resolve(name), LinkOption.NOFOLLOW_LINKS)) {
                Map<String, Object> prepared = read(parent.resolve(name));
                if (!"READY".equals(str(prepared, "state")) || !str(identity, "taskId").equals(str(prepared, "taskId"))) throw new IllegalArgumentException("原准备意图未完成或不属于此任务，不会分配新的写入归属");
            }
            boolean present = Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS);
            if (present != Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("原写入锁或完整意图缺失，请先核对现场");
            read(manifest);
            regular(lockPath);
            String originalKey = present ? fileKey(lockPath) : "";
            FileChannel channel = openLock(lockPath, present);
            FileLock lock = null;
            try {
                if (!present) originalKey = fileKey(lockPath);
                lock = lock(channel);
                Map<String, Object> old = read(manifest);
                if (!old.isEmpty() && !List.of("RELEASED", "RECONCILED").contains(str(old, "state"))) throw new IllegalArgumentException("原现场写入意图尚未核对，不会因进程退出自动接管");
                if (!old.isEmpty() && same(map(old, "identity"), identity)) throw new IllegalArgumentException("原写入归属已经结束，不能重放同一编号");
                String key = fileKey(lockPath);
                if (!key.equals(originalKey)) throw new IllegalArgumentException("取得锁期间原锁文件已被替换");
                if (!old.isEmpty() && !str(old, "fileKey").isBlank() && !key.equals(str(old, "fileKey"))) throw new IllegalArgumentException("原写入锁文件已被替换，不能接管");
                String marker = str(identity, "id") + ":" + UUID.randomUUID();
                channel.truncate(0); channel.position(0);
                ByteBuffer content = ByteBuffer.wrap(marker.getBytes(StandardCharsets.UTF_8));
                while (content.hasRemaining()) channel.write(content);
                channel.force(true);
                requireMarker(channel, lockPath, marker);
                if (!key.equals(fileKey(lockPath))) throw new IllegalArgumentException("原文件描述符与实际锁路径不一致");
                Map<String, Object> record = obj("schemaVersion", 1, "state", "HELD", "identity", identity, "lockPath", lockPath.toString(), "manifestPath", manifest.toString(),
                    "fileKey", key, "lockMarker", marker, "executorPid", ProcessHandle.current().pid(), "processStartedAt", birth(), "lockValid", true, "grantedAt", now());
                metadata.write(manifest, mapper.writeValueAsBytes(record));
                Holder holder = new Holder(new LinkedHashMap<>(identity), parent, channel, lock, key, record);
                held.put(str(identity, "id"), holder); require(holder, identity); return new LinkedHashMap<>(record);
            } catch (Exception exception) {
                held.remove(str(identity, "id"));
                if (lock != null) { try { lock.release(); } catch (IOException ignored) { } }
                try { channel.close(); } catch (IOException ignored) { }
                throw exception;
            }
        } catch (IOException exception) { throw new IllegalStateException("现场写入意图未取得完整回执，保留原文件供核对", exception); }
        finally { gate.unlock(); }
    }

    /**
     * 每次实际调用借用原锁，迟到的旧编号不能借用新根的文件锁。
     */
    public Borrow enter(Map<String, Object> identity) {
        gate.lock();
        try {
            Holder holder = held.get(str(identity, "id"));
            if (holder == null) throw new IllegalArgumentException("原根现场写入锁不可用");
            require(holder, identity); holder.calls++; return new Borrow(holder);
        } catch (IOException exception) { throw new IllegalArgumentException("原写入锁或意图无法核验", exception); }
        finally { gate.unlock(); }
    }

    /**
     * 实际调用结束才关闭原锁；重开清理同时核验原 PID/出生及完整原意图。
     */
    public Map<String, Object> release(Map<String, Object> identity, String note) {
        gate.lock();
        try {
            Path parent = parent(identity, false), lockPath = parent.resolve("writer.lock"), manifest = parent.resolve("writer.json");
            Holder holder = held.get(str(identity, "id"));
            if (holder != null) {
                require(holder, identity);
                if (holder.calls != 0) throw new IllegalArgumentException("原根仍有实际在途调用，不能释放现场写入锁");
                Map<String, Object> record = new LinkedHashMap<>(holder.record);
                record.putAll(obj("state", note.isBlank() ? "RELEASED" : "RECONCILED", "lockValid", false, "releasedAt", now(), "note", note));
                metadata.write(manifest, mapper.writeValueAsBytes(record));
                holder.lock.release(); holder.channel.close(); held.remove(str(identity, "id"));
                return receipt(identity, record, false);
            }
            if (number(identity, "executorPid") != ProcessHandle.current().pid()) requireOriginalStopped(identity);
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS) && !Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) return receipt(identity, obj("state", "ABSENT"), true);
            regular(lockPath);
            if (!Files.exists(lockPath, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("原写入锁缺失但意图仍存在，请人工检查替换或删除");
            String originalKey = fileKey(lockPath);
            try (FileChannel channel = openLock(lockPath, true); FileLock lock = lock(channel)) {
                Map<String, Object> record = read(manifest);
                if (!originalKey.equals(fileKey(lockPath))) throw new IllegalArgumentException("核对原锁期间文件身份已变化");
                if (!record.isEmpty() && !same(map(record, "identity"), identity)) {
                    return receipt(identity, obj("state", "FOREIGN_PRESERVED", "originalRecord", record), true);
                }
                if (!record.isEmpty() && !str(record, "fileKey").isBlank() && !str(record, "fileKey").equals(fileKey(lockPath))) throw new IllegalArgumentException("原写入锁文件身份已变化，不能确认释放");
                if (!str(record, "lockMarker").isBlank()) requireMarker(channel, lockPath, str(record, "lockMarker"));
                if (List.of("RELEASED", "RECONCILED").contains(str(record, "state"))) return receipt(identity, record, false);
                if (note.isBlank()) throw new IllegalArgumentException("原写入归属的结束回执缺失，需要核对说明");
                requireOriginalStopped(identity);
                if (record.isEmpty()) record = obj("schemaVersion", 1, "identity", identity, "originalManifestPresent", false);
                record.putAll(obj("state", "RECONCILED", "lockValid", false, "releasedAt", now(), "note", note, "fileKey", fileKey(lockPath)));
                metadata.write(manifest, mapper.writeValueAsBytes(record));
                return receipt(identity, record, false);
            }
        } catch (IOException exception) { throw new IllegalStateException("原写入锁关闭或回执保存尚未确认", exception); }
        finally { gate.unlock(); }
    }

    private Map<String, Object> receipt(Map<String, Object> identity, Map<String, Object> record, boolean absent) {
        return obj("identity", identity, "protectionReleased", true, "nativeOwnerAbsent", absent, "manifest", record,
            "receiptExecutorPid", ProcessHandle.current().pid(), "receiptProcessStartedAt", birth(), "observedAt", now());
    }

    private void require(Holder holder, Map<String, Object> identity) throws IOException {
        if (!same(holder.identity, identity) || !holder.lock.isValid() || !holder.channel.isOpen() || !holder.fileKey.equals(fileKey(holder.parent.resolve("writer.lock")))
                || !holder.parent.toRealPath().equals(holder.parent) || !str(holder.record, "lockMarker").equals(lockMarker(holder.channel))) throw new IllegalArgumentException("原写入锁或实际目录身份已变化");
        requireMarker(holder.channel, holder.parent.resolve("writer.lock"), str(holder.record, "lockMarker"));
        Path work = holder.parent.resolve("work");
        if (Files.exists(work, LinkOption.NOFOLLOW_LINKS) && (!Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS) || !work.toRealPath().equals(work))) throw new IllegalArgumentException("原工作目录的解析目标已变化");
        Map<String, Object> saved = read(holder.parent.resolve("writer.json"));
        if (!"HELD".equals(str(saved, "state")) || !same(map(saved, "identity"), holder.identity) || !holder.fileKey.equals(str(saved, "fileKey"))
                || !str(holder.record, "lockMarker").equals(str(saved, "lockMarker"))) throw new IllegalArgumentException("原写入意图已变化，拒绝继续执行");
    }

    private Path parent(Map<String, Object> identity, boolean create) throws IOException {
        String taskId = str(identity, "taskId");
        if (!taskId.matches("[a-zA-Z0-9_-]{1,100}") || str(identity, "id").isBlank() || str(identity, "taskExecutionId").isBlank()) throw new IllegalArgumentException("写入锁缺少原根身份");
        Path root = data.toRealPath(), expected = root.resolve("workspaces").resolve(taskId).resolve("work");
        if (!Path.of(str(identity, "scopePath")).toAbsolutePath().normalize().equals(expected)) throw new IllegalArgumentException("写入锁不属于原任务数据范围");
        Path current = root;
        for (Path component : root.relativize(expected.getParent())) {
            current = current.resolve(component);
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw new IllegalArgumentException("写入锁父目录包含外国解析目标");
            } else if (create) Files.createDirectory(current);
        }
        return expected.getParent();
    }

    private Map<String, Object> read(Path path) throws IOException {
        if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return obj();
        regular(path);
        if (Files.size(path) > 256000) throw new IllegalArgumentException("现场写入意图超过读取上限");
        return mapper.readValue(Files.readAllBytes(path), new TypeReference<>() {});
    }

    private static void regular(Path path) throws IOException {
        if (Files.exists(path, LinkOption.NOFOLLOW_LINKS) && !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("写入锁或意图不是原普通文件");
    }

    private static String fileKey(Path path) throws IOException {
        return WorkspaceFileIdentity.key(path);
    }

    /**
     * Windows 持有原描述符期间拒绝删除访问，路径不能换成另一文件后再恢复旧编号。
     */
    private static FileChannel openLock(Path path, boolean present) throws IOException {
        var options = new HashSet<OpenOption>(List.of(StandardOpenOption.READ, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS));
        if (!present) options.add(StandardOpenOption.CREATE_NEW);
        if (System.getProperty("os.name", "").startsWith("Windows")) options.add(com.sun.nio.file.ExtendedOpenOption.NOSHARE_DELETE);
        return FileChannel.open(path, options);
    }

    /**
     * Unix 的文件锁是建议锁，另从实际路径核对标记与原描述符的关联。
     */
    private static void requireMarker(FileChannel channel, Path path, String expected) throws IOException {
        if (!expected.equals(lockMarker(channel))) throw new IllegalArgumentException("原写入锁描述符的标记已变化");
        if (!System.getProperty("os.name", "").startsWith("Windows") && (Files.size(path) > 512 || !expected.equals(Files.readString(path, StandardCharsets.UTF_8)))) throw new IllegalArgumentException("原描述符不再对应实际锁路径");
    }

    /**
     * Windows 独占锁期间从原描述符读取标记，其他文件句柄不能读取锁定范围。
     */
    private static String lockMarker(FileChannel channel) throws IOException {
        if (channel.size() > 512) throw new IllegalArgumentException("原写入锁的文件描述符标记已变化");
        ByteBuffer content = ByteBuffer.allocate((int) channel.size()); channel.position(0);
        while (content.hasRemaining()) if (channel.read(content) < 0) throw new IllegalArgumentException("原写入锁的文件描述符标记不完整");
        return new String(content.array(), StandardCharsets.UTF_8);
    }

    private static FileLock lock(FileChannel channel) throws IOException {
        FileLock lock;
        try { lock = channel.tryLock(); } catch (OverlappingFileLockException exception) { throw new IllegalArgumentException("现场写入锁仍由原调用持有", exception); }
        if (lock == null) throw new IllegalArgumentException("原 Worker 仍持有现场写入锁，不能接管");
        return lock;
    }

    private static void requireExecutor(Map<String, Object> identity) {
        if (!(identity.get("executorPid") instanceof Number pid) || pid.longValue() != ProcessHandle.current().pid() || !birth().equals(str(identity, "processStartedAt"))) throw new IllegalArgumentException("写入锁不属于当前实际 Worker 进程");
    }

    private static void requireOriginalStopped(Map<String, Object> identity) {
        if (!(identity.get("executorPid") instanceof Number pid) || str(identity, "processStartedAt").isBlank()) throw new IllegalArgumentException("原写入者的出生身份缺失");
        boolean alive = ProcessHandle.of(pid.longValue()).filter(ProcessHandle::isAlive).map(process -> process.info().startInstant()
            .map(value -> value.toString().equals(str(identity, "processStartedAt"))).orElse(true)).orElse(false);
        if (alive) throw new IllegalArgumentException("原写入者仍存活或出生身份无法核验，不能以说明解除保护");
    }

    private static String birth() { return ProcessHandle.current().info().startInstant().map(Instant::toString).orElseThrow(); }

    private static long number(Map<String, Object> value, String field) {
        if (!(value.get(field) instanceof Number number) || number.longValue() < 1) throw new IllegalArgumentException("写入归属缺少原进程身份");
        return number.longValue();
    }

    private static boolean same(Map<String, Object> left, Map<String, Object> right) {
        if (!left.keySet().equals(right.keySet())) return false;
        for (String field : left.keySet()) {
            Object a = left.get(field), b = right.get(field);
            if (a instanceof Number x && b instanceof Number y) { if (new java.math.BigDecimal(x.toString()).compareTo(new java.math.BigDecimal(y.toString())) != 0) return false; }
            else if (!Objects.equals(a, b)) return false;
        }
        return true;
    }

    @Override public void close() {
        gate.lock();
        try {
            for (Holder holder : new ArrayList<>(held.values())) {
                try { holder.lock.release(); } catch (IOException ignored) { }
                try { holder.channel.close(); } catch (IOException ignored) { }
            }
            held.clear();
        } finally { gate.unlock(); }
    }

    @FunctionalInterface
    public interface MetadataWriter { void write(Path path, byte[] bytes) throws IOException; }

    public final class Borrow implements AutoCloseable {
        private final Holder holder;
        private boolean closed;
        private Borrow(Holder holder) { this.holder = holder; }
        @Override public void close() { gate.lock(); try { if (!closed) { closed = true; holder.calls--; } } finally { gate.unlock(); } }
    }

    private static final class Holder {
        final Map<String, Object> identity, record;
        final Path parent;
        final FileChannel channel;
        final FileLock lock;
        final String fileKey;
        int calls;
        Holder(Map<String, Object> identity, Path parent, FileChannel channel, FileLock lock, String fileKey, Map<String, Object> record) {
            this.identity = identity; this.parent = parent; this.channel = channel; this.lock = lock; this.fileKey = fileKey; this.record = record;
        }
    }
}
