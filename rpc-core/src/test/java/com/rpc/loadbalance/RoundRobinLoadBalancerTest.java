package com.rpc.loadbalance;

import com.rpc.loadbalance.impl.RoundRobinLoadBalancer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 轮询负载均衡策略单元测试
 */
public class RoundRobinLoadBalancerTest {

    private RoundRobinLoadBalancer loadBalancer;
    private List<InetSocketAddress> addresses;

    @BeforeEach
    void setUp() {
        loadBalancer = new RoundRobinLoadBalancer();
        addresses = Arrays.asList(
            new InetSocketAddress("localhost", 8081),
            new InetSocketAddress("localhost", 8082),
            new InetSocketAddress("localhost", 8083)
        );
    }

    @Test
    void select_shouldReturnAddressesInRoundRobinOrder() {
        // When: 连续选择4次
        InetSocketAddress selected1 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected2 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected3 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected4 = loadBalancer.select("testService", addresses);

        // Then: 应该按顺序轮询，并在第4次循环回第一个
        assertEquals("localhost/127.0.0.1:8081", selected1.toString());
        assertEquals("localhost/127.0.0.1:8082", selected2.toString());
        assertEquals("localhost/127.0.0.1:8083", selected3.toString());
        assertEquals("localhost/127.0.0.1:8081", selected4.toString()); // 循环
    }

    @Test
    void select_withNullAddresses_shouldReturnNull() {
        // When & Then
        assertNull(loadBalancer.select("testService", null));
    }

    @Test
    void select_withEmptyAddresses_shouldReturnNull() {
        // When & Then
        assertNull(loadBalancer.select("testService", Arrays.asList()));
    }

    @Test
    void select_withEmptyServiceName_shouldReturnNull() {
        // When & Then
        assertNull(loadBalancer.select("", addresses));
    }
}