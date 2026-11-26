package dev.agentworkbench.runtime;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 为 bubblewrap 生成固定架构的经典 BPF，封闭宿主进程访问和虚拟机套接字通道。
 */
final class LinuxSeccompFilter {
    private static final int LOAD_WORD_ABSOLUTE = 0x20;
    private static final int JUMP_EQUAL = 0x15;
    private static final int JUMP_BITS_SET = 0x45;
    private static final int AND_VALUE = 0x54;
    private static final int RETURN = 0x06;
    private static final int KILL_PROCESS = 0x80000000;
    private static final int DENY_PERMISSION = 0x00050001;
    private static final int ALLOW = 0x7fff0000;
    private static final int SYSCALL_NUMBER_OFFSET = 0;
    private static final int ARCHITECTURE_OFFSET = 4;
    // seccomp_data 参数是本机字节序的 64 位值；大端主机的低 32 位位于后半字。
    private static final int FIRST_ARGUMENT_LOW_WORD_OFFSET = ByteOrder.nativeOrder() == ByteOrder.BIG_ENDIAN ? 20 : 16;

    private LinuxSeccompFilter() { }

    /**
     * 返回可由 bubblewrap --seccomp 读取的本机字节序 sock_filter 数组；未知架构拒绝执行。
     */
    static byte[] program(String architecture, boolean denyNetwork) {
        String normalized = architecture == null ? "" : architecture.toLowerCase(Locale.ROOT);
        boolean x86 = switch (normalized) {
            case "amd64", "x86_64" -> true;
            case "aarch64", "arm64" -> false;
            default -> throw new IllegalArgumentException("Linux 沙箱仅支持 x86_64 和 aarch64 架构");
        };
        int auditArchitecture = x86 ? 0xc000003e : 0xc00000b7;
        int socket = x86 ? 41 : 198, socketPair = x86 ? 53 : 199;
        // ptrace、keyring、性能/内存接口及句柄访问不能用于取得宿主能力。
        int[] deniedSyscalls = x86
            ? new int[] { 101, 248, 249, 250, 272, 298, 304, 308, 310, 311, 321, 323, 425, 426, 427, 438 }
            : new int[] { 97, 117, 217, 218, 219, 241, 265, 268, 270, 271, 280, 282, 425, 426, 427, 438 };
        List<Instruction> instructions = new ArrayList<>();
        instructions.add(statement(LOAD_WORD_ABSOLUTE, ARCHITECTURE_OFFSET));
        instructions.add(jump(JUMP_EQUAL, auditArchitecture, 1, 0));
        instructions.add(statement(RETURN, KILL_PROCESS));
        instructions.add(statement(LOAD_WORD_ABSOLUTE, SYSCALL_NUMBER_OFFSET));
        if (x86) {
            // x32 与 x86_64 共用 audit 架构，但系统调用号不同，不能通过兼容 ABI 绕过规则。
            instructions.add(jump(JUMP_BITS_SET, 0x40000000, 0, 1));
            instructions.add(statement(RETURN, KILL_PROCESS));
        }
        for (int syscall : deniedSyscalls) deny(instructions, syscall);
        if (denyNetwork) deny(instructions, socket);
        else allowFamilies(instructions, socket, 2, 10);
        // 共享网络只授予 IPv4/IPv6；私有 Unix socketpair 不暴露宿主抽象套接字。
        allowPrivateStreamPair(instructions, socketPair);
        instructions.add(statement(RETURN, ALLOW));

        ByteBuffer encoded = ByteBuffer.allocate(instructions.size() * 8).order(ByteOrder.nativeOrder());
        for (Instruction instruction : instructions) {
            encoded.putShort((short) instruction.code()).put((byte) instruction.whenTrue())
                .put((byte) instruction.whenFalse()).putInt(instruction.value());
        }
        return encoded.array();
    }

    private static void deny(List<Instruction> instructions, int syscall) {
        instructions.add(jump(JUMP_EQUAL, syscall, 0, 1));
        instructions.add(statement(RETURN, DENY_PERMISSION));
    }

    private static void allowFamilies(List<Instruction> instructions, int syscall, int... families) {
        instructions.add(jump(JUMP_EQUAL, syscall, 0, families.length + 3));
        instructions.add(statement(LOAD_WORD_ABSOLUTE, FIRST_ARGUMENT_LOW_WORD_OFFSET));
        for (int index = 0; index < families.length; index++) {
            instructions.add(jump(JUMP_EQUAL, families[index], families.length - index, 0));
        }
        instructions.add(statement(RETURN, DENY_PERMISSION));
        instructions.add(statement(RETURN, ALLOW));
    }

    /**
     * 数据报 pair 可另行指定宿主抽象地址；仅保留不可重定向的流式 pair 及其描述符标志。
     */
    private static void allowPrivateStreamPair(List<Instruction> instructions, int syscall) {
        instructions.add(jump(JUMP_EQUAL, syscall, 0, 8));
        instructions.add(statement(LOAD_WORD_ABSOLUTE, FIRST_ARGUMENT_LOW_WORD_OFFSET));
        instructions.add(jump(JUMP_EQUAL, 1, 1, 0));
        instructions.add(statement(RETURN, DENY_PERMISSION));
        instructions.add(statement(LOAD_WORD_ABSOLUTE, FIRST_ARGUMENT_LOW_WORD_OFFSET + 8));
        instructions.add(statement(AND_VALUE, 0xf));
        instructions.add(jump(JUMP_EQUAL, 1, 1, 0));
        instructions.add(statement(RETURN, DENY_PERMISSION));
        instructions.add(statement(RETURN, ALLOW));
    }

    private static Instruction statement(int code, int value) { return new Instruction(code, 0, 0, value); }
    private static Instruction jump(int code, int value, int whenTrue, int whenFalse) { return new Instruction(code, whenTrue, whenFalse, value); }

    private record Instruction(int code, int whenTrue, int whenFalse, int value) { }
}
