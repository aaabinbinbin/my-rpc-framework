package com.rpc.core.discovery;

import lombok.extern.slf4j.Slf4j;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 服务目录。
 *
 * 这个类位于 consumer 侧“服务发现”和“地址选择”之间，
 * 负责对注册中心里的服务实例列表做本地缓存、订阅更新、预热和失败回退。
 *
 * 可以把它理解成：
 * 1. 上游对接 ServiceDiscovery。
 * 2. 下游给 RpcServiceResolver 提供稳定、可缓存的服务实例快照。
 */
@Slf4j
public class ServiceDirectory implements AutoCloseable {
    /** 原始服务发现接口，真正和注册中心交互的是它。 */
    private final ServiceDiscovery serviceDiscovery;
    /** 本地缓存，减少每次调用都直接打注册中心。 */
    private final ServiceDiscoveryCache cache = new ServiceDiscoveryCache();
    /** 一个服务只维护一个监听器，避免重复订阅同一个 serviceName。 */
    private final Map<String, ServiceChangeListener> listeners = new ConcurrentHashMap<>();
    /** 缓存过期时间。 */
    private final long cacheTtlMillis;
    /** 注册中心失败时是否允许回退到旧快照。 */
    private final boolean allowStaleOnFailure;

    public ServiceDirectory(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, 30000L, true);
    }

    public ServiceDirectory(ServiceDiscovery serviceDiscovery, long cacheTtlMillis, boolean allowStaleOnFailure) {
        this.serviceDiscovery = serviceDiscovery;
        this.cacheTtlMillis = cacheTtlMillis;
        this.allowStaleOnFailure = allowStaleOnFailure;
    }

    /**
     * 获取某个服务的实例快照。
     *
     * 处理顺序：
     * 1. 优先读未过期缓存。
     * 2. 如果已经订阅过，就直接刷新。
     * 3. 如果是第一次访问，就先建立订阅，再拿首次快照。
     */
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
            ServiceInstancesSnapshot initialSnapshot = serviceDiscovery.subscribe(serviceName, listener);
            return cache.put(serviceName, initialSnapshot);
        } catch (RuntimeException e) {
            listeners.remove(serviceName, listener);
            return discoverWithFallback(serviceName, entry);
        }
    }

    /**
     * 主动刷新某个服务的实例列表。
     *
     * 通常用于缓存失效后重新发现服务，
     * 同时在注册中心异常时支持按配置回退到旧快照。
     */
    public ServiceInstancesSnapshot refresh(String serviceName) {
        return discoverWithFallback(serviceName, cache.getEntry(serviceName));
    }

    /** 启动阶段预热一组服务，降低首次调用时的注册中心访问延迟。 */
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

    /** 取消所有订阅并清理本地缓存。 */
    @Override
    public void close() {
        for (Map.Entry<String, ServiceChangeListener> entry : listeners.entrySet()) {
            serviceDiscovery.unsubscribe(entry.getKey(), entry.getValue());
        }
        listeners.clear();
        cache.clear();
    }

    /**
     * 从注册中心拉取最新快照，失败时按配置决定是否回退到旧缓存。
     *
     * 这个设计是为了避免注册中心短暂抖动时，consumer 端请求瞬间全量失败。
     */
    private ServiceInstancesSnapshot discoverWithFallback(String serviceName, ServiceDiscoveryCache.CacheEntry entry) {
        try {
            ServiceInstancesSnapshot snapshot = serviceDiscovery.discover(serviceName);
            return cache.put(serviceName, snapshot);
        } catch (RuntimeException e) {
            if (allowStaleOnFailure && entry != null) {
                log.warn("Service discovery failed, fallback to cached snapshot: serviceName={}", serviceName, e);
                return entry.getSnapshot();
            }
            throw e;
        }
    }

    /** 判断缓存项是否过期。 */
    private boolean isExpired(ServiceDiscoveryCache.CacheEntry entry) {
        if (cacheTtlMillis <= 0) {
            return false;
        }
        return System.currentTimeMillis() - entry.getUpdatedAtMillis() > cacheTtlMillis;
    }
}
