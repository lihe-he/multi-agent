package dev.agentworkbench.mcp;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.ApiException;
import dev.agentworkbench.ExecutionStore;
import dev.agentworkbench.WorkbenchCatalog;
import dev.agentworkbench.ResourceManager;
import org.springframework.beans.factory.annotation.Autowired;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.time.Duration;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;

/**
 * MCP 服务身份、定义版本与独立授权池；未知结果不自动重放。
 */
@Service
public class McpManager {
    private final ExecutionStore store;
    private final ObjectMapper mapper;
    private final WorkbenchCatalog catalog;
    private final ResourceManager resources;
    private final TransportFactory transportFactory;
    private final Map<String, ManagedTransport> transports = new ConcurrentHashMap<>();
    private volatile boolean observing = true;
    private final Map<String, String> tokens = new ConcurrentHashMap<>();
    private final Map<String, ReentrantLock> locks = new ConcurrentHashMap<>();

    public McpManager(ExecutionStore store, ObjectMapper mapper, WorkbenchCatalog catalog) { this(store, mapper, catalog, new ResourceManager()); }

    @Autowired
    public McpManager(ExecutionStore store, ObjectMapper mapper, WorkbenchCatalog catalog, ResourceManager resources) { this(store, mapper, catalog, resources, McpTransport::open); }

    McpManager(ExecutionStore store, ObjectMapper mapper, WorkbenchCatalog catalog, ResourceManager resources, TransportFactory transportFactory) {
        this.store = store; this.mapper = mapper; this.catalog = catalog; this.resources = resources; this.transportFactory = transportFactory;
    }

    @PostConstruct
    void recover() {
        for (Map<String, Object> operation : store.list("mcpOperation")) {
            if (Set.of("PREPARED", "DISPATCHED").contains(str(operation, "state"))
                    || ("ACKNOWLEDGED".equals(str(operation, "state")) && "UNCONFIRMED".equals(str(operation, "stopState")))) {
                if ("SENT".equals(str(operation, "dispatchState"))) operation.put("dispatchState", "UNKNOWN");
                operation.put("state", "UNKNOWN"); operation.put("stopState", "UNCONFIRMED"); operation.put("canReconcile", true);
                operation.put("error", "核心退出前未完成操作或停止确认，禁止自动重放"); store.put("mcpOperation", operation);
            }
        }
    }

    @jakarta.annotation.PreDestroy
    void stopObservers() { observing = false; }

    public synchronized Map<String, Object> save(Map<String, Object> input) {
        String serverId = str(input, "id").isBlank() ? id() : str(input, "id");
        Map<String, Object> previous = store.find("mcpServer", serverId);
        String transport = required(input, "transport", 16).toUpperCase();
        if (!Set.of("STDIO", "HTTP").contains(transport)) { throw new ApiException(400, "MCP 传输应为 STDIO 或 HTTP"); }
        String url = str(input, "url").trim(), command = str(input, "command").trim();
        if ("HTTP".equals(transport)) { validateUrl(url); }
        else if (command.isBlank() || command.length() > 4096) { throw new ApiException(400, "stdio 需要可执行文件路径"); }
        List<String> args = strings(input.get("args")), projects = strings(input.get("projectIds")), tools = strings(input.get("allowedTools"));
        if (args.size() > 100 || projects.size() > 100 || tools.size() > 1000) { throw new ApiException(400, "MCP 配置超出上限"); }
        for (String project : projects) { store.get("project", project); }
        String identity = hash(obj("id", serverId, "transport", transport, "url", url, "command", command, "args", args));
        Map<String, Object> server = obj("id", serverId, "name", required(input, "name", 160), "transport", transport, "url", url, "command", command, "args", args,
            "projectIds", projects, "allowedTools", tools, "enabled", bool(input, "enabled", true), "identity", identity, "version", previous == null ? 1 : num(previous, "version", 1) + 1,
            "updatedAt", now(), "capabilityBoundary", "用户显式授权的工具清单；远端只读和幂等声明未经平台证明，不用于自动放宽权限");
        if (input.containsKey("token")) {
            String token = str(input, "token"); if (token.isBlank()) { tokens.remove(serverId); } else { tokens.put(serverId, token); }
        } else if (previous != null && !identity.equals(str(previous, "identity"))) { tokens.remove(serverId); server.put("credentialNotice", "服务身份已变更，请重新提供凭据"); }
        store.put("mcpServer", server); return visible(server);
    }

    public List<Map<String, Object>> list() { return store.list("mcpServer").stream().map(this::visible).toList(); }

    public Map<String, Object> probe(String serverId) {
        try (var serviceLease = acquireServer(serverId, () -> false)) {
            requireServerResolved(serverId);
            Map<String, Object> server = store.get("mcpServer", serverId);
            if (!bool(server, "enabled", false)) { throw new ApiException(409, "MCP 服务已停用"); }
            try (var transport = openTransport(server, () -> false, Duration.ofSeconds(30), null)) {
                Map<String, Object> discovery = discover(server, transport);
                persistDiscovery(server, discovery);
                return discovery;
            } catch (Exception exception) {
                String message = redact(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                synchronized (this) {
                    Map<String, Object> current = store.get("mcpServer", serverId);
                    if (num(current, "version", -1) == num(server, "version", -2)) { current.put("health", "UNAVAILABLE"); current.put("error", message); current.put("probedAt", now()); store.put("mcpServer", current); }
                }
                return obj("available", false, "identity", server.get("identity"), "message", message, "tools", List.of());
            }
        }
    }

    /**
     * 交互工具调用先核验最新定义；请求编号在同一服务内去重。
     */
    public Map<String, Object> call(String serverId, Map<String, Object> input) {
        try (var serviceLease = acquireServer(serverId, () -> false)) {
            requireServerResolved(serverId);
            Map<String, Object> server = store.get("mcpServer", serverId);
            String projectId = required(input, "projectId", 100), tool = required(input, "name", 200), requestId = required(input, "requestId", 100);
            if (!bool(server, "enabled", false) || !strings(server.get("projectIds")).contains(projectId) || !strings(server.get("allowedTools")).contains(tool)) { throw new ApiException(403, "MCP 服务、项目或工具未获得显式授权"); }
            store.get("project", projectId);
            String taskId = str(input, "taskId");
            if (!taskId.isBlank() && !projectId.equals(str(store.get("task", taskId), "projectId"))) { throw new ApiException(403, "任务不属于授权项目"); }
            if (!taskId.isBlank()) { throw new ApiException(403, "任务内 MCP 只能通过固定授权动作入口派发"); }
            String expectedVersion = required(input, "definitionVersion", 128);
            Map<String, Object> arguments = map(input, "arguments");
            String operationId = hash(obj("server", serverId, "request", requestId));
            Map<String, Object> existing = store.find("mcpOperation", operationId);
            if (existing != null) {
                if (!projectId.equals(str(existing, "projectId")) || !taskId.equals(str(existing, "taskId")) || !tool.equals(str(existing, "tool")) || !hash(arguments).equals(str(existing, "argumentHash")) || !str(server, "identity").equals(str(existing, "identity")) || !expectedVersion.equals(str(existing, "definitionVersion"))) { throw new ApiException(409, "请求编号已用于不同参数、项目或服务版本，不能复用"); }
                if (!"ACKNOWLEDGED".equals(str(existing, "state"))) { throw new ApiException(409, "相同请求已有未确认操作，请人工核对，不能重放"); }
                return obj("operationId", operationId, "state", "ACKNOWLEDGED", "deduplicated", true, "result", existing.get("result"));
            }
            Map<String, Object> operation = obj("id", operationId, "requestId", requestId, "serverId", serverId, "identity", server.get("identity"), "projectId", projectId, "taskId", taskId,
                "tool", tool, "definitionVersion", expectedVersion, "argumentKeys", new ArrayList<>(arguments.keySet()), "argumentHash", hash(arguments),
                "state", "PREPARED", "dispatchState", "NOT_SENT", "stopState", "UNCONFIRMED", "createdAt", now());
            boolean sent = false;
            try (var transport = openTransport(server, () -> false, Duration.ofSeconds(30), operation)) {
                Map<String, Object> discovery = discover(server, transport);
                persistDiscovery(server, discovery);
                if (!expectedVersion.equals(str(discovery, "definitionVersion"))) { throw new ApiException(409, "MCP 工具定义已变化，请重新检查工具与授权后使用新版本"); }
                if (objects(discovery.get("tools")).stream().noneMatch(item -> tool.equals(str(item, "name")))) { throw new ApiException(404, "MCP 服务未提供该工具"); }
                Map<String, Object> live = store.get("mcpServer", serverId);
                if (!bool(live, "enabled", false) || num(live, "version", -1) != num(server, "version", -2) || !str(server, "identity").equals(str(live, "identity")) || !strings(live.get("projectIds")).contains(projectId) || !strings(live.get("allowedTools")).contains(tool)) { throw new ApiException(403, "MCP 授权或服务身份在派发前变化，已停止调用"); }
                store.put("mcpOperation", operation);
                operation.put("state", "DISPATCHED"); operation.put("dispatchState", "SENT"); store.put("mcpOperation", operation); sent = true;
                Map<String, Object> result = transport.request("tools/call", obj("name", tool, "arguments", arguments));
                Map<String, Object> safeResult = mapper.readValue(redact(mapper.writeValueAsString(result)), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                operation.put("state", "ACKNOWLEDGED"); operation.put("dispatchState", "ACKNOWLEDGED"); operation.put("result", safeResult); operation.put("completedAt", now()); store.put("mcpOperation", operation);
                store.event(taskId, "MCP_RESULT", serverId + "/" + tool + " 已收到服务回执；不把声明只读当作强隔离保证");
                return obj("operationId", operationId, "state", "ACKNOWLEDGED", "result", safeResult, "definitionVersion", expectedVersion);
            } catch (ApiException exception) {
                if (!localStopConfirmed(serverId)) return unknownMcp(taskId, operation, exception.getMessage());
                throw exception;
            }
            catch (Exception exception) {
                String message = redact(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                if (sent || !localStopConfirmed(serverId)) return unknownMcp(taskId, operation, message);
                throw new ApiException(502, "MCP 连接或能力核验失败，工具尚未派发：" + message);
            }
        }
    }

    public Map<String, Object> invokeFixedTool(String taskId, String stageKind, String alias, BooleanSupplier cancelled) {
        return invokeFixedTool(taskId, stageKind, alias, Map.of(), cancelled);
    }

    public Map<String, Object> invokeFixedTool(String taskId, String stageKind, String alias, String agentRunId, String requestId, String toolCallId, BooleanSupplier cancelled) {
        return invokeFixedTool(taskId, stageKind, alias, store.agentToolSource(taskId, agentRunId, requestId, toolCallId), cancelled);
    }

    private Map<String, Object> invokeFixedTool(String taskId, String stageKind, String alias, Map<String, Object> source, BooleanSupplier cancelled) {
        Map<String, Object> task = store.get("task", taskId);
        if (!str(task, "teamId").isBlank() && source.isEmpty()) throw new ApiException(403, "团队 MCP 动作需要实际执行者和原请求来源");
        Map<String, Object> authorization = map(task, "mcpAuthorization");
        Map<String, Object> binding = objects(authorization.get("bindings")).stream()
            .filter(item -> alias.equals(str(item, "alias"))).findFirst().orElseThrow(() -> new ApiException(403, "MCP 固定动作未获此任务授权"));
        if (!strings(binding.get("allowedStageKinds")).contains(stageKind) || !Set.of("IMPLEMENT", "REPAIR").contains(stageKind)) { throw new ApiException(403, "当前阶段不能调用 MCP 固定动作"); }
        McpTaskPolicy.assertCurrent(store, task);
        if (cancelled.getAsBoolean()) return obj("state", "CANCELLED", "error", "任务已请求停止，MCP 动作未派发");
        String serverId = str(binding, "serverId"), actionKey = str(binding, "actionKey");
        String operationId = hash(obj("taskId", taskId, "actionKey", actionKey));
        try (var serviceLease = acquireServer(serverId, cancelled)) {
            task = store.get("task", taskId);
            if (!source.isEmpty()) store.agentToolSource(taskId, str(source, "agentRunId"), str(source, "modelRequestId"), str(source, "toolCallId"));
            McpTaskPolicy.assertCurrent(store, task);
            Map<String, Object> existing = store.find("mcpOperation", operationId);
            if (existing != null) {
                if ("ACKNOWLEDGED".equals(str(existing, "state"))) return obj("state", "ACKNOWLEDGED", "deduplicated", true, "result", existing.get("result"), "operationId", existing.get("id"), "sourceAgentRunId", existing.getOrDefault("agentRunId", ""), "sourceRequestId", existing.getOrDefault("modelRequestId", ""));
                if ("RECONCILED".equals(str(existing, "state")) && "NOT_SENT".equals(str(existing, "dispatchState"))) return obj("state", "REJECTED", "error", existing.get("error"), "operationId", operationId);
                return obj("state", "UNKNOWN", "error", "此固定动作已有记录，不能自动重放；请先核对记录");
            }
            requireServerResolved(serverId);
            Map<String, Object> server = store.get("mcpServer", serverId);
            int timeout = num(binding, "timeoutMs", 30000), authVersion = num(authorization, "version", 1);
            Map<String, Object> operation = obj("id", operationId, "requestId", operationId, "taskId", taskId, "projectId", task.get("projectId"),
                "stage", stageKind, "serverId", serverId, "serverName", binding.get("serverName"), "identity", server.get("identity"),
                "tool", binding.get("name"), "actionKey", actionKey, "authorizationVersion", authVersion, "definitionVersion", binding.get("definitionVersion"),
                "argumentKeys", new ArrayList<>(map(binding, "fixedArguments").keySet()), "argumentHash", hash(binding.get("fixedArguments")),
                "inputSummary", "固定参数键：" + String.join(", ", map(binding, "fixedArguments").keySet()),
                "state", "PREPARED", "dispatchState", "NOT_SENT", "stopState", "UNCONFIRMED", "canReconcile", false, "createdAt", now());
            operation.putAll(source);
            store.beginMcpOperation(taskId, operation, authVersion, timeout);
            boolean sent = false;
            try (var transport = openTransport(server, cancelled, Duration.ofMillis(timeout), operation)) {
                Map<String, Object> discovery = discover(server, transport);
                if (!str(binding, "definitionVersion").equals(str(discovery, "definitionVersion"))) throw new ApiException(409, "MCP 工具定义已变化，派发已阻止");
                Map<String, Object> latestTask = store.get("task", taskId);
                McpTaskPolicy.assertCurrent(store, latestTask);
                if (cancelled.getAsBoolean()) throw new CancellationException("MCP 动作派发前收到停止请求");
                if (!source.isEmpty()) store.agentToolSource(taskId, str(source, "agentRunId"), str(source, "modelRequestId"), str(source, "toolCallId"));
                Map<String, Object> live = store.get("mcpServer", serverId);
                if (!str(binding, "identity").equals(str(live, "identity")) || num(binding, "configVersion", -1) != num(live, "version", -2)) throw new ApiException(409, "MCP 服务配置变化，派发已阻止");
                operation.put("state", "DISPATCHED"); operation.put("dispatchState", "SENT"); store.put("mcpOperation", operation); sent = true;
                Map<String, Object> result = transport.request("tools/call", obj("name", binding.get("name"), "arguments", binding.get("fixedArguments")));
                Map<String, Object> safeResult = mapper.readValue(redact(mapper.writeValueAsString(result)), new com.fasterxml.jackson.core.type.TypeReference<>() { });
                operation.put("state", "ACKNOWLEDGED"); operation.put("dispatchState", "ACKNOWLEDGED"); operation.put("result", safeResult); operation.put("completedAt", now()); store.put("mcpOperation", operation);
                store.event(taskId, "MCP_RESULT", serverId + "/" + binding.get("name") + " 已收到固定动作回执");
                return obj("state", "ACKNOWLEDGED", "result", safeResult, "operationId", operationId);
            } catch (CancellationException exception) {
                if (sent || !localStopConfirmed(serverId)) return unknownMcp(taskId, operation, exception.getMessage());
                operation.put("stopState", "CONFIRMED");
                operation.put("state", "RECONCILED"); operation.put("dispatchState", "NOT_SENT"); operation.put("error", "停止请求在派发前到达"); store.put("mcpOperation", operation);
                return obj("state", "CANCELLED", "error", "MCP 动作在派发前停止");
            } catch (Exception exception) {
                String message = redact(exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage());
                if (sent || !localStopConfirmed(serverId)) return unknownMcp(taskId, operation, message);
                operation.put("stopState", "CONFIRMED");
                operation.put("state", "RECONCILED"); operation.put("dispatchState", "NOT_SENT"); operation.put("error", message); store.put("mcpOperation", operation);
                return obj("state", "REJECTED", "error", message, "operationId", operationId);
            }
        } catch (CancellationException exception) {
            return obj("state", "CANCELLED", "error", "等待 MCP 服务期间停止，动作未登记或派发");
        }
    }

    /**
     * 服务串行等待响应根取消；拿到锁后复验账本，避免沿用等待前的状态。
     */
    private ServiceLease acquireServer(String serverId, BooleanSupplier cancelled) {
        ReentrantLock lock = locks.computeIfAbsent(serverId, ignored -> new ReentrantLock(true));
        try {
            while (true) {
                if (cancelled.getAsBoolean()) { throw new CancellationException("MCP 服务等待已停止"); }
                if (lock.tryLock(100, TimeUnit.MILLISECONDS)) {
                    if (cancelled.getAsBoolean()) { lock.unlock(); throw new CancellationException("MCP 服务等待已停止"); }
                    return new ServiceLease(lock);
                }
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt(); throw new CancellationException("MCP 服务等待被中断");
        }
    }

    private record ServiceLease(ReentrantLock lock) implements AutoCloseable {
        @Override
        public void close() { lock.unlock(); }
    }

    private Map<String, Object> unknownMcp(String taskId, Map<String, Object> operation, String reason) {
        boolean stopped = localStopConfirmed(str(operation, "serverId"));
        Map<String, Object> current;
        synchronized (store) {
            current = store.find("mcpOperation", str(operation, "id"));
            if (current == null) current = new LinkedHashMap<>(operation);
            stopped = stopped || current.containsKey("localStoppedAt");
            current.put("state", "UNKNOWN");
            if ("SENT".equals(str(current, "dispatchState"))) current.put("dispatchState", "UNKNOWN");
            current.put("stopState", stopped ? "CONFIRMED" : "UNCONFIRMED"); current.put("canReconcile", stopped);
            current.put("error", redact(reason == null ? "结果未知" : reason)); current.put("updatedAt", now()); store.put("mcpOperation", current);
        }
        store.event(taskId, "MCP_UNKNOWN", str(operation, "serverId") + "/" + str(operation, "tool") + " 未确认；禁止重放");
        return obj("state", "UNKNOWN", "error", current.get("error"), "operationId", current.get("id"));
    }

    @FunctionalInterface
    interface TransportFactory {
        McpTransport open(Map<String, Object> server, String token, ObjectMapper mapper, BooleanSupplier cancelled, Duration timeout) throws Exception;
    }

    private ManagedTransport openTransport(Map<String, Object> server, BooleanSupplier cancelled, Duration timeout, Map<String, Object> operation) throws Exception {
        var lease = resources.acquire(resourceRequest(server), cancelled);
        try {
            var managed = new ManagedTransport(str(server, "id"), transportFactory.open(server, tokens.getOrDefault(str(server, "id"), ""), mapper, cancelled, timeout), lease, operation);
            transports.put(str(server, "id"), managed);
            return managed;
        } catch (Exception exception) { lease.close(); throw exception; }
    }

    /**
     * 停止确认前保留工具和进程容量；已收到回执也不能绕过残留进程检查。
     */
    private final class ManagedTransport implements McpTransport {
        private final String serverId;
        private final McpTransport delegate;
        private final ResourceManager.Lease lease;
        private final String operationId;
        private boolean closed;
        private boolean released;

        private ManagedTransport(String serverId, McpTransport delegate, ResourceManager.Lease lease, Map<String, Object> operation) {
            this.serverId = serverId; this.delegate = delegate; this.lease = lease; this.operationId = operation == null ? "" : str(operation, "id");
        }

        @Override public Map<String, Object> request(String method, Map<String, Object> params) throws Exception { return delegate.request(method, params); }
        @Override public void notify(String method, Map<String, Object> params) throws Exception { delegate.notify(method, params); }
        @Override public boolean definitionsChanged() { return delegate.definitionsChanged(); }
        @Override public synchronized boolean stopConfirmed() { return closed && delegate.stopConfirmed(); }

        private synchronized boolean releaseIfStopped() {
            if (released) return true;
            if (!closed || !delegate.stopConfirmed()) return false;
            synchronized (store) {
                Map<String, Object> current = operationId.isBlank() ? null : store.find("mcpOperation", operationId);
                if (current != null) {
                    current.put("stopState", "CONFIRMED"); current.put("localStoppedAt", now());
                    if ("UNKNOWN".equals(str(current, "state"))) current.put("canReconcile", true);
                    store.put("mcpOperation", current);
                }
            }
            lease.close(); transports.remove(serverId, this);
            released = true;
            return true;
        }

        @Override
        public synchronized void close() {
            try { delegate.close(); }
            finally {
                closed = true;
                if (!releaseIfStopped()) {
                    Thread.startVirtualThread(() -> {
                        while (observing && transports.get(serverId) == this) {
                            try { Thread.sleep(100); if (releaseIfStopped()) return; }
                            catch (InterruptedException exception) { Thread.currentThread().interrupt(); return; }
                        }
                    });
                    throw new McpTransport.McpFailure("MCP 本地进程或传输未确认停止，容量继续保留；请核对实际进程", true);
                }
            }
        }
    }

    private boolean localStopConfirmed(String serverId) {
        ManagedTransport transport = transports.get(serverId);
        return transport == null || transport.releaseIfStopped();
    }

    public void requireTaskStopped(String taskId) {
        for (Map<String, Object> operation : store.list("mcpOperation")) {
            if (taskId.equals(str(operation, "taskId")) && !localStopConfirmed(str(operation, "serverId"))) {
                throw new ApiException(409, "MCP 本地进程或传输仍未停止，不能记录人工核对");
            }
        }
    }

    public List<Map<String, Object>> operations(String serverId) { store.get("mcpServer", serverId); return store.list("mcpOperation").stream().filter(operation -> serverId.equals(str(operation, "serverId"))).toList(); }

    synchronized void persistDiscovery(Map<String, Object> observed, Map<String, Object> discovery) {
        Map<String, Object> current = store.get("mcpServer", str(observed, "id"));
        if (!bool(current, "enabled", false) || num(current, "version", -1) != num(observed, "version", -2)) { throw new ApiException(409, "MCP 配置在能力发现期间变化，请重新探测"); }
        current.put("discovery", discovery); current.put("health", "AVAILABLE"); current.put("probedAt", now()); current.remove("error"); store.put("mcpServer", current);
    }

    Map<String, Object> discover(Map<String, Object> server, McpTransport transport) throws Exception {
        Map<String, Object> initialized = transport.request("initialize", obj("protocolVersion", "2025-06-18", "capabilities", obj(), "clientInfo", obj("name", "agent-workbench", "version", "0.2.0-alpha.8")));
        String protocol = str(initialized, "protocolVersion");
        if (!Set.of("2025-06-18", "2025-03-26").contains(protocol)) { throw new ApiException(409, "此客户端尚未验证服务协商的 MCP 版本：" + protocol); }
        transport.notify("notifications/initialized", obj());
        List<Map<String, Object>> tools = new ArrayList<>();
        Map<String, Object> capabilities = map(initialized, "capabilities");
        if (capabilities.containsKey("tools")) {
            String cursor = "";
            for (int page = 0; page < 20; page++) {
                Map<String, Object> response = transport.request("tools/list", cursor.isBlank() ? obj() : obj("cursor", cursor));
                tools.addAll(objects(response.get("tools"))); cursor = str(response, "nextCursor");
                if (tools.size() > 1000) { throw new ApiException(409, "MCP 工具数量超过 1000 项边界"); }
                if (cursor.isBlank()) { break; }
                if (page == 19) { throw new ApiException(409, "MCP 工具分页未完成，不声明完整发现"); }
            }
        }
        tools.sort(java.util.Comparator.comparing(item -> str(item, "name")));
        if (tools.stream().map(item -> str(item, "name")).distinct().count() != tools.size()) { throw new ApiException(409, "同一服务返回重复工具名，拒绝模糊绑定"); }
        String definitionVersion = hash(obj("identity", server.get("identity"), "protocol", protocol, "serverInfo", initialized.get("serverInfo"), "tools", tools));
        List<Map<String, Object>> bound = tools.stream().map(tool -> { Map<String, Object> item = new LinkedHashMap<>(tool); item.put("binding", str(server, "id") + "/" + str(tool, "name") + "@" + definitionVersion); item.put("authorized", strings(server.get("allowedTools")).contains(str(tool, "name"))); return item; }).toList();
        return obj("available", true, "identity", server.get("identity"), "protocolVersion", protocol, "definitionVersion", definitionVersion, "serverInfo", initialized.getOrDefault("serverInfo", obj()), "capabilities", capabilities, "tools", bound, "message", "真实完成初始化与工具发现；未调用业务工具");
    }

    private Map<String, Object> visible(Map<String, Object> server) { Map<String, Object> value = new LinkedHashMap<>(server); value.put("hasToken", tokens.containsKey(str(server, "id"))); value.put("credentialStorage", "SESSION_ONLY"); return value; }

    private void validateUrl(String url) {
        try {
            URI uri = URI.create(url);
            if (uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null || uri.getFragment() != null) { throw new IllegalArgumentException(); }
            if (!"https".equals(uri.getScheme()) && !("http".equals(uri.getScheme()) && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(uri.getHost()))) { throw new IllegalArgumentException(); }
        } catch (Exception exception) { throw new ApiException(400, "MCP 远程地址须使用 HTTPS，本机端点可使用 HTTP；不能在 URL 中携带凭据"); }
    }

    String redact(String value) { String text = catalog.redact(value); for (String token : tokens.values()) { if (!token.isBlank()) { text = text.replace(token, "[REDACTED]"); } } return text; }
    String token(String serverId) { return tokens.getOrDefault(serverId, ""); }
    static Map<String, Integer> resourceRequest(Map<String, Object> server) { Map<String, Integer> result = new LinkedHashMap<>(Map.of("tool", 1, "mcp:" + str(server, "id"), 1)); if ("STDIO".equals(str(server, "transport"))) { result.put("process", 1); } return result; }
    void requireServerResolved(String serverId) {
        if (!localStopConfirmed(serverId)) throw new ApiException(409, "MCP 本地进程或传输仍未停止，暂不开放新派发");
        if (store.list("mcpOperation").stream().anyMatch(operation -> serverId.equals(str(operation, "serverId")) && Set.of("PREPARED", "DISPATCHED", "UNKNOWN").contains(str(operation, "state")))) { throw new ApiException(409, "该 MCP 服务存在未核对动作，暂不开放新派发"); }
    }
    private String hash(Object value) { return McpTaskPolicy.hash(value); }
}
