package com.rpc.core.transport.netty.client.scheduler;

import io.netty.util.concurrent.DefaultThreadFactory;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端请求超时扫描共享调度器。
 *
 * 所处阶段：RpcNettyClient 启动后，定期扫描 pending 请求并清理超时项。
 * 主要职责：多个客户端实例共享一个定时线程，避免每个 client 都创建独立线程造成资源浪费。
 *
 * 注意事项：通过引用计数控制 executor 生命周期，最后一个客户端释放后才关闭线程池。
 */
public final class ClientSharedScheduler {
    /** JVM 内共享单例。 */
    private static final ClientSharedScheduler INSTANCE = new ClientSharedScheduler();

    /** 当前持有该调度器的客户端数量。 */
    private final AtomicInteger references = new AtomicInteger(0);
    /** 实际执行超时扫描任务的单线程调度器。 */
    private volatile ScheduledExecutorService executor;

    /** 单例类不允许外部实例化。 */
    private ClientSharedScheduler() {
    }

    /**
     * 获取共享调度器并增加引用计数。
     */
    public static ClientSharedScheduler getInstance() {
        INSTANCE.acquire();
        return INSTANCE;
    }

    /**
     * 注册固定频率任务。
     *
     * 边界处理：如果 executor 已关闭，会在调度前重新创建。
     */
    public synchronized ScheduledFuture<?> scheduleAtFixedRate(Runnable command,
                                                               long initialDelay,
                                                               long period,
                                                               TimeUnit unit) {
        ensureExecutor();
        return executor.scheduleAtFixedRate(command, initialDelay, period, unit);
    }

    /**
     * 释放一次引用。
     *
     * 注意事项：release 调用次数必须和 getInstance 匹配；计数归零时关闭线程池并允许后续重新创建。
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
     * 获取当前引用计数。
     *
     * 主要用于测试验证资源释放逻辑。
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
                    new DefaultThreadFactory("request-timeout-scanner")
            );
        }
    }
}
