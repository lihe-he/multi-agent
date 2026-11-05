package dev.agentworkbench;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * 版本化 JSON 契约的集中访问器。
 */
public final class Json {
    private Json() { }

    public static Map<String, Object> obj(Object... pairs) {
        Map<String, Object> result = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            result.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return result;
    }

    public static String str(Map<String, Object> value, String key) {
        return value.get(key) == null ? "" : String.valueOf(value.get(key));
    }

    public static int num(Map<String, Object> value, String key, int fallback) {
        Object item = value.get(key);
        if (item instanceof Number number) { return number.intValue(); }
        try { return Integer.parseInt(String.valueOf(item)); } catch (NumberFormatException ignored) { return fallback; }
    }

    public static boolean bool(Map<String, Object> value, String key, boolean fallback) {
        Object item = value.get(key);
        return item == null ? fallback : Boolean.parseBoolean(String.valueOf(item));
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> map(Object value) {
        return value instanceof Map<?, ?> ? (Map<String, Object>) value : new LinkedHashMap<>();
    }

    public static Map<String, Object> map(Map<String, Object> value, String key) { return map(value.get(key)); }

    @SuppressWarnings("unchecked")
    public static List<Map<String, Object>> objects(Object value) {
        return value instanceof List<?> ? (List<Map<String, Object>>) value : new ArrayList<>();
    }

    public static List<String> strings(Object value) {
        if (!(value instanceof List<?> list)) { return new ArrayList<>(); }
        return list.stream().map(String::valueOf).toList();
    }

    public static String id() { return UUID.randomUUID().toString(); }
    public static String now() { return Instant.now().toString(); }

    public static String required(Map<String, Object> value, String key, int maximum) {
        String text = str(value, key).trim();
        if (text.isEmpty() || text.length() > maximum) {
            throw new ApiException(400, key + " 必须填写，且不能超过 " + maximum + " 字符");
        }
        return text;
    }
}
