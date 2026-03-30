package com.rpc.core.runtime.server;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

public final class BizThreadPool {
    private BizThreadPool() {
    }

    public static ExecutorService create(int coreThreads, int maxThreads, int queueCapacity) {
    // 服务端业务执行刻意与 IO 线程隔离；
    // AbortPolicy 可以在过载时立即暴露问题，而不是悄悄无限排队。
        return new ThreadPoolExecutor(
                coreThreads,
                maxThreads,
                60L,
                TimeUnit.SECONDS,
                new LinkedBlockingQueue<>(queueCapacity),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }
}

