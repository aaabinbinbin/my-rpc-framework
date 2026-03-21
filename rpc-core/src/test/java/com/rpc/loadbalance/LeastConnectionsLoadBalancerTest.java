package com.rpc.loadbalance;

import com.rpc.loadbalance.impl.LeastConnectionsLoadBalancer;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.Arrays;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 最小连接数负载均衡策略单元测试
 */
@Slf4j
public class LeastConnectionsLoadBalancerTest {

    private LeastConnectionsLoadBalancer loadBalancer;
    private List<InetSocketAddress> addresses;

    @BeforeEach
    void setUp() {
        loadBalancer = new LeastConnectionsLoadBalancer();
        addresses = Arrays.asList(
            new InetSocketAddress("localhost", 8081),
            new InetSocketAddress("localhost", 8082),
            new InetSocketAddress("localhost", 8083)
        );
    }

    @Test
    void select_shouldReturnAddressWithLeastConnections() {
        // When: 初始状态下，所有连接数为0，应选择第一个
        InetSocketAddress selected1 = loadBalancer.select("testService", addresses);
        assertEquals("localhost/127.0.0.1:8081", selected1.toString());

        // 模拟释放一次连接（虽然通常在调用后由框架处理）
        loadBalancer.releaseConnection(selected1);

        // 再次选择，仍应选择第一个，因为其连接数最少（-1 vs 0 vs 0）
        InetSocketAddress selected2 = loadBalancer.select("testService", addresses);
        assertEquals("localhost/127.0.0.1:8081", selected2.toString());
    }

    @Test
    void test() {
        // When: 初始状态下，所有连接数为0，应选择第一个
        InetSocketAddress selected1 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected2 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected3 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected4 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected5 = loadBalancer.select("testService", addresses);
        InetSocketAddress selected6 = loadBalancer.select("testService", addresses);
        assertEquals("localhost/127.0.0.1:8081", selected1.toString());
        assertEquals("localhost/127.0.0.1:8082", selected2.toString());
        assertEquals("localhost/127.0.0.1:8083", selected3.toString());
        assertEquals("localhost/127.0.0.1:8081", selected4.toString());
        assertEquals("localhost/127.0.0.1:8082", selected5.toString());
        assertEquals("localhost/127.0.0.1:8083", selected6.toString());
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