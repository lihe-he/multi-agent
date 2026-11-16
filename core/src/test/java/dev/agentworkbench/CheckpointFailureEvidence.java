package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.extension.AfterTestExecutionCallback;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.io.TempDir;
import org.opentest4j.TestAbortedException;
import static dev.agentworkbench.Json.*;

/**
 * 测试失败后、JUnit 清理前保存原夹具；复制失败只记证据缺口，不替换原测试异常。
 */
public final class CheckpointFailureEvidence implements AfterTestExecutionCallback {
    @Override public void afterTestExecution(ExtensionContext context) {
        if (context.getExecutionException().isEmpty() || context.getExecutionException().get() instanceof TestAbortedException) return;
        try { preserve(context); }
        catch (Exception exception) {
            System.err.println("Original failed test fixture preservation gap for " + context.getDisplayName() + ": " + exception);
        }
    }

    private void preserve(ExtensionContext context) throws Exception {
        Path evidenceRoot = Path.of("target").toAbsolutePath().normalize();
        Path evidence = evidenceRoot.resolve("checkpoint-evidence-test-" + UUID.randomUUID());
        if (!evidence.startsWith(evidenceRoot)) throw new IllegalStateException("Invalid test evidence directory");
        Files.createDirectories(evidence);
        List<Map<String, Object>> files = new ArrayList<>();
        List<Map<String, Object>> gaps = new ArrayList<>();
        Map<String, Object> receipt = obj("recordedAt", now(), "test", context.getDisplayName(), "class", context.getRequiredTestClass().getName(),
            "originalFailure", context.getExecutionException().orElseThrow().toString(), "scope", "ORIGINAL_FAILED_TEST_FIXTURE_BEFORE_JUNIT_CLEANUP", "files", files, "copyGaps", gaps);
        try {
            Object instance = context.getRequiredTestInstance();
            for (var field : instance.getClass().getDeclaredFields()) {
                if (!field.isAnnotationPresent(TempDir.class) || !field.getType().equals(Path.class)) continue;
                field.setAccessible(true); Path source = ((Path) field.get(instance)).toRealPath();
                Path temporaryRoot = Path.of(System.getProperty("java.io.tmpdir")).toRealPath();
                if (!source.startsWith(temporaryRoot) || source.equals(temporaryRoot)) throw new IllegalStateException("JUnit fixture is outside the original temporary root");
                receipt.put("originalFixture", source.toString());
                Path destination = evidence.resolve("original-fixture"); Files.createDirectory(destination);
                Files.walkFileTree(source, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attributes) throws java.io.IOException {
                        Files.createDirectories(destination.resolve(source.relativize(directory))); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) throws java.io.IOException {
                        Path relative = source.relativize(file), copied = destination.resolve(relative);
                        try {
                            if (attributes.isSymbolicLink()) {
                                String link = Files.readSymbolicLink(file).toString();
                                Files.copy(file, copied, LinkOption.NOFOLLOW_LINKS);
                                files.add(obj("file", relative.toString(), "type", "SYMBOLIC_LINK", "target", link));
                            } else {
                                if (!attributes.isRegularFile()) throw new java.io.IOException("Unsupported original fixture file: " + relative);
                                String original = hash(Files.readAllBytes(file));
                                Files.copy(file, copied, StandardCopyOption.COPY_ATTRIBUTES, LinkOption.NOFOLLOW_LINKS);
                                if (!original.equals(hash(Files.readAllBytes(copied))) || !original.equals(hash(Files.readAllBytes(file))))
                                    throw new java.io.IOException("Original fixture changed during preservation: " + relative);
                                files.add(obj("file", relative.toString(), "type", "REGULAR", "bytes", Files.size(copied), "sha256", original));
                            }
                        } catch (Exception exception) { gaps.add(obj("file", relative.toString(), "reason", exception.toString())); }
                        return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult visitFileFailed(Path file, java.io.IOException exception) {
                        gaps.add(obj("file", source.relativize(file).toString(), "reason", exception.toString()));
                        return FileVisitResult.CONTINUE;
                    }
                });
                receipt.put("completeCopy", gaps.isEmpty());
                if (!gaps.isEmpty()) receipt.put("copyGap", "Some original fixture files were unreadable or changed during copying; see copyGaps");
            }
            if (!receipt.containsKey("originalFixture")) receipt.put("copyGap", "No original Path TempDir field was available");
        } catch (Exception exception) { receipt.put("completeCopy", false); receipt.put("copyGap", exception.toString()); }
        Files.write(evidence.resolve("fixture-preservation.json"), new ObjectMapper().writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
    }

    private static String hash(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }
}
