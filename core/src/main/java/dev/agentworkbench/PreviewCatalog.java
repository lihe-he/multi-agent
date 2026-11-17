package dev.agentworkbench;

import org.springframework.stereotype.Service;
import java.net.URI;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import static dev.agentworkbench.Json.*;

/**
 * 核心保存预览范围与标签，浏览器登录数据只由隔离的桌面会话持有。
 */
@Service
public class PreviewCatalog {
    private final ExecutionStore store;
    private final WorkbenchCatalog catalog;

    public PreviewCatalog(ExecutionStore store, WorkbenchCatalog catalog) { this.store = store; this.catalog = catalog; }

    public Map<String, Object> get(String projectId, String sessionId) {
        synchronized (store) {
            store.session(projectId, sessionId);
            Map<String, Object> current = store.find("previewSession", sessionId);
            if (current != null) { scope(current, projectId, sessionId); return current; }
            Map<String, Object> node = catalog.nodes().localNode();
            Map<String, Object> identity = store.find("previewIdentity", "local");
            if (identity == null) { identity = obj("id", "local", "serverId", id(), "createdAt", now()); store.put("previewIdentity", identity); }
            current = obj("id", sessionId, "projectId", projectId, "sessionId", sessionId, "executionNodeId", node.get("id"),
                "serverId", identity.get("serverId"), "version", 1, "authorizationVersion", 1, "storageGeneration", 1,
                "allowedLoopbackPorts", List.of(), "tabs", List.of(), "activeTabId", "", "createdAt", now(), "updatedAt", now(), "protocolVersion", 1);
            return store.put("previewSession", current);
        }
    }

    /**
     * 标签和本机端口授权按整份当前版本提交，旧响应不能覆盖较新的用户选择。
     */
    public Map<String, Object> update(String projectId, String sessionId, Map<String, Object> input) {
        if (input == null) throw new ApiException(400, "预览配置必须为对象。");
        fields(input, Set.of("expectedVersion", "allowedLoopbackPorts", "tabs", "activeTabId"));
        synchronized (store) {
            Map<String, Object> session = store.session(projectId, sessionId);
            if (!bool(session, "isOpen", false)) throw new ApiException(409, "请先打开原会话再修改预览。");
            Map<String, Object> current = get(projectId, sessionId); version(input, current);
            List<Integer> oldPorts = ports(current.get("allowedLoopbackPorts"));
            List<Integer> newPorts = input.containsKey("allowedLoopbackPorts") ? ports(input.get("allowedLoopbackPorts")) : oldPorts;
            Object priorTabs = oldPorts.equals(newPorts) ? current.get("tabs") : removeUnboundTabs(current.get("tabs"), newPorts);
            List<Map<String, Object>> newTabs = input.containsKey("tabs") ? tabs(input.get("tabs"), newPorts) : tabs(priorTabs, newPorts);
            String active = input.containsKey("activeTabId") ? text(input, "activeTabId", 100, true) : str(current, "activeTabId");
            final String selected = active;
            boolean activeExists = selected.isEmpty() || newTabs.stream().anyMatch(tab -> selected.equals(str(tab, "id")));
            if (!activeExists && !oldPorts.equals(newPorts) && !input.containsKey("activeTabId")) active = "";
            else if (!activeExists) throw new ApiException(400, "选中标签必须属于当前预览会话。");
            Map<String, Object> changed = new LinkedHashMap<>(current);
            changed.putAll(obj("allowedLoopbackPorts", newPorts, "tabs", newTabs, "activeTabId", active,
                "version", num(current, "version", 0) + 1,
                "authorizationVersion", num(current, "authorizationVersion", 1) + (oldPorts.equals(newPorts) ? 0 : 1), "updatedAt", now()));
            return store.put("previewSession", changed);
        }
    }

    /**
     * 重置先推进隔离代次，旧页面不再属于当前授权；实际存储清理由桌面单独回执。
     */
    public Map<String, Object> reset(String projectId, String sessionId, Map<String, Object> input) {
        if (input == null) throw new ApiException(400, "预览配置必须为对象。");
        fields(input, Set.of("expectedVersion"));
        synchronized (store) {
            if (!bool(store.session(projectId, sessionId), "isOpen", false)) throw new ApiException(409, "请先打开原会话再重置预览。");
            Map<String, Object> current = get(projectId, sessionId); version(input, current);
            Map<String, Object> changed = new LinkedHashMap<>(current);
            changed.putAll(obj("storageGeneration", num(current, "storageGeneration", 0) + 1, "version", num(current, "version", 0) + 1,
                "authorizationVersion", num(current, "authorizationVersion", 1) + 1, "tabs", List.of(), "activeTabId", "", "updatedAt", now()));
            return store.put("previewSession", changed);
        }
    }

    /**
     * 桌面每次附加和轮询都核对当前会话与节点，失效配置不能继续驱动旧页面。
     */
    public Map<String, Object> authorize(String projectId, String sessionId) {
        synchronized (store) {
            if (!bool(store.session(projectId, sessionId), "isOpen", false)) throw new ApiException(409, "原协作会话已关闭。");
            Map<String, Object> current = get(projectId, sessionId), node = store.get("executionNode", str(current, "executionNodeId"));
            if (!bool(node, "enabled", false) || !str(node, "id").equals(str(catalog.nodes().localNode(), "id"))) throw new ApiException(403, "原本机节点已停用或已变化。");
            ports(current.get("allowedLoopbackPorts"));
            return current;
        }
    }

    private static List<Integer> ports(Object raw) {
        if (!(raw instanceof List<?> values) || values.size() > 16) throw new ApiException(400, "本机预览端口最多十六个。");
        List<Integer> result = new ArrayList<>();
        for (Object value : values) {
            if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1 || ((Number) value).longValue() > 65535) throw new ApiException(400, "预览端口必须为1到65535的整数。");
            int port = ((Number) value).intValue();
            if (!result.contains(port)) result.add(port);
        }
        return result.stream().sorted().toList();
    }

    private static List<Map<String, Object>> tabs(Object raw, List<Integer> ports) {
        if (!(raw instanceof List<?> values) || values.size() > 12) throw new ApiException(400, "每个预览会话最多十二个标签。");
        List<Map<String, Object>> result = new ArrayList<>(); Set<String> ids = new HashSet<>();
        for (Object value : values) {
            if (!(value instanceof Map<?, ?> map) || map.keySet().stream().anyMatch(key -> !(key instanceof String))) throw new ApiException(400, "预览标签必须为对象。");
            Map<String, Object> tab = new LinkedHashMap<>(); map.forEach((key, item) -> tab.put((String) key, item));
            fields(tab, Set.of("id", "url", "title"));
            String tabId = text(tab, "id", 100, false);
            if (!tabId.matches("[a-zA-Z0-9_-]{1,100}") || !ids.add(tabId)) throw new ApiException(400, "标签编号无效或重复。");
            String url = address(text(tab, "url", 8192, false), ports), title = text(tab, "title", 180, true);
            result.add(obj("id", tabId, "url", url, "title", title));
        }
        return result;
    }

    private static List<Map<String, Object>> removeUnboundTabs(Object raw, List<Integer> ports) {
        List<Map<String, Object>> retained = new ArrayList<>();
        for (Map<String, Object> tab : objects(raw)) {
            try { address(str(tab, "url"), ports); retained.add(tab); }
            catch (ApiException exception) { if (exception.status() != 403) throw exception; }
        }
        return retained;
    }

    static String address(String input, List<Integer> ports) {
        try {
            URI url = URI.create(input);
            if (url.getScheme() == null || !Set.of("http", "https").contains(url.getScheme().toLowerCase(java.util.Locale.ROOT))
                || url.getRawUserInfo() != null || url.getHost() == null || url.getPort() > 65535 || url.getPort() == 0) throw new IllegalArgumentException();
            String scheme = url.getScheme().toLowerCase(java.util.Locale.ROOT);
            String host = url.getHost().toLowerCase(java.util.Locale.ROOT).replaceFirst("\\.$", "");
            boolean local = loopback(host);
            if (ambiguousNumericHost(host)) throw new IllegalArgumentException();
            int port = url.getPort() >= 0 ? url.getPort() : scheme.equals("https") ? 443 : 80;
            if (local && !ports.contains(port)) throw new ApiException(403, "请先绑定该会话的本机预览端口。");
            String authority = url.getRawAuthority();
            return scheme + "://" + authority + (url.getRawPath().isEmpty() ? "/" : url.getRawPath());
        } catch (ApiException exception) { throw exception; }
        catch (IllegalArgumentException exception) { throw new ApiException(400, "预览只接受不含用户名或密码的HTTP/HTTPS地址。"); }
    }

    private static boolean loopback(String host) {
        String value = host.replaceFirst("^\\[", "").replaceFirst("\\]$", "");
        if (value.equals("localhost") || value.endsWith(".localhost")) return true;
        if (value.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) return value.startsWith("127.");
        if (!value.contains(":")) return false;
        try {
            byte[] address = InetAddress.getByName(value).getAddress();
            if (address.length == 4) return (address[0] & 0xff) == 127;
            boolean mapped = true;
            for (int index = 0; index < 10; index++) mapped &= address[index] == 0;
            mapped &= (address[10] & 0xff) == 0xff && (address[11] & 0xff) == 0xff;
            boolean ipv6Loopback = address[15] == 1;
            for (int index = 0; index < 15; index++) ipv6Loopback &= address[index] == 0;
            return mapped && (address[12] & 0xff) == 127 || ipv6Loopback;
        } catch (UnknownHostException exception) { throw new IllegalArgumentException(exception); }
    }

    private static boolean ambiguousNumericHost(String host) {
        if (host.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+")) {
            for (String part : host.split("\\.")) if ((part.length() > 1 && part.startsWith("0")) || Integer.parseInt(part) > 255) return true;
            return false;
        }
        if (host.contains(":")) return false;
        String last = host.substring(host.lastIndexOf('.') + 1);
        return last.matches("(?i)(?:0x[0-9a-f]+|[0-9]+)");
    }

    private static void scope(Map<String, Object> current, String projectId, String sessionId) {
        if (!projectId.equals(str(current, "projectId")) || !sessionId.equals(str(current, "sessionId"))) throw new ApiException(403, "预览记录不属于当前项目会话。");
    }

    private static void version(Map<String, Object> input, Map<String, Object> current) {
        Object value = input.get("expectedVersion");
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() != num(current, "version", 0)) throw new ApiException(409, "预览配置已经变化，请刷新后确认。");
    }

    private static String text(Map<String, Object> input, String key, int maximum, boolean empty) {
        if (!(input.get(key) instanceof String value) || value.length() > maximum || (!empty && value.isBlank())) throw new ApiException(400, key + "文本无效或超过长度限制。");
        return value;
    }

    private static void fields(Map<String, Object> input, Set<String> allowed) {
        if (input == null) throw new ApiException(400, "预览配置必须为对象。");
        if (!allowed.containsAll(input.keySet())) throw new ApiException(400, "预览配置包含不支持的字段。");
    }
}
