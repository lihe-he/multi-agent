package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Windows 长路径克隆与审查保留原文件和仓库配置，失败现场独立保存。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class WorkspaceLongPathTest {
    @TempDir
    Path temporary;

    @ParameterizedTest
    @ValueSource(strings = { "LEGACY", "CLONE", "WORKTREE" })
    @EnabledOnOs(OS.WINDOWS)
    void windowsLongObjectPathsPreserveOriginalWorkspaceAndReview(String strategy) throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Path source = Files.createDirectories(temporary.resolve("中文 source"));
        byte[] original = "original source\r\n".getBytes(StandardCharsets.UTF_8), binary = { 0, 1, (byte) 255, 13, 10 };
        Files.write(source.resolve("app.txt"), original); Files.write(source.resolve("binary.bin"), binary);
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Long Path Fixture");
        git(source, "config", "user.email", "long-path@example.invalid"); git(source, "config", "core.longpaths", "false");
        git(source, "add", "."); git(source, "commit", "-m", "original source");
        String head = git(source, "rev-parse", "HEAD").strip(); byte[] index = Files.readAllBytes(source.resolve(".git/index")), config = Files.readAllBytes(source.resolve(".git/config"));
        Path data = temporary.toRealPath().resolve("中文 long data");
        while (data.toString().length() < 150) data = data.resolve("long-path-directory");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString()); String taskId = UUID.randomUUID().toString();
        Map<String, Object> prepared = "LEGACY".equals(strategy) ? runtime.prepare(source.toString(), taskId, false)
            : runtime.prepareSelected(source.toString(), taskId, false, runtime.selectWorkspace(source.toString(), Json.obj("strategy", strategy, "ref", "HEAD"), false, 1));
        Path work = Path.of(Json.str(prepared, "workspace"));
        assertArrayEquals(original, Files.readAllBytes(work.resolve("app.txt"))); assertArrayEquals(binary, Files.readAllBytes(work.resolve("binary.bin")));
        byte[] workIndex = Files.readAllBytes(Path.of(git(work, "rev-parse", "--path-format=absolute", "--git-path", "index").strip()));
        Files.writeString(work.resolve("app.txt"), "changed 中文\n"); Files.writeString(work.resolve("new file.txt"), "literal new output\n");
        String version = runtime.fingerprint(work.toString()); Path review = Path.of(runtime.reviewCopy(work.toString(), taskId, version));
        assertNotEquals(work, review); assertTrue(review.resolve(".git/objects/pack/pack-" + "0".repeat(40) + ".pack").toString().length() > 260);
        assertEquals(version, runtime.fingerprint(review.toString())); assertEquals("changed 中文\n", Files.readString(review.resolve("app.txt")));
        assertEquals("literal new output\n", Files.readString(review.resolve("new file.txt"))); assertArrayEquals(binary, Files.readAllBytes(review.resolve("binary.bin")));
        assertArrayEquals(original, Files.readAllBytes(source.resolve("app.txt"))); assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index")));
        assertArrayEquals(config, Files.readAllBytes(source.resolve(".git/config"))); assertEquals(head, git(source, "rev-parse", "HEAD").strip());
        assertEquals("false", git(source, "config", "--local", "--get", "core.longpaths").strip());
        assertArrayEquals(workIndex, Files.readAllBytes(Path.of(git(work, "rev-parse", "--path-format=absolute", "--git-path", "index").strip())));
        assertEquals("changed 中文\n", Files.readString(work.resolve("app.txt"))); assertEquals("", git(review, "remote").strip());
        Path receipt = Path.of("target/windows-long-path-evidence-" + UUID.randomUUID()); Files.createDirectories(receipt);
        List<Map<String, Object>> objects = new ArrayList<>();
        try (var files = Files.walk(review.resolve(".git/objects"))) {
            for (Path object : files.filter(Files::isRegularFile).filter(path -> path.toString().length() > 260).toList()) {
                String name = review.resolve(".git/objects").relativize(object).toString().replace('\\', '/');
                if (!name.matches("(?:[0-9a-f]{2}/(?:[0-9a-f]{38}|[0-9a-f]{62})|pack/pack-(?:[0-9a-f]{40}|[0-9a-f]{64})\\.pack)")) continue;
                byte[] bytes = Files.readAllBytes(object); Path copy = receipt.resolve("objects").resolve(name); Files.createDirectories(copy.getParent()); Files.write(copy, bytes);
                objects.add(Json.obj("path", object.toString(), "pathUtf16Length", object.toString().length(), "file", "objects/" + name, "bytes", bytes.length, "sha256", sha(bytes)));
            }
        }
        assertFalse(objects.isEmpty());
        Files.write(receipt.resolve("source-index.bin"), index); Files.write(receipt.resolve("source-config.bin"), config); Files.write(receipt.resolve("work-index.bin"), workIndex);
        Files.write(receipt.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Json.obj("recordedAt", Instant.now().toString(), "strategy", strategy,
            "workspace", work.toString(), "review", review.toString(), "version", version, "actualObjects", objects, "sourceHead", head,
            "sourceIndexSha256", sha(index), "sourceConfigSha256", sha(config), "originalSourceAndWorkspaceRetained", true, "perCommandLongPathsOnly", true)));
    }

    /**
     * 强制超过显式 GIT_DIR 的字节限制，同时核对原 READY、锁定归属和独立审查。
     */
    @Test
    @EnabledOnOs(OS.WINDOWS)
    void windowsWorktreeBeyondExplicitGitDirectoryLimitRetainsReadyOwnership() throws Exception {
        ObjectMapper mapper = new ObjectMapper(); Path source = Files.createDirectories(temporary.resolve("中文 worktree source"));
        byte[] original = "original worktree\r\n".getBytes(StandardCharsets.UTF_8), binary = { 0, (byte) 255, 13, 10 };
        Files.write(source.resolve("app.txt"), original); Files.write(source.resolve("binary.bin"), binary);
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Long Worktree Fixture");
        git(source, "config", "user.email", "long-worktree@example.invalid"); git(source, "config", "core.longpaths", "false");
        git(source, "add", "."); git(source, "commit", "-m", "original worktree");
        String head = git(source, "rev-parse", "HEAD").strip(), refs = git(source, "show-ref");
        byte[] sourceIndex = Files.readAllBytes(source.resolve(".git/index")), sourceConfig = Files.readAllBytes(source.resolve(".git/config"));
        String taskId = UUID.randomUUID().toString(); Path data = temporary.toRealPath().resolve("中文 long worktree data");
        while (data.resolve("workspaces").resolve(taskId).resolve("work/.git").toString().getBytes(StandardCharsets.UTF_8).length <= 220)
            data = data.resolve("中文 long-path-directory");
        WorkspaceRuntime runtime = new WorkspaceRuntime(mapper, data.toString());
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), Json.obj("strategy", "WORKTREE", "ref", "HEAD"), false, 1);
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), taskId, false, selection);
        Path work = Path.of(Json.str(prepared, "workspace"));
        assertTrue(work.resolve(".git").toString().getBytes(StandardCharsets.UTF_8).length > 220);
        assertTrue(work.toString().length() < 240);
        assertTrue(Files.isRegularFile(work.resolve(".git"))); assertArrayEquals(original, Files.readAllBytes(work.resolve("app.txt")));
        assertArrayEquals(binary, Files.readAllBytes(work.resolve("binary.bin"))); assertEquals(head, git(work, "rev-parse", "HEAD").strip());
        assertEquals("HEAD", git(work, "rev-parse", "--abbrev-ref", "HEAD").strip());
        Map<String, Object> ownership = Json.map(prepared, "workspaceOwnership"); Path gitDirectory = Path.of(Json.str(ownership, "gitDirectory"));
        assertEquals(Json.str(ownership, "lockReason"), Files.readString(gitDirectory.resolve("locked")).strip());
        runtime.requireWorkspaceOwnership(taskId, work.toString(), prepared);
        byte[] workIndex = Files.readAllBytes(gitDirectory.resolve("index"));
        byte[] manifest = Files.readAllBytes(work.getParent().resolve("selection.json"));
        WorkspaceRuntime reopened = new WorkspaceRuntime(mapper, data.toString());
        Map<String, Object> recovered = reopened.prepareSelected(source.toString(), taskId, false, selection);
        assertTrue(Boolean.TRUE.equals(recovered.get("recoveredPreparation"))); assertEquals(ownership, recovered.get("workspaceOwnership"));
        assertArrayEquals(manifest, Files.readAllBytes(work.getParent().resolve("selection.json")));
        Files.writeString(work.resolve("app.txt"), "changed 中文\n"); Files.writeString(work.resolve("new file.txt"), "new worktree output\n");
        String version = reopened.fingerprint(work.toString()); Path review = Path.of(reopened.reviewCopy(work.toString(), taskId, version));
        assertEquals(version, reopened.fingerprint(review.toString())); assertTrue(Files.isDirectory(review.resolve(".git")));
        assertTrue(review.resolve(".git/objects/pack/pack-" + "0".repeat(40) + ".pack").toString().length() > 260);
        assertEquals("changed 中文\n", Files.readString(review.resolve("app.txt"))); assertEquals("new worktree output\n", Files.readString(review.resolve("new file.txt")));
        assertArrayEquals(binary, Files.readAllBytes(review.resolve("binary.bin"))); assertEquals("", git(review, "remote").strip());
        assertArrayEquals(workIndex, Files.readAllBytes(gitDirectory.resolve("index"))); assertArrayEquals(original, Files.readAllBytes(source.resolve("app.txt")));
        assertArrayEquals(sourceIndex, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(sourceConfig, Files.readAllBytes(source.resolve(".git/config")));
        assertEquals(head, git(source, "rev-parse", "HEAD").strip()); assertEquals(refs, git(source, "show-ref"));
        assertEquals("false", git(source, "config", "--local", "--get", "core.longpaths").strip());
        reopened.requireWorkspaceOwnership(taskId, work.toString(), recovered);
        Path receipt = Path.of("target/windows-long-path-evidence-" + UUID.randomUUID()); Files.createDirectories(receipt);
        Files.write(receipt.resolve("source-index.bin"), sourceIndex); Files.write(receipt.resolve("source-config.bin"), sourceConfig);
        Files.write(receipt.resolve("work-index.bin"), workIndex); Files.write(receipt.resolve("selection.json"), manifest);
        Files.write(receipt.resolve("report.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(Json.obj("recordedAt", Instant.now().toString(),
            "strategy", "WORKTREE", "workspace", work.toString(), "gitMarkerUtf8Bytes", work.resolve(".git").toString().getBytes(StandardCharsets.UTF_8).length,
            "review", review.toString(), "sourceHead", head, "sourceRefs", refs, "sourceIndexSha256", sha(sourceIndex), "sourceConfigSha256", sha(sourceConfig),
            "workspaceOwnership", ownership, "readyReceiptReusedWithoutRewrite", true, "sourceAndWorkIndexRetained", true)));
    }

    /**
     * 夹具查询使用同一有界进程回执；不会改写用户或系统 Git 配置。
     */
    private static String git(Path root, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.longpaths=true", "-c", "core.autocrlf=false", "-C", root.toString()));
        command.addAll(List.of(arguments)); Process process = new ProcessBuilder(command).start();
        try (var readers = java.util.concurrent.Executors.newVirtualThreadPerTaskExecutor()) {
            var output = readers.submit(() -> process.getInputStream().readAllBytes());
            var errors = readers.submit(() -> process.getErrorStream().readAllBytes());
            try { assertTrue(process.waitFor(20, java.util.concurrent.TimeUnit.SECONDS)); }
            finally { if (process.isAlive()) process.destroyForcibly(); }
            byte[] bytes = output.get(5, java.util.concurrent.TimeUnit.SECONDS), diagnostics = errors.get(5, java.util.concurrent.TimeUnit.SECONDS);
            Path log = Path.of("target/windows-long-path-git-queries.jsonl");
            Files.writeString(log, new ObjectMapper().writeValueAsString(Json.obj("recordedAt", Instant.now().toString(), "command", command,
                "exitCode", process.exitValue(), "stdout", new String(bytes, StandardCharsets.UTF_8), "stderr", new String(diagnostics, StandardCharsets.UTF_8))) + "\n",
                StandardCharsets.UTF_8, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
            assertEquals(0, process.exitValue(), new String(diagnostics, StandardCharsets.UTF_8)); return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String sha(byte[] bytes) throws Exception {
        return java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    }
}
