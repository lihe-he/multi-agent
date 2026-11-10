package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 所选提交与现场归属单独保存；linked worktree 不写入原仓库的共享配置。
 */
final class GitWorkspaces {
    private static final Set<String> STRATEGIES = Set.of("CLONE", "WORKTREE");
    private final WorkspaceRuntime runtime;
    private final ObjectMapper mapper;
    private final Path data;

    GitWorkspaces(WorkspaceRuntime runtime, ObjectMapper mapper, Path data) {
        this.runtime = runtime; this.mapper = mapper; this.data = data;
    }

    Map<String, Object> options(String projectPath) {
        Path source = runtime.projectRoot(projectPath);
        Map<String, Object> snapshot = sourceSnapshot(source);
        List<Map<String, Object>> refs = new ArrayList<>();
        String output = runtime.git(source, "for-each-ref", "--sort=refname", "--format=%(refname)%00%(objectname)%00%(refname:short)%00%(objecttype)%00%(symref)", "refs/heads", "refs/remotes");
        for (String record : output.split("\n")) {
            if (record.isBlank()) continue;
            String[] fields = record.split(String.valueOf((char) 0), -1);
            if (fields.length != 5 || !"commit".equals(fields[3]) || !fields[4].isBlank()) continue;
            if (refs.size() >= 2000) throw new IllegalArgumentException("仓库引用超过 2000 项，请先整理引用后再选择启动提交");
            refs.add(obj("ref", fields[0], "head", fields[1], "name", fields[2], "kind", fields[0].startsWith("refs/heads/") ? "LOCAL" : "REMOTE"));
        }
        return obj("schemaVersion", 1, "sourcePath", source.toString(), "source", snapshot, "refs", refs,
            "strategies", List.of("CLONE", "WORKTREE"), "worktrees", worktrees(source));
    }

    Map<String, Object> selection(String projectPath, Map<String, Object> input, boolean includeChanges, int version) {
        if (version < 1) throw new IllegalArgumentException("启动选择版本必须为正整数");
        if (!Set.of("strategy", "ref", "expectedHead", "sourceVersion", "sourceIndex").containsAll(input.keySet())) throw new IllegalArgumentException("启动选择包含不支持的字段");
        for (String field : input.keySet()) if (!(input.get(field) instanceof String)) throw new IllegalArgumentException("启动选择的 " + field + " 必须为字符串");
        String strategy = str(input, "strategy"), ref = str(input, "ref");
        if (strategy.isBlank()) strategy = "CLONE";
        if (ref.isBlank()) ref = "HEAD";
        if (!STRATEGIES.contains(strategy)) throw new IllegalArgumentException("未知现场策略");
        Map<String, Object> available = options(projectPath), source = map(available, "source");
        String baseline = str(source, "head");
        if (!"HEAD".equals(ref)) {
            String selectedRef = ref;
            baseline = objects(available.get("refs")).stream().filter(item -> selectedRef.equals(str(item, "ref"))).findFirst()
                .map(item -> str(item, "head")).orElseThrow(() -> new IllegalArgumentException("所选引用不在当前仓库的本地或远程分支中，请刷新后选择"));
        }
        for (String field : List.of("expectedHead", "sourceVersion", "sourceIndex")) {
            String actual = "expectedHead".equals(field) ? baseline : "sourceVersion".equals(field) ? str(source, "version") : str(source, "index");
            if (input.containsKey(field) && !actual.equals(str(input, field))) throw new WorkspaceSelectionConflict();
        }
        if (bool(source, "dirty", false) && !includeChanges) throw new IllegalArgumentException("项目有未提交改动，请选择带入当前改动或先整理工作区");
        if (includeChanges && !baseline.equals(str(source, "head"))) throw new IllegalArgumentException("带入当前改动需要选择当前 HEAD，不能把改动套用到其他提交");
        return obj("schemaVersion", 1, "version", version, "strategy", strategy, "ref", ref, "baseline", baseline,
            "sourcePath", available.get("sourcePath"), "source", source, "includeChanges", includeChanges, "createdAt", now());
    }

    void current(String projectPath, Map<String, Object> selection) {
        Path source = runtime.projectRoot(projectPath);
        if (!source.toString().equals(str(selection, "sourcePath"))) throw new IllegalArgumentException("启动选择不属于当前仓库绑定，请重新确认");
        requireSource(source, selection);
    }

    boolean hasPreparation(String taskId) {
        runtime.requireId(taskId); Path parent = target(taskId).getParent();
        return Files.exists(parent.resolve("selection.json"), LinkOption.NOFOLLOW_LINKS) || Files.exists(parent.resolve("preparation.json"), LinkOption.NOFOLLOW_LINKS)
            || Files.exists(parent.resolve("work"), LinkOption.NOFOLLOW_LINKS);
    }

    /**
     * 准备意图先落盘；只有原 READY 回执和实际关联一致时才复用现场。
     */
    Map<String, Object> prepare(String projectPath, String taskId, boolean includeChanges, Map<String, Object> selection) {
        runtime.requireId(taskId);
        Path source = runtime.projectRoot(projectPath), target = target(taskId), manifest = target.getParent().resolve("selection.json");
        if (!source.toString().equals(str(selection, "sourcePath")) || !STRATEGIES.contains(str(selection, "strategy"))
                || !str(selection, "baseline").matches("[0-9a-f]{40}|[0-9a-f]{64}") || bool(selection, "includeChanges", false) != includeChanges) throw new IllegalArgumentException("准备参数不属于原启动选择");
        if (target.startsWith(source) || source.startsWith(target)) throw new IllegalArgumentException("任务数据目录与源仓库不能互相包含");
        try {
            ensureParent(target.getParent());
            if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
                Map<String, Object> saved = read(manifest);
                if (!"READY".equals(str(saved, "state")) || !selection.equals(map(saved, "selection"))) throw new IllegalArgumentException("原现场准备未完成或启动选择已变化，保留现场供人工核对：" + target);
                Map<String, Object> recovered = new LinkedHashMap<>(map(saved, "facts"));
                owned(taskId, target.toString(), recovered);
                if (!str(saved, "version").equals(runtime.fingerprint(target.toString()))) throw new IllegalArgumentException("原准备回执后的文件已变化，不能把它当作尚未执行的现场：" + target);
                recovered.put("recoveredPreparation", true); return recovered;
            }
            if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || Files.exists(target.getParent().resolve("preparation.json"), LinkOption.NOFOLLOW_LINKS)) throw new IllegalArgumentException("目标目录已有现场但缺少本次选择回执，不会覆盖：" + target);
            requireSource(source, selection); runtime.ensureSpace();
            String owner = id(), reason = "Agent Workbench workspace " + owner;
            Map<String, Object> record = obj("schemaVersion", 1, "taskId", taskId, "owner", owner, "state", "PREPARED", "selection", selection, "target", target.toString(), "createdAt", now());
            save(manifest, record);
            if ("WORKTREE".equals(str(selection, "strategy"))) {
                runtime.git(source, "worktree", "add", "--detach", "--lock", "--reason", reason, "--", target.toString(), str(selection, "baseline"));
            } else {
                runtime.git(target.getParent(), "-c", "protocol.file.allow=always", "clone", "--no-checkout", "--no-hardlinks", "--no-local", "--", source.toString(), target.toString());
                // 远程跟踪引用的提交可能未被普通 clone 下载，仍按原 SHA 获取，不重新解析分支。
                runtime.git(target, "-c", "protocol.file.allow=always", "fetch", "--no-tags", "--no-write-fetch-head", "--", source.toString(), str(selection, "baseline"));
                runtime.git(target, "checkout", "--detach", str(selection, "baseline"));
                runtime.git(target, "remote", "remove", "origin");
                runtime.git(target, "config", "user.name", "Agent Workbench");
                runtime.git(target, "config", "user.email", "workbench@localhost");
                runtime.git(target, "config", "core.hooksPath", "/dev/null");
            }
            if (includeChanges && bool(map(selection, "source"), "dirty", false)) runtime.copyChanges(source, target);
            requireSource(source, selection);
            String content = runtime.fingerprint(target.toString());
            Map<String, Object> facts = obj("workspace", target.toRealPath().toString(), "baseline", selection.get("baseline"), "version", content,
                "sourceDirty", map(selection, "source").get("dirty"), "workspaceSelection", selection,
                "workspaceOwnership", obj("schemaVersion", 1, "owner", owner, "taskId", taskId, "strategy", selection.get("strategy"), "sourcePath", source.toString(),
                    "gitDirectory", gitDirectory(target, "--git-dir"), "commonDirectory", gitDirectory(target, "--git-common-dir"), "lockReason", "WORKTREE".equals(str(selection, "strategy")) ? reason : ""));
            record.putAll(obj("state", "READY", "version", content, "facts", facts, "completedAt", now()));
            save(manifest, record); owned(taskId, target.toString(), facts); return facts;
        } catch (IOException exception) { throw new IllegalStateException("现场元数据无法完成保存，保留原目录和准备意图供核对", exception); }
    }

    /**
     * 原任务、目录、Git 关联和锁定理由都要匹配，不能仅凭路径相同接管。
     */
    void owned(String taskId, String workspace, Map<String, Object> facts) {
        runtime.requireId(taskId);
        Path expected = target(taskId), supplied = Path.of(workspace).toAbsolutePath().normalize();
        Map<String, Object> ownership = map(facts, "workspaceOwnership");
        try {
            boolean gitMarker = Files.isDirectory(supplied.resolve(".git"), LinkOption.NOFOLLOW_LINKS) || Files.isRegularFile(supplied.resolve(".git"), LinkOption.NOFOLLOW_LINKS);
            if (!supplied.equals(expected) || !supplied.toRealPath().equals(expected) || !gitMarker) throw new IllegalArgumentException("任务现场缺失、解析目标变化或 Git 标记无效");
            Map<String, Object> saved = read(expected.getParent().resolve("selection.json"));
            if (!"READY".equals(str(saved, "state")) || !taskId.equals(str(saved, "taskId")) || !map(map(saved, "facts"), "workspaceOwnership").equals(ownership)
                    || !taskId.equals(str(ownership, "taskId")) || !gitDirectory(supplied, "--git-dir").equals(str(ownership, "gitDirectory"))
                    || !gitDirectory(supplied, "--git-common-dir").equals(str(ownership, "commonDirectory"))) throw new IllegalArgumentException("原现场归属回执与实际 Git 目录不一致");
            if ("WORKTREE".equals(str(ownership, "strategy"))) {
                Path source = runtime.projectRoot(str(ownership, "sourcePath"));
                if (!gitDirectory(source, "--git-common-dir").equals(str(ownership, "commonDirectory"))) throw new IllegalArgumentException("worktree 已失去原仓库关联");
                Map<String, Object> registered = worktrees(source).stream().filter(item -> workspacePathEquals(str(item, "path"), supplied)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("原仓库中没有此任务的 worktree 注册"));
                if (!str(ownership, "lockReason").equals(str(registered, "locked")) || !bool(registered, "detached", false)) throw new IllegalArgumentException("worktree 锁定归属或 detached 状态已变化，请人工核对");
            } else if (!gitDirectory(supplied, "--git-common-dir").equals(supplied.resolve(".git").toRealPath().toString())) throw new IllegalArgumentException("独立 clone 已被替换为共享 Git 现场");
        } catch (IOException exception) { throw new IllegalArgumentException("原现场或归属记录不可读取，目录不会自动重建", exception); }
    }

    String rules(String projectPath, Map<String, Object> selection) {
        Path source = runtime.projectRoot(projectPath); requireSource(source, selection);
        String content;
        if (bool(selection, "includeChanges", false) || str(selection, "baseline").equals(str(map(selection, "source"), "head"))) {
            Path rules = source.resolve("AGENTS.md");
            try {
                if (!Files.exists(rules, LinkOption.NOFOLLOW_LINKS)) content = "";
                else {
                    if (Files.isSymbolicLink(rules) || !Files.isRegularFile(rules, LinkOption.NOFOLLOW_LINKS) || Files.size(rules) > 256000) throw new IllegalArgumentException("项目规则不是可读取的普通文件或超过上限");
                    content = Files.readString(rules);
                }
            } catch (IOException exception) { throw new IllegalArgumentException("无法读取所选现场的项目规则", exception); }
        } else {
            String entry = runtime.git(source, "ls-tree", "-z", str(selection, "baseline"), "--", "AGENTS.md");
            if (entry.isBlank()) content = "";
            else {
                if (!entry.startsWith("100644 blob ") && !entry.startsWith("100755 blob ")) throw new IllegalArgumentException("所选提交的项目规则不是普通文件");
                content = runtime.git(source, "show", str(selection, "baseline") + ":AGENTS.md");
            }
        }
        requireSource(source, selection); return content;
    }

    private Map<String, Object> sourceSnapshot(Path source) {
        String head = runtime.git(source, "rev-parse", "HEAD").strip(), branch = runtime.git(source, "branch", "--show-current").strip();
        String status = runtime.git(source, "status", "--porcelain=v1", "-uall"), index = index(source), version = runtime.fingerprint(source.toString());
        if (!head.equals(runtime.git(source, "rev-parse", "HEAD").strip()) || !branch.equals(runtime.git(source, "branch", "--show-current").strip())
                || !status.equals(runtime.git(source, "status", "--porcelain=v1", "-uall")) || !index.equals(index(source))
                || !version.equals(runtime.fingerprint(source.toString()))) throw new WorkspaceSelectionConflict();
        return obj("head", head, "branch", branch, "statusDigest", digest(status.getBytes(StandardCharsets.UTF_8)), "dirty", !status.isBlank(), "index", index, "version", version);
    }

    private void requireSource(Path source, Map<String, Object> selection) {
        if (!map(selection, "source").equals(sourceSnapshot(source))) throw new WorkspaceSelectionConflict();
    }

    private String index(Path source) {
        Path index = source.resolve(runtime.git(source, "rev-parse", "--git-path", "index").strip()).normalize();
        try { return Files.exists(index) ? HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(index))) : "MISSING"; }
        catch (Exception exception) { throw new IllegalArgumentException("源暂存区无法读取", exception); }
    }

    private String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private String gitDirectory(Path root, String argument) throws IOException {
        return root.resolve(runtime.git(root, "rev-parse", argument).strip()).normalize().toRealPath().toString();
    }

    private List<Map<String, Object>> worktrees(Path source) {
        List<Map<String, Object>> result = new ArrayList<>(); Map<String, Object> record = obj();
        for (String field : runtime.git(source, "worktree", "list", "--porcelain", "-z").split(String.valueOf((char) 0), -1)) {
            if (field.isEmpty()) { if (!record.isEmpty()) result.add(record); record = obj(); continue; }
            int separator = field.indexOf(' '); String key = separator < 0 ? field : field.substring(0, separator);
            Object value = separator < 0 ? true : field.substring(separator + 1);
            record.put("worktree".equals(key) ? "path" : "HEAD".equals(key) ? "head" : key, value);
        }
        if (!record.isEmpty()) result.add(record); return result;
    }

    private boolean workspacePathEquals(String path, Path expected) {
        try { return Path.of(path).toRealPath().equals(expected); } catch (IOException exception) { return false; }
    }

    private Path target(String taskId) {
        try { Files.createDirectories(data); return data.toRealPath().resolve("workspaces").resolve(taskId).resolve("work"); }
        catch (IOException exception) { throw new IllegalStateException("任务数据目录不可定位", exception); }
    }

    private void ensureParent(Path parent) throws IOException {
        Path current = data.toRealPath();
        if (!parent.startsWith(current)) throw new IllegalArgumentException("任务目录不属于当前数据范围");
        for (Path component : current.relativize(parent)) {
            current = current.resolve(component);
            // 先核对已有父目录，再创建下一层，避免通过重解析点在外国目录中留下任务目录。
            if (Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
                if (!Files.isDirectory(current, LinkOption.NOFOLLOW_LINKS) || !current.toRealPath().equals(current)) throw new IllegalArgumentException("任务目录包含符号链接或重解析点，不能准备现场");
            } else Files.createDirectory(current);
        }
    }

    private Map<String, Object> read(Path path) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > 1000000) throw new IllegalArgumentException("现场回执不是普通文件或超过上限");
        return mapper.readValue(Files.readAllBytes(path), new TypeReference<>() {});
    }

    private void save(Path path, Map<String, Object> record) throws IOException { runtime.writeMetadataAtomic(path, mapper.writeValueAsBytes(record)); }
}
