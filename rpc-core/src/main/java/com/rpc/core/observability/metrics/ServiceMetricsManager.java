package com.rpc.core.observability.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * 服务指标管理器。
 *
 * 可以把它理解成一个全局的“serviceName -> ServiceMetrics”仓库。
 * consumer/provider 的指标过滤器会把调用结果记到这里，监控导出逻辑再统一读取。
 */
public final class ServiceMetricsManager {
    private static final ServiceMetricsManager INSTANCE = new ServiceMetricsManager();
    /** 按服务名保存指标对象。 */
    private final Map<String, ServiceMetrics> metricsMap = new ConcurrentHashMap<>();

    private ServiceMetricsManager() {
    }

    public static ServiceMetricsManager getInstance() {
        return INSTANCE;
    }

    /** 为一个服务准备指标对象；如果已存在则直接复用。 */
    public void register(String serviceName) {
        // 指标按服务懒创建，避免启动时为所有潜在服务预分配对象。
        metricsMap.computeIfAbsent(serviceName, key -> new ServiceMetrics());
    }

    /** 移除一个服务的指标，常用于服务下线或测试重置。 */
    public void remove(String serviceName) {
        metricsMap.remove(serviceName);
    }

    /** 获取某个服务对应的指标对象。 */
    public ServiceMetrics get(String serviceName) {
        return metricsMap.get(serviceName);
    }

    /** 导出所有服务的只读指标快照。 */
    public Map<String, ServiceMetrics.MetricsSnapshot> snapshotAll() {
        // 对外只暴露快照，避免外部逻辑依赖内部可变状态。
        return metricsMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().snapshot()));
    }
}
