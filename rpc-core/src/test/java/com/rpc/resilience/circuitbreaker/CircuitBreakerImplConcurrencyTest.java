package com.rpc.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerImpl;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：熔断器实现并发测试")
class CircuitBreakerImplConcurrencyTest {
    @DisplayName("验证允许仅允许的半开打开探测在并发请求场景")
    @Test
    void shouldAllowOnlyPermittedHalfOpenProbesUnderConcurrentRequests() throws Exception {
        CircuitBreakerImpl circuitBreaker = new CircuitBreakerImpl("svc", 50.0f, 1, 1_000, 1);
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
        writeField(circuitBreaker, "lastFailureTime", System.currentTimeMillis() - 2_000L);

        CountDownLatch ready = new CountDownLatch(16);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger allowed = new AtomicInteger(0);
        List<Thread> threads = new ArrayList<>();

        for (int i = 0; i < 16; i++) {
            Thread thread = new Thread(() -> {
                ready.countDown();
                try {
                    start.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return;
                }
                if (circuitBreaker.allowRequest()) {
                    allowed.incrementAndGet();
                }
            });
            threads.add(thread);
            thread.start();
        }

        ready.await();
        start.countDown();
        for (Thread thread : threads) {
            thread.join();
        }

        assertEquals(1, allowed.get());
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
    }

    private static void writeField(Object target, String fieldName, long value) throws Exception {
        Field field = target.getClass().getDeclaredField(fieldName);
        field.setAccessible(true);
        field.setLong(target, value);
    }
}
