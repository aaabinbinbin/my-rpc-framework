package com.rpc.core.observability.metrics;

import lombok.Value;

import java.util.concurrent.atomic.AtomicLong;

public class ServiceMetrics {
    private final AtomicLong totalCalls = new AtomicLong();
    private final AtomicLong failedCalls = new AtomicLong();
    private final AtomicLong totalLatencyNanos = new AtomicLong();
    private final AtomicLong lastLatencyNanos = new AtomicLong();

    public void recordSuccess(long latencyNanos) {
        totalCalls.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
        lastLatencyNanos.set(latencyNanos);
    }

    public void recordFailure(long latencyNanos) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
        lastLatencyNanos.set(latencyNanos);
    }

    public MetricsSnapshot snapshot() {
    // 对外暴露快照对象，既能让读取接口更友好，
    // 又能保持底层计数器继续用无锁、只增不减的实现。
        long calls = totalCalls.get();
        long failures = failedCalls.get();
        long totalLatency = totalLatencyNanos.get();
        return new MetricsSnapshot(
                calls,
                failures,
                calls == 0 ? 0 : totalLatency / calls,
                lastLatencyNanos.get()
        );
    }

    @Value
    public static class MetricsSnapshot {
        long totalCalls;
        long failedCalls;
        long averageLatencyNanos;
        long lastLatencyNanos;
    }
}

