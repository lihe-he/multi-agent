package dev.agentworkbench.mcp;

import dev.agentworkbench.ApiException;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import static dev.agentworkbench.Json.*;

/**
 * 外部 MCP 客户端使用项目限定的会话令牌，不能取得桌面管理权限。
 */
@Component
public class McpAccessRegistry {
    private final Map<String, Grant> grants = new ConcurrentHashMap<>();

    public Map<String, Object> issue(String projectId) {
        if (grants.size() >= 256) { throw new ApiException(409, "MCP 会话授权已达 256 项，请撤销不再使用的令牌"); }
        byte[] entropy = new byte[32]; new SecureRandom().nextBytes(entropy);
        String token = "mcp_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        Grant grant = new Grant(id(), projectId, digest(token), now());
        grants.put(grant.id(), grant);
        Map<String, Object> result = visible(grant); result.put("token", token); return result;
    }

    public List<Map<String, Object>> list() { return grants.values().stream().map(this::visible).toList(); }

    public void revoke(String id) {
        if (grants.remove(id) == null) { throw new ApiException(404, "MCP 会话授权不存在或已过期"); }
    }

    public boolean permits(String authorization, String requestPath, String method) {
        if (authorization == null || !authorization.startsWith("Bearer mcp_") || !Set.of("GET", "POST").contains(method)) { return false; }
        byte[] actual = digest(authorization.substring("Bearer ".length()));
        return grants.values().stream().anyMatch(grant -> requestPath.equals("/api/mcp/rpc/" + grant.projectId()) && MessageDigest.isEqual(grant.tokenHash(), actual));
    }

    private Map<String, Object> visible(Grant grant) { return obj("id", grant.id(), "projectId", grant.projectId(), "endpoint", "/api/mcp/rpc/" + grant.projectId(), "createdAt", grant.createdAt(), "scope", "PROJECT_READ_ONLY", "credentialStorage", "SESSION_ONLY"); }

    private byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private record Grant(String id, String projectId, byte[] tokenHash, String createdAt) { }
}
