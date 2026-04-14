package com.rpc.core.observability.metrics;

import lombok.Value;

import java.util.Map;

/**
 * RPC 框架观测快照。
 *
 * 所处阶段：Spring Boot 观测端点或测试需要一次性读取框架运行状态时。
 * 主要职责：聚合客户端运行时指标和服务维度指标，形成对外只读视图。
 */
@Value
public class ObservabilitySnapshot {
    /** 客户端运行时指标快照，例如限流拒绝、pending 超时清理、重连次数。 */
    ClientRuntimeMetrics.Snapshot clientRuntime;
    /** provider/consumer 服务维度指标，key 通常是 service 或 service#method。 */
    Map<String, ServiceMetrics.MetricsSnapshot> serviceMetrics;

    /**
     * 从各指标管理器采集当前快照。
     *
     * 注意事项：该方法只读取内存指标，不触发网络调用或阻塞操作。
     */
    public static ObservabilitySnapshot capture() {
        return new ObservabilitySnapshot(
                ClientRuntimeMetricsManager.getInstance().snapshot(),
                ServiceMetricsManager.getInstance().snapshotAll()
        );
    }
}
