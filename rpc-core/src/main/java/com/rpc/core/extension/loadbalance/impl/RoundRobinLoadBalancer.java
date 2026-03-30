package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 轮询负载均衡器。
 * 它为每个服务维护一个计数器，让连续请求按稳定顺序在候选提供者之间轮转。
 */
@Slf4j
public class RoundRobinLoadBalancer implements LoadBalancer {
    private final ConcurrentHashMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || serviceName.isEmpty()) {
            return null;
        }
        AtomicInteger counter = counters.computeIfAbsent(serviceName, ignored -> new AtomicInteger(0));
        int index = Math.abs(counter.getAndIncrement() % addresses.size());
        InetSocketAddress selected = addresses.get(index);
        log.info("[RoundRobin] selected={}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "roundRobin";
    }
}
