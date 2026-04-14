package com.rpc.spring.boot;

import com.rpc.core.observability.metrics.ClientRuntimeMetrics;
import com.rpc.core.observability.metrics.ObservabilitySnapshot;
import com.rpc.core.observability.metrics.ServiceMetrics;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * RPC 可观测门面。
 *
 * 所处阶段：应用运行期被 HTTP 端点或其他 Spring Bean 调用。
 * 主要职责：屏蔽 core 层指标抓取细节，并在对外返回前执行排序、限量和裁剪。
 *
 * 注意事项：该类不缓存指标，每次调用都会抓取当前内存中的最新快照。
 */
public class RpcObservabilityFacade {
    /** HTTP 展开服务指标时的默认返回数量，防止误返回过大 Map。 */
    private static final int DEFAULT_SERVICE_LIMIT = 50;
    /** HTTP 展开服务指标时的最大返回数量，用于保护接口和调用方。 */
    private static final int MAX_SERVICE_LIMIT = 200;

    /**
     * 抓取当前 RPC 可观测快照。
     *
     * @return 包含客户端运行时指标和服务维度指标的快照
     */
    public ObservabilitySnapshot snapshot() {
        return ObservabilitySnapshot.capture();
    }

    /**
     * 读取客户端运行时指标。
     *
     * @return 客户端连接、请求等运行状态快照
     */
    public ClientRuntimeMetrics.Snapshot clientRuntime() {
        return snapshot().getClientRuntime();
    }

    /**
     * 读取服务维度指标。
     *
     * @return key 为服务标识，value 为该服务指标快照的 Map
     */
    public Map<String, ServiceMetrics.MetricsSnapshot> serviceMetrics() {
        return snapshot().getServiceMetrics();
    }

    /**
     * 构造 HTTP 可观测响应。
     *
     * 边界处理：默认不返回服务明细；当 includeServices=true 时，会按服务名排序并限制返回数量。
     *
     * @param includeServices 是否包含服务维度指标明细
     * @param limit 服务明细最大返回数量，为空或小于等于 0 时使用默认值
     * @return HTTP 响应 DTO
     */
    public RpcObservabilityResponse httpSnapshot(boolean includeServices, Integer limit) {
        ObservabilitySnapshot snapshot = snapshot();
        Map<String, ServiceMetrics.MetricsSnapshot> allServiceMetrics = snapshot.getServiceMetrics();
        if (!includeServices) {
            return new RpcObservabilityResponse(
                    snapshot.getClientRuntime(),
                    allServiceMetrics.size(),
                    0,
                    false,
                    Map.of()
            );
        }

        int boundedLimit = normalizeServiceLimit(limit);
        Map<String, ServiceMetrics.MetricsSnapshot> selectedServiceMetrics = allServiceMetrics.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .limit(boundedLimit)
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        Map.Entry::getValue,
                        (left, right) -> left,
                        LinkedHashMap::new
                ));

        return new RpcObservabilityResponse(
                snapshot.getClientRuntime(),
                allServiceMetrics.size(),
                selectedServiceMetrics.size(),
                allServiceMetrics.size() > selectedServiceMetrics.size(),
                selectedServiceMetrics
        );
    }

    /**
     * 归一化服务明细返回数量。
     *
     * 边界处理：空值和非正数回退默认值，过大值截断到最大上限。
     *
     * @param limit 用户传入的 limit 参数
     * @return 安全的服务明细返回数量
     */
    private int normalizeServiceLimit(Integer limit) {
        if (limit == null) {
            return DEFAULT_SERVICE_LIMIT;
        }
        if (limit <= 0) {
            return DEFAULT_SERVICE_LIMIT;
        }
        return Math.min(limit, MAX_SERVICE_LIMIT);
    }
}
