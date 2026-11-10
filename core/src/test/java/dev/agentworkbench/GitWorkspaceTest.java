package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import dev.agentworkbench.runtime.WorkspaceSelectionConflict;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 Git 核对所选提交、暂存保全和 linked worktree 归属，不模拟仓库命令。
 */
class GitWorkspaceTest {
    @TempDir Path temporary;
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void optionsEnumerateActualRefsAndIgnoreRemoteHeadAliasWithoutRefreshingSourceIndex() throws Exception {
        Path source = project(); byte[] index = Files.readAllBytes(source.resolve(".git/index"));
        Map<String, Object> options = runtime().workspaceOptions(source.toString());
        List<Map<String, Object>> refs = objects(options.get("refs"));
        assertEquals(List.of("refs/heads/feature", "refs/heads/main", "refs/remotes/origin/feature"), refs.stream().map(item -> str(item, "ref")).toList());
        assertEquals("LOCAL", refs.getFirst().get("kind")); assertEquals("REMOTE", refs.getLast().get("kind"));
        assertEquals("main", map(options, "source").get("branch")); assertEquals(1, objects(options.get("worktrees")).size());
        assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void selectedBranchHasItsOwnFilesAndRulesWhileSourceHeadIndexConfigAndRefsStayUnchanged(String strategy) throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime();
        byte[] index = Files.readAllBytes(source.resolve(".git/index")), config = Files.readAllBytes(source.resolve(".git/config"));
        String head = git(source, "rev-parse", "HEAD"), refs = git(source, "for-each-ref", "--format=%(refname) %(objectname)");
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", strategy, "ref", "refs/heads/feature", "expectedHead", git(source, "rev-parse", "feature").strip()), false, 1);
        assertEquals("feature rules\n", runtime.workspaceRules(source.toString(), selection));
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), "selected-task", false, selection);
        Path work = Path.of(str(prepared, "workspace"));
        assertEquals("feature content\n", Files.readString(work.resolve("README.md")));
        assertEquals(selection.get("baseline"), git(work, "rev-parse", "HEAD").strip()); assertEquals("", git(work, "branch", "--show-current").strip());
        assertEquals(head, git(source, "rev-parse", "HEAD")); assertEquals(refs, git(source, "for-each-ref", "--format=%(refname) %(objectname)"));
        assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(config, Files.readAllBytes(source.resolve(".git/config")));
        Map<String, Object> ownership = map(prepared, "workspaceOwnership");
        assertEquals(strategy, ownership.get("strategy")); assertEquals("selected-task", ownership.get("taskId"));
        if ("WORKTREE".equals(strategy)) {
            assertTrue(Files.isRegularFile(work.resolve(".git")));
            assertEquals(source.resolve(".git").toRealPath().toString(), ownership.get("commonDirectory"));
            assertTrue(git(source, "worktree", "list", "--porcelain").contains("locked " + str(ownership, "lockReason")));
        } else { assertTrue(Files.isDirectory(work.resolve(".git"))); assertNotEquals(source.resolve(".git").toRealPath().toString(), ownership.get("commonDirectory")); }
        runtime.requireWorkspaceOwnership("selected-task", work.toString(), prepared);
        Files.writeString(work.resolve("README.md"), "later task edit\n");
        runtime.requireWorkspaceOwnership("selected-task", work.toString(), prepared);
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "selected-task", false, selection));
        assertEquals("later task edit\n", Files.readString(work.resolve("README.md")));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void includingStagedUnstagedUntrackedAndDeletedFilesKeepsOriginalStaging(String strategy) throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime();
        Files.writeString(source.resolve("README.md"), "staged version\n"); git(source, "add", "README.md");
        Files.writeString(source.resolve("README.md"), "unstaged version\n"); Files.delete(source.resolve("old.txt"));
        Files.createDirectories(source.resolve("notes")); Files.writeString(source.resolve("notes/汉字.txt"), "untracked notes\n");
        byte[] index = Files.readAllBytes(source.resolve(".git/index")); String staged = git(source, "diff", "--cached", "--binary"), status = git(source, "status", "--porcelain=v1", "-uall");
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", strategy), true, 1);
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), "dirty-task", true, selection); Path work = Path.of(str(prepared, "workspace"));
        assertEquals("unstaged version\n", Files.readString(work.resolve("README.md"))); assertEquals("untracked notes\n", Files.readString(work.resolve("notes/汉字.txt"))); assertFalse(Files.exists(work.resolve("old.txt")));
        assertEquals(staged, git(source, "diff", "--cached", "--binary")); assertEquals(status, git(source, "status", "--porcelain=v1", "-uall"));
        assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index"))); assertTrue(bool(prepared, "sourceDirty", false));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void remoteOnlyCommitIsFetchedByItsFrozenShaEvenWhenItsRefMoves(String strategy) throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime();
        git(source, "checkout", "--detach", "feature"); Files.writeString(source.resolve("remote-only.txt"), "remote-only commit\n");
        git(source, "add", "."); git(source, "commit", "-m", "remote-only commit"); String remoteHead = git(source, "rev-parse", "HEAD").strip();
        git(source, "update-ref", "refs/remotes/origin/remote-only", remoteHead); git(source, "checkout", "main");
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", strategy, "ref", "refs/remotes/origin/remote-only", "expectedHead", remoteHead), false, 1);
        git(source, "update-ref", "refs/remotes/origin/remote-only", "main");
        byte[] index = Files.readAllBytes(source.resolve(".git/index")), config = Files.readAllBytes(source.resolve(".git/config"));
        String refs = git(source, "for-each-ref", "--format=%(refname) %(objectname)");
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), "remote-task", false, selection); Path work = Path.of(str(prepared, "workspace"));
        assertEquals(remoteHead, git(work, "rev-parse", "HEAD").strip()); assertEquals("remote-only commit\n", Files.readString(work.resolve("remote-only.txt")));
        assertEquals("refs/remotes/origin/remote-only", map(prepared, "workspaceSelection").get("ref"));
        assertEquals(refs, git(source, "for-each-ref", "--format=%(refname) %(objectname)"));
        assertArrayEquals(index, Files.readAllBytes(source.resolve(".git/index"))); assertArrayEquals(config, Files.readAllBytes(source.resolve(".git/config")));
    }

    @ParameterizedTest
    @CsvSource({"CLONE,HEAD", "WORKTREE,HEAD", "CLONE,CONTENT", "WORKTREE,CONTENT", "CLONE,INDEX", "WORKTREE,INDEX"})
    void changesAfterConfirmationCreateNoWorktreeOrPreparationIntent(String strategy, String change) throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime();
        if ("CONTENT".equals(change)) Files.writeString(source.resolve("README.md"), "dirty before\n");
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", strategy), "CONTENT".equals(change), 1);
        switch (change) {
            case "HEAD" -> { Files.writeString(source.resolve("new.txt"), "new commit\n"); git(source, "add", "new.txt"); git(source, "commit", "-m", "source moved"); }
            case "CONTENT" -> Files.writeString(source.resolve("README.md"), "dirty after\n");
            case "INDEX" -> git(source, "update-index", "--assume-unchanged", "README.md");
            default -> fail("invalid fixture");
        }
        assertThrows(WorkspaceSelectionConflict.class, () -> runtime.prepareSelected(source.toString(), "stale-task", "CONTENT".equals(change), selection));
        Path task = temporary.resolve("data/workspaces/stale-task");
        assertFalse(Files.exists(task.resolve("work"))); assertFalse(Files.exists(task.resolve("selection.json")));
        assertEquals(1, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size());
    }

    @Test
    void staleUiSelectionInvalidRefAndDirtyOtherBranchAreRejected() throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime(); Map<String, Object> options = runtime.workspaceOptions(source.toString());
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj("ref", "HEAD~1"), false, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj("strategy", "CURRENT_DIRECTORY"), false, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj("ref", "--orphan"), false, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj("path", temporary.toString()), false, 1));
        Files.writeString(source.resolve("README.md"), "dirty current\n");
        assertThrows(WorkspaceSelectionConflict.class, () -> runtime.selectWorkspace(source.toString(), obj("sourceVersion", map(options, "source").get("version")), true, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj("ref", "refs/heads/feature"), true, 1));
        assertThrows(IllegalArgumentException.class, () -> runtime.selectWorkspace(source.toString(), obj(), false, 1));
    }

    @ParameterizedTest
    @ValueSource(strings = {"CLONE", "WORKTREE"})
    void reopeningUsesTheOriginalReadyReceiptEvenAfterSourceMoves(String strategy) throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime();
        Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", strategy), false, 1);
        Map<String, Object> first = runtime.prepareSelected(source.toString(), "reopen-task", false, selection);
        Files.writeString(source.resolve("source-new.txt"), "source moved\n"); git(source, "add", "source-new.txt"); git(source, "commit", "-m", "next source commit");
        int count = objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size();
        Map<String, Object> second = runtime().prepareSelected(source.toString(), "reopen-task", false, selection);
        assertTrue(bool(second, "recoveredPreparation", false)); assertEquals(first.get("workspaceOwnership"), second.get("workspaceOwnership")); assertEquals(first.get("baseline"), second.get("baseline"));
        assertEquals(count, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size());
        assertFalse(Files.exists(Path.of(str(second, "workspace")).resolve("source-new.txt")));
    }

    @Test
    void partialIntentAndForeignTargetRemainUntouched() throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime(); Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", "WORKTREE"), false, 1);
        Path task = Files.createDirectories(temporary.resolve("data/workspaces/partial-task")), manifest = task.resolve("selection.json");
        byte[] original = mapper.writeValueAsBytes(obj("taskId", "partial-task", "state", "PREPARED", "selection", selection)); Files.write(manifest, original);
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "partial-task", false, selection)); assertArrayEquals(original, Files.readAllBytes(manifest));
        Path foreign = Files.createDirectories(temporary.resolve("data/workspaces/foreign-task/work")); Files.writeString(foreign.resolve("user.txt"), "keep user content");
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "foreign-task", false, selection)); assertEquals("keep user content", Files.readString(foreign.resolve("user.txt")));
        assertEquals(1, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size());
    }

    @Test
    void ownershipCannotBorrowAnotherTaskOrIgnoreAChangedWorktreeLock() throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime(); Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", "WORKTREE"), false, 1);
        Map<String, Object> prepared = runtime.prepareSelected(source.toString(), "owned-task", false, selection); String workspace = str(prepared, "workspace");
        assertThrows(IllegalArgumentException.class, () -> runtime.requireWorkspaceOwnership("other-task", workspace, prepared));
        git(source, "worktree", "unlock", workspace);
        assertThrows(IllegalArgumentException.class, () -> runtime.requireWorkspaceOwnership("owned-task", workspace, prepared));
        assertTrue(Files.isRegularFile(Path.of(workspace).resolve("README.md"))); assertFalse(git(source, "worktree", "list", "--porcelain").contains("locked "));
    }

    @Test
    void redirectedTaskParentAndTraversalIdsAreRejected() throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime(); Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", "WORKTREE"), false, 1);
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "../source", false, selection));
        Path parent = Files.createDirectories(temporary.resolve("data/workspaces")), foreign = Files.createDirectories(temporary.resolve("foreign"));
        try { Files.createSymbolicLink(parent.resolve("redirect-task"), foreign); }
        catch (java.io.IOException | UnsupportedOperationException exception) { Assumptions.abort("平台未授予符号链接创建权限"); }
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "redirect-task", false, selection));
        assertFalse(Files.exists(foreign.resolve("work"))); assertFalse(Files.exists(foreign.resolve("selection.json")));
    }

    @Test
    @EnabledOnOs(OS.WINDOWS)
    void redirectedWorkspaceRootCannotCreateTaskDirectoriesInsideAForeignJunction() throws Exception {
        Path source = project(); WorkspaceRuntime runtime = runtime(); Map<String, Object> selection = runtime.selectWorkspace(source.toString(), obj("strategy", "WORKTREE"), false, 1);
        Path data = Files.createDirectories(temporary.resolve("data")), foreign = Files.createDirectories(temporary.resolve("foreign-junction-target"));
        Process process = new ProcessBuilder("node", "-e", "require('node:fs').symlinkSync(process.argv[1], process.argv[2], 'junction')", foreign.toString(), data.resolve("workspaces").toString()).redirectErrorStream(true).start();
        process.getOutputStream().close();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); fail("Fixture junction creation timed out"); }
        assertEquals(0, process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> runtime.prepareSelected(source.toString(), "junction-task", false, selection));
        try (var entries = Files.list(foreign)) { assertEquals(0, entries.count()); }
        assertEquals(1, objects(runtime.workspaceOptions(source.toString()).get("worktrees")).size());
    }

    private WorkspaceRuntime runtime() { return new WorkspaceRuntime(mapper, temporary.resolve("data").toString()); }

    private Path project() throws Exception {
        Path source = Files.createDirectories(temporary.resolve("中文 空格 source"));
        git(source, "init", "--initial-branch=main"); git(source, "config", "user.name", "Fixture"); git(source, "config", "user.email", "fixture@localhost"); git(source, "config", "core.autocrlf", "false");
        Files.writeString(source.resolve(".gitattributes"), "* text eol=lf\n"); Files.writeString(source.resolve("README.md"), "main content\n"); Files.writeString(source.resolve("AGENTS.md"), "main rules\n"); Files.writeString(source.resolve("old.txt"), "original file\n");
        git(source, "add", "."); git(source, "commit", "-m", "initial"); git(source, "checkout", "-b", "feature");
        Files.writeString(source.resolve("README.md"), "feature content\n"); Files.writeString(source.resolve("AGENTS.md"), "feature rules\n"); git(source, "add", "."); git(source, "commit", "-m", "feature content");
        git(source, "update-ref", "refs/remotes/origin/feature", "HEAD"); git(source, "symbolic-ref", "refs/remotes/origin/HEAD", "refs/remotes/origin/feature"); git(source, "checkout", "main"); return source;
    }

    private String git(Path directory, String... arguments) throws Exception {
        List<String> command = new java.util.ArrayList<>(List.of("git", "--no-optional-locks", "-c", "core.hooksPath=/dev/null", "-c", "core.autocrlf=false")); command.addAll(List.of(arguments));
        Process process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
        if (!process.waitFor(20, TimeUnit.SECONDS)) { process.destroyForcibly(); process.waitFor(5, TimeUnit.SECONDS); throw new IllegalStateException("Fixture Git timed out"); }
        String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8); assertEquals(0, process.exitValue(), output); return output;
    }
}
