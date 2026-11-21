package dev.agentworkbench.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import static dev.agentworkbench.runtime.NativeCliReceipt.State.*;
import static org.junit.jupiter.api.Assertions.*;

class NativeCliReceiptTest {
    private static final String THREAD = "019ac0d5-486f-7aa2-9434-3c9f7b9ae0b2";
    private static final String SESSION = "bd9e4a36-5c41-43ec-b9d2-1ee9cf733054";
    private final ObjectMapper json = new ObjectMapper();
    private final NativeCliReceipt parser = new NativeCliReceipt(json);

    @Test
    void codexRequiresACompletedTurnAndRetainsTheFinalCompletedMessage() throws Exception {
        String stdout = stream(thread(), start(), message("first"), message("final 中文"), complete());
        var receipt = parse("CODEX", stdout);
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals("final 中文", receipt.output());
        assertFalse(receipt.metadata().containsKey("threadId"));
        assertEquals(sha(THREAD), receipt.metadata().get("threadIdSha256"));
        assertEquals("codex-exec-jsonl", receipt.metadata().get("protocol"));
        assertEquals(sha(stdout), receipt.metadata().get("stdoutSha256"));
        assertEquals(1, receipt.metadata().get("turnCount"));
        assertEquals(1, receipt.metadata().get("completedTurns"));
        assertEquals("TURN_COMPLETED", receipt.metadata().get("reason"));
    }

    @Test
    void codexAgentMessageWithoutTerminalCannotSucceed() throws Exception {
        assertUnknown(parse("CODEX", stream(thread(), start(), message("must not be reused"))), "MISSING_TURN_TERMINAL");
    }

    @Test
    void codexExplicitFailureIsNotChangedByAssistantText() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), message("looks complete"), fail("failed")));
        assertEquals(FAILED, receipt.state());
        assertEquals("TURN_FAILED", receipt.metadata().get("reason"));
        assertEquals(1, receipt.metadata().get("failedTurns"));
    }

    @Test
    void codexRetryErrorsCanRecoverThroughAnExplicitCompletedTurn() throws Exception {
        var receipt = parse("CODEX", stream(error("initial retry"), thread(), start(), error("retry again"), message("done"), complete()));
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals(2, receipt.metadata().get("errorEvents"));
        assertEquals("done", receipt.output());
    }

    @Test
    void codexTrailingUnresolvedErrorInvalidatesEarlierSuccess() throws Exception {
        assertUnknown(parse("CODEX", stream(thread(), start(), message("done"), complete(), error("later failure"))), "UNRESOLVED_ERROR");
    }

    @Test
    void codexBareErrorDoesNotInventAFailedTurn() throws Exception {
        assertUnknown(parse("CODEX", stream(error("could have been retried"))), "MISSING_THREAD");
    }

    @Test
    void codexMultipleCompletedTurnsUseTheLastTurnOutput() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), message("old"), complete(), start(), message("new"), complete()));
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals("new", receipt.output());
        assertEquals(2, receipt.metadata().get("completedTurns"));
    }

    @Test
    void codexFailedTurnCannotBeErasedByALaterSuccessfulTurn() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), fail("first failed"), start(), message("second finished"), complete()));
        assertEquals(FAILED, receipt.state());
        assertEquals(2, receipt.metadata().get("turnCount"));
        assertEquals(1, receipt.metadata().get("failedTurns"));
        assertEquals(1, receipt.metadata().get("completedTurns"));
    }

    @Test
    void codexLastOpenTurnKeepsAWholeInvocationUnknown() throws Exception {
        assertUnknown(parse("CODEX", stream(thread(), start(), message("first"), complete(), start(), message("unfinished"))), "MISSING_TURN_TERMINAL");
    }

    @Test
    void codexDuplicateOrContradictoryTerminalsAreUnknown() throws Exception {
        for (String terminal : new String[] { complete(), fail("contradiction") }) {
            assertUnknown(parse("CODEX", stream(thread(), start(), complete(), terminal)), "CONFLICTING_TURN_TERMINAL");
        }
    }

    @Test
    void codexRequiresOneThreadAndOrderedTurnStarts() throws Exception {
        assertUnknown(parse("CODEX", stream(start(), complete())), "INVALID_TURN_SEQUENCE");
        assertUnknown(parse("CODEX", stream(thread(), start(), start(), complete())), "INVALID_TURN_SEQUENCE");
        assertUnknown(parse("CODEX", stream(thread(), thread())), "MULTIPLE_THREADS");
    }

    @Test
    void codexNonfatalItemErrorIsNotAThreadFailure() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), "{\"type\":\"item.completed\",\"item\":{\"type\":\"error\",\"message\":\"warning\"}}", message("done"), complete()));
        assertEquals(SUCCEEDED, receipt.state());
    }

    @Test
    void codexDoesNotTreatUpdatedMessagesAsFinalOutput() throws Exception {
        String updated = json.writeValueAsString(Map.of("type", "item.updated", "item", Map.of("type", "agent_message", "text", "partial")));
        var receipt = parse("CODEX", stream(thread(), start(), updated, complete()));
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals("", receipt.output());
        assertEquals(false, receipt.metadata().get("outputPresent"));
    }

    @Test
    void codexUnknownNonterminalEventsAreForwardCompatible() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), "{\"type\":\"future.telemetry\",\"secret\":\"not metadata\"}", message("done"), complete()));
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals(1, receipt.metadata().get("unknownEventCount"));
        assertFalse(receipt.metadata().toString().contains("not metadata"));
    }

    @Test
    void codexMalformedAndTrailingJsonCannotBeIgnored() throws Exception {
        assertUnknown(parse("CODEX", stream(thread(), start(), "not JSON", complete())), "MALFORMED_JSON");
        assertUnknown(parse("CODEX", stream(thread(), start(), complete() + " {}")), "MALFORMED_JSON");
        assertUnknown(parse("CODEX", stream(thread(), start(), "{\"type\":\"turn.completed\",\"type\":\"turn.failed\"}")), "MALFORMED_JSON");
    }

    @Test
    void codexInvalidShapesAndSequencesAreUnknown() throws Exception {
        assertUnknown(parse("CODEX", stream(thread(), start(), "[]")), "INVALID_EVENT");
        assertUnknown(parse("CODEX", stream(thread(), start(), "{\"type\":false}")), "INVALID_EVENT");
        assertUnknown(parse("CODEX", stream(thread(), start(), "{\"type\":\"turn.failed\",\"error\":\"bad\"}")), "INVALID_TURN_FAILURE");
        assertUnknown(parse("CODEX", stream(thread(), start(), "{\"type\":\"error\",\"message\":3}")), "INVALID_ERROR_EVENT");
        assertUnknown(parse("CODEX", stream(thread(), message("outside a turn"))), "INVALID_ITEM_SEQUENCE");
        assertUnknown(parse("CODEX", stream(thread(), start(), "{\"type\":\"item.completed\",\"item\":{\"type\":\"agent_message\",\"text\":3}}")), "INVALID_AGENT_MESSAGE");
    }

    @Test
    void codexAcceptsCrlfBlankLinesAndCompleteFinalJsonWithoutNewline() throws Exception {
        String stdout = thread() + "\r\n\r\n" + start() + "\r\n" + message("done") + "\r\n" + complete();
        assertEquals(SUCCEEDED, parse("codex", stdout).state());
    }

    @Test
    void claudeSuccessRequiresAResultEnvelopeAndBooleanErrorFlag() throws Exception {
        var receipt = parse("CLAUDE", claude("success", false, "done", null));
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals("done", receipt.output());
        assertFalse(receipt.metadata().containsKey("sessionId"));
        assertEquals(sha(SESSION), receipt.metadata().get("sessionIdSha256"));
        assertEquals("NOT_REPORTED", receipt.metadata().get("terminalReason"));
        assertEquals("RESULT_SUCCESS", receipt.metadata().get("reason"));
    }

    @Test
    void claudeSuccessSubtypeCanStillReportAnApiError() throws Exception {
        var receipt = parse("CLAUDE", claude("success", true, "API failure", "completed"));
        assertEquals(FAILED, receipt.state());
        assertEquals("CLI_REPORTED_ERROR", receipt.metadata().get("reason"));
    }

    @Test
    void claudeKnownFailureSubtypesRemainFailures() throws Exception {
        for (String subtype : new String[] { "error_during_execution", "error_max_turns", "error_max_budget_usd", "error_max_structured_output_retries" }) {
            assertEquals(FAILED, parse("CLAUDE", claude(subtype, true, "failure", null)).state());
            assertUnknown(parse("CLAUDE", claude(subtype, false, "inconsistent", null)), "CONFLICTING_RESULT");
        }
    }

    @Test
    void claudeTerminalReasonsDoNotInventCoreCancellation() throws Exception {
        for (String reason : new String[] { "aborted_streaming", "aborted_tools" }) {
            var receipt = parse("CLAUDE", claude("success", false, "partial", reason));
            assertEquals(FAILED, receipt.state());
            assertEquals("CLI_REPORTED_ABORT", receipt.metadata().get("reason"));
            assertEquals(reason, receipt.metadata().get("terminalReason"));
        }
        for (String reason : new String[] { "max_turns", "max_budget_usd", "max_structured_output_retries" }) {
            assertEquals(FAILED, parse("CLAUDE", claude("success", false, "partial", reason)).state());
        }
        assertEquals(SUCCEEDED, parse("CLAUDE", claude("success", false, "done", "completed")).state());
        assertUnknown(parse("CLAUDE", claude("success", false, "uncertain", "new_reason")), "UNRECOGNIZED_TERMINAL_REASON");
    }

    @Test
    void claudeUnknownSubtypeDoesNotInventSuccessButAnErrorFlagIsDefinitive() throws Exception {
        assertUnknown(parse("CLAUDE", claude("future_result", false, "maybe", null)), "UNRECOGNIZED_RESULT_SUBTYPE");
        assertEquals(FAILED, parse("CLAUDE", claude("future_result", true, "failed", null)).state());
    }

    @Test
    void claudeResultTextMayBeAbsentForACompleteStructuredResult() throws Exception {
        String stdout = json.writeValueAsString(Map.of("type", "result", "subtype", "success", "is_error", false, "session_id", SESSION, "structured_output", Map.of("ok", true)));
        var receipt = parse("CLAUDE", stdout);
        assertEquals(SUCCEEDED, receipt.state());
        assertEquals("", receipt.output());
    }

    @Test
    void claudeMissingMalformedAndConflictingEnvelopesAreUnknown() throws Exception {
        assertUnknown(parse("CLAUDE", "{}"), "MISSING_RESULT");
        assertUnknown(parse("CLAUDE", "[]"), "MISSING_RESULT");
        assertUnknown(parse("CLAUDE", "{\"type\":\"result\",\"result\":\"done\"}"), "INVALID_RESULT");
        String valid = claude("success", false, "done", null);
        assertUnknown(parse("CLAUDE", valid + "\n" + valid), "MALFORMED_JSON");
        assertUnknown(parse("CLAUDE", valid.substring(0, valid.length() - 1)), "MALFORMED_JSON");
        assertUnknown(parse("CLAUDE", "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"is_error\":true,\"session_id\":\"" + SESSION + "\"}"), "MALFORMED_JSON");
    }

    @Test
    void claudeTypedCriticalFieldsAreNotCoerced() throws Exception {
        var envelope = new java.util.LinkedHashMap<String, Object>(Map.of("type", "result", "subtype", "success", "is_error", false, "session_id", SESSION, "result", "done"));
        envelope.put("is_error", "false");
        assertUnknown(parse("CLAUDE", json.writeValueAsString(envelope)), "INVALID_RESULT");
        envelope.put("is_error", false); envelope.put("result", Map.of("result", "done"));
        assertUnknown(parse("CLAUDE", json.writeValueAsString(envelope)), "INVALID_RESULT_OUTPUT");
        envelope.put("result", "done"); envelope.put("terminal_reason", 2);
        assertUnknown(parse("CLAUDE", json.writeValueAsString(envelope)), "INVALID_TERMINAL_REASON");
    }

    @Test
    void nullAndNonObjectRootsDoNotProduceUncheckedExceptions() {
        for (String root : new String[] { "null", "true", "42", "\"text\"", "[]" }) {
            assertEquals(UNKNOWN, parse("CODEX", root).state());
            assertEquals(UNKNOWN, parse("CLAUDE", root).state());
        }
        assertEquals(UNKNOWN, parser.parse(null, "{}", false).state());
    }

    @Test
    void nullAndNonStringCriticalIdentitiesAndSubtypesAreRejected() throws Exception {
        for (String bad : new String[] { "null", "false", "[]", "{}", "3", "\"\"" }) {
            assertUnknown(parse("CODEX", "{\"type\":\"thread.started\",\"thread_id\":" + bad + "}"), "INVALID_THREAD");
            assertUnknown(parse("CLAUDE", "{\"type\":\"result\",\"subtype\":\"success\",\"is_error\":false,\"session_id\":" + bad + "}"), "INVALID_RESULT");
            assertUnknown(parse("CLAUDE", "{\"type\":\"result\",\"subtype\":" + bad + ",\"is_error\":false,\"session_id\":\"" + SESSION + "\"}"), "INVALID_RESULT");
        }
    }

    @Test
    void stderrIsNotAcceptedAsPartOfEitherProtocol() throws Exception {
        assertUnknown(parse("CLAUDE", claude("success", false, "done", null) + "\nwarning from stderr"), "MALFORMED_JSON");
        assertUnknown(parse("CODEX", stream(thread(), start(), complete()) + "\nwarning from stderr"), "MALFORMED_JSON");
    }

    @Test
    void truncationAlwaysWinsOverAnApparentlySuccessfulPrefix() throws Exception {
        for (String engine : new String[] { "CODEX", "CLAUDE" }) {
            String stdout = "CODEX".equals(engine) ? stream(thread(), start(), message("done"), complete()) : claude("success", false, "done", null);
            assertUnknown(parser.parse(engine, stdout, true), "TRUNCATED_STDOUT");
        }
    }

    @Test
    void emptyUnsupportedAndOversizeInputsRemainUnknown() {
        assertUnknown(parser.parse("CODEX", null, false), "EMPTY_STDOUT");
        assertUnknown(parser.parse("CLAUDE", " \r\n", false), "EMPTY_STDOUT");
        assertUnknown(parser.parse("credential-shaped-engine", "{}", false), "UNSUPPORTED_ENGINE");
        var oversized = parser.parse("CODEX", "x".repeat(NativeCliReceipt.MAX_STDOUT_CHARS + 1), false);
        assertUnknown(oversized, "STDOUT_LIMIT");
        assertEquals("NOT_COMPUTED_LIMIT", oversized.metadata().get("stdoutDigestState"));
        assertEquals("", oversized.metadata().get("stdoutSha256"));
    }

    @Test
    void eventLimitDoesNotAcceptAnUnboundedStream() throws Exception {
        String stdout = stream(thread(), start()) + "\n" + "{\"type\":\"future\"}\n".repeat(NativeCliReceipt.MAX_EVENTS);
        assertUnknown(parse("CODEX", stdout), "EVENT_LIMIT");
    }

    @Test
    void opaqueIdentitiesAreOnlyHashedAndArbitraryErrorsAreNotMetadata() throws Exception {
        String secret = "sk-should-never-appear-in-metadata";
        String stdout = stream(json.writeValueAsString(Map.of("type", "thread.started", "thread_id", secret)), start(), error(secret), message("done"), complete());
        var codex = parse("CODEX", stdout);
        assertEquals(SUCCEEDED, codex.state());
        assertFalse(codex.metadata().containsKey("threadId"));
        assertEquals(sha(secret), codex.metadata().get("threadIdSha256"));
        assertFalse(json.writeValueAsString(codex.metadata()).contains(secret));
        String claude = json.writeValueAsString(Map.of("type", "result", "subtype", secret, "is_error", true, "session_id", secret, "errors", new String[] { secret }));
        var failed = parse("CLAUDE", claude);
        assertEquals(FAILED, failed.state());
        assertEquals("OTHER", failed.metadata().get("reportedSubtype"));
        assertFalse(json.writeValueAsString(failed.metadata()).contains(secret));
    }

    @Test
    void invalidIdentityIsRejectedInsteadOfBeingUnboundedMetadata() throws Exception {
        assertUnknown(parse("CODEX", json.writeValueAsString(Map.of("type", "thread.started", "thread_id", "x".repeat(257)))), "INVALID_THREAD");
        assertUnknown(parse("CLAUDE", json.writeValueAsString(Map.of("type", "result", "subtype", "success", "is_error", false, "session_id", ""))), "INVALID_RESULT");
    }

    @Test
    void receiptMetadataIsImmutableAndMapperConfigurationIsNotChanged() throws Exception {
        var receipt = parse("CLAUDE", claude("success", false, "done", null));
        assertThrows(UnsupportedOperationException.class, () -> receipt.metadata().put("reason", "changed"));
        assertFalse(json.isEnabled(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION));
        assertFalse(json.isEnabled(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS));
    }

    @Test
    void incompleteProcessStreamsCannotReuseACompleteProtocolPrefix() throws Exception {
        String stdout = stream(thread(), start(), message("done"), complete());
        var receipt = parser.parse("CODEX", stdout, false, false);
        assertUnknown(receipt, "INCOMPLETE_STREAMS");
        assertEquals(false, receipt.metadata().get("streamsComplete"));
        assertEquals(false, receipt.metadata().get("stdoutTruncated"));
        assertEquals(sha(stdout), receipt.metadata().get("stdoutSha256"));
    }

    @ParameterizedTest
    @CsvSource({ "SUCCEEDED,SUCCEEDED,SUCCEEDED", "SUCCEEDED,FAILED,FAILED", "SUCCEEDED,UNKNOWN,UNKNOWN",
        "FAILED,SUCCEEDED,UNKNOWN", "FAILED,FAILED,FAILED", "FAILED,UNKNOWN,UNKNOWN",
        "UNKNOWN,SUCCEEDED,UNKNOWN", "UNKNOWN,FAILED,UNKNOWN", "UNKNOWN,UNKNOWN,UNKNOWN",
        "CANCELLED,SUCCEEDED,CANCELLED", "CANCELLED,FAILED,CANCELLED", "CANCELLED,UNKNOWN,CANCELLED" })
    void processUnknownAndCoreCancellationHaveExplicitPrecedence(String process, NativeCliReceipt.State protocol, String expected) {
        assertEquals(expected, NativeCliReceipt.executionStatus(process, protocol));
    }

    @Test
    void unsupportedProcessStatesAndMissingProtocolCannotInventSuccess() {
        assertEquals("UNKNOWN", NativeCliReceipt.executionStatus("new-process-state", SUCCEEDED));
        assertEquals("UNKNOWN", NativeCliReceipt.executionStatus(null, SUCCEEDED));
        assertEquals("UNKNOWN", NativeCliReceipt.executionStatus("SUCCEEDED", null));
    }

    @Test
    void coreMetadataProjectionRemovesRawPayloadsAndOpaqueIdentityValues() throws Exception {
        var original = parse("CODEX", stream(thread(), start(), message("done"), complete()));
        var supplied = new java.util.LinkedHashMap<>(original.metadata());
        supplied.put("stdout", "sk-private-stdout"); supplied.put("errors", Map.of("message", "sk-private-error"));
        supplied.put("threadId", "sk-private-opaque-identity");
        var safe = NativeCliReceipt.metadata(supplied);
        assertEquals("SUCCEEDED", safe.get("state"));
        assertEquals(sha(THREAD), safe.get("threadIdSha256"));
        assertFalse(safe.containsKey("threadId")); assertFalse(safe.containsKey("stdout")); assertFalse(safe.containsKey("errors"));
        assertFalse(safe.toString().contains("sk-private"));
        assertThrows(UnsupportedOperationException.class, () -> safe.put("state", "changed"));
    }

    @Test
    void uuidIdentitiesRemainHashedInsteadOfBeingPersistableAsRawMetadata() throws Exception {
        var codex = parse("CODEX", stream(thread(), start(), message("done"), complete()));
        var claudeReceipt = parse("CLAUDE", claude("success", false, "done", null));
        for (var receipt : new NativeCliReceipt.Receipt[] { codex, claudeReceipt }) {
            var safe = NativeCliReceipt.metadata(receipt.metadata());
            String identity = "CODEX".equals(safe.get("engine")) ? THREAD : SESSION;
            String idField = "CODEX".equals(safe.get("engine")) ? "threadId" : "sessionId";
            String hashField = "CODEX".equals(safe.get("engine")) ? "threadIdSha256" : "sessionIdSha256";
            assertEquals(sha(identity), safe.get(hashField));
            assertFalse(safe.containsKey(idField));
            assertFalse(safe.toString().contains(identity));
        }
    }

    @Test
    void coreMetadataProjectionAcceptsKnownSuccessfulFailedAndUnknownReceipts() throws Exception {
        for (var receipt : new NativeCliReceipt.Receipt[] {
            parse("CODEX", stream(thread(), start(), complete())), parse("CODEX", stream(thread(), start(), fail("error"))),
            parse("CODEX", stream(thread(), start())), parse("CLAUDE", claude("success", false, "done", null)),
            parse("CLAUDE", claude("success", true, "failed", null)), parser.parse("CLAUDE", "", false),
            parser.parse("CODEX", "x".repeat(NativeCliReceipt.MAX_STDOUT_CHARS + 1), false) }) {
            var safe = NativeCliReceipt.metadata(receipt.metadata());
            assertEquals(receipt.state().name(), safe.get("state"));
            assertEquals(receipt.metadata().get("reason"), safe.get("reason"));
            assertEquals(receipt.metadata().get("stdoutSha256"), safe.get("stdoutSha256"));
        }
    }

    @Test
    void coreMetadataRejectsMalformedTypedFactsAndConflictingProcessClaims() throws Exception {
        var receipt = parse("CODEX", stream(thread(), start(), message("done"), complete()));
        for (var change : new Object[][] { { "reason", "raw provider secret" }, { "turnCount", "1" }, { "turnCount", 1.0 },
            { "protocolVersion", 2 }, { "stdoutTruncated", true }, { "streamsComplete", false }, { "stdoutSha256", "raw private text" } }) {
            var supplied = new java.util.LinkedHashMap<>(receipt.metadata()); supplied.put((String) change[0], change[1]);
            assertEquals("INVALID_RECEIPT_METADATA", NativeCliReceipt.metadata(supplied).get("reason"));
            assertFalse(NativeCliReceipt.metadata(supplied).toString().contains("raw provider secret"));
        }
        var supplied = new java.util.LinkedHashMap<>(receipt.metadata());
        supplied.put("processState", "UNKNOWN"); supplied.put("executionState", "SUCCEEDED"); supplied.put("outputComplete", true);
        assertEquals("UNKNOWN", NativeCliReceipt.metadata(supplied).get("state"));
        supplied.put("processState", "SUCCEEDED"); supplied.put("processExitCode", 1);
        assertEquals("UNKNOWN", NativeCliReceipt.metadata(supplied).get("state"));
        supplied.put("processExitCode", 0); supplied.put("outputComplete", false);
        assertEquals("UNKNOWN", NativeCliReceipt.metadata(supplied).get("state"));
    }

    private NativeCliReceipt.Receipt parse(String engine, String stdout) { return parser.parse(engine, stdout, false); }

    private static void assertUnknown(NativeCliReceipt.Receipt receipt, String reason) {
        assertEquals(UNKNOWN, receipt.state(), receipt.metadata().toString());
        assertEquals(reason, receipt.metadata().get("reason"));
        assertEquals("", receipt.output(), "unknown output cannot accidentally become a reusable final answer");
    }

    private String thread() throws Exception { return json.writeValueAsString(Map.of("type", "thread.started", "thread_id", THREAD)); }
    private static String start() { return "{\"type\":\"turn.started\"}"; }
    private static String complete() { return "{\"type\":\"turn.completed\",\"usage\":{\"input_tokens\":7,\"cached_input_tokens\":0,\"output_tokens\":3}}"; }
    private String message(String text) throws Exception { return json.writeValueAsString(Map.of("type", "item.completed", "item", Map.of("id", "item_1", "type", "agent_message", "text", text))); }
    private String fail(String message) throws Exception { return json.writeValueAsString(Map.of("type", "turn.failed", "error", Map.of("message", message))); }
    private String error(String message) throws Exception { return json.writeValueAsString(Map.of("type", "error", "message", message)); }
    private static String stream(String... values) { return String.join("\n", values); }

    private String claude(String subtype, boolean error, String output, String terminal) throws Exception {
        var result = new java.util.LinkedHashMap<String, Object>(Map.of("type", "result", "subtype", subtype, "is_error", error, "session_id", SESSION, "result", output));
        if (terminal != null) result.put("terminal_reason", terminal);
        return json.writeValueAsString(result);
    }

    private static String sha(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }
}
