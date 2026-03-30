package com.rpc.core.observability.metrics;

import com.rpc.core.runtime.server.ServerLifecycle;
import lombok.Value;

import java.util.concurrent.ThreadPoolExecutor;

public class ServerRuntimeMetrics {
    private final ServerLifecycle lifecycle;
    private final ThreadPoolExecutor bizExecutor;

    public ServerRuntimeMetrics(ServerLifecycle lifecycle, ThreadPoolExecutor bizExecutor) {
        this.lifecycle = lifecycle;
        this.bizExecutor = bizExecutor;
    }

    public Snapshot snapshot() {
    // 运行时指标同时组合生命周期状态和业务线程池状态，
    // 这两部分已经足够判断服务提供端是否健康、
    // 以及当前是否正在承受背压。
        return new Snapshot(
                lifecycle.isAcceptingRequests(),
                lifecycle.getInflightRequests(),
                bizExecutor.getActiveCount(),
                bizExecutor.getPoolSize(),
                bizExecutor.getQueue().size()
        );
    }

    @Value
    public static class Snapshot {
        boolean acceptingRequests;
        int inflightRequests;
        int activeThreads;
        int poolSize;
        int queueSize;
    }
}

