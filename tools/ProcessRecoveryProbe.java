import java.time.Instant;
import java.util.concurrent.TimeUnit;

/**
 * 独立于核心与数据库核验测试夹具的原 PID/出生时间；失败清理也不终止复用 PID。
 */
class ProcessRecoveryProbe {
    public static void main(String[] args) throws Exception {
        if (args.length == 2 && java.util.Set.of("inspect-all", "stop-all").contains(args[0])) { batch(args); return; }
        if (args.length != 3 || !java.util.Set.of("inspect", "stop", "identity").contains(args[0])) throw new IllegalArgumentException("inspect|stop|identity PID original-birth");
        long pid = Long.parseLong(args[1]);
        if ("identity".equals(args[0])) {
            ProcessHandle original = ProcessHandle.of(pid).orElseThrow(); String originalBirth = original.info().startInstant().orElseThrow().toString();
            if (!original.isAlive()) throw new IllegalStateException("Original fixture process already exited");
            System.out.printf("{\"pid\":%d,\"processStartedAt\":\"%s\"}%n", pid, originalBirth); return;
        }
        String birth = Instant.parse(args[2]).toString();
        State before = inspect(pid, birth), after = before; boolean requested = false;
        if ("stop".equals(args[0]) && before.matchesOriginal() && before.handle() != null && birth.equals(before.handle().info().startInstant().map(Object::toString).orElse("")) && pid != ProcessHandle.current().pid()) {
            requested = before.handle().destroyForcibly();
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
            do { after = inspect(pid, birth); if (!after.originalStopped()) Thread.sleep(20); }
            while (!after.originalStopped() && System.nanoTime() < deadline);
        }
        System.out.printf("{\"pid\":%d,\"originalStartedAt\":\"%s\",\"currentStartedAt\":\"%s\",\"alive\":%s,\"matchesOriginal\":%s,\"stopRequested\":%s,\"beforeStopAlive\":%s,\"originalStopped\":%s}%n",
            pid, birth, after.startedAt(), after.alive(), after.matchesOriginal(), requested, before.alive(), after.originalStopped());
    }

    /**
     * 专属夹具在单个 JVM 中核验全部原身份，前后各枚举一次 PID，仍逐项核对出生时间。
     */
    private static void batch(String[] args) throws Exception {
        var file = java.nio.file.Path.of(args[1]).toAbsolutePath().normalize();
        if (!java.nio.file.Files.isRegularFile(file) || !java.nio.file.Files.isRegularFile(file.getParent().resolve("fixture.json"))
            || !file.getFileName().toString().endsWith("-identities.tsv") || java.nio.file.Files.size(file) > 2 * 1024 * 1024) throw new IllegalArgumentException("Dedicated fixture identity list is required");
        var identities = new java.util.ArrayList<Identity>();
        for (String line : java.nio.file.Files.readAllLines(file, java.nio.charset.StandardCharsets.UTF_8)) {
            String[] fields = line.split("\t", -1);
            if (fields.length != 2 || !fields[0].matches("[1-9][0-9]*")) throw new IllegalArgumentException("Invalid original process identity");
            identities.add(new Identity(Long.parseLong(fields[0]), Instant.parse(fields[1]).toString()));
        }
        if (identities.isEmpty() || identities.size() > 10000) throw new IllegalArgumentException("Fixture identity count is outside the bounded range");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(60);
        var visibleBefore = new Visibility(visiblePids());
        var originalStates = new java.util.ArrayList<State>();
        var requested = new java.util.ArrayList<Boolean>();
        for (Identity identity : identities) {
            if (System.nanoTime() >= deadline) throw new IllegalStateException("Original identity batch exceeded its bounded deadline");
            State before = inspectBatch(identity, visibleBefore);
            originalStates.add(before);
            boolean stopRequested = false;
            if ("stop-all".equals(args[0]) && before.matchesOriginal() && before.handle() != null && identity.birth().equals(before.handle().info().startInstant().map(Object::toString).orElse("")) && identity.pid() != ProcessHandle.current().pid()) {
                stopRequested = before.handle().destroyForcibly();
                long stopDeadline = Math.min(deadline, System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
                State after;
                do { after = inspect(identity.pid(), identity.birth()); if (!after.originalStopped()) Thread.sleep(20); }
                while (!after.originalStopped() && System.nanoTime() < stopDeadline);
            }
            requested.add(stopRequested);
        }
        var visibleAfter = new Visibility(visiblePids());
        var output = new StringBuilder("[");
        for (int index = 0; index < identities.size(); index++) {
            Identity identity = identities.get(index);
            State after = inspectBatch(identity, visibleAfter);
            if (index > 0) output.append(',');
            output.append("{\"pid\":").append(identity.pid()).append(",\"originalStartedAt\":\"").append(identity.birth())
                .append("\",\"currentStartedAt\":\"").append(after.startedAt()).append("\",\"alive\":").append(after.alive())
                .append(",\"matchesOriginal\":").append(after.matchesOriginal()).append(",\"stopRequested\":").append(requested.get(index))
                .append(",\"beforeStopAlive\":").append(originalStates.get(index).alive()).append(",\"originalStopped\":").append(after.originalStopped()).append('}');
        }
        System.out.println(output.append(']'));
    }

    private static java.util.Set<Long> visiblePids() {
        return ProcessHandle.allProcesses().map(ProcessHandle::pid).collect(java.util.stream.Collectors.toSet());
    }

    private static State inspectBatch(Identity identity, Visibility visible) {
        ProcessHandle handle = ProcessHandle.of(identity.pid()).orElse(null);
        String actual = handle == null ? "" : handle.info().startInstant().map(Object::toString).orElse("");
        boolean alive = handle != null && handle.isAlive() || visible.pids.contains(identity.pid());
        if (alive && actual.isBlank() && System.getProperty("os.name", "").startsWith("Windows")) {
            if (!visible.metadataAttempted) { visible.metadataAttempted = true; visible.births = windowsBirths(); }
            actual = visible.births.getOrDefault(identity.pid(), "");
        }
        boolean matches = alive && identity.birth().equals(actual), stopped = !alive || !actual.isBlank() && !identity.birth().equals(actual);
        return new State(handle, actual, alive, matches, stopped);
    }

    /**
     * 独立 WMI 对照补充受限句柄的出生时间，原生实现和核验夹具不共用身份解析代码。
     */
    private static java.util.Map<Long, String> windowsBirths() {
        Process query = null;
        String queryBirth = "";
        try {
            var classes = java.nio.file.Path.of(ProcessRecoveryProbe.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            var reports = classes.getParent().resolve("reports");
            if (!java.nio.file.Files.isDirectory(reports)) reports = classes;
            var output = java.nio.file.Files.createTempFile(reports, "windows-original-pid-metadata-", ".tsv");
            var error = java.nio.file.Files.createTempFile(reports, "windows-original-pid-metadata-", ".stderr");
            String command = "[Console]::OutputEncoding=[Text.UTF8Encoding]::new($false); Get-CimInstance -ClassName Win32_Process -ErrorAction Stop | ForEach-Object { $born=if ($_.CreationDate) {$_.CreationDate.ToUniversalTime().ToString('yyyy-MM-ddTHH:mm:ss.fffZ')} else {''}; [Console]::WriteLine(('{0}|{1}' -f $_.ProcessId,$born)) }";
            var executable = java.nio.file.Path.of(System.getenv("SystemRoot"), "System32", "WindowsPowerShell", "v1.0", "powershell.exe");
            query = new ProcessBuilder(executable.toString(), "-NoProfile", "-NonInteractive", "-Command", command).redirectOutput(output.toFile()).redirectError(error.toFile()).start();
            queryBirth = query.info().startInstant().map(Object::toString).orElse("");
            if (!query.waitFor(15, TimeUnit.SECONDS) || query.exitValue() != 0 || java.nio.file.Files.size(output) > 2 * 1024 * 1024) return java.util.Map.of();
            var result = new java.util.HashMap<Long, String>();
            for (String line : java.nio.file.Files.readAllLines(output, java.nio.charset.StandardCharsets.UTF_8)) {
                String[] fields = line.split("\\|", -1);
                if (fields.length != 2 || !fields[0].matches("[0-9]+")) return java.util.Map.of();
                String birth = fields[1].isBlank() ? "" : Instant.parse(fields[1]).toString();
                if (result.putIfAbsent(Long.parseLong(fields[0]), birth) != null) return java.util.Map.of();
            }
            String selfBirth = ProcessHandle.current().info().startInstant().map(Object::toString).orElse("");
            if (selfBirth.isBlank() || !selfBirth.equals(result.get(ProcessHandle.current().pid()))) return java.util.Map.of();
            return java.util.Map.copyOf(result);
        } catch (Exception unavailable) { return java.util.Map.of(); }
        finally {
            if (query != null && query.isAlive() && !queryBirth.isBlank() && queryBirth.equals(query.info().startInstant().map(Object::toString).orElse(""))) query.destroyForcibly();
        }
    }

    private static final class Visibility {
        final java.util.Set<Long> pids;
        java.util.Map<Long, String> births = java.util.Map.of();
        boolean metadataAttempted;
        Visibility(java.util.Set<Long> pids) { this.pids = pids; }
    }

    private record Identity(long pid, String birth) { }

    /**
     * 句柄与独立 PID 枚举共同核验；缺少出生时间不能证明原进程已停止。
     */
    private static State inspect(long pid, String birth) {
        return inspectBatch(new Identity(pid, birth), new Visibility(visiblePids()));
    }

    private record State(ProcessHandle handle, String startedAt, boolean alive, boolean matchesOriginal, boolean originalStopped) { }
}
