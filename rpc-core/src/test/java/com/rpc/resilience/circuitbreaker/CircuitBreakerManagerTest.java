package com.rpc.resilience.circuitbreaker;

import com.rpc.core.common.exception.dedicated.CircuitBreakerException;
import com.rpc.core.extension.loadbalance.impl.RandomLoadBalancer;
import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：熔断器管理器测试")
class CircuitBreakerManagerTest {
    @DisplayName("验证不创建实例器当实例Level禁用场景")
    @Test
    void shouldNotCreateInstanceBreakerWhenInstanceLevelDisabled() {
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        manager.resetAll();
        manager.setEnableInstanceLevelCircuitBreaker(false);

        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        CircuitBreaker first = manager.getInstanceCircuitBreaker("svc", address);
        CircuitBreaker second = manager.getInstanceCircuitBreaker("svc", address);
        first.recordFailure();

        assertSame(first, second);
        assertTrue(first.allowRequest());
        assertEquals(0, manager.instanceBreakerCount());

        manager.resetAll();
    }

    @DisplayName("验证仍然选择地址当实例Level禁用场景")
    @Test
    void shouldStillSelectAddressWhenInstanceLevelDisabled() throws Exception {
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        manager.resetAll();
        manager.setEnableInstanceLevelCircuitBreaker(false);

        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        InetSocketAddress selected = new RandomLoadBalancer()
                .selectWithCircuitBreaker("svc", List.of(address), manager);

        assertEquals(address, selected);
        assertEquals(0, manager.instanceBreakerCount());

        manager.resetAll();
    }

    @DisplayName("验证Consume半开打开探测仅用于选中实例场景")
    @Test
    void shouldConsumeHalfOpenProbeOnlyForSelectedInstance() throws Exception {
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        manager.resetAll();
        manager.configure(50.0f, 1, 1_000L, 1);

        InetSocketAddress halfOpenAddress = new InetSocketAddress("127.0.0.1", 8081);
        InetSocketAddress selectedAddress = new InetSocketAddress("127.0.0.1", 8082);
        CircuitBreaker breaker = manager.getInstanceCircuitBreaker("svc", halfOpenAddress);
        breaker.recordFailure();
        writeField(breaker, "lastFailureTime", System.currentTimeMillis() - 2_000L);

        InetSocketAddress actual = new PreferLastLoadBalancer()
                .selectWithCircuitBreaker("svc", List.of(halfOpenAddress, selectedAddress), manager);

        assertEquals(selectedAddress, actual);
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
        assertTrue(breaker.allowRequest());

        manager.resetAll();
    }

    @DisplayName("验证报告全部实例打开原因场景")
    @Test
    void shouldReportAllInstancesOpenReason() {
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        manager.resetAll();
        manager.configure(50.0f, 1, 60_000L, 1);

        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 8083);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 8084);
        manager.getInstanceCircuitBreaker("svc", first).recordFailure();
        manager.getInstanceCircuitBreaker("svc", second).recordFailure();

        CircuitBreakerException exception = assertThrows(
                CircuitBreakerException.class,
                () -> new PreferLastLoadBalancer().selectWithCircuitBreaker("svc", List.of(first, second), manager)
        );

        assertEquals(CircuitBreakerException.Reason.ALL_INSTANCES_OPEN, exception.getReason());
        manager.resetAll();
    }

    @DisplayName("验证报告半开打开探测耗尽原因场景")
    @Test
    void shouldReportHalfOpenProbeExhaustedReason() throws Exception {
        CircuitBreakerManager manager = CircuitBreakerManager.getInstance();
        manager.resetAll();
        manager.configure(50.0f, 1, 1_000L, 1);

        InetSocketAddress halfOpenAddress = new InetSocketAddress("127.0.0.1", 8085);
        CircuitBreaker breaker = manager.getInstanceCircuitBreaker("svc", halfOpenAddress);
        breaker.recordFailure();
        writeField(breaker, "lastFailureTime", System.currentTimeMillis() - 2_000L);

        assertTrue(breaker.allowRequest());
        CircuitBreakerException exception = assertThrows(
                CircuitBreakerException.class,
                () -> new PreferLastLoadBalancer().selectWithCircuitBreaker("svc", List.of(halfOpenAddress), manager)
        );

        assertEquals(CircuitBreakerException.Reason.HALF_OPEN_PROBE_EXHAUSTED, exception.getReason());
        manager.resetAll();
    }

    private static void writeField(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }

    private static final class PreferLastLoadBalancer extends RandomLoadBalancer {
        @Override
        public InetSocketAddress select(String serviceName, List<InetSocketAddress> addresses) {
            return addresses.get(addresses.size() - 1);
        }
    }
}
