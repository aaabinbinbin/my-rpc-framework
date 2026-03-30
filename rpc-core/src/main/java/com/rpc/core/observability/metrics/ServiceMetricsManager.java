package com.rpc.core.observability.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

public final class ServiceMetricsManager {
    private static final ServiceMetricsManager INSTANCE = new ServiceMetricsManager();
    private final Map<String, ServiceMetrics> metricsMap = new ConcurrentHashMap<>();

    private ServiceMetricsManager() {
    }

    public static ServiceMetricsManager getInstance() {
        return INSTANCE;
    }

    public void register(String serviceName) {
    // 指标按服务懒创建，
    // 这样服务注册和过滤器记录都能共享同一份存储，而不用预先声明所有服务。
        metricsMap.computeIfAbsent(serviceName, key -> new ServiceMetrics());
    }

    public void remove(String serviceName) {
        metricsMap.remove(serviceName);
    }

    public ServiceMetrics get(String serviceName) {
        return metricsMap.get(serviceName);
    }

    public Map<String, ServiceMetrics.MetricsSnapshot> snapshotAll() {
    // 对外只暴露不可变快照，不直接暴露实时计数器，
    // 避免监控调用方意外依赖可变的内部状态。
        return metricsMap.entrySet().stream()
                .collect(Collectors.toMap(Map.Entry::getKey, entry -> entry.getValue().snapshot()));
    }
}

