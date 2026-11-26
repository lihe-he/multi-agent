package dev.agentworkbench.runtime;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.net.UnixDomainSocketAddress;
import java.nio.channels.ServerSocketChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.FileAccess.READ_ONLY;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.FileAccess.WORKSPACE_WRITE;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.NetworkAccess.ALLOW;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.NetworkAccess.DENY;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.Status.ENFORCED;
import static dev.agentworkbench.runtime.NativeSandboxPolicy.Status.UNAVAILABLE;
import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

/**
 * 在真实 Linux 命名空间中核验文件、网络和环境边界，不以包装命令代替强制结果。
 */
@EnabledOnOs(OS.LINUX)
class LinuxBubblewrapSandboxPolicyTest {
    private static final String FORBIDDEN_ENVIRONMENT = "SANDBOX_HOST_PRIVATE_SENTINEL";
    private static final String FORBIDDEN_VALUE = "must-not-reach-the-sandbox";
    private static final Duration PROCESS_TIMEOUT = Duration.ofSeconds(20);
    private static final int OUTPUT_LIMIT = 65_536;
    private final ObjectMapper json = new ObjectMapper();
    @TempDir Path temporary;

    @Test
    void readOnlyWorkspacePreservesOriginalBytesAndRejectsNewFiles() throws Exception {
        Fixture fixture = fixture();
        Path original = fixture.workspace.resolve("input.txt"), created = fixture.workspace.resolve("created.txt");
        Files.writeString(original, "original 中文\n", StandardCharsets.UTF_8);
        var decision = available(fixture, READ_ONLY, DENY, """
            import json, pathlib
            original = pathlib.Path('input.txt')
            result = {'before': original.read_text(encoding='utf-8')}
            for name in ('input.txt', 'created.txt'):
                try:
                    pathlib.Path(name).write_text('unauthorized replacement', encoding='utf-8')
                    result[name] = False
                except OSError:
                    result[name] = True
            print(json.dumps(result))
            """);

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals("original 中文\n", result.path("before").asText());
        assertTrue(result.path("input.txt").asBoolean(), result.toString());
        assertTrue(result.path("created.txt").asBoolean(), result.toString());
        assertEquals("original 中文\n", Files.readString(original, StandardCharsets.UTF_8));
        assertFalse(Files.exists(created));
    }

    @Test
    void writableWorkspaceCannotReadOrOverwriteHostFilesThroughAnyPathForm() throws Exception {
        Fixture fixture = fixture();
        Files.createSymbolicLink(fixture.workspace.resolve("absolute-link"), fixture.hostSecret);
        Files.createSymbolicLink(fixture.workspace.resolve("relative-link"), Path.of("../host-private/secret.txt"));
        Path original = fixture.workspace.resolve("input.txt"), created = fixture.workspace.resolve("created.txt");
        Files.writeString(original, "before", StandardCharsets.UTF_8);
        var decision = available(fixture, WORKSPACE_WRITE, DENY, """
            import json, pathlib, sys
            pathlib.Path('input.txt').write_text('updated 中文', encoding='utf-8')
            pathlib.Path('created.txt').write_text('new artifact', encoding='utf-8')
            result = []
            for name in sys.argv[1:]:
                path = pathlib.Path(name)
                item = {'path': name}
                try:
                    path.read_bytes()
                    item['readDenied'] = False
                except OSError:
                    item['readDenied'] = True
                try:
                    path.write_bytes(b'escaped write')
                    item['writeDenied'] = False
                except OSError:
                    item['writeDenied'] = True
                result.append(item)
            print(json.dumps(result))
            """, fixture.hostSecret.toString(), "../host-private/secret.txt", "absolute-link", "relative-link", fixture.coreSecret.toString());

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals(5, result.size());
        for (JsonNode boundary : result) {
            assertTrue(boundary.path("readDenied").asBoolean(), boundary.toString());
            assertTrue(boundary.path("writeDenied").asBoolean(), boundary.toString());
        }
        assertEquals("updated 中文", Files.readString(original, StandardCharsets.UTF_8));
        assertEquals("new artifact", Files.readString(created, StandardCharsets.UTF_8));
        assertArrayEquals(fixture.secretBytes, Files.readAllBytes(fixture.hostSecret));
        assertEquals("private core state", Files.readString(fixture.coreSecret, StandardCharsets.UTF_8));
    }

    @Test
    void descendantProcessInheritsTheFilesystemBoundary() throws Exception {
        Fixture fixture = fixture();
        String child = """
            import json, pathlib, sys
            result = {}
            for action in ('read', 'write'):
                try:
                    path = pathlib.Path(sys.argv[1])
                    path.read_bytes() if action == 'read' else path.write_bytes(b'child escaped')
                    result[action] = False
                except OSError:
                    result[action] = True
            pathlib.Path('child-artifact.txt').write_text('child completed', encoding='utf-8')
            print(json.dumps(result))
            """;
        var decision = available(fixture, WORKSPACE_WRITE, DENY, """
            import json, subprocess, sys
            child = subprocess.run([sys.executable, '-c', sys.argv[1], sys.argv[2]],
                                   capture_output=True, text=True, timeout=5)
            print(json.dumps({'exitCode': child.returncode, 'child': json.loads(child.stdout)}))
            """, child, fixture.hostSecret.toString());

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals(0, result.path("exitCode").asInt(-1), result.toString());
        assertTrue(result.path("child").path("read").asBoolean(), result.toString());
        assertTrue(result.path("child").path("write").asBoolean(), result.toString());
        assertEquals("child completed", Files.readString(fixture.workspace.resolve("child-artifact.txt"), StandardCharsets.UTF_8));
        assertArrayEquals(fixture.secretBytes, Files.readAllBytes(fixture.hostSecret));
    }

    @ParameterizedTest
    @EnumSource(NativeSandboxPolicy.NetworkAccess.class)
    void networkPolicyControlsActualHostLoopbackConnections(NativeSandboxPolicy.NetworkAccess network) throws Exception {
        Fixture fixture = fixture();
        byte[] response = ("loopback-" + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        AtomicInteger requests = new AtomicInteger();
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 0);
        server.createContext("/sandbox", exchange -> {
            requests.incrementAndGet();
            try {
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var decision = available(fixture, READ_ONLY, network, """
                import http.client, json, sys
                connection = http.client.HTTPConnection('127.0.0.1', int(sys.argv[1]), timeout=2)
                try:
                    connection.request('GET', '/sandbox')
                    response = connection.getresponse()
                    result = {'connected': True, 'status': response.status, 'body': response.read().decode('utf-8')}
                except OSError as error:
                    result = {'connected': False, 'errno': error.errno}
                finally:
                    connection.close()
                print(json.dumps(result))
                """, Integer.toString(server.getAddress().getPort()));

            JsonNode result = launch(decision, fixture.workspace);
            assertEquals(network == ALLOW, result.path("connected").asBoolean(), result.toString());
            assertEquals(network == ALLOW ? 1 : 0, requests.get());
            if (network == ALLOW) {
                assertEquals(200, result.path("status").asInt());
                assertEquals(new String(response, StandardCharsets.UTF_8), result.path("body").asText());
            }
        } finally { server.stop(0); }
    }

    @ParameterizedTest
    @EnumSource(NativeSandboxPolicy.NetworkAccess.class)
    void socketFilterKeepsPrivateSocketpairsButRejectsForbiddenSocketFamilies(NativeSandboxPolicy.NetworkAccess network) throws Exception {
        Fixture fixture = fixture();
        var decision = available(fixture, READ_ONLY, network, """
            import ctypes, json, os, platform, socket
            first, second = socket.socketpair()
            try:
                first.sendall(b'private channel')
                private = second.recv(100).decode('ascii')
            finally:
                first.close()
                second.close()
            libc = ctypes.CDLL(None, use_errno=True)
            libc.syscall.restype = ctypes.c_long
            socket_call, pair_call = {'x86_64': (41, 53), 'aarch64': (198, 199)}[platform.machine()]
            families = [('inet', socket.AF_INET), ('inet6', socket.AF_INET6), ('unix', socket.AF_UNIX),
                        ('vsock', 40), ('tipc', 30), ('unknown', 255)]
            result = {'private': private, 'calls': []}
            for operation, number in (('socket', socket_call), ('socketpair', pair_call)):
                for name, family in families:
                    for high_word in (False, True):
                        descriptors = (ctypes.c_int * 2)(-1, -1)
                        domain = ctypes.c_ulong(family | ((1 << 32) if high_word else 0))
                        ctypes.set_errno(0)
                        returned = libc.syscall(ctypes.c_long(number), domain, ctypes.c_int(socket.SOCK_STREAM),
                                                ctypes.c_int(0), ctypes.byref(descriptors))
                        error = ctypes.get_errno() if returned < 0 else 0
                        if returned >= 0:
                            for descriptor in ([returned] if operation == 'socket' else descriptors):
                                os.close(descriptor)
                        result['calls'].append({'operation': operation, 'family': name, 'highWord': high_word,
                                                'returned': returned, 'errno': error})
            ctypes.set_errno(0)
            result['ioUringResult'] = libc.syscall(ctypes.c_long(425), ctypes.c_int(1), ctypes.c_void_p())
            result['ioUringErrno'] = ctypes.get_errno()
            print(json.dumps(result))
            """);

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals("private channel", result.path("private").asText());
        assertEquals(24, result.path("calls").size());
        for (JsonNode call : result.path("calls")) {
            String family = call.path("family").asText();
            boolean permitted = call.path("operation").asText().equals("socketpair") ? family.equals("unix")
                : network == ALLOW && List.of("inet", "inet6").contains(family);
            if (permitted) assertTrue(call.path("returned").asLong(-1) >= 0, call.toString());
            else {
                assertEquals(-1, call.path("returned").asLong(), call.toString());
                assertPermissionDenied(call.path("errno").asInt(), call.toString());
            }
        }
        assertEquals(-1, result.path("ioUringResult").asInt(), result.toString());
        assertPermissionDenied(result.path("ioUringErrno").asInt(), result.toString());
    }

    @ParameterizedTest
    @EnumSource(NativeSandboxPolicy.NetworkAccess.class)
    void hostAbstractUnixListenerCannotBeReachedEvenWhenIpNetworkingIsAllowed(NativeSandboxPolicy.NetworkAccess network) throws Exception {
        Fixture fixture = fixture();
        String address = "workbench-host-" + UUID.randomUUID();
        Process listener = new ProcessBuilder(python().toString(), "-u", "-c", """
            import json, select, socket, sys
            server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
            try:
                server.bind('\\0' + sys.argv[1])
                server.listen(1)
                print('ready', flush=True)
                readable, _, _ = select.select([server, sys.stdin], [], [], 45)
                connected = server in readable
                if connected:
                    client, _ = server.accept()
                    client.close()
                print(json.dumps({'connected': connected}), flush=True)
            finally:
                server.close()
            """, address).redirectErrorStream(true).start();
        try (var output = listener.inputReader(StandardCharsets.UTF_8)) {
            var ready = CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            });
            assertEquals("ready", ready.get(5, TimeUnit.SECONDS));
            var decision = available(fixture, READ_ONLY, network, """
                import json, socket, sys
                channel = None
                try:
                    channel = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
                    channel.settimeout(2)
                    channel.connect('\\0' + sys.argv[1])
                    result = {'connected': True, 'errno': 0}
                except OSError as error:
                    result = {'connected': False, 'errno': error.errno}
                finally:
                    if channel is not None:
                        channel.close()
                print(json.dumps(result))
                """, address);

            JsonNode result = launch(decision, fixture.workspace);
            assertFalse(result.path("connected").asBoolean(), result.toString());
            assertPermissionDenied(result.path("errno").asInt(), result.toString());
            listener.getOutputStream().write('\n');
            listener.getOutputStream().close();
            assertTrue(listener.waitFor(5, TimeUnit.SECONDS), "Host listener did not finish its bounded observation");
            assertEquals(0, listener.exitValue());
            JsonNode hostResult = json.readTree(output.readLine());
            assertFalse(hostResult.path("connected").asBoolean(), hostResult.toString());
        } finally {
            if (listener.isAlive()) {
                listener.destroyForcibly();
                listener.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @ParameterizedTest
    @EnumSource(NativeSandboxPolicy.NetworkAccess.class)
    void datagramPairsCannotReachHostAbstractListenersAndOnlyPrivateStreamsRemainAvailable(NativeSandboxPolicy.NetworkAccess network) throws Exception {
        Fixture fixture = fixture();
        String address = "workbench-datagram-" + UUID.randomUUID(), payload = "sandbox-message-" + UUID.randomUUID();
        Process listener = new ProcessBuilder(python().toString(), "-u", "-c", """
            import json, select, socket, sys, time
            server = socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM)
            try:
                server.bind('\\0' + sys.argv[1])
                server.settimeout(2)
                with socket.socket(socket.AF_UNIX, socket.SOCK_DGRAM) as control:
                    control.sendto(b'host positive control', server.getsockname())
                assert server.recv(100) == b'host positive control'
                print('ready', flush=True)
                messages = []
                deadline = time.monotonic() + 45
                while time.monotonic() < deadline:
                    readable, _, _ = select.select([server, sys.stdin], [], [], max(0, deadline - time.monotonic()))
                    if server in readable:
                        messages.append(server.recv(4096).decode('ascii'))
                    if sys.stdin in readable:
                        break
                print(json.dumps({'controlReceived': True, 'messages': messages}), flush=True)
            finally:
                server.close()
            """, address).redirectErrorStream(true).start();
        try (var output = listener.inputReader(StandardCharsets.UTF_8)) {
            var ready = CompletableFuture.supplyAsync(() -> {
                try { return output.readLine(); }
                catch (IOException exception) { throw new java.io.UncheckedIOException(exception); }
            });
            assertEquals("ready", ready.get(5, TimeUnit.SECONDS));
            var decision = available(fixture, READ_ONLY, network, """
                import ctypes, json, platform, socket, sys
                libc = ctypes.CDLL(None, use_errno=True)
                libc.syscall.restype = ctypes.c_long
                pair_call = {'x86_64': 53, 'aarch64': 199}[platform.machine()]
                types = [('stream', socket.SOCK_STREAM),
                         ('stream-cloexec', socket.SOCK_STREAM | socket.SOCK_CLOEXEC),
                         ('stream-nonblock', socket.SOCK_STREAM | socket.SOCK_NONBLOCK),
                         ('stream-both', socket.SOCK_STREAM | socket.SOCK_NONBLOCK | socket.SOCK_CLOEXEC),
                         ('datagram', socket.SOCK_DGRAM),
                         ('datagram-both', socket.SOCK_DGRAM | socket.SOCK_NONBLOCK | socket.SOCK_CLOEXEC),
                         ('seqpacket', socket.SOCK_SEQPACKET),
                         ('seqpacket-both', socket.SOCK_SEQPACKET | socket.SOCK_NONBLOCK | socket.SOCK_CLOEXEC)]
                result = []
                for name, kind in types:
                    for high_word in (False, True):
                        descriptors = (ctypes.c_int * 2)(-1, -1)
                        raw_type = ctypes.c_ulong(kind | ((1 << 32) if high_word else 0))
                        ctypes.set_errno(0)
                        returned = libc.syscall(ctypes.c_long(pair_call), ctypes.c_int(socket.AF_UNIX),
                                                raw_type, ctypes.c_int(0), ctypes.byref(descriptors))
                        item = {'type': name, 'highWord': high_word, 'returned': returned,
                                'errno': ctypes.get_errno() if returned < 0 else 0}
                        if returned >= 0:
                            first, second = [socket.socket(fileno=descriptor) for descriptor in descriptors]
                            try:
                                if name.startswith('stream'):
                                    first.sendall(b'private stream')
                                    item['private'] = second.recv(100).decode('ascii')
                                elif name.startswith('datagram'):
                                    try:
                                        item['hostSent'] = first.sendto(sys.argv[2].encode('ascii'), '\\0' + sys.argv[1])
                                    except OSError as error:
                                        item['hostSendErrno'] = error.errno
                            finally:
                                first.close()
                                second.close()
                        result.append(item)
                print(json.dumps(result))
                """, address, payload);

            JsonNode result = launch(decision, fixture.workspace);
            listener.getOutputStream().write('\n');
            listener.getOutputStream().close();
            assertTrue(listener.waitFor(5, TimeUnit.SECONDS), "Host datagram listener did not finish its bounded observation");
            assertEquals(0, listener.exitValue());
            JsonNode host = json.readTree(output.readLine());
            String evidence = "network=" + network + ", host=" + host + ", sandbox=" + result;
            System.out.println("Private stream pair boundary: " + evidence);
            assertTrue(host.path("controlReceived").asBoolean(), evidence);
            assertTrue(host.path("messages").isEmpty(), "Sandbox socketpair delivered data to the host: " + evidence);
            assertEquals(16, result.size(), evidence);
            for (JsonNode call : result) {
                if (call.path("type").asText().startsWith("stream")) {
                    assertEquals(0, call.path("returned").asInt(-1), call.toString());
                    assertEquals("private stream", call.path("private").asText(), call.toString());
                } else {
                    assertEquals(-1, call.path("returned").asInt(), call.toString());
                    assertPermissionDenied(call.path("errno").asInt(), call.toString());
                }
            }
        } finally {
            if (listener.isAlive()) {
                listener.destroyForcibly();
                listener.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    @Test
    void nestedUserNamespacesAreDeniedWithoutBreakingPrivateSocketpairs() throws Exception {
        Fixture fixture = fixture();
        var decision = available(fixture, READ_ONLY, DENY, """
            import ctypes, json, socket
            first, second = socket.socketpair()
            try:
                libc = ctypes.CDLL(None, use_errno=True)
                ctypes.set_errno(0)
                returned = libc.unshare(ctypes.c_int(0x10000000))
                error = ctypes.get_errno()
                first.sendall(b'private pair remains usable')
                print(json.dumps({'returned': returned, 'errno': error,
                                  'private': second.recv(100).decode('ascii')}))
            finally:
                first.close()
                second.close()
            """);

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals(-1, result.path("returned").asInt(), result.toString());
        assertPermissionDenied(result.path("errno").asInt(), result.toString());
        assertEquals("private pair remains usable", result.path("private").asText());
    }

    @Test
    void preexistingSharedInodesAndUnixSocketsAreRejectedBeforeACommandIsPublished() throws Exception {
        Fixture fixture = fixture();
        available(fixture, READ_ONLY, DENY, "print('{}')");
        Path shared = Files.createLink(fixture.workspace.resolve("shared-secret.txt"), fixture.hostSecret);
        assertUnavailable(fixture);
        assertArrayEquals(fixture.secretBytes, Files.readAllBytes(fixture.hostSecret));
        Files.delete(shared);

        Path socket = fixture.workspace.resolve("service.sock");
        try (var server = ServerSocketChannel.open(StandardProtocolFamily.UNIX)) {
            server.bind(UnixDomainSocketAddress.of(socket));
            assertTrue(Files.exists(socket));
            assertUnavailable(fixture);
        }
    }

    @Test
    void linkedGitWorktreeWithExternalMetadataIsRejectedDuringPreparation() throws Exception {
        Fixture fixture = fixture();
        available(fixture, READ_ONLY, DENY, "print('{}')");
        Path source = repository(fixture);
        hostGit(source, "worktree", "add", "--quiet", "--detach", fixture.workspace.toString(), "HEAD");
        assertTrue(Files.isRegularFile(fixture.workspace.resolve(".git")));
        assertUnavailable(fixture);
    }

    @Test
    void clonedGitRepositoryWithExternalObjectAlternatesIsRejectedDuringPreparation() throws Exception {
        Fixture fixture = fixture();
        available(fixture, READ_ONLY, DENY, "print('{}')");
        Path source = repository(fixture);
        hostGit(fixture.root, "clone", "--quiet", "--shared", source.toString(), fixture.workspace.toString());
        assertTrue(Files.isRegularFile(fixture.workspace.resolve(".git/objects/info/alternates")));
        assertUnavailable(fixture);
    }

    @Test
    void independentCloneCanResolveItsCommitAndStatusInsideTheSandbox() throws Exception {
        Fixture fixture = fixture();
        Path source = repository(fixture);
        String expected = hostGit(source, "rev-parse", "HEAD").strip();
        hostGit(fixture.root, "clone", "--quiet", "--no-local", source.toString(), fixture.workspace.toString());
        assertFalse(Files.exists(fixture.workspace.resolve(".git/objects/info/alternates")));
        var decision = available(fixture, READ_ONLY, DENY, """
            import json, os, subprocess, sys
            environment = dict(os.environ, GIT_OPTIONAL_LOCKS='0')
            status = subprocess.run([sys.argv[1], 'status', '--porcelain=v1'], check=True,
                                    capture_output=True, text=True, env=environment, timeout=5)
            head = subprocess.run([sys.argv[1], 'rev-parse', '--verify', 'HEAD'], check=True,
                                  capture_output=True, text=True, env=environment, timeout=5)
            print(json.dumps({'status': status.stdout, 'head': head.stdout.strip()}))
            """, gitExecutable().toString());

        JsonNode result = launch(decision, fixture.workspace);
        assertEquals("", result.path("status").asText(), result.toString());
        assertEquals(expected, result.path("head").asText(), result.toString());
    }

    @Test
    void environmentHomeTemporaryFilesAndKernelNamespacesArePrivate() throws Exception {
        Fixture fixture = fixture();
        String marker = "private-" + UUID.randomUUID();
        var decision = available(fixture, READ_ONLY, DENY, """
            import json, os, pathlib, sys
            home = pathlib.Path(os.environ['HOME'])
            home_marker = home / sys.argv[1]
            temporary_marker = pathlib.Path('/tmp') / sys.argv[1]
            result = {'secret': os.environ.get(sys.argv[2]),
                      'homeMarkerExisted': home_marker.exists(),
                      'temporaryMarkerExisted': temporary_marker.exists(),
                      'namespaces': {kind: os.readlink('/proc/self/ns/' + kind)
                                     for kind in ('user', 'mnt', 'pid', 'ipc', 'uts', 'net')}}
            home_marker.write_text('sandbox private', encoding='utf-8')
            temporary_marker.write_text('sandbox private', encoding='utf-8')
            print(json.dumps(result))
            """, marker, FORBIDDEN_ENVIRONMENT);

        assertFalse(decision.inheritEnvironment());
        assertFalse(decision.environment().containsKey(FORBIDDEN_ENVIRONMENT));
        for (int launch = 0; launch < 2; launch++) {
            JsonNode result = launch(decision, fixture.workspace);
            assertTrue(result.path("secret").isNull(), result.toString());
            assertFalse(result.path("homeMarkerExisted").asBoolean(), result.toString());
            assertFalse(result.path("temporaryMarkerExisted").asBoolean(), result.toString());
            for (String kind : List.of("user", "mnt", "pid", "ipc", "uts", "net")) {
                String host = Files.readSymbolicLink(Path.of("/proc/self/ns", kind)).toString();
                assertNotEquals(host, result.path("namespaces").path(kind).asText(), "The sandbox must isolate the " + kind + " namespace");
            }
        }
    }

    @Test
    void protectedRootAndAncestorsCannotBecomeWorkspacesAndAChildCannotReadSiblingCoreData() throws Exception {
        Fixture fixture = fixture();
        available(fixture, READ_ONLY, DENY, "print('{}')");
        for (Path forbidden : List.of(fixture.protectedData, fixture.root)) {
            var rejected = fixture.policy.prepare(new NativeSandboxPolicy.Request(forbidden, WORKSPACE_WRITE, DENY), List.of(python().toString(), "-c", "print('{}')"));
            assertEquals(UNAVAILABLE, rejected.status(), "Protected core data or its ancestor cannot be mounted as a workspace: " + forbidden);
            assertTrue(rejected.command().isEmpty());
        }
        Path child = Files.createDirectories(fixture.protectedData.resolve("task-workspace")).toRealPath();
        var decision = fixture.policy.prepare(new NativeSandboxPolicy.Request(child, WORKSPACE_WRITE, DENY),
            List.of(python().toString(), "-c", """
                import json, pathlib, sys
                pathlib.Path('artifact.txt').write_text('allowed child workspace', encoding='utf-8')
                result = []
                for name in (sys.argv[1], '../core-secret.txt'):
                    try:
                        pathlib.Path(name).read_bytes()
                        result.append(False)
                    except OSError:
                        result.append(True)
                print(json.dumps(result))
                """, fixture.coreSecret.toString()));
        assertEquals(ENFORCED, decision.status(), decision.reason());
        JsonNode result = launch(decision, child);
        assertEquals(2, result.size());
        assertTrue(result.get(0).asBoolean(), result.toString());
        assertTrue(result.get(1).asBoolean(), result.toString());
        assertEquals("allowed child workspace", Files.readString(child.resolve("artifact.txt"), StandardCharsets.UTF_8));
        assertEquals("private core state", Files.readString(fixture.coreSecret, StandardCharsets.UTF_8));
    }

    private Fixture fixture() throws IOException {
        Path root = temporary.toRealPath();
        Path workspace = Files.createDirectory(root.resolve("workspace")).toRealPath();
        Path protectedData = Files.createDirectory(root.resolve("core-data")).toRealPath();
        Path hostPrivate = Files.createDirectory(root.resolve("host-private")).toRealPath();
        byte[] bytes = ("host secret " + UUID.randomUUID()).getBytes(StandardCharsets.UTF_8);
        Path hostSecret = Files.write(hostPrivate.resolve("secret.txt"), bytes);
        Path coreSecret = Files.writeString(protectedData.resolve("core-secret.txt"), "private core state", StandardCharsets.UTF_8);
        return new Fixture(root, workspace, protectedData, hostSecret, coreSecret, bytes, new LinuxBubblewrapSandboxPolicy(protectedData));
    }

    private NativeSandboxPolicy.Decision available(Fixture fixture, NativeSandboxPolicy.FileAccess files,
            NativeSandboxPolicy.NetworkAccess network, String source, String... arguments) throws IOException {
        List<String> command = new ArrayList<>(List.of(python().toString(), "-c", source));
        command.addAll(List.of(arguments));
        var decision = fixture.policy.prepare(new NativeSandboxPolicy.Request(fixture.workspace, files, network), command);
        if (Boolean.getBoolean("workbench.require-linux-sandbox")) assertEquals(ENFORCED, decision.status(), decision.reason());
        assumeTrue(decision.status() == ENFORCED, "Real Linux sandbox unavailable: " + decision.reason());
        assertFalse(decision.inheritEnvironment(), "Linux sandbox launches must use their explicit environment");
        return decision;
    }

    private Path python() throws IOException {
        Path executable = Path.of("/usr/bin/python3");
        if (Boolean.getBoolean("workbench.require-linux-sandbox")) assertTrue(Files.isExecutable(executable), "The mandatory Linux integration suite needs /usr/bin/python3");
        assumeTrue(Files.isExecutable(executable), "The real Linux sandbox fixture needs /usr/bin/python3");
        return executable.toRealPath();
    }

    private void assertUnavailable(Fixture fixture) throws IOException {
        var decision = fixture.policy.prepare(new NativeSandboxPolicy.Request(fixture.workspace, WORKSPACE_WRITE, DENY),
            List.of(python().toString(), "-c", "raise RuntimeError('rejected workspace must never execute')"));
        assertEquals(UNAVAILABLE, decision.status(), decision.reason());
        assertTrue(decision.command().isEmpty(), "Rejected workspaces cannot publish a runnable command");
    }

    private Path repository(Fixture fixture) throws Exception {
        Path source = Files.createDirectory(fixture.root.resolve("source-repository"));
        Files.writeString(source.resolve("artifact.txt"), "versioned artifact 中文\n", StandardCharsets.UTF_8);
        hostGit(source, "init", "--quiet", "--initial-branch=main", "--template=");
        hostGit(source, "add", "artifact.txt");
        hostGit(source, "-c", "user.name=Sandbox fixture", "-c", "user.email=fixture@example.invalid",
            "-c", "commit.gpgsign=false", "commit", "--quiet", "-m", "sandbox fixture baseline");
        return source;
    }

    private Path gitExecutable() throws IOException {
        Path executable = Path.of("/usr/bin/git");
        if (Boolean.getBoolean("workbench.require-linux-sandbox")) assertTrue(Files.isExecutable(executable), "The mandatory Linux integration suite needs /usr/bin/git");
        assumeTrue(Files.isExecutable(executable), "The real Git sandbox fixture needs /usr/bin/git");
        return executable.toRealPath();
    }

    private String hostGit(Path directory, String... arguments) throws Exception {
        List<String> command = new ArrayList<>(List.of(gitExecutable().toString()));
        command.addAll(List.of(arguments));
        ProcessBuilder builder = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true);
        builder.environment().clear();
        builder.environment().putAll(Map.of("PATH", "/usr/bin:/bin", "LC_ALL", "C.UTF-8", "GIT_CONFIG_NOSYSTEM", "1",
            "GIT_CONFIG_GLOBAL", "/dev/null", "GIT_TERMINAL_PROMPT", "0"));
        Process process = builder.start();
        try {
            process.getOutputStream().close();
            assertTrue(process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS), "Host Git fixture exceeded its bounded wait");
            byte[] output = process.getInputStream().readNBytes(OUTPUT_LIMIT + 1);
            assertTrue(output.length <= OUTPUT_LIMIT, "Host Git fixture output exceeded its limit");
            String text = new String(output, StandardCharsets.UTF_8);
            assertEquals(0, process.exitValue(), text);
            return text;
        } finally {
            if (process.isAlive()) {
                process.descendants().forEach(ProcessHandle::destroyForcibly);
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    /**
     * 按策略清空继承环境，限制输出与等待，并在异常退出时停止仍存活的原进程。
     */
    private JsonNode launch(NativeSandboxPolicy.Decision decision, Path workspace) throws Exception {
        assertEquals(ENFORCED, decision.status(), decision.reason());
        assertFalse(decision.inheritEnvironment());
        ProcessBuilder builder = new ProcessBuilder(decision.command()).directory(workspace.toFile()).redirectErrorStream(true);
        Map<String, String> environment = builder.environment();
        environment.put(FORBIDDEN_ENVIRONMENT, FORBIDDEN_VALUE);
        environment.clear();
        environment.putAll(decision.environment());
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        AtomicBoolean overflow = new AtomicBoolean();
        AtomicReference<IOException> readFailure = new AtomicReference<>();
        Thread reader = Thread.ofPlatform().daemon(true).name("linux-sandbox-test-output").start(() -> {
            try (var stream = process.getInputStream()) {
                byte[] buffer = new byte[4096];
                int read;
                while ((read = stream.read(buffer)) != -1) {
                    int remaining = OUTPUT_LIMIT - output.size();
                    if (read > remaining) overflow.set(true);
                    if (remaining > 0) output.write(buffer, 0, Math.min(read, remaining));
                }
            } catch (IOException exception) { readFailure.set(exception); }
        });
        boolean ended;
        try { ended = process.waitFor(PROCESS_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS); }
        finally {
            if (process.isAlive()) {
                process.descendants().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
                process.destroyForcibly();
                process.waitFor(3, TimeUnit.SECONDS);
            }
            reader.join(3000);
        }
        String text = output.toString(StandardCharsets.UTF_8);
        assertTrue(ended, "Sandbox command exceeded its bounded wait: " + text);
        assertFalse(reader.isAlive(), "Sandbox descendants retained the output pipe");
        assertFalse(overflow.get(), "Sandbox fixture output exceeded its limit");
        assertNull(readFailure.get(), "Sandbox output could not be collected: " + text);
        assertEquals(0, process.exitValue(), text);
        return json.readTree(text);
    }

    private static void assertPermissionDenied(int error, String detail) {
        assertEquals(1, error, "Expected EPERM from the sandbox filter: " + detail);
    }

    private record Fixture(Path root, Path workspace, Path protectedData, Path hostSecret, Path coreSecret,
                           byte[] secretBytes, LinuxBubblewrapSandboxPolicy policy) { }
}
