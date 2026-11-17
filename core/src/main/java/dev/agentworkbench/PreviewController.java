package dev.agentworkbench;

import org.springframework.web.bind.annotation.*;
import java.util.Map;

/**
 * 预览元数据沿用当前认证与项目会话归属，不提供页面代理或Cookie读取。
 */
@RestController
@RequestMapping("/api/projects/{projectId}/sessions/{sessionId}/preview")
public class PreviewController {
    private final PreviewCatalog previews;

    public PreviewController(PreviewCatalog previews) { this.previews = previews; }

    @GetMapping
    public Map<String, Object> get(@PathVariable String projectId, @PathVariable String sessionId) { return previews.get(projectId, sessionId); }

    @GetMapping("/authorization")
    public Map<String, Object> authorize(@PathVariable String projectId, @PathVariable String sessionId) { return previews.authorize(projectId, sessionId); }

    @PostMapping
    public Map<String, Object> update(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody Map<String, Object> input) { return previews.update(projectId, sessionId, input); }

    @PostMapping("/reset")
    public Map<String, Object> reset(@PathVariable String projectId, @PathVariable String sessionId, @RequestBody Map<String, Object> input) { return previews.reset(projectId, sessionId, input); }
}
