package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡策略
 */
@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer {
    // 每个服务维护一个轮询计数器
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || serviceName.isEmpty()) {
            return null;
        }
        // 创建或者获取计数器
        AtomicInteger counter = counters.computeIfAbsent(serviceName, k -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % addresses.size());
        InetSocketAddress selected = addresses.get(index);
        log.info("[RoundRobin] 选择: {}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "roundRobin";
    }
}
