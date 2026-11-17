package dev.agentworkbench.runtime;

import dev.agentworkbench.CheckpointFailureEvidence;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 固定发布包字节、损坏拒绝和真实目录别名分别验证，保留原失败现场。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class NativePtyBundleTest {
    @TempDir(cleanup = CleanupMode.ON_SUCCESS) Path directory;

    @Test
    void separateExtractionsKeepExactReleasedPlatformBytesAndDistinctOwnership() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data")).toRealPath();
        NativePtyBundle first = NativePtyBundle.extract(data), second = NativePtyBundle.extract(data);
        assertNotEquals(first.directory(), second.directory()); assertEquals(first.facts().get("releasedJarSha256"), second.facts().get("releasedJarSha256"));
        for (NativePtyBundle bundle : java.util.List.of(first, second)) {
            bundle.verify(); assertFalse(objects(bundle.facts().get("files")).isEmpty());
            for (Map<String, Object> file : objects(bundle.facts().get("files"))) {
                byte[] bytes = Files.readAllBytes(bundle.directory().resolve(str(file, "path")));
                assertEquals(((Number) file.get("bytes")).longValue(), bytes.length); assertEquals(str(file, "sha256"), hash(bytes));
                try (var input = getClass().getClassLoader().getResourceAsStream("resources/com/pty4j/native/" + str(file, "path"))) {
                    assertNotNull(input); assertArrayEquals(input.readAllBytes(), bytes);
                }
            }
        }
        assertNotEquals(first.facts().get("ownerSha256"), second.facts().get("ownerSha256"));
    }

    @Test
    void changedNativeBytesAndChangedOwnerReceiptCannotPassTheOriginalVerification() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data")).toRealPath(); NativePtyBundle changed = NativePtyBundle.extract(data);
        Map<String, Object> file = objects(changed.facts().get("files")).getFirst(); Path path = changed.directory().resolve(str(file, "path"));
        byte[] bytes = Files.readAllBytes(path); bytes[0] ^= 1; Files.write(path, bytes);
        assertThrows(IOException.class, changed::verify); assertArrayEquals(bytes, Files.readAllBytes(path));
        NativePtyBundle owner = NativePtyBundle.extract(data); Path receipt = owner.directory().resolve("owner.json");
        byte[] replacement = "{\"pid\":1,\"foreign\":true}".getBytes(java.nio.charset.StandardCharsets.UTF_8); Files.write(receipt, replacement);
        assertThrows(IOException.class, owner::verify); assertArrayEquals(replacement, Files.readAllBytes(receipt));
    }

    @Test
    void actualDirectoryAliasCannotRedirectNativeExtractionIntoForeignFiles() throws Exception {
        Path data = Files.createDirectory(directory.resolve("data")).toRealPath(), foreign = Files.createDirectory(directory.resolve("foreign")).toRealPath();
        Path alias = data.resolve("terminal-native"); Files.writeString(foreign.resolve("original.txt"), "original foreign bytes\n");
        if (System.getProperty("os.name", "").startsWith("Windows")) {
            Process link = new ProcessBuilder(Path.of(System.getenv("SystemRoot"), "System32", "cmd.exe").toString(), "/D", "/C", "mklink", "/J", alias.toString(), foreign.toString())
                .redirectErrorStream(true).start(); String output = new String(link.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            assertTrue(link.waitFor(15, TimeUnit.SECONDS), output); assertEquals(0, link.exitValue(), output);
        } else Files.createSymbolicLink(alias, foreign);
        assertEquals(foreign, alias.toRealPath()); assertThrows(IOException.class, () -> NativePtyBundle.extract(data));
        assertEquals("original foreign bytes\n", Files.readString(foreign.resolve("original.txt")));
        try (var files = Files.list(foreign)) { assertEquals(1, files.count()); }
    }

    private static String hash(byte[] bytes) throws Exception { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
}
