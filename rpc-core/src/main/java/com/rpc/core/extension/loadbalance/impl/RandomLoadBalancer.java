package com.rpc.core.extension.loadbalance.impl;

import com.rpc.core.extension.loadbalance.LoadBalancer;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Random;

/**
 * 随机负载均衡器。
 * 当各个提供者节点能力比较接近时，它通常是一个很好的默认选择：
 * 状态少、协调成本低，而且足够均匀地分散请求。
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
        log.info("[Random] selected={}", selected);
        return selected;
    }

    @Override
    public String getName() {
        return "random";
    }
}
