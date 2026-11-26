package dev.agentworkbench.runtime;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * 为原生 CLI 启动解析可验证的操作系统沙箱命令。
 */
public interface NativeSandboxPolicy {
    enum FileAccess { READ_ONLY, WORKSPACE_WRITE }
    enum NetworkAccess { ALLOW, DENY }
    enum Status { ENFORCED, UNAVAILABLE }

    record Request(Path workspace, FileAccess fileAccess, NetworkAccess networkAccess) {
        public Request {
            workspace = Objects.requireNonNull(workspace).toAbsolutePath().normalize();
            Objects.requireNonNull(fileAccess);
            Objects.requireNonNull(networkAccess);
        }
    }

    record Decision(Status status, List<String> command, String policyId, String reason, Map<String, String> environment, boolean inheritEnvironment) {
        public Decision(Status status, List<String> command, String policyId, String reason) {
            this(status, command, policyId, reason, Map.of(), true);
        }

        public Decision {
            Objects.requireNonNull(status);
            command = List.copyOf(command);
            policyId = Objects.requireNonNullElse(policyId, "");
            reason = Objects.requireNonNullElse(reason, "");
            environment = Map.copyOf(environment);
            if (status == Status.ENFORCED && (command.isEmpty() || policyId.isBlank())) {
                throw new IllegalArgumentException("已强制执行的沙箱回执必须包含命令和策略编号");
            }
            if (status == Status.UNAVAILABLE && !command.isEmpty()) {
                throw new IllegalArgumentException("不可用的沙箱策略不能返回可执行命令");
            }
        }

        public static Decision enforced(List<String> command, String policyId) {
            return new Decision(Status.ENFORCED, command, policyId, "");
        }

        /**
         * 严格启动只接收显式环境，凭据不能通过包装器参数传递。
         */
        public static Decision isolated(List<String> command, String policyId, Map<String, String> environment) {
            return new Decision(Status.ENFORCED, command, policyId, "", environment, false);
        }

        public static Decision unavailable(String reason) {
            return new Decision(Status.UNAVAILABLE, List.of(), "", reason);
        }
    }

    /**
     * 将完整原生命令包装为实际受策略约束的操作系统启动命令。
     */
    Decision prepare(Request request, List<String> command);

    static NativeSandboxPolicy unavailable() {
        return (request, command) -> Decision.unavailable("当前平台没有已验证的原生 CLI 沙箱实现");
    }

    /**
     * 独立 Worker 和直接运行时使用相同平台选择，不从模型参数加载策略。
     */
    static NativeSandboxPolicy platform(Path protectedData) {
        return System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).equals("linux")
            ? new LinuxBubblewrapSandboxPolicy(protectedData) : unavailable();
    }
}
