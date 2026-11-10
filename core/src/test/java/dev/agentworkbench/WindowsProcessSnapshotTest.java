package dev.agentworkbench;

import com.sun.jna.Memory;
import java.time.Instant;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

class WindowsProcessSnapshotTest {
    private static final String BIRTH = "2026-11-10T01:02:03.004Z";

    private void entry(Memory buffer, int offset, int next, int pointerBytes, long pid, String birth) {
        buffer.setInt(offset, next);
        buffer.setLong(offset + 32, (Instant.parse(birth).toEpochMilli() + 11644473600000L) * 10000 + 9999);
        if (pointerBytes == 8) buffer.setLong(offset + 80, pid); else buffer.setInt(offset + 68, (int) pid);
    }

    @ParameterizedTest
    @ValueSource(ints = { 4, 8 })
    void bothPointerWidthsPreservePidAndTheSameMillisecondBirth(int pointerBytes) {
        try (Memory buffer = new Memory(256)) {
            buffer.clear(); entry(buffer, 0, 128, pointerBytes, 41, BIRTH); entry(buffer, 128, 0, pointerBytes, 42, "2026-11-10T02:03:04.005Z");
            var snapshot = WindowsProcessSnapshot.parse(buffer, 256, pointerBytes, 41, BIRTH);
            assertEquals(BIRTH, snapshot.get(41L)); assertEquals("2026-11-10T02:03:04.005Z", snapshot.get(42L)); assertEquals(2, snapshot.size());
        }
    }

    @Test
    void corruptLinksOrIdentityNeverProduceAUsableSnapshot() {
        try (Memory buffer = new Memory(256)) {
            buffer.clear(); entry(buffer, 0, 128, 8, 41, BIRTH); entry(buffer, 128, 0, 8, 42, BIRTH);
            assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 257, 8, 41, BIRTH));
            assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 16, 41, BIRTH));
            assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 8, 41, "wrong-birth"));
            buffer.setInt(0, -1); assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 8, 41, BIRTH));
            buffer.setInt(0, 8); assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 8, 41, BIRTH));
            buffer.setInt(0, 128); buffer.setLong(128 + 80, 41); assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 8, 41, BIRTH));
            buffer.setLong(128 + 80, 42); buffer.setLong(128 + 32, -1); assertThrows(IllegalArgumentException.class, () -> WindowsProcessSnapshot.parse(buffer, 256, 8, 41, BIRTH));
        }
    }

    @Test
    void actualWindowsSnapshotMatchesReadableProcessBirths() {
        assumeTrue(System.getProperty("os.name", "").startsWith("Windows"), "Windows native system process snapshot");
        var snapshot = WindowsProcessSnapshot.capture();
        assertEquals(ProcessHandle.current().info().startInstant().orElseThrow().toString(), snapshot.get(ProcessHandle.current().pid()));
        ProcessHandle parent = ProcessHandle.current().parent().orElseThrow();
        assertEquals(parent.info().startInstant().orElseThrow().toString(), snapshot.get(parent.pid()));
    }
}
