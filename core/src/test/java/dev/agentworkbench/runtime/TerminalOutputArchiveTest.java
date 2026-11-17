package dev.agentworkbench.runtime;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class TerminalOutputArchiveTest {
    @TempDir Path data;
    private final ObjectMapper json = new ObjectMapper();

    @Test void splitUnicodeAndAnsiBytesSurviveIndependentReopen() throws Exception {
        String terminal = UUID.randomUUID().toString(), epoch = UUID.randomUUID().toString();
        TerminalOutputArchive archive = new TerminalOutputArchive(json, data);
        var writer = archive.create(terminal, epoch, obj("workerId", "original-worker", "pid", 42), 1024);
        byte[] original = "\u001b[31m中文\u001b[0m\r\n尾部".getBytes(StandardCharsets.UTF_8);
        var first = writer.append(Arrays.copyOfRange(original, 0, 7));
        var second = writer.append(Arrays.copyOfRange(original, 7, original.length));
        writer.finish(true);
        TerminalOutputArchive reopened = new TerminalOutputArchive(json, data);
        assertArrayEquals(Arrays.copyOfRange(original, 0, 7), reopened.read(terminal, epoch, first));
        assertArrayEquals(Arrays.copyOfRange(original, 7, original.length), reopened.read(terminal, epoch, second));
        Map<String, Object> facts = reopened.inspect(terminal, epoch);
        assertTrue(bool(facts, "complete", false));
        assertEquals(original.length, ((Number) facts.get("contiguousBytes")).longValue());
        assertEquals(2, objects(facts.get("descriptors")).size());
        assertThrows(IllegalStateException.class, () -> reopened.create(terminal, epoch, obj(), 1024));
    }

    @Test void corruptedBytesAndRemovedDescriptorsDoNotBecomeHealthyHistory() throws Exception {
        String terminal = UUID.randomUUID().toString(), epoch = UUID.randomUUID().toString();
        TerminalOutputArchive archive = new TerminalOutputArchive(json, data);
        var writer = archive.create(terminal, epoch, obj(), 1024);
        Map<String, Object> first = writer.append("original".getBytes(StandardCharsets.UTF_8));
        Map<String, Object> second = writer.append("tail".getBytes(StandardCharsets.UTF_8)); writer.finish(true);
        Path folder = data.resolve("terminal-output").resolve(terminal);
        Path changed;
        try (var files = Files.list(folder)) { changed = files.filter(file -> file.getFileName().toString().startsWith("00000000000000000000-") && file.toString().endsWith(".bin")).findFirst().orElseThrow(); }
        Files.write(changed, "modified".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalStateException.class, () -> archive.read(terminal, epoch, first));
        Map<String, Object> corrupted = archive.inspect(terminal, epoch);
        assertFalse(bool(corrupted, "complete", true)); assertFalse(objects(corrupted.get("gaps")).isEmpty());
        try (var files = Files.list(folder)) {
            Files.delete(files.filter(file -> file.getFileName().toString().startsWith("00000000000000000008-") && file.toString().endsWith(".json")).findFirst().orElseThrow());
        }
        assertThrows(IllegalStateException.class, () -> archive.read(terminal, epoch, second));
        Map<String, Object> missing = archive.inspect(terminal, epoch);
        assertFalse(bool(missing, "complete", true)); assertEquals(1, strings(missing.get("unreferencedFiles")).size());
        assertTrue(Files.exists(changed));
    }

    @Test void failedQuotaFencesWriterAndRetainsIncompleteOriginal() {
        String terminal = UUID.randomUUID().toString(), epoch = UUID.randomUUID().toString();
        TerminalOutputArchive archive = new TerminalOutputArchive(json, data);
        var writer = archive.create(terminal, epoch, obj(), 3);
        Map<String, Object> original = writer.append(new byte[] {1, 2});
        assertThrows(IllegalStateException.class, () -> writer.append(new byte[] {3, 4}));
        assertThrows(IllegalStateException.class, () -> writer.append(new byte[] {3}));
        assertFalse(bool(writer.finish(true), "complete", true));
        assertArrayEquals(new byte[] {1, 2}, archive.read(terminal, epoch, original));
        Map<String, Object> facts = archive.inspect(terminal, epoch);
        assertFalse(bool(facts, "complete", true)); assertEquals(1, strings(facts.get("unreferencedFiles")).size());
    }

    @Test void byteCursorRetainsLongOffsetsAndDetectsOriginalGap() throws Exception {
        String terminal = UUID.randomUUID().toString(), epoch = UUID.randomUUID().toString();
        TerminalOutputArchive archive = new TerminalOutputArchive(json, data);
        archive.create(terminal, epoch, obj(), 1024);
        byte[] bytes = {7, 8}; long offset = 4_294_967_296L;
        String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        Map<String, Object> descriptor = obj("protocolVersion", 1, "terminalId", terminal, "epoch", epoch,
            "offset", offset, "length", 2, "next", offset + 2, "sha256", hash);
        String file = String.format(java.util.Locale.ROOT, "%020d-%06d-%s", offset, 2, hash);
        Path folder = data.resolve("terminal-output").resolve(terminal);
        Files.write(folder.resolve(file + ".bin"), bytes); Files.write(folder.resolve(file + ".json"), json.writeValueAsBytes(descriptor));
        assertArrayEquals(bytes, archive.read(terminal, epoch, descriptor));
        Map<String, Object> facts = archive.inspect(terminal, epoch);
        assertFalse(bool(facts, "complete", true)); assertFalse(objects(facts.get("gaps")).isEmpty());
        assertEquals(offset + 2, ((Number) objects(facts.get("descriptors")).getFirst().get("next")).longValue());
    }

    @Test void foreignEpochAndPathInputsCannotReadSavedBytes() {
        String terminal = UUID.randomUUID().toString(), epoch = UUID.randomUUID().toString();
        TerminalOutputArchive archive = new TerminalOutputArchive(json, data);
        var writer = archive.create(terminal, epoch, obj(), 1024); Map<String, Object> original = writer.append(new byte[] {1});
        assertThrows(IllegalStateException.class, () -> archive.read(terminal, UUID.randomUUID().toString(), original));
        assertThrows(IllegalArgumentException.class, () -> archive.read("../other", epoch, original));
        assertThrows(IllegalStateException.class, () -> archive.read(terminal, epoch, obj("file", "../outside")));
        assertArrayEquals(new byte[] {1}, archive.read(terminal, epoch, original));
    }
}
