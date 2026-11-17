package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.runtime.TerminalOutputArchive;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 真实 SQLite 原根领取下验证控制意图；此夹具不启动原生终端进程。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class TerminalLedgerTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path data;
    private ExecutionStore store;
    private TerminalLedger ledger;
    private String taskId, terminalId;
    private Map<String, Object> controller;

    @BeforeEach
    void open() throws Exception {
        store = new ExecutionStore(new ObjectMapper(), data.toString()); ledger = new TerminalLedger(store); taskId = id();
        Path work = Files.createDirectories(data.resolve("workspaces").resolve(taskId).resolve("work")).toRealPath();
        store.put("task", obj("id", taskId, "projectId", "terminal-ledger-fixture", "sessionId", "fixture-session", "state", "PAUSED",
            "workspace", work.toString(), "workspaceOwnership", obj("owner", "original-owned-workspace"),
            "execution", obj("version", 1, "executionNodeId", "local", "bindingVersion", 1),
            "contract", obj("confirmed", true, "version", 1), "budget", obj("usedCalls", 7, "maxCalls", 20)));
        Map<String, Object> terminal = ledger.newSession(store.get("task", taskId), "SYSTEM_CMD", 80, 24); terminalId = str(terminal, "id");
        TaskDispatchQueue queue = new TaskDispatchQueue(store);
        Map<String, Object> action = queue.enqueue(taskId, "USER_TERMINAL", task -> task.put("pendingTerminalId", terminalId), Map.of("terminalSession", List.of(terminal)));
        Map<String, Object> execution = queue.claim(str(action, "id"), 1, ignored -> {}); assertFalse(execution.isEmpty());
        store.editTask(taskId, task -> task.put("state", "RUNNING"), null, "");
        Map<String, Object> lease = obj("id", id(), "operationType", "TERMINAL", "operationId", id(), "taskId", taskId,
            "state", "ACTIVE", "taskExecutionId", execution.get("id"), "dispatchActionId", action.get("id"), "workspaceWriteLeaseId", "",
            "workerId", "original-ledger-fixture-worker", "epoch", 1, "workingDirectory", work.toString(),
            "executorPid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString(),
            "executionNodeId", "local", "repositoryBindingId", "fixture-binding", "bindingVersion", 1, "authorizationVersion", 1);
        store.put("workerLease", lease); ledger.bind(terminalId, lease); ledger.nativeLaunch(terminalId);
        ledger.started(terminalId, obj("pid", ProcessHandle.current().pid(), "processStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString(),
            "backend", "SQLITE_LEDGER_FIXTURE_WITHOUT_PTY"));
        controller = ledger.acquire(terminalId, obj("expectedGeneration", 0, "controllerId", id()));
    }

    @AfterEach
    void close() throws Exception { if (store != null) store.close(); }

    private Map<String, Object> input(String text, long sequence) {
        Map<String, Object> terminal = map(controller, "terminal");
        return obj("requestId", id(), "kind", "INPUT", "sequence", sequence, "controllerId", terminal.get("controllerId"),
            "generation", controller.get("generation"), "controlSecret", controller.get("controlSecret"),
            "dataBase64", Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8)));
    }

    private Map<String, Object> dispatch(Map<String, Object> body) {
        assertTrue(ledger.prepare(terminalId, body).dispatch()); return ledger.dispatched(terminalId, str(body, "requestId"));
    }

    @Test
    void lostResponseQueriesOnlyTheOriginalUnknownActionWithoutInputOrControlSecrets() {
        Map<String, Object> body = input("QUERY_ONLY_PRIVATE_INPUT\r", 1), action = dispatch(body);
        ledger.authorize(terminalId, action); Map<String, Object> original = ledger.finishAction(terminalId, str(body, "requestId"), "UNKNOWN");
        long eventCursor = store.latestEvent();
        assertEquals(original, ledger.observation(terminalId, str(body, "requestId"))); assertEquals(original, ledger.observation(terminalId, str(body, "requestId")));
        assertEquals(eventCursor, store.latestEvent()); assertEquals(1, store.list("terminalAction").size());
        assertEquals(1, num(original, "dispatchCount", -1)); assertEquals(1, num(original, "nativeAuthorizationCount", -1));
        assertFalse(original.toString().contains("QUERY_ONLY_PRIVATE_INPUT")); assertFalse(original.toString().contains(str(controller, "controlSecret")));
        assertFalse(ledger.observation(terminalId, str(body, "requestId")).containsKey("dataBase64"));
        assertEquals(404, assertThrows(ApiException.class, () -> ledger.observation(terminalId, id())).status());
        assertEquals(400, assertThrows(ApiException.class, () -> ledger.observation(terminalId, "../../another-input")).status());
    }

    @ParameterizedTest
    @ValueSource(strings = {"EXTRA_INPUT_FIELD", "INPUT_AS_OBJECT", "FLOAT_SEQUENCE", "STRING_GENERATION", "STOP_WITH_INPUT", "RESIZE_WITH_INPUT", "HUGE_COLUMNS", "NULL_ROWS"})
    void malformedControlBodiesNeverCreateAnInputIntent(String malformed) {
        Map<String, Object> body = input("UNSENT_PRIVATE_INPUT\r", 1);
        switch (malformed) {
            case "EXTRA_INPUT_FIELD" -> body.put("command", "another executable");
            case "INPUT_AS_OBJECT" -> body.put("dataBase64", obj("unexpected", "input"));
            case "FLOAT_SEQUENCE" -> body.put("sequence", 1.0);
            case "STRING_GENERATION" -> body.put("generation", "1");
            case "STOP_WITH_INPUT" -> body.put("kind", "STOP");
            case "RESIZE_WITH_INPUT" -> body.putAll(obj("kind", "RESIZE", "columns", 80, "rows", 24));
            case "HUGE_COLUMNS", "NULL_ROWS" -> { body.remove("dataBase64"); body.putAll(obj("kind", "RESIZE", "columns", 80, "rows", 24)); body.put("HUGE_COLUMNS".equals(malformed) ? "columns" : "rows", "HUGE_COLUMNS".equals(malformed) ? Long.MAX_VALUE : null); }
            default -> throw new IllegalArgumentException(malformed);
        }
        long eventCursor = store.latestEvent(); assertEquals(400, assertThrows(ApiException.class, () -> ledger.prepare(terminalId, body)).status());
        assertEquals(eventCursor, store.latestEvent()); assertTrue(store.list("terminalAction").isEmpty()); assertEquals(0, num(store.get("terminalSession", terminalId), "lastActionSequence", -1));
    }

    @Test
    void malformedTakeoverCannotReplaceTheOriginalController() {
        Map<String, Object> before = store.get("terminalSession", terminalId);
        for (Map<String, Object> body : List.of(obj("controllerId", id()), obj("controllerId", id(), "expectedGeneration", 1, "controlSecret", str(controller, "controlSecret")), obj("controllerId", id(), "expectedGeneration", 1.0))) {
            assertEquals(400, assertThrows(ApiException.class, () -> ledger.acquire(terminalId, body)).status()); assertEquals(before, store.get("terminalSession", terminalId));
        }
        assertFalse(TerminalLedger.visible(before).containsKey("controllerDigest"));
    }

    @Test
    void sessionKeepsOriginalNestedWorkspaceAndAuthorizationSnapshots() {
        Map<String, Object> task = store.get("task", taskId), session = ledger.newSession(task, "SYSTEM_CMD", 80, 24);
        map(task, "workspaceOwnership").put("owner", "changed-after-intent"); map(task, "execution").put("version", 2);
        assertEquals("original-owned-workspace", map(session, "workspaceOwnership").get("owner"));
        assertEquals(1, num(map(session, "execution"), "version", -1));
    }

    @Test
    void sameInputIdentityReturnsOriginalIntentAndChangedBytesCannotDispatch() throws Exception {
        Map<String, Object> body = input("ledger-only original input\r", 1);
        TerminalLedger.Prepared prepared = ledger.prepare(terminalId, body); assertTrue(prepared.dispatch());
        assertFalse(ledger.prepare(terminalId, body).dispatch());
        Map<String, Object> changed = new java.util.LinkedHashMap<>(body);
        changed.put("dataBase64", Base64.getEncoder().encodeToString("foreign input\r".getBytes(StandardCharsets.UTF_8)));
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.prepare(terminalId, changed)).status());
        ledger.dispatched(terminalId, str(body, "requestId")); assertFalse(ledger.prepare(terminalId, body).dispatch());
        assertEquals(1, store.list("terminalAction").size()); assertEquals(7, num(map(store.get("task", taskId), "budget"), "usedCalls", -1));
        String persisted = new ObjectMapper().writeValueAsString(store.list("terminalAction"));
        assertFalse(persisted.contains("ledger-only original input")); assertFalse(persisted.contains(str(body, "dataBase64")));
    }

    @Test
    void nativeInputAuthorizationCanBeConsumedOnlyOnceForTheOriginalAction() {
        Map<String, Object> body = input("original one-shot native write\r", 1), action = dispatch(body);
        ledger.authorize(terminalId, action);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.authorize(terminalId, action)).status());
        assertEquals(1, num(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "nativeAuthorizationCount", 0));
    }

    @Test
    void failedDurableAuthorizationCannotReleaseInputToTheNativeWrite() throws Exception {
        Map<String, Object> body = input("must remain unsent after SQLite failure\r", 1), action = dispatch(body);
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var statement = database.createStatement()) {
            statement.execute("CREATE TRIGGER fail_terminal_authorization BEFORE UPDATE ON entities WHEN new.kind='terminalAction' AND json_extract(new.body,'$.nativeAuthorizationCount')=1 BEGIN SELECT RAISE(ABORT,'terminal authorization fault'); END");
        }
        assertEquals(503, assertThrows(ApiException.class, () -> ledger.authorize(terminalId, action)).status());
        assertFalse(store.healthy());
        assertEquals(0, num(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "nativeAuthorizationCount", 0));
        assertEquals("DISPATCHED", str(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "state"));
    }

    @Test
    void lostInputAcknowledgmentBlocksMoreInputAndControllerTakeover() {
        Map<String, Object> body = input("original input with lost acknowledgment\r", 1); dispatch(body);
        ledger.finishAction(terminalId, str(body, "requestId"), "UNKNOWN");
        assertFalse(ledger.prepare(terminalId, body).dispatch());
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.prepare(terminalId, input("next input\r", 2))).status());
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.acquire(terminalId, obj("expectedGeneration", 1, "controllerId", id()))).status());
        assertEquals(1, store.list("terminalAction").size()); assertTrue(bool(store.get("terminalSession", terminalId), "inputFenced", false));
    }

    @Test
    void explicitTakeoverAfterSettlementRejectsEveryOldControllerAction() {
        Map<String, Object> old = input("first input\r", 1); ledger.authorize(terminalId, dispatch(old)); ledger.finishAction(terminalId, str(old, "requestId"), "ACKNOWLEDGED");
        Map<String, Object> next = ledger.acquire(terminalId, obj("expectedGeneration", 1, "controllerId", id()));
        assertEquals(2, ((Number) next.get("generation")).longValue());
        assertEquals(403, assertThrows(ApiException.class, () -> ledger.prepare(terminalId, old)).status());
        assertFalse(map(next, "terminal").containsKey("controllerDigest")); assertEquals(1, store.list("terminalAction").size());
    }

    @Test
    void reopenedCoreRetainsUnknownOriginalDispatchAndNeverReplaysInput() throws Exception {
        Map<String, Object> body = input("original dispatched input\r", 1); dispatch(body);
        String originalEpoch = str(store.get("terminalSession", terminalId), "epoch");
        store.close(); store = new ExecutionStore(new ObjectMapper(), data.toString()); ledger = new TerminalLedger(store);
        new TaskDispatchQueue(store).recover(); ledger.recover();
        assertEquals("UNKNOWN", str(store.get("terminalSession", terminalId), "state"));
        assertEquals(originalEpoch, str(store.get("terminalSession", terminalId), "epoch"));
        assertEquals("UNKNOWN", str(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "state"));
        assertEquals(1, num(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "dispatchCount", 0));
        assertThrows(ApiException.class, () -> ledger.prepare(terminalId, body));
        assertEquals(1, store.list("terminalSession").size()); assertEquals(1, store.list("terminalAction").size());
        assertEquals(7, num(map(store.get("task", taskId), "budget"), "usedCalls", -1));
    }

    @Test
    void preparedInputOwnsItsBytesAndCallerCannotChangeDeferredNativeData() {
        Map<String, Object> body = input("original deferred bytes\r", 1); TerminalLedger.Prepared prepared = ledger.prepare(terminalId, body);
        byte[] copy = prepared.input(); copy[0] = 0;
        assertArrayEquals("original deferred bytes\r".getBytes(StandardCharsets.UTF_8), prepared.input());
        assertThrows(UnsupportedOperationException.class, () -> prepared.observation().put("state", "changed"));
    }

    @Test
    void originalInputWithoutNativeAuthorizationCannotBeAcknowledged() {
        Map<String, Object> body = input("never authorized native input\r", 1); dispatch(body);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.finishAction(terminalId, str(body, "requestId"), "ACKNOWLEDGED")).status());
        assertEquals("DISPATCHED", str(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "state"));
        ledger.finishAction(terminalId, str(body, "requestId"), "REJECTED");
        assertEquals("REJECTED", str(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "state"));
    }

    @Test
    void changedNativeInputFactsCannotConsumeTheOriginalPermission() {
        Map<String, Object> body = input("original framed bytes\r", 1), action = dispatch(body);
        Map<String, Object> foreign = new java.util.LinkedHashMap<>(action); foreign.put("length", num(action, "length", 0) + 1);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.authorize(terminalId, foreign)).status());
        assertEquals(0, num(store.get("terminalAction", terminalId + ":" + str(body, "requestId")), "nativeAuthorizationCount", -1));
        ledger.authorize(terminalId, action);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.finishAction(terminalId, str(body, "requestId"), "REJECTED")).status());
        ledger.finishAction(terminalId, str(body, "requestId"), "UNKNOWN");
        assertTrue(bool(store.get("terminalSession", terminalId), "inputFenced", false));
    }

    private TerminalOutputArchive.Writer outputWriter() {
        Map<String, Object> context = ledger.outputContext(terminalId);
        return new TerminalOutputArchive(new ObjectMapper(), data).create(terminalId, str(context, "epoch"), map(context, "owner"), ((Number) context.get("outputLimit")).longValue());
    }

    @Test
    void splitOutputFramesAndReopenedViewPreserveTheExactRawByteCursor() throws Exception {
        TerminalOutputArchive.Writer writer = outputWriter();
        byte[] original = "\u001b[32m中文\u001b[0m\r\n原尾部".getBytes(StandardCharsets.UTF_8);
        ledger.publishOutput(terminalId, writer.append(java.util.Arrays.copyOfRange(original, 0, 7)));
        ledger.publishOutput(terminalId, writer.append(java.util.Arrays.copyOfRange(original, 7, original.length)));
        Map<String, Object> terminal = ledger.finishOutput(terminalId, writer.finish(true)); assertEquals("COMPLETE", terminal.get("outputIntegrity"));
        String epoch = str(terminal, "epoch"); var output = new java.io.ByteArrayOutputStream(); long cursor = 0;
        do {
            Map<String, Object> frame = ledger.outputPage(terminalId, epoch, cursor, 3);
            assertEquals(cursor, ((Number) frame.get("offset")).longValue()); byte[] bytes = Base64.getDecoder().decode(str(frame, "dataBase64"));
            assertEquals(bytes.length, ((Number) frame.get("length")).intValue()); assertTrue(bytes.length <= 3);
            output.write(bytes); cursor = ((Number) frame.get("next")).longValue();
        } while (cursor < original.length);
        assertArrayEquals(original, output.toByteArray());
        assertEquals(0, ((Number) ledger.outputPage(terminalId, epoch, cursor, 3).get("length")).longValue());
        store.close(); store = new ExecutionStore(new ObjectMapper(), data.toString()); ledger = new TerminalLedger(store);
        new TaskDispatchQueue(store).recover(); ledger.recover();
        Map<String, Object> resumed = ledger.outputPage(terminalId, epoch, 7, 65536);
        assertArrayEquals(java.util.Arrays.copyOfRange(original, 7, original.length), Base64.getDecoder().decode(str(resumed, "dataBase64")));
        assertEquals("UNKNOWN", str(store.get("terminalSession", terminalId), "state")); assertEquals(2, store.list("terminalOutputSegment").size());
    }

    @Test
    void duplicateOutputPublicationKeepsOneOriginalReferenceAndNoExtraCursorAdvance() {
        TerminalOutputArchive.Writer writer = outputWriter(); Map<String, Object> descriptor = writer.append(new byte[] {1, 2, 3});
        Map<String, Object> original = ledger.publishOutput(terminalId, descriptor);
        assertEquals(original, ledger.publishOutput(terminalId, descriptor));
        assertEquals(1, store.list("terminalOutputSegment").size());
        assertEquals(3, ((Number) store.get("terminalSession", terminalId).get("outputEnd")).longValue());
        assertEquals(1, ((Number) store.get("terminalSession", terminalId).get("outputSegments")).longValue());
        assertEquals("COMPLETE", ledger.finishOutput(terminalId, writer.finish(true)).get("outputIntegrity"));
    }

    @Test
    void savedOutputSurvivesSqlitePublicationFailureAndIsNotAHealthyEmptyTranscript() throws Exception {
        TerminalOutputArchive.Writer writer = outputWriter(); Map<String, Object> descriptor = writer.append("original unpublished tail".getBytes(StandardCharsets.UTF_8));
        try (var database = DriverManager.getConnection("jdbc:sqlite:" + data.resolve("workbench.sqlite")); var statement = database.createStatement()) {
            statement.execute("CREATE TRIGGER fail_terminal_output BEFORE INSERT ON entities WHEN new.kind='terminalOutputSegment' BEGIN SELECT RAISE(ABORT,'terminal output publication fault'); END");
        }
        assertEquals(503, assertThrows(ApiException.class, () -> ledger.publishOutput(terminalId, descriptor)).status());
        assertFalse(store.healthy()); assertEquals(0, ((Number) store.get("terminalSession", terminalId).get("outputEnd")).longValue());
        assertTrue(store.list("terminalOutputSegment").isEmpty()); writer.finish(false);
        TerminalOutputArchive archive = new TerminalOutputArchive(new ObjectMapper(), data);
        assertArrayEquals("original unpublished tail".getBytes(StandardCharsets.UTF_8), archive.read(terminalId, str(descriptor, "epoch"), descriptor));
        assertFalse(bool(archive.inspect(terminalId, str(descriptor, "epoch")), "complete", true));
        assertThrows(ApiException.class, () -> ledger.prepare(terminalId, input("unsent after SQLite output fault\r", 1)));
    }

    @Test
    void missingEarlierOutputPublicationFencesInputAndCannotBecomeHealthyByRetry() {
        TerminalOutputArchive.Writer writer = outputWriter(); Map<String, Object> first = writer.append(new byte[] {1, 2}), second = writer.append(new byte[] {3, 4});
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.publishOutput(terminalId, second)).status());
        assertTrue(store.list("terminalOutputSegment").isEmpty());
        assertTrue(bool(store.get("terminalSession", terminalId), "inputFenced", false));
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.publishOutput(terminalId, first)).status());
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.prepare(terminalId, input("unsent after output gap\r", 1))).status());
        assertEquals("UNCONFIRMED", ledger.finishOutput(terminalId, writer.finish(false)).get("outputIntegrity"));
    }

    @Test
    void corruptedPublishedBytesRefuseReadWithoutSkippingTheOriginalCursor() throws Exception {
        TerminalOutputArchive.Writer writer = outputWriter(); Map<String, Object> descriptor = writer.append("original".getBytes(StandardCharsets.UTF_8));
        ledger.publishOutput(terminalId, descriptor); ledger.finishOutput(terminalId, writer.finish(true));
        Path file;
        try (var files = Files.list(data.resolve("terminal-output").resolve(terminalId))) { file = files.filter(path -> path.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        Files.writeString(file, "modified");
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.outputPage(terminalId, str(descriptor, "epoch"), 0, 1024)).status());
        assertEquals(8, ((Number) store.get("terminalSession", terminalId).get("outputEnd")).longValue());
        assertEquals("modified", Files.readString(file)); assertEquals(1, store.list("terminalOutputSegment").size());
    }

    @Test
    void foreignOutputOwnerCannotReplaceTheOriginalWorkerSource() throws Exception {
        TerminalOutputArchive.Writer writer = outputWriter(); Map<String, Object> descriptor = writer.append(new byte[] {1, 2});
        Path owner = data.resolve("terminal-output").resolve(terminalId).resolve("owner.json"); ObjectMapper json = new ObjectMapper();
        Map<String, Object> changed = json.readValue(Files.readAllBytes(owner), new com.fasterxml.jackson.core.type.TypeReference<>() {});
        map(map(changed, "owner"), "source").put("workerId", "foreign-worker"); byte[] bytes = json.writeValueAsBytes(changed); Files.write(owner, bytes);
        assertEquals(409, assertThrows(ApiException.class, () -> ledger.publishOutput(terminalId, descriptor)).status());
        assertArrayEquals(bytes, Files.readAllBytes(owner)); assertTrue(store.list("terminalOutputSegment").isEmpty());
        assertEquals("UNCONFIRMED", store.get("terminalSession", terminalId).get("outputIntegrity"));
        assertTrue(bool(store.get("terminalSession", terminalId), "inputFenced", false));
    }

    @Test
    void completeActionHistoryUsesItsOwnCursorAndKeepsOriginalInputSequences() {
        for (int sequence = 1; sequence <= 135; sequence++) {
            Map<String, Object> body = input("history input\r", sequence); ledger.authorize(terminalId, dispatch(body)); ledger.finishAction(terminalId, str(body, "requestId"), "ACKNOWLEDGED");
        }
        Map<String, Object> first = store.terminalActionPage(terminalId, 0, 100), second = store.terminalActionPage(terminalId, ((Number) first.get("cursor")).longValue(), 100);
        assertEquals(100, objects(first.get("actions")).size()); assertTrue(bool(first, "hasMore", false));
        assertEquals(35, objects(second.get("actions")).size()); assertFalse(bool(second, "hasMore", true));
        var actions = new java.util.ArrayList<>(objects(first.get("actions"))); actions.addAll(objects(second.get("actions")));
        for (int index = 0; index < actions.size(); index++) { assertEquals(135 - index, num(actions.get(index), "sequence", -1)); assertTrue(actions.get(index).containsKey("historyCursor")); }
        assertEquals(135, actions.stream().map(action -> action.get("id")).distinct().count());
    }

    @ParameterizedTest
    @ValueSource(strings = {"PAUSING", "CANCELLING"})
    void completeNativeReceiptPreservesTheOriginalTaskStopDisposition(String requestedState) {
        TerminalOutputArchive.Writer writer = outputWriter(); ledger.publishOutput(terminalId, writer.append("original manual tail\r\n".getBytes(StandardCharsets.UTF_8)));
        ledger.finishOutput(terminalId, writer.finish(true));
        store.editTask(taskId, task -> task.put("state", requestedState), null, "");
        Map<String, Object> result = obj("status", "CANCELLED", "startupAcknowledged", true, "originalProcessStopped", true, "observedChildrenStopped", true,
            "outputReaderCompleted", true, "nativeCleanupThreadCompleted", true, "ownedStreamsClosed", true, "scope", "SQLITE_LEDGER_FIXTURE_WITHOUT_PTY");
        assertEquals("CANCELLED", ledger.completed(terminalId, result, "manual-version").get("state"));
        Map<String, Object> task = store.get("task", taskId);
        assertEquals("CANCELLING".equals(requestedState) ? "CANCELLED" : "PAUSED", task.get("state"));
        if ("CANCELLING".equals(requestedState)) assertEquals("CANCELLED", task.get("outcome"));
        assertFalse(task.containsKey("pendingTerminalId")); assertEquals("manual-version", task.get("version"));
        assertEquals("NOT_RUN", map(task, "verification").get("status")); assertEquals("NOT_RUN", map(task, "review").get("status"));
        assertEquals(7, num(map(task, "budget"), "usedCalls", -1));
    }

    @Test
    void completeOutputAndNativeStopCannotReplaceAnUnknownOriginalInputReceipt() {
        Map<String, Object> body = input("original uncertain write\r", 1), action = dispatch(body);
        ledger.authorize(terminalId, action); ledger.finishAction(terminalId, str(body, "requestId"), "UNKNOWN");
        TerminalOutputArchive.Writer writer = outputWriter(); ledger.publishOutput(terminalId, writer.append("saved tail".getBytes(StandardCharsets.UTF_8))); ledger.finishOutput(terminalId, writer.finish(true));
        Map<String, Object> result = obj("status", "EXITED", "startupAcknowledged", true, "originalProcessStopped", true, "observedChildrenStopped", true,
            "outputReaderCompleted", true, "nativeCleanupThreadCompleted", true, "ownedStreamsClosed", true, "scope", "SQLITE_LEDGER_FIXTURE_WITHOUT_PTY");
        assertEquals("UNKNOWN", ledger.completed(terminalId, result, "manual-version").get("state"));
        assertEquals("UNKNOWN", store.get("task", taskId).get("state")); assertEquals(terminalId, store.get("task", taskId).get("pendingTerminalId"));
        assertEquals("UNKNOWN", store.get("terminalAction", str(action, "id")).get("state")); assertTrue(store.hasUnknownTerminalEffects(taskId));
        assertEquals("COMPLETE_WITH_UNCONFIRMED_INPUT_OR_NATIVE_RESULT", store.get("terminalSession", terminalId).get("outputIntegrity"));
    }
}
