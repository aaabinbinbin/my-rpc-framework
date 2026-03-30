package com.rpc.core.discovery;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
public class ServiceDirectory implements AutoCloseable {
    private final ServiceDiscovery serviceDiscovery;
    private final ServiceDiscoveryCache cache = new ServiceDiscoveryCache();
    // 一个 serviceName（服务名）对应一个 listener（监听器），避免重复订阅同一服务。
    private final Map<String, ServiceChangeListener> listeners = new ConcurrentHashMap<>();
    private final long cacheTtlMillis;
    private final boolean allowStaleOnFailure;

    public ServiceDirectory(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, 30000L, true);
    }

    public ServiceDirectory(ServiceDiscovery serviceDiscovery, long cacheTtlMillis, boolean allowStaleOnFailure) {
        this.serviceDiscovery = serviceDiscovery;
        this.cacheTtlMillis = cacheTtlMillis;
        this.allowStaleOnFailure = allowStaleOnFailure;
    }

    public ServiceInstancesSnapshot getSnapshot(String serviceName) {
        ServiceDiscoveryCache.CacheEntry entry = cache.getEntry(serviceName);
        if (entry != null && !isExpired(entry)) {
            return entry.getSnapshot();
        }

        if (listeners.containsKey(serviceName)) {
            return refresh(serviceName);
        }

        ServiceChangeListener listener = nextSnapshot -> {
            cache.put(serviceName, nextSnapshot);
            log.info("Service directory updated: serviceName={}, instances={}",
                    serviceName, nextSnapshot.getAddresses());
        };
        ServiceChangeListener existing = listeners.putIfAbsent(serviceName, listener);
        if (existing != null) {
            return refresh(serviceName);
        }

        try {
            // 第一次读取某个服务时走 subscribe（订阅），后续由 watcher（观察器）推送更新本地缓存。
            ServiceInstancesSnapshot initialSnapshot = serviceDiscovery.subscribe(serviceName, listener);
            return cache.put(serviceName, initialSnapshot);
        } catch (RuntimeException e) {
            listeners.remove(serviceName, listener);
            return discoverWithFallback(serviceName, entry);
        }
    }

    public ServiceInstancesSnapshot refresh(String serviceName) {
        return discoverWithFallback(serviceName, cache.getEntry(serviceName));
    }

    public void preheat(List<String> serviceNames) {
        if (serviceNames == null || serviceNames.isEmpty()) {
            return;
        }

        for (String serviceName : serviceNames) {
            if (serviceName == null || serviceName.isBlank()) {
                continue;
            }
            getSnapshot(serviceName.trim());
        }
    }

    @Override
    public void close() {
        for (Map.Entry<String, ServiceChangeListener> entry : listeners.entrySet()) {
            serviceDiscovery.unsubscribe(entry.getKey(), entry.getValue());
        }
        listeners.clear();
        cache.clear();
    }

    private ServiceInstancesSnapshot discoverWithFallback(String serviceName, ServiceDiscoveryCache.CacheEntry entry) {
        try {
            ServiceInstancesSnapshot snapshot = serviceDiscovery.discover(serviceName);
            return cache.put(serviceName, snapshot);
        } catch (RuntimeException e) {
            // 注册中心短暂不可用时，允许回退到本地旧快照，避免 consumer（消费端）直接全量失败。
            if (allowStaleOnFailure && entry != null) {
                log.warn("Service discovery failed, fallback to cached snapshot: serviceName={}", serviceName, e);
                return entry.getSnapshot();
            }
            throw e;
        }
    }

    private boolean isExpired(ServiceDiscoveryCache.CacheEntry entry) {
        if (cacheTtlMillis <= 0) {
            return false;
        }
        return System.currentTimeMillis() - entry.getUpdatedAtMillis() > cacheTtlMillis;
    }
}
