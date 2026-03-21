package com.rpc.loadbalance;

import com.rpc.loadbalance.impl.RandomLoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 随机负载均衡策略单元测试
 */
@Slf4j
public class RandomLoadBalancerTest {

    private RandomLoadBalancer loadBalancer;
    private List<InetSocketAddress> addresses;

    @BeforeEach
    void setUp() {
        loadBalancer = new RandomLoadBalancer();
        addresses = Arrays.asList(
            new InetSocketAddress("localhost", 8081),
            new InetSocketAddress("localhost", 8082),
            new InetSocketAddress("localhost", 8083)
        );
    }

    @Test
    void select_shouldReturnOneOfTheAddresses() {
        // When: 多次选择
        Map<String, Integer> selectedAddresses = new HashMap<>();
        for (int i = 0; i < 100; i++) {
            InetSocketAddress selected = loadBalancer.select("testService", addresses);
            assertNotNull(selected);
            String addressKey = selected.toString();
            selectedAddresses.put(addressKey, selectedAddresses.getOrDefault(addressKey, 0) + 1);
        }
        // Then: 至少选择了两个不同的地址（高概率事件，验证随机性）
        assertTrue(selectedAddresses.size() > 1, "随机负载均衡应能选择多个不同地址");
        for (Map.Entry<String, Integer> entry : selectedAddresses.entrySet()) {
            log.info("{}调用次数: {}", entry.getKey(), entry.getValue());
        }
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
}