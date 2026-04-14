package com.rpc.core.transport.netty.client.scheduler;

import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 连接池空闲连接回收共享调度器。
 *
 * 所处阶段：ConnectionPool 启用 idle eviction 后，定期扫描并关闭空闲连接。
 * 主要职责：让多个连接池复用一个轻量级定时线程，避免调度线程随客户端数量线性增长。
 */
public final class ConnectionPoolSharedScheduler {
    /** JVM 内共享单例。 */
    private static final ConnectionPoolSharedScheduler INSTANCE = new ConnectionPoolSharedScheduler();

    /** 当前持有该调度器的连接池数量。 */
    private final AtomicInteger references = new AtomicInteger(0);
    /** 执行连接回收任务的单线程调度器。 */
    private volatile ScheduledExecutorService executor;

    /** 单例类不允许外部实例化。 */
    private ConnectionPoolSharedScheduler() {
    }

    /**
     * 获取共享调度器并增加引用计数。
     */
    public static ConnectionPoolSharedScheduler getInstance() {
        INSTANCE.acquire();
        return INSTANCE;
    }

    /**
     * 注册固定频率的空闲连接回收任务。
     */
    public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                               long initialDelay,
                                                               long period,
                                                               TimeUnit unit) {
        ensureExecutor();
        return executor.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    /**
     * 释放一次引用，引用归零时关闭调度线程。
     */
    public synchronized void release() {
        int remaining = references.decrementAndGet();
        if (remaining > 0) {
            return;
        }
        if (executor != null && !executor.isShutdown()) {
            executor.shutdownNow();
        }
        executor = null;
        references.set(0);
    }

    /**
     * 增加引用计数并确保 executor 可用。
     */
    private synchronized void acquire() {
        ensureExecutor();
        references.incrementAndGet();
    }

    /**
     * 懒创建或重建底层调度器。
     */
    private void ensureExecutor() {
        if (executor == null || executor.isShutdown()) {
            executor = Executors.newSingleThreadScheduledExecutor(
                    new DefaultThreadFactory("connection-pool-evictor")
            );
        }
    }
}
