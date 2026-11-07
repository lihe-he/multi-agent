package dev.agentworkbench;

import dev.agentworkbench.runtime.AgentToolSession;
import dev.agentworkbench.runtime.ModelCallObserver;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;

/**
 * 核心边界先脱敏再截断；工具仅取得该运行实际成功回执的本地身份。
 */
final class RequestObservation implements ModelCallObserver {
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;
    private final String taskId;
    private final String runId;
    private final boolean reserved;
    private final BooleanSupplier cancelled;
    private final AtomicBoolean denied;
    private final Runnable before;
    private final AgentToolSession tools;
    private final int maxCalls;
    private String requestId = "";
    private String receiptId = "";

    RequestObservation(ExecutionStore store, WorkbenchCatalog catalog, String taskId, String runId, boolean reserved, BooleanSupplier cancelled, AtomicBoolean denied, Runnable before, AgentToolSession tools, int maxCalls) {
        this.store = store; this.catalog = catalog; this.taskId = taskId; this.runId = runId; this.reserved = reserved;
        this.cancelled = cancelled; this.denied = denied; this.before = before; this.tools = tools; this.maxCalls = maxCalls;
    }

    @Override
    public boolean begin(Map<String, Object> metadata) {
        if (cancelled.getAsBoolean()) return false;
        before.run();
        if (cancelled.getAsBoolean()) return false;
        if (maxCalls > 0 && num(store.get("agentRun", runId), "calls", 0) >= maxCalls) { denied.set(true); return false; }
        if (!requestId.isBlank()) end(obj("state", "UNKNOWN", "errorType", "MISSING_OBSERVATION", "reason", "前次请求缺少观测回执"));
        Map<String, Object> safeInput = new LinkedHashMap<>(metadata);
        if (safeInput.containsKey("inputPreview")) {
            String redacted = catalog.redact(str(safeInput, "inputPreview"));
            safeInput.put("inputPreview", redacted.substring(0, Math.min(6000, redacted.length()))); safeInput.put("inputTruncated", redacted.length() > 6000);
        }
        receiptId = ""; requestId = store.beginModelRequest(taskId, runId, reserved, safeInput);
        if (requestId.isBlank()) denied.set(true);
        return !requestId.isBlank();
    }

    @Override
    public void end(Map<String, Object> observation) {
        if (requestId.isBlank()) return;
        Map<String, Object> safe = new LinkedHashMap<>(observation);
        for (String field : List.of("providerRequestId", "responseId", "responseModel", "finishReason", "outputPreview", "reason", "errorType")) if (safe.containsKey(field)) {
            String redacted = catalog.redact(str(safe, field)); safe.put(field, redacted.substring(0, Math.min(6000, redacted.length())));
            if (field.equals("outputPreview")) safe.put("outputTruncated", redacted.length() > 6000);
        }
        store.finishModelRequest(requestId, safe); receiptId = "SUCCEEDED".equals(str(safe, "state")) ? requestId : ""; requestId = "";
    }

    @Override public AgentToolSession tools() { return tools; }
    @Override public String lastRequestId() { return receiptId; }
}
