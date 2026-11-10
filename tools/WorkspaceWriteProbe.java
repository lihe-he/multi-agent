package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import static dev.agentworkbench.Json.*;

/**
 * 第二 JVM 核验原锁竞争和崩溃后的持久意图，加载被核对的最终 Jar 类。
 */
public class WorkspaceWriteProbe {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 2 || !java.util.Set.of("lock", "contend", "own").contains(arguments[0])) throw new IllegalArgumentException("lock original-lock-path | contend fixture-data identity-file | own new-probe-data");
        ObjectMapper mapper = new ObjectMapper(); Map<String, Object> result = obj("pid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().map(Instant::toString).orElseThrow());
        if ("lock".equals(arguments[0])) {
            try (var channel = FileChannel.open(Path.of(arguments[1]), StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = channel.tryLock()) { result.put("acquired", lock != null); }
        } else if ("own".equals(arguments[0])) {
            if (arguments.length != 2) throw new IllegalArgumentException("own requires a new dedicated probe data directory");
            own(mapper, Path.of(arguments[1]), result);
        } else {
            if (arguments.length != 3) throw new IllegalArgumentException("contend requires the original identity file");
            Map<String, Object> identity = new LinkedHashMap<>(mapper.readValue(Files.readAllBytes(Path.of(arguments[2])), new TypeReference<Map<String, Object>>() {}));
            identity.putAll(obj("id", UUID.randomUUID().toString(), "taskExecutionId", "independent-contender-root", "dispatchActionId", "independent-contender-action", "workerId", "independent-contender", "executorPid", ProcessHandle.current().pid(), "processStartedAt", result.get("processStartedAt")));
            WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, arguments[1]);
            try {
                Map<String, Object> grant = runtime.acquireWorkspaceWrite(identity); result.putAll(obj("acquired", true, "unexpectedGrant", grant)); runtime.releaseWorkspaceWrite(identity, "");
            } catch (IllegalArgumentException exception) { result.putAll(obj("acquired", false, "rejectedReason", exception.getMessage())); }
            finally { runtime.closeWorkspaceWrites(); }
        }
        System.out.println(mapper.writeValueAsString(result));
    }

    /**
     * 内置 JRE 用已编译夹具核验实际授予、借用与释放，不依赖 javac 或重放任务。
     */
    private static void own(ObjectMapper mapper, Path supplied, Map<String, Object> result) throws Exception {
        Path data = supplied.toAbsolutePath().normalize();
        if (Files.exists(data)) throw new IllegalArgumentException("probe data directory must be new");
        Files.createDirectories(data); data = data.toRealPath();
        String taskId = "bundled-jre-" + UUID.randomUUID();
        Path scope = data.resolve("workspaces").resolve(taskId).resolve("work");
        Map<String, Object> identity = obj("schemaVersion", 1, "id", UUID.randomUUID().toString(), "taskId", taskId, "projectId", "bundled-jre-probe",
            "taskExecutionId", "bundled-jre-probe-root", "dispatchActionId", "bundled-jre-probe-action", "attempt", 1, "scopePath", scope.toString(),
            "scopeKey", System.getProperty("os.name", "").startsWith("Windows") ? scope.toString().toLowerCase(java.util.Locale.ROOT) : scope.toString(),
            "workerId", "bundled-jre-probe-worker", "executionNodeId", "bundled-jre-probe-node", "workerEpoch", 1,
            "executorPid", result.get("pid"), "processStartedAt", result.get("processStartedAt"), "ownerCoreSessionId", "bundled-jre-probe-session",
            "ownerCorePid", result.get("pid"), "ownerCoreStartedAt", result.get("processStartedAt"), "authorizationVersion", 1, "workspaceSelectionVersion", 1, "strategy", "CLONE", "baseline", "");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString());
        try {
            Map<String, Object> grant = runtime.acquireWorkspaceWrite(identity); result.putAll(obj("acquired", true, "grant", grant));
            try (var borrow = runtime.borrowWorkspaceWrite(identity)) {
                try { runtime.releaseWorkspaceWrite(identity, ""); throw new IllegalStateException("borrowed original lock was released early"); }
                catch (IllegalArgumentException expected) { result.put("releaseDuringBorrowRejected", true); }
            }
            result.put("release", runtime.releaseWorkspaceWrite(identity, ""));
            try (var channel = FileChannel.open(scope.getParent().resolve("writer.lock"), StandardOpenOption.READ, StandardOpenOption.WRITE); var lock = channel.tryLock()) {
                if (lock == null) throw new IllegalStateException("released original lock was still held");
                result.put("lockAvailableAfterRelease", true);
            }
            result.putAll(obj("javaHome", System.getProperty("java.home"), "javaVersion", System.getProperty("java.version"), "dataDirectory", data.toString(),
                "manifest", mapper.readValue(Files.readAllBytes(scope.getParent().resolve("writer.json")), new TypeReference<Map<String, Object>>() {})));
            if (System.getProperty("os.name", "").startsWith("Windows")) {
                var processes = WindowsProcessSnapshot.capture();
                result.putAll(obj("nativeProcessBirth", processes.get(ProcessHandle.current().pid()), "nativeProcessSnapshotCount", processes.size()));
            }
        } finally { runtime.closeWorkspaceWrites(); }
    }
}
