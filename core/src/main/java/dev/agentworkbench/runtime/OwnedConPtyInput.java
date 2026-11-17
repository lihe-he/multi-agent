package dev.agentworkbench.runtime;

import com.sun.jna.Native;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinError;
import com.sun.jna.platform.win32.WinNT;
import com.sun.jna.ptr.IntByReference;
import java.io.IOException;
import java.io.InputStream;
import java.util.HexFormat;
import java.util.Objects;

/**
 * 固定发布类的读取管道复制为不可继承的原句柄，原生 EOF 后才由读取者关闭。
 */
final class OwnedConPtyInput extends InputStream {
    private static final String INPUT_CLASS = "com.pty4j.windows.conpty.WinHandleInputStream";
    private static final String INPUT_CLASS_SHA256 = "1f44486911d333b5738d7adf0a150c9fb4cba595309f28b8917d36fa49f80df7";
    private static final int DUPLICATE_SAME_ACCESS = 0x00000002;
    private final WinNT.HANDLE handle;
    private boolean closed;

    private OwnedConPtyInput(WinNT.HANDLE handle) { this.handle = handle; }

    static OwnedConPtyInput open(InputStream original) throws IOException {
        Class<?> type = original.getClass();
        if (!INPUT_CLASS.equals(type.getName())) throw new IOException("原 ConPTY 读取类不属于已核验发布包");
        try (InputStream code = type.getResourceAsStream("WinHandleInputStream.class")) {
            if (code == null) throw new IOException("原 ConPTY 读取类字节缺失");
            byte[] bytes = code.readNBytes(65_537);
            if (bytes.length > 65_536 || !INPUT_CLASS_SHA256.equals(HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes))))
                throw new IOException("原 ConPTY 读取类字节与固定发布包不一致");
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IOException(exception); }
        try {
            var field = type.getDeclaredField("myReadPipe");
            if (!field.trySetAccessible() || !(field.get(original) instanceof WinNT.HANDLE source) || WinBase.INVALID_HANDLE_VALUE.equals(source))
                throw new IOException("原 ConPTY 读取管道身份缺失");
            Kernel32 api = Kernel32.INSTANCE; WinNT.HANDLE process = api.GetCurrentProcess(); var duplicate = new WinNT.HANDLEByReference();
            if (!api.DuplicateHandle(process, source, process, duplicate, 0, false, DUPLICATE_SAME_ACCESS)) throw nativeFailure("复制原读取句柄");
            WinNT.HANDLE owned = duplicate.getValue();
            if (owned == null || WinBase.INVALID_HANDLE_VALUE.equals(owned)) throw new IOException("原读取句柄复制回执无效");
            if (!api.SetHandleInformation(owned, WinBase.HANDLE_FLAG_INHERIT, 0)) {
                IOException failure = nativeFailure("固定原读取句柄不可继承");
                if (!api.CloseHandle(owned)) failure.addSuppressed(nativeFailure("关闭未完成核验的读取句柄"));
                throw failure;
            }
            return new OwnedConPtyInput(owned);
        } catch (ReflectiveOperationException | RuntimeException exception) { throw new IOException("固定 ConPTY 读取布局无法核验", exception); }
    }

    @Override public int read() throws IOException {
        byte[] bytes = new byte[1]; return read(bytes, 0, 1) == 1 ? Byte.toUnsignedInt(bytes[0]) : -1;
    }

    @Override public synchronized int read(byte[] bytes, int offset, int length) throws IOException {
        Objects.checkFromIndexSize(offset, length, bytes.length); if (length == 0) return 0;
        if (closed) throw new IOException("原 ConPTY 独立读取句柄已经关闭");
        byte[] buffer = new byte[length]; var received = new IntByReference();
        if (!Kernel32.INSTANCE.ReadFile(handle, buffer, length, received, null)) {
            int error = Native.getLastError();
            if (error == WinError.ERROR_BROKEN_PIPE) return -1;
            throw new IOException("原 ConPTY 读取失败，错误码 " + error);
        }
        int count = received.getValue(); if (count == 0) return -1;
        if (count < 0 || count > length) throw new IOException("原 ConPTY 读取长度越过原帧边界");
        System.arraycopy(buffer, 0, bytes, offset, count); return count;
    }

    @Override public synchronized void close() throws IOException {
        if (closed) return;
        if (!Kernel32.INSTANCE.CloseHandle(handle)) throw nativeFailure("关闭原独立读取句柄");
        closed = true;
    }

    synchronized boolean closed() { return closed; }
    private static IOException nativeFailure(String operation) { return new IOException(operation + "失败，错误码 " + Native.getLastError()); }
}
