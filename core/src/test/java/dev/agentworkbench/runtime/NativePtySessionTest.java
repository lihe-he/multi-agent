package dev.agentworkbench.runtime;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import dev.agentworkbench.CheckpointFailureEvidence;
import dev.agentworkbench.ManagedProcessLedger;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.CleanupMode;
import org.junit.jupiter.api.io.TempDir;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * 实际系统 PTY 验证尺寸、Unicode、完整尾部和原出生停止，范围仅为原生适配器。
 */
@ExtendWith(CheckpointFailureEvidence.class)
class NativePtySessionTest {
    @TempDir(cleanup = CleanupMode.NEVER) static Path directory;
    private static Path data;
    private static final ObjectMapper mapper = new ObjectMapper();
    private static final boolean WINDOWS = System.getProperty("os.name", "").startsWith("Windows");

    @BeforeAll static void initialize() throws Exception { data = Files.createDirectory(directory.resolve("native-shared-data")).toRealPath(); }

    /**
     * 原生库属于整个测试 JVM，退出前复制全部原字节，保留拥有目录供独立核对。
     */
    @AfterAll static void preserveOriginalNativeJvmFixture() throws Exception {
        Path source = directory.toRealPath(), target = Path.of("target").toAbsolutePath().normalize();
        Path evidence = target.resolve("native-pty-evidence-" + id());
        if (!evidence.startsWith(target) || evidence.equals(target)) throw new IllegalStateException("Native evidence path is outside the test target");
        Files.createDirectory(evidence); Path copied = Files.createDirectory(evidence.resolve("original-fixture"));
        List<Map<String, Object>> files = new java.util.ArrayList<>();
        Map<String, Object> receipt = obj("recordedAt", now(), "originalFixture", source.toString(), "scope", "COMPLETE_ORIGINAL_NATIVE_TEST_JVM_FIXTURE_BEFORE_JVM_EXIT",
            "ownerPid", ProcessHandle.current().pid(), "ownerStartedAt", ProcessHandle.current().info().startInstant().orElseThrow().toString(), "files", files, "completeCopy", false);
        try {
            Files.walkFileTree(source, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult preVisitDirectory(Path directory, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                    Files.createDirectories(copied.resolve(source.relativize(directory))); return java.nio.file.FileVisitResult.CONTINUE;
                }
                @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attributes) throws java.io.IOException {
                    if (!attributes.isRegularFile() || attributes.isSymbolicLink()) throw new java.io.IOException("Unexpected native fixture file type");
                    byte[] bytes = Files.readAllBytes(file); Path relative = source.relativize(file), destination = copied.resolve(relative);
                    Files.copy(file, destination, java.nio.file.LinkOption.NOFOLLOW_LINKS);
                    if (!java.util.Arrays.equals(bytes, Files.readAllBytes(destination)) || !java.util.Arrays.equals(bytes, Files.readAllBytes(file))) throw new java.io.IOException("Original native fixture changed during copy");
                    try { files.add(obj("file", relative.toString(), "bytes", bytes.length, "sha256", java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes)))); }
                    catch (java.security.NoSuchAlgorithmException exception) { throw new java.io.IOException(exception); }
                    return java.nio.file.FileVisitResult.CONTINUE;
                }
            });
            receipt.put("completeCopy", true);
        } catch (Exception exception) { receipt.put("copyGap", exception.toString()); throw exception; }
        finally { Files.write(evidence.resolve("fixture-preservation.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt)); }
    }

    private final class Fixture implements AutoCloseable {
        final Path work = Files.createTempDirectory(directory.toRealPath(), "中文 native work ").toRealPath();
        final String terminal = id(), epoch = id(), marker = id().replace("-", "");
        final List<Map<String, Object>> observations = new CopyOnWriteArrayList<>(), references = new CopyOnWriteArrayList<>();
        final TerminalOutputArchive archive = new TerminalOutputArchive(mapper, data);
        final TerminalOutputArchive.Writer writer = archive.create(terminal, epoch, obj("scope", "NATIVE_ADAPTER_TEST_ONLY", "workingDirectory", work.toString()), TerminalOutputArchive.MAX_OUTPUT_BYTES);
        final NativePtySession session = NativePtySession.open(data, work, WINDOWS ? "SYSTEM_CMD" : "SYSTEM_SH", 80, 24, observations::add, writer, references::add);
        final java.util.concurrent.ExecutorService owner = Executors.newSingleThreadExecutor();
        final java.util.concurrent.Future<Map<String, Object>> result;
        final java.util.concurrent.CountDownLatch firstPublicationEntered = new java.util.concurrent.CountDownLatch(1);
        final java.util.concurrent.CountDownLatch executionAdmission = new java.util.concurrent.CountDownLatch(1);

        Fixture(boolean failPublication) throws Exception {
            this(failPublication, 0);
        }

        Fixture(boolean failPublication, int firstPublicationDelayMillis) throws Exception {
            this(failPublication, firstPublicationDelayMillis, false);
        }

        Fixture(boolean failPublication, int firstPublicationDelayMillis, boolean delayOriginalTail) throws Exception {
            this(failPublication, firstPublicationDelayMillis, delayOriginalTail ? "_1" : "");
        }

        Fixture(boolean failPublication, int firstPublicationDelayMillis, String delayedTailLine) throws Exception {
            this(failPublication, firstPublicationDelayMillis, delayedTailLine, false);
        }

        Fixture(boolean failPublication, int firstPublicationDelayMillis, String delayedTailLine, boolean deferExecution) throws Exception {
            Files.write(work.resolve("original-start.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(session.started()));
            if (!deferExecution) executionAdmission.countDown();
            result = owner.submit(() -> {
                assertTrue(executionAdmission.await(15, TimeUnit.SECONDS), "original native reader admission did not arrive");
                var firstPublication = new java.util.concurrent.atomic.AtomicBoolean();
                var observed = new ByteArrayOutputStream();
                Map<String, Object> outcome = session.execute(() -> false, writer, descriptor -> {
                    if (!delayedTailLine.isEmpty()) observed.writeBytes(archive.read(terminal, epoch, descriptor));
                    String text = observed.toString(StandardCharsets.UTF_8).replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
                        .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "");
                    boolean selected = delayedTailLine.isEmpty() || text.contains("TAIL_" + marker + delayedTailLine);
                    if (selected && firstPublication.compareAndSet(false, true)) {
                        firstPublicationEntered.countDown();
                        if (firstPublicationDelayMillis > 0) {
                            long started = System.nanoTime();
                            try {
                                TimeUnit.MILLISECONDS.sleep(firstPublicationDelayMillis);
                                Files.write(work.resolve("original-publication-delay.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                                    obj("requestedMillis", firstPublicationDelayMillis, "elapsedNanos", System.nanoTime() - started, "descriptor", descriptor, "recordedAt", now())));
                            } catch (Exception exception) { throw new IllegalStateException("original delayed publication fixture failed", exception); }
                        }
                    }
                    references.add(new java.util.LinkedHashMap<>(descriptor));
                    if (failPublication) throw new IllegalStateException("controlled loss after original raw segment was saved");
                });
                Files.write(work.resolve("original-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(outcome)); return outcome;
            });
        }

        void input(String text) throws Exception { session.input(text.getBytes(StandardCharsets.UTF_8)); }
        /**
         * 控制台夹具沿用已核验的普通命令入口，原生输入仍逐字节发送。
         */
        void powershell(String script) throws Exception {
            assertFalse(script.contains("\""), "fixed fixture script must fit its original command quoting");
            input("powershell.exe -NoLogo -NoProfile -NonInteractive -Command \"" + script + "\"\r");
        }
        Map<String, Object> result() throws Exception { return result.get(20, TimeUnit.SECONDS); }
        byte[] outputBytes() throws Exception {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (Map<String, Object> descriptor : references) bytes.write(archive.read(terminal, epoch, descriptor));
            return bytes.toByteArray();
        }
        String plainText() throws Exception {
            return new String(outputBytes(), StandardCharsets.UTF_8).replaceAll("\\u001B\\[[0-?]*[ -/]*[@-~]", "")
                .replaceAll("\\u001B\\][^\\u0007]*(?:\\u0007|\\u001B\\\\)", "");
        }
        /**
         * 按终端 LF 分行，仅忽略行尾回车；完整编号及全部内容仍须逐行匹配，原字节保持。
         */
        boolean containsTailLine(String text, int index) {
            String expected = "TAIL_" + marker + "_" + index;
            return java.util.Arrays.stream(text.split("\n", -1)).anyMatch(line -> line.replaceFirst("\\r+$", "").equals(expected));
        }
        @Override public void close() throws Exception {
            try {
                Map<String, Object> original = session.started();
                List<Map<String, Object>> children = ProcessHandle.of(((Number) original.get("pid")).longValue()).stream().flatMap(ProcessHandle::descendants).map(child -> {
                    ProcessHandle.Info info = child.info();
                    return obj("pid", child.pid(), "processStartedAt", info.startInstant().map(Object::toString).orElse(""), "alive", child.isAlive(),
                        "command", info.command().orElse(""), "arguments", info.arguments().map(List::of).orElse(List.of()), "cpuNanos", info.totalCpuDuration().map(java.time.Duration::toNanos).orElse(0L));
                }).toList();
                Files.write(work.resolve("original-before-fixture-stop.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    obj("recordedAt", now(), "original", original, "resultReturnedBeforeStop", result.isDone(), "observedChildren", observations, "currentDescendants", children,
                        "publishedSegments", references.size(), "publishedBytes", outputBytes().length)));
            } finally {
                session.requestStop(); executionAdmission.countDown();
                try { result.get(20, TimeUnit.SECONDS); }
                finally { owner.shutdownNow(); }
            }
        }
    }

    @Test
    void actualSystemShellKeepsDimensionsUnicodeAndAllOriginalTailLines() throws Exception {
        try (var fixture = new Fixture(false)) {
            if (WINDOWS) {
                fixture.powershell("[IO.File]::WriteAllText('initial-command-entered.txt','entered');$facts=@{columns=[Console]::WindowWidth;rows=[Console]::WindowHeight;inputRedirected=[Console]::IsInputRedirected;outputRedirected=[Console]::IsOutputRedirected;cwd=(Get-Location).ProviderPath};"
                    + "[IO.File]::WriteAllText('initial-facts-ready.txt','ready');$json=$facts|ConvertTo-Json -Compress;[IO.File]::WriteAllText('initial.json',$json,[Text.UTF8Encoding]::new($false))");
                await(() -> Files.isRegularFile(fixture.work.resolve("initial.json")), 15);
                Map<String, Object> initial = mapper.readValue(Files.readAllBytes(fixture.work.resolve("initial.json")), new TypeReference<>() { });
                assertEquals(false, initial.get("inputRedirected")); assertEquals(false, initial.get("outputRedirected"));
                assertEquals(80, num(initial, "columns", 0)); assertEquals(24, num(initial, "rows", 0)); assertEquals(fixture.work, Path.of(str(initial, "cwd")).toRealPath());
            } else {
                fixture.input("stty size > initial-size.txt; if test -t 0 && test -t 1; then printf 'TTY_TRUE\\n' > initial-tty.txt; fi\n");
                await(() -> Files.isRegularFile(fixture.work.resolve("initial-tty.txt")), 15);
                assertEquals("24 80", Files.readString(fixture.work.resolve("initial-size.txt")).strip()); assertEquals("TTY_TRUE\n", Files.readString(fixture.work.resolve("initial-tty.txt")));
            }
            Map<String, Object> size = fixture.session.resize(101, 37); assertEquals(101, num(size, "columns", 0)); assertEquals(37, num(size, "rows", 0));
            if (WINDOWS) {
                fixture.powershell("[IO.File]::WriteAllText('resized.txt',(([Console]::WindowHeight).ToString()+' '+[Console]::WindowWidth),[Text.UTF8Encoding]::new($false));"
                    + "[Console]::WriteLine('UNICODE_" + fixture.marker + ":'+([char]0x4E2D)+([char]0x6587));for($i=1;$i -le 200;$i++){[Console]::WriteLine('TAIL_" + fixture.marker + "_'+$i)}");
            } else fixture.input("stty size > resized.txt; printf 'UNICODE_" + fixture.marker + ":中文\\n'; i=1; while test \"$i\" -le 200; do printf 'TAIL_" + fixture.marker + "_%s\\n' \"$i\"; i=$((i+1)); done\n");
            await(() -> {
                try { return fixture.containsTailLine(fixture.plainText(), 200); }
                catch (Exception exception) { throw new IllegalStateException(exception); }
            }, 15);
            assertEquals("37 101", Files.readString(fixture.work.resolve("resized.txt")).strip());
            fixture.input(WINDOWS ? "exit 7\r" : "exit 7\n"); Map<String, Object> outcome = fixture.result();
            assertEquals("EXITED", outcome.get("status")); assertEquals(7, num(outcome, "exitCode", -1));
            assertEquals(true, outcome.get("outputReaderCompleted")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            Map<String, Object> inspection = fixture.archive.inspect(fixture.terminal, fixture.epoch); assertEquals(true, inspection.get("complete"));
            byte[] raw = fixture.outputBytes(); assertEquals(((Number) inspection.get("contiguousBytes")).longValue(), raw.length);
            String text = fixture.plainText(); assertTrue(text.contains("UNICODE_" + fixture.marker + ":中文"));
            for (int index = 1; index <= 200; index++) assertTrue(fixture.containsTailLine(text, index), "original tail missing: " + index);
            Map<String, Object> process = fixture.session.started(); assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) process.get("pid")).longValue(), str(process, "processStartedAt")));
            assertThrows(java.io.IOException.class, () -> fixture.session.input("must not reach an exited shell".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void delayedFirstPublicationStillDrainsEveryOriginalTailBeforeImmediateExit() throws Exception {
        try (var fixture = new Fixture(false, 500)) {
            assertTrue(fixture.firstPublicationEntered.await(10, TimeUnit.SECONDS));
            if (WINDOWS) fixture.input("for /L %i in (1,1,200) do @echo TAIL_" + fixture.marker + "_%i\rexit 7\r");
            else fixture.input("i=1; while test \"$i\" -le 200; do printf 'TAIL_" + fixture.marker + "_%s\\n' \"$i\"; i=$((i+1)); done; exit 7\n");
            Map<String, Object> outcome = fixture.result();
            assertEquals("EXITED", outcome.get("status")); assertEquals(7, num(outcome, "exitCode", -1));
            assertEquals(true, fixture.archive.inspect(fixture.terminal, fixture.epoch).get("complete"));
            String text = fixture.plainText();
            for (int index = 1; index <= 200; index++)
                assertTrue(fixture.containsTailLine(text, index), "original delayed tail missing: " + index);
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("outputReaderCompleted")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
        }
    }

    @Test
    void delayedOriginalTailPublicationStillDrainsBeforeImmediateExit() throws Exception {
        try (var fixture = new Fixture(false, 500, true)) {
            if (WINDOWS) fixture.input("for /L %i in (1,1,200) do @echo TAIL_" + fixture.marker + "_%i\rexit 7\r");
            else fixture.input("i=1; while test \"$i\" -le 200; do printf 'TAIL_" + fixture.marker + "_%s\\n' \"$i\"; i=$((i+1)); done; exit 7\n");
            assertTrue(fixture.firstPublicationEntered.await(10, TimeUnit.SECONDS));
            Map<String, Object> outcome = fixture.result();
            assertEquals("EXITED", outcome.get("status")); assertEquals(7, num(outcome, "exitCode", -1));
            assertEquals(true, fixture.archive.inspect(fixture.terminal, fixture.epoch).get("complete"));
            String text = fixture.plainText();
            for (int index = 1; index <= 200; index++)
                assertTrue(fixture.containsTailLine(text, index), "original delayed tail missing: " + index);
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("outputReaderCompleted")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
        }
    }

    @Test
    void delayedFinalTailPublicationStillConfirmsImmediateExitAndCompleteOutput() throws Exception {
        try (var fixture = new Fixture(false, 500, "_200")) {
            if (WINDOWS) fixture.input("for /L %i in (1,1,200) do @echo TAIL_" + fixture.marker + "_%i\rexit 7\r");
            else fixture.input("i=1; while test \"$i\" -le 200; do printf 'TAIL_" + fixture.marker + "_%s\\n' \"$i\"; i=$((i+1)); done; exit 7\n");
            assertTrue(fixture.firstPublicationEntered.await(10, TimeUnit.SECONDS));
            Map<String, Object> outcome = fixture.result();
            assertEquals("EXITED", outcome.get("status")); assertEquals(7, num(outcome, "exitCode", -1));
            assertEquals(true, fixture.archive.inspect(fixture.terminal, fixture.epoch).get("complete"));
            String text = fixture.plainText();
            for (int index = 1; index <= 200; index++)
                assertTrue(fixture.containsTailLine(text, index), "original delayed final tail missing: " + index);
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("outputReaderCompleted")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
        }
    }

    @Test
    void publicationFailureKeepsOriginalRawBytesAndStopsWithoutACompleteTranscriptClaim() throws Exception {
        try (var fixture = new Fixture(true)) {
            Map<String, Object> outcome = fixture.result(); assertEquals("UNKNOWN", outcome.get("status"));
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted"));
            assertFalse(fixture.references.isEmpty()); byte[] original = fixture.archive.read(fixture.terminal, fixture.epoch, fixture.references.getFirst()); assertTrue(original.length > 0);
            assertFalse(bool(fixture.archive.inspect(fixture.terminal, fixture.epoch), "complete", true));
            assertThrows(java.io.IOException.class, () -> fixture.session.input("unsent after publication loss".getBytes(StandardCharsets.UTF_8)));
        }
    }

    @Test
    void stoppingARealForegroundChildWaitsForItsOriginalBirthAndTheOutputReader() throws Exception {
        try (var fixture = new Fixture(false)) {
            if (WINDOWS) fixture.powershell("[IO.File]::WriteAllText('child-pid.txt',([Diagnostics.Process]::GetCurrentProcess().Id).ToString());Start-Sleep -Seconds 30");
            else fixture.input("sh -c 'echo $$ > child-pid.txt; sleep 30'\n");
            await(() -> Files.isRegularFile(fixture.work.resolve("child-pid.txt")), 15); long child = Long.parseLong(Files.readString(fixture.work.resolve("child-pid.txt")).strip());
            String birth = ProcessHandle.of(child).orElseThrow().info().startInstant().orElseThrow().toString();
            await(() -> fixture.observations.stream().anyMatch(item -> ((Number) item.get("pid")).longValue() == child && birth.equals(str(item, "processStartedAt"))), 5);
            fixture.session.requestStop(); Map<String, Object> outcome = fixture.result(); assertEquals("CANCELLED", outcome.get("status"));
            assertTrue(ManagedProcessLedger.observedProcessStopped(child, birth)); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("outputReaderCompleted")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
            assertTrue(objects(outcome.get("observedChildren")).stream().anyMatch(item -> ((Number) item.get("pid")).longValue() == child && birth.equals(str(item, "processStartedAt"))));
        }
    }

    /**
     * 原 shell 离开工作目录后替换该目录，未发送输入仍被原目录编号门禁拦截。
     */
    @Test
    void replacingTheOriginalWorkspaceFencesInputAndResizeAndKeepsUnknown() throws Exception {
        try (var fixture = new Fixture(false)) {
            Path parent = fixture.work.getParent().toRealPath(), proof = parent.resolve("cwd-proof-" + fixture.marker + ".txt");
            if (WINDOWS) {
                fixture.input("cd /d \"" + parent + "\"\r");
                fixture.powershell("[IO.File]::WriteAllText('cwd-command-entered.txt','entered');$cwd=(Get-Location).ProviderPath;[IO.File]::WriteAllText('" + proof.getFileName() + "',$cwd,[Text.UTF8Encoding]::new($false))");
            } else fixture.input("cd '" + parent.toString().replace("'", "'\\''") + "'; pwd > '" + proof.getFileName() + "'\n");
            await(() -> Files.isRegularFile(proof), 10);
            assertEquals(parent.toString(), Files.readString(proof).strip());
            Path displaced = fixture.work.resolveSibling("displaced-native-work-" + fixture.marker).toAbsolutePath().normalize();
            assertTrue(fixture.work.startsWith(directory.toRealPath())); assertTrue(displaced.startsWith(directory.toRealPath()));
            Files.move(fixture.work, displaced); Files.createDirectory(fixture.work);
            String displacedKey = WorkspaceFileIdentity.directoryKey(displaced), replacementKey = WorkspaceFileIdentity.directoryKey(fixture.work);
            assertEquals(str(fixture.session.started(), "workingDirectoryFileKey"), displacedKey); assertNotEquals(displacedKey, replacementKey);
            Files.write(parent.resolve("original-directory-replacement-" + fixture.marker + ".json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                obj("originalPath", fixture.work.toString(), "originalFileKey", fixture.session.started().get("workingDirectoryFileKey"),
                    "displacedPath", displaced.toString(), "displacedFileKey", displacedKey, "replacementFileKey", replacementKey,
                    "shellCwdProof", proof.toString(), "shellCwd", Files.readString(proof).strip(), "recordedAt", now())));
            String unsent = WINDOWS ? "echo NEVER_SENT_" + fixture.marker + ">unsent.txt\r" : "printf 'NEVER_SENT_" + fixture.marker + "' > unsent.txt\n";
            assertThrows(java.io.IOException.class, () -> fixture.session.input(unsent.getBytes(StandardCharsets.UTF_8)));
            assertThrows(java.io.IOException.class, () -> fixture.session.resize(99, 31));
            Map<String, Object> outcome = fixture.result(); assertEquals("UNKNOWN", outcome.get("status"));
            assertEquals(false, outcome.get("workingDirectoryIdentityVerified"));
            assertFalse(map(outcome, "workingDirectoryIdentityFailure").isEmpty());
            assertNotEquals(str(fixture.session.started(), "workingDirectoryFileKey"), WorkspaceFileIdentity.directoryKey(fixture.work));
            assertFalse(Files.exists(fixture.work.resolve("unsent.txt"))); assertFalse(Files.exists(displaced.resolve("unsent.txt")));
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("nativeEofObserved")); assertEquals(true, outcome.get("nativeCleanupThreadCompleted"));
            assertEquals(true, outcome.get("ownedStreamsClosed"));
        }
    }

    /**
     * 隐去原输出归属使真实落盘失败，仍排空原管道并保留未提交前缀与原失败。
     */
    @Test
    void originalOutputStorageFailureDrainsToNativeEofAndRetainsABoundedUncommittedPrefix() throws Exception {
        try (var fixture = new Fixture(false, 0, "", true)) {
            Path originalOwner = data.resolve("terminal-output").resolve(fixture.terminal).resolve("owner.json");
            byte[] ownerBytes = Files.readAllBytes(originalOwner);
            Path withheldOwner = fixture.work.resolve("original-output-owner-withheld.json");
            assertTrue(originalOwner.startsWith(data)); assertTrue(withheldOwner.startsWith(directory.toRealPath()));
            Files.move(originalOwner, withheldOwner);
            if (WINDOWS) fixture.powershell("[IO.File]::WriteAllText('storage-child-pid.txt',([Diagnostics.Process]::GetCurrentProcess().Id).ToString());"
                + "[Console]::Write(('UNCOMMITTED_" + fixture.marker + "_')*100000);Start-Sleep -Seconds 30");
            else fixture.input("sh -c 'echo $$ > storage-child-pid.txt; yes UNCOMMITTED_" + fixture.marker + "'\n");
            await(() -> Files.isRegularFile(fixture.work.resolve("storage-child-pid.txt")), 12);
            long child = Long.parseLong(Files.readString(fixture.work.resolve("storage-child-pid.txt")).strip());
            String childBirth = ProcessHandle.of(child).orElseThrow().info().startInstant().orElseThrow().toString();
            fixture.executionAdmission.countDown();
            Map<String, Object> outcome = fixture.result(); assertEquals("UNKNOWN", outcome.get("status"));
            assertEquals("IllegalStateException", outcome.get("outputStorageFailureType"));
            assertEquals("IllegalStateException", outcome.get("outputEndReceiptFailureType"));
            assertEquals("", outcome.get("readerFailureType")); assertEquals(true, outcome.get("nativeEofObserved"));
            assertEquals(true, outcome.get("originalProcessStopped")); assertEquals(true, outcome.get("observedChildrenStopped"));
            assertEquals(true, outcome.get("nativeCleanupThreadCompleted")); assertEquals(true, outcome.get("ownedStreamsClosed"));
            assertTrue(ManagedProcessLedger.observedProcessStopped(child, childBirth));
            assertEquals(0L, ((Number) outcome.get("savedOutputBytes")).longValue()); assertTrue(fixture.references.isEmpty());
            Map<String, Object> gap = map(outcome, "uncommittedOutput");
            byte[] prefix = java.util.Base64.getDecoder().decode(str(gap, "retainedPrefixBase64"));
            long observed = ((Number) gap.get("bytes")).longValue();
            assertTrue(observed > 0); assertTrue(prefix.length > 0 && prefix.length <= 65_536);
            assertEquals(observed, ((Number) outcome.get("observedOutputBytes")).longValue());
            assertEquals(prefix.length, ((Number) gap.get("retainedPrefixBytes")).longValue());
            assertEquals(java.util.HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(prefix)), gap.get("retainedPrefixSha256"));
            assertEquals(observed > prefix.length, gap.get("truncated"));
            assertEquals(false, map(outcome, "outputEnd").get("complete")); assertEquals(true, map(outcome, "outputEnd").get("endReceiptMissing"));
            assertThrows(java.io.IOException.class, () -> fixture.session.input("unsent after raw storage failure".getBytes(StandardCharsets.UTF_8)));
            assertArrayEquals(ownerBytes, Files.readAllBytes(withheldOwner));
            Files.move(withheldOwner, originalOwner);
            assertArrayEquals(ownerBytes, Files.readAllBytes(originalOwner));
            Map<String, Object> inspection = fixture.archive.inspect(fixture.terminal, fixture.epoch);
            assertEquals(false, inspection.get("complete")); assertTrue(objects(inspection.get("descriptors")).isEmpty());
        }
    }

    /**
     * 已创建原进程后丢失身份发布，先排空原输出并等待原生清理，再返回未知回执。
     */
    @Test
    void postLaunchIdentityPublicationFailureSettlesOriginalResourcesBeforeReturningItsReceipt() throws Exception {
        Path work = Files.createTempDirectory(directory.toRealPath(), "native-startup-failure-").toRealPath();
        String terminal = id(), epoch = id();
        TerminalOutputArchive archive = new TerminalOutputArchive(mapper, data);
        TerminalOutputArchive.Writer output = archive.create(terminal, epoch, obj("scope", "ORIGINAL_POST_LAUNCH_IDENTITY_FAILURE", "workingDirectory", work.toString()), TerminalOutputArchive.MAX_OUTPUT_BYTES);
        List<Map<String, Object>> observed = new CopyOnWriteArrayList<>(), published = new CopyOnWriteArrayList<>();
        NativePtySession.StartupFailure failure = assertThrows(NativePtySession.StartupFailure.class,
            () -> NativePtySession.open(data, work, WINDOWS ? "SYSTEM_CMD" : "SYSTEM_SH", 80, 24, facts -> {
                observed.add(new java.util.LinkedHashMap<>(facts)); throw new IllegalStateException("controlled original native identity publication loss");
            }, output, published::add));
        Map<String, Object> receipt = failure.receipt();
        Files.write(work.resolve("original-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
        Files.write(work.resolve("original-start.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(map(receipt, "nativeProcess")));
        assertEquals("UNKNOWN", receipt.get("status")); assertEquals(false, receipt.get("startupAcknowledged"));
        assertEquals(true, receipt.get("nativeObjectReturned")); assertEquals(true, receipt.get("startupCleanupBeforeReturn"));
        assertEquals("controlled original native identity publication loss", map(receipt, "startupFailure").get("message"));
        assertEquals(true, receipt.get("originalProcessStopped")); assertEquals(true, receipt.get("observedChildrenStopped"));
        assertEquals(true, receipt.get("nativeEofObserved")); assertEquals(true, receipt.get("outputReaderCompleted"));
        assertEquals(true, receipt.get("outputPublisherCompleted")); assertEquals(true, receipt.get("nativeCleanupThreadCompleted"));
        assertEquals(true, receipt.get("ownedStreamsClosed")); assertEquals("", receipt.get("readerFailureType"));
        Map<String, Object> identity = observed.getFirst();
        assertEquals(map(receipt, "nativeProcess").get("pid"), identity.get("pid"));
        assertEquals(map(receipt, "nativeProcess").get("processStartedAt"), identity.get("processStartedAt"));
        assertTrue(ManagedProcessLedger.observedProcessStopped(((Number) identity.get("pid")).longValue(), str(identity, "processStartedAt")));
        Map<String, Object> inspection = archive.inspect(terminal, epoch);
        assertEquals(mapper.readTree(mapper.writeValueAsBytes(map(receipt, "outputEnd"))),
            mapper.readTree(mapper.writeValueAsBytes(map(inspection, "end"))));
        assertEquals(published.size(), objects(inspection.get("descriptors")).size());
    }

    /**
     * 构造器丢失返回对象时不推断进程已停；原测试拥有者独立停止实测身份，未知回执保留。
     */
    @Test
    void lostBuilderObjectReturnKeepsUnknownReceiptAndRequiresIndependentOriginalIdentityCleanup() throws Exception {
        Path work = Files.createTempDirectory(directory.toRealPath(), "native-builder-return-loss-").toRealPath();
        String terminal = id(), epoch = id(); TerminalOutputArchive archive = new TerminalOutputArchive(mapper, data);
        TerminalOutputArchive.Writer output = archive.create(terminal, epoch, obj("scope", "ORIGINAL_NATIVE_BUILDER_OBJECT_RETURN_LOSS", "workingDirectory", work.toString()), TerminalOutputArchive.MAX_OUTPUT_BYTES);
        var constructed = new java.util.concurrent.atomic.AtomicReference<com.pty4j.PtyProcess>();
        var attempts = new java.util.concurrent.atomic.AtomicInteger();
        List<Thread> nativeThreads = new CopyOnWriteArrayList<>();
        List<Map<String, Object>> observed = new CopyOnWriteArrayList<>(), published = new CopyOnWriteArrayList<>();
        Map<String, Object> identity = obj();
        try {
            NativePtySession.StartupFailure failure = assertThrows(NativePtySession.StartupFailure.class,
                () -> NativePtySession.open(data, work, WINDOWS ? "SYSTEM_CMD" : "SYSTEM_SH", 80, 24, observed::add, output, published::add, builder -> {
                    attempts.incrementAndGet(); var previous = Thread.getAllStackTraces().keySet();
                    constructed.set(builder.start());
                    Thread.getAllStackTraces().keySet().stream().filter(thread -> !previous.contains(thread)
                        && (thread.getName().startsWith("WinConPtyProcess WaitFor ") || thread.getName().startsWith("PtyProcess Reaper for "))).forEach(nativeThreads::add);
                    throw new java.io.IOException("controlled original native builder object return loss");
                }));
            Map<String, Object> receipt = failure.receipt();
            Files.write(work.resolve("original-result.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(receipt));
            assertEquals(1, attempts.get()); assertNotNull(constructed.get());
            ProcessHandle original = ProcessHandle.of(constructed.get().pid()).orElseThrow();
            identity = obj("pid", original.pid(), "processStartedAt", original.info().startInstant().orElseThrow().toString());
            Files.write(work.resolve("independent-original-builder-identity.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(identity));
            assertTrue(original.isAlive()); assertEquals("UNKNOWN", receipt.get("status")); assertEquals(false, receipt.get("nativeObjectReturned"));
            assertEquals(false, receipt.get("startupCleanupBeforeReturn")); assertEquals(false, receipt.get("originalProcessStopped"));
            assertEquals(false, receipt.get("nativeEofObserved")); assertEquals(false, receipt.get("outputReaderCompleted"));
            assertEquals(true, receipt.get("requiresOriginalWorkerAndNativeProcessReconciliation"));
            assertEquals(0L, ((Number) map(receipt, "nativeProcess").get("pid")).longValue());
            assertEquals("", map(receipt, "nativeProcess").get("processStartedAt")); assertTrue(observed.isEmpty()); assertTrue(published.isEmpty());
            assertEquals(false, map(receipt, "outputEnd").get("complete")); assertEquals(false, archive.inspect(terminal, epoch).get("complete"));
            assertThrows(IllegalStateException.class, () -> output.append("unsent after unreturned native object".getBytes(StandardCharsets.UTF_8)));
        } finally {
            com.pty4j.PtyProcess process = constructed.get();
            if (process != null) {
                process.destroyForcibly(); assertTrue(process.waitFor(20, TimeUnit.SECONDS), "independent original builder process stop");
                for (Thread thread : nativeThreads) { thread.join(TimeUnit.SECONDS.toMillis(20)); assertFalse(thread.isAlive(), "original native cleanup thread"); }
                process.getInputStream().close(); process.getOutputStream().close(); process.getErrorStream().close();
                assertTrue(ManagedProcessLedger.observedProcessStopped(process.pid(), str(identity, "processStartedAt")));
                Files.write(work.resolve("independent-original-builder-stop.json"), mapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(
                    obj("pid", process.pid(), "processStartedAt", identity.get("processStartedAt"), "originalStopped", true, "nativeThreadsStopped", nativeThreads.size(), "recordedAt", now())));
            }
        }
    }

    private static void await(BooleanSupplier condition, int seconds) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(seconds);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) TimeUnit.MILLISECONDS.sleep(20);
        assertTrue(condition.getAsBoolean(), "original native condition did not arrive within " + seconds + " seconds");
    }
}
