package dev.agentworkbench;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import dev.agentworkbench.mcp.McpAccessRegistry;
import org.springframework.web.filter.OncePerRequestFilter;
import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Set;

/**
 * 核心仅服务持有启动令牌的本机客户端，不接受查询参数中的密钥。
 */
@Component
public class LocalAccessFilter extends OncePerRequestFilter {
    private final String token;
    private final McpAccessRegistry mcpAccess;

    public LocalAccessFilter(String configured) { this(configured, new McpAccessRegistry()); }

    @Autowired
    public LocalAccessFilter(@Value("${workbench.token:}") String configured, McpAccessRegistry mcpAccess) {
        this.mcpAccess = mcpAccess;
        if (configured.isBlank()) {
            byte[] bytes = new byte[32]; new SecureRandom().nextBytes(bytes);
            token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
            System.out.println("WORKBENCH_TOKEN=" + token);
        } else {
            if (configured.length() < 24) { throw new IllegalArgumentException("WORKBENCH_TOKEN 至少 24 个字符"); }
            token = configured;
        }
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain) throws ServletException, IOException {
        response.setHeader("X-Content-Type-Options", "nosniff");
        response.setHeader("Cache-Control", "no-store");
        if (!Set.of("127.0.0.1", "0:0:0:0:0:0:0:1", "::1").contains(request.getRemoteAddr())) { reject(response, 403, "只允许本机连接"); return; }
        String origin = request.getHeader("Origin");
        if (origin != null) {
            try {
                if (!"null".equals(origin)) {
                    URI parsed = URI.create(origin);
                    if (!Set.of("http", "https").contains(parsed.getScheme()) || !Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(parsed.getHost())) { reject(response, 403, "不允许此来源"); return; }
                }
                response.setHeader("Access-Control-Allow-Origin", origin);
                response.setHeader("Vary", "Origin");
                response.setHeader("Access-Control-Allow-Headers", "Authorization,Content-Type,Last-Event-ID");
                response.setHeader("Access-Control-Allow-Methods", "GET,POST,PATCH,DELETE,OPTIONS");
            } catch (IllegalArgumentException exception) { reject(response, 403, "来源无效"); return; }
        }
        if ("OPTIONS".equals(request.getMethod())) { response.setStatus(204); return; }
        String authorization = request.getHeader("Authorization");
        byte[] actual = authorization == null ? new byte[0] : authorization.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), actual) && !mcpAccess.permits(authorization, request.getRequestURI(), request.getMethod())) { reject(response, 401, "连接令牌无效、已撤销或不适用于此范围"); return; }
        chain.doFilter(request, response);
    }

    private void reject(HttpServletResponse response, int code, String message) throws IOException {
        response.setStatus(code); response.setContentType("application/json;charset=UTF-8");
        response.getWriter().write("{\"error\":\"" + message + "\"}");
    }
}
