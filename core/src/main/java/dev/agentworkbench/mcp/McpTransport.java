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
import static dev.agentworkbench.Json.*;

/**
 * MCP 2025-06-18 的有界 stdio 与 Streamable HTTP 请求传输。
 */
interface McpTransport extends AutoCloseable {
    Map<String, Object> request(String method, Map<String, Object> params) throws Exception;
    void notify(String method, Map<String, Object> params) throws Exception;
    @Override void close();

    static McpTransport open(Map<String, Object> server, String token, ObjectMapper mapper) throws Exception {
        return "STDIO".equals(str(server, "transport")) ? new Stdio(server, mapper) : new Http(server, token, mapper);
    }

    abstract class Base implements McpTransport {
        static final int MAX_MESSAGE = 1024 * 1024;
        final ObjectMapper mapper;
        final AtomicLong sequence = new AtomicLong();
        Base(ObjectMapper mapper) { this.mapper = mapper; }

        Map<String, Object> message(String method, Map<String, Object> params, boolean notification) {
            Map<String, Object> value = obj("jsonrpc", "2.0", "method", method, "params", params);
            if (!notification) { value.put("id", sequence.incrementAndGet()); }
            return value;
        }

        Map<String, Object> decode(String value) throws Exception { return mapper.readValue(value, new TypeReference<>() { }); }

        Map<String, Object> unwrap(Map<String, Object> response) {
            if (response.containsKey("error")) { throw new McpFailure("MCP 服务返回错误：" + mapper.valueToTree(response.get("error")), false); }
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

        Stdio(Map<String, Object> server, ObjectMapper mapper) throws Exception {
            super(mapper);
            String command = str(server, "command");
            if (command.toLowerCase().matches(".*\\.(cmd|bat)$")) { throw new McpFailure("stdio 请配置可执行文件及参数，例如 node + server.js；不直接解释批处理脚本", false); }
            List<String> args = new ArrayList<>(); args.add(command); args.addAll(strings(server.get("args")));
            ProcessBuilder builder = new ProcessBuilder(args);
            builder.environment().keySet().removeIf(name -> name.toUpperCase(java.util.Locale.ROOT).startsWith("WORKBENCH_") || name.toUpperCase(java.util.Locale.ROOT).matches(".*(API_KEY|TOKEN|PASSWORD|SECRET).*"));
            process = builder.start();
            input = new BufferedWriter(new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
            output = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            Thread.startVirtualThread(() -> { try (InputStream stream = process.getErrorStream()) { stream.transferTo(java.io.OutputStream.nullOutputStream()); } catch (Exception ignored) { } });
        }

        @Override
        public synchronized Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            Map<String, Object> request = message(method, params, false);
            write(request);
            var future = reader.submit(() -> {
                int messages = 0;
                while (messages++ < 1000) {
                    String line = boundedLine(output);
                    if (line == null) { throw new McpFailure("MCP 子进程在返回结果前退出", true); }
                    Map<String, Object> response = decode(line);
                    if (String.valueOf(request.get("id")).equals(String.valueOf(response.get("id"))) && !response.containsKey("method")) { return unwrap(response); }
                    if (response.containsKey("method") && response.containsKey("id")) {
                        write(obj("jsonrpc", "2.0", "id", response.get("id"), "error", obj("code", -32601, "message", "Client capability not supported")));
                    }
                }
                throw new McpFailure("MCP 通知数量超过请求边界", true);
            });
            try { return future.get(30, TimeUnit.SECONDS); }
            catch (Exception exception) { future.cancel(true); close(); throw new McpFailure("MCP 请求未取得确认结果：" + exception.getClass().getSimpleName(), true); }
        }

        @Override
        public synchronized void notify(String method, Map<String, Object> params) throws Exception { write(message(method, params, true)); }

        private void write(Map<String, Object> value) throws Exception { input.write(mapper.writeValueAsString(value)); input.newLine(); input.flush(); }

        @Override
        public void close() {
            try { input.close(); } catch (Exception ignored) { }
            var descendants = process.descendants().toList();
            for (var child : descendants) { child.destroy(); }
            process.destroy();
            try { if (!process.waitFor(500, TimeUnit.MILLISECONDS)) { process.destroyForcibly(); } } catch (InterruptedException exception) { Thread.currentThread().interrupt(); }
            for (var child : descendants) { if (child.isAlive()) { child.destroyForcibly(); } }
            try { output.close(); } catch (Exception ignored) { }
            reader.shutdownNow();
        }
    }

    final class Http extends Base {
        private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
        private final URI uri;
        private final String token;
        private String session;
        private String protocol = "2025-06-18";

        Http(Map<String, Object> server, String token, ObjectMapper mapper) { super(mapper); uri = URI.create(str(server, "url")); this.token = token; }

        @Override
        public Map<String, Object> request(String method, Map<String, Object> params) throws Exception {
            Map<String, Object> request = message(method, params, false);
            HttpResponse<InputStream> response = client.send(builder().POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(request), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.ofInputStream());
            response.headers().firstValue("Mcp-Session-Id").ifPresent(value -> session = value);
            try (InputStream body = response.body(); var reader = Executors.newVirtualThreadPerTaskExecutor()) {
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
                    Map<String, Object> result = future.get(30, TimeUnit.SECONDS);
                    if ("initialize".equals(method)) { protocol = str(result, "protocolVersion"); }
                    return result;
                } catch (Exception exception) { body.close(); future.cancel(true); throw new McpFailure("MCP HTTP 请求未取得确认结果：" + exception.getClass().getSimpleName(), true); }
            }
        }

        @Override
        public void notify(String method, Map<String, Object> params) throws Exception {
            HttpResponse<Void> response = client.send(builder().POST(HttpRequest.BodyPublishers.ofString(mapper.writeValueAsString(message(method, params, true)), StandardCharsets.UTF_8)).build(), HttpResponse.BodyHandlers.discarding());
            if (response.statusCode() < 200 || response.statusCode() >= 300) { throw new McpFailure("MCP 通知被拒绝：HTTP " + response.statusCode(), false); }
        }

        private HttpRequest.Builder builder() {
            HttpRequest.Builder builder = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(30)).header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream").header("MCP-Protocol-Version", protocol);
            if (session != null) { builder.header("Mcp-Session-Id", session); }
            if (!token.isBlank()) { builder.header("Authorization", "Bearer " + token); }
            return builder;
        }

        @Override
        public void close() {
            if (session != null) { try { client.send(builder().DELETE().build(), HttpResponse.BodyHandlers.discarding()); } catch (Exception ignored) { } }
            client.close();
        }
    }

    final class McpFailure extends RuntimeException {
        final boolean unknown;
        McpFailure(String message, boolean unknown) { super(message); this.unknown = unknown; }
    }
}
