package dev.agentworkbench;

import jakarta.annotation.PostConstruct;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import static dev.agentworkbench.Json.*;

/**
 * 项目会话与可追溯记录；浏览状态不控制后台任务生命周期。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sessions")
public class SessionController {
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;

    public SessionController(ExecutionStore store, WorkbenchCatalog catalog) { this.store = store; this.catalog = catalog; }

    @PostConstruct
    void migrate() { store.migrateTaskSessions(); }

    @GetMapping
    public Map<String, Object> list(@PathVariable String projectId, @RequestParam(defaultValue = "0") long before,
            @RequestParam(defaultValue = "50") int limit, @RequestParam(defaultValue = "false") boolean openOnly) {
        return store.sessions(projectId, before, limit, openOnly);
    }

    @GetMapping("/search")
    public Map<String, Object> search(@PathVariable String projectId, @RequestParam String q, @RequestParam(defaultValue = "30") int limit) {
        return store.searchSessionMessages(projectId, catalog.redact(q), limit);
    }

    @PostMapping
    public Map<String, Object> create(@PathVariable String projectId, @RequestBody Map<String, Object> body) {
        return store.createSession(projectId, catalog.redact(required(body, "title", 180)));
    }

    @GetMapping("/{sessionId}")
    public Map<String, Object> get(@PathVariable String projectId, @PathVariable String sessionId) { return store.session(projectId, sessionId); }

    @PostMapping("/{sessionId}")
    public Map<String, Object> update(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(body);
        if (input.containsKey("title")) input.put("title", catalog.redact(required(input, "title", 180)));
        return store.editSession(projectId, sessionId, input);
    }

    @GetMapping("/{sessionId}/messages")
    public Map<String, Object> messages(@PathVariable String projectId, @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long before, @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "50") int limit) {
        return store.sessionMessages(projectId, sessionId, before, after, limit);
    }

    @PostMapping("/{sessionId}/messages")
    public Map<String, Object> note(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody Map<String, Object> body) {
        String requestId = required(body, "requestId", 100);
        if (!requestId.matches("[a-zA-Z0-9_-]{1,100}")) throw new ApiException(400, "记录编号仅允许字母、数字、下划线和短横线");
        return store.appendSessionNote(projectId, sessionId, requestId, catalog.redact(required(body, "content", 16000)));
    }

    @GetMapping("/{sessionId}/events")
    public Map<String, Object> events(@PathVariable String projectId, @PathVariable String sessionId,
            @RequestParam(defaultValue = "0") long after, @RequestParam(defaultValue = "100") int limit) {
        return store.sessionEvents(projectId, sessionId, after, limit);
    }
}
