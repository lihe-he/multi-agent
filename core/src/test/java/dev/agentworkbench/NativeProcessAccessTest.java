package dev.agentworkbench;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 原生快照缺失、PID 复用和逐轮刷新保持身份事实，读取失败不伪造停止。
 */
class NativeProcessAccessTest {
    private static final long PID = Integer.MAX_VALUE;
    private static final String ORIGINAL_BIRTH = "2026-11-16T00:00:00Z";
    private static final String REUSED_BIRTH = "2026-11-16T00:01:00Z";

    @Test
    void oneCompleteWindowsSnapshotCoversTheWholeInspectionAndTheNextScopeReadsAgain() {
        AtomicInteger nativeCalls = new AtomicInteger(), enumerationCalls = new AtomicInteger();
        AtomicReference<Map<Long, String>> current = new AtomicReference<>(Map.of(PID, ORIGINAL_BIRTH));
        var access = new ManagedProcessLedger.NativeProcessAccess(true, () -> { nativeCalls.incrementAndGet(); return current.get(); }, () -> {
            enumerationCalls.incrementAndGet(); throw new AssertionError("完整原生快照不应重复枚举系统 PID");
        });
        try (var scope = access.inspection()) {
            for (int index = 0; index < 100; index++) assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, ORIGINAL_BIRTH), access.inspect(PID));
            try (var nested = access.inspection()) { assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, ORIGINAL_BIRTH), access.inspect(PID)); }
        }
        assertEquals(1, nativeCalls.get()); assertEquals(0, enumerationCalls.get());
        current.set(Map.of(PID, REUSED_BIRTH));
        try (var scope = access.inspection()) { assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, REUSED_BIRTH), access.inspect(PID)); }
        assertEquals(2, nativeCalls.get()); assertEquals(0, enumerationCalls.get());
    }

    @Test
    void failedWindowsSnapshotRetainsVisiblePidAsUnverifiableAndRefreshesAfterTheScope() {
        AtomicInteger nativeCalls = new AtomicInteger(), enumerationCalls = new AtomicInteger();
        AtomicReference<Set<Long>> visible = new AtomicReference<>(Set.of(PID));
        var access = new ManagedProcessLedger.NativeProcessAccess(true, () -> { nativeCalls.incrementAndGet(); throw new IllegalStateException("原快照读取失败"); }, () -> {
            enumerationCalls.incrementAndGet(); return visible.get();
        });
        try (var scope = access.inspection()) {
            for (int index = 0; index < 100; index++) assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, ""), access.inspect(PID));
        }
        assertEquals(1, nativeCalls.get()); assertEquals(1, enumerationCalls.get());
        visible.set(Set.of());
        try (var scope = access.inspection()) { assertEquals(new ManagedProcessLedger.ProcessSnapshot(false, ""), access.inspect(PID)); }
        assertEquals(2, nativeCalls.get()); assertEquals(2, enumerationCalls.get());
    }

    @Test
    void absentBirthAndUnavailableFallbackCannotClaimAnOriginalProcessStopped() {
        var unknownBirth = new ManagedProcessLedger.NativeProcessAccess(true, () -> Map.of(PID, ""), () -> { throw new AssertionError("已取得完整可见清单"); });
        assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, ""), unknownBirth.inspect(PID));
        var unavailable = new ManagedProcessLedger.NativeProcessAccess(true, () -> { throw new UnsatisfiedLinkError("原生读取不可用"); }, () -> {
            throw new IllegalStateException("系统 PID 清单也无法读取");
        });
        assertThrows(IllegalStateException.class, () -> unavailable.inspect(PID));
        var nonWindows = new ManagedProcessLedger.NativeProcessAccess(false, () -> { throw new AssertionError("非 Windows 不读原生快照"); }, () -> Set.of(PID));
        assertEquals(new ManagedProcessLedger.ProcessSnapshot(true, ""), nonWindows.inspect(PID));
    }
}
