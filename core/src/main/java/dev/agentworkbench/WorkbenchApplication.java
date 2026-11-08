package dev.agentworkbench;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

/**
 * 本地研发工作台核心入口。
 */
@SpringBootApplication
public class WorkbenchApplication {
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && "--workbench-internal-worker".equals(args[0])) { WorkerProcessMain.main(args); return; }
        prepareWindowsPipeDirectory();
        SpringApplication.run(WorkbenchApplication.class, args);
    }

    /**
     * 避免 Windows 短格式临时路径导致 JDK 21 本地管道创建失败。
     */
    private static void prepareWindowsPipeDirectory() {
        if (!System.getProperty("os.name", "").startsWith("Windows") || System.getProperty("jdk.net.unixdomain.tmpdir") != null) { return; }
        try {
            String configured = System.getenv("WORKBENCH_DATA_DIR");
            java.nio.file.Path temporary = java.nio.file.Path.of(configured == null || configured.isBlank() ? "./data" : configured).toAbsolutePath().resolve("nio-tmp");
            java.nio.file.Files.createDirectories(temporary);
            System.setProperty("jdk.net.unixdomain.tmpdir", temporary.toString());
        } catch (java.io.IOException exception) {
            throw new IllegalStateException("无法准备本地进程通信目录", exception);
        }
    }
}
