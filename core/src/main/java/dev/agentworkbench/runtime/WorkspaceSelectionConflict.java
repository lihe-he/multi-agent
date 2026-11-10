package dev.agentworkbench.runtime;

/**
 * 源快照过期是可操作的选择冲突，公开固定提示而不暴露 Git 内部错误。
 */
public final class WorkspaceSelectionConflict extends IllegalArgumentException {
    public WorkspaceSelectionConflict() {
        super("仓库提交、文件或暂存区已变化，请刷新启动选择后重新确认");
    }
}
