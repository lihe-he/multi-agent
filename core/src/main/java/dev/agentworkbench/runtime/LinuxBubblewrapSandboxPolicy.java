package dev.agentworkbench.runtime;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * 空挂载树只暴露系统运行时和原工作区；真实命名空间预检失败时拒绝启动。
 */
public final class LinuxBubblewrapSandboxPolicy implements NativeSandboxPolicy {
    private static final String POLICY = "linux-bwrap-workspace-v1";
    private static final List<Path> RUNTIME_ROOTS = List.of(Path.of("/usr"), Path.of("/bin"), Path.of("/sbin"), Path.of("/lib"), Path.of("/lib64"));
    private static final Map<String, String> ENVIRONMENT = Map.ofEntries(
        Map.entry("PATH", "/usr/bin:/bin"), Map.entry("HOME", "/workbench-home"),
        Map.entry("TMPDIR", "/tmp"), Map.entry("XDG_CONFIG_HOME", "/workbench-home/.config"),
        Map.entry("XDG_CACHE_HOME", "/workbench-home/.cache"), Map.entry("XDG_DATA_HOME", "/workbench-home/.local/share"),
        Map.entry("LANG", "C.UTF-8"), Map.entry("LC_ALL", "C.UTF-8"), Map.entry("NO_COLOR", "1"),
        Map.entry("GIT_TERMINAL_PROMPT", "0"), Map.entry("PYTHONIOENCODING", "utf-8"));
    private final Path protectedData;

    public LinuxBubblewrapSandboxPolicy(Path protectedData) {
        this.protectedData = protectedData.toAbsolutePath().normalize();
    }

    @Override
    public Decision prepare(Request request, List<String> command) {
        if (!System.getProperty("os.name", "").toLowerCase(Locale.ROOT).equals("linux")) return Decision.unavailable("bubblewrap 策略仅适用于 Linux");
        try {
            Path data = protectedData.toRealPath(), workspace = request.workspace().toRealPath();
            requireWorkspace(workspace, data);
            List<String> nativeCommand = command(workspace, command);
            Path bwrap = trustedExecutable(Path.of("/usr/bin/bwrap"));
            trustedExecutable(Path.of("/bin/sh"));
            List<Path> roots = runtimeRoots();
            for (Path root : roots) {
                Path real = root.toRealPath();
                if (data.startsWith(real) || real.startsWith(data)) throw new IOException("Core data overlaps the public system runtime");
            }
            Path filter = filter(data, request.networkAccess() == NetworkAccess.DENY);
            List<String> probe = wrapped(bwrap, filter, request, workspace, roots,
                List.of("/bin/sh", "-c", "test -d /proc/self && test -d \"$HOME\" && test ! -e /etc/passwd && printf 'workbench-sandbox-ready'"));
            if (!preflight(probe, workspace)) return Decision.unavailable("bubblewrap 命名空间或 seccomp 真实预检失败；需要系统安装和启用相应能力");
            return Decision.isolated(wrapped(bwrap, filter, request, workspace, roots, nativeCommand), POLICY, ENVIRONMENT);
        } catch (IOException | java.io.UncheckedIOException | IllegalArgumentException | UnsupportedOperationException | SecurityException exception) {
            return Decision.unavailable("Linux 沙箱运行时、工作区或策略不可用：" + exception.getClass().getSimpleName());
        }
    }

    /**
     * 工作区不能覆盖核心控制目录或系统挂载点，特殊文件不能把宿主句柄引入现场。
     */
    private void requireWorkspace(Path workspace, Path data) throws IOException {
        if (!Files.isDirectory(workspace, LinkOption.NOFOLLOW_LINKS) || data.startsWith(workspace)
                || workspace.startsWith(data.resolve("native-sandbox"))) throw new IOException("Invalid workspace boundary");
        for (String reserved : List.of("/usr", "/bin", "/sbin", "/lib", "/lib64", "/etc", "/proc", "/sys", "/dev", "/run", "/workbench-home")) {
            Path path = Path.of(reserved);
            if (workspace.startsWith(path) || path.startsWith(workspace)) throw new IOException("Workspace overlaps a system boundary");
        }
        if (Set.of(Path.of("/tmp"), Path.of("/var"), Path.of("/var/tmp"), Path.of("/home"), Path.of("/root"), Path.of("/opt"), Path.of("/mnt"), Path.of("/media"))
                .contains(workspace)) throw new IOException("Workspace is a shared host root");
        try (var files = Files.walk(workspace)) {
            var entries = files.iterator(); int count = 0;
            while (entries.hasNext()) {
                if (++count > 100_000) throw new IOException("Workspace exceeds sandbox inventory boundary");
                Path entry = entries.next();
                if (entry.getFileName() != null && entry.getFileName().toString().equals(".git")) requireGitBoundary(entry, workspace);
                if (Files.isSymbolicLink(entry)) continue;
                var attributes = Files.readAttributes(entry, java.nio.file.attribute.BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
                if (!attributes.isDirectory() && !attributes.isRegularFile()) throw new IOException("Workspace contains a special file");
                if (attributes.isRegularFile() && ((Number) Files.getAttribute(entry, "unix:nlink", LinkOption.NOFOLLOW_LINKS)).longValue() != 1) {
                    throw new IOException("Workspace contains a shared file inode");
                }
            }
        }
    }

    /**
     * Git 的管理目录和对象依赖也必须在同一挂载内，外部 worktree 或共享对象在交付提示前拒绝。
     */
    private static void requireGitBoundary(Path marker, Path workspace) throws IOException {
        Path directory = contained(marker, workspace);
        if (Files.isRegularFile(directory)) {
            String pointer = metadata(directory);
            if (!pointer.startsWith("gitdir: ")) throw new IOException("Invalid Git directory pointer");
            directory = contained(marker.getParent().resolve(pointer.substring(8).strip()), workspace);
        }
        if (!Files.isDirectory(directory)) throw new IOException("Git metadata is not a directory");
        Path common = directory;
        if (Files.exists(directory.resolve("commondir"), LinkOption.NOFOLLOW_LINKS)) {
            common = contained(directory.resolve(metadata(contained(directory.resolve("commondir"), workspace))), workspace);
        }
        requireObjectBoundary(directory.resolve("objects"), workspace, new HashSet<>());
        if (!common.equals(directory)) requireObjectBoundary(common.resolve("objects"), workspace, new HashSet<>());
    }

    private static void requireObjectBoundary(Path objects, Path workspace, Set<Path> visited) throws IOException {
        if (!Files.exists(objects, LinkOption.NOFOLLOW_LINKS)) return;
        Path real = contained(objects, workspace);
        if (!visited.add(real)) return;
        if (visited.size() > 128) throw new IOException("Git object dependencies exceed sandbox inventory boundary");
        Path alternates = real.resolve("info/alternates");
        if (Files.exists(alternates, LinkOption.NOFOLLOW_LINKS)) {
            for (String alternate : metadata(contained(alternates, workspace)).lines().toList()) {
                if (alternate.isBlank()) continue;
                if (alternate.startsWith("\"")) throw new IOException("Quoted Git object dependencies are unsupported");
                requireObjectBoundary(contained(real.resolve(alternate), workspace), workspace, visited);
            }
        }
    }

    private static Path contained(Path path, Path workspace) throws IOException {
        Path real = path.toRealPath();
        if (!real.startsWith(workspace)) throw new IOException("Git metadata depends on files outside the workspace");
        return real;
    }

    private static String metadata(Path file) throws IOException {
        if (!Files.isRegularFile(file)) throw new IOException("Git metadata must be a regular file");
        try (var stream = Files.newInputStream(file)) {
            byte[] bytes = stream.readNBytes(8193);
            if (bytes.length > 8192) throw new IOException("Git metadata exceeds sandbox inventory boundary");
            String value = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).strip();
            if (value.indexOf('\0') >= 0) throw new IOException("Invalid Git metadata");
            return value;
        }
    }

    private List<String> command(Path workspace, List<String> command) throws IOException {
        if (command == null || command.isEmpty() || command.stream().anyMatch(value -> value == null || value.indexOf('\0') >= 0)) throw new IOException("Invalid command");
        String name = command.getFirst(); Path executable = Path.of(name);
        if (!executable.isAbsolute()) {
            if (name.contains("/")) throw new IOException("Relative executable path is unsupported");
            executable = Path.of("/usr/bin", name);
        }
        executable = executable.toRealPath();
        if (!executable.startsWith(workspace)) trustedExecutable(executable);
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable)
                || !executable.startsWith(workspace) && RUNTIME_ROOTS.stream().noneMatch(executable::startsWith)) throw new IOException("Executable is outside approved runtime roots");
        List<String> result = new ArrayList<>(command); result.set(0, executable.toString()); return List.copyOf(result);
    }

    private static Path trustedExecutable(Path file) throws IOException {
        Path resolved = file.toRealPath();
        if (!Files.isRegularFile(resolved) || !Files.isExecutable(resolved) || ((Number) Files.getAttribute(resolved, "unix:uid")).longValue() != 0) {
            throw new IOException("Runtime executable must be system owned");
        }
        requireSystemPermissions(resolved); return resolved;
    }

    private static void requireSystemPermissions(Path file) throws IOException {
        for (Path entry = file; entry != null; entry = entry.getParent()) {
            int mode = ((Number) Files.getAttribute(entry, "unix:mode")).intValue();
            if ((mode & 0022) != 0 || ((Number) Files.getAttribute(entry, "unix:uid")).longValue() != 0) throw new IOException("Runtime path is writable outside system administration");
        }
    }

    private static List<Path> runtimeRoots() throws IOException {
        List<Path> roots = new ArrayList<>();
        for (Path path : RUNTIME_ROOTS) if (Files.exists(path)) {
            Path real = path.toRealPath(); requireSystemPermissions(real); roots.add(path);
        }
        if (!roots.contains(Path.of("/usr")) || !Files.isExecutable(Path.of("/bin/sh"))) throw new IOException("System runtime is incomplete");
        return List.copyOf(roots);
    }

    /**
     * BPF 文件只保存在核心私有目录；包装器以原生文件描述符传递，不把策略挂入工作区。
     */
    private Path filter(Path data, boolean denyNetwork) throws IOException {
        byte[] program = LinuxSeccompFilter.program(System.getProperty("os.arch", ""), denyNetwork);
        Path parent = data.resolve("native-sandbox");
        try { Files.createDirectory(parent, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------"))); }
        catch (java.nio.file.FileAlreadyExistsException ignored) { }
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS) || !parent.toRealPath().equals(parent)
                || !Files.getOwner(parent).equals(Files.getOwner(data)) || !Files.getPosixFilePermissions(parent).equals(PosixFilePermissions.fromString("rwx------"))) {
            throw new IOException("Sandbox policy directory lost private ownership");
        }
        String digest;
        try { digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(program)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
        Path file = parent.resolve(digest + ".bpf");
        try (var channel = Files.newByteChannel(file, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("r--------")))) {
            java.nio.ByteBuffer bytes = java.nio.ByteBuffer.wrap(program); while (bytes.hasRemaining()) channel.write(bytes);
        }
        catch (java.nio.file.FileAlreadyExistsException ignored) { }
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || !Files.getOwner(file).equals(Files.getOwner(parent))
                || !Files.getPosixFilePermissions(file).equals(PosixFilePermissions.fromString("r--------"))
                || !MessageDigest.isEqual(program, Files.readAllBytes(file))) throw new IOException("Sandbox BPF file differs from its fixed policy");
        return file;
    }

    private List<String> wrapped(Path bwrap, Path filter, Request request, Path workspace, List<Path> roots, List<String> command) throws IOException {
        List<String> args = new ArrayList<>(List.of("/bin/sh", "-c", "exec \"$@\" 3<\"$0\"", filter.toString(), bwrap.toString(),
            "--die-with-parent", "--new-session", "--unshare-user", "--disable-userns", "--assert-userns-disabled", "--unshare-pid", "--unshare-ipc", "--unshare-uts", "--cap-drop", "ALL"));
        if (request.networkAccess() == NetworkAccess.DENY) args.add("--unshare-net");
        for (Path root : roots) args.addAll(List.of("--ro-bind", root.toRealPath().toString(), root.toString()));
        args.addAll(List.of("--proc", "/proc", "--dev", "/dev", "--tmpfs", "/tmp", "--tmpfs", "/workbench-home"));
        for (String name : List.of("/etc/ld.so.cache", "/etc/ssl/certs", "/etc/ca-certificates", "/etc/pki/tls/certs")) addSystemFile(args, name, protectedData.toRealPath());
        if (request.networkAccess() == NetworkAccess.ALLOW) {
            for (String name : List.of("/etc/resolv.conf", "/etc/hosts", "/etc/nsswitch.conf")) addSystemFile(args, name, protectedData.toRealPath());
        }
        args.addAll(List.of(request.fileAccess() == FileAccess.READ_ONLY ? "--ro-bind" : "--bind", workspace.toString(), workspace.toString(),
            "--remount-ro", "/", "--chdir", workspace.toString(), "--seccomp", "3", "--"));
        args.addAll(command); return List.copyOf(args);
    }

    private static void addSystemFile(List<String> command, String name, Path data) throws IOException {
        Path path = Path.of(name);
        if (!Files.exists(path)) return;
        Path real = path.toRealPath();
        if (name.equals("/etc/resolv.conf")) requireResolverPermissions(real);
        else requireSystemPermissions(real);
        if (data.startsWith(real) || real.startsWith(data)) throw new IOException("Core data overlaps exposed system configuration");
        command.addAll(List.of("--ro-bind", real.toString(), name));
    }

    /**
     * systemd-resolved 的固定 DNS 数据可由专用服务账号维护，不能由当前工作台账号或其他用户改写。
     */
    private static void requireResolverPermissions(Path file) throws IOException {
        try { requireSystemPermissions(file); return; }
        catch (IOException exception) {
            Path directory = Path.of("/run/systemd/resolve");
            if (!Set.of(directory.resolve("resolv.conf"), directory.resolve("stub-resolv.conf")).contains(file)
                    || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw exception;
            var service = file.getFileSystem().getUserPrincipalLookupService().lookupPrincipalByName("systemd-resolve");
            if (service.equals(Files.getOwner(Path.of("/proc/self")))) throw new IOException("Resolver service cannot be the workbench account");
            for (Path entry = file; entry != null; entry = entry.getParent()) {
                int mode = ((Number) Files.getAttribute(entry, "unix:mode")).intValue();
                boolean rootOwned = ((Number) Files.getAttribute(entry, "unix:uid")).longValue() == 0;
                if ((mode & 0022) != 0 || !rootOwned && !(entry.startsWith(directory) && service.equals(Files.getOwner(entry)))) {
                    throw new IOException("Resolver path is writable outside its dedicated system service");
                }
            }
        }
    }

    private static boolean preflight(List<String> command, Path workspace) throws IOException {
        ProcessBuilder builder = new ProcessBuilder(command).directory(workspace.toFile()).redirectErrorStream(true);
        builder.environment().clear(); builder.environment().putAll(ENVIRONMENT);
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            if (!process.waitFor(10, TimeUnit.SECONDS)) return false;
            byte[] output = process.getInputStream().readNBytes(8192);
            return process.exitValue() == 0 && new String(output, java.nio.charset.StandardCharsets.UTF_8).equals("workbench-sandbox-ready");
        } catch (InterruptedException exception) { Thread.currentThread().interrupt(); return false; }
        finally {
            if (process.isAlive()) { process.descendants().forEach(ProcessHandle::destroyForcibly); process.destroyForcibly(); }
        }
    }
}
