package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.ObjectReader;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * 仅依据完整标准输出解析原生协议，不代替进程停止和成果版本核验。
 */
public final class NativeCliReceipt {
    public static final int MAX_STDOUT_CHARS = 524_288;
    public static final int MAX_EVENTS = 10_000;
    private static final Set<String> CLAUDE_FAILURES = Set.of("error_during_execution", "error_max_turns", "error_max_budget_usd", "error_max_structured_output_retries");
    private static final Set<String> CLAUDE_LIMITS = Set.of("max_turns", "max_budget_usd", "max_structured_output_retries");
    private static final Set<String> PROCESS_STATES = Set.of("SUCCEEDED", "FAILED", "CANCELLED", "UNKNOWN");
    private static final Set<String> REASONS = Set.of("STDOUT_LIMIT", "TRUNCATED_STDOUT", "INCOMPLETE_STREAMS", "EMPTY_STDOUT", "UNSUPPORTED_ENGINE",
        "EVENT_LIMIT", "MALFORMED_JSON", "INVALID_EVENT", "UNREADABLE_STDOUT", "MISSING_THREAD", "MISSING_TURN_TERMINAL", "UNRESOLVED_ERROR",
        "TURN_FAILED", "TURN_COMPLETED", "MULTIPLE_THREADS", "INVALID_THREAD", "INVALID_TURN_SEQUENCE", "CONFLICTING_TURN_TERMINAL",
        "INVALID_TURN_FAILURE", "INVALID_ERROR_EVENT", "INVALID_ITEM_SEQUENCE", "INVALID_AGENT_MESSAGE", "MISSING_RESULT", "INVALID_RESULT",
        "INVALID_RESULT_OUTPUT", "INVALID_TERMINAL_REASON", "CLI_REPORTED_ERROR", "CONFLICTING_RESULT", "UNRECOGNIZED_RESULT_SUBTYPE",
        "CLI_REPORTED_ABORT", "CLI_REPORTED_LIMIT", "UNRECOGNIZED_TERMINAL_REASON", "RESULT_SUCCESS");
    private static final Map<String, Set<String>> TEXT_VALUES = Map.of(
        "engine", Set.of("CODEX", "CLAUDE", "UNSUPPORTED"), "protocol", Set.of("codex-exec-jsonl", "claude-result-json", "unsupported"),
        "state", Set.of("SUCCEEDED", "FAILED", "UNKNOWN"), "reason", REASONS,
        "stdoutDigestState", Set.of("COMPLETE", "NOT_COMPUTED_LIMIT"), "processState", PROCESS_STATES, "executionState", PROCESS_STATES,
        "reportedSubtype", Set.of("success", "error_during_execution", "error_max_turns", "error_max_budget_usd", "error_max_structured_output_retries", "OTHER"),
        "terminalReason", Set.of("completed", "aborted_streaming", "aborted_tools", "max_turns", "max_budget_usd", "max_structured_output_retries", "NOT_REPORTED", "OTHER"));
    private static final Set<String> BOOLEAN_FIELDS = Set.of("stdoutPresent", "stdoutTruncated", "stderrTruncated", "streamsComplete", "outputPresent", "openTurn", "reportedError", "outputComplete");
    private static final Set<String> NUMBER_FIELDS = Set.of("protocolVersion", "stdoutChars", "eventCount", "turnCount", "completedTurns", "failedTurns", "errorEvents", "unknownEventCount", "faultLine", "processExitCode");
    private static final Set<String> HASH_FIELDS = Set.of("stdoutSha256", "threadIdSha256", "sessionIdSha256");
    private static final Pattern SHA256 = Pattern.compile("[0-9a-fA-F]{64}");
    private final ObjectReader reader;

    public enum State { SUCCEEDED, FAILED, UNKNOWN }

    /**
     * output 是待上层脱敏的模型内容；metadata 不保存任意错误文本或不透明标识原文。
     */
    public record Receipt(State state, String output, Map<String, Object> metadata) {
        public Receipt {
            Objects.requireNonNull(state);
            Objects.requireNonNull(output);
            metadata = Map.copyOf(metadata);
        }
    }

    public NativeCliReceipt(ObjectMapper mapper) {
        reader = Objects.requireNonNull(mapper).readerFor(JsonNode.class)
            .with(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
            .with(JsonParser.Feature.STRICT_DUPLICATE_DETECTION);
    }

    /**
     * 调用方只传 stdout；截断、未知格式和缺失终态均不能形成成功回执。
     */
    public Receipt parse(String engine, String stdout, boolean truncated) {
        return parse(engine, stdout, truncated, true);
    }

    /**
     * 管道收尾未确认时仍保留已捕获文本摘要，但不解析为可复用终态。
     */
    public Receipt parse(String engine, String stdout, boolean truncated, boolean streamsComplete) {
        String selected = engine == null ? "UNSUPPORTED" : engine.toUpperCase(Locale.ROOT);
        if (!Set.of("CODEX", "CLAUDE").contains(selected)) selected = "UNSUPPORTED";
        Map<String, Object> metadata = new LinkedHashMap<>();
        metadata.put("protocolVersion", 1);
        metadata.put("engine", selected);
        metadata.put("protocol", switch (selected) { case "CODEX" -> "codex-exec-jsonl"; case "CLAUDE" -> "claude-result-json"; default -> "unsupported"; });
        metadata.put("stdoutSha256", "");
        metadata.put("stdoutDigestState", "NOT_COMPUTED_LIMIT");
        metadata.put("stdoutChars", stdout == null ? 0 : stdout.length());
        metadata.put("stdoutPresent", stdout != null);
        metadata.put("stdoutTruncated", truncated);
        metadata.put("streamsComplete", streamsComplete);
        if (stdout != null && stdout.length() > MAX_STDOUT_CHARS) return unknown(metadata, "STDOUT_LIMIT");
        metadata.put("stdoutSha256", sha256(Objects.requireNonNullElse(stdout, "")));
        metadata.put("stdoutDigestState", "COMPLETE");
        if (truncated) return unknown(metadata, "TRUNCATED_STDOUT");
        if (!streamsComplete) return unknown(metadata, "INCOMPLETE_STREAMS");
        if (stdout == null || stdout.isBlank()) return unknown(metadata, "EMPTY_STDOUT");
        if ("UNSUPPORTED".equals(selected)) return unknown(metadata, "UNSUPPORTED_ENGINE");
        return "CODEX".equals(selected) ? codex(stdout, metadata) : claude(stdout, metadata);
    }

    /**
     * 原进程未知或非零退出与协议成功冲突时保持未知；核心取消只由原进程事实决定。
     */
    public static String executionStatus(String processState, State protocolState) {
        if ("CANCELLED".equals(processState)) return "CANCELLED";
        if ("SUCCEEDED".equals(processState) && protocolState != null) return protocolState.name();
        if ("FAILED".equals(processState) && protocolState == State.FAILED) return "FAILED";
        return "UNKNOWN";
    }

    /**
     * 核心只保存固定键和有界标量；不透明身份、任意错误文本及嵌套载荷不能透传。
     */
    public static Map<String, Object> metadata(Object value) {
        if (!(value instanceof Map<?, ?> supplied)) return invalidMetadata();
        Map<String, Object> safe = new LinkedHashMap<>();
        for (var entry : supplied.entrySet()) {
            if (!(entry.getKey() instanceof String key)) continue;
            Object field = entry.getValue();
            if (TEXT_VALUES.containsKey(key)) {
                if (!(field instanceof String text) || text.length() > 128 || !TEXT_VALUES.get(key).contains(text)) return invalidMetadata();
                safe.put(key, text);
            } else if (BOOLEAN_FIELDS.contains(key)) {
                if (!(field instanceof Boolean)) return invalidMetadata();
                safe.put(key, field);
            } else if (NUMBER_FIELDS.contains(key)) {
                if (!(field instanceof Integer || field instanceof Long)) return invalidMetadata();
                long number = ((Number) field).longValue();
                long maximum = "stdoutChars".equals(key) || "processExitCode".equals(key) ? Integer.MAX_VALUE
                    : "faultLine".equals(key) ? MAX_STDOUT_CHARS + 1L : MAX_EVENTS + 1L;
                if (number < ("processExitCode".equals(key) ? Integer.MIN_VALUE : 0) || number > maximum) return invalidMetadata();
                safe.put(key, number);
            } else if (HASH_FIELDS.contains(key)) {
                if (!(field instanceof String hash) || !(hash.isEmpty() && "stdoutSha256".equals(key) || SHA256.matcher(hash).matches())) return invalidMetadata();
                safe.put(key, hash.toLowerCase(Locale.ROOT));
            }
        }
        if (!safe.keySet().containsAll(Set.of("protocolVersion", "engine", "protocol", "state", "reason", "stdoutDigestState", "stdoutSha256",
                "stdoutChars", "stdoutPresent", "stdoutTruncated", "streamsComplete", "outputPresent"))
                || !Long.valueOf(1).equals(safe.get("protocolVersion"))) return invalidMetadata();
        String engine = (String) safe.get("engine"), state = (String) safe.get("state"), reason = (String) safe.get("reason");
        String protocol = switch (engine) { case "CODEX" -> "codex-exec-jsonl"; case "CLAUDE" -> "claude-result-json"; default -> "unsupported"; };
        if (!protocol.equals(safe.get("protocol"))) return invalidMetadata();
        if ("COMPLETE".equals(safe.get("stdoutDigestState")) && !SHA256.matcher((String) safe.get("stdoutSha256")).matches()) return invalidMetadata();
        if ("NOT_COMPUTED_LIMIT".equals(safe.get("stdoutDigestState")) && !"".equals(safe.get("stdoutSha256"))) return invalidMetadata();
        if (!"UNKNOWN".equals(state) && ("UNSUPPORTED".equals(engine) || !Boolean.TRUE.equals(safe.get("stdoutPresent"))
                || ((Number) safe.get("stdoutChars")).longValue() < 1 || ((Number) safe.get("stdoutChars")).longValue() > MAX_STDOUT_CHARS
                || !"COMPLETE".equals(safe.get("stdoutDigestState")))) return invalidMetadata();
        if ("SUCCEEDED".equals(state)) {
            if (!Boolean.TRUE.equals(safe.get("stdoutPresent")) || Boolean.TRUE.equals(safe.get("stdoutTruncated"))
                    || !Boolean.TRUE.equals(safe.get("streamsComplete")) || !"COMPLETE".equals(safe.get("stdoutDigestState"))) return invalidMetadata();
            if ("CODEX".equals(engine) && (!"TURN_COMPLETED".equals(reason) || !safe.containsKey("threadIdSha256")
                    || ((Number) safe.getOrDefault("completedTurns", 0L)).longValue() < 1 || !Long.valueOf(0).equals(safe.get("failedTurns"))
                    || !Boolean.FALSE.equals(safe.get("openTurn")))) return invalidMetadata();
            if ("CLAUDE".equals(engine) && (!"RESULT_SUCCESS".equals(reason) || !safe.containsKey("sessionIdSha256")
                    || !"success".equals(safe.get("reportedSubtype")) || !Boolean.FALSE.equals(safe.get("reportedError"))
                    || !Set.of("completed", "NOT_REPORTED").contains(safe.get("terminalReason")))) return invalidMetadata();
            if ("UNSUPPORTED".equals(engine)) return invalidMetadata();
        }
        if ("FAILED".equals(state) && (!Set.of("TURN_FAILED", "CLI_REPORTED_ERROR", "CLI_REPORTED_ABORT", "CLI_REPORTED_LIMIT").contains(reason)
                || Boolean.TRUE.equals(safe.get("stdoutTruncated")) || !Boolean.TRUE.equals(safe.get("streamsComplete")))) return invalidMetadata();
        if (safe.containsKey("executionState") && (!safe.containsKey("processState")
                || !safe.get("executionState").equals(executionStatus((String) safe.get("processState"), State.valueOf(state))))) return invalidMetadata();
        if ("SUCCEEDED".equals(safe.get("processState")) && safe.containsKey("processExitCode") && !Long.valueOf(0).equals(safe.get("processExitCode"))) return invalidMetadata();
        if ("SUCCEEDED".equals(safe.get("executionState")) && !Boolean.TRUE.equals(safe.get("outputComplete"))) return invalidMetadata();
        return Map.copyOf(safe);
    }

    private static Map<String, Object> invalidMetadata() {
        return Map.of("protocolVersion", 1, "state", "UNKNOWN", "reason", "INVALID_RECEIPT_METADATA");
    }

    private Receipt codex(String stdout, Map<String, Object> metadata) {
        Codex progress = new Codex(metadata);
        try (BufferedReader lines = new BufferedReader(new StringReader(stdout))) {
            String line;
            int lineNumber = 0;
            while ((line = lines.readLine()) != null) {
                lineNumber++;
                if (line.isBlank()) continue;
                if (++progress.events > MAX_EVENTS) return progress.unknown("EVENT_LIMIT", lineNumber);
                JsonNode event;
                try { event = reader.readValue(line); }
                catch (IOException | RuntimeException exception) { return progress.unknown("MALFORMED_JSON", lineNumber); }
                if (!event.isObject() || !text(event, "type")) return progress.unknown("INVALID_EVENT", lineNumber);
                String problem = progress.accept(event);
                if (problem != null) return progress.unknown(problem, lineNumber);
            }
        } catch (IOException exception) {
            return progress.unknown("UNREADABLE_STDOUT", 0);
        }
        if (progress.threadId == null) return progress.unknown("MISSING_THREAD", 0);
        if (progress.inTurn || progress.completed + progress.failed == 0) return progress.unknown("MISSING_TURN_TERMINAL", 0);
        if (progress.pendingError) return progress.unknown("UNRESOLVED_ERROR", 0);
        progress.facts();
        return finish(metadata, progress.failed > 0 ? State.FAILED : State.SUCCEEDED,
            progress.lastTerminalOutput, progress.failed > 0 ? "TURN_FAILED" : "TURN_COMPLETED");
    }

    private final class Codex {
        final Map<String, Object> metadata;
        String threadId;
        boolean inTurn;
        boolean pendingError;
        int events;
        int turns;
        int completed;
        int failed;
        int errors;
        int ignored;
        String currentOutput = "";
        String lastTerminalOutput = "";

        Codex(Map<String, Object> metadata) { this.metadata = metadata; }

        String accept(JsonNode event) {
            return switch (event.get("type").textValue()) {
                case "thread.started" -> thread(event);
                case "turn.started" -> start();
                case "turn.completed" -> terminal(event, false);
                case "turn.failed" -> terminal(event, true);
                case "error" -> error(event);
                case "item.started", "item.updated", "item.completed" -> item(event);
                default -> { ignored++; yield null; }
            };
        }

        String thread(JsonNode event) {
            if (threadId != null) return "MULTIPLE_THREADS";
            if (inTurn || turns > 0 || !identity(event, "thread_id")) return "INVALID_THREAD";
            threadId = event.get("thread_id").textValue();
            identityFacts(metadata, "thread", threadId);
            return null;
        }

        String start() {
            if (threadId == null || inTurn) return "INVALID_TURN_SEQUENCE";
            inTurn = true;
            turns++;
            currentOutput = "";
            return null;
        }

        String terminal(JsonNode event, boolean failure) {
            if (threadId == null || !inTurn) return "CONFLICTING_TURN_TERMINAL";
            if (failure && (!event.path("error").isObject() || !event.path("error").path("message").isTextual())) return "INVALID_TURN_FAILURE";
            inTurn = false;
            pendingError = false;
            lastTerminalOutput = currentOutput;
            if (failure) failed++; else completed++;
            return null;
        }

        String error(JsonNode event) {
            if (!event.path("message").isTextual()) return "INVALID_ERROR_EVENT";
            errors++;
            pendingError = true;
            return null;
        }

        String item(JsonNode event) {
            if (threadId == null || !inTurn || !event.path("item").isObject() || !text(event.path("item"), "type")) return "INVALID_ITEM_SEQUENCE";
            JsonNode item = event.get("item");
            if ("agent_message".equals(item.get("type").textValue())) {
                if (!item.path("text").isTextual()) return "INVALID_AGENT_MESSAGE";
                if ("item.completed".equals(event.get("type").textValue())) currentOutput = item.get("text").textValue();
            }
            return null;
        }

        void facts() {
            metadata.put("eventCount", events);
            metadata.put("turnCount", turns);
            metadata.put("completedTurns", completed);
            metadata.put("failedTurns", failed);
            metadata.put("errorEvents", errors);
            metadata.put("unknownEventCount", ignored);
            metadata.put("openTurn", inTurn);
        }

        Receipt unknown(String reason, int line) {
            facts();
            if (line > 0) metadata.put("faultLine", line);
            return NativeCliReceipt.unknown(metadata, reason);
        }
    }

    private Receipt claude(String stdout, Map<String, Object> metadata) {
        JsonNode result;
        try { result = reader.readValue(stdout); }
        catch (IOException | RuntimeException exception) { return unknown(metadata, "MALFORMED_JSON"); }
        if (!result.isObject() || !"result".equals(result.path("type").textValue())) return unknown(metadata, "MISSING_RESULT");
        if (!text(result, "subtype") || !result.path("is_error").isBoolean() || !identity(result, "session_id")) return unknown(metadata, "INVALID_RESULT");
        if (result.hasNonNull("result") && !result.get("result").isTextual()) return unknown(metadata, "INVALID_RESULT_OUTPUT");
        if (result.hasNonNull("terminal_reason") && !text(result, "terminal_reason")) return unknown(metadata, "INVALID_TERMINAL_REASON");
        String subtype = result.get("subtype").textValue();
        String terminal = result.path("terminal_reason").isTextual() ? result.get("terminal_reason").textValue() : "";
        String output = result.path("result").isTextual() ? result.get("result").textValue() : "";
        boolean error = result.get("is_error").booleanValue();
        identityFacts(metadata, "session", result.get("session_id").textValue());
        metadata.put("reportedSubtype", "success".equals(subtype) || CLAUDE_FAILURES.contains(subtype) ? subtype : "OTHER");
        metadata.put("reportedError", error);
        metadata.put("terminalReason", Set.of("completed", "aborted_streaming", "aborted_tools").contains(terminal) || CLAUDE_LIMITS.contains(terminal) ? terminal : terminal.isEmpty() ? "NOT_REPORTED" : "OTHER");
        if (error) return finish(metadata, State.FAILED, output, "CLI_REPORTED_ERROR");
        if (CLAUDE_FAILURES.contains(subtype)) return unknown(metadata, "CONFLICTING_RESULT");
        if (!"success".equals(subtype)) return unknown(metadata, "UNRECOGNIZED_RESULT_SUBTYPE");
        if (Set.of("aborted_streaming", "aborted_tools").contains(terminal)) return finish(metadata, State.FAILED, output, "CLI_REPORTED_ABORT");
        if (CLAUDE_LIMITS.contains(terminal)) return finish(metadata, State.FAILED, output, "CLI_REPORTED_LIMIT");
        if (!terminal.isEmpty() && !"completed".equals(terminal)) return unknown(metadata, "UNRECOGNIZED_TERMINAL_REASON");
        return finish(metadata, State.SUCCEEDED, output, "RESULT_SUCCESS");
    }

    private static boolean text(JsonNode value, String field) {
        return value.path(field).isTextual() && !value.get(field).textValue().isBlank();
    }

    private static boolean identity(JsonNode value, String field) {
        return text(value, field) && value.get(field).textValue().length() <= 256;
    }

    private static void identityFacts(Map<String, Object> metadata, String kind, String value) {
        metadata.put(kind + "IdSha256", sha256(value));
    }

    private static Receipt unknown(Map<String, Object> metadata, String reason) {
        return finish(metadata, State.UNKNOWN, "", reason);
    }

    private static Receipt finish(Map<String, Object> metadata, State state, String output, String reason) {
        metadata.put("state", state.name());
        metadata.put("reason", reason);
        metadata.put("outputPresent", !output.isEmpty());
        return new Receipt(state, output, metadata);
    }

    private static String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable", exception); }
    }
}
