package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少连接负载均衡器。
 *
 * 所处阶段：consumer 已经拿到可用 provider 列表，准备选择当前压力最小的实例。
 * 设计要点：
 * - select 只查看当前计数并选出最小值，不提前增加计数。
 * - recordSelection 只在实例通过熔断 allowRequest 后执行，避免半开探测失败导致计数泄漏。
 * - releaseSelection 在调用结束时释放计数，保证 activeConnections 反映真实在途请求。
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    private static final long COUNTER_TTL_MILLIS = 300_000L;
    private static final int MAX_TRACKED_ADDRESSES = 4_096;

    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();
    private final Map<String, Long> lastSeenAt = new ConcurrentHashMap<>();

    /** 从当前候选列表中选择 active 计数最小的地址。 */
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        Set<String> activeKeys = new HashSet<>(addresses.size());
        for (InetSocketAddress address : addresses) {
            String key = addressToString(address);
            activeKeys.add(key);
            lastSeenAt.put(key, now);
        }
        pruneCounters(activeKeys, now);

        InetSocketAddress selected = null;
        int minConnections = Integer.MAX_VALUE;
        for (InetSocketAddress address : addresses) {
            String key = addressToString(address);
            AtomicInteger count = connectionCounts.computeIfAbsent(key, ignored -> new AtomicInteger(0));
            int connections = count.get();
            if (connections < minConnections) {
                minConnections = connections;
                selected = address;
            }
        }

        log.info("[LeastConnections] selected={}, activeConnections={}", selected, minConnections);
        return selected;
    }

    /** 只有实例真正被选中且允许调用后，才增加 active 计数。 */
    @Override
    public void recordSelection(String serviceName,
                                InetSocketAddress address,
                                CircuitBreakerManager circuitBreakerManager) {
        if (address == null) {
            return;
        }
        String key = addressToString(address);
        int current = connectionCounts.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
        log.debug("[LeastConnections] acquired={}, activeConnections={}", address, current);
    }

    /** 释放某个地址的一次 active 计数，防止异常路径导致计数长期偏高。 */
    public void releaseConnection(InetSocketAddress address) {
        if (address == null) {
            return;
        }
        String key = addressToString(address);
        AtomicInteger count = connectionCounts.get(key);
        if (count != null) {
            int current = count.updateAndGet(value -> Math.max(0, value - 1));
            log.debug("[LeastConnections] released={}, activeConnections={}", address, current);
        }
    }

    /** 调用结束后由上层统一释放选择状态。 */
    @Override
    public void releaseSelection(String serviceName, InetSocketAddress address) {
        releaseConnection(address);
    }

    @Override
    public String getName() {
        return "leastConnections";
    }

    private String addressToString(InetSocketAddress address) {
        return address.getHostString() + ":" + address.getPort();
    }

    /** 清理已经不在服务列表中、且没有在途请求的历史地址计数。 */
    private void pruneCounters(Set<String> activeKeys, long now) {
        for (Map.Entry<String, Long> entry : lastSeenAt.entrySet()) {
            String key = entry.getKey();
            if (!activeKeys.contains(key) && isReleasable(key) && now - entry.getValue() > COUNTER_TTL_MILLIS) {
                removeAddress(key, entry.getValue());
            }
        }

        for (String key : connectionCounts.keySet()) {
            if (!activeKeys.contains(key) && isReleasable(key)) {
                removeAddress(key, null);
            }
        }

        int overflow = lastSeenAt.size() - MAX_TRACKED_ADDRESSES;
        if (overflow <= 0) {
            return;
        }

        lastSeenAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .filter(entry -> isReleasable(entry.getKey()))
                .limit(overflow)
                .forEach(entry -> removeAddress(entry.getKey(), entry.getValue()));
    }

    private boolean isReleasable(String key) {
        AtomicInteger count = connectionCounts.get(key);
        return count == null || count.get() <= 0;
    }

    private void removeAddress(String key, Long timestamp) {
        if (timestamp != null && !lastSeenAt.remove(key, timestamp)) {
            return;
        }
        if (timestamp == null) {
            lastSeenAt.remove(key);
        }
        connectionCounts.remove(key);
    }
}
