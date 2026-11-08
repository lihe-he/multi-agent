package dev.agentworkbench;

import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;

/**
 * 全量资源申请采用原子授予，等待时不持有临时预留，避免反向申请互锁。
 */
@Component
public class ResourceManager {
    private final Map<String, Integer> used = new HashMap<>();
    private final Map<String, Integer> capacities = new HashMap<>(Map.of("model", 2, "tool", 2, "process", 4));
    private final ReentrantLock reservations = new ReentrantLock();
    private final Condition changed = reservations.newCondition();

    public Lease acquire(Map<String, Integer> requested, BooleanSupplier cancelled) {
        Map<String, Integer> ordered = new TreeMap<>(requested);
        reservations.lock();
        try {
            for (var entry : ordered.entrySet()) {
                int capacity = capacities.getOrDefault(entry.getKey(), 1);
                if (entry.getValue() < 1 || entry.getValue() > capacity) {
                    throw new ApiException(409, "资源需求无法满足：" + entry.getKey());
                }
            }
            while (true) {
                if (cancelled.getAsBoolean()) { throw new CancellationException("根任务已停止"); }
                boolean available = ordered.entrySet().stream().allMatch(entry -> used.getOrDefault(entry.getKey(), 0) + entry.getValue() <= capacities.getOrDefault(entry.getKey(), 1));
                if (available) {
                    ordered.forEach((key, count) -> used.merge(key, count, Integer::sum));
                    return new Lease(ordered);
                }
                try { changed.await(100, TimeUnit.MILLISECONDS); } catch (InterruptedException exception) { Thread.currentThread().interrupt(); throw new CancellationException(); }
            }
        } finally { reservations.unlock(); }
    }

    public int activeModels() { reservations.lock(); try { return used.getOrDefault("model", 0); } finally { reservations.unlock(); } }

    public Map<String, Integer> snapshot() { reservations.lock(); try { return Map.copyOf(used); } finally { reservations.unlock(); } }

    public final class Lease implements AutoCloseable {
        private final Map<String, Integer> resources;
        private boolean closed;
        private Lease(Map<String, Integer> resources) { this.resources = resources; }

        @Override
        public void close() {
            reservations.lock();
            try {
                if (closed) { return; }
                resources.forEach((key, count) -> used.compute(key, (ignored, current) -> Math.max(0, (current == null ? 0 : current) - count)));
                closed = true; changed.signalAll();
            } finally { reservations.unlock(); }
        }
    }
}
