package dev.agentworkbench;

import com.sun.jna.Function;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.NativeLibrary;
import com.sun.jna.ptr.IntByReference;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Windows 系统进程快照补充受限句柄的出生时间，只读取元数据，不授予停止权限。
 */
final class WindowsProcessSnapshot {
    private static final int MAXIMUM_BYTES = 32 * 1024 * 1024;
    private static final int LENGTH_MISMATCH = 0xc0000004;
    private static final long WINDOWS_EPOCH_MILLISECONDS = 11644473600000L;

    private WindowsProcessSnapshot() { }

    /**
     * 有界读取完整快照；布局和本 JVM 出生时间必须同时核验后才能使用。
     */
    static Map<Long, String> capture() {
        if (!System.getProperty("os.name", "").startsWith("Windows")) return Map.of();
        Function query = NativeLibrary.getInstance("ntdll").getFunction("NtQuerySystemInformation", Function.ALT_CONVENTION);
        int capacity = 1024 * 1024;
        for (int attempt = 0; attempt < 8; attempt++) {
            try (Memory buffer = new Memory(capacity)) {
                IntByReference returned = new IntByReference();
                int status = query.invokeInt(new Object[] { 5, buffer, capacity, returned });
                if (status == 0) {
                    String birth = ProcessHandle.current().info().startInstant().map(Object::toString).orElseThrow();
                    return parse(buffer, returned.getValue(), Native.POINTER_SIZE, ProcessHandle.current().pid(), birth);
                }
                if (status != LENGTH_MISMATCH) throw new IllegalStateException("Windows 系统进程快照读取失败，NTSTATUS 0x" + Integer.toHexString(status));
                long required = Math.max((long) capacity * 2, (long) returned.getValue() + 65536);
                if (returned.getValue() < 0 || required > MAXIMUM_BYTES) throw new IllegalStateException("Windows 系统进程快照超出有界缓冲区");
                capacity = (int) required;
            }
        }
        throw new IllegalStateException("Windows 系统进程快照未在有界重试内完成");
    }

    /**
     * 只读取稳定前缀的 PID 和 CreateTime；无效偏移、重复 PID 或本进程不匹配均拒绝。
     */
    static Map<Long, String> parse(Memory buffer, int length, int pointerBytes, long selfPid, String selfBirth) {
        if (pointerBytes != 4 && pointerBytes != 8) throw new IllegalArgumentException("未知 Windows 指针宽度");
        int pidOffset = pointerBytes == 8 ? 80 : 68, prefix = pidOffset + pointerBytes;
        if (length < prefix || length > buffer.size() || selfBirth.isBlank()) throw new IllegalArgumentException("Windows 系统进程快照长度无法核验");
        Map<Long, String> result = new LinkedHashMap<>();
        long offset = 0;
        while (true) {
            if (offset > length - prefix) throw new IllegalArgumentException("Windows 系统进程快照偏移越界");
            long pid = pointerBytes == 8 ? buffer.getLong(offset + pidOffset) : Integer.toUnsignedLong(buffer.getInt(offset + pidOffset));
            long ticks = buffer.getLong(offset + 32);
            if (pid < 0 || pid > 0xffffffffL || ticks < 0) throw new IllegalArgumentException("Windows 系统进程身份字段无效");
            if (pid != 0) {
                String birth = ticks == 0 ? "" : Instant.ofEpochMilli(ticks / 10000 - WINDOWS_EPOCH_MILLISECONDS).toString();
                if (result.putIfAbsent(pid, birth) != null) throw new IllegalArgumentException("Windows 系统进程快照出现重复 PID");
            }
            long next = Integer.toUnsignedLong(buffer.getInt(offset));
            if (next == 0) break;
            if (next < prefix || next % pointerBytes != 0 || next > length - prefix - offset) throw new IllegalArgumentException("Windows 系统进程快照链无法核验");
            offset += next;
        }
        if (!selfBirth.equals(result.get(selfPid))) throw new IllegalArgumentException("Windows 系统进程快照的本 JVM 出生时间不匹配");
        return Map.copyOf(result);
    }
}
