package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.io.CleanupMode;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 实际文件系统与两个 JVM 核对整次根锁、原出生身份及保留意图。
 */
class WorkspaceWriteGuardTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;
    private final ObjectMapper mapper = new ObjectMapper();

    private Map<String, Object> identity(Path data, String id) throws Exception {
        return obj("schemaVersion", 1, "id", id, "taskId", "root", "projectId", "project", "taskExecutionId", "execution-" + id, "dispatchActionId", "dispatch-" + id,
            "scopePath", data.toRealPath().resolve("workspaces/root/work").toString(), "workerId", "worker", "workerEpoch", 1,
            "executorPid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());
    }

    private WorkspaceWriteGuard guard(Path data) {
        return new WorkspaceWriteGuard(mapper, data, (path, bytes) -> {
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp");
            Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        });
    }

    private Map<String, Object> read(Path path) throws Exception { return mapper.readValue(Files.readAllBytes(path), new TypeReference<>() {}); }

    private void assertIdentity(Map<String, Object> expected, Map<String, Object> actual) throws Exception {
        assertEquals(mapper.readValue(mapper.writeValueAsBytes(expected), new TypeReference<Map<String, Object>>() {}), actual);
    }

    private void await(BooleanSupplier condition) throws Exception {
        long until = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
        while (System.nanoTime() < until) { if (condition.getAsBoolean()) return; Thread.sleep(20); }
        fail("original guard process did not reach its recorded boundary");
    }

    @Test
    void fileIdentityTracksTheNativeFileInsteadOfItsContentOrAliasName() throws Exception {
        Path first = Files.writeString(directory.resolve("first.lock"), "same bytes"), alias = Files.createLink(directory.resolve("anchor.lock"), first), copy = Files.copy(first, directory.resolve("copy.lock"));
        String original = WorkspaceFileIdentity.key(first); assertEquals(original, WorkspaceFileIdentity.key(alias)); assertNotEquals(original, WorkspaceFileIdentity.key(copy));
        Files.writeString(first, "updated bytes"); assertEquals(original, WorkspaceFileIdentity.key(first));
        if (System.getProperty("os.name").startsWith("Windows")) assertTrue(original.startsWith("WINDOWS:"));
    }

    @Test
    void activeBorrowBlocksReleaseAndOldCallsCannotBorrowANewRoot() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")); Map<String, Object> original = identity(data, "first");
        try (var guard = guard(data)) {
            Map<String, Object> grant = guard.acquire(original); assertTrue(bool(grant, "lockValid", false)); assertFalse(str(grant, "fileKey").isBlank());
            try (var borrow = guard.enter(original)) {
                assertThrows(IllegalArgumentException.class, () -> guard.release(original, ""));
                Map<String, Object> changed = new LinkedHashMap<>(original); changed.put("taskExecutionId", "foreign-root");
                assertThrows(IllegalArgumentException.class, () -> guard.enter(changed));
                assertEquals("HELD", read(Path.of(str(grant, "manifestPath"))).get("state"));
            }
            Map<String, Object> released = guard.release(original, ""); assertTrue(bool(released, "protectionReleased", false));
            assertFalse(bool(map(released, "manifest"), "lockValid", true)); assertThrows(IllegalArgumentException.class, () -> guard.enter(original)); assertThrows(IllegalArgumentException.class, () -> guard.acquire(original));
            Map<String, Object> next = identity(data, "second"); guard.acquire(next);
            assertThrows(IllegalArgumentException.class, () -> guard.enter(original)); try (var borrow = guard.enter(next)) { assertThrows(IllegalArgumentException.class, () -> guard.release(next, "checked")); }
            assertTrue(bool(guard.release(next, ""), "protectionReleased", false));
        }
    }

    @Test
    void closedNativeLockKeepsOriginalIntentAndRequiresTheOriginalProcessToStop() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")), reports = Files.createDirectories(directory.resolve("reports"));
        Process child = child(data, reports); Map<String, Object> grant;
        try {
            await(() -> Files.exists(reports.resolve("held.json"))); grant = read(reports.resolve("held.json")); Map<String, Object> old = map(grant, "identity");
            assertEquals(child.pid(), ((Number) grant.get("executorPid")).longValue()); assertEquals(child.info().startInstant().orElseThrow().toString(), grant.get("processStartedAt"));
            child.getOutputStream().write('C'); child.getOutputStream().flush(); await(() -> Files.exists(reports.resolve("native-closed.json")));
            Path lock = Path.of(str(grant, "lockPath"));
            try (var channel = FileChannel.open(lock, StandardOpenOption.WRITE); FileLock nativeLock = channel.tryLock()) { assertNotNull(nativeLock, "original native lock must actually be closed"); }
            try (var competitor = guard(data)) {
                assertThrows(IllegalArgumentException.class, () -> competitor.acquire(identityUnchecked(data, "new-root")));
                assertThrows(IllegalArgumentException.class, () -> competitor.release(old, "checked while old process is alive"));
                assertEquals("HELD", read(Path.of(str(grant, "manifestPath"))).get("state"));
                stop(child, str(grant, "processStartedAt"));
                assertTrue(bool(competitor.release(old, "original process and effects checked"), "protectionReleased", false));
                Map<String, Object> next = identity(data, "new-root"); competitor.acquire(next); competitor.release(next, "");
            }
        } finally { stop(child, child.info().startInstant().map(Instant::toString).orElse("")); }
    }

    @Test
    void physicalAliasCompetesWithTheSameOriginalJvmLockAndCrashDoesNotAuthorizeTakeover() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")).toRealPath(), reports = Files.createDirectories(directory.resolve("reports")), alias = directory.resolve("alias");
        alias(alias, data); assertEquals(data, alias.toRealPath()); Process child = child(data, reports);
        try {
            await(() -> Files.exists(reports.resolve("held.json"))); Map<String, Object> grant = read(reports.resolve("held.json")), original = map(grant, "identity");
            try (var competitor = guard(alias)) {
                assertThrows(IllegalArgumentException.class, () -> competitor.acquire(identityUnchecked(data, "alias-competitor")));
                stop(child, str(grant, "processStartedAt"));
                assertThrows(IllegalArgumentException.class, () -> competitor.acquire(identityUnchecked(data, "alias-competitor")));
                assertIdentity(original, map(read(Path.of(str(grant, "manifestPath"))), "identity"));
                competitor.release(original, "original crashed JVM and effects checked");
                Map<String, Object> next = identity(data, "alias-competitor"); assertTrue(bool(competitor.acquire(next), "lockValid", false)); competitor.release(next, "");
            }
        } finally {
            stop(child, child.info().startInstant().map(Instant::toString).orElse(""));
            Files.deleteIfExists(alias);
        }
    }

    @Test
    void incompletePreparationOrForeignParentCannotReceiveAWriteGrant() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")), parent = Files.createDirectories(data.resolve("workspaces/root"));
        Map<String, Object> original = identity(data, "owner"); Path prepared = parent.resolve("selection.json"); byte[] bytes = mapper.writeValueAsBytes(obj("taskId", "root", "state", "PREPARED")); Files.write(prepared, bytes);
        try (var guard = guard(data)) {
            assertThrows(IllegalArgumentException.class, () -> guard.acquire(original)); assertArrayEquals(bytes, Files.readAllBytes(prepared)); assertFalse(Files.exists(parent.resolve("writer.lock")));
        }
        Files.delete(prepared); Files.delete(parent); Path foreign = Files.createDirectories(directory.resolve("foreign")); alias(parent, foreign);
        try (var guard = guard(data)) { assertThrows(IllegalArgumentException.class, () -> guard.acquire(original)); assertFalse(Files.exists(foreign.resolve("writer.lock"))); }
        finally { Files.deleteIfExists(parent); }
    }

    @Test
    void changedManifestOrReplacedLockFileCannotBeConfirmedOrReused() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")); Map<String, Object> original = identity(data, "owner");
        Map<String, Object> grant;
        try (var guard = guard(data)) {
            grant = guard.acquire(original); Path manifest = Path.of(str(grant, "manifestPath")); Map<String, Object> changed = read(manifest); changed.put("fileKey", "foreign-key"); Files.write(manifest, mapper.writeValueAsBytes(changed));
            assertThrows(IllegalArgumentException.class, () -> guard.enter(original)); assertThrows(IllegalArgumentException.class, () -> guard.release(original, ""));
            Files.write(manifest, mapper.writeValueAsBytes(grant));
        }
        Path lock = Path.of(str(grant, "lockPath")), previous = lock.resolveSibling("original-writer.lock"); Files.move(lock, previous); Files.writeString(lock, "foreign bytes", StandardOpenOption.CREATE_NEW);
        try (var reopened = guard(data)) {
            assertThrows(IllegalArgumentException.class, () -> reopened.release(original, "checked")); assertThrows(IllegalArgumentException.class, () -> reopened.acquire(identityUnchecked(data, "new-owner")));
            assertEquals("foreign bytes", Files.readString(lock)); assertIdentity(original, map(read(Path.of(str(grant, "manifestPath"))), "identity"));
        }
    }

    @Test
    void failedManifestWriteLeavesAnIncompleteIntentWithoutGrantingAnyBorrow() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")); Map<String, Object> original = identity(data, "owner");
        try (var failed = new WorkspaceWriteGuard(mapper, data, (path, bytes) -> { throw new java.io.IOException("receipt storage fault"); })) {
            assertThrows(IllegalStateException.class, () -> failed.acquire(original)); assertThrows(IllegalArgumentException.class, () -> failed.enter(original));
        }
        Path parent = Path.of(str(original, "scopePath")).getParent(); assertTrue(Files.isRegularFile(parent.resolve("writer.lock"), LinkOption.NOFOLLOW_LINKS)); assertFalse(Files.exists(parent.resolve("writer.json")));
        try (var next = guard(data)) { assertThrows(IllegalArgumentException.class, () -> next.acquire(identityUnchecked(data, "other"))); }
    }

    @Test
    void heldDescriptorRejectsPathReplacementBeforeAWriteCanBorrowIt() throws Exception {
        Path data = Files.createDirectories(directory.resolve("data")); Map<String, Object> original = identity(data, "owner");
        try (var guard = guard(data)) {
            Map<String, Object> grant = guard.acquire(original); Path lock = Path.of(str(grant, "lockPath")), moved = lock.resolveSibling("moved-writer.lock");
            if (System.getProperty("os.name").startsWith("Windows")) {
                assertThrows(java.nio.file.FileSystemException.class, () -> Files.move(lock, moved));
                assertThrows(java.nio.file.FileSystemException.class, () -> Files.delete(lock));
                assertFalse(Files.exists(moved)); try (var borrow = guard.enter(original)) { assertEquals("HELD", read(Path.of(str(grant, "manifestPath"))).get("state")); }
            } else {
                Files.move(lock, moved); Files.copy(moved, lock);
                assertThrows(IllegalArgumentException.class, () -> guard.enter(original)); assertThrows(IllegalArgumentException.class, () -> guard.release(original, ""));
                Files.delete(lock); Files.move(moved, lock); try (var borrow = guard.enter(original)) { assertEquals(str(grant, "fileKey"), WorkspaceFileIdentity.key(lock)); }
            }
            assertTrue(bool(guard.release(original, ""), "protectionReleased", false));
            Files.move(lock, moved); assertFalse(Files.exists(lock)); assertTrue(Files.isRegularFile(moved));
        }
    }

    private Map<String, Object> identityUnchecked(Path data, String id) {
        try { return identity(data, id); } catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private Process child(Path data, Path reports) throws Exception {
        String java = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
        String classpath = System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
        Path nio = data.resolve("nio-tmp");
        if (System.getProperty("os.name").startsWith("Windows")) while (nio.toString().getBytes(StandardCharsets.UTF_8).length < 120) nio = nio.resolve("workbench-jdk-loopback");
        nio = Files.createDirectories(nio).toRealPath();
        return new ProcessBuilder(java, "-Dfile.encoding=UTF-8", "-Djdk.net.unixdomain.tmpdir=" + nio, "-Djdk.virtualThreadScheduler.parallelism=2", "-Djdk.virtualThreadScheduler.maxPoolSize=2", "-cp", classpath, Probe.class.getName(), data.toString(), reports.toString())
            .redirectError(reports.resolve("process.stderr").toFile()).redirectOutput(reports.resolve("process.stdout").toFile()).start();
    }

    private static void stop(Process child, String birth) throws Exception {
        if (!child.isAlive()) return;
        assertFalse(birth.isBlank()); assertEquals(birth, child.info().startInstant().orElseThrow().toString()); child.destroyForcibly(); assertTrue(child.waitFor(5, TimeUnit.SECONDS));
    }

    private static void alias(Path link, Path target) throws Exception {
        if (System.getProperty("os.name").startsWith("Windows")) {
            Process command = new ProcessBuilder("cmd.exe", "/d", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
            byte[] output = command.getInputStream().readAllBytes(); assertTrue(command.waitFor(5, TimeUnit.SECONDS)); assertEquals(0, command.exitValue(), new String(output, java.nio.charset.StandardCharsets.UTF_8));
        } else Files.createSymbolicLink(link, target);
    }

    /**
     * 子 JVM 使用原生产守卫，父测试只按其实际 PID/出生身份核对和停止。
     */
    public static final class Probe {
        public static void main(String[] arguments) throws Exception {
            ObjectMapper mapper = new ObjectMapper(); Path data = Path.of(arguments[0]).toRealPath(), reports = Path.of(arguments[1]);
            Map<String, Object> identity = obj("schemaVersion", 1, "id", "original-child-write", "taskId", "root", "projectId", "project", "taskExecutionId", "original-child-root", "dispatchActionId", "original-child-dispatch",
                "scopePath", data.resolve("workspaces/root/work").toString(), "workerId", "original-child", "workerEpoch", 1, "executorPid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString());
            WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString());
            try {
                Map<String, Object> grant = runtime.acquireWorkspaceWrite(identity); report(reports.resolve("held.json"), mapper.writeValueAsBytes(grant));
                for (int command; (command = System.in.read()) >= 0;) {
                    if (command == 'C') { runtime.closeWorkspaceWrites(); report(reports.resolve("native-closed.json"), mapper.writeValueAsBytes(obj("identity", identity, "nativeClosed", true))); }
                    else if (command == 'R') { report(reports.resolve("released.json"), mapper.writeValueAsBytes(runtime.releaseWorkspaceWrite(identity, ""))); return; }
                }
            } finally { runtime.closeWorkspaceWrites(); }
        }

        private static void report(Path path, byte[] bytes) throws Exception {
            Path temporary = path.resolveSibling(path.getFileName() + ".tmp"); Files.write(temporary, bytes, StandardOpenOption.CREATE_NEW);
            try (var channel = FileChannel.open(temporary, StandardOpenOption.WRITE)) { channel.force(true); }
            Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE);
        }
    }
}
