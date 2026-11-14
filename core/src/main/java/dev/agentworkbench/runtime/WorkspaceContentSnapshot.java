package dev.agentworkbench.runtime;

import java.util.Map;

/**
 * 不可变内容清单；内容指纹与 Git blob 摘要各自保留，预览字节须匹配原清单。
 */
public record WorkspaceContentSnapshot(String version, String objectFormat, Map<String, File> files) {
    public WorkspaceContentSnapshot {
        files = Map.copyOf(files);
    }

    /**
     * index 模式与实际执行位分别参与原内容指纹，Git 比较使用实际显示模式。
     */
    public record File(String path, boolean exists, String indexMode, boolean executable, long bytes, String sha256, String gitBlob) {
        public String effectiveMode() { return exists ? executable ? "100755" : "100644" : "000000"; }
    }
}
