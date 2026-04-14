package com.rpc.core.observability.metrics;

import lombok.Value;

import java.util.concurrent.atomic.AtomicLong;

/**
 * 客户端运行时指标容器。
 *
 * 所处阶段：consumer 侧连接池、pending 请求表、重连处理器处理异常或资源保护时。
 * 主要职责：记录与稳定性直接相关的计数器，便于压测和线上调参时判断瓶颈位置。
 *
 * 注意事项：使用 AtomicLong 支持高并发无锁累加，snapshot 方法返回某一时刻的弱一致读数。
 */
public class ClientRuntimeMetrics {
    /** 单连接 inflight 上限导致的拒绝次数。 */
    private final AtomicLong inflightLimitRejections = new AtomicLong();
    /** pending 请求总量上限导致的拒绝次数。 */
    private final AtomicLong pendingLimitRejections = new AtomicLong();
    /** 客户端总连接数上限导致的拒绝次数。 */
    private final AtomicLong totalConnectionLimitRejections = new AtomicLong();
    /** 超时扫描任务清理 pending 请求的累计数量。 */
    private final AtomicLong requestTimeoutClearCount = new AtomicLong();
    /** 重连任务被调度的累计次数。 */
    private final AtomicLong reconnectScheduledCount = new AtomicLong();
    /** 重连成功累计次数。 */
    private final AtomicLong reconnectSucceededCount = new AtomicLong();
    /** 重连失败累计次数。 */
    private final AtomicLong reconnectFailedCount = new AtomicLong();

    /** 记录单连接 inflight 限制触发。 */
    public void recordInflightLimitRejection() {
        inflightLimitRejections.incrementAndGet();
    }

    /** 记录 pending 请求上限触发。 */
    public void recordPendingLimitRejection() {
        pendingLimitRejections.incrementAndGet();
    }

    /** 记录总连接数上限触发。 */
    public void recordTotalConnectionLimitRejection() {
        totalConnectionLimitRejections.incrementAndGet();
    }

    /**
     * 记录被超时扫描清理的请求数。
     *
     * 边界处理：count <= 0 时忽略，避免错误调用导致指标污染。
     */
    public void recordTimeoutCleared(long count) {
        if (count > 0) {
            requestTimeoutClearCount.addAndGet(count);
        }
    }

    /** 记录重连任务被调度。 */
    public void recordReconnectScheduled() {
        reconnectScheduledCount.incrementAndGet();
    }

    /** 记录重连成功。 */
    public void recordReconnectSucceeded() {
        reconnectSucceededCount.incrementAndGet();
    }

    /** 记录重连失败。 */
    public void recordReconnectFailed() {
        reconnectFailedCount.incrementAndGet();
    }

    /**
     * 生成当前指标快照。
     */
    public Snapshot snapshot() {
        return new Snapshot(
                inflightLimitRejections.get(),
                pendingLimitRejections.get(),
                totalConnectionLimitRejections.get(),
                requestTimeoutClearCount.get(),
                reconnectScheduledCount.get(),
                reconnectSucceededCount.get(),
                reconnectFailedCount.get()
        );
    }

    /**
     * 重置全部计数器。
     *
     * 适用场景：测试隔离或压测新轮次开始前。
     */
    public void reset() {
        inflightLimitRejections.set(0);
        pendingLimitRejections.set(0);
        totalConnectionLimitRejections.set(0);
        requestTimeoutClearCount.set(0);
        reconnectScheduledCount.set(0);
        reconnectSucceededCount.set(0);
        reconnectFailedCount.set(0);
    }

    /**
     * 客户端运行时指标只读快照。
     */
    @Value
    public static class Snapshot {
        /** 单连接 inflight 上限导致的拒绝次数。 */
        long inflightLimitRejections;
        /** pending 请求总量上限导致的拒绝次数。 */
        long pendingLimitRejections;
        /** 客户端总连接数上限导致的拒绝次数。 */
        long totalConnectionLimitRejections;
        /** 超时扫描任务清理 pending 请求的累计数量。 */
        long requestTimeoutClearCount;
        /** 重连任务被调度的累计次数。 */
        long reconnectScheduledCount;
        /** 重连成功累计次数。 */
        long reconnectSucceededCount;
        /** 重连失败累计次数。 */
        long reconnectFailedCount;
    }
}
