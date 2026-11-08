package dev.agentworkbench;

import java.io.IOException;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import java.util.function.Consumer;
import java.util.function.Function;
import static dev.agentworkbench.Json.*;

/**
 * 私有双向调用分别排序；读取线程只分发，嵌套核心回调不阻塞其他执行或心跳。
 */
final class WorkerRpc implements AutoCloseable {
    private static final int MAX_REQUESTS = 128;
    @FunctionalInterface interface Handler { Object handle(String method, Map<String, Object> arguments) throws Exception; }
    private final WorkerWire wire;
    private final Handler handler;
    private final BiConsumer<String, Map<String, Object>> events;
    private final Consumer<IOException> disconnected;
    private final Function<Throwable, String> safeError;
    private final Object writes = new Object();
    private final Map<Long, CompletableFuture<Object>> pending = new ConcurrentHashMap<>();
    private final ExecutorService handlers = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger incoming = new AtomicInteger();
    private final AtomicBoolean closed = new AtomicBoolean();
    private long sentSequence;
    private long receivedSequence;

    WorkerRpc(WorkerWire wire, Handler handler, BiConsumer<String, Map<String, Object>> events, Consumer<IOException> disconnected) {
        this(wire, handler, events, disconnected, exception -> "Worker 操作失败：" + category(exception));
    }

    WorkerRpc(WorkerWire wire, Handler handler, BiConsumer<String, Map<String, Object>> events, Consumer<IOException> disconnected, Function<Throwable, String> safeError) {
        this.wire = wire; this.handler = handler; this.events = events; this.disconnected = disconnected; this.safeError = safeError;
        Thread.ofPlatform().daemon(true).name("workbench-worker-rpc-" + id()).start(this::read);
    }

    CompletableFuture<Object> request(String method, Map<String, Object> arguments) {
        requireName(method);
        CompletableFuture<Object> result = new CompletableFuture<>();
        try {
            synchronized (writes) {
                if (closed.get()) throw lost();
                if (pending.size() >= MAX_REQUESTS) throw new IOException("Worker 在途请求数量超过边界");
                long sequence = ++sentSequence;
                pending.put(sequence, result);
                wire.write(obj("type", "REQUEST", "sequence", sequence, "method", method, "arguments", arguments));
            }
        } catch (IOException exception) {
            result.completeExceptionally(lost()); fail();
        }
        return result;
    }

    void event(String name, Map<String, Object> arguments) throws IOException {
        requireName(name);
        try {
            synchronized (writes) {
                if (closed.get()) throw lost();
                wire.write(obj("type", "EVENT", "name", name, "arguments", arguments));
            }
        } catch (IOException exception) { fail(); throw lost(); }
    }

    boolean open() { return !closed.get(); }
    int pendingRequests() { return pending.size(); }
    boolean awaitHandlers(long timeout, java.util.concurrent.TimeUnit unit) throws InterruptedException { return handlers.awaitTermination(timeout, unit); }

    void closeOutput() throws IOException { synchronized (writes) { wire.closeOutput(); } }

    private void read() {
        try {
            Map<String, Object> message;
            while ((message = wire.read()) != null && !closed.get()) {
                switch (str(message, "type")) {
                    case "REQUEST" -> accept(message);
                    case "REPLY" -> reply(message);
                    case "EVENT" -> { String name = str(message, "name"); requireName(name); events.accept(name, arguments(message)); }
                    default -> throw new IOException("Worker 消息不属于当前双向协议");
                }
            }
        } catch (Exception ignored) { }
        finally { fail(); }
    }

    private void accept(Map<String, Object> message) throws IOException {
        long sequence = sequence(message);
        if (sequence != receivedSequence + 1) throw new IOException("Worker 请求顺序无效，不能重复执行");
        receivedSequence = sequence;
        String method = str(message, "method"); requireName(method);
        Map<String, Object> arguments = arguments(message);
        if (incoming.incrementAndGet() > MAX_REQUESTS) { incoming.decrementAndGet(); throw new IOException("Worker 接收请求数量超过边界"); }
        try {
            handlers.execute(() -> {
                Map<String, Object> response;
                try { response = obj("type", "REPLY", "sequence", sequence, "value", handler.handle(method, arguments)); }
                catch (Throwable exception) {
                    int status = exception instanceof ApiException api ? api.status() : exception instanceof IllegalArgumentException ? 400 : 500;
                    String text;
                    try { text = safeError.apply(exception); } catch (Throwable ignored) { text = "Worker 操作失败，原始参数不会写入日志"; }
                    if (text == null) text = "Worker 操作失败，原始参数不会写入日志";
                    response = obj("type", "REPLY", "sequence", sequence, "error", obj("category", category(exception), "status", status, "message", text.substring(0, Math.min(text.length(), 2000))));
                }
                try {
                    synchronized (writes) { if (!closed.get()) wire.write(response); }
                } catch (IOException exception) { fail(); }
                finally { incoming.decrementAndGet(); }
            });
        } catch (RuntimeException exception) { incoming.decrementAndGet(); throw new IOException("Worker 调用分发已经停止"); }
    }

    private void reply(Map<String, Object> message) throws IOException {
        long sequence = sequence(message);
        CompletableFuture<Object> result = pending.remove(sequence);
        if (result == null) throw new IOException("Worker 回执不属于原在途请求");
        if (message.containsKey("error")) {
            if (!(message.get("error") instanceof Map<?, ?>)) { result.completeExceptionally(lost()); throw new IOException("Worker 错误回执无效"); }
            Map<String, Object> error = map(message, "error");
            String category = str(error, "category"), text = str(error, "message"); int status = num(error, "status", -1);
            if (!java.util.Set.of("VALIDATION", "CANCELLED", "API", "UNKNOWN").contains(category) || status < 400 || status > 599 || text.length() > 2000) {
                result.completeExceptionally(lost()); throw new IOException("Worker 错误回执越过协议边界");
            }
            result.completeExceptionally(new RemoteFailure(category, status, text));
        } else if (message.containsKey("value")) result.complete(message.get("value"));
        else { result.completeExceptionally(lost()); throw new IOException("Worker 回执没有原结果"); }
    }

    private static long sequence(Map<String, Object> message) throws IOException {
        Object value = message.get("sequence");
        if (!(value instanceof Integer || value instanceof Long) || ((Number) value).longValue() < 1) throw new IOException("Worker 请求编号无效");
        return ((Number) value).longValue();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> arguments(Map<String, Object> message) throws IOException {
        if (!(message.get("arguments") instanceof Map<?, ?>)) throw new IOException("Worker 调用参数不是对象");
        return (Map<String, Object>) message.get("arguments");
    }

    private static void requireName(String name) {
        if (name == null || name.isBlank() || name.length() > 64) throw new IllegalArgumentException("Worker 方法或事件名称无效");
    }

    private static String category(Throwable exception) {
        if (exception instanceof IllegalArgumentException) return "VALIDATION";
        if (exception instanceof CancellationException) return "CANCELLED";
        if (exception instanceof ApiException) return "API";
        return "UNKNOWN";
    }

    private static IOException lost() { return new IOException("Worker 通道已中断，原执行结果必须核对，不自动重试"); }

    private void fail() {
        java.util.List<CompletableFuture<Object>> aborted;
        synchronized (writes) {
            if (!closed.compareAndSet(false, true)) return;
            aborted = java.util.List.copyOf(pending.values()); pending.clear();
        }
        try { disconnected.accept(lost()); } catch (RuntimeException ignored) { }
        for (CompletableFuture<Object> future : aborted) future.completeExceptionally(lost());
        handlers.shutdownNow();
        try { wire.close(); } catch (IOException ignored) { }
    }

    @Override public void close() { fail(); }

    /**
     * 原响应只携带分类及边界内原因，不附带私有帧或异常因果链。
     */
    static final class RemoteFailure extends RuntimeException {
        private final String category;
        private final int status;
        RemoteFailure(String category, int status, String message) { super(message); this.category = category; this.status = status; }
        String category() { return category; }
        int status() { return status; }
    }
}
