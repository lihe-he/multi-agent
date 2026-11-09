package dev.agentworkbench;

import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

/**
 * 独立测试进程只向专属 SQLite 夹具加入失败触发器，不修改日常核心数据库。
 */
public class DispatchDatabaseFixture {
    public static void main(String[] args) throws Exception {
        if (args.length != 3 || !Set.of("taskExecution", "dispatchAction").contains(args[1]) || !args[2].matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("fixture-data-directory record-kind task-id");
        Path database = Path.of(args[0]).toAbsolutePath().resolve("workbench.sqlite");
        if (!java.nio.file.Files.isRegularFile(database)) throw new IllegalStateException("The dedicated fixture database must already exist");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            sql.execute("CREATE TRIGGER dispatch_jar_fixture_fault BEFORE INSERT ON entities WHEN new.kind='" + args[1]
                + "' AND json_extract(new.body,'$.taskId')='" + args[2] + "' BEGIN SELECT RAISE(ABORT,'controlled dispatch approval fault'); END");
        }
        System.out.println("Installed the dedicated approval transaction fault.");
    }
}
