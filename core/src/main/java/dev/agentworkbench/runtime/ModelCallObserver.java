package dev.agentworkbench.runtime;

import java.util.Map;
import java.util.function.BooleanSupplier;

/**
 * 请求派发前原子承诺预算与身份，收到结果后保存观测；普通预算回调仍可使用。
 */
public interface ModelCallObserver extends BooleanSupplier {
    boolean begin(Map<String, Object> metadata);
    void end(Map<String, Object> observation);

    @Override
    default boolean getAsBoolean() { return begin(Map.of()); }
}
