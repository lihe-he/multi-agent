package dev.agentworkbench.mcp;

import dev.agentworkbench.ApiException;
import org.springframework.stereotype.Component;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * 单次阶段调用的短期只读授权，身份与令牌摘要仅保存在当前进程。
 */
@Component
public class StageInputMcpRegistry {
    private static final String PREFIX = "/api/mcp/stage-input/";
    private static final int LIMIT = 256;
    private final Map<String, Grant> grants = new HashMap<>();
    private final SecureRandom random = new SecureRandom();
    private final Clock clock;

    public StageInputMcpRegistry() { this(Clock.systemUTC()); }

    StageInputMcpRegistry(Clock clock) { this.clock = clock; }

    /**
     * 令牌只在创建结果中返回，默认字符串表示不包含凭据。
     */
    public record Handle(String id, String token, String endpoint) {
        @Override
        public String toString() { return "Handle[id=" + id + ", token=[redacted], endpoint=" + endpoint + "]"; }
    }

    /**
     * 绑定原始调用身份与实时读取器，不建立持久化或管理列表入口。
     */
    public Handle open(Map<String, Object> identity, Supplier<Map<String, Object>> read, Duration lifetime) {
        if (identity == null || read == null || lifetime == null || lifetime.isZero() || lifetime.isNegative()) {
            throw new ApiException(400, "Invalid stage input grant");
        }
        Map<String, Object> snapshot = immutableMap(identity);
        Instant now = clock.instant();
        Instant expiresAt;
        try { expiresAt = now.plus(lifetime); }
        catch (ArithmeticException | DateTimeException exception) { throw new ApiException(400, "Invalid stage input lifetime"); }
        byte[] entropy = new byte[32]; random.nextBytes(entropy);
        String token = "stage_" + Base64.getUrlEncoder().withoutPadding().encodeToString(entropy);
        synchronized (grants) {
            grants.values().removeIf(grant -> !now.isBefore(grant.expiresAt));
            if (grants.size() >= LIMIT) { throw new ApiException(409, "Stage input grant limit reached"); }
            String id;
            do { id = UUID.randomUUID().toString(); } while (grants.containsKey(id));
            grants.put(id, new Grant(snapshot, read, digest(token), expiresAt));
            return new Handle(id, token, PREFIX + id);
        }
    }

    /**
     * 撤销不等待在途读取，调用方可在自身回调锁内完成调用生命周期清理。
     */
    public void revoke(String id) { synchronized (grants) { grants.remove(id); } }

    public boolean permits(String authorization, String path, String method) {
        if (path == null || method == null || !Set.of("GET", "POST").contains(method) || !path.startsWith(PREFIX)) { return false; }
        String id = path.substring(PREFIX.length());
        return !id.isEmpty() && authorized(authorization, id) != null;
    }

    /**
     * 读取器自行重新校验原调用；调用期间不持内部锁，返回前再确认授权存活。
     */
    public Map<String, Object> read(String authorization, String id) {
        Grant grant = authorized(authorization, id);
        if (grant == null) { throw unavailable(); }
        Map<String, Object> result;
        try { result = immutableMap(grant.read.get()); }
        catch (Exception exception) {
            synchronized (grants) { grants.remove(id, grant); }
            throw unavailable();
        }
        synchronized (grants) {
            if (grants.get(id) != grant || !clock.instant().isBefore(grant.expiresAt)) {
                grants.remove(id, grant);
                throw unavailable();
            }
        }
        return result;
    }

    private Grant authorized(String authorization, String id) {
        if (authorization == null || id == null || !authorization.matches("Bearer stage_[A-Za-z0-9_-]{43}")) { return null; }
        byte[] actual = digest(authorization.substring("Bearer ".length()));
        synchronized (grants) {
            Grant grant = grants.get(id);
            if (grant == null) { return null; }
            if (!clock.instant().isBefore(grant.expiresAt)) { grants.remove(id, grant); return null; }
            return MessageDigest.isEqual(grant.tokenHash, actual) ? grant : null;
        }
    }

    private static byte[] digest(String value) {
        try { return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)); }
        catch (NoSuchAlgorithmException exception) { throw new IllegalStateException("SHA-256 unavailable"); }
    }

    private static ApiException unavailable() { return new ApiException(403, "Stage input unavailable"); }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> immutableMap(Map<String, Object> value) {
        if (value == null) { throw new ApiException(400, "Invalid stage input data"); }
        return (Map<String, Object>) immutable(value, new IdentityHashMap<>(), 0);
    }

    private static Object immutable(Object value, IdentityHashMap<Object, Boolean> ancestors, int depth) {
        if (value == null || value instanceof String || value instanceof Boolean || value instanceof Byte
            || value instanceof Short || value instanceof Integer || value instanceof Long || value instanceof Float
            || value instanceof Double || value instanceof BigInteger || value instanceof BigDecimal) { return value; }
        if (depth >= 64 || ancestors.put(value, true) != null) { throw new ApiException(400, "Invalid stage input data"); }
        try {
            if (value instanceof Map<?, ?> map) {
                Map<String, Object> copy = new LinkedHashMap<>();
                for (Map.Entry<?, ?> entry : map.entrySet()) {
                    if (!(entry.getKey() instanceof String key)) { throw new ApiException(400, "Invalid stage input data"); }
                    copy.put(key, immutable(entry.getValue(), ancestors, depth + 1));
                }
                return Collections.unmodifiableMap(copy);
            }
            if (value instanceof List<?> list) {
                List<Object> copy = new ArrayList<>();
                for (Object item : list) { copy.add(immutable(item, ancestors, depth + 1)); }
                return Collections.unmodifiableList(copy);
            }
            throw new ApiException(400, "Invalid stage input data");
        } finally { ancestors.remove(value); }
    }

    private static final class Grant {
        private final Map<String, Object> identity;
        private final Supplier<Map<String, Object>> read;
        private final byte[] tokenHash;
        private final Instant expiresAt;

        private Grant(Map<String, Object> identity, Supplier<Map<String, Object>> read, byte[] tokenHash, Instant expiresAt) {
            this.identity = identity; this.read = read; this.tokenHash = tokenHash; this.expiresAt = expiresAt;
        }
    }
}
