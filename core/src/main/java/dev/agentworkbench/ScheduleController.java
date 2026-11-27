package dev.agentworkbench;

import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

import static dev.agentworkbench.Json.*;

/**
 * Local API for durable daily schedules and their trigger records.
 */
@RestController
@RequestMapping("/api/schedules")
public final class ScheduleController {
    private final PersistentScheduleService schedules;

    public ScheduleController(PersistentScheduleService schedules) { this.schedules = schedules; }

    @GetMapping
    public java.util.List<Map<String, Object>> list() { return schedules.list(); }

    @PostMapping
    public Map<String, Object> create(@RequestBody Map<String, Object> body) { return schedules.create(body); }

    @PatchMapping("/{id}")
    public Map<String, Object> update(@PathVariable String id, @RequestBody Map<String, Object> body) { return schedules.update(id, body); }

    @GetMapping("/{id}/occurrences")
    public java.util.List<Map<String, Object>> occurrences(@PathVariable String id) { return schedules.occurrences(id); }

    @DeleteMapping("/{id}")
    public Map<String, Object> disable(@PathVariable String id) {
        Map<String, Object> schedule = schedules.list().stream().filter(item -> id.equals(str(item, "id"))).findFirst().orElseThrow(() -> new ApiException(404, "定时计划不存在"));
        return schedules.update(id, obj("expectedVersion", schedule.get("version"), "enabled", false));
    }
}
