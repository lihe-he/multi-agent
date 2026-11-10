package dev.agentworkbench.runtime;

import com.sun.jna.Memory;
import com.sun.jna.platform.win32.Kernel32;
import com.sun.jna.platform.win32.WinBase;
import com.sun.jna.platform.win32.WinDef.DWORD;
import com.sun.jna.platform.win32.WinNT;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HexFormat;

/**
 * 锁文件身份来自文件系统；Windows 的空 NIO fileKey 使用原生卷编号与 128 位文件编号。
 */
final class WorkspaceFileIdentity {
    private WorkspaceFileIdentity() { }

    static String key(Path path) throws IOException {
        BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attributes.isRegularFile()) throw new IllegalArgumentException("写入锁不是原普通文件");
        if (attributes.fileKey() != null) return "NIO:" + attributes.fileKey();
        if (System.getProperty("os.name", "").startsWith("Windows")) return windows(path);
        throw new IllegalArgumentException("文件系统未提供可核验的写入锁文件身份");
    }

    /**
     * 只打开属性句柄，不读取锁文件内容；共享模式保留原 FileChannel 锁的独占语义。
     */
    private static String windows(Path path) throws IOException {
        Kernel32 api = Kernel32.INSTANCE;
        String supplied = path.toAbsolutePath().normalize().toString();
        String nativePath = supplied.startsWith("\\\\?\\") ? supplied : supplied.startsWith("\\\\") ? "\\\\?\\UNC\\" + supplied.substring(2) : "\\\\?\\" + supplied;
        WinNT.HANDLE handle = api.CreateFile(nativePath, WinNT.FILE_READ_ATTRIBUTES, WinNT.FILE_SHARE_READ | WinNT.FILE_SHARE_WRITE | WinNT.FILE_SHARE_DELETE,
            null, WinNT.OPEN_EXISTING, WinNT.FILE_FLAG_OPEN_REPARSE_POINT, null);
        if (handle == null || WinBase.INVALID_HANDLE_VALUE.equals(handle)) throw nativeFailure(api);
        Throwable failure = null;
        try (Memory attributes = new Memory(8); Memory identity = new Memory(24)) {
            if (!api.GetFileInformationByHandleEx(handle, WinBase.FileAttributeTagInfo, attributes, new DWORD(attributes.size()))) throw nativeFailure(api);
            if ((attributes.getInt(0) & (WinNT.FILE_ATTRIBUTE_REPARSE_POINT | WinNT.FILE_ATTRIBUTE_DIRECTORY)) != 0) throw new IllegalArgumentException("原生写入锁句柄指向了重解析点或目录");
            if (!api.GetFileInformationByHandleEx(handle, WinBase.FileIdInfo, identity, new DWORD(identity.size()))) throw nativeFailure(api);
            byte[] identifier = identity.getByteArray(8, 16);
            if (java.util.Arrays.equals(identifier, new byte[16])) throw new IllegalArgumentException("文件系统没有返回稳定的原生文件编号");
            return "WINDOWS:" + Long.toUnsignedString(identity.getLong(0), 16) + ":" + HexFormat.of().formatHex(identifier);
        } catch (IOException | RuntimeException | Error exception) { failure = exception; throw exception; }
        finally {
            if (!api.CloseHandle(handle)) {
                IOException closing = nativeFailure(api);
                if (failure == null) throw closing; else failure.addSuppressed(closing);
            }
        }
    }

    private static IOException nativeFailure(Kernel32 api) { return new IOException("Windows 原生文件身份无法核验，错误码 " + api.GetLastError()); }
}
