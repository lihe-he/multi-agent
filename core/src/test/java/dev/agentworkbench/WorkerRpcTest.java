package dev.agentworkbench;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.channels.Channels;
import java.nio.channels.Pipe;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static dev.agentworkbench.Json.*;
import static org.junit.jupiter.api.Assertions.*;

class WorkerRpcTest {
    @Test
    void concurrentCallsAndNestedCallbacksKeepTheirOriginalValues() throws Exception {
        ChannelPair transport = channels();
        AtomicReference<WorkerRpc> right = new AtomicReference<>(); AtomicInteger dispatched = new AtomicInteger(), callbacks = new AtomicInteger();
        try (WorkerRpc left = new WorkerRpc(transport.left(), (method, arguments) -> {
            assertEquals("MODEL_RECEIPT", method); callbacks.incrementAndGet(); return obj("requestId", arguments.get("requestId"), "original", arguments.get("original"));
        }, (name, arguments) -> {}, ignored -> {});
             WorkerRpc peer = new WorkerRpc(transport.right(), (method, arguments) -> {
                 dispatched.incrementAndGet(); assertEquals("RUN", method);
                 if (num(arguments, "original", 0) % 2 == 0) Thread.sleep(20);
                 return right.get().request("MODEL_RECEIPT", arguments).get(5, TimeUnit.SECONDS);
             }, (name, arguments) -> {}, ignored -> {});
             var callers = Executors.newVirtualThreadPerTaskExecutor()) {
            right.set(peer); List<CompletableFuture<Object>> results = new ArrayList<>();
            for (int index = 0; index < 48; index++) {
                int original = index;
                results.add(CompletableFuture.supplyAsync(() -> left.request("RUN", obj("requestId", "original-model-" + original, "original", original)).join(), callers));
            }
            for (int index = 0; index < results.size(); index++) {
                @SuppressWarnings("unchecked") Map<String, Object> observed = (Map<String, Object>) results.get(index).get(10, TimeUnit.SECONDS);
                assertEquals(index, num(observed, "original", -1)); assertEquals("original-model-" + index, observed.get("requestId"));
            }
            assertEquals(48, dispatched.get()); assertEquals(48, callbacks.get()); assertEquals(0, left.pendingRequests()); assertEquals(0, peer.pendingRequests());
        }
    }

    @Test
    void disconnectPreservesOneDispatchAndFailsTheOriginalPendingCallWithoutEchoingSecrets() throws Exception {
        ChannelPair transport = channels();
        CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1); AtomicInteger dispatched = new AtomicInteger();
        try (WorkerRpc left = new WorkerRpc(transport.left(), (method, arguments) -> null, (name, arguments) -> {}, ignored -> {});
             WorkerRpc right = new WorkerRpc(transport.right(), (method, arguments) -> {
                 dispatched.incrementAndGet(); entered.countDown(); release.await(5, TimeUnit.SECONDS); return arguments;
             }, (name, arguments) -> {}, ignored -> {})) {
            CompletableFuture<Object> result = left.request("RUN", obj("private", "private-pipe-control-secret"));
            assertTrue(entered.await(5, TimeUnit.SECONDS)); right.close();
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> result.get(5, TimeUnit.SECONDS));
            assertFalse(failure.toString().contains("private-pipe-control-secret")); assertNull(failure.getCause().getCause());
            assertEquals(1, dispatched.get()); assertEquals(0, left.pendingRequests()); assertFalse(left.open());
            var unavailable = left.request("RUN", obj("private", "private-pipe-control-secret"));
            assertThrows(java.util.concurrent.ExecutionException.class, () -> unavailable.get(5, TimeUnit.SECONDS)); assertEquals(1, dispatched.get());
        } finally { release.countDown(); }
    }

    @Test
    void validationReplyKeepsItsCategoryWithoutPrivatePayloadOrExceptionCause() throws Exception {
        ChannelPair transport = channels();
        try (WorkerRpc left = new WorkerRpc(transport.left(), (method, arguments) -> null, (name, arguments) -> {}, ignored -> {});
             WorkerRpc right = new WorkerRpc(transport.right(), (method, arguments) -> { throw new IllegalArgumentException("private-validation-secret"); }, (name, arguments) -> {}, ignored -> {})) {
            var failure = assertThrows(java.util.concurrent.ExecutionException.class, () -> left.request("VALIDATE", obj()).get(5, TimeUnit.SECONDS));
            WorkerRpc.RemoteFailure original = assertInstanceOf(WorkerRpc.RemoteFailure.class, failure.getCause());
            assertEquals("VALIDATION", original.category()); assertEquals(400, original.status()); assertFalse(original.toString().contains("private-validation-secret")); assertNull(original.getCause());
            assertTrue(left.open()); assertTrue(right.open());
        }
    }

    @Test
    void duplicateIncomingSequenceStopsTheChannelWithoutRepeatingExecution() throws Exception {
        ChannelPair transport = channels();
        CountDownLatch entered = new CountDownLatch(1), disconnected = new CountDownLatch(1); AtomicInteger calls = new AtomicInteger();
        try (WorkerWire sender = transport.left();
             WorkerRpc receiver = new WorkerRpc(transport.right(), (method, arguments) -> {
                 calls.incrementAndGet(); entered.countDown(); Thread.sleep(5000); return obj();
             }, (name, arguments) -> {}, ignored -> disconnected.countDown())) {
            Map<String, Object> original = obj("type", "REQUEST", "sequence", 1, "method", "RUN", "arguments", obj("private", "private-sequence-secret"));
            sender.write(original); assertTrue(entered.await(5, TimeUnit.SECONDS)); sender.write(original);
            assertTrue(disconnected.await(5, TimeUnit.SECONDS)); assertFalse(receiver.open()); assertEquals(1, calls.get()); assertNull(sender.read());
        }
    }

    private ChannelPair channels() throws Exception {
        Pipe forward = Pipe.open(), reverse = Pipe.open();
        return new ChannelPair(new WorkerWire(new ObjectMapper(), Channels.newInputStream(reverse.source()), Channels.newOutputStream(forward.sink())),
            new WorkerWire(new ObjectMapper(), Channels.newInputStream(forward.source()), Channels.newOutputStream(reverse.sink())));
    }

    private record ChannelPair(WorkerWire left, WorkerWire right) { }
}
