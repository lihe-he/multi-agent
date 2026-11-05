package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.Path;
import java.nio.file.Files;
import java.nio.channels.FileChannel;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class ExecutionStoreTest {
    @TempDir Path directory;

    @Test
    void acknowledgedResultAndEvidenceSurviveReopen() throws Exception {
        try (var close = new StoreHandle(new ExecutionStore(new ObjectMapper(), directory.toString()))) {
            var store = close.store;
            store.createTask(obj("id", "task", "state", "RUNNING", "evidence", new ArrayList<>()));
            var operation = store.beginOperation("task", "verify", "VERIFICATION", "test");
            store.markOperation(str(operation, "id"), "DISPATCHED", obj());
            store.recordEvidence("task", str(operation, "id"), obj("id", "proof", "stage", "verify", "status", "FAILED", "output", "assertion failed"));
            assertEquals(1, objects(store.get("task", "task").get("evidence")).size());
            assertEquals("ACKNOWLEDGED", str(store.get("operation", str(operation, "id")), "state"));
        }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            assertEquals("FAILED", str(reopened.get("evidence", "proof"), "status"));
            assertFalse(reopened.get("task", "task").containsKey("activeOperation"));
        } finally { reopened.close(); }
    }

    @Test
    void eventCursorReturnsBoundedCompletePages() throws Exception {
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            for (int index = 0; index < 425; index++) { store.event("task", "LOG", "event " + index); }
            var first = store.events("task", 0, 10000);
            assertEquals(200, objects(first.get("events")).size()); assertTrue(bool(first, "hasMore", false));
            long cursor = ((Number) first.get("cursor")).longValue();
            var second = store.events("task", cursor, 200);
            var third = store.events("task", ((Number) second.get("cursor")).longValue(), 200);
            assertEquals(200, objects(second.get("events")).size()); assertEquals(25, objects(third.get("events")).size());
            assertFalse(bool(third, "hasMore", true)); assertEquals(425L, third.get("cursor"));
        } finally { store.close(); }
    }

    @Test
    void unknownResultIsNotAcknowledgedAsSuccessful() throws Exception {
        var store = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            store.createTask(obj("id", "task", "evidence", new ArrayList<>()));
            var operation = store.beginOperation("task", "external", "TOOL", "remote");
            store.recordEvidence("task", str(operation, "id"), obj("id", "unknown", "stage", "external", "status", "UNKNOWN", "exitCode", 0));
            assertEquals("UNKNOWN", str(store.get("operation", str(operation, "id")), "state"));
        } finally { store.close(); }
    }

    @Test
    void dataDirectoryHasOneOwnerUntilFirstStoreCloses() throws Exception {
        var first = new ExecutionStore(new ObjectMapper(), directory.toString());
        try {
            IllegalStateException rejected = assertThrows(IllegalStateException.class, () -> new ExecutionStore(new ObjectMapper(), directory.toString()));
            assertTrue(rejected.getMessage().contains("另一个实例"));
            first.put("probe", obj("id", "still-owned", "value", 1));
            assertEquals(1, num(first.get("probe", "still-owned"), "value", 0));
        } finally { first.close(); }
        var reopened = new ExecutionStore(new ObjectMapper(), directory.toString());
        try { assertEquals(1, num(reopened.get("probe", "still-owned"), "value", 0)); }
        finally { reopened.close(); }
    }

    @Test
    void failedDatabaseInitializationReleasesOwnershipLock() throws Exception {
        Files.writeString(directory.resolve("workbench.sqlite"), "not a valid SQLite database");
        assertThrows(Exception.class, () -> new ExecutionStore(new ObjectMapper(), directory.toString()));
        try (var channel = FileChannel.open(directory.resolve(".core.lock"), StandardOpenOption.WRITE); var lock = channel.tryLock()) { assertNotNull(lock); }
        Files.delete(directory.resolve("workbench.sqlite"));
        var valid = new ExecutionStore(new ObjectMapper(), directory.toString());
        valid.close();
    }

    @Test
    void anotherJavaProcessCannotOwnTheSameDataDirectory() throws Exception {
        var first = new ExecutionStore(new ObjectMapper(), directory.toString());
        Process probe = null;
        try {
            String executable = Path.of(System.getProperty("java.home"), "bin", System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java").toString();
            probe = new ProcessBuilder(executable, "-Djdk.net.unixdomain.tmpdir=" + directory, "-cp", System.getProperty("java.class.path"), OwnershipProbe.class.getName(), directory.toString()).redirectErrorStream(true).start();
            assertTrue(probe.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            String output = new String(probe.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, probe.exitValue(), output); assertTrue(output.contains("LOCK_REJECTED"), output);
        } finally {
            if (probe != null && probe.isAlive()) { probe.destroyForcibly(); }
            first.close();
        }
    }

    public static class OwnershipProbe {
        public static void main(String[] args) throws Exception {
            try {
                var opened = new ExecutionStore(new ObjectMapper(), args[0]); opened.close();
                throw new IllegalStateException("Unexpected second owner");
            } catch (IllegalStateException exception) {
                if (!exception.getMessage().contains("另一个实例")) { throw exception; }
                System.out.println("LOCK_REJECTED");
            }
        }
    }

    private static class StoreHandle implements AutoCloseable {
        private final ExecutionStore store;
        StoreHandle(ExecutionStore store) { this.store = store; }
        public void close() throws Exception { store.close(); }
    }
}
