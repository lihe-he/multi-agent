package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerWireTest {
    @Test
    void fragmentedUtf8FramesRemainSeparateAndPreserveOriginalValues() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (var sender = new WorkerWire(new ObjectMapper(), InputStream.nullInputStream(), bytes)) {
            sender.write(obj("type", "RESULT", "id", 17, "output", "中文\nemoji 🧪\u0000"));
            sender.write(obj("type", "HEARTBEAT", "epoch", 3));
        }
        InputStream fragmented = new ByteArrayInputStream(bytes.toByteArray()) {
            @Override public synchronized int read(byte[] buffer, int offset, int length) { return super.read(buffer, offset, Math.min(length, 1)); }
        };
        try (var receiver = new WorkerWire(new ObjectMapper(), fragmented, ByteArrayOutputStream.nullOutputStream())) {
            var first = receiver.read(); assertEquals("RESULT", first.get("type")); assertEquals(17, num(first, "id", 0)); assertEquals("中文\nemoji 🧪\u0000", first.get("output"));
            assertEquals("HEARTBEAT", receiver.read().get("type")); assertNull(receiver.read());
        }
    }

    @Test
    void invalidLengthTruncatedAndMalformedFramesFailWithoutEchoingPrivateContent() throws Exception {
        for (int length : new int[] {-1, 0, 1, WorkerWire.MAX_FRAME_BYTES + 1}) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); new DataOutputStream(bytes).writeInt(length);
            try (var receiver = new WorkerWire(new ObjectMapper(), new ByteArrayInputStream(bytes.toByteArray()), ByteArrayOutputStream.nullOutputStream())) {
                assertThrows(IOException.class, receiver::read);
            }
        }
        for (byte[] frame : new byte[][] {{0}, {0, 0, 0, 4, 123}, framed("{\"type\":\"RESULT\",\"private\":\"private-frame-secret\" broken}"), framed("[]"), framed("null"),
                framed("{\"type\":\"RESULT\"}{\"private\":\"private-frame-secret\"}"), framed("{\"type\":\"RESULT\",\"type\":\"REPLAY\",\"private\":\"private-frame-secret\"}")}) {
            try (var receiver = new WorkerWire(new ObjectMapper(), new ByteArrayInputStream(frame), ByteArrayOutputStream.nullOutputStream())) {
                IOException failure = assertThrows(IOException.class, receiver::read);
                assertFalse(failure.toString().contains("private-frame-secret")); assertNull(failure.getCause());
            }
        }
    }

    private static byte[] framed(String text) throws Exception {
        byte[] payload = text.getBytes(StandardCharsets.UTF_8); ByteArrayOutputStream bytes = new ByteArrayOutputStream(); DataOutputStream output = new DataOutputStream(bytes);
        output.writeInt(payload.length); output.write(payload); return bytes.toByteArray();
    }
}
