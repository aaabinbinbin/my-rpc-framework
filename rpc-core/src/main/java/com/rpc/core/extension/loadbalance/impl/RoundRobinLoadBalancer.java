package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡器。
 * 它为每个服务维护一个计数器，让连续请求按稳定顺序在候选提供者之间轮转。
 */
@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer {
    private static final long COUNTER_TTL_MILLIS = 300_000L;
    private static final int MAX_TRACKED_SERVICES = 4_096;

    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> lastUsedAt = new ConcurrentHashMap<>();

    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty() || serviceName == null || serviceName.isEmpty()) {
            return null;
        }
        long now = System.currentTimeMillis();
        lastUsedAt.put(serviceName, now);
        pruneCounters(now);
        AtomicInteger counter = counters.computeIfAbsent(serviceName, ignored -> new AtomicInteger(0));
        int index = Math.floorMod(counter.getAndIncrement(), addresses.size());
        InetSocketAddress selected = addresses.get(index);
        log.info("[RoundRobin] selected={}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "roundRobin";
    }

    private void pruneCounters(long now) {
        for (Map.Entry<String, Long> entry : lastUsedAt.entrySet()) {
            if (now - entry.getValue() > COUNTER_TTL_MILLIS) {
                removeService(entry.getKey(), entry.getValue());
            }
        }

        int overflow = lastUsedAt.size() - MAX_TRACKED_SERVICES;
        if (overflow <= 0) {
            return;
        }

        lastUsedAt.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.naturalOrder()))
                .limit(overflow)
                .forEach(entry -> removeService(entry.getKey(), entry.getValue()));
    }

    private void removeService(String serviceName, Long timestamp) {
        if (timestamp != null && !lastUsedAt.remove(serviceName, timestamp)) {
            return;
        }
        if (timestamp == null) {
            lastUsedAt.remove(serviceName);
        }
        counters.remove(serviceName);
    }
}
