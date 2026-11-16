package dev.agentworkbench.runtime;

import dev.agentworkbench.ApiException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 固定对象编号的有界 Git 批协议；逐项核验身份、类型、长度、原哈希与结束边界。
 */
final class GitBlobBatch {
    static final int MAX_OBJECTS = 256;
    static final int MAX_HEADER = 96;
    static final int MAX_BYTES = 2 * 1024 * 1024;
    static final int MAX_FILE = 256_000;

    private GitBlobBatch() { }

    static String request(List<String> objects) {
        if (objects.isEmpty() || objects.size() > MAX_OBJECTS || new HashSet<>(objects).size() != objects.size()
                || objects.stream().anyMatch(value -> value == null || !value.matches("(?:[0-9a-f]{40}|[0-9a-f]{64})")))
            throw conflict("批量对象请求无效或超过有界清单。");
        return String.join("\n", objects) + "\n";
    }

    static Map<String, Long> sizes(List<String> objects, byte[] output) {
        request(objects);
        if (output.length > objects.size() * MAX_HEADER) throw conflict("批量对象信息超过完整读取上限。");
        Cursor cursor = new Cursor(output); Map<String, Long> result = new LinkedHashMap<>();
        for (String object : objects) result.put(object, cursor.header(object));
        cursor.finished(); return result;
    }

    static int contentBound(Map<String, Long> objects) {
        request(List.copyOf(objects.keySet()));
        long total = 0;
        for (Long bytes : objects.values()) {
            if (bytes == null || bytes < 0 || bytes > MAX_FILE) throw conflict("批量对象内容超过单文件读取上限。");
            total += bytes + MAX_HEADER + 1;
        }
        if (total > MAX_BYTES) throw conflict("批量对象内容超过单批读取上限。");
        return (int) total;
    }

    static Map<String, byte[]> contents(Map<String, Long> objects, byte[] output) {
        if (output.length > contentBound(objects)) throw conflict("批量对象输出超过原清单边界。");
        Cursor cursor = new Cursor(output); Map<String, byte[]> result = new LinkedHashMap<>();
        for (Map.Entry<String, Long> entry : objects.entrySet()) {
            long size = cursor.header(entry.getKey());
            if (size != entry.getValue()) throw conflict("批量对象长度与原信息回执不一致。");
            byte[] bytes = cursor.content((int) size);
            if (!hash(entry.getKey().length() == 40 ? "SHA-1" : "SHA-256", bytes).equals(entry.getKey())) throw conflict("批量对象字节与原 Git 哈希不一致。");
            result.put(entry.getKey(), bytes);
        }
        cursor.finished(); return result;
    }

    private static String hash(String algorithm, byte[] bytes) {
        try {
            MessageDigest digest = MessageDigest.getInstance(algorithm);
            digest.update(("blob " + bytes.length + "\0").getBytes(StandardCharsets.US_ASCII)); digest.update(bytes);
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException exception) { throw new IllegalStateException(exception); }
    }

    private static final class Cursor {
        private final byte[] output;
        private int offset;

        private Cursor(byte[] output) { this.output = output; }

        private long header(String object) {
            int end = offset;
            while (end < output.length && output[end] != '\n' && end - offset < MAX_HEADER) {
                if (output[end] < 32 || output[end] > 126) throw conflict("批量对象信息包含无效字符。");
                end++;
            }
            if (end == output.length || end - offset >= MAX_HEADER) throw conflict("批量对象信息缺少完整结束边界。");
            String[] fields = new String(output, offset, end - offset, StandardCharsets.US_ASCII).split(" ", -1);
            offset = end + 1;
            if (fields.length != 3 || !object.equals(fields[0]) || !"blob".equals(fields[1]) || !fields[2].matches("(?:0|[1-9][0-9]*)"))
                throw conflict("批量对象编号、类型或长度无效，不能接受缺失对象。");
            try { return Long.parseLong(fields[2]); }
            catch (NumberFormatException exception) { throw conflict("批量对象长度无法完整表示。"); }
        }

        private byte[] content(int size) {
            if (size >= output.length - offset || output[offset + size] != '\n') throw conflict("批量对象内容缺失或分隔符不一致。");
            byte[] bytes = Arrays.copyOfRange(output, offset, offset + size); offset += size + 1; return bytes;
        }

        private void finished() { if (offset != output.length) throw conflict("批量对象输出包含未请求的额外字节。"); }
    }

    private static ApiException conflict(String message) { return new ApiException(409, message); }
}
