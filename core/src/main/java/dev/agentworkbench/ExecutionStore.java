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
public class ExecutionStore implements AutoCloseable {
    private final Connection connection;
    private final ObjectMapper mapper;
    private final Path directory;
    /**
     * 整个核心生命周期持有跨进程锁，防止第二实例恢复并接管相同任务目录。
     */
    private final FileChannel ownershipChannel;
    private final FileLock ownershipLock;
    private volatile boolean healthy = true;
    /**
     * 持锁开库的本次身份，同一 JVM 重开数据目录也不能沿用旧核心会话。
     */
    private final String coreSessionId = java.util.UUID.randomUUID().toString();

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
                    if (schemaVersion > 8) throw new IllegalStateException("数据版本高于当前核心支持范围，请使用兼容版本");
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
                statement.execute("CREATE INDEX IF NOT EXISTS entities_subtask_root ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.parentRunId'),json_extract(body,'$.state')) WHERE kind='subtask'");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_subtask_dispatch ON entities(json_extract(body,'$.parentRunId'),json_extract(body,'$.requestId'),json_extract(body,'$.toolCallId')) WHERE kind='subtask'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_team_message ON entities(json_extract(body,'$.teamId'),json_extract(body,'$.sequence')) WHERE kind='teamMessage'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_effect_state ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.state')) WHERE kind IN ('operation','mcpOperation','teamOperation')");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_worker_lease_operation ON entities(json_extract(body,'$.operationKind'),json_extract(body,'$.operationId')) WHERE kind='workerLease'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_worker_lease_owner ON entities(json_extract(body,'$.workerId'),json_extract(body,'$.state')) WHERE kind='workerLease'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_worker_lease_task ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.state')) WHERE kind='workerLease'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_process_session_owner ON entities(json_extract(body,'$.ownerCorePid'),json_extract(body,'$.ownerCoreStartedAt'),json_extract(body,'$.state')) WHERE kind='processSession'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_process_observation_session ON entities(json_extract(body,'$.sessionId')) WHERE kind='processObservation'");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_task_execution_attempt ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.attempt')) WHERE kind='taskExecution'");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_dispatch_execution ON entities(json_extract(body,'$.taskExecutionId')) WHERE kind='dispatchAction'");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_dispatch_active_task ON entities(json_extract(body,'$.taskId')) WHERE kind='dispatchAction' AND json_extract(body,'$.state') IN ('PENDING','CLAIMED')");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_dispatch_pending ON entities(json_extract(body,'$.state')) WHERE kind='dispatchAction'");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_dispatch_task ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.state')) WHERE kind IN ('dispatchAction','taskExecution')");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_workspace_write_root ON entities(json_extract(body,'$.taskExecutionId')) WHERE kind='workspaceWriteLease'");
                statement.execute("CREATE UNIQUE INDEX IF NOT EXISTS entities_workspace_write_scope ON entities(json_extract(body,'$.scopeKey')) WHERE kind='workspaceWriteLease' AND json_extract(body,'$.protectionReleased')=0");
                statement.execute("CREATE INDEX IF NOT EXISTS entities_workspace_write_task ON entities(json_extract(body,'$.taskId'),json_extract(body,'$.state')) WHERE kind='workspaceWriteLease'");
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
                statement.execute("PRAGMA user_version=8");
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
    String coreSessionId() { return coreSessionId; }

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

    /**
     * 节点归属、租约和操作记录共用原事务，调用方在同一存储锁内完成版本预检。
     */
    synchronized void commitRecords(Map<String, List<Map<String, Object>>> records, String taskId, String eventType, String message) {
        commitRecords(records, taskId, eventType == null ? List.of() : List.of(obj("type", eventType, "message", message)));
    }

    /**
     * 根状态、执行尝试、队列动作及确认事件一起提交，任何写入失败均不派发。
     */
    synchronized void commitRecords(Map<String, List<Map<String, Object>>> records, String taskId, List<Map<String, Object>> events) {
        transaction(() -> {
            for (var entry : records.entrySet()) for (Map<String, Object> record : entry.getValue()) write(entry.getKey(), record);
            for (Map<String, Object> event : events) appendEvent(taskId, str(event, "type"), str(event, "message"));
        });
    }

    /**
     * 领取在 SQL 中再次限制原状态与版本；零行更新保留原记录，不写任务或执行尝试。
     */
    synchronized boolean claimDispatch(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action, int expectedVersion) {
        return claimDispatch(task, execution, action, expectedVersion, obj());
    }

    /**
     * 新写入意图与原根领取共用事务，任何一项失败都不留下可执行的归属。
     */
    synchronized boolean claimDispatch(Map<String, Object> task, Map<String, Object> execution, Map<String, Object> action, int expectedVersion, Map<String, Object> writeLease) {
        boolean[] claimed = {false};
        transaction(() -> {
            try (var update = connection.prepareStatement("UPDATE entities SET body=? WHERE kind='dispatchAction' AND id=? AND json_extract(body,'$.state')='PENDING' AND json_extract(body,'$.version')=? AND json_extract(body,'$.dispatchCount')=0"
                    + " AND EXISTS(SELECT 1 FROM entities task WHERE task.kind='task' AND task.id=? AND json_extract(task.body,'$.state')='QUEUED' AND json_extract(task.body,'$.taskExecutionId')=? AND json_extract(task.body,'$.dispatchActionId')=?)"
                    + " AND EXISTS(SELECT 1 FROM entities execution WHERE execution.kind='taskExecution' AND execution.id=? AND json_extract(execution.body,'$.state')='QUEUED' AND json_extract(execution.body,'$.dispatchActionId')=?)")) {
                update.setString(1, mapper.writeValueAsString(action)); update.setString(2, str(action, "id")); update.setInt(3, expectedVersion);
                update.setString(4, str(task, "id")); update.setString(5, str(execution, "id")); update.setString(6, str(action, "id")); update.setString(7, str(execution, "id")); update.setString(8, str(action, "id"));
                claimed[0] = update.executeUpdate() == 1;
            }
            if (claimed[0]) {
                write("task", task); write("taskExecution", execution);
                if (!writeLease.isEmpty()) write("workspaceWriteLease", writeLease);
                appendEvent(str(task, "id"), "TASK_DISPATCHED", "原动作 " + action.get("id") + " 已领取一次，根执行 " + execution.get("id"));
            }
        });
        return claimed[0];
    }

    /**
     * 尝试号按根任务累计，暂停、取消和重开均不重用历史编号。
     */
    synchronized int nextTaskExecutionAttempt(String taskId) {
        try (var query = connection.prepareStatement("SELECT COALESCE(MAX(json_extract(body,'$.attempt')),0)+1 FROM entities WHERE kind='taskExecution' AND json_extract(body,'$.taskId')=?")) {
            query.setString(1, taskId); try (var rows = query.executeQuery()) { return rows.next() ? rows.getInt(1) : 1; }
        } catch (SQLException exception) { throw failure(exception); }
    }

    /**
     * 持久动作按插入顺序有界扫描；被阻塞的旧动作不能遮住后续任务。
     */
    synchronized List<Map<String, Object>> pendingDispatchActions(long after, int requestedLimit) {
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try (var query = connection.prepareStatement("SELECT rowid,body FROM entities WHERE kind='dispatchAction' AND json_extract(body,'$.state')='PENDING' AND rowid>? ORDER BY rowid LIMIT ?")) {
            query.setLong(1, after); query.setInt(2, limit);
            try (var rows = query.executeQuery()) {
                List<Map<String, Object>> actions = new ArrayList<>();
                while (rows.next()) { Map<String, Object> action = decode(rows.getString(2)); action.put("sequence", rows.getLong(1)); actions.add(action); }
                return actions;
            }
        } catch (Exception exception) { throw failure(exception); }
    }

    synchronized List<Map<String, Object>> taskDispatchRecords(String kind, String taskId) {
        if (!Set.of("taskExecution", "dispatchAction", "workspaceWriteLease").contains(kind)) throw new IllegalArgumentException("Invalid dispatch record kind");
        get("task", taskId);
        return dispatchRecords(kind, taskId);
    }

    /**
     * 历史进程门禁保留孤立写入记录的原 Worker 关联，任务删除不代表原进程停止。
     */
    synchronized List<Map<String, Object>> workspaceWriteLeases(String taskId) { return dispatchRecords("workspaceWriteLease", taskId); }

    private List<Map<String, Object>> dispatchRecords(String kind, String taskId) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind=? AND json_extract(body,'$.taskId')=? ORDER BY rowid DESC")) {
            query.setString(1, kind); query.setString(2, taskId);
            try (var rows = query.executeQuery()) { List<Map<String, Object>> records = new ArrayList<>(); while (rows.next()) records.add(decode(rows.getString(1))); return records; }
        } catch (Exception exception) { throw failure(exception); }
    }

    /**
     * 根历史保留原 UNKNOWN；核对说明单独保存，只有未核对的未知记录阻止新尝试。
     */
    synchronized boolean hasUnknownTaskExecution(String taskId) {
        try (var query = connection.prepareStatement("SELECT 1 FROM entities WHERE kind IN ('taskExecution','dispatchAction') AND json_extract(body,'$.taskId')=? AND json_extract(body,'$.state')='UNKNOWN' AND json_extract(body,'$.reconciliation') IS NULL LIMIT 1")) {
            query.setString(1, taskId); try (var rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { throw failure(exception); }
    }

    public synchronized Map<String, Object> taskExecutionPage(String taskId, long before, int limit, String state) {
        if (!state.isBlank() && !TaskDispatchQueue.EXECUTION_STATES.contains(state)) throw new ApiException(400, "根执行状态无效");
        Map<String, Object> page = dispatchRecordPage("taskExecution", "executions", taskId, before, limit, state);
        for (Map<String, Object> execution : objects(page.get("executions"))) execution.put("dispatchAction", get("dispatchAction", str(execution, "dispatchActionId")));
        return page;
    }

    public synchronized Map<String, Object> dispatchActionPage(String taskId, long before, int limit, String state) {
        if (!state.isBlank() && !TaskDispatchQueue.ACTION_STATES.contains(state)) throw new ApiException(400, "派发动作状态无效");
        return dispatchRecordPage("dispatchAction", "actions", taskId, before, limit, state);
    }

    public synchronized Map<String, Object> workspaceWriteLeasePage(String taskId, long before, int limit, String state) {
        if (!state.isBlank() && !WorkspaceWriteLedger.STATES.contains(state)) throw new ApiException(400, "现场写入归属状态无效");
        return dispatchRecordPage("workspaceWriteLease", "leases", taskId, before, limit, state);
    }

    private Map<String, Object> dispatchRecordPage(String kind, String key, String taskId, long before, int requestedLimit, String state) {
        get("task", taskId);
        if (before < 0) throw new ApiException(400, "根执行游标无效");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try (var query = connection.prepareStatement("SELECT rowid,body FROM entities WHERE kind=? AND json_extract(body,'$.taskId')=?"
                + (before > 0 ? " AND rowid<?" : "") + (state.isBlank() ? "" : " AND json_extract(body,'$.state')=?") + " ORDER BY rowid DESC LIMIT ?")) {
            int index = 1; query.setString(index++, kind); query.setString(index++, taskId); if (before > 0) query.setLong(index++, before); if (!state.isBlank()) query.setString(index++, state); query.setInt(index, limit + 1);
            List<Map<String, Object>> records = new ArrayList<>(); long cursor = before; boolean more = false;
            try (var rows = query.executeQuery()) {
                while (rows.next()) { if (records.size() == limit) { more = true; break; } cursor = rows.getLong(1); Map<String, Object> record = decode(rows.getString(2)); record.put("sequence", cursor); records.add(record); }
            }
            return obj(key, records, "cursor", cursor, "hasMore", more);
        } catch (Exception exception) { throw failure(exception); }
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
            if (!str(task, "teamId").isBlank()) write("team", obj("id", task.get("teamId"), "taskId", task.get("id"), "projectId", task.get("projectId"), "sessionId", task.get("sessionId"), "name", task.get("title"), "createdAt", now(), "messageSequence", 0, "version", 1));
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
        copyTaskExecutionSource(operation, str(operation, "agentRunId").isBlank() ? task : get("agentRun", str(operation, "agentRunId")));
        requireActiveTaskExecution(task, operation);
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
        copyTaskExecutionSource(operation, run == null ? task : run);
        if ("WORKSPACE".equals(kind) && task.containsKey("workspaceSelection")) operation.put("workspaceSelection", task.get("workspaceSelection"));
        if (run != null) {
            if (!taskId.equals(str(run, "taskId")) || !stage.equals(str(run, "stage")) || !"WAITING_RESOURCE".equals(str(run, "state"))) throw new ApiException(409, "Agent 运行归属或状态不匹配");
            operation.put("agentRunId", agentRunId); run.put("operationId", operation.get("id"));
            run.put("state", "RUNNING"); run.putIfAbsent("startedAt", now());
        }
        if (run == null || str(run, "subtaskId").isBlank()) task.put("activeOperation", operation.get("id"));
        task.put("updatedAt", now());
        transaction(() -> {
            write("operation", operation); write("task", task);
            if (run != null) {
                write("agentRun", run);
                if (!str(run, "subtaskId").isBlank()) {
                    Map<String, Object> child = subtask(taskId, str(run, "subtaskId")); child.put("state", "RUNNING"); write("subtask", child);
                }
            }
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
        run.put("permissions", CollaborationPolicy.rootPermissions(task, stage));
        copyTaskExecutionSource(run, task);
        if (!str(task, "teamId").isBlank()) run.putAll(obj("teamId", task.get("teamId"), "depth", 0));
        run.put("profileVersions", objects(task.get("profileSnapshot")).stream().filter(profile -> str(stage, "role").equals(str(profile, "role")))
            .map(profile -> obj("id", profile.get("id"), "version", profile.get("version"))).toList());
        transaction(() -> { write("agentRun", run); appendEvent(taskId, "AGENT_QUEUED", str(stage, "id") + " · AgentRun " + str(run, "id")); });
        return run;
    }

    public synchronized void agentRunWaiting(String runId, String state) {
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING").contains(state)) throw new ApiException(400, "无效 Agent 等待状态");
        Map<String, Object> run = get("agentRun", runId);
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING").contains(str(run, "state"))) throw new ApiException(409, "已结束的 Agent 不可重新运行");
        if (state.equals(str(run, "state"))) return;
        run.put("state", state);
        transaction(() -> {
            write("agentRun", run);
            if (!str(run, "subtaskId").isBlank()) {
                Map<String, Object> child = subtask(str(run, "taskId"), str(run, "subtaskId"));
                if (child.containsKey("endedAt")) throw new ApiException(409, "已结束的副任务不可重新运行");
                child.put("state", state); child.put("updatedAt", now()); write("subtask", child);
            }
            appendEvent(str(run, "taskId"), "AGENT_STATE", str(run, "stage") + " · AgentRun " + runId + "：" + state);
        });
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
        if (!Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING").contains(str(run, "state"))) throw new ApiException(409, "已结束的 Agent 运行不可覆盖");
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
        for (String key : List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "teamId", "subtaskId", "parentRunId", "definitionId", "definitionVersion", "depth", "workerLeaseId", "workerId", "workerEpoch", "executionNodeId", "repositoryBindingId", "bindingVersion", "deployment", "executorPid", "processStartedAt", "corePid", "processSessionId")) if (run.containsKey(key)) request.put(key, run.get(key));
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
        if (before < 0 || (!state.isBlank() && !Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING", "SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "PAUSED", "WAITING_INPUT", "BUDGET_EXHAUSTED").contains(state))) throw new ApiException(400, "无效运行游标或状态");
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

    public synchronized Map<String, Object> saveAgentDefinition(Map<String, Object> definition, Map<String, Object> previous) {
        Map<String, Object> current = find("agentDefinition", str(definition, "id"));
        if ((current == null) != (previous == null) || (current != null && num(current, "version", 1) != num(previous, "version", 1))) throw new ApiException(409, "Agent 定义版本冲突");
        transaction(() -> {
            if (previous != null) write("agentDefinitionRevision", obj("id", id(), "definitionId", previous.get("id"), "record", previous, "createdAt", now()));
            write("agentDefinition", definition);
        });
        return definition;
    }

    private Map<String, Object> agentRequest(String taskId, String runId, String requestId) {
        Map<String, Object> run = get("agentRun", runId), request = get("modelRequest", requestId);
        if (!taskId.equals(str(run, "taskId")) || !taskId.equals(str(request, "taskId")) || !runId.equals(str(request, "agentRunId")) || !"SUCCEEDED".equals(str(request, "state"))) throw new ApiException(403, "工具操作没有所属运行的成功模型回执");
        if (!Set.of("RUNNING", "WAITING_TOOL", "WAITING_CHILDREN").contains(str(run, "state"))) throw new ApiException(409, "来源 Agent 本轮已结束");
        return run;
    }

    private Map<String, Object> teamRequest(String taskId, String runId, String requestId) {
        Map<String, Object> run = agentRequest(taskId, runId, requestId);
        if (str(run, "teamId").isBlank()) throw new ApiException(403, "委派操作没有所属团队");
        if (Set.of("PROPOSAL", "REVIEW").contains(str(run, "kind"))) throw new ApiException(403, "独立提案和审查不能使用共享团队输入输出");
        return run;
    }

    /**
     * 固定 MCP 动作的执行来源只由真实模型回执派生，不能用消息或参数伪造。
     */
    public synchronized Map<String, Object> agentToolSource(String taskId, String runId, String requestId, String toolCallId) {
        Map<String, Object> run = agentRequest(taskId, runId, requestId), permissions = map(run, "permissions");
        if (toolCallId.isBlank() || toolCallId.length() > 200 || bool(run, "readOnly", true) || Set.of("PROPOSAL", "REVIEW").contains(str(run, "kind"))
                || !AgentPermissions.has(permissions, "mcp") || !AgentPermissions.has(permissions, "write")) throw new ApiException(403, "执行者没有固定 MCP 权限或原工具身份");
        if (!str(run, "subtaskId").isBlank() && (!AgentPermissions.unrestricted(permissions, "read") || !AgentPermissions.unrestricted(permissions, "write"))) throw new ApiException(403, "窄范围副任务不能证明外部 MCP 的文件访问边界，请交回根授权阶段");
        Map<String, Object> source = obj("agentRunId", runId, "modelRequestId", requestId, "toolCallId", toolCallId, "rootTaskId", run.get("rootTaskId"), "actorKind", run.get("kind"));
        for (String key : List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "teamId", "subtaskId", "parentRunId", "definitionId", "definitionVersion", "depth", "sessionId", "workerLeaseId", "workerId", "workerEpoch", "executionNodeId", "repositoryBindingId", "bindingVersion", "deployment", "executorPid", "processStartedAt", "corePid")) if (run.containsKey(key)) source.put(key, run.get(key));
        return source;
    }

    public synchronized boolean hasUnknownEffects(String taskId) {
        try (var query = connection.prepareStatement("SELECT 1 FROM entities WHERE (kind IN ('operation','mcpOperation','teamOperation') OR (kind='workspaceWriteLease' AND json_extract(body,'$.protectionReleased')=0)) AND json_extract(body,'$.taskId')=? AND json_extract(body,'$.state')='UNKNOWN' LIMIT 1")) {
            query.setString(1, taskId); try (var rows = query.executeQuery()) { return rows.next(); }
        } catch (SQLException exception) { throw failure(exception); }
    }

    /**
     * 副任务和排队运行同事务建立，数量限制按根累计，不随结束、重建或递归清零。
     */
    public synchronized Map<String, Object> enqueueSubtask(String taskId, String parentRunId, String requestId, String toolCallId, Map<String, Object> input) {
        Map<String, Object> parent = teamRequest(taskId, parentRunId, requestId), task = get("task", taskId), policy = map(task, "collaboration");
        if (!bool(policy, "enabled", false) || !AgentPermissions.has(map(parent, "permissions"), "delegate")) throw new ApiException(403, "当前阶段没有委派权限");
        if (!Set.of("RUNNING", "WAITING_RESOURCE").contains(str(task, "state"))) throw new ApiException(409, "根任务已停止新增委派");
        if (toolCallId.isBlank() || toolCallId.length() > 200) throw new ApiException(400, "工具调用身份无效");
        for (Map<String, Object> old : list("subtask")) {
            if (parentRunId.equals(str(old, "parentRunId")) && requestId.equals(str(old, "requestId")) && toolCallId.equals(str(old, "toolCallId"))) {
                if (!map(old, "request").equals(input)) throw new ApiException(409, "同一委派身份的参数不能改变");
                return old;
            }
        }
        if (hasUnknownEffects(taskId)) throw new ApiException(409, "团队有未确认操作，停止新委派");
        int depth = num(parent, "depth", 0) + 1;
        if (depth > num(policy, "maxDepth", 3) || num(policy, "spawned", 0) >= num(policy, "maxTotal", 24)) throw new ApiException(409, "根委派数量或递归深度达到上限");
        long active = list("agentRun").stream().filter(run -> taskId.equals(str(run, "taskId")) && Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING").contains(str(run, "state"))).count();
        if (active >= num(policy, "maxActive", 6)) throw new ApiException(409, "活动或排队 Agent 达到根上限");
        Map<String, Object> budget = map(task, "budget");
        if (num(budget, "usedCalls", 0) + Math.max(0, num(budget, "reserveCalls", 0) - num(budget, "usedReservedCalls", 0)) >= num(budget, "maxCalls", 0)) throw new ApiException(409, "根开发预算不足，不能创建新副任务");
        Map<String, Object> definition = objects(policy.get("definitions")).stream().filter(value -> str(input, "definitionId").equals(str(value, "id"))).findFirst().orElseThrow(() -> new ApiException(404, "Agent 定义不在任务固定版本中"));
        Map<String, Object> liveDefinition = find("agentDefinition", str(definition, "id")), liveRoute = find("route", str(map(definition, "routeSnapshot"), "id"));
        if (liveDefinition == null || !bool(liveDefinition, "enabled", false) || liveRoute == null || !bool(liveRoute, "enabled", false)) throw new ApiException(409, "副 Agent 定义或路由已停用");
        if (!(input.get("scope") instanceof List<?>)) throw new ApiException(400, "委派需要明确文件范围清单");
        if (input.containsKey("dependsOn") && (!(input.get("dependsOn") instanceof List<?> values) || values.stream().anyMatch(value -> !(value instanceof String dependency) || dependency.isBlank() || dependency.length() > 100))) throw new ApiException(400, "依赖需要副任务身份清单");
        if (input.containsKey("maxCalls") && (!(input.get("maxCalls") instanceof Number number) || number.doubleValue() != number.intValue())) throw new ApiException(400, "副任务调用上限必须为整数");
        Map<String, Object> permissions = AgentPermissions.narrow(map(parent, "permissions"), definition, input);
        if (strings(permissions.get("capabilities")).isEmpty()) throw new ApiException(403, "副 Agent 没有可用的实际能力交集");
        List<String> dependencies = strings(input.get("dependsOn")).stream().distinct().toList();
        if (dependencies.size() > 12) throw new ApiException(400, "依赖最多 12 项");
        for (String dependency : dependencies) {
            Map<String, Object> sibling = subtask(taskId, dependency);
            if (!parentRunId.equals(str(sibling, "parentRunId"))) throw new ApiException(409, "等待依赖必须为同一父运行中已创建的副任务，避免父子等待环");
        }
        int maxCalls = num(input, "maxCalls", num(definition, "maxCalls", 8));
        if (maxCalls < 1 || maxCalls > num(definition, "maxCalls", 8)) throw new ApiException(400, "副任务调用上限不得增加定义的限制");
        String subtaskId = id(), runId = id(), state = dependencies.isEmpty() ? "WAITING_RESOURCE" : "WAITING_DEPENDENCY";
        Map<String, Object> route = map(definition, "routeSnapshot");
        Map<String, Object> child = obj("id", subtaskId, "taskId", taskId, "rootTaskId", taskId, "teamId", parent.get("teamId"), "projectId", task.get("projectId"), "sessionId", task.get("sessionId"),
            "parentRunId", parentRunId, "parentSubtaskId", parent.getOrDefault("subtaskId", ""), "agentRunId", runId, "requestId", requestId, "toolCallId", toolCallId,
            "title", required(input, "title", 180), "goal", required(input, "goal", 12000), "definitionId", definition.get("id"), "definitionVersion", definition.get("version"), "role", definition.get("role"),
            "permissions", permissions, "depth", depth, "dependsOn", dependencies, "state", state, "createdAt", now(), "maxCalls", maxCalls, "request", input,
            "routeId", route.get("id"), "routeVersion", route.getOrDefault("version", 1), "engine", route.get("engine"), "model", route.get("model"), "integrationState", "NOT_ADOPTED");
        Map<String, Object> run = obj("id", runId, "schemaVersion", 1, "taskId", taskId, "rootTaskId", taskId, "subtaskId", subtaskId, "teamId", parent.get("teamId"), "parentRunId", parentRunId,
            "projectId", task.get("projectId"), "sessionId", task.get("sessionId"), "stage", "subagent-" + subtaskId, "kind", "SUBAGENT", "title", child.get("title"), "role", definition.get("role"),
            "attempt", 1, "depth", depth, "state", state, "createdAt", now(), "permissions", permissions, "readOnly", !AgentPermissions.has(permissions, "write"), "scope", AgentPermissions.layers(permissions, "write"),
            "routeId", route.get("id"), "routeVersion", route.getOrDefault("version", 1), "routeName", route.get("name"), "engine", "API", "model", route.get("model"), "provider", route.get("provider"),
            "definitionId", definition.get("id"), "definitionVersion", definition.get("version"), "reserved", false, "calls", 0, "callUnit", "API_REQUEST", "usage", obj("cost", "UNKNOWN"));
        copyTaskExecutionSource(child, parent); copyTaskExecutionSource(run, parent);
        policy.put("spawned", num(policy, "spawned", 0) + 1); task.put("updatedAt", now());
        transaction(() -> { write("task", task); write("subtask", child); write("agentRun", run); appendEvent(taskId, "SUBAGENT_CREATED", str(child, "title") + " · " + subtaskId + "，继承根预算和权限交集"); });
        return child;
    }

    public synchronized Map<String, Object> subtask(String taskId, String subtaskId) {
        Map<String, Object> child = get("subtask", subtaskId);
        if (!taskId.equals(str(child, "taskId"))) throw new ApiException(404, "副任务不属于此根任务");
        return child;
    }

    public synchronized Map<String, Object> updateSubtask(String taskId, String subtaskId, Map<String, Object> facts) {
        Map<String, Object> child = subtask(taskId, subtaskId), run = get("agentRun", str(child, "agentRunId"));
        if (child.containsKey("endedAt") || !Set.of("WAITING_RESOURCE", "WAITING_TOOL", "WAITING_CHILDREN", "WAITING_DEPENDENCY", "RUNNING").contains(str(run, "state"))) throw new ApiException(409, "副任务结束后的执行事实不可覆盖");
        if (!Set.of("workspace", "baseWorkspace", "inputVersion", "error").containsAll(facts.keySet())) throw new ApiException(400, "副任务状态来自实际运行，采纳事实来自操作回执");
        child.putAll(facts);
        for (String key : List.of("workspace", "inputVersion")) if (facts.containsKey(key)) run.put(key, facts.get(key));
        child.put("updatedAt", now());
        transaction(() -> { write("subtask", child); write("agentRun", run); });
        return child;
    }

    public synchronized void finishSubtask(String taskId, String subtaskId, String state, String reason) {
        Map<String, Object> child = subtask(taskId, subtaskId), run = get("agentRun", str(child, "agentRunId"));
        if (child.containsKey("endedAt")) throw new ApiException(409, "副任务终态不可覆盖");
        if (!Set.of("SUCCEEDED", "FAILED", "UNKNOWN", "CANCELLED", "PAUSED", "WAITING_INPUT", "BUDGET_EXHAUSTED").contains(state) || !state.equals(str(run, "state"))) throw new ApiException(409, "副任务必须使用实际 AgentRun 的结束事实");
        child.putAll(obj("state", state, "reason", reason, "endedAt", now(), "evidenceId", run.getOrDefault("evidenceId", ""), "resultVersion", run.getOrDefault("resultVersion", "")));
        transaction(() -> { write("subtask", child); appendEvent(taskId, "SUBAGENT_FINISHED", str(child, "title") + "：" + state); });
    }

    public synchronized Map<String, Object> team(String taskId) {
        Map<String, Object> task = get("task", taskId);
        if (str(task, "teamId").isBlank()) return obj("team", null, "agents", List.of(), "runs", List.of());
        Map<String, Object> team = get("team", str(task, "teamId")); team.put("taskState", task.get("state"));
        List<Map<String, Object>> agents = list("subtask").stream().filter(child -> taskId.equals(str(child, "taskId"))).map(child -> {
            Map<String, Object> visible = new java.util.LinkedHashMap<>(child); visible.remove("request"); visible.remove("baseWorkspace");
            if (!child.containsKey("endedAt")) visible.put("state", get("agentRun", str(child, "agentRunId")).get("state"));
            return visible;
        }).toList();
        return obj("team", team, "agents", agents, "runs", list("agentRun").stream().filter(run -> taskId.equals(str(run, "taskId"))).toList(),
            "limits", obj("maxActive", map(task, "collaboration").get("maxActive"), "maxDepth", map(task, "collaboration").get("maxDepth"), "maxTotal", map(task, "collaboration").get("maxTotal"), "spawned", map(task, "collaboration").get("spawned")));
    }

    public synchronized Map<String, Object> beginTeamOperation(String taskId, String runId, String requestId, String toolCallId, String kind, String target, Map<String, Object> facts) {
        Map<String, Object> run = teamRequest(taskId, runId, requestId);
        if (toolCallId.isBlank() || toolCallId.length() > 200) throw new ApiException(400, "工具调用身份无效");
        for (Map<String, Object> old : list("teamOperation")) if (runId.equals(str(old, "agentRunId")) && requestId.equals(str(old, "requestId")) && toolCallId.equals(str(old, "toolCallId"))) {
            if (!kind.equals(str(old, "kind")) || !target.equals(str(old, "target")) || !facts.equals(map(old, "facts"))) throw new ApiException(409, "团队操作身份不能复用或改变参数");
            return old;
        }
        if (list("teamOperation").stream().anyMatch(old -> taskId.equals(str(old, "taskId")) && "UNKNOWN".equals(str(old, "state")))) throw new ApiException(409, "未确认团队操作不能通过新编号重放");
        Map<String, Object> operation = obj("id", id(), "taskId", taskId, "teamId", run.get("teamId"), "agentRunId", runId, "requestId", requestId, "toolCallId", toolCallId, "kind", kind, "target", target, "state", "PREPARED", "createdAt", now(), "facts", facts);
        copyTaskExecutionSource(operation, run);
        transaction(() -> write("teamOperation", operation)); return operation;
    }

    public synchronized void finishTeamOperation(String operationId, String state, Map<String, Object> facts) {
        finishTeamOperation(operationId, state, facts, null);
    }

    synchronized void finishWorkerTeamOperation(String operationId, String state, Map<String, Object> facts, Map<String, Object> lease) {
        finishTeamOperation(operationId, state, facts, lease);
    }

    private void finishTeamOperation(String operationId, String state, Map<String, Object> facts, Map<String, Object> lease) {
        Map<String, Object> operation = get("teamOperation", operationId);
        requireLeaseReceipt(operation, state, lease);
        if (!Set.of("PREPARED", "DISPATCHED").contains(str(operation, "state"))) throw new ApiException(409, "团队操作终态不可覆盖");
        if (!Set.of("DISPATCHED", "ACKNOWLEDGED", "REJECTED", "UNKNOWN", "CANCELLED").contains(state)
                || (state.equals("DISPATCHED") && !"PREPARED".equals(str(operation, "state")))
                || (state.equals("ACKNOWLEDGED") && !"DISPATCHED".equals(str(operation, "state")))) throw new ApiException(409, "团队操作必须遵守准备、派发和回执顺序");
        Map<String, Object> child = null;
        if ("ACKNOWLEDGED".equals(state) && "ADOPT_SUBAGENT".equals(str(operation, "kind"))) {
            child = subtask(str(operation, "taskId"), str(operation, "target"));
            if (!"SUCCEEDED".equals(str(child, "state")) || !AgentPermissions.has(map(child, "permissions"), "write") || !str(operation, "agentRunId").equals(str(child, "parentRunId"))
                    || !str(map(operation, "facts"), "resultVersion").equals(str(child, "resultVersion")) || str(facts, "resultVersion").isBlank()) throw new ApiException(409, "采纳回执必须对应成功直接副任务的原成果");
            List<Map<String, Object>> integrations = new ArrayList<>(objects(child.get("integrations")));
            integrations.add(obj("operationId", operationId, "agentRunId", operation.get("agentRunId"), "resultVersion", facts.get("resultVersion"), "createdAt", now()));
            child.putAll(obj("integrationState", "ADOPTED", "integrationVersion", facts.get("resultVersion"), "integrations", integrations));
        }
        operation.put("state", state); operation.put("observation", facts); operation.put("updatedAt", now());
        Map<String, Object> adopted = child;
        transaction(() -> { write("teamOperation", operation); if (lease != null) write("workerLease", lease); if (adopted != null) write("subtask", adopted); appendEvent(str(operation, "taskId"), "TEAM_OPERATION", str(operation, "kind") + "：" + state); });
    }

    /**
     * 发件身份由认证用户或真实模型回执派生；重复编号不能改变收件人和正文。
     */
    public synchronized Map<String, Object> teamMessage(String taskId, String sender, String recipient, String key, String content, String requestId, String toolCallId) {
        Map<String, Object> task = get("task", taskId), team = get("team", str(task, "teamId"));
        if (!sender.equals("USER")) {
            teamRequest(taskId, sender, requestId);
            if (toolCallId.isBlank() || toolCallId.length() > 200) throw new ApiException(400, "团队消息缺少原工具调用身份");
        }
        if (!recipient.isBlank()) {
            Map<String, Object> target = get("agentRun", recipient);
            if (!str(team, "id").equals(str(target, "teamId"))) throw new ApiException(404, "收件人不属于此团队");
        }
        if (key.isBlank() || key.length() > 200 || content.isBlank() || content.length() > 6000) throw new ApiException(400, "团队消息编号或正文无效");
        for (Map<String, Object> old : list("teamMessage")) if (str(team, "id").equals(str(old, "teamId")) && sender.equals(str(old, "sender")) && key.equals(str(old, "key"))) {
            if (!recipient.equals(str(old, "recipient")) || !content.equals(str(old, "content"))) throw new ApiException(409, "重复消息不能改变内容");
            return old;
        }
        if (num(team, "messageSequence", 0) >= 2000) throw new ApiException(409, "本团队消息达到保留上限");
        int sequence = num(team, "messageSequence", 0) + 1; team.put("messageSequence", sequence);
        Map<String, Object> message = obj("id", id(), "teamId", team.get("id"), "taskId", taskId, "projectId", task.get("projectId"), "sessionId", task.get("sessionId"), "sequence", sequence,
            "sender", sender, "recipient", recipient, "key", key, "content", content, "requestId", requestId, "toolCallId", toolCallId, "createdAt", now());
        transaction(() -> { write("team", team); write("teamMessage", message); appendEvent(taskId, "TEAM_MESSAGE", "团队消息 #" + sequence + " · " + sender); }); return message;
    }

    public synchronized Map<String, Object> teamMessages(String taskId, long after, long before, int requestedLimit, String reader) {
        Map<String, Object> task = get("task", taskId);
        if (!reader.isBlank() && !str(task, "teamId").equals(str(get("agentRun", reader), "teamId"))) throw new ApiException(404, "读者不属于此团队");
        if (after < 0 || before < 0 || (after > 0 && before > 0)) throw new ApiException(400, "消息游标无效");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        List<Map<String, Object>> messages = list("teamMessage").stream().filter(message -> str(task, "teamId").equals(str(message, "teamId")))
            .filter(message -> reader.isBlank() || str(message, "recipient").isBlank() || reader.equals(str(message, "recipient")) || reader.equals(str(message, "sender")))
            .filter(message -> (after == 0 || num(message, "sequence", 0) > after) && (before == 0 || num(message, "sequence", 0) < before))
            .sorted(java.util.Comparator.comparingInt(message -> (after > 0 ? 1 : -1) * num(message, "sequence", 0))).limit(limit + 1L).toList();
        boolean more = messages.size() > limit; if (more) messages = messages.subList(0, limit);
        return obj("messages", messages, "cursor", messages.isEmpty() ? (after > 0 ? after : before) : messages.getLast().get("sequence"), "hasMore", more);
    }

    /**
     * 成员收件箱从序号零正向读取，避免第一轮静默跳过较早的待收消息。
     */
    public synchronized List<Map<String, Object>> teamInbox(String taskId, long after, String reader) {
        Map<String, Object> task = get("task", taskId), run = get("agentRun", reader);
        if (after < 0 || !str(task, "teamId").equals(str(run, "teamId")) || str(run, "teamId").isBlank()) throw new ApiException(404, "收件箱不属于此团队");
        if (Set.of("PROPOSAL", "REVIEW").contains(str(run, "kind"))) return List.of();
        return list("teamMessage").stream().filter(message -> str(task, "teamId").equals(str(message, "teamId")) && num(message, "sequence", 0) > after)
            .filter(message -> str(message, "recipient").isBlank() || reader.equals(str(message, "recipient")) || reader.equals(str(message, "sender")))
            .sorted(java.util.Comparator.comparingInt(message -> num(message, "sequence", 0))).limit(40).toList();
    }

    public synchronized void markOperation(String operationId, String state, Map<String, Object> details) {
        markOperation(operationId, state, details, null);
    }

    synchronized void markWorkerOperation(String operationId, String state, Map<String, Object> details, Map<String, Object> lease) {
        markOperation(operationId, state, details, lease);
    }

    private void markOperation(String operationId, String state, Map<String, Object> details, Map<String, Object> lease) {
        Map<String, Object> operation = get("operation", operationId);
        requireLeaseReceipt(operation, state, lease);
        Map<String, Object> recoveryLease = lease;
        if (lease == null && !str(operation, "workerLeaseId").isBlank()) {
            recoveryLease = get("workerLease", str(operation, "workerLeaseId"));
            if ("UNKNOWN".equals(state) && "ACTIVE".equals(str(recoveryLease, "state"))) recoveryLease.putAll(obj("state", "UNKNOWN", "stopState", "UNKNOWN", "endedAt", now()));
            if ("RECONCILED".equals(state) && "UNKNOWN".equals(str(recoveryLease, "state"))) recoveryLease.putAll(obj("state", "RECONCILED", "stopState", "USER_CONFIRMED", "reconciliation", details));
        }
        operation.put("state", state); operation.put("updatedAt", now());
        operation.putAll(details);
        Map<String, Object> persistedLease = recoveryLease;
        Map<String, Object> writeGrant = obj();
        if (!map(details, "workspaceWriteGrant").isEmpty()) {
            if (!"ACKNOWLEDGED".equals(state)) throw new ApiException(403, "现场写入锁需要原操作的完整成功回执");
            writeGrant = new WorkspaceWriteLedger(this).granted(operation, details);
        }
        Map<String, Object> persistedGrant = writeGrant;
        transaction(() -> { write("operation", operation); if (persistedLease != null) write("workerLease", persistedLease); if (!persistedGrant.isEmpty()) write("workspaceWriteLease", persistedGrant); });
    }

    private void requireLeaseReceipt(Map<String, Object> operation, String state, Map<String, Object> lease) {
        if (str(operation, "workerLeaseId").isBlank()) return;
        if (lease == null && !Set.of("UNKNOWN", "RECONCILED").contains(state)) throw new ApiException(403, "操作回执必须由原 Worker 租约确认");
        if (lease != null && (!str(operation, "workerLeaseId").equals(str(lease, "id")) || !str(operation, "id").equals(str(lease, "operationId"))
                || !str(operation, "workerId").equals(str(lease, "workerId")) || num(operation, "workerEpoch", -1) != num(lease, "epoch", -2))) throw new ApiException(403, "操作与 Worker 回执归属不匹配");
        if (!Set.of("PREPARED", "DISPATCHED").contains(str(operation, "state")) && !("UNKNOWN".equals(str(operation, "state")) && Set.of("UNKNOWN", "RECONCILED").contains(state))) throw new ApiException(409, "已结束的 Worker 操作事实不可覆盖");
    }

    /**
     * 证据只允许追加，结果和任务状态在同一事务中保存。
     */
    public synchronized void recordEvidence(String taskId, String operationId, Map<String, Object> evidence) {
        recordEvidence(taskId, operationId, evidence, null);
    }

    synchronized void recordWorkerEvidence(String taskId, String operationId, Map<String, Object> evidence, Map<String, Object> lease) {
        recordEvidence(taskId, operationId, evidence, lease);
    }

    private void recordEvidence(String taskId, String operationId, Map<String, Object> evidence, Map<String, Object> lease) {
        if (lease == null && !str(get("operation", operationId), "workerLeaseId").isBlank()) throw new ApiException(403, "证据必须来自该次 Worker 执行租约");
        Map<String, Object> task = get("task", taskId);
        List<Map<String, Object>> evidenceList = new ArrayList<>(objects(task.get("evidence")));
        evidenceList.add(evidence); task.put("evidence", evidenceList);
        if (operationId.equals(str(task, "activeOperation"))) task.remove("activeOperation"); task.put("updatedAt", now());
        Map<String, Object> operation = get("operation", operationId);
        copyTaskExecutionSource(evidence, operation);
        requireLeaseReceipt(operation, "UNKNOWN".equals(str(evidence, "status")) ? "UNKNOWN" : "ACKNOWLEDGED", lease);
        operation.put("state", "UNKNOWN".equals(str(evidence, "status")) ? "UNKNOWN" : "ACKNOWLEDGED");
        operation.put("evidenceId", evidence.get("id")); operation.put("updatedAt", now());
        Map<String, Object> run = str(operation, "agentRunId").isBlank() ? null : get("agentRun", str(operation, "agentRunId"));
        if (run != null) {
            evidence.put("agentRunId", run.get("id")); run.put("evidenceId", evidence.get("id"));
            for (String key : List.of("taskExecutionId", "dispatchActionId", "teamId", "subtaskId", "parentRunId", "definitionId", "definitionVersion", "depth")) if (run.containsKey(key)) evidence.put(key, run.get(key));
            if (lease != null) for (String key : List.of("workerLeaseId", "workerId", "executionNodeId", "repositoryBindingId", "bindingVersion", "workerEpoch")) run.put(key, evidence.get(key));
            run.put("resultVersion", evidence.getOrDefault("resultVersion", evidence.get("version"))); run.put("usage", evidence.getOrDefault("usage", obj("cost", "UNKNOWN")));
        }
        transaction(() -> {
            if (find("evidence", str(evidence, "id")) != null) { throw new IllegalStateException("证据不可覆盖"); }
            write("evidence", evidence); write("task", task); write("operation", operation);
            if (lease != null) write("workerLease", lease);
            if (run != null) write("agentRun", run);
            appendEvent(taskId, "EVIDENCE", str(evidence, "stage") + "：" + str(evidence, "status"));
        });
    }

    public synchronized void event(String taskId, String type, String message) {
        transaction(() -> appendEvent(taskId, type, message));
    }

    /**
     * 按原会话查询完整进程清单；显示截断不能减少停止门禁检查的记录。
     */
    synchronized List<Map<String, Object>> processObservations(String sessionId) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind='processObservation' AND json_extract(body,'$.sessionId')=? ORDER BY rowid DESC")) {
            query.setString(1, sessionId);
            try (var rows = query.executeQuery()) { List<Map<String, Object>> result = new ArrayList<>(); while (rows.next()) result.add(decode(rows.getString(1))); return result; }
        } catch (Exception exception) { throw failure(exception); }
    }

    /**
     * 心跳和根停止查询走租约索引，不扫描全部历史执行。
     */
    synchronized List<Map<String, Object>> workerLeases(String workerId, String taskId, String state) {
        String owner = workerId.isBlank() ? "json_extract(body,'$.taskId')=?" : "json_extract(body,'$.workerId')=?";
        boolean scopedTask = !workerId.isBlank() && !taskId.isBlank();
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind='workerLease' AND " + owner + (scopedTask ? " AND json_extract(body,'$.taskId')=?" : "") + (state.isBlank() ? "" : " AND json_extract(body,'$.state')=?") + " ORDER BY rowid DESC")) {
            int index = 1; query.setString(index++, workerId.isBlank() ? taskId : workerId); if (scopedTask) query.setString(index++, taskId); if (!state.isBlank()) query.setString(index, state);
            try (var rows = query.executeQuery()) { List<Map<String, Object>> leases = new ArrayList<>(); while (rows.next()) leases.add(decode(rows.getString(1))); return leases; }
        } catch (Exception exception) { throw failure(exception); }
    }

    synchronized Map<String, Object> workerLeaseForOperation(String kind, String operationId) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind='workerLease' AND json_extract(body,'$.operationKind')=? AND json_extract(body,'$.operationId')=?")) {
            query.setString(1, kind); query.setString(2, operationId); try (var rows = query.executeQuery()) { return rows.next() ? decode(rows.getString(1)) : null; }
        } catch (Exception exception) { throw failure(exception); }
    }

    /**
     * 同一规范目录的未释放归属通过索引查询，UNKNOWN 核对前仍阻止新根领取。
     */
    synchronized Map<String, Object> protectedWorkspaceWriteLease(String scopeKey) {
        try (var query = connection.prepareStatement("SELECT body FROM entities WHERE kind='workspaceWriteLease' AND json_extract(body,'$.scopeKey')=? AND json_extract(body,'$.protectionReleased')=0 LIMIT 1")) {
            query.setString(1, scopeKey); try (var rows = query.executeQuery()) { return rows.next() ? decode(rows.getString(1)) : null; }
        } catch (Exception exception) { throw failure(exception); }
    }

    synchronized Map<String, Object> workerLeasePage(String taskId, long before, int requestedLimit, String state) {
        get("task", taskId);
        if (before < 0 || (!state.isBlank() && !Set.of("ACTIVE", "RELEASED", "UNKNOWN", "RECONCILED").contains(state))) throw new ApiException(400, "Worker 租约游标或状态无效");
        int limit = Math.max(1, Math.min(100, requestedLimit));
        try (var query = connection.prepareStatement("SELECT rowid,body FROM entities WHERE kind='workerLease' AND json_extract(body,'$.taskId')=?"
                + (before > 0 ? " AND rowid<?" : "") + (state.isBlank() ? "" : " AND json_extract(body,'$.state')=?") + " ORDER BY rowid DESC LIMIT ?")) {
            int index = 1; query.setString(index++, taskId); if (before > 0) query.setLong(index++, before); if (!state.isBlank()) query.setString(index++, state); query.setInt(index, limit + 1);
            List<Map<String, Object>> leases = new ArrayList<>(); long cursor = before; boolean more = false;
            try (var rows = query.executeQuery()) {
                while (rows.next()) {
                    if (leases.size() == limit) { more = true; break; }
                    cursor = rows.getLong(1); Map<String, Object> lease = WorkerLedger.publicLease(decode(rows.getString(2))); lease.put("sequence", cursor);
                    Map<String, Object> operation = get(str(lease, "operationKind"), str(lease, "operationId"));
                    lease.put("kind", operation.get("kind")); lease.put("operationState", operation.get("state")); lease.put("evidenceId", operation.getOrDefault("evidenceId", "")); leases.add(lease);
                }
            }
            return obj("leases", leases, "cursor", cursor, "hasMore", more);
        } catch (Exception exception) { throw failure(exception); }
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

    /**
     * 根尝试来源从原任务或父运行传递；历史记录没有来源时不推测补造。
     */
    static void copyTaskExecutionSource(Map<String, Object> target, Map<String, Object> source) {
        for (String key : List.of("taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId")) if (!str(source, key).isBlank()) target.put(key, source.get(key));
    }

    /**
     * 实际操作只能属于本次核心已领取的原根尝试，历史无来源记录继续使用原门禁。
     */
    synchronized void requireActiveTaskExecution(Map<String, Object> task, Map<String, Object> source) {
        if (str(task, "taskExecutionId").isBlank()) return;
        if (!str(task, "taskExecutionId").equals(str(source, "taskExecutionId")) || !str(task, "dispatchActionId").equals(str(source, "dispatchActionId"))) throw new ApiException(409, "操作缺少当前根执行来源");
        if (!str(task, "workspaceWriteLeaseId").isBlank() && !str(task, "workspaceWriteLeaseId").equals(str(source, "workspaceWriteLeaseId"))) throw new ApiException(409, "操作缺少原根现场写入归属");
        Map<String, Object> execution = get("taskExecution", str(source, "taskExecutionId")), action = get("dispatchAction", str(source, "dispatchActionId"));
        if (!"RUNNING".equals(str(execution, "state")) || !"CLAIMED".equals(str(action, "state")) || num(action, "dispatchCount", 0) != 1
                || !str(task, "id").equals(str(execution, "taskId")) || !str(execution, "id").equals(str(action, "taskExecutionId")) || !str(action, "id").equals(str(execution, "dispatchActionId"))
                || !coreSessionId.equals(str(action, "ownerCoreSessionId")) || !coreSessionId.equals(str(execution, "ownerCoreSessionId"))) throw new ApiException(409, "原根尝试尚未领取、已结束或不属于本次核心");
        if (!map(task, "workspaceSelection").equals(map(execution, "workspaceSelection"))) throw new ApiException(409, "启动选择已变化，旧根尝试不能继续派发");
    }

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
        healthy = false;
        try { connection.close(); }
        finally {
            try { if (ownershipLock.isValid()) { ownershipLock.release(); } }
            finally { ownershipChannel.close(); }
        }
    }

    @FunctionalInterface
    private interface SqlAction { void run() throws Exception; }
}
