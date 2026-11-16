package dev.agentworkbench.runtime;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 请求派发前原子承诺预算与身份，收到结果后保存观测；普通预算回调仍可使用。
 */
public interface ModelCallObserver extends BooleanSupplier {
    boolean begin(Map<String, Object> metadata);
    void end(Map<String, Object> observation);
    default AgentToolSession tools() { return null; }
    default String lastRequestId() { return ""; }

    /**
     * 文件检查点上下文由原核心提供，不从模型参数取得。
     */
    default Map<String, Object> checkpointContext() { return Map.of(); }

    /**
     * 工具完成后单独发布文件快照，保存失败不改写原工具回执。
     */
    default void checkpoint(Map<String, Object> descriptor) { }

    @Override
    default boolean getAsBoolean() { return begin(Map.of()); }
}
