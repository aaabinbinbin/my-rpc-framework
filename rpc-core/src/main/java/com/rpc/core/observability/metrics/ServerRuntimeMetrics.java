package com.rpc.core.observability.metrics;

import com.rpc.core.runtime.server.ServerLifecycle;
import lombok.Value;

import java.util.concurrent.ThreadPoolExecutor;

/**
 * 服务端运行时指标采集器。
 *
 * 所处阶段：provider 运行中，观测端点或测试读取服务端健康状态时。
 * 主要职责：组合 ServerLifecycle 和业务线程池状态，判断服务端是否接收新请求、是否存在背压。
 */
public class ServerRuntimeMetrics {
    /** 服务端生命周期状态，提供是否接收请求和 inflight 数。 */
    private final ServerLifecycle lifecycle;
    /** provider 业务线程池，承载反射调用和业务方法执行。 */
    private final ThreadPoolExecutor bizExecutor;

    /**
     * 创建服务端指标采集器。
     */
    public ServerRuntimeMetrics(ServerLifecycle lifecycle, ThreadPoolExecutor bizExecutor) {
        this.lifecycle = lifecycle;
        this.bizExecutor = bizExecutor;
    }

    /**
     * 采集当前服务端指标快照。
     *
     * 注意事项：只读取线程池和生命周期状态，不阻塞业务线程。
     */
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

    /**
     * 服务端运行时只读快照。
     */
    @Value
    public static class Snapshot {
        /** 当前服务端是否接受新请求。 */
        boolean acceptingRequests;
        /** 当前正在处理中的请求数量。 */
        int inflightRequests;
        /** 业务线程池活跃线程数。 */
        int activeThreads;
        /** 业务线程池当前线程数。 */
        int poolSize;
        /** 业务线程池队列积压数量。 */
        int queueSize;
    }
}

