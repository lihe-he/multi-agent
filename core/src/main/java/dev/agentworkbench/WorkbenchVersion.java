package dev.agentworkbench;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * API、MCP 与策略记录共用 Maven 构建版本。
 */
public final class WorkbenchVersion {
    /**
     * 本次构建的版本标识。
     */
    public static final String CURRENT = loadVersion();

    private WorkbenchVersion() {}

    /**
     * 读取已过滤的构建资源，缺失或未过滤时拒绝使用。
     */
    private static String loadVersion() {
        try (InputStream input = WorkbenchVersion.class.getResourceAsStream("/workbench-build.properties")) {
            if (input == null) throw new IllegalStateException("缺少构建版本信息");
            Properties properties = new Properties();
            properties.load(input);
            String version = properties.getProperty("version");
            if (version == null || version.isBlank() || version.contains("@") || version.contains("${"))
                throw new IllegalStateException("构建版本信息未正确生成");
            return version.trim();
        } catch (IOException exception) {
            throw new IllegalStateException("无法读取构建版本信息", exception);
        }
    }
}
