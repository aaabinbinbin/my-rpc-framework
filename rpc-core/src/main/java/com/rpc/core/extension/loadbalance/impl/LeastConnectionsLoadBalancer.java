package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 选择当前记录中活跃连接数最少的地址。
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }

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

        if (selected != null) {
            String key = addressToString(selected);
            int current = connectionCounts.computeIfAbsent(key, ignored -> new AtomicInteger(0)).incrementAndGet();
            log.info("[LeastConnections] selected={}, activeConnections={}", selected, current);
        }
        return selected;
    }

    public void releaseConnection(InetSocketAddress address) {
        if (address == null) {
            return;
        }
        String key = addressToString(address);
        AtomicInteger count = connectionCounts.get(key);
        if (count != null) {
            int current = count.decrementAndGet();
            log.debug("[LeastConnections] released={}, activeConnections={}", address, current);
        }
    }

    @Override
    public String getName() {
        return "leastConnections";
    }

    private String addressToString(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + ":" + address.getPort();
    }
}
