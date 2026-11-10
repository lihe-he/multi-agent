package dev.agentworkbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

/**
 * 专属 Jar 夹具在真实准备完成后的任务保存处注入故障，原文件和 Worker 回执保留。
 */
public class WorkspaceDatabaseFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("fail-ready", "clear").contains(args[1]) || !args[2].matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("fixture-data-directory mode task-id");
        Path data = Path.of(args[0]).toAbsolutePath().normalize(), database = data.resolve("workbench.sqlite");
        if (!Files.isRegularFile(database) || !Files.isRegularFile(data.getParent().resolve("reports/fixture.json"))) throw new IllegalStateException("Only an existing dedicated workspace fixture database is supported");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            if ("clear".equals(args[1])) sql.execute("DROP TRIGGER IF EXISTS workspace_jar_ready_fault");
            else sql.execute("CREATE TRIGGER workspace_jar_ready_fault BEFORE UPDATE ON entities WHEN new.kind='task' AND new.id='" + args[2]
                + "' AND json_extract(new.body,'$.workspaceOwnership') IS NOT NULL BEGIN SELECT RAISE(ABORT,'workspace READY task receipt fault'); END");
        }
        System.out.println("Updated the dedicated workspace receipt fault.");
    }
}
