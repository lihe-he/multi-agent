package dev.agentworkbench;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import dev.agentworkbench.mcp.McpTaskPolicy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import static dev.agentworkbench.Json.*;

/**
 * Durable daily schedules that create one independently scoped task per local date.
 */
@Service
public final class PersistentScheduleService {
    private static final Logger LOGGER = Logger.getLogger(PersistentScheduleService.class.getName());
    private static final Duration MISFIRE_GRACE = Duration.ofMinutes(5);
    private static final int BACKFILL_DATES_PER_SCHEDULE_TICK = 100;
    private static final int PENDING_PER_TICK = 100;
    private static final Set<String> SCHEDULE_FIELDS = Set.of("name", "timezone", "localTime", "enabled", "task");
    private static final Set<String> TASK_FIELDS = Set.of("projectId", "routeId", "title", "goal", "mode", "contract", "budget", "locks", "includeChanges", "workflow", "collaboration", "mcpBindings", "mcpBudget", "workspaceSelection");

    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final WorkbenchEngine engine;
    private final Clock clock;
    private final ScheduledExecutorService poller = Executors.newSingleThreadScheduledExecutor(runnable -> Thread.ofPlatform().daemon(true).name("workbench-schedule-poller").unstarted(runnable));

    @Autowired
    public PersistentScheduleService(ExecutionStore store, WorkbenchCatalog catalog, WorkbenchEngine engine) {
        this(store, catalog, engine, Clock.systemUTC());
    }

    PersistentScheduleService(ExecutionStore store, WorkbenchCatalog catalog, WorkbenchEngine engine, Clock clock) {
        this.store = store; this.catalog = catalog; this.engine = engine; this.clock = clock;
    }

    @PostConstruct
    void start() { poller.scheduleWithFixedDelay(this::pollSafely, 1, 30, TimeUnit.SECONDS); }

    @PreDestroy
    void close() { poller.shutdownNow(); }

    public synchronized Map<String, Object> create(Map<String, Object> input) {
        requireKeys(input, SCHEDULE_FIELDS, "定时计划");
        Instant created = clock.instant();
        Map<String, Object> schedule = normalize(input, id(), 1);
        schedule.put("createdAt", created.toString()); schedule.put("updatedAt", created.toString());
        store.createSchedule(schedule, revision(schedule, created, null));
        return schedule;
    }

    public synchronized Map<String, Object> update(String scheduleId, Map<String, Object> input) {
        if (input.keySet().stream().anyMatch(key -> !SCHEDULE_FIELDS.contains(key) && !"expectedVersion".equals(key))) throw new ApiException(400, "定时计划包含不支持的字段");
        Map<String, Object> old = store.get("schedule", scheduleId);
        if (!(input.get("expectedVersion") instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() != num(old, "version", 1)) throw new ApiException(409, "定时计划已变化，请刷新后编辑");
        Map<String, Object> merged = new LinkedHashMap<>();
        for (String field : SCHEDULE_FIELDS) merged.put(field, input.containsKey(field) ? input.get(field) : old.get(field));
        int version = num(old, "version", 1) + 1;
        Instant effective = clock.instant();
        Map<String, Object> updated = normalize(merged, scheduleId, version);
        updated.put("createdAt", old.get("createdAt")); updated.put("updatedAt", effective.toString());
        List<Map<String, Object>> revisions = store.scheduleRevisions(scheduleId);
        if (revisions.isEmpty()) throw new ApiException(503, "定时计划缺少持久配置版本");
        Map<String, Object> closedRevision = new LinkedHashMap<>(revisions.getLast());
        closedRevision.put("effectiveTo", effective.toString());
        boolean changed = store.updateScheduleVersion(scheduleId, num(old, "version", 1), updated, closedRevision, revision(updated, effective, null));
        if (!changed) throw new ApiException(409, "定时计划已变化，请刷新后编辑");
        return updated;
    }

    public synchronized List<Map<String, Object>> list() { return store.list("schedule"); }

    public synchronized List<Map<String, Object>> occurrences(String scheduleId) {
        store.get("schedule", scheduleId);
        return store.scheduleOccurrences(scheduleId, 100);
    }

    private Map<String, Object> normalize(Map<String, Object> input, String scheduleId, int version) {
        String name = required(input, "name", 160), zone = required(input, "timezone", 100), localTime = required(input, "localTime", 5);
        DailyScheduleTime.zone(zone); DailyScheduleTime.time(localTime);
        if (!(input.get("enabled") instanceof Boolean)) throw new ApiException(400, "enabled 必须为布尔值");
        if (!(input.get("task") instanceof Map<?, ?>)) throw new ApiException(400, "task 必须为任务模板对象");
        Map<String, Object> task = map(input.get("task"));
        requireKeys(task, TASK_FIELDS, "任务模板");
        requireObject(task, "contract", "完成约定");
        requireObject(task, "budget", "调用预算");
        requireObject(task, "locks", "硬锁定");
        requireObject(task, "workflow", "流程选择");
        requireObject(task, "collaboration", "协作配置");
        requireObject(task, "mcpBudget", "MCP 预算");
        requireObject(task, "workspaceSelection", "启动现场选择");
        requireObjectList(task, "mcpBindings", "MCP 固定动作");
        validateContractShape(task);
        validateBudgetAndLocksShape(task);
        validateWorkflowSelection(task);
        validateWorkspaceSelection(task);
        validateCollaborationShape(task);
        validateMcpShape(task);
        String projectId = required(task, "projectId", 100), routeId = required(task, "routeId", 100);
        store.get("project", projectId);
        Map<String, Object> route = store.get("route", routeId);
        if (!BudgetPolicy.matchesLocks(route, map(task.get("locks")))) throw new ApiException(409, "所选运行器不满足定时任务的硬锁定");
        CollaborationPolicy.snapshot(store, projectId, route, map(task.get("locks")), map(task.get("collaboration")));
        McpTaskPolicy.snapshot(store, projectId, route, task.get("mcpBindings"), 1);
        required(task, "title", 180); required(task, "goal", 40000);
        Map<String, Object> contract = map(task.get("contract"));
        if (!(contract.get("acceptance") instanceof List<?> acceptanceItems) || acceptanceItems.stream().anyMatch(value -> !(value instanceof String))) throw new ApiException(400, "验收条件必须为字符串清单");
        if (!(contract.get("commands") instanceof List<?> commandItems) || commandItems.stream().anyMatch(value -> !(value instanceof String))) throw new ApiException(400, "必要验证命令必须为字符串清单");
        List<String> acceptance = strings(contract.get("acceptance")).stream().filter(value -> !value.isBlank()).toList();
        List<String> commands = strings(contract.get("commands")).stream().filter(value -> !value.isBlank()).toList();
        if (acceptance.isEmpty() || commands.isEmpty()) throw new ApiException(400, "任务模板需要至少一项验收条件和一条必要验证命令");
        if (acceptance.size() > 30 || commands.size() > 12) throw new ApiException(400, "完成约定过大，请拆分任务");
        if (contract.containsKey("scope") && (!(contract.get("scope") instanceof List<?> scope) || scope.stream().anyMatch(value -> !(value instanceof String)))) throw new ApiException(400, "修改范围必须为相对路径清单");
        List<String> scope = strings(contract.get("scope"));
        if (scope.size() > 100) throw new ApiException(400, "完成约定过大，请拆分任务");
        AgentPermissions.scope(scope.isEmpty() ? List.of("**") : scope);
        Map<String, Object> budget = map(task.get("budget"));
        if (budget.containsKey("maxCalls")) requireIntegerRange(budget, "maxCalls", 2, 1000, "总调用预算");
        if (budget.containsKey("reserveCalls")) requireIntegerRange(budget, "reserveCalls", 1, 999, "验证预留");
        int maxCalls = num(budget, "maxCalls", 24), reserveCalls = num(budget, "reserveCalls", 6);
        if (maxCalls < 2 || maxCalls > 1000 || reserveCalls < 1 || reserveCalls >= maxCalls) throw new ApiException(400, "调用上限应为 2 至 1000，验证预留必须大于 0 且小于总额度");
        if (task.containsKey("includeChanges") && !(task.get("includeChanges") instanceof Boolean)) throw new ApiException(400, "includeChanges 必须为布尔值");
        String mode = str(task, "mode").toUpperCase();
        if (!mode.isBlank() && !Set.of("AUTO", "LEAD", "ROUNDTABLE", "PIPELINE").contains(mode)) throw new ApiException(400, "未知协作模式");
        Map<String, Object> copy = new LinkedHashMap<>(task);
        return obj("id", scheduleId, "schemaVersion", 1, "version", version, "name", name, "timezone", zone, "localTime", localTime,
            "enabled", input.get("enabled"), "state", bool(input, "enabled", false) ? "ACTIVE" : "DISABLED", "task", copy);
    }

    private Map<String, Object> revision(Map<String, Object> schedule, Instant effectiveFrom, Instant effectiveTo) {
        return obj("id", str(schedule, "id") + ":v" + num(schedule, "version", 1), "schemaVersion", 1, "scheduleId", schedule.get("id"),
            "version", schedule.get("version"), "enabled", schedule.get("enabled"), "timezone", schedule.get("timezone"), "localTime", schedule.get("localTime"),
            "task", schedule.get("task"), "effectiveFrom", effectiveFrom.toString(), "effectiveTo", effectiveTo == null ? null : effectiveTo.toString(),
            "lastScannedLocalDate", null, "createdAt", effectiveFrom.toString());
    }

    private void requireKeys(Map<String, Object> input, Set<String> allowed, String label) {
        if (!allowed.containsAll(input.keySet())) throw new ApiException(400, label + "包含不支持的字段");
    }

    private void requireObject(Map<String, Object> input, String key, String label) {
        if (input.containsKey(key) && !(input.get(key) instanceof Map<?, ?>)) throw new ApiException(400, key + " 必须为" + label + "对象");
    }

    private void requireList(Map<String, Object> input, String key, String label) {
        if (input.containsKey(key) && !(input.get(key) instanceof List<?>)) throw new ApiException(400, key + " 必须为" + label + "清单");
    }

    private void requireObjectList(Map<String, Object> input, String key, String label) {
        requireList(input, key, label);
        if (input.get(key) instanceof List<?> values && values.stream().anyMatch(value -> !(value instanceof Map<?, ?>))) {
            throw new ApiException(400, key + " 必须为对象清单");
        }
    }

    private void validateWorkflowSelection(Map<String, Object> task) {
        if (!(task.get("workflow") instanceof Map<?, ?> raw)) return;
        Map<String, Object> workflow = map(raw);
        Set<String> fields = Set.of("definitionId", "version", "digest", "rootRouteVersion", "allowedRouteVersions", "allowedRouteIds");
        requireKeys(workflow, fields, "流程选择");
        required(workflow, "definitionId", 100); required(workflow, "digest", 200);
        requireInteger(workflow, "version", "流程版本");
        if (workflow.containsKey("rootRouteVersion")) requireInteger(workflow, "rootRouteVersion", "根路由版本");
        if (workflow.containsKey("allowedRouteIds")) requireStringList(workflow.get("allowedRouteIds"), "allowedRouteIds");
        if (workflow.containsKey("allowedRouteVersions")) {
            if (!(workflow.get("allowedRouteVersions") instanceof Map<?, ?> versions)
                    || versions.values().stream().anyMatch(value -> !(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1)) {
                throw new ApiException(400, "allowedRouteVersions 必须为正整数映射");
            }
        }
    }

    private void validateContractShape(Map<String, Object> task) {
        if (!(task.get("contract") instanceof Map<?, ?> raw)) return;
        Map<String, Object> contract = map(raw);
        requireKeys(contract, Set.of("scope", "acceptance", "commands"), "完成约定");
    }

    private void validateBudgetAndLocksShape(Map<String, Object> task) {
        if (task.get("budget") instanceof Map<?, ?> raw) requireKeys(map(raw), Set.of("maxCalls", "reserveCalls"), "调用预算");
        if (task.get("locks") instanceof Map<?, ?> raw) {
            Map<String, Object> locks = map(raw);
            requireKeys(locks, Set.of("engine", "model", "provider", "routeId"), "硬锁定");
            if (locks.values().stream().anyMatch(value -> !(value instanceof String))) throw new ApiException(400, "硬锁定值必须为字符串");
        }
    }

    private void validateWorkspaceSelection(Map<String, Object> task) {
        if (!(task.get("workspaceSelection") instanceof Map<?, ?> raw)) return;
        Map<String, Object> selection = map(raw);
        requireKeys(selection, Set.of("strategy", "ref", "expectedHead", "sourceVersion", "sourceIndex"), "启动现场选择");
        for (String key : selection.keySet()) if (!(selection.get(key) instanceof String)) throw new ApiException(400, key + " 必须为字符串");
        String strategy = str(selection, "strategy");
        if (!strategy.isBlank() && !Set.of("CLONE", "WORKTREE").contains(strategy)) throw new ApiException(400, "未知现场策略");
    }

    private void validateCollaborationShape(Map<String, Object> task) {
        if (!(task.get("collaboration") instanceof Map<?, ?> raw)) return;
        Map<String, Object> collaboration = map(raw);
        requireKeys(collaboration, Set.of("enabled", "maxActive", "maxDepth", "maxTotal", "definitionIds", "definitionVersions", "allowedRouteIds"), "协作配置");
        if (collaboration.containsKey("enabled") && !(collaboration.get("enabled") instanceof Boolean)) throw new ApiException(400, "协作 enabled 必须为布尔值");
        if (collaboration.containsKey("maxActive")) requireIntegerRange(collaboration, "maxActive", 2, 6, "活动 Agent 上限");
        if (collaboration.containsKey("maxDepth")) requireIntegerRange(collaboration, "maxDepth", 1, 4, "委派深度");
        if (collaboration.containsKey("maxTotal")) requireIntegerRange(collaboration, "maxTotal", 1, 64, "累计委派上限");
        for (String key : List.of("definitionIds", "allowedRouteIds")) if (collaboration.containsKey(key)) requireStringList(collaboration.get(key), key);
        if (collaboration.containsKey("definitionVersions")) {
            Object value = collaboration.get("definitionVersions");
            if (!(value instanceof Map<?, ?> versions) || versions.values().stream().anyMatch(item -> !(item instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1)) {
                throw new ApiException(400, "definitionVersions 必须为正整数映射");
            }
            if (!new java.util.HashSet<>(strings(collaboration.get("definitionIds")).stream().distinct().toList()).equals(versions.keySet())) {
                throw new ApiException(400, "definitionVersions 必须与 definitionIds 完全对应");
            }
        }
    }

    private void validateMcpShape(Map<String, Object> task) {
        if (task.get("mcpBindings") instanceof List<?> bindings) {
            if (bindings.size() > 16) throw new ApiException(400, "固定 MCP 动作最多 16 项");
            for (Object value : bindings) {
                Map<String, Object> binding = map(value);
                requireKeys(binding, Set.of("serverId", "name", "configVersion", "definitionVersion", "fixedArguments", "timeoutMs"), "MCP 固定动作");
                required(binding, "serverId", 100); required(binding, "name", 200);
                requireIntegerRange(binding, "configVersion", 1, Integer.MAX_VALUE, "MCP 配置版本");
                required(binding, "definitionVersion", 200);
                if (!(binding.get("fixedArguments") instanceof Map<?, ?>)) throw new ApiException(400, "fixedArguments 必须为 JSON 对象");
                if (binding.containsKey("timeoutMs")) requireIntegerRange(binding, "timeoutMs", 1000, 60000, "工具超时");
            }
        }
        if (task.get("mcpBudget") instanceof Map<?, ?> raw) {
            Map<String, Object> budget = map(raw);
            requireKeys(budget, Set.of("maxCalls", "maxDurationMs"), "MCP 预算");
            if (budget.containsKey("maxCalls")) requireIntegerRange(budget, "maxCalls", 1, 64, "MCP 调用上限");
            if (budget.containsKey("maxDurationMs")) requireIntegerRange(budget, "maxDurationMs", 1000, 3600000, "MCP 时间上限");
        }
    }

    private void requireStringList(Object value, String key) {
        if (!(value instanceof List<?> list) || list.size() > 100 || list.stream().anyMatch(item -> !(item instanceof String text) || text.isBlank() || text.length() > 100)) {
            throw new ApiException(400, key + " 必须为有效字符串清单");
        }
    }

    private void requireInteger(Map<String, Object> input, String key, String label) {
        Object value = input.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < 1) throw new ApiException(400, label + "必须为正整数");
    }

    private void requireIntegerRange(Map<String, Object> input, String key, int minimum, int maximum, String label) {
        Object value = input.get(key);
        if (!(value instanceof Number number) || number.doubleValue() != number.intValue() || number.intValue() < minimum || number.intValue() > maximum) {
            throw new ApiException(400, label + "必须为 " + minimum + " 至 " + maximum + " 的整数");
        }
    }

    private void pollSafely() {
        try { tick(clock.instant()); }
        catch (RuntimeException exception) { LOGGER.log(Level.SEVERE, "定时扫描失败；周期轮询将继续", exception); }
    }

    synchronized void tick(Instant instant) {
        for (Map<String, Object> occurrence : store.pendingScheduleOccurrences(PENDING_PER_TICK)) {
            try { process(occurrence); }
            catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "无法恢复待处理的定时触发 " + str(occurrence, "id"), exception);
                if (!store.healthy()) return;
            }
        }
        for (Map<String, Object> schedule : store.list("schedule")) {
            try { backfill(schedule, instant); }
            catch (RuntimeException exception) {
                LOGGER.log(Level.SEVERE, "定时计划扫描失败 " + str(schedule, "id"), exception);
                if (!store.healthy()) return;
            }
        }
    }

    private void backfill(Map<String, Object> schedule, Instant now) {
        int remaining = BACKFILL_DATES_PER_SCHEDULE_TICK;
        for (Map<String, Object> revision : store.scheduleRevisions(str(schedule, "id"))) {
            if (remaining <= 0) break;
            if (!bool(revision, "enabled", false)) continue;
            remaining -= scanRevision(revision, now, remaining);
        }
    }

    private int scanRevision(Map<String, Object> revision, Instant now, int limit) {
        ZoneId zone = DailyScheduleTime.zone(str(revision, "timezone"));
        Instant from = Instant.parse(str(revision, "effectiveFrom"));
        Instant to = str(revision, "effectiveTo").isBlank() ? null : Instant.parse(str(revision, "effectiveTo"));
        Instant through = to != null && to.isBefore(now) ? to : now;
        if (through.isBefore(from)) return 0;
        String lastScanned = str(revision, "lastScannedLocalDate");
        LocalDate date = lastScanned.isBlank() ? from.atZone(zone).toLocalDate() : LocalDate.parse(lastScanned).plusDays(1);
        LocalDate last = through.atZone(zone).toLocalDate();
        int scanned = 0;
        LocalDate completedThrough = lastScanned.isBlank() ? null : LocalDate.parse(lastScanned);
        while (!date.isAfter(last) && scanned < limit) {
            String scheduleId = str(revision, "scheduleId"), localTime = str(revision, "localTime");
            Map<String, Object> existing = store.scheduleOccurrence(scheduleId, date.toString());
            if (existing == null) {
                DailyScheduleTime.Due due = DailyScheduleTime.resolve(str(revision, "timezone"), localTime, date);
                if (due == null) {
                    var transition = DailyScheduleTime.gap(str(revision, "timezone"), localTime, date);
                    if (transition != null && transition.getInstant().isAfter(now)) break;
                    if (transition != null && !transition.getInstant().isBefore(from)
                            && (to == null || transition.getInstant().isBefore(to)) && !transition.getInstant().isAfter(now)) {
                        Map<String, Object> skipped = occurrence(revision, date, transition.getInstant(), "SKIPPED_DST_GAP", "本地时刻位于夏令时缺口；已审计跳过，不会补跑");
                        store.createScheduleOccurrenceIfAbsent(skipped);
                    }
                } else {
                    if (due.instant().isAfter(now)) break;
                    if (!due.instant().isBefore(from) && (to == null || due.instant().isBefore(to))) {
                        boolean missed = now.isAfter(due.instant().plus(MISFIRE_GRACE));
                        Map<String, Object> created = occurrence(revision, date, due.instant(), missed ? "MISSED" : "PENDING",
                            missed ? "错过五分钟执行窗口，按跳过策略关闭；不会补跑" : "触发已持久化，等待唯一任务创建与确认");
                        store.createScheduleOccurrenceIfAbsent(created);
                        Map<String, Object> saved = store.scheduleOccurrence(scheduleId, date.toString());
                        if (saved != null && "PENDING".equals(str(saved, "state"))) process(saved);
                    }
                }
            }
            completedThrough = date;
            scanned++;
            date = date.plusDays(1);
        }
        if (completedThrough != null && !completedThrough.toString().equals(lastScanned)) {
            revision.put("lastScannedLocalDate", completedThrough.toString()); revision.put("updatedAt", clock.instant().toString());
            store.put("scheduleRevision", revision);
        }
        return scanned;
    }

    private Map<String, Object> occurrence(Map<String, Object> revision, LocalDate date, Instant scheduledAt, String state, String message) {
        Instant recordedAt = clock.instant();
        return obj("id", id(), "schemaVersion", 1, "scheduleId", revision.get("scheduleId"), "scheduleVersion", revision.get("version"),
            "occurrenceKey", date.toString(), "localDate", date.toString(), "timezone", revision.get("timezone"), "localTime", revision.get("localTime"),
            "scheduledAt", scheduledAt.toString(), "taskTemplate", revision.get("task"), "state", state, "createdAt", recordedAt.toString(),
            "updatedAt", recordedAt.toString(), "message", message);
    }

    private void process(Map<String, Object> occurrence) {
        try {
            String occurrenceId = str(occurrence, "id");
            Map<String, Object> task = store.scheduledTask(occurrenceId);
            if (task == null) {
                String label = str(occurrence, "localDate");
                Map<String, Object> template = new LinkedHashMap<>(map(occurrence, "taskTemplate"));
                task = catalog.createScheduledTask(template, occurrenceId, label);
            }
            String taskId = str(task, "id");
            if ("DRAFT".equals(str(task, "state"))) engine.action(taskId, "approve", obj());
            Map<String, Object> currentTask = store.get("task", taskId);
            occurrence.put("taskId", taskId); occurrence.put("state", "TRIGGERED"); occurrence.put("triggeredAt", clock.instant().toString());
            occurrence.put("updatedAt", clock.instant().toString()); occurrence.put("message", "为本次日期创建了独立任务与会话，并按已确认计划排队"); occurrence.put("taskState", currentTask.get("state"));
            store.put("scheduleOccurrence", occurrence);
        } catch (RuntimeException exception) {
            String raw = exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
            String message = catalog.redact(raw);
            if (message == null || message.isBlank()) message = exception.getClass().getSimpleName();
            occurrence.put("state", "NEEDS_ATTENTION"); occurrence.put("updatedAt", clock.instant().toString());
            occurrence.put("message", message.length() <= 1000 ? message : message.substring(0, 1000));
            store.put("scheduleOccurrence", occurrence);
        }
    }
}
