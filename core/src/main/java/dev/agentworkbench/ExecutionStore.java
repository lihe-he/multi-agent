package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import static dev.agentworkbench.Json.*;

/**
 * 状态、操作意图和事件共用 SQLite 事务；写失败立即封闭派发。
 */
@Component
public class ExecutionStore {
    private final Connection connection;
    private final ObjectMapper mapper;
    private final Path directory;
    /**
     * 整个核心生命周期持有跨进程锁，防止第二实例恢复并接管相同任务目录。
     */
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private volatile boolean healthy = true;

    public ExecutionStore(ObjectMapper mapper, @Value("${workbench.data-dir:./data}") String dataDir) throws Exception {
        this.mapper = mapper;
        directory = Path.of(dataDir).toAbsolutePath().normalize();
        Files.createDirectories(directory);
        FileChannel channel = null;
        FileLock lock = null;
        Connection opened = null;
        try {
            channel = FileChannel.open(directory.resolve(".core.lock"), StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try { lock = channel.tryLock(); }
            catch (OverlappingFileLockException exception) { throw new IllegalStateException("核心数据目录已被另一个实例占用，请关闭旧核心后重试", exception); }
            if (lock == null) { throw new IllegalStateException("核心数据目录已被另一个实例占用，请关闭旧核心后重试"); }
            opened = DriverManager.getConnection("jdbc:sqlite:" + directory.resolve("workbench.sqlite"));
            try (var statement = opened.createStatement()) {
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                statement.execute("CREATE TABLE IF NOT EXISTS entities(kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(kind,id))");
                statement.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,type TEXT NOT NULL,message TEXT NOT NULL,created_at TEXT NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS events_task_id ON events(task_id,id)");
                statement.execute("PRAGMA user_version=1");
            }
            connection = opened; ownershipChannel = channel; ownershipLock = lock;
        } catch (Exception exception) {
            if (opened != null) { try { opened.close(); } catch (SQLException ignored) { } }
            if (lock != null) { try { lock.release(); } catch (java.io.IOException ignored) { } }
            if (channel != null) { try { channel.close(); } catch (java.io.IOException ignored) { } }
            throw exception;
        }
    }

    public Path directory() { return directory; }
    public boolean healthy() { return healthy; }

    public synchronized List<Map<String, Object>> list(String kind) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind=? ORDER BY rowid DESC")) {
            query.setString(1, kind);
            try (var rows = query.executeQuery()) {
                List<Map<String, Object>> result = new ArrayList<>();
                while (rows.next()) { result.add(decode(rows.getString(1))); }
                return result;
            }
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized Map<String, Object> find(String kind, String id) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind=? AND id=?")) {
            query.setString(1, kind);
            query.setString(2, id);
            try (var rows = query.executeQuery()) { return rows.next() ? decode(rows.getString(1)) : null; }
        } catch (Exception exception) { throw failure(exception); }
    }

    public Map<String, Object> get(String kind, String id) {
        Map<String, Object> result = find(kind, id);
        if (result == null) { throw new ApiException(404, "记录不存在：" + kind); }
        return result;
    }

    public synchronized Map<String, Object> put(String kind, Map<String, Object> entity) {
        transaction(() -> write(kind, entity));
        return entity;
    }

    public synchronized void delete(String kind, String id) {
        transaction(() -> {
            try (var statement = connection.prepareStatement("DELETE FROM entities WHERE kind=? AND id=?")) {
                statement.setString(1, kind); statement.setString(2, id); statement.executeUpdate();
            }
        });
    }

    /**
     * 更新任务及事件的原子边界，调用方不能持有过期任务覆盖新状态。
     */
    public synchronized Map<String, Object> editTask(String id, Consumer<Map<String, Object>> edit, String type, String message) {
        Map<String, Object> task = get("task", id);
        edit.accept(task);
        task.put("updatedAt", now());
        transaction(() -> {
            write("task", task);
            if (type != null) { appendEvent(id, type, message); }
        });
        return task;
    }

    public synchronized Map<String, Object> createTask(Map<String, Object> task) {
        transaction(() -> { write("task", task); appendEvent(str(task, "id"), "TASK_CREATED", "需求、完成约定和策略快照已保存，等待确认计划"); });
        return task;
    }

    /**
     * MCP 动作意图和保守预算承诺同时保存，恢复时不能遗漏未确认动作。
     */
    public synchronized void beginMcpOperation(String taskId, Map<String, Object> operation, int authorizationVersion, int timeoutMs) {
        Map<String, Object> task = get("task", taskId);
        if (num(map(task, "mcpAuthorization"), "version", 0) != authorizationVersion || !"RUNNING".equals(str(task, "state"))) { throw new ApiException(409, "任务授权或执行状态已变化"); }
        if (list("mcpOperation").stream().anyMatch(value -> taskId.equals(str(value, "taskId")) && Set.of("PREPARED", "DISPATCHED", "UNKNOWN").contains(str(value, "state")))) { throw new ApiException(409, "存在未核对 MCP 动作"); }
        if (find("mcpOperation", str(operation, "id")) != null) { throw new ApiException(409, "固定 MCP 动作已登记，不能重新派发"); }
        Map<String, Object> budget = map(task, "mcpBudget");
        int calls = num(budget, "usedCalls", 0), duration = num(budget, "committedDurationMs", 0);
        if (calls >= num(budget, "maxCalls", 8) || duration + timeoutMs > num(budget, "maxDurationMs", 240000)) { throw new ApiException(409, "根任务 MCP 次数或超时承诺预算不足"); }
        budget.put("usedCalls", calls + 1); budget.put("committedDurationMs", duration + timeoutMs); task.put("mcpBudget", budget);
        transaction(() -> { write("task", task); write("mcpOperation", operation); appendEvent(taskId, "MCP_PREPARED", "已保存固定动作意图与根工具预算承诺"); });
    }

    /**
     * 外部操作开始前先保存意图和任务位置。崩溃后只核对，不重放。
     */
    public synchronized Map<String, Object> beginOperation(String taskId, String stage, String kind, String target) {
        Map<String, Object> operation = obj("id", id(), "taskId", taskId, "stage", stage, "kind", kind,
            "target", target, "state", "PREPARED", "createdAt", now());
        Map<String, Object> task = get("task", taskId);
        task.put("activeOperation", operation.get("id"));
        task.put("updatedAt", now());
        transaction(() -> {
            write("operation", operation); write("task", task);
            appendEvent(taskId, "OPERATION_PREPARED", stage + "：已保存操作意图");
        });
        return operation;
    }

    public synchronized void markOperation(String operationId, String state, Map<String, Object> details) {
        Map<String, Object> operation = get("operation", operationId);
        operation.put("state", state); operation.put("updatedAt", now());
        operation.putAll(details);
        transaction(() -> write("operation", operation));
    }

    /**
     * 证据只允许追加，结果和任务状态在同一事务中保存。
     */
    public synchronized void recordEvidence(String taskId, String operationId, Map<String, Object> evidence) {
        Map<String, Object> task = get("task", taskId);
        List<Map<String, Object>> evidenceList = new ArrayList<>(objects(task.get("evidence")));
        evidenceList.add(evidence); task.put("evidence", evidenceList);
        task.remove("activeOperation"); task.put("updatedAt", now());
        Map<String, Object> operation = get("operation", operationId);
        operation.put("state", "UNKNOWN".equals(str(evidence, "status")) ? "UNKNOWN" : "ACKNOWLEDGED");
        operation.put("evidenceId", evidence.get("id")); operation.put("updatedAt", now());
        transaction(() -> {
            if (find("evidence", str(evidence, "id")) != null) { throw new IllegalStateException("证据不可覆盖"); }
            write("evidence", evidence); write("task", task); write("operation", operation);
            appendEvent(taskId, "EVIDENCE", str(evidence, "stage") + "：" + str(evidence, "status"));
        });
    }

    public synchronized void event(String taskId, String type, String message) {
        transaction(() -> appendEvent(taskId, type, message));
    }

    public synchronized Map<String, Object> events(String taskId, long after, int requestedLimit) {
        int limit = Math.max(1, Math.min(200, requestedLimit));
        boolean all = taskId == null;
        String sql = "SELECT id,task_id,type,message,created_at FROM events WHERE id>?" + (all ? "" : " AND task_id=?") + " ORDER BY id LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setLong(1, Math.max(0, after));
            if (!all) { query.setString(2, taskId); }
            query.setInt(all ? 2 : 3, limit + 1);
            List<Map<String, Object>> result = new ArrayList<>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    result.add(obj("id", rows.getLong(1), "taskId", rows.getString(2), "type", rows.getString(3), "message", rows.getString(4), "createdAt", rows.getString(5)));
                }
            }
            boolean hasMore = result.size() > limit;
            if (hasMore) { result.removeLast(); }
            long cursor = result.isEmpty() ? after : ((Number) result.getLast().get("id")).longValue();
            return obj("events", result, "cursor", cursor, "hasMore", hasMore);
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized long latestEvent() {
        try (var statement = connection.createStatement(); var result = statement.executeQuery("SELECT COALESCE(MAX(id),0) FROM events")) {
            return result.next() ? result.getLong(1) : 0;
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized Path backup() {
        Path target = directory.resolve("backup-" + System.currentTimeMillis() + ".sqlite");
        try (var statement = connection.prepareStatement("VACUUM INTO ?")) {
            statement.setString(1, target.toString()); statement.execute(); return target;
        } catch (Exception exception) { throw failure(exception); }
    }

    private Map<String, Object> decode(String text) throws Exception { return mapper.readValue(text, new TypeReference<>() { }); }

    private void write(String kind, Map<String, Object> entity) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO entities(kind,id,body) VALUES(?,?,?) ON CONFLICT(kind,id) DO UPDATE SET body=excluded.body")) {
            statement.setString(1, kind); statement.setString(2, str(entity, "id")); statement.setString(3, mapper.writeValueAsString(entity)); statement.executeUpdate();
        }
    }

    private void appendEvent(String taskId, String type, String message) throws SQLException {
        try (var statement = connection.prepareStatement("INSERT INTO events(task_id,type,message,created_at) VALUES(?,?,?,?)")) {
            statement.setString(1, taskId); statement.setString(2, type); statement.setString(3, message == null ? "" : message); statement.setString(4, now()); statement.executeUpdate();
        }
    }

    private void transaction(SqlAction action) {
        if (!healthy) { throw new ApiException(503, "持久存储不可用，自动执行已停止"); }
        try {
            connection.setAutoCommit(false); action.run(); connection.commit();
        } catch (Exception exception) {
            try { connection.rollback(); } catch (SQLException ignored) { }
            throw failure(exception);
        } finally {
            try { connection.setAutoCommit(true); } catch (SQLException exception) { healthy = false; }
        }
    }

    private ApiException failure(Exception exception) {
        healthy = false;
        return new ApiException(503, "持久存储失败，已封闭新派发：" + exception.getClass().getSimpleName());
    }

    @PreDestroy
    public synchronized void close() throws Exception {
        try { connection.close(); }
        finally {
            try { if (ownershipLock.isValid()) { ownershipLock.release(); } }
            finally { ownershipChannel.close(); }
        }
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws Exception; }
}
