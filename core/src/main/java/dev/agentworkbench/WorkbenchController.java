package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.WorkspaceRuntime;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import static dev.agentworkbench.Json.*;

/**
 * 供桌面使用的版本化本机 HTTP 接口。
 */
@RestController
@RequestMapping("/api")
public class WorkbenchController {
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final WorkbenchEngine engine;
    private final WorkspaceRuntime runtime;
    private final ResourceManager resources;
    private final PlanValidator validator;
    private final ObjectMapper mapper;
    private final ConfigurableApplicationContext application;

    public WorkbenchController(ExecutionStore store, WorkbenchCatalog catalog, WorkbenchEngine engine, WorkspaceRuntime runtime, ResourceManager resources, PlanValidator validator, ObjectMapper mapper, ConfigurableApplicationContext application) {
        this.store = store; this.catalog = catalog; this.engine = engine; this.runtime = runtime; this.resources = resources; this.validator = validator; this.mapper = mapper;
        this.application = application;
    }

    @GetMapping("/health")
    public Map<String, Object> health() { return obj("status", store.healthy() ? "UP" : "DEGRADED", "version", "0.2.0-alpha.4", "apiVersion", 1, "schemaVersion", 2); }

    @PostMapping("/shutdown")
    public Map<String, Object> shutdown() {
        Thread.startVirtualThread(() -> {
            try { Thread.sleep(200); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            application.close();
        });
        return obj("status", "STOPPING", "message", "正在受控停止任务并保存状态");
    }

    @GetMapping("/state")
    public Map<String, Object> state() {
        List<Map<String, Object>> tasks = store.list("task");
        List<Map<String, Object>> summaries = tasks.stream().map(task -> {
            Map<String, Object> value = new java.util.LinkedHashMap<>(task);
            value.remove("evidence"); value.remove("context"); value.remove("profileSnapshot"); value.remove("routeSnapshot");
            value.put("evidenceCount", objects(task.get("evidence")).size()); return value;
        }).toList();
        return obj("projects", store.list("project"), "tasks", summaries, "routes", catalog.routes(), "memories", store.list("memory"), "profiles", store.list("profile"),
            "system", obj("version", "0.2.0-alpha.4", "modelCapacity", 2, "activeModels", resources.activeModels(), "resources", resources.snapshot(), "storageHealthy", store.healthy(),
                "eventCursor", store.latestEvent(), "credentialStorage", "SESSION_ONLY", "releaseStatus", "DEVELOPMENT_PREVIEW", "capabilities", List.of("Git 隔离工作目录", "三模式阶段编排", "根预算与验证预留", "独立审查与人工验收", "结果未知保护", "持久事件续读"),
                "limitations", List.of("API 兼容性须按实际端点验证", "原生 CLI 费用与内部请求数为软预算", "本机工作目录隔离不等于操作系统安全隔离", "MCP、自动采纳、三端打包与长期性能尚待发布验收")));
    }

    @PostMapping("/projects")
    public Map<String, Object> project(@RequestBody Map<String, Object> body) { return catalog.importProject(body); }

    @PostMapping("/projects/{id}/scan")
    public Map<String, Object> scan(@PathVariable String id) { return catalog.scanProject(id); }

    @GetMapping("/projects/{id}/search")
    public Map<String, Object> search(@PathVariable String id, @RequestParam(defaultValue = "") String q) { return catalog.search(id, q); }

    @GetMapping("/projects/{id}")
    public Map<String, Object> project(@PathVariable String id) { return store.get("project", id); }

    @PostMapping("/tasks")
    public Map<String, Object> createTask(@RequestBody Map<String, Object> body) { return catalog.createTask(body); }

    @GetMapping("/tasks/{id}")
    public Map<String, Object> task(@PathVariable String id) { return store.get("task", id); }

    @GetMapping("/tasks/{id}/mcp-operations")
    public List<Map<String, Object>> mcpOperations(@PathVariable String id) {
        store.get("task", id);
        return store.list("mcpOperation").stream().filter(operation -> id.equals(str(operation, "taskId"))).toList();
    }

    @PostMapping("/tasks/{id}/{action}")
    public Map<String, Object> action(@PathVariable String id, @PathVariable String action, @RequestBody(required = false) Map<String, Object> body) { return engine.action(id, action, body == null ? obj() : body); }

    @PostMapping("/tasks/{id}/mcp-authorization")
    public Map<String, Object> mcpAuthorization(@PathVariable String id, @RequestBody Map<String, Object> body) { return engine.action(id, "mcp-authorization", body); }

    @GetMapping("/tasks/{id}/diff")
    public Map<String, Object> diff(@PathVariable String id) {
        Map<String, Object> task = store.get("task", id); String workspace = str(task, "workspace");
        return obj("diff", workspace.isBlank() ? "" : runtime.diff(workspace), "version", workspace.isBlank() ? "" : runtime.fingerprint(workspace), "workspace", workspace);
    }

    @GetMapping("/tasks/{id}/operations")
    public List<Map<String, Object>> operations(@PathVariable String id) {
        store.get("task", id);
        return store.list("operation").stream().filter(operation -> id.equals(str(operation, "taskId"))).toList();
    }

    @GetMapping("/tasks/{id}/events")
    public Map<String, Object> events(@PathVariable String id, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "200") int limit) {
        store.get("task", id); return store.events(id, after, limit);
    }

    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter subscribe(@RequestParam(defaultValue = "0") long after, @RequestHeader(value = "Last-Event-ID", required = false) String lastId) {
        long startingCursor = after;
        try { if (lastId != null) { startingCursor = Math.max(after, Long.parseLong(lastId)); } } catch (NumberFormatException ignored) { }
        SseEmitter emitter = new SseEmitter(60000L);
        AtomicBoolean closed = new AtomicBoolean();
        emitter.onCompletion(() -> closed.set(true)); emitter.onTimeout(() -> { closed.set(true); emitter.complete(); }); emitter.onError(ignored -> closed.set(true));
        long initial = startingCursor;
        Thread.startVirtualThread(() -> {
            long cursor = initial;
            try {
                while (!closed.get()) {
                    Map<String, Object> page = store.events(null, cursor, 100);
                    for (Map<String, Object> event : objects(page.get("events"))) {
                        emitter.send(SseEmitter.event().id(String.valueOf(event.get("id"))).name("event").data(event));
                        cursor = ((Number) event.get("id")).longValue();
                    }
                    if (!bool(page, "hasMore", false)) { emitter.send(SseEmitter.event().comment("heartbeat")); Thread.sleep(1500); }
                }
            } catch (Exception exception) { emitter.complete(); }
        });
        return emitter;
    }

    @PostMapping("/routes")
    public Map<String, Object> route(@RequestBody Map<String, Object> body) { return catalog.saveRoute(body); }

    @GetMapping("/routes")
    public List<Map<String, Object>> routes() { return catalog.routes(); }

    @PostMapping("/routes/{id}/probe")
    public Map<String, Object> probe(@PathVariable String id) { return catalog.probe(id); }

    @PostMapping("/memories")
    public Map<String, Object> memory(@RequestBody Map<String, Object> body) { return catalog.saveMemory(body); }

    @PatchMapping("/memories/{id}")
    public Map<String, Object> memory(@PathVariable String id, @RequestBody Map<String, Object> body) { return catalog.editMemory(id, body); }

    @DeleteMapping("/memories/{id}")
    public Map<String, Object> deleteMemory(@PathVariable String id) { store.get("memory", id); store.delete("memory", id); return obj("deleted", true); }

    @PostMapping("/profiles")
    public Map<String, Object> profile(@RequestBody Map<String, Object> body) { return catalog.saveProfile(body); }

    @GetMapping("/profiles/{id}/history")
    public List<Map<String, Object>> profileHistory(@PathVariable String id) { return store.list("profileRevision").stream().filter(item -> id.equals(str(item, "profileId"))).toList(); }

    @PostMapping("/plans/validate")
    public Map<String, Object> validate(@RequestBody Map<String, Object> body) { return validator.validate(body); }

    @GetMapping("/tasks/{id}/export")
    public ResponseEntity<byte[]> export(@PathVariable String id) throws IOException {
        Map<String, Object> task = store.get("task", id);
        String workspace = str(task, "workspace");
        if (List.of("RUNNING", "QUEUED", "WAITING_RESOURCE", "PAUSING", "CANCELLING").contains(str(task, "state"))) { throw new ApiException(409, "请先暂停任务并等待停止确认后导出一致成果"); }
        if (!workspace.isBlank() && !str(task, "version").equals(runtime.fingerprint(workspace))) { throw new ApiException(409, "当前文件与任务记录的版本不同，请先重新验证，避免将新补丁与旧证据混合导出"); }
        String patch = workspace.isBlank() ? "" : runtime.exportPatch(workspace);
        if (!workspace.isBlank() && !str(task, "version").equals(runtime.fingerprint(workspace))) { throw new ApiException(409, "生成补丁时文件发生变化，请停止外部修改并重新验证"); }
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(bytes, StandardCharsets.UTF_8)) {
            putZip(zip, "manifest.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(obj("schemaVersion", 1, "taskId", id, "exportedAt", now(), "version", task.get("version"), "baseline", task.get("baseline"), "state", task.get("state"), "outcome", task.get("outcome"), "credentialsIncluded", false)));
            putZip(zip, "task.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(task));
            putZip(zip, "changes.patch", patch.getBytes(StandardCharsets.UTF_8));
            putZip(zip, "operations.json", mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(operations(id)));
            StringBuilder lines = new StringBuilder(); long cursor = 0;
            while (true) {
                Map<String, Object> page = store.events(id, cursor, 200);
                for (Map<String, Object> event : objects(page.get("events"))) { lines.append(mapper.writeValueAsString(event)).append('\n'); }
                cursor = ((Number) page.get("cursor")).longValue();
                if (!bool(page, "hasMore", false)) { break; }
            }
            putZip(zip, "events.jsonl", lines.toString().getBytes(StandardCharsets.UTF_8));
            putZip(zip, "README.md", ("# 任务交付包\n\n本包保存需求、固定计划、证据、原始事件和代码补丁。\n\n状态：" + task.get("state") + "\n代码版本：" + task.get("version") + "\n\n验收只记录用户决定，不自动写入原项目。应用补丁前请检查目标版本和未提交修改，并重新运行必要验证。未知操作、失败和未验证项以 task.json 及 operations.json 为准。凭据不包含在本包中。\n").getBytes(StandardCharsets.UTF_8));
        }
        return ResponseEntity.ok().header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=task-" + id + ".zip").contentType(MediaType.parseMediaType("application/zip")).body(bytes.toByteArray());
    }

    @PostMapping("/backup")
    public Map<String, Object> backup() {
        if (resources.activeModels() > 0 || store.list("task").stream().anyMatch(task -> List.of("RUNNING", "WAITING_RESOURCE", "QUEUED", "PAUSING", "CANCELLING").contains(str(task, "state")))) { throw new ApiException(409, "请先暂停任务，才能创建一致备份"); }
        return obj("path", store.backup().toString(), "scope", "STRUCTURED_STATE_ONLY", "message", "仅结构化状态快照；工作目录与成果需通过任务交付包另存，不能当作完整灾难恢复备份");
    }

    private void putZip(ZipOutputStream zip, String path, byte[] content) throws IOException { zip.putNextEntry(new ZipEntry(path)); zip.write(content); zip.closeEntry(); }
}
