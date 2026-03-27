package com.rpc.metrics;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public final class ServiceMetricsManager {
    private static final ServiceMetricsManager INSTANCE = new ServiceMetricsManager();
    private final Map<String, ServiceMetrics> metricsMap = new ConcurrentHashMap<>();

    private ServiceMetricsManager() {
    }

    public static ServiceMetricsManager getInstance() {
        return INSTANCE;
    }

    public void register(String serviceName) {
        metricsMap.computeIfAbsent(serviceName, key -> new ServiceMetrics());
    }

    public void remove(String serviceName) {
        metricsMap.remove(serviceName);
    }

    public ServiceMetrics get(String serviceName) {
        return metricsMap.get(serviceName);
    }
}
