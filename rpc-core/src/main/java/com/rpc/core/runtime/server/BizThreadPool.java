package com.rpc.core.runtime.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * provider 侧业务线程池工厂。
 *
 * 所处阶段：Netty server 启动时创建，用于承载远程业务方法执行。
 * 设计目的：
 * - 将业务执行从 Netty IO 线程中隔离出来。
 * - 使用有界队列限制堆积，过载时由上层返回 SERVER_BUSY。
 * - 对非法配置做下限保护，避免 0 或负数线程配置导致启动失败。
 */
public final class BizThreadPool {
    private BizThreadPool() {
    }

    /**
     * 创建业务线程池。
     *
     * 注意：这里使用 AbortPolicy，让上层能够捕获拒绝异常并转换成明确的 RPC 失败响应；
     * 不使用无界队列，避免 provider 在高压下无限堆积请求导致 OOM。
     */
    public static ExecutorService create(int coreThreads, int maxThreads, int queueCapacity) {
        int safeCoreThreads = Math.max(1, coreThreads);
        int safeMaxThreads = Math.max(safeCoreThreads, maxThreads);
        int safeQueueCapacity = Math.max(1, queueCapacity);

        return new ThreadPoolExecutor(
                safeCoreThreads,
                safeMaxThreads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(safeQueueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}
