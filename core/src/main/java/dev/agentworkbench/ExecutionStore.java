package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.sqlite.Function;

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
            Function.create(opened, "session_search_fold", new Function() {
                @Override
                protected void xFunc() throws SQLException { result(foldSearch(value_text(0))); }
            }, 1, Function.FLAG_DETERMINISTIC);
            try (var statement = opened.createStatement()) {
                int schemaVersion;
                try (var version = statement.executeQuery("PRAGMA user_version")) {
                    schemaVersion = version.next() ? version.getInt(1) : 0;
                    if (schemaVersion > 3) throw new IllegalStateException("数据版本高于当前核心支持范围，请使用兼容版本");
                }
                statement.execute("PRAGMA journal_mode=WAL");
                statement.execute("PRAGMA synchronous=FULL");
                statement.execute("PRAGMA busy_timeout=5000");
                opened.setAutoCommit(false);
                statement.execute("CREATE TABLE IF NOT EXISTS entities(kind TEXT NOT NULL,id TEXT NOT NULL,body TEXT NOT NULL,PRIMARY KEY(kind,id))");
                statement.execute("CREATE TABLE IF NOT EXISTS events(id INTEGER PRIMARY KEY AUTOINCREMENT,task_id TEXT NOT NULL,type TEXT NOT NULL,message TEXT NOT NULL,created_at TEXT NOT NULL)");
                statement.execute("CREATE INDEX IF NOT EXISTS events_task_id ON events(task_id,id)");
                statement.execute("CREATE TABLE IF NOT EXISTS session_messages(sequence INTEGER PRIMARY KEY AUTOINCREMENT,id TEXT NOT NULL UNIQUE,session_id TEXT NOT NULL,request_id TEXT NOT NULL,body TEXT NOT NULL,UNIQUE(session_id,request_id))");
                statement.execute("CREATE INDEX IF NOT EXISTS session_messages_cursor ON session_messages(session_id,sequence)");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_session_project ON entities(kind,json_extract(body,'$.projectId'))");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_agent_run_task ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.stage')) WHERE kind='agentRun'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_model_request_run ON entities(json_extract(body,'$.agentRunId'),json_extract(body,'$.state')) WHERE kind='modelRequest'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_model_request_task ON entities(json_extract(body,'$.taskId')) WHERE kind='modelRequest'");
                boolean rebuildSearch;
                try (var tables = statement.executeQuery("SELECT 1 FROM sqlite_master WHERE type='table' AND name='session_search'")) { rebuildSearch = schemaVersion < 3 || !tables.next(); }
                statement.execute("CREATE VIRTUAL TABLE IF NOT EXISTS session_search USING fts5(project_id UNINDEXED,session_id UNINDEXED,message_id UNINDEXED,sequence UNINDEXED,content,tokenize='trigram case_sensitive 1')");
                statement.execute("CREATE TRIGGER IF NOT EXISTS session_search_insert AFTER INSERT ON session_messages BEGIN INSERT INTO session_search(rowid,project_id,session_id,message_id,sequence,content) VALUES(new.sequence,json_extract(new.body,'$.projectId'),new.session_id,new.id,new.sequence,session_search_fold(json_extract(new.body,'$.content'))); END");
                statement.execute("CREATE TRIGGER IF NOT EXISTS session_search_delete AFTER DELETE ON session_messages BEGIN DELETE FROM session_search WHERE rowid=old.sequence; END");
                statement.execute("CREATE TRIGGER IF NOT EXISTS session_search_update AFTER UPDATE ON session_messages BEGIN DELETE FROM session_search WHERE rowid=old.sequence; INSERT INTO session_search(rowid,project_id,session_id,message_id,sequence,content) VALUES(new.sequence,json_extract(new.body,'$.projectId'),new.session_id,new.id,new.sequence,session_search_fold(json_extract(new.body,'$.content'))); END");
                if (rebuildSearch) {
                    statement.execute("DELETE FROM session_search");
                    statement.execute("INSERT INTO session_search(rowid,project_id,session_id,message_id,sequence,content) SELECT sequence,json_extract(body,'$.projectId'),session_id,id,sequence,session_search_fold(json_extract(body,'$.content')) FROM session_messages");
                }
                statement.execute("PRAGMA user_version=3");
                opened.commit(); opened.setAutoCommit(true);
            }
            connection = opened; ownershipChannel = channel; ownershipLock = lock;
        } catch (Exception exception) {
            if (opened != null) { try { if (!opened.getAutoCommit()) opened.rollback(); } catch (SQLException ignored) { } }
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
        Map<String, Object> session = null;
        if (!str(task, "sessionId").isBlank()) {
            session = get("session", str(task, "sessionId"));
            if (!str(task, "projectId").equals(str(session, "projectId"))) throw new ApiException(403, "会话不属于任务项目");
        } else if (!str(task, "projectId").isBlank() && task.containsKey("title") && task.containsKey("goal")) {
            session = obj("id", id(), "projectId", task.get("projectId"), "title", task.get("title"), "version", 1,
                "isOpen", true, "createdAt", now(), "updatedAt", now());
            task.put("sessionId", session.get("id"));
        }
        Map<String, Object> linkedSession = session;
        transaction(() -> {
            write("task", task); appendEvent(str(task, "id"), "TASK_CREATED", "需求、完成约定和策略快照已保存，等待确认计划");
            if (linkedSession != null) {
                write("session", linkedSession);
                writeSessionMessage(linkedSession, "task:" + str(task, "id"), "TASK_GOAL", str(task, "goal"), str(task, "id"));
            }
        });
        return task;
    }

    public synchronized Map<String, Object> createSession(String projectId, String title) {
        get("project", projectId);
        Map<String, Object> session = obj("id", id(), "projectId", projectId, "title", title, "version", 1,
            "isOpen", true, "createdAt", now(), "updatedAt", now());
        transaction(() -> write("session", session));
        return session;
    }

    public synchronized Map<String, Object> editSession(String projectId, String sessionId, Map<String, Object> input) {
        Map<String, Object> session = session(projectId, sessionId);
        if (num(input, "expectedVersion", -1) != num(session, "version", 1)) throw new ApiException(409, "会话已变化，请刷新后重试");
        if (input.containsKey("title")) session.put("title", required(input, "title", 180));
        if (input.containsKey("isOpen")) {
            if (!(input.get("isOpen") instanceof Boolean)) throw new ApiException(400, "isOpen 必须为布尔值");
            session.put("isOpen", input.get("isOpen"));
        }
        session.put("version", num(session, "version", 1) + 1); session.put("updatedAt", now());
        transaction(() -> write("session", session));
        return session;
    }

    public synchronized Map<String, Object> session(String projectId, String sessionId) {
        get("project", projectId);
        Map<String, Object> session = get("session", sessionId);
        if (!projectId.equals(str(session, "projectId"))) throw new ApiException(404, "当前项目没有此会话");
        return session;
    }

    /**
     * 会话历史按稳定行游标分页，新会话插入不会造成旧页重复或遗漏。
     */
    public synchronized Map<String, Object> sessions(String projectId, long before, int requestedLimit, boolean openOnly) {
        get("project", projectId);
        int limit = Math.max(1, Math.min(100, requestedLimit));
        String sql = "SELECT rowid,body FROM entities WHERE kind='session' AND json_extract(body,'$.projectId')=? AND (?=0 OR rowid<?)"
            + (openOnly ? " AND json_extract(body,'$.isOpen')=1" : "") + " ORDER BY rowid DESC LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, projectId); query.setLong(2, Math.max(0, before)); query.setLong(3, Math.max(0, before)); query.setInt(4, limit + 1);
            List<Map<String, Object>> result = new ArrayList<>(); List<Long> cursors = new ArrayList<>();
            try (var rows = query.executeQuery()) { while (rows.next()) { cursors.add(rows.getLong(1)); result.add(decode(rows.getString(2))); } }
            boolean hasMore = result.size() > limit;
            if (hasMore) { result.removeLast(); cursors.removeLast(); }
            return obj("sessions", result, "cursor", cursors.isEmpty() ? before : cursors.getLast(), "hasMore", hasMore);
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized Map<String, Object> appendSessionNote(String projectId, String sessionId, String requestId, String content) {
        Map<String, Object> session = session(projectId, sessionId);
        try (var query = connection.prepareStatement("SELECT body FROM session_messages WHERE session_id=? AND request_id=?")) {
            query.setString(1, sessionId); query.setString(2, requestId);
            try (var rows = query.executeQuery()) {
                if (rows.next()) {
                    Map<String, Object> existing = decode(rows.getString(1));
                    if (!"NOTE".equals(str(existing, "kind")) || !content.equals(str(existing, "content"))) throw new ApiException(409, "记录编号已用于其他内容");
                    return existing;
                }
            }
        } catch (ApiException exception) { throw exception; }
        catch (Exception exception) { throw failure(exception); }
        Map<String, Object> message = obj("id", id(), "sessionId", sessionId, "projectId", projectId, "requestId", requestId,
            "role", "USER", "kind", "NOTE", "content", content, "createdAt", now());
        transaction(() -> insertSessionMessage(session, message));
        return message;
    }

    private void writeSessionMessage(Map<String, Object> session, String requestId, String kind, String content, String taskId) throws Exception {
        insertSessionMessage(session, obj("id", id(), "sessionId", session.get("id"), "projectId", session.get("projectId"), "requestId", requestId,
            "role", "USER", "kind", kind, "content", content, "taskId", taskId, "createdAt", now()));
    }

    private void insertSessionMessage(Map<String, Object> session, Map<String, Object> message) throws Exception {
        try (var statement = connection.prepareStatement("INSERT INTO session_messages(id,session_id,request_id,body) VALUES(?,?,?,?)")) {
            statement.setString(1, str(message, "id")); statement.setString(2, str(session, "id")); statement.setString(3, str(message, "requestId"));
            statement.setString(4, mapper.writeValueAsString(message)); statement.executeUpdate();
        }
        session.put("updatedAt", now()); write("session", session);
    }

    /**
     * 会话正文按字面子串检索；索引和短词回退共用 Unicode 单码点小写规则。
     */
    public synchronized Map<String, Object> searchSessionMessages(String projectId, String requestedQuery, int requestedLimit) {
        get("project", projectId);
        String queryText = requestedQuery == null ? "" : requestedQuery.trim();
        if (queryText.isEmpty() || queryText.length() > 120 || queryText.indexOf('\0') >= 0) throw new ApiException(400, "搜索词长度须为 1 到 120 个字符，且不得包含空字符");
        String foldedQuery = foldSearch(queryText);
        int limit = Math.max(1, Math.min(100, requestedLimit));
        boolean shortQuery = foldedQuery.codePointCount(0, foldedQuery.length()) < 3;
        String sql = "SELECT f.session_id,f.message_id,f.sequence,m.body,s.body FROM session_search f JOIN session_messages m ON m.sequence=f.rowid "
            + "JOIN entities s ON s.kind='session' AND s.id=m.session_id WHERE f.project_id=? AND "
            + (shortQuery ? "instr(f.content,?)>0" : "session_search MATCH ?")
            + " AND json_extract(s.body,'$.projectId')=? AND json_extract(m.body,'$.projectId')=? ORDER BY f.sequence DESC LIMIT ?";
        try (var statement = connection.prepareStatement(sql)) {
            statement.setString(1, projectId);
            statement.setString(2, shortQuery ? foldedQuery : "\"" + foldedQuery.replace("\"", "\"\"") + "\"");
            statement.setString(3, projectId); statement.setString(4, projectId); statement.setInt(5, limit + 1);
            List<Map<String, Object>> results = new ArrayList<>();
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    Map<String, Object> message = decode(rows.getString(4)); String content = str(message, "content");
                    String foldedContent = foldSearch(content); int foldedMatch = foldedContent.indexOf(foldedQuery);
                    if (foldedMatch < 0) continue;
                    int matchPoint = foldedContent.codePointCount(0, foldedMatch);
                    int endPoint = matchPoint + foldedQuery.codePointCount(0, foldedQuery.length());
                    int match = content.offsetByCodePoints(0, matchPoint); int matchEnd = content.offsetByCodePoints(0, endPoint);
                    int start = content.offsetByCodePoints(0, Math.max(0, matchPoint - 80));
                    int end = content.offsetByCodePoints(0, Math.min(content.codePointCount(0, content.length()), endPoint + 120));
                    results.add(obj("sessionId", rows.getString(1), "sessionTitle", str(decode(rows.getString(5)), "title"), "messageId", rows.getString(2),
                        "sequence", rows.getLong(3), "taskId", str(message, "taskId"), "content", content.substring(start, end),
                        "matchStart", match - start, "matchEnd", matchEnd - start, "createdAt", str(message, "createdAt")));
                }
            }
            boolean hasMore = results.size() > limit; if (hasMore) results.removeLast();
            return obj("results", results, "hasMore", hasMore);
        } catch (Exception exception) { throw failure(exception); }
    }

    private static String foldSearch(String value) {
        if (value == null) return "";
        StringBuilder folded = new StringBuilder(value.length());
        value.codePoints().forEach(point -> folded.appendCodePoint(Character.toLowerCase(point)));
        return folded.toString();
    }

    public synchronized Map<String, Object> sessionMessages(String projectId, String sessionId, long before, long after, int requestedLimit) {
        session(projectId, sessionId);
        if (before < 0 || after < 0 || (before > 0 && after > 0)) throw new ApiException(400, "消息游标不能同时指定 before 与 after，且不得为负");
        int limit = Math.max(1, Math.min(100, requestedLimit)); boolean ascending = after > 0;
        String sql = "SELECT sequence,body FROM session_messages WHERE session_id=? AND " + (ascending ? "sequence>? ORDER BY sequence" : "(?=0 OR sequence<?) ORDER BY sequence DESC") + " LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, sessionId); query.setLong(2, ascending ? after : before);
            if (!ascending) query.setLong(3, before);
            query.setInt(ascending ? 3 : 4, limit + 1);
            List<Map<String, Object>> result = new ArrayList<>();
            try (var rows = query.executeQuery()) {
                while (rows.next()) { Map<String, Object> message = decode(rows.getString(2)); message.put("sequence", rows.getLong(1)); result.add(message); }
            }
            boolean hasMore = result.size() > limit; if (hasMore) result.removeLast();
            if (!ascending) java.util.Collections.reverse(result);
            long oldest = result.isEmpty() ? before : ((Number) result.getFirst().get("sequence")).longValue();
            long latest = result.isEmpty() ? after : ((Number) result.getLast().get("sequence")).longValue();
            return obj("messages", result, "cursor", ascending ? latest : oldest, "latestCursor", latest, "hasMore", hasMore);
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized Map<String, Object> sessionEvents(String projectId, String sessionId, long after, int requestedLimit) {
        session(projectId, sessionId);
        int limit = Math.max(1, Math.min(200, requestedLimit));
        String sql = "SELECT e.id,e.task_id,e.type,e.message,e.created_at FROM events e JOIN entities t ON t.kind='task' AND t.id=e.task_id"
            + " WHERE json_extract(t.body,'$.sessionId')=? AND json_extract(t.body,'$.projectId')=? AND e.id>? ORDER BY e.id LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            query.setString(1, sessionId); query.setString(2, projectId); query.setLong(3, Math.max(0, after)); query.setInt(4, limit + 1);
            List<Map<String, Object>> result = new ArrayList<>();
            try (var rows = query.executeQuery()) { while (rows.next()) result.add(obj("id", rows.getLong(1), "taskId", rows.getString(2), "type", rows.getString(3), "message", rows.getString(4), "createdAt", rows.getString(5))); }
            boolean hasMore = result.size() > limit; if (hasMore) result.removeLast();
            return obj("events", result, "cursor", result.isEmpty() ? after : result.getLast().get("id"), "hasMore", hasMore);
        } catch (Exception exception) { throw failure(exception); }
    }

    /**
     * 旧任务按稳定编号迁入独立会话，重启不重复生成目标记录或改动执行门禁。
     */
    public synchronized void migrateTaskSessions() {
        for (Map<String, Object> task : list("task")) {
            if (!str(task, "sessionId").isBlank()) continue;
            String sessionId = "legacy-" + str(task, "id");
            Map<String, Object> session = obj("id", sessionId, "projectId", task.get("projectId"), "title", task.getOrDefault("title", "历史任务"), "version", 1,
                "isOpen", false, "createdAt", task.getOrDefault("createdAt", now()), "updatedAt", task.getOrDefault("updatedAt", now()));
            task.put("sessionId", sessionId);
            transaction(() -> { write("session", session); write("task", task); writeSessionMessage(session, "task:" + str(task, "id"), "TASK_GOAL", str(task, "goal"), str(task, "id")); });
        }
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
        return beginOperation(taskId, stage, kind, target, "");
    }

    public synchronized Map<String, Object> beginOperation(String taskId, String stage, String kind, String target, String agentRunId) {
        Map<String, Object> operation = obj("id", id(), "taskId", taskId, "stage", stage, "kind", kind,
            "target", target, "state", "PREPARED", "createdAt", now());
        Map<String, Object> task = get("task", taskId);
        Map<String, Object> run = agentRunId.isBlank() ? null : get("agentRun", agentRunId);
        if (run != null) {
            if (!taskId.equals(str(run, "taskId")) || !stage.equals(str(run, "stage")) || !"WAITING_RESOURCE".equals(str(run, "state"))) throw new ApiException(409, "Agent 运行归属或状态不匹配");
            operation.put("agentRunId", agentRunId); run.put("operationId", operation.get("id"));
            run.put("state", "RUNNING"); run.put("startedAt", now());
        }
        task.put("activeOperation", operation.get("id"));
        task.put("updatedAt", now());
        transaction(() -> {
            write("operation", operation); write("task", task);
            if (run != null) write("agentRun", run);
            appendEvent(taskId, "OPERATION_PREPARED", stage + "：已保存操作意图");
        });
        return operation;
    }

    /**
     * 每次实际 Agent 调度保留独立身份；排队也先落库，重跑不覆盖旧运行。
     */
    public synchronized Map<String, Object> queueAgentRun(String taskId, Map<String, Object> stage, String workspace, String inputVersion, boolean reserved) {
        Map<String, Object> task = get("task", taskId), route = map(task, "routeSnapshot");
        int attempt;
        try (var query = connection.prepareStatement("SELECT COUNT(*) FROM entities WHERE kind='agentRun' AND json_extract(body,'$.taskId')=? AND json_extract(body,'$.stage')=?")) {
            query.setString(1, taskId); query.setString(2, str(stage, "id"));
            try (var rows = query.executeQuery()) { attempt = rows.next() ? rows.getInt(1) + 1 : 1; }
        } catch (SQLException exception) { throw failure(exception); }
        Map<String, Object> run = obj("id", id(), "schemaVersion", 1, "taskId", taskId, "rootTaskId", taskId,
            "projectId", task.get("projectId"), "sessionId", task.getOrDefault("sessionId", ""),
            "stage", stage.get("id"), "kind", stage.get("kind"), "title", stage.get("title"), "role", stage.get("role"),
            "attempt", attempt, "state", "WAITING_RESOURCE", "createdAt", now(), "workspace", workspace, "inputVersion", inputVersion,
            "readOnly", bool(stage, "readOnly", true), "scope", map(task, "contract").getOrDefault("scope", List.of()),
            "routeId", task.get("routeId"), "routeVersion", route.getOrDefault("version", 1), "routeName", route.get("name"),
            "engine", route.get("engine"), "model", route.get("model"), "provider", route.get("provider"),
            "reserved", reserved, "calls", 0, "callUnit", "API".equals(str(route, "engine")) ? "API_REQUEST" : "CLI_SESSION",
            "usage", obj("cost", "UNKNOWN"));
        run.put("profileVersions", objects(task.get("profileSnapshot")).stream().filter(profile -> str(stage, "role").equals(str(profile, "role")))
            .map(profile -> obj("id", profile.get("id"), "version", profile.get("version"))).toList());
        transaction(() -> { write("agentRun", run); appendEvent(taskId, "AGENT_QUEUED", str(stage, "id") + " · AgentRun " + str(run, "id")); });
        return run;
    }

    public synchronized void agentRunWaiting(String runId, String state) {
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "RUNNING").contains(state)) throw new ApiException(400, "无效 Agent 等待状态");
        Map<String, Object> run = get("agentRun", runId);
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "RUNNING").contains(str(run, "state"))) throw new ApiException(409, "已结束的 Agent 不可重新运行");
        if (state.equals(str(run, "state"))) return;
        run.put("state", state);
        transaction(() -> { write("agentRun", run); appendEvent(str(run, "taskId"), "AGENT_STATE", str(run, "stage") + " · AgentRun " + runId + "：" + state); });
    }

    /**
     * 运行调用数与根预算共用事务，失败或取消不退回已承诺的调用。
     */
    public synchronized boolean acquireAgentCall(String taskId, String agentRunId, boolean reserved) {
        Map<String, Object> task = get("task", taskId), run = get("agentRun", agentRunId);
        if (!taskId.equals(str(run, "taskId")) || !"RUNNING".equals(str(run, "state"))) throw new ApiException(409, "Agent 运行不可继续调用");
        if (!BudgetPolicy.acquire(map(task, "budget"), reserved)) return false;
        run.put("calls", num(run, "calls", 0) + 1); task.put("updatedAt", now());
        transaction(() -> { write("task", task); write("agentRun", run); appendEvent(taskId, "MODEL_CALL", str(run, "stage") + " · AgentRun " + agentRunId + "：已计入根任务" + (reserved ? "验证预留" : "开发额度")); });
        return true;
    }

    public synchronized void finishAgentRun(String runId, String state, String reason) {
        if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "PAUSED", "WAITING_INPUT", "BUDGET_EXHAUSTED").contains(state)) throw new ApiException(400, "无效 Agent 结束状态");
        Map<String, Object> run = get("agentRun", runId);
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "RUNNING").contains(str(run, "state"))) throw new ApiException(409, "已结束的 Agent 运行不可覆盖");
        run.put("state", state); run.put("reason", reason); run.put("endedAt", now());
        long elapsed = java.time.Duration.between(java.time.Instant.parse(str(run, "createdAt")), java.time.Instant.parse(str(run, "endedAt"))).toMillis();
        run.put("elapsedMs", Math.max(0, elapsed));
        if (!str(run, "startedAt").isBlank()) run.put("queueMs", Math.max(0, java.time.Duration.between(java.time.Instant.parse(str(run, "createdAt")), java.time.Instant.parse(str(run, "startedAt"))).toMillis()));
        transaction(() -> {
            for (Map<String, Object> request : pendingModelRequests(runId)) {
                endModelRequest(request, obj("state", "UNKNOWN", "errorType", "MISSING_OBSERVATION", "reason", "执行已结束但请求回执缺失，不推测成功或自动重试"));
            }
            write("agentRun", run); appendEvent(str(run, "taskId"), "AGENT_FINISHED", str(run, "stage") + " · AgentRun " + runId + "：" + state);
        });
    }

    /**
     * 请求身份、运行调用和根预算同事务保存；拒绝时不生成请求记录。
     */
    public synchronized String beginModelRequest(String taskId, String runId, boolean reserved, Map<String, Object> metadata) {
        Map<String, Object> task = get("task", taskId), run = get("agentRun", runId);
        if (!taskId.equals(str(run, "taskId")) || !"RUNNING".equals(str(run, "state"))) throw new ApiException(409, "Agent 运行不可继续调用");
        if (!BudgetPolicy.acquire(map(task, "budget"), reserved)) return "";
        Map<String, Object> request = obj("id", id(), "schemaVersion", 1, "taskId", taskId, "rootTaskId", run.get("rootTaskId"),
            "projectId", run.get("projectId"), "sessionId", run.get("sessionId"), "agentRunId", runId, "stage", run.get("stage"),
            "sequence", num(run, "calls", 0) + 1, "state", "DISPATCHING", "createdAt", now(), "attempt", 1,
            "routeId", run.get("routeId"), "routeVersion", run.get("routeVersion"), "engine", run.get("engine"), "model", run.get("model"),
            "unit", run.get("callUnit"), "usage", obj("cost", "UNKNOWN"));
        for (String key : List.of("round", "messageCount", "inputBytes", "inputDigest", "toolCount", "inputPreview", "inputTruncated")) {
            if (metadata.containsKey(key)) request.put(key, metadata.get(key));
        }
        run.put("calls", num(run, "calls", 0) + 1); task.put("updatedAt", now());
        transaction(() -> {
            write("task", task); write("agentRun", run); write("modelRequest", request);
            appendEvent(taskId, "MODEL_REQUEST", str(run, "stage") + " · 请求 " + request.get("id") + "：已承诺根预算，准备派发");
        });
        return str(request, "id");
    }

    public synchronized void finishModelRequest(String requestId, Map<String, Object> observation) {
        Map<String, Object> request = get("modelRequest", requestId);
        if (!"DISPATCHING".equals(str(request, "state"))) throw new ApiException(409, "已结束的模型请求不可覆盖");
        if (!Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN").contains(str(observation, "state"))) throw new ApiException(400, "无效模型请求状态");
        transaction(() -> endModelRequest(request, observation));
    }

    private void endModelRequest(Map<String, Object> request, Map<String, Object> observation) throws Exception {
        for (String key : List.of("state", "httpStatus", "providerRequestId", "responseId", "responseModel", "finishReason", "outputPreview", "outputTruncated", "errorType", "reason", "usage", "remoteOutcome")) {
            if (observation.containsKey(key)) request.put(key, observation.get(key));
        }
        request.put("endedAt", now());
        request.put("elapsedMs", Math.max(0, java.time.Duration.between(java.time.Instant.parse(str(request, "createdAt")), java.time.Instant.parse(str(request, "endedAt"))).toMillis()));
        write("modelRequest", request);
        appendEvent(str(request, "taskId"), "MODEL_RESPONSE", str(request, "stage") + " · 请求 " + request.get("id") + "：" + request.get("state"));
    }

    private List<Map<String, Object>> pendingModelRequests(String runId) throws Exception {
        List<Map<String, Object>> requests = new ArrayList<>();
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind='modelRequest' AND json_extract(body,'$.agentRunId')=? AND json_extract(body,'$.state')='DISPATCHING'")) {
            query.setString(1, runId);
            try (var rows = query.executeQuery()) { while (rows.next()) requests.add(decode(rows.getString(1))); }
        }
        return requests;
    }

    public synchronized Map<String, Object> modelRequestPage(String taskId, String runId, long before, int requestedLimit, String state, String queryText) {
        get("task", taskId);
        if (!runId.isBlank() && !taskId.equals(str(get("agentRun", runId), "taskId"))) throw new ApiException(404, "运行不属于此任务");
        if (before < 0 || (!state.isBlank() && !Set.of("DISPATCHING", "SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN").contains(state)) || queryText.length() > 200) throw new ApiException(400, "无效请求游标、状态或查询");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        String sql = "SELECT rowid,body FROM entities WHERE kind='modelRequest' AND json_extract(body,'$.taskId')=?"
            + (runId.isBlank() ? "" : " AND json_extract(body,'$.agentRunId')=?")
            + (state.isBlank() ? "" : " AND json_extract(body,'$.state')=?")
            + (queryText.isBlank() ? "" : " AND instr(session_search_fold(body),session_search_fold(?))>0")
            + (before == 0 ? "" : " AND rowid<?") + " ORDER BY rowid DESC LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            int index = 1; query.setString(index++, taskId);
            if (!runId.isBlank()) query.setString(index++, runId);
            if (!state.isBlank()) query.setString(index++, state);
            if (!queryText.isBlank()) query.setString(index++, queryText);
            if (before > 0) query.setLong(index++, before);
            query.setInt(index, limit + 1);
            List<Map<String, Object>> requests = new ArrayList<>(); long cursor = before;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (requests.size() == limit) return obj("requests", requests, "cursor", cursor, "hasMore", true);
                    cursor = rows.getLong(1); requests.add(decode(rows.getString(2)));
                }
            }
            return obj("requests", requests, "cursor", cursor, "hasMore", false);
        } catch (Exception exception) { throw failure(exception); }
    }

    public synchronized Map<String, Object> agentRunPage(String projectId, String sessionId, String taskId, long before, int requestedLimit, String state) {
        get("project", projectId);
        if (!sessionId.isBlank()) session(projectId, sessionId);
        if (!taskId.isBlank() && !projectId.equals(str(get("task", taskId), "projectId"))) throw new ApiException(403, "任务不属于此项目");
        if (before < 0 || (!state.isBlank() && !Set.of("WAITING_RESOURCE", "WAITING_TOOL", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "PAUSED", "WAITING_INPUT", "BUDGET_EXHAUSTED").contains(state))) throw new ApiException(400, "无效运行游标或状态");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        String sql = "SELECT rowid,body FROM entities WHERE kind='agentRun' AND json_extract(body,'$.projectId')=?"
            + (sessionId.isBlank() ? "" : " AND json_extract(body,'$.sessionId')=?")
            + (taskId.isBlank() ? "" : " AND json_extract(body,'$.taskId')=?")
            + (state.isBlank() ? "" : " AND json_extract(body,'$.state')=?")
            + (before == 0 ? "" : " AND rowid<?") + " ORDER BY rowid DESC LIMIT ?";
        try (var query = connection.prepareStatement(sql)) {
            int index = 1; query.setString(index++, projectId);
            if (!sessionId.isBlank()) query.setString(index++, sessionId);
            if (!taskId.isBlank()) query.setString(index++, taskId);
            if (!state.isBlank()) query.setString(index++, state);
            if (before != 0) query.setLong(index++, before);
            query.setInt(index, limit + 1);
            List<Map<String, Object>> runs = new ArrayList<>(); long cursor = before;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (runs.size() == limit) return obj("runs", runs, "cursor", cursor, "hasMore", true);
                    cursor = rows.getLong(1); runs.add(decode(rows.getString(2)));
                }
            }
            return obj("runs", runs, "cursor", cursor, "hasMore", false);
        } catch (Exception exception) { throw failure(exception); }
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
        Map<String, Object> run = str(operation, "agentRunId").isBlank() ? null : get("agentRun", str(operation, "agentRunId"));
        if (run != null) {
            evidence.put("agentRunId", run.get("id")); run.put("evidenceId", evidence.get("id"));
            run.put("resultVersion", evidence.getOrDefault("resultVersion", evidence.get("version"))); run.put("usage", evidence.getOrDefault("usage", obj("cost", "UNKNOWN")));
        }
        transaction(() -> {
            if (find("evidence", str(evidence, "id")) != null) { throw new IllegalStateException("证据不可覆盖"); }
            write("evidence", evidence); write("task", task); write("operation", operation);
            if (run != null) write("agentRun", run);
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
