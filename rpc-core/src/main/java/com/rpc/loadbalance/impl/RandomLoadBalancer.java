package com.rpc.loadbalance.impl;

import com.rpc.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

/**
 * 随机负载均衡策略
 */
@Slf4j
public class RandomLoadBalancer implements LoadBalancer {
    private final Random random = new Random();
    @Override
    public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
        if (addresses == null || addresses.isEmpty()) {
            return null;
        }
        int index = random.nextInt(addresses.size());
        InetSocketAddress selected = addresses.get(index);
        log.info("[Random] 选择: {}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "random";
    }
}
