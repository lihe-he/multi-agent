package dev.agentworkbench.runtime;

import java.util.List;
import java.util.Map;

/**
 * 引擎提供固定工具与逐请求资源边界；模型不能自行构造执行上下文。
 */
public interface AgentToolSession {
    List<Map<String, Object>> definitions();
    AutoCloseable acquireModel();
    default AutoCloseable acquireTool() { return () -> {}; }
    Map<String, Object> invoke(String alias, Map<String, Object> arguments);
    default Map<String, Object> invoke(String alias, Map<String, Object> arguments, String requestId, String toolCallId) { return invoke(alias, arguments); }
    default Map<String, Object> permissions() { return Map.of(); }
    default List<Map<String, Object>> inbox() { return List.of(); }
    default void checkCurrent() { }
}
