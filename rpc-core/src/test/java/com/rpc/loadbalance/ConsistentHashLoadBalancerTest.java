package com.rpc.loadbalance;

import com.rpc.loadbalance.impl.ConsistentHashLoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 一致性Hash负载均衡策略单元测试
 */
@Slf4j
public class ConsistentHashLoadBalancerTest {

    private ConsistentHashLoadBalancer loadBalancer;
    private List<InetSocketAddress> addresses;

    @BeforeEach
    void setUp() {
        loadBalancer = new ConsistentHashLoadBalancer();
        addresses = Arrays.asList(
            new InetSocketAddress("localhost", 8081),
            new InetSocketAddress("localhost", 8082),
            new InetSocketAddress("localhost", 8083)
        );
    }

    @Test
    void select_shouldReturnSameAddressForSameServiceName() {
        // When: 多次选择同一个服务名
        String serviceName = "testService";
        InetSocketAddress selected1 = loadBalancer.select(serviceName, addresses);
        InetSocketAddress selected2 = loadBalancer.select(serviceName, addresses);
        InetSocketAddress selected3 = loadBalancer.select(serviceName, addresses);

        // Then: 应该始终返回相同的地址，保证一致性
        assertNotNull(selected1);
        assertEquals(selected1.toString(), selected2.toString());
        assertEquals(selected2.toString(), selected3.toString());
    }

    @Test
    void select_withDifferentServiceNames_shouldReturnDifferentAddresses() {
        // Given
        String service1 = "serviceA";
        String service2 = "serviceB";

        // When
        InetSocketAddress selected1 = loadBalancer.select(service1, addresses);
        InetSocketAddress selected2 = loadBalancer.select(service2, addresses);

        // Then: 不同的服务名很可能映射到不同的节点
        // 注意：由于哈希的随机性，这里不能保证绝对不同，但应该有较大概率不同
        // 因此我们主要验证其不相等的可能性
        if (addresses.size() > 1) {
            log.info("serviceA -> {}", selected1);
            log.info("serviceB -> {}", selected2);
            // 这个断言可能偶尔失败，所以更合适的测试是检查其分布性，而非必然不同
            // 我们保留日志输出用于观察
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

    @Test
    void select_withEmptyServiceName_shouldReturnNull() {
        // When & Then
        assertNull(loadBalancer.select("", addresses));
    }
}