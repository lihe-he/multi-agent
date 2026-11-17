package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 终端控制先保存原意图与派发事实，输入摘要留存，未知回执不重发。
 */
final class TerminalLedger {
    static final int MAX_INPUT_BYTES = 16_384;
    static final long MAX_ACTIONS = 32_768;
    private final ExecutionStore store;
    private final TerminalOutputArchive output;
    private final SecureRandom random = new SecureRandom();
    private final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.locks.ReentrantLock> actionGates = new java.util.concurrent.ConcurrentHashMap<>();

    TerminalLedger(ExecutionStore store) { this.store = store; output = new TerminalOutputArchive(new ObjectMapper(), store.directory()); }

    Map<String, Object> newSession(Map<String, Object> task, String recipe, int columns, int rows) {
        dimensions(columns, rows);
        if (!Set.of("SYSTEM_CMD", "SYSTEM_SH").contains(recipe)) throw new ApiException(400, "终端启动方案无效");
        return obj("id", id(), "protocolVersion", 1, "taskId", task.get("id"), "projectId", task.get("projectId"),
            "sessionId", task.getOrDefault("sessionId", ""), "workspace", task.get("workspace"), "workspaceOwnership", snapshot(map(task, "workspaceOwnership")),
            "execution", snapshot(map(task, "execution")), "recipe", recipe, "columns", columns, "rows", rows, "epoch", id(), "state", "QUEUED",
            "versionBeforeManualActivity", task.getOrDefault("version", ""), "verificationBeforeManualActivity", snapshot(map(task, "verification")), "reviewBeforeManualActivity", snapshot(map(task, "review")),
            "createdAt", now(), "nativeLaunchAttempted", false, "controllerGeneration", 0L, "lastActionSequence", 0L,
            "outputEnd", 0L, "outputSegments", 0L, "outputLimit", TerminalOutputArchive.MAX_OUTPUT_BYTES, "outputIntegrity", "PENDING", "inputFenced", false);
    }

    /**
     * 绑定本次实际 Worker 领取，不创建第二次操作或改变原根来源。
     */
    void bind(String terminalId, Map<String, Object> lease) {
        synchronized (store) {
            Map<String, Object> terminal = store.get("terminalSession", terminalId), task = store.get("task", str(terminal, "taskId"));
            if (!"QUEUED".equals(str(terminal, "state")) || !terminalId.equals(str(task, "pendingTerminalId"))) throw new ApiException(409, "原终端尚未处于待启动边界");
            if (!"TERMINAL".equals(str(lease, "operationType")) || !str(task, "id").equals(str(lease, "taskId"))
                    || !ExecutionNodes.samePath(str(terminal, "workspace"), str(lease, "workingDirectory"))) throw new ApiException(403, "终端不属于原任务现场领取");
            store.requireActiveTaskExecution(task, lease);
            Map<String, Object> source = new LinkedHashMap<>();
            for (String field : List.of("id", "operationId", "taskExecutionId", "dispatchActionId", "workspaceWriteLeaseId", "workerId", "epoch", "executorPid", "processStartedAt", "executionNodeId", "repositoryBindingId", "bindingVersion", "authorizationVersion"))
                source.put("id".equals(field) ? "workerLeaseId" : "epoch".equals(field) ? "workerEpoch" : field, lease.get(field));
            terminal.putAll(obj("state", "STARTING", "source", source, "coreSessionId", store.coreSessionId(), "startedAt", now()));
            store.put("terminalSession", terminal);
        }
    }

    void nativeLaunch(String terminalId) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            if (!"STARTING".equals(str(terminal, "state")) || bool(terminal, "nativeLaunchAttempted", false)) throw new ApiException(409, "原终端不能重复启动");
            terminal.put("nativeLaunchAttempted", true); store.put("terminalSession", terminal);
        }
    }

    void started(String terminalId, Map<String, Object> facts) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            if (!"STARTING".equals(str(terminal, "state")) || !bool(terminal, "nativeLaunchAttempted", false)) throw new ApiException(409, "终端缺少原启动派发事实");
            if (number(facts, "pid") < 1 || str(facts, "processStartedAt").isBlank() || str(facts, "backend").isBlank()) throw new ApiException(409, "原终端进程身份不完整");
            Instant.parse(str(facts, "processStartedAt"));
            terminal.putAll(obj("state", "RUNNING", "nativeProcess", snapshot(facts), "outputIntegrity", "LIVE", "runningAt", now()));
            store.commitRecords(Map.of("terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_STARTED", "原终端进程已取得身份；输出持久保存，关闭客户端保留此进程");
        }
    }

    Map<String, Object> acquire(String terminalId, Map<String, Object> body) {
        if (!body.keySet().equals(Set.of("expectedGeneration", "controllerId")) || !(body.get("controllerId") instanceof String)) throw new ApiException(400, "终端控制只接受原代数和控制者编号");
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            if (!"RUNNING".equals(str(terminal, "state")) || bool(terminal, "inputFenced", false)) throw new ApiException(409, "当前终端不能授予输入控制");
            if (number(body, "expectedGeneration") != number(terminal, "controllerGeneration")) throw new ApiException(409, "终端控制归属已变化，请刷新");
            requireId(str(body, "controllerId")); requireNoPending(terminalId);
            byte[] secretBytes = new byte[32]; random.nextBytes(secretBytes);
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(secretBytes);
            long previous = number(terminal, "controllerGeneration");
            if (previous >= 9_007_199_254_740_991L) throw new ApiException(409, "终端控制代数已达到客户端精确整数边界");
            long generation = previous + 1;
            terminal.putAll(obj("controllerId", body.get("controllerId"), "controllerGeneration", generation, "controllerDigest", digest(secret.getBytes(StandardCharsets.US_ASCII)), "controllerAcquiredAt", now()));
            store.commitRecords(Map.of("terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_CONTROLLER", "用户取得第 " + generation + " 代终端控制；其他查看者不发送输入或调整尺寸");
            return obj("terminal", visible(terminal), "controlSecret", secret, "generation", generation, "nextSequence", number(terminal, "lastActionSequence") + 1);
        }
    }

    /**
     * 原输入字节只随本次内存派发传递，SQLite 留编号、长度、摘要及归属。
     */
    Prepared prepare(String terminalId, Map<String, Object> body) {
        String requestedKind = str(body, "kind");
        Set<String> fields = new java.util.HashSet<>(Set.of("requestId", "kind", "sequence", "controllerId", "generation", "controlSecret"));
        if ("INPUT".equals(requestedKind)) fields.add("dataBase64");
        if ("RESIZE".equals(requestedKind)) fields.addAll(Set.of("columns", "rows"));
        if (!body.keySet().equals(fields) || !List.of("requestId", "kind", "controllerId", "controlSecret").stream().allMatch(field -> body.get(field) instanceof String)
                || ("INPUT".equals(requestedKind) && !(body.get("dataBase64") instanceof String))) throw new ApiException(400, "终端动作只接受原编号、控制凭据和该动作必要字段");
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            authenticate(terminal, body); requireId(str(body, "requestId"));
            String kind = str(body, "kind"); byte[] data = new byte[0];
            int columns = 0, rows = 0;
            switch (kind) {
                case "INPUT" -> {
                    String encoded = str(body, "dataBase64");
                    if (encoded.length() > (MAX_INPUT_BYTES + 2) / 3 * 4) throw new ApiException(413, "单次输入过长，未派发到终端");
                    try { data = Base64.getDecoder().decode(encoded); }
                    catch (IllegalArgumentException exception) { throw new ApiException(400, "终端输入编码无效"); }
                    if (data.length < 1 || data.length > MAX_INPUT_BYTES) throw new ApiException(413, "单次输入长度无效，未派发到终端");
                }
                case "RESIZE" -> {
                    long requestedColumns = number(body, "columns"), requestedRows = number(body, "rows");
                    if (requestedColumns < 2 || requestedColumns > 500 || requestedRows < 1 || requestedRows > 300) throw new ApiException(400, "终端尺寸超出允许范围");
                    columns = (int) requestedColumns; rows = (int) requestedRows;
                }
                case "STOP" -> { }
                default -> throw new ApiException(400, "终端控制动作无效");
            }
            long sequence = number(body, "sequence");
            String actionId = terminalId + ":" + str(body, "requestId");
            Map<String, Object> facts = obj("terminalId", terminalId, "requestId", body.get("requestId"), "kind", kind, "sequence", sequence,
                "controllerId", body.get("controllerId"), "controllerGeneration", number(body, "generation"), "length", data.length,
                "sha256", digest(kind.equals("INPUT") ? data : (kind + ":" + columns + ":" + rows).getBytes(StandardCharsets.US_ASCII)), "columns", columns, "rows", rows);
            Map<String, Object> original = store.find("terminalAction", actionId);
            if (original != null) {
                for (String field : facts.keySet()) if (!equalFact(original.get(field), facts.get(field))) throw new ApiException(409, "原终端动作编号与本次事实冲突，未再次派发");
                return new Prepared(original, new byte[0], false);
            }
            if (!"RUNNING".equals(str(terminal, "state")) || bool(terminal, "inputFenced", false)) throw new ApiException(409, "原终端输入已停止或需核对");
            requireNoPending(terminalId);
            if (sequence != number(terminal, "lastActionSequence") + 1 || sequence > MAX_ACTIONS) throw new ApiException(409, "终端动作顺序或数量越过边界，未派发");
            Map<String, Object> action = new LinkedHashMap<>(facts);
            action.putAll(obj("id", actionId, "protocolVersion", 1, "taskId", terminal.get("taskId"), "state", "INTENT", "dispatchCount", 0, "nativeAuthorizationCount", 0,
                "createdAt", now(), "source", terminal.get("source")));
            terminal.put("lastActionSequence", sequence);
            store.commitRecords(Map.of("terminalAction", List.of(action), "terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_ACTION_INTENT", "保存原终端 " + kind + " 动作 " + sequence + "，输入字节不写入动作历史");
            return new Prepared(action, data, true);
        }
    }

    Map<String, Object> dispatched(String terminalId, String requestId) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId), action = store.get("terminalAction", terminalId + ":" + requestId);
            if (!"RUNNING".equals(str(terminal, "state")) || bool(terminal, "inputFenced", false)
                    || !"INTENT".equals(str(action, "state")) || number(action, "dispatchCount") != 0
                    || number(action, "sequence") != number(terminal, "lastActionSequence")
                    || number(action, "controllerGeneration") != number(terminal, "controllerGeneration")) throw new ApiException(409, "原终端动作不能重复派发");
            action.putAll(obj("state", "DISPATCHED", "dispatchCount", 1, "dispatchedAt", now()));
            store.put("terminalAction", action); return action;
        }
    }

    Map<String, Object> authorize(String terminalId, Map<String, Object> request) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId), action = store.get("terminalAction", terminalId + ":" + str(request, "requestId"));
            if (!"RUNNING".equals(str(terminal, "state")) || bool(terminal, "inputFenced", false) || !"DISPATCHED".equals(str(action, "state"))
                    || number(action, "dispatchCount") != 1 || number(action, "nativeAuthorizationCount") != 0
                    || number(terminal, "controllerGeneration") != number(action, "controllerGeneration")) throw new ApiException(409, "终端原动作或控制归属已失效");
            for (String field : List.of("terminalId", "requestId", "kind", "sequence", "sha256", "length", "columns", "rows", "controllerId", "controllerGeneration"))
                if (!equalFact(action.get(field), request.get(field))) throw new ApiException(409, "终端原动作事实与本次原生输入请求冲突");
            action.putAll(obj("nativeAuthorizationCount", 1, "nativeAuthorizedAt", now()));
            store.commitRecords(Map.of("terminalAction", List.of(action)), str(terminal, "taskId"), "TERMINAL_ACTION_AUTHORIZED", "原终端动作 " + action.get("sequence") + " 已授予一次原生输入许可，重复回调不再授予");
            return action;
        }
    }

    Map<String, Object> finishAction(String terminalId, String requestId, String state) {
        return finishAction(terminalId, requestId, state, obj());
    }

    Map<String, Object> finishAction(String terminalId, String requestId, String state, Map<String, Object> receipt) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId), action = store.get("terminalAction", terminalId + ":" + requestId);
            if (!Set.of("ACKNOWLEDGED", "REJECTED", "UNKNOWN").contains(state) || !"DISPATCHED".equals(str(action, "state"))) throw new ApiException(409, "终端原动作缺少可确认的派发边界");
            if (("ACKNOWLEDGED".equals(state) && number(action, "nativeAuthorizationCount") != 1)
                    || ("REJECTED".equals(state) && number(action, "nativeAuthorizationCount") != 0)) throw new ApiException(409, "终端回执与原生输入授权边界不一致，已授权但缺少确认的动作只能保留未知");
            action.putAll(obj("state", state, "endedAt", now()));
            if (!receipt.isEmpty()) action.put("nativeReceipt", snapshot(receipt));
            if ("UNKNOWN".equals(state)) terminal.putAll(obj("inputFenced", true, "inputUnconfirmed", true));
            if ("ACKNOWLEDGED".equals(state) && "RESIZE".equals(str(action, "kind"))) terminal.putAll(obj("columns", action.get("columns"), "rows", action.get("rows")));
            if ("ACKNOWLEDGED".equals(state) && "STOP".equals(str(action, "kind"))) terminal.putAll(obj("state", "STOPPING", "inputFenced", true));
            store.commitRecords(Map.of("terminalAction", List.of(action), "terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_ACTION_RESULT", "原终端动作 " + action.get("sequence") + "：" + state + "；未知输入不重发");
            return action;
        }
    }

    ActionGate actionGate(String terminalId) {
        java.util.concurrent.locks.ReentrantLock lock = actionGates.computeIfAbsent(terminalId, ignored -> new java.util.concurrent.locks.ReentrantLock());
        lock.lock(); return new ActionGate(lock);
    }

    /**
     * 响应丢失后只读取原动作，无需控制密钥，也不触发输入派发。
     */
    Map<String, Object> observation(String terminalId, String requestId) {
        requireId(requestId);
        synchronized (store) {
            Map<String, Object> terminal = store.get("terminalSession", terminalId); getTask(terminal);
            Map<String, Object> action = store.get("terminalAction", terminalId + ":" + requestId);
            if (!terminalId.equals(str(action, "terminalId")) || !str(terminal, "taskId").equals(str(action, "taskId"))) throw new ApiException(409, "原动作记录不属于所查询的终端");
            return action;
        }
    }

    /**
     * 原生进程和输出收尾与输入回执分开核对，人工活动不形成验证通过事实。
     */
    Map<String, Object> completed(String terminalId, Map<String, Object> result, String version) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId), task = store.get("task", str(terminal, "taskId"));
            boolean nativeSettled = bool(result, "originalProcessStopped", false) && bool(result, "observedChildrenStopped", false)
                && bool(result, "outputReaderCompleted", false) && bool(result, "nativeCleanupThreadCompleted", false) && bool(result, "ownedStreamsClosed", false);
            boolean complete = nativeSettled && bool(result, "startupAcknowledged", false) && bool(terminal, "outputEnded", false)
                && "COMPLETE".equals(str(terminal, "outputIntegrity")) && !store.terminalHasUnsettledAction(terminalId)
                && !bool(terminal, "inputUnconfirmed", false) && Set.of("EXITED", "CANCELLED").contains(str(result, "status"));
            String state = complete ? str(result, "status") : "UNKNOWN";
            terminal.putAll(obj("state", state, "inputFenced", true, "result", snapshot(result), "versionAfterManualActivity", version, "endedAt", now()));
            if (!complete) terminal.put("outputIntegrity", "COMPLETE".equals(str(terminal, "outputIntegrity")) ? "COMPLETE_WITH_UNCONFIRMED_INPUT_OR_NATIVE_RESULT" : "UNCONFIRMED");
            boolean taskCancelled = complete && Set.of("CANCELLING", "CANCELLED").contains(str(task, "state"));
            task.putAll(obj("state", complete ? taskCancelled ? "CANCELLED" : "PAUSED" : "UNKNOWN", "version", version, "verification", obj("status", "NOT_RUN"), "review", obj("status", "NOT_RUN"), "lastTerminalId", terminalId));
            if (taskCancelled) { task.put("outcome", "CANCELLED"); terminal.put("taskStopDisposition", "CANCELLED"); }
            if (complete) { task.remove("pendingTerminalId"); task.remove("activeOperation"); task.remove("error"); }
            else task.put("error", "原终端进程、输入或输出缺少完整回执；保留历史和累计预算，需核对原效果");
            for (Map<String, Object> stage : objects(task.get("plan"))) if (Set.of("verify", "review", "delivery").contains(str(stage, "id"))) stage.put("state", "PENDING");
            store.commitRecords(Map.of("terminalSession", List.of(terminal), "task", List.of(task)), str(task, "id"), "TERMINAL_FINISHED", "原终端 " + state + "；人工活动后等待重新验证与独立审查");
            return visible(terminal);
        }
    }

    void recover() {
        synchronized (store) {
            for (Map<String, Object> action : store.list("terminalAction")) {
                if ("DISPATCHED".equals(str(action, "state"))) action.putAll(obj("state", "UNKNOWN", "lostAt", now(), "reason", "核心重启，原输入派发回执缺失，不重发"));
                else if ("INTENT".equals(str(action, "state"))) action.putAll(obj("state", "WITHDRAWN", "endedAt", now(), "reason", "原输入尚无派发事实，重启后不自动发送"));
                else continue;
                store.put("terminalAction", action);
            }
            for (Map<String, Object> terminal : store.list("terminalSession")) if (Set.of("STARTING", "RUNNING", "STOPPING").contains(str(terminal, "state"))) {
                terminal.putAll(obj("state", "UNKNOWN", "inputFenced", true, "outputIntegrity", "UNCONFIRMED", "lostAt", now(), "reason", "核心重启，原终端进程及输出需核对，不重建 shell 或重发输入"));
                store.put("terminalSession", terminal);
            }
        }
    }

    Map<String, Object> outputContext(String terminalId) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            return obj("terminalId", terminalId, "epoch", terminal.get("epoch"), "owner", outputOwner(terminal), "outputLimit", terminal.get("outputLimit"));
        }
    }

    /**
     * 原始字节已完整落盘且来源、游标一致后，才同事务发布引用和终端尾游标。
     */
    Map<String, Object> publishOutput(String terminalId, Map<String, Object> descriptor) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId);
            if (!Set.of("STARTING", "RUNNING", "STOPPING").contains(str(terminal, "state")) || bool(terminal, "outputEnded", false) || bool(terminal, "outputFenced", false)) throw new ApiException(409, "终端输出已收尾或封闭，不能发布新片段");
            Map<String, Object> reference = segmentReference(descriptor);
            if (!terminalId.equals(str(reference, "terminalId")) || !str(terminal, "epoch").equals(str(reference, "epoch"))) {
                fenceOutput(terminal, "输出不属于原终端 epoch"); throw new ApiException(409, "输出不属于原终端 epoch");
            }
            try { requireOutputOwner(terminal); output.read(terminalId, str(terminal, "epoch"), reference); }
            catch (RuntimeException exception) { fenceOutput(terminal, "原输出字节或 Worker 来源未完整核验"); throw new ApiException(409, "原输出字节或 Worker 来源未完整核验"); }
            String recordId = terminalId + ":" + str(terminal, "epoch") + ":" + number(reference, "offset");
            Map<String, Object> existing = store.find("terminalOutputSegment", recordId);
            if (existing != null) {
                if (!segmentReference(existing).equals(reference)) { fenceOutput(terminal, "原输出游标与新引用冲突"); throw new ApiException(409, "原输出游标与新引用冲突"); }
                return existing;
            }
            if (number(reference, "offset") != number(terminal, "outputEnd") || number(reference, "next") > number(terminal, "outputLimit")) {
                fenceOutput(terminal, "终端输出游标缺失、重叠或超过保存边界"); throw new ApiException(409, "终端输出游标缺失、重叠或超过保存边界");
            }
            Map<String, Object> record = new LinkedHashMap<>(reference);
            record.putAll(obj("id", recordId, "taskId", terminal.get("taskId"), "publishedAt", now(), "source", snapshot(map(terminal, "source"))));
            terminal.putAll(obj("outputEnd", reference.get("next"), "outputSegments", number(terminal, "outputSegments") + 1, "outputIntegrity", "LIVE"));
            store.commitRecords(Map.of("terminalOutputSegment", List.of(record), "terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_OUTPUT", "原终端输出保存到字节 " + reference.get("next"));
            return store.get("terminalOutputSegment", recordId);
        }
    }

    Map<String, Object> finishOutput(String terminalId, Map<String, Object> end) {
        synchronized (store) {
            Map<String, Object> terminal = current(terminalId); requireOutputOwner(terminal);
            if (!terminalId.equals(str(end, "terminalId")) || !str(terminal, "epoch").equals(str(end, "epoch"))) throw new ApiException(409, "结束描述不属于原终端输出");
            if (bool(terminal, "outputEnded", false)) {
                if (!endReference(map(terminal, "outputEndObservation")).equals(endReference(end))) throw new ApiException(409, "原终端输出结束描述不能改写");
                return visible(terminal);
            }
            Map<String, Object> inspection = output.inspect(terminalId, str(terminal, "epoch"));
            boolean complete = bool(inspection, "complete", false) && endReference(map(inspection, "end")).equals(endReference(end))
                && number(end, "bytes") == number(terminal, "outputEnd") && number(end, "segments") == number(terminal, "outputSegments");
            terminal.putAll(obj("state", "STOPPING", "inputFenced", true, "outputEnded", true, "outputEndObservation", snapshot(end), "outputIntegrity", complete ? "COMPLETE" : "UNCONFIRMED", "outputEndedAt", now()));
            if (!complete) terminal.putAll(obj("inputFenced", true, "outputGap", "原落盘输出与已发布游标或结束描述不完整，保留所有片段供核对"));
            store.commitRecords(Map.of("terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_OUTPUT_END", complete ? "原终端输出与全部发布游标完整核对" : "原终端输出含未确认边界，未报告完整 transcript");
            return visible(terminal);
        }
    }

    /**
     * 查看者只读取原发布字节；每帧可以截断片段，回传摘要和长游标供渲染排空后确认。
     */
    Map<String, Object> outputPage(String terminalId, String epoch, long after, int limit) {
        synchronized (store) {
            Map<String, Object> terminal = store.get("terminalSession", terminalId); getTask(terminal);
            if (!str(terminal, "epoch").equals(epoch) || after < 0 || after > number(terminal, "outputEnd")) throw new ApiException(409, "终端 epoch 或字节游标不属于原发布范围");
            if (limit < 1 || limit > TerminalOutputArchive.MAX_SEGMENT_BYTES) throw new ApiException(400, "终端输出帧长度无效");
            long end = number(terminal, "outputEnd"); byte[] frame = new byte[0]; Map<String, Object> original = obj();
            if (after < end) {
                Map<String, Object> record = store.terminalOutputAt(terminalId, epoch, after);
                if (record.isEmpty()) throw new ApiException(409, "原终端输出引用有缺口，未跳过缺失字节");
                original = segmentReference(record); requireOutputOwner(terminal);
                byte[] bytes;
                try { bytes = output.read(terminalId, epoch, original); }
                catch (RuntimeException exception) { throw new ApiException(409, "原终端输出字节缺失或损坏，游标未推进"); }
                int offset = Math.toIntExact(after - number(original, "offset"));
                frame = java.util.Arrays.copyOfRange(bytes, offset, Math.min(bytes.length, offset + limit));
            }
            long next = after + frame.length;
            return obj("terminalId", terminalId, "epoch", epoch, "offset", after, "length", frame.length, "next", next, "sha256", digest(frame),
                "dataBase64", Base64.getEncoder().encodeToString(frame), "publishedEnd", end, "hasMore", next < end,
                "terminalState", terminal.get("state"), "outputIntegrity", terminal.get("outputIntegrity"), "originalSegment", original);
        }
    }

    private void getTask(Map<String, Object> terminal) { store.get("task", str(terminal, "taskId")); }

    private void requireOutputOwner(Map<String, Object> terminal) {
        Map<String, Object> owner = output.ownership(str(terminal, "id"), str(terminal, "epoch"));
        if (!snapshot(map(owner, "owner")).equals(outputOwner(terminal))) throw new ApiException(409, "原输出文件来源与本次终端 Worker 领取不一致");
    }

    private static Map<String, Object> outputOwner(Map<String, Object> terminal) {
        return obj("terminalId", terminal.get("id"), "taskId", terminal.get("taskId"), "coreSessionId", terminal.get("coreSessionId"), "source", snapshot(map(terminal, "source")));
    }

    private static Map<String, Object> segmentReference(Map<String, Object> descriptor) {
        Map<String, Object> reference = new LinkedHashMap<>();
        for (String field : List.of("protocolVersion", "terminalId", "epoch", "offset", "length", "next", "sha256")) reference.put(field, descriptor.get(field));
        for (String field : List.of("protocolVersion", "offset", "length", "next")) reference.put(field, number(reference, field));
        return reference;
    }

    private static Map<String, Object> endReference(Map<String, Object> end) {
        Map<String, Object> result = snapshot(end);
        for (String field : List.of("protocolVersion", "bytes", "segments")) result.put(field, number(end, field));
        return result;
    }

    private void fenceOutput(Map<String, Object> terminal, String reason) {
        terminal.putAll(obj("inputFenced", true, "outputFenced", true, "outputIntegrity", "UNCONFIRMED", "outputGap", reason, "outputFencedAt", now()));
        store.commitRecords(Map.of("terminalSession", List.of(terminal)), str(terminal, "taskId"), "TERMINAL_OUTPUT_UNCONFIRMED", reason + "；停止新增输入并保留原输出");
    }

    private Map<String, Object> current(String terminalId) {
        if (!store.healthy()) throw new ApiException(503, "核心存储不可写，终端停止新增控制");
        Map<String, Object> terminal = store.get("terminalSession", terminalId);
        if (!store.coreSessionId().equals(str(terminal, "coreSessionId"))) throw new ApiException(409, "终端不属于当前核心原会话，需核对旧进程");
        Map<String, Object> task = store.get("task", str(terminal, "taskId"));
        if (!terminalId.equals(str(task, "pendingTerminalId"))) throw new ApiException(409, "原任务不再持有此终端");
        Map<String, Object> source = map(terminal, "source");
        store.requireActiveTaskExecution(task, source);
        if (!map(task, "execution").equals(map(terminal, "execution")) || !map(task, "workspaceOwnership").equals(map(terminal, "workspaceOwnership"))
                || !ExecutionNodes.samePath(str(task, "workspace"), str(terminal, "workspace"))) throw new ApiException(409, "原终端现场或授权已变化");
        return terminal;
    }

    private void requireNoPending(String terminalId) {
        if (store.terminalHasUnsettledAction(terminalId)) throw new ApiException(409, "前一终端动作尚未核对，不能发送新输入或转交控制");
    }

    private static void authenticate(Map<String, Object> terminal, Map<String, Object> body) {
        String secret = str(body, "controlSecret");
        if (secret.length() != 43 || number(body, "generation") != number(terminal, "controllerGeneration") || !str(terminal, "controllerId").equals(str(body, "controllerId"))
                || !MessageDigest.isEqual(digest(secret.getBytes(StandardCharsets.US_ASCII)).getBytes(StandardCharsets.US_ASCII), str(terminal, "controllerDigest").getBytes(StandardCharsets.US_ASCII))) throw new ApiException(403, "终端控制凭据或代数已失效");
    }

    static Map<String, Object> visible(Map<String, Object> terminal) { Map<String, Object> value = new LinkedHashMap<>(terminal); value.remove("controllerDigest"); return value; }
    static void dimensions(int columns, int rows) { if (columns < 2 || columns > 500 || rows < 1 || rows > 300) throw new ApiException(400, "终端尺寸超出允许范围"); }
    private static void requireId(String value) { if (!value.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) throw new ApiException(400, "终端动作或控制者编号无效"); }
    private static long number(Map<String, Object> value, String key) { Object number = value.get(key); if (!(number instanceof Integer || number instanceof Long)) throw new ApiException(400, "终端顺序与代数必须为整数"); return ((Number) number).longValue(); }
    private static boolean equalFact(Object left, Object right) { return left instanceof Number a && right instanceof Number b ? a.longValue() == b.longValue() : java.util.Objects.equals(left, right); }
    private static Map<String, Object> snapshot(Map<String, Object> value) {
        Map<String, Object> result = new LinkedHashMap<>(); value.forEach((key, item) -> result.put(key, snapshotValue(item))); return result;
    }
    private static Object snapshotValue(Object value) {
        if (value instanceof Map<?, ?>) return snapshot(map(value));
        if (value instanceof List<?> list) return list.stream().map(TerminalLedger::snapshotValue).toList();
        return value;
    }
    private static String digest(byte[] bytes) { try { return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); } catch (Exception exception) { throw new IllegalStateException("终端输入摘要无法计算", exception); } }

    record Prepared(Map<String, Object> observation, byte[] input, boolean dispatch) {
        Prepared { observation = Map.copyOf(observation); input = input.clone(); }
        @Override public byte[] input() { return input.clone(); }
    }

    record ActionGate(java.util.concurrent.locks.ReentrantLock lock) implements AutoCloseable {
        @Override public void close() { lock.unlock(); }
    }
}
