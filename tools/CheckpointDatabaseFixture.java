package dev.agentworkbench;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.DriverManager;
import java.util.Set;
import java.util.UUID;

/**
 * 专属普通 Jar 夹具在文件返回之后拒绝 SQLite 回执，生产撤销代码保持原字节。
 */
public final class CheckpointDatabaseFixture {
    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 3 || !Set.of("fail-ack", "fail-publish", "clear").contains(arguments[1])) throw new IllegalArgumentException("fixture-data-directory mode original-task-id");
        String taskId = UUID.fromString(arguments[2]).toString();
        if (!taskId.equals(arguments[2])) throw new IllegalArgumentException("Original canonical task identity required");
        Path data = Path.of(arguments[0]).toRealPath(), database = data.resolve("workbench.sqlite"), fixture = data.getParent().resolve("reports/fixture.json");
        if (!data.getFileName().toString().equals("data") || !data.getParent().getFileName().toString().startsWith("checkpoints-")
                || !Files.isRegularFile(database) || !Files.isRegularFile(fixture) || !Files.readString(fixture).contains("ACTUAL_JAR_CHECKPOINT_RESTORE_WITH_SQLITE_RECEIPT_FAULTS"))
            throw new IllegalStateException("Only the dedicated checkpoint acceptance database is supported");
        try (var connection = DriverManager.getConnection("jdbc:sqlite:" + database); var sql = connection.createStatement()) {
            sql.execute("PRAGMA busy_timeout=5000");
            try (var task = connection.prepareStatement("SELECT 1 FROM entities WHERE kind='task' AND id=?")) {
                task.setString(1, taskId); try (var rows = task.executeQuery()) { if (!rows.next()) throw new IllegalArgumentException("Original fixture task is missing"); }
            }
            if (arguments[1].equals("clear")) sql.execute("DROP TRIGGER IF EXISTS checkpoint_jar_fault");
            else {
                String condition = arguments[1].equals("fail-ack")
                    ? "new.kind='operation' AND json_extract(new.body,'$.kind')='CHECKPOINT_RESTORE' AND json_extract(new.body,'$.state')='ACKNOWLEDGED'"
                    : "new.kind='checkpointRestorePreview' AND json_extract(new.body,'$.state')='APPLIED'";
                sql.execute("CREATE TRIGGER checkpoint_jar_fault BEFORE UPDATE ON entities WHEN json_extract(new.body,'$.taskId')='" + taskId + "' AND " + condition
                    + " BEGIN SELECT RAISE(ABORT,'original checkpoint Jar receipt fault'); END");
            }
        }
        System.out.println("Updated the dedicated checkpoint receipt fault.");
    }
}
