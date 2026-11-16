package dev.agentworkbench.runtime;

import dev.agentworkbench.ApiException;

/**
 * 原 Worker 明确在效果开始前拒绝撤销，区别于核心收到结果后的归属失效。
 */
public final class CheckpointRestoreRejected extends ApiException {
    public CheckpointRestoreRejected(int status, String message) { super(status, message); }
}
