package com.rpc.core.observability.metrics;

/**
 * 客户端运行时指标单例管理器。
 *
 * 所处阶段：连接池、pending 请求管理、重连处理器等客户端组件记录运行状态时。
 * 主要职责：提供全局共享的 ClientRuntimeMetrics，便于观测端点一次性采集客户端健康数据。
 */
public final class ClientRuntimeMetricsManager {
    /** JVM 内共享单例，避免不同客户端组件写入不同指标对象。 */
    private static final ClientRuntimeMetricsManager INSTANCE = new ClientRuntimeMetricsManager();

    /** 客户端运行时指标容器。 */
    private final ClientRuntimeMetrics metrics = new ClientRuntimeMetrics();

    /** 单例类不允许外部实例化。 */
    private ClientRuntimeMetricsManager() {
    }

    /**
     * 获取指标管理器单例。
     */
    public static ClientRuntimeMetricsManager getInstance() {
        return INSTANCE;
    }

    /**
     * 获取可写指标对象。
     *
     * 注意事项：业务组件只应调用 record 方法，不应在运行中随意 reset。
     */
    public ClientRuntimeMetrics getMetrics() {
        return metrics;
    }

    /**
     * 获取当前指标快照。
     */
    public ClientRuntimeMetrics.Snapshot snapshot() {
        return metrics.snapshot();
    }

    /**
     * 重置指标。
     *
     * 适用场景：测试隔离或独立压测轮次开始前清零。
     */
    public void reset() {
        metrics.reset();
    }
}
