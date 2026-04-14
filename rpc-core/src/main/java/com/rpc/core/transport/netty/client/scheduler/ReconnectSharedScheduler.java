package com.rpc.core.transport.netty.client.scheduler;

import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端重连共享调度器。
 *
 * 所处阶段：Netty channelInactive 后，ReconnectHandler 需要延迟发起重连时。
 * 主要职责：集中承载重连延迟任务，避免每条连接或每个 handler 创建独立线程。
 */
public final class ReconnectSharedScheduler {
    /** JVM 内共享单例。 */
    private static final ReconnectSharedScheduler INSTANCE = new ReconnectSharedScheduler();

    /** 当前持有该调度器的重连处理器数量。 */
    private final AtomicInteger references = new AtomicInteger(0);
    /** 执行重连延迟任务的单线程调度器。 */
    private volatile ScheduledExecutorService executor;

    /** 单例类不允许外部实例化。 */
    private ReconnectSharedScheduler() {
    }

    /**
     * 获取共享调度器并增加引用计数。
     */
    public static ReconnectSharedScheduler getInstance() {
        INSTANCE.acquire();
        return INSTANCE;
    }

    /**
     * 调度一次延迟重连任务。
     */
    public synchronized ScheduledFuture<?> schedule(Runnable command, long delay, TimeUnit unit) {
        ensureExecutor();
        return executor.schedule(command, delay, unit);
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
     * 获取当前引用计数，主要用于测试。
     */
    public int referenceCount() {
        return references.get();
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
                    new DefaultThreadFactory("reconnect-scheduler")
            );
        }
    }
}
