package com.rpc.core.discovery;

import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

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
    private static final long DEFAULT_REMEMBERED_ADDRESS_TTL_MILLIS = 300_000L;
    private static final int MAX_REMEMBERED_ADDRESSES = 4_096;

    /** 原始服务发现接口，真正和注册中心交互的是它。 */
    private final ServiceDiscovery serviceDiscovery;
    /** 本地缓存，减少每次调用都直接打注册中心。 */
    private final ServiceDiscoveryCache cache = new ServiceDiscoveryCache();
    /** 一个服务只维护一个监听器，避免重复订阅同一个 serviceName。 */
    private final Map<String, ServiceChangeListener> listeners = new ConcurrentHashMap<>();
    private final Map<String, CompletableFuture<ServiceInstancesSnapshot>> refreshFutures = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Set<String>> addressServices = new ConcurrentHashMap<>();
    private final Map<InetSocketAddress, Long> addressLastSeen = new ConcurrentHashMap<>();
    /** 缓存过期时间。 */
    private final long cacheTtlMillis;
    /** 注册中心失败时是否允许回退到旧快照。 */
    private final boolean allowStaleOnFailure;
    private final long rememberedAddressTtlMillis;
    private final int maxRememberedAddresses;

    public ServiceDirectory(ServiceDiscovery serviceDiscovery) {
        this(serviceDiscovery, 30000L, true);
    }

    public ServiceDirectory(ServiceDiscovery serviceDiscovery, long cacheTtlMillis, boolean allowStaleOnFailure) {
        this(serviceDiscovery,
                cacheTtlMillis,
                allowStaleOnFailure,
                cacheTtlMillis <= 0 ? DEFAULT_REMEMBERED_ADDRESS_TTL_MILLIS : Math.max(cacheTtlMillis * 2, 60_000L),
                MAX_REMEMBERED_ADDRESSES);
    }

    ServiceDirectory(ServiceDiscovery serviceDiscovery,
                     long cacheTtlMillis,
                     boolean allowStaleOnFailure,
                     long rememberedAddressTtlMillis,
                     int maxRememberedAddresses) {
        this.serviceDiscovery = serviceDiscovery;
        this.cacheTtlMillis = cacheTtlMillis;
        this.allowStaleOnFailure = allowStaleOnFailure;
        this.rememberedAddressTtlMillis = rememberedAddressTtlMillis;
        this.maxRememberedAddresses = maxRememberedAddresses;
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

        ServiceChangeListener listener = nextSnapshot -> updateSnapshot(serviceName, nextSnapshot, true);
        ServiceChangeListener existing = listeners.putIfAbsent(serviceName, listener);
        if (existing != null) {
            return refresh(serviceName);
        }

        try {
            ServiceInstancesSnapshot initialSnapshot = serviceDiscovery.subscribe(serviceName, listener);
            return updateSnapshot(serviceName, initialSnapshot, false);
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
        CompletableFuture<ServiceInstancesSnapshot> refreshFuture = new CompletableFuture<>();
        CompletableFuture<ServiceInstancesSnapshot> existing = refreshFutures.putIfAbsent(serviceName, refreshFuture);
        if (existing != null) {
            return existing.join();
        }

        try {
            ServiceInstancesSnapshot snapshot = discoverWithFallback(serviceName, cache.getEntry(serviceName));
            refreshFuture.complete(snapshot);
            return snapshot;
        } catch (RuntimeException e) {
            refreshFuture.completeExceptionally(e);
            throw e;
        } finally {
            refreshFutures.remove(serviceName, refreshFuture);
        }
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
    public boolean containsAddress(InetSocketAddress address) {
        if (address == null) {
            return false;
        }
        pruneRememberedAddresses();
        if (containsAddressInCache(address)) {
            return true;
        }
        Set<String> candidateServices = addressServices.get(address);
        Iterable<String> serviceNames = (candidateServices == null || candidateServices.isEmpty())
                ? listeners.keySet()
                : new HashSet<>(candidateServices);
        for (String serviceName : serviceNames) {
            ServiceInstancesSnapshot snapshot = refresh(serviceName);
            if (snapshot.getAddresses().contains(address)) {
                return true;
            }
        }
        return false;
    }

    public void rememberAddressService(InetSocketAddress address, String serviceName) {
        if (address == null || serviceName == null || serviceName.isBlank()) {
            return;
        }
        addressServices.computeIfAbsent(address, ignored -> new CopyOnWriteArraySet<>()).add(serviceName);
        addressLastSeen.put(address, System.currentTimeMillis());
        pruneRememberedAddresses();
    }

    private boolean containsAddressInCache(InetSocketAddress address) {
        for (ServiceDiscoveryCache.CacheEntry entry : cache.entries()) {
            if (entry.getSnapshot().getAddresses().contains(address)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void close() {
        for (Map.Entry<String, ServiceChangeListener> entry : listeners.entrySet()) {
            serviceDiscovery.unsubscribe(entry.getKey(), entry.getValue());
        }
        listeners.clear();
        refreshFutures.clear();
        cache.clear();
        addressServices.clear();
        addressLastSeen.clear();
    }

    /**
     * 从注册中心拉取最新快照，失败时按配置决定是否回退到旧缓存。
     *
     * 这个设计是为了避免注册中心短暂抖动时，consumer 端请求瞬间全量失败。
     */
    private ServiceInstancesSnapshot discoverWithFallback(String serviceName, ServiceDiscoveryCache.CacheEntry entry) {
        try {
            ServiceInstancesSnapshot snapshot = serviceDiscovery.discover(serviceName);
            return updateSnapshot(serviceName, snapshot, false);
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

    private ServiceInstancesSnapshot updateSnapshot(String serviceName,
                                                   ServiceInstancesSnapshot snapshot,
                                                   boolean logOnChange) {
        ServiceInstancesSnapshot previous = cache.get(serviceName);
        for (InetSocketAddress address : snapshot.getAddresses()) {
            rememberAddressService(address, serviceName);
        }
        cache.put(serviceName, snapshot);
        if (logOnChange && !snapshot.equals(previous)) {
            log.info("Service directory updated: serviceName={}, instances={}",
                    serviceName, snapshot.getAddresses());
        }
        return snapshot;
    }

    private void pruneRememberedAddresses() {
        long now = System.currentTimeMillis();
        long ttlMillis = rememberedAddressTtlMillis();

        for (Map.Entry<InetSocketAddress, Long> entry : addressLastSeen.entrySet()) {
            if (now - entry.getValue() > ttlMillis) {
                removeRememberedAddress(entry.getKey(), entry.getValue());
            }
        }

        int overflow = addressLastSeen.size() - maxRememberedAddresses;
        if (overflow <= 0) {
            return;
        }

        addressLastSeen.entrySet().stream()
                .sorted(Map.Entry.comparingByValue())
                .limit(overflow)
                .forEach(entry -> removeRememberedAddress(entry.getKey(), entry.getValue()));
    }

    private long rememberedAddressTtlMillis() {
        return rememberedAddressTtlMillis;
    }

    private void removeRememberedAddress(InetSocketAddress address, Long timestamp) {
        if (timestamp != null && !addressLastSeen.remove(address, timestamp)) {
            return;
        }
        if (timestamp == null) {
            addressLastSeen.remove(address);
        }
        addressServices.remove(address);
    }
}
