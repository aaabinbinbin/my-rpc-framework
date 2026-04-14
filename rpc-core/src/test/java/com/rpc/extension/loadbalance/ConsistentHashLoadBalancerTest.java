package com.rpc.extension.loadbalance;

import com.rpc.core.extension.loadbalance.impl.ConsistentHashLoadBalancer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：一致性哈希负载均衡测试")
class ConsistentHashLoadBalancerTest {
    @DisplayName("验证重建哈希环当地址列表变化场景")
    @Test
    void shouldRebuildHashRingWhenAddressListChanges() {
        ConsistentHashLoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        InetSocketAddress staleAddress = new InetSocketAddress("10.0.0.1", 8080);
        InetSocketAddress newAddress = new InetSocketAddress("10.0.0.2", 8080);

        InetSocketAddress first = loadBalancer.select("svc", List.of(staleAddress));
        InetSocketAddress second = loadBalancer.select("svc", List.of(newAddress));

        assertEquals(staleAddress, first);
        assertEquals(newAddress, second);
    }

    @DisplayName("验证忽略地址顺序当复用哈希环场景")
    @Test
    void shouldIgnoreAddressOrderingWhenReusingHashRing() {
        ConsistentHashLoadBalancer loadBalancer = new ConsistentHashLoadBalancer();
        InetSocketAddress addressA = new InetSocketAddress("10.0.0.1", 8080);
        InetSocketAddress addressB = new InetSocketAddress("10.0.0.2", 8080);

        InetSocketAddress first = loadBalancer.select("svc", List.of(addressA, addressB));
        InetSocketAddress second = loadBalancer.select("svc", List.of(addressB, addressA));

        assertEquals(first, second);
    }
}
