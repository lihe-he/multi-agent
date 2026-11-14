package dev.agentworkbench;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 核对实际编译资源与 Maven 版本声明。
 */
class WorkbenchVersionTest {
    /**
     * 运行时版本必须来自本次构建，不能保留旧值或模板占位符。
     */
    @Test
    void generatedRuntimeVersionMatchesTheCurrentMavenBuild() {
        String expected = System.getProperty("workbench.expected-version");
        assertNotNull(expected);
        assertEquals(expected, WorkbenchVersion.CURRENT);
        assertFalse(WorkbenchVersion.CURRENT.contains("@"));
        assertFalse(WorkbenchVersion.CURRENT.contains("${"));
    }
}
