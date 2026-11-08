import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 独立于核心与数据库核验测试夹具的原 PID/出生时间；失败清理也不终止复用 PID。
 */
class ProcessRecoveryProbe {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !java.util.Set.of("inspect", "stop").contains(args[0])) throw new IllegalArgumentException("inspect|stop PID original-birth");
        long pid = Long.parseLong(args[1]); String birth = Instant.parse(args[2]).toString();
        State before = inspect(pid, birth), after = before; boolean requested = false;
        if ("stop".equals(args[0]) && before.matchesOriginal() && pid != ProcessHandle.current().pid()) {
            requested = before.handle().destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do { after = inspect(pid, birth); if (!after.originalStopped()) Thread.sleep(20); }
            while (!after.originalStopped() && System.nanoTime() < deadline);
        }
        System.out.printf("{\"pid\":%d,\"originalStartedAt\":\"%s\",\"currentStartedAt\":\"%s\",\"alive\":%s,\"matchesOriginal\":%s,\"stopRequested\":%s,\"beforeStopAlive\":%s,\"originalStopped\":%s}%n",
            pid, birth, after.startedAt(), after.alive(), after.matchesOriginal(), requested, before.alive(), after.originalStopped());
    }

    /**
     * 句柄与独立 PID 枚举共同核验；缺少出生时间不能证明原进程已停止。
     */
    private static State inspect(long pid, String birth) {
        ProcessHandle handle = ProcessHandle.of(pid).orElse(null);
        String actual = handle == null ? "" : handle.info().startInstant().map(Object::toString).orElse("");
        boolean alive = handle != null && handle.isAlive() || ProcessHandle.allProcesses().anyMatch(item -> item.pid() == pid);
        boolean matches = alive && birth.equals(actual), stopped = !alive || !actual.isBlank() && !birth.equals(actual);
        return new State(handle, actual, alive, matches, stopped);
    }

    private record State(ProcessHandle handle, String startedAt, boolean alive, boolean matchesOriginal, boolean originalStopped) { }
}
