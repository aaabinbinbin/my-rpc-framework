package com.rpc.spring.boot;

import com.rpc.core.observability.metrics.ClientRuntimeMetrics;
import com.rpc.core.observability.metrics.ServiceMetrics;

import java.util.Map;

/**
 * RPC 可观测 HTTP 响应对象。
 *
 * 所处阶段：RpcObservabilityEndpoint 返回结果时由 Spring MVC 序列化为 JSON。
 * 主要职责：承载客户端运行时指标、服务指标总量、实际返回数量、是否截断以及服务指标明细。
 *
 * 注意事项：字段全部只读，避免 Controller 返回后被业务代码再次修改。
 */
public class RpcObservabilityResponse {
    /** 客户端运行时指标快照，例如连接数、在途请求等。 */
    private final ClientRuntimeMetrics.Snapshot clientRuntime;
    /** 当前内存中统计到的服务指标总数量。 */
    private final int totalServices;
    /** 本次响应实际返回的服务指标数量。 */
    private final int returnedServices;
    /** 服务指标是否因为 limit 上限被截断。 */
    private final boolean serviceMetricsTruncated;
    /** 服务维度指标明细，key 为服务标识。 */
    private final Map<String, ServiceMetrics.MetricsSnapshot> serviceMetrics;

    /**
     * 创建可观测响应对象。
     *
     * @param clientRuntime 客户端运行时指标快照
     * @param totalServices 服务指标总数量
     * @param returnedServices 本次实际返回的服务指标数量
     * @param serviceMetricsTruncated 服务指标是否被截断
     * @param serviceMetrics 服务维度指标明细
     */
    public RpcObservabilityResponse(
            ClientRuntimeMetrics.Snapshot clientRuntime,
            int totalServices,
            int returnedServices,
            boolean serviceMetricsTruncated,
            Map<String, ServiceMetrics.MetricsSnapshot> serviceMetrics
    ) {
        this.clientRuntime = clientRuntime;
        this.totalServices = totalServices;
        this.returnedServices = returnedServices;
        this.serviceMetricsTruncated = serviceMetricsTruncated;
        this.serviceMetrics = serviceMetrics;
    }

    /** @return 客户端运行时指标快照 */
    public ClientRuntimeMetrics.Snapshot getClientRuntime() {
        return clientRuntime;
    }

    /** @return 服务指标总数量 */
    public int getTotalServices() {
        return totalServices;
    }

    /** @return 本次实际返回的服务指标数量 */
    public int getReturnedServices() {
        return returnedServices;
    }

    /** @return 服务指标是否因为 limit 上限被截断 */
    public boolean isServiceMetricsTruncated() {
        return serviceMetricsTruncated;
    }

    /** @return 服务维度指标明细 */
    public Map<String, ServiceMetrics.MetricsSnapshot> getServiceMetrics() {
        return serviceMetrics;
    }
}
