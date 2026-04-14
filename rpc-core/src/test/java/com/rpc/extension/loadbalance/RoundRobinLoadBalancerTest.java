package com.rpc.extension.loadbalance;

import com.rpc.core.extension.loadbalance.impl.RoundRobinLoadBalancer;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：轮询负载均衡测试")
class RoundRobinLoadBalancerTest {
    @DisplayName("验证清理过期服务计数器On选择场景")
    @Test
    void shouldPruneExpiredServiceCountersOnSelect() throws Exception {
        RoundRobinLoadBalancer loadBalancer = new RoundRobinLoadBalancer();
        Map<String, AtomicInteger> counters = readField(loadBalancer, "counters");
        Map<String, Long> lastUsedAt = readField(loadBalancer, "lastUsedAt");

        counters.put("stale-service", new AtomicInteger(7));
        lastUsedAt.put("stale-service", 0L);

        loadBalancer.select("active-service", List.of(new InetSocketAddress("127.0.0.1", 8080)));

        assertFalse(counters.containsKey("stale-service"));
        assertTrue(counters.containsKey("active-service"));
    }

    @DisplayName("验证处理整数最小值Counter场景")
    @Test
    void shouldHandleIntegerMinValueCounter() throws Exception {
        RoundRobinLoadBalancer loadBalancer = new RoundRobinLoadBalancer();
        Map<String, AtomicInteger> counters = readField(loadBalancer, "counters");
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        counters.put("svc", new AtomicInteger(Integer.MIN_VALUE));

        InetSocketAddress selected = loadBalancer.select("svc", List.of(address));

        assertEquals(address, selected);
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
