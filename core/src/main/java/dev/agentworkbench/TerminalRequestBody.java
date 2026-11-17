package dev.agentworkbench;

import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import java.io.BufferedReader;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/**
 * 终端 JSON 在解析前独立限长，已认证的大请求也不能进入输入派发路径。
 */
final class TerminalRequestBody {
    static final int MAX_BYTES = 32_768;

    static boolean applies(HttpServletRequest request) {
        return "POST".equals(request.getMethod()) && request.getRequestURI().matches("/api/(?:tasks/[^/]+/terminals|terminals/[^/]+/(?:control|actions))");
    }

    static HttpServletRequest bounded(HttpServletRequest request) throws IOException {
        if (request.getContentLengthLong() > MAX_BYTES) throw new TooLarge();
        byte[] bytes = request.getInputStream().readNBytes(MAX_BYTES + 1);
        if (bytes.length > MAX_BYTES) throw new TooLarge();
        ByteArrayInputStream input = new ByteArrayInputStream(bytes);
        ServletInputStream stream = new ServletInputStream() {
            @Override public int read() { return input.read(); }
            @Override public int read(byte[] target, int offset, int length) { return input.read(target, offset, length); }
            @Override public boolean isFinished() { return input.available() == 0; }
            @Override public boolean isReady() { return true; }
            @Override public void setReadListener(ReadListener listener) { throw new IllegalStateException("终端请求体按同步有界 JSON 解析"); }
        };
        return new HttpServletRequestWrapper(request) {
            @Override public int getContentLength() { return bytes.length; }
            @Override public long getContentLengthLong() { return bytes.length; }
            @Override public ServletInputStream getInputStream() { return stream; }
            @Override public BufferedReader getReader() { return new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8)); }
        };
    }

    static final class TooLarge extends IOException { }
}
