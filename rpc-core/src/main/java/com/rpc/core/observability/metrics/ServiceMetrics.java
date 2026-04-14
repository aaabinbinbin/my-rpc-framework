package com.rpc.core.observability.metrics;

import lombok.Value;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 单个服务的运行指标聚合器。
 *
 * 这个类不负责上报，也不负责展示，
 * 只负责把一次次调用累加成“总次数、失败次数、平均耗时、最近一次耗时”这些基础指标。
 */
public class ServiceMetrics {
    /** 累计调用总次数，无论成功还是失败都会计数。 */
    private final AtomicLong totalCalls = new AtomicLong();
    /** 累计失败次数，用来计算失败率。 */
    private final AtomicLong failedCalls = new AtomicLong();
    /** 累计总耗时，用于后续计算平均耗时。 */
    private final AtomicLong totalLatencyNanos = new AtomicLong();
    /** 最近一次调用耗时，方便快速观察最新请求表现。 */
    private final AtomicLong lastLatencyNanos = new AtomicLong();

    /** 记录一次成功调用。 */
    public void recordSuccess(long latencyNanos) {
        totalCalls.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
        lastLatencyNanos.set(latencyNanos);
    }

    /** 记录一次失败调用。失败调用同样要计入总次数和耗时。 */
    public void recordFailure(long latencyNanos) {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        totalLatencyNanos.addAndGet(latencyNanos);
        lastLatencyNanos.set(latencyNanos);
    }

    /**
     * 生成当前指标快照。
     *
     * 对外返回只读快照，而不是直接暴露内部计数器，
     * 这样既方便上层读取，也不会破坏内部无锁累加的实现。
     */
    public MetricsSnapshot snapshot() {
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

    /**
     * 只读指标快照。
     * 上层监控逻辑拿到这个对象后，可以安全展示，不会影响原始计数。
     */
    @Value
    public static class MetricsSnapshot {
        long totalCalls;
        long failedCalls;
        long averageLatencyNanos;
        long lastLatencyNanos;
    }
}
