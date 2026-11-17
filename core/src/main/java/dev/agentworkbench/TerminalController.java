package dev.agentworkbench;

import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.util.Map;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import static dev.agentworkbench.Json.*;

/**
 * 沿用本机 Bearer 认证；控制凭据仅在请求体中传递，查看不会改变原 shell。
 */
@RestController
@RequestMapping("/api")
public final class TerminalController {
    private final WorkbenchEngine engine;

    public TerminalController(WorkbenchEngine engine) { this.engine = engine; }

    static Map<String, Object> limits() {
        return obj("activeSessions", 4, "inputBytes", TerminalLedger.MAX_INPUT_BYTES, "frameBytes", TerminalOutputArchive.MAX_SEGMENT_BYTES,
            "outputBytes", TerminalOutputArchive.MAX_OUTPUT_BYTES, "actions", TerminalLedger.MAX_ACTIONS, "requestBytes", TerminalRequestBody.MAX_BYTES);
    }

    @PostMapping("/tasks/{id}/terminals")
    public Map<String, Object> start(@PathVariable String id, @RequestBody Map<String, Object> body) { return engine.startTerminal(id, body); }

    @GetMapping("/tasks/{id}/terminals")
    public Map<String, Object> history(@PathVariable String id, @RequestParam(defaultValue = "0") long before, @RequestParam(defaultValue = "30") int limit) {
        return engine.terminalPage(id, before, limit);
    }

    @GetMapping("/terminals/{id}")
    public Map<String, Object> session(@PathVariable String id) { return engine.terminal(id); }

    @GetMapping("/terminals/{id}/output")
    public Map<String, Object> output(@PathVariable String id, @RequestParam String epoch, @RequestParam(defaultValue = "0") long after,
            @RequestParam(defaultValue = "65536") int limit) { return engine.terminalOutput(id, epoch, after, limit); }

    @PostMapping("/terminals/{id}/control")
    public Map<String, Object> control(@PathVariable String id, @RequestBody Map<String, Object> body) { return engine.acquireTerminal(id, body); }

    @PostMapping("/terminals/{id}/actions")
    public Map<String, Object> action(@PathVariable String id, @RequestBody Map<String, Object> body) { return engine.terminalAction(id, body); }

    @GetMapping("/terminals/{id}/actions")
    public Map<String, Object> actions(@PathVariable String id, @RequestParam(defaultValue = "0") long before, @RequestParam(defaultValue = "30") int limit) {
        return engine.terminalActions(id, before, limit);
    }

    @GetMapping("/terminals/{id}/actions/{requestId}")
    public Map<String, Object> observation(@PathVariable String id, @PathVariable String requestId) { return engine.terminalActionObservation(id, requestId); }
}
