package dev.agentworkbench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

/**
 * 私有进程管道使用有界 UTF-8 JSON 帧，原生工具输出不进入协议流。
 */
final class WorkerWire implements AutoCloseable {
    static final int PROTOCOL_VERSION = 1;
    static final int MAX_FRAME_BYTES = 8 * 1024 * 1024;
    private final ObjectMapper mapper;
    private final DataInputStream input;
    private final DataOutputStream output;
    private final ReentrantLock writes = new ReentrantLock();

    WorkerWire(ObjectMapper mapper, InputStream input, OutputStream output) {
        this.mapper = mapper; this.input = new DataInputStream(input); this.output = new DataOutputStream(output);
    }

    Map<String, Object> read() throws IOException {
        int first = input.read();
        if (first < 0) return null;
        int length = (first << 24) | (input.readUnsignedByte() << 16) | (input.readUnsignedByte() << 8) | input.readUnsignedByte();
        if (length < 2 || length > MAX_FRAME_BYTES) throw new IOException("Worker 消息长度超出协议边界");
        byte[] bytes = new byte[length]; input.readFully(bytes);
        try {
            Map<String, Object> message = mapper.readerFor(new TypeReference<Map<String, Object>>() { }).with(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .with(com.fasterxml.jackson.core.JsonParser.Feature.STRICT_DUPLICATE_DETECTION).readValue(bytes);
            if (message == null || !(message.get("type") instanceof String type) || type.isBlank() || type.length() > 40) throw new IOException("Worker 消息类型无效");
            return message;
        } catch (IOException exception) {
            throw new IOException("Worker 消息格式无效，原始帧不会写入日志");
        }
    }

    void write(Map<String, Object> message) throws IOException {
        writes.lock();
        try {
            byte[] bytes;
            try { bytes = mapper.writeValueAsBytes(message); }
            catch (IOException exception) { throw new IOException("Worker 消息无法编码，原始数据不会写入日志"); }
            if (bytes.length < 2 || bytes.length > MAX_FRAME_BYTES) throw new IOException("Worker 消息超过传输上限");
            output.writeInt(bytes.length); output.write(bytes); output.flush();
        } finally { writes.unlock(); }
    }

    void closeOutput() throws IOException { output.close(); }

    @Override public void close() throws IOException {
        try { output.close(); } finally { input.close(); }
    }
}
