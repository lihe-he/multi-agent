package dev.agentworkbench;

import java.util.Map;

/**
 * 实际执行者以稳定操作编号领取归属；客户端连接不参与执行租约生命周期。
 */
interface ExecutionWorker extends AutoCloseable {
    void dispatch(String operationId, String workspace, Map<String, Object> details);
    void dispatchTeam(String operationId, String workspace);
    void finish(String operationId, String state, Map<String, Object> details);
    void finishTeam(String operationId, String state, Map<String, Object> details);
    void evidence(String taskId, String operationId, Map<String, Object> evidence);
    boolean current(String taskId);
    Map<String, Object> identity();
    void settleRun(String runId);
    @Override void close();
}
