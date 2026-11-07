package dev.agentworkbench.mcp;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.CancellationException;
import java.util.function.BooleanSupplier;
import static dev.agentworkbench.Json.*;

/**
 * MCP 2025-06-18 的有界 stdio 与 Streamable HTTP 请求传输。
 */
interface McpTransport extends AutoCloseable {
    Map<String, Object> request(String method, Map<String, Object> params) throws Exception;
    void notify(String method, Map<String, Object> params) throws Exception;
    default boolean stopConfirmed() { return true; }
    default boolean definitionsChanged() { return false; }
    @Override void close();

    static McpTransport open(Map<String, Object> server, String token, ObjectMapper mapper) throws Exception {
        return open(server, token, mapper, () -> false, Duration.ofSeconds(30));
    }

    static McpTransport open(Map<String, Object> server, String token, ObjectMapper mapper, BooleanSupplier cancelled, Duration timeout) throws Exception {
        return "STDIO".equals(str(server, "transport")) ? new Stdio(server, mapper, cancelled, timeout) : new Http(server, token, mapper, cancelled, timeout);
    }

    abstract class Base implements McpTransport {
        static final int MAX_MESSAGE = 1024 * 1024;
        final ObjectMapper mapper;
        final AtomicLong sequence = new AtomicLong();
        final BooleanSupplier cancelled;
        final long deadline;
        volatile boolean changed;
        Base(ObjectMapper mapper, BooleanSupplier cancelled, Duration timeout) { this.mapper = mapper; this.cancelled = cancelled; this.deadline = System.nanoTime() + timeout.toNanos(); }

        void check() {
            if (cancelled.getAsBoolean()) { throw new CancellationException("MCP 执行已请求停止"); }
            if (System.nanoTime() >= deadline) { throw new McpFailure("MCP 总期限已耗尽", true); }
        }

        <T> T await(Future<T> future) throws Exception {
            try {
                while (true) {
                    if (future.isDone()) { return future.get(); }
                    check();
                    try { return future.get(100, TimeUnit.MILLISECONDS); } catch (TimeoutException ignored) { }
                }
            } catch (ExecutionException error) {
                if (error.getCause() instanceof Exception cause) { throw cause; }
                throw error;
            } finally { if (!future.isDone()) { future.cancel(true); } }
        }

        void observe(Map<String, Object> value) { if ("notifications/tools/list_changed".equals(str(value, "method"))) { changed = true; } }
        @Override public boolean definitionsChanged() { return changed; }

        Map<String, Object> message(String method, Map<String, Object> params, boolean notification) {
            Map<String, Object> value = obj("jsonrpc", "2.0", "method", method, "params", params);
            if (!notification) { value.put("id", sequence.incrementAndGet()); }
            return value;
        }

        Map<String, Object> decode(String value) throws Exception { return mapper.readValue(value, new TypeReference<>() { }); }

        Map<String, Object> unwrap(Map<String, Object> response) {
            if (response.containsKey("error")) { throw new RpcFailure(map(response, "error")); }
            if (!response.containsKey("result")) { throw new McpFailure("MCP 响应缺少 result", true); }
            return map(response.get("result"));
        }

        String boundedLine(BufferedReader reader) throws Exception {
            StringBuilder text = new StringBuilder(); int next;
            while ((next = reader.read()) != -1) {
                if (next == '\n') { return text.toString(); }
                if (next != '\r') { text.append((char) next); }
                if (text.length() > MAX_MESSAGE) { throw new McpFailure("MCP 消息超过 1 MB 上限", true); }
            }
            return text.isEmpty() ? null : text.toString();
        }
    }

    final class Stdio extends Base {
        private final Process process;
        private final BufferedWriter input;
        private final BufferedReader output;
        private final java.util.concurrent.ExecutorService reader = Executors.newVirtualThreadPerTaskExecutor();
        private final Map<Long, ProcessHandle> descendants = new java.util.concurrent.ConcurrentHashMap<>();
        private volatile boolean stopped;
        private volatile boolean closed;

        Stdio(Map<String, Object> server, ObjectMapper mapper, BooleanSupplier cancelled, Duration timeout) throws Exception {
            super(mapper, cancelled, timeout);
            check();
            String command = str(server, "command");
            if (command.toLowerCase().matches(".*\\.(cmd|bat)$")) { throw new McpFailure("stdio 请配置可执行文件及参数，例如 node + server.js；不直接解释批处理脚本", false); }
            List<String> args = new ArrayList<>(); args.add(command); args.addAll(strings(server.get("args")));
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.environment().keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("WORKBENCH_") || name.toUpperCase(java.util.Locale.ROOT).matches(".*(API_KEY|TOKEN|PASSWORD|SECRET).*"));
            process = builder.start();
            descendants.put(process.pid(), process.toHandle());
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread.startVirtualThread(() -> { try (InputStream stream = process.getErrorStream()) { stream.transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) { } });
        }

        @Override
        public synchronized Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            check();
            Map<String, Object> request = message(method, params, false);
            write(request);
            var future = reader.submit(() -> {
                int messages = 0;
                while (messages++ < 1000) {
                    String line = boundedLine(output);
                    if (line == null) { throw new McpFailure("MCP 子进程在返回结果前退出", true); }
                    Map<String, Object> response = decode(line);
                    observe(response);
                    if (String.valueOf(request.get("id")).equals(String.valueOf(response.get("id"))) && !response.containsKey("method")) { return unwrap(response); }
                    if (response.containsKey("method") && response.containsKey("id")) {
                        write(obj("jsonrpc", "2.0", "id", response.get("id"), "error", obj("code", -32601, "message", "Client capability not supported")));
                    }
                }
                throw new McpFailure("MCP 通知数量超过请求边界", true);
            });
            try { return await(future); }
            catch (Exception exception) { future.cancel(true); close(); throw exception; }
        }

        @Override void check() {
            if (process != null) { process.descendants().forEach(child -> descendants.put(child.pid(), child)); }
            super.check();
        }

        @Override
        public synchronized void notify(String method, Map<String, Object> params) throws Exception { check(); write(message(method, params, true)); }

        private void write(Map<String, Object> value) throws Exception { input.write(mapper.writeValueAsString(value)); input.newLine(); input.flush(); }

        @Override
        public synchronized void close() {
            if (closed) { return; } closed = true;
            process.descendants().forEach(child -> descendants.put(child.pid(), child));
            long stopDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(1000);
            descendants.values().stream().filter(child -> child.pid() != process.pid()).forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            long childDeadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(200);
            while (descendants.values().stream().anyMatch(child -> child.pid() != process.pid() && child.isAlive()) && System.nanoTime() < childDeadline) {
                try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            process.descendants().forEach(child -> descendants.put(child.pid(), child));
            descendants.values().forEach(child -> { if (child.isAlive()) child.destroyForcibly(); });
            while (descendants.values().stream().anyMatch(ProcessHandle::isAlive) && System.nanoTime() < stopDeadline) {
                try { Thread.sleep(20); } catch (InterruptedException error) { Thread.currentThread().interrupt(); break; }
            }
            stopped = descendants.values().stream().noneMatch(ProcessHandle::isAlive);
            try { process.getOutputStream().close(); } catch (Exception ignored) { }
            try { process.getInputStream().close(); } catch (Exception ignored) { }
            reader.shutdownNow();
        }
        @Override public boolean stopConfirmed() {
            if (closed && !stopped) stopped = descendants.values().stream().noneMatch(ProcessHandle::isAlive);
            return stopped;
        }
    }

    final class Http extends Base {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        private final URI uri;
        private final String token;
        private String session;
        private String protocol = "2025-06-18";

        Http(Map<String, Object> server, String token, ObjectMapper mapper, BooleanSupplier cancelled, Duration timeout) { super(mapper, cancelled, timeout); uri = URI.create(str(server, "url")); this.token = token; }

        @Override
        public Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            check();
            Map<String, Object> request = message(method, params, false);
            HttpResponse<InputStream> response = await(client.sendAsync(builder().POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofInputStream()));
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
            var reader = Executors.newVirtualThreadPerTaskExecutor();
            try (InputStream body = response.body()) {
                if (response.statusCode() != 200) { throw new McpFailure("MCP HTTP 状态 " + response.statusCode() + "；未自动重放", response.statusCode() >= 500 || response.statusCode() == 404); }
                var future = reader.submit(() -> {
                    String type = response.headers().firstValue("Content-Type").orElse("");
                    if (type.contains("text/event-stream")) {
                        BufferedReader lines = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
                        StringBuilder data = new StringBuilder(); String line; int count = 0;
                        while ((line = boundedLine(lines)) != null && count++ < 2000) {
                            if (line.isEmpty()) {
                                if (!data.isEmpty()) {
                                    Map<String, Object> event = decode(data.toString()); data.setLength(0);
                                    observe(event);
                                    if (String.valueOf(request.get("id")).equals(String.valueOf(event.get("id"))) && !event.containsKey("method")) { return unwrap(event); }
                                }
                            } else if (line.startsWith("data:")) { if (!data.isEmpty()) { data.append('\n'); } data.append(line.substring(5).stripLeading()); }
                            if (data.length() > MAX_MESSAGE) { throw new McpFailure("MCP 事件过大", true); }
                        }
                        throw new McpFailure("MCP 事件流未返回匹配请求结果", true);
                    }
                    byte[] bytes = body.readNBytes(MAX_MESSAGE + 1);
                    if (bytes.length > MAX_MESSAGE) { throw new McpFailure("MCP 响应超过 1 MB 上限", true); }
                    Map<String, Object> value = decode(new String(bytes, StandardCharsets.UTF_8));
                    if (!String.valueOf(request.get("id")).equals(String.valueOf(value.get("id")))) { throw new McpFailure("MCP 响应编号不匹配", true); }
                    return unwrap(value);
                });
                try {
                    Map<String, Object> result = await(future);
                    if ("initialize".equals(method)) { protocol = str(result, "protocolVersion"); }
                    return result;
                } catch (Exception exception) { body.close(); future.cancel(true); throw exception; }
            } finally { reader.shutdownNow(); }
        }

        @Override
        public void notify(String method, Map<String, Object> params) throws Exception {
            check();
            HttpResponse<Void> response = await(client.sendAsync(builder().POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message(method, params, true)), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding()));
            if (response.statusCode() < 200 || response.statusCode() >= 300) { throw new McpFailure("MCP 通知被拒绝：HTTP " + response.statusCode(), false); }
        }

        private HttpRequest.Builder builder() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofNanos(Math.max(1, deadline - System.nanoTime()))).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", protocol);
            if (session != null) { builder.header("Mcp-Session-Id", session); }
            if (!token.isBlank()) { builder.header("Authorization", "Bearer " + token); }
            return builder;
        }

        @Override
        public void close() {
            if (session != null) { try { client.sendAsync(builder().timeout(Duration.ofMillis(500)).DELETE().build(), HttpResponse.BodyHandlers.discarding()).get(500, TimeUnit.MILLISECONDS); } catch (Exception ignored) { } }
            client.shutdownNow();
        }
    }

    final class RpcFailure extends RuntimeException {
        final Map<String, Object> error;
        RpcFailure(Map<String, Object> error) { super("MCP 服务已返回错误回执：" + error.get("code")); this.error = error; }
    }

    final class McpFailure extends RuntimeException {
        final boolean unknown;
        McpFailure(String message, boolean unknown) { super(message); this.unknown = unknown; }
    }
}
