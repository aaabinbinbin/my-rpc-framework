package com.rpc.extension.loadbalance;

import com.rpc.core.extension.loadbalance.impl.LeastConnectionsLoadBalancer;
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

@DisplayName("测试类：最少连接负载均衡测试")
class LeastConnectionsLoadBalancerTest {
    @DisplayName("验证清理失效地址不再在快照场景")
    @Test
    void shouldPruneInactiveAddressesThatAreNoLongerInSnapshot() throws Exception {
        LeastConnectionsLoadBalancer loadBalancer = new LeastConnectionsLoadBalancer();
        Map<String, AtomicInteger> connectionCounts = readField(loadBalancer, "connectionCounts");
        Map<String, Long> lastSeenAt = readField(loadBalancer, "lastSeenAt");

        connectionCounts.put("10.0.0.9:8080", new AtomicInteger(0));
        lastSeenAt.put("10.0.0.9:8080", System.currentTimeMillis());

        loadBalancer.select("svc", List.of(new InetSocketAddress("10.0.0.1", 8080)));

        assertFalse(connectionCounts.containsKey("10.0.0.9:8080"));
        assertTrue(connectionCounts.containsKey("10.0.0.1:8080"));
    }

    @DisplayName("验证递增仅在选择Is接受场景")
    @Test
    void shouldIncrementOnlyAfterSelectionIsAccepted() throws Exception {
        LeastConnectionsLoadBalancer loadBalancer = new LeastConnectionsLoadBalancer();
        Map<String, AtomicInteger> connectionCounts = readField(loadBalancer, "connectionCounts");
        InetSocketAddress address = new InetSocketAddress("10.0.0.1", 8080);

        loadBalancer.select("svc", List.of(address));

        assertEquals(0, connectionCounts.get("10.0.0.1:8080").get());

        loadBalancer.recordSelection("svc", address, null);
        assertEquals(1, connectionCounts.get("10.0.0.1:8080").get());

        loadBalancer.releaseSelection("svc", address);
        assertEquals(0, connectionCounts.get("10.0.0.1:8080").get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T readField(Object target, String fieldName) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        return (T) field.get(target);
    }
}
