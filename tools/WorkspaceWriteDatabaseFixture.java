package dev.agentworkbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;

/**
 * 仅在专属实际 Jar 夹具中使原授予或释放回执失败，不修改生产授予逻辑。
 */
public class WorkspaceWriteDatabaseFixture {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !Set.of("fail-grant", "fail-release", "clear").contains(arguments[1]) || !arguments[2].matches("[0-9a-f-]{36}")) throw new IllegalArgumentException("fixture-data-directory mode original-task-id");
        Path data = Path.of(arguments[0]).toAbsolutePath().normalize(), database = data.resolve("workbench.sqlite");
        if (!Files.isRegularFile(database) || !Files.isRegularFile(data.getParent().resolve("reports/fixture.json"))) throw new IllegalStateException("Only an existing dedicated workspace-write fixture database is supported");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            try (var task = connection.prepareStatement("SELECT 1 FROM entities WHERE kind='task' AND id=?")) {
                task.setString(1, arguments[2]); try (var rows = task.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Original fixture task is missing"); }
            }
            if ("clear".equals(arguments[1])) sql.execute("DROP TRIGGER IF EXISTS workspace_write_jar_fault");
            else {
                String state = "fail-grant".equals(arguments[1]) ? "HELD" : "RELEASED";
                sql.execute("CREATE TRIGGER workspace_write_jar_fault BEFORE UPDATE ON entities WHEN new.kind='workspaceWriteLease' AND json_extract(new.body,'$.taskId')='" + arguments[2]
                    + "' AND json_extract(new.body,'$.state')='" + state + "' BEGIN SELECT RAISE(ABORT,'original workspace-write Jar receipt fault'); END");
            }
        }
        System.out.println("Updated the dedicated workspace-write receipt fault.");
    }
}
