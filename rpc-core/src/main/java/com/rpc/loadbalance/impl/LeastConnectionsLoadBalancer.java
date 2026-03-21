package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 最少连接数负载均衡策略
 */
@Slf4j
public class LeastConnectionsLoadBalancer implements LoadBalancer {
    // 记录每个地址的连接数
    private final Map<String, AtomicInteger> connectionCounts = new ConcurrentHashMap<>();

    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        // 找到连接数最少的地址
        InetSocketAddress selected = null;
        int minConnections = Integer.MAX_VALUE;
        for (InetSocketAddress address : addresses) {
            String addrKey = addressToString(address);
            AtomicInteger count = connectionCounts.computeIfAbsent(addrKey, k -> new AtomicInteger(0));
            int connections = count.get();

            if (connections < minConnections) {
                minConnections = connections;
                selected = address;
            }
        }
        // 增加选中地址的连接数
        if (selected != null) {
            String addrKey = addressToString(selected);
            connectionCounts.computeIfAbsent(addrKey, k -> new AtomicInteger(0)).incrementAndGet();
            log.info("[LeastConnections] 选择：{} (当前连接数：{})", selected, connectionCounts.get(addrKey).get());
        }
        return selected;
    }

    /**
     * 释放连接（RPC 调用完成后调用）
     */
    public void releaseConnection(InetSocketAddress address) {
        if (address != null) {
            String addrKey = addressToString(address);
            AtomicInteger count = connectionCounts.get(addrKey);
            if (count != null) {
                count.decrementAndGet();
                log.debug("[LeastConnections] 释放连接：{} (剩余连接数：{})",
                        address, count.get());
            }
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
