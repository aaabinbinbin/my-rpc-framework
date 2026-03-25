package com.rpc.faulttolerance;

import com.rpc.faulttolerance.circuitbreaker.CircuitBreakerImpl;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.*;

/**
 * 熔断器测试
 */
class CircuitBreakerTest {

    @Test
    void testStateTransition() throws InterruptedException {
        CircuitBreaker breaker = new CircuitBreakerImpl(
                "test-service",
                50.0f,      // 50% 失败率
                5,          // 最少 5 个请求
                1000,       // 1 秒休眠
                2           // 半开 2 个请求
        );

        // 初始状态：CLOSED
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());

        // 模拟连续失败
        for (int i = 0; i < 10; i++) {
            breaker.recordFailure();
        }

        // 状态应变为 OPEN
        assertEquals(CircuitBreakerState.OPEN, breaker.getState());
        assertFalse(breaker.allowRequest());

        // 等待 1 秒
        Thread.sleep(1000);

        // 状态应变为 HALF_OPEN
        assertEquals(CircuitBreakerState.HALF_OPEN, breaker.getState());
        assertTrue(breaker.allowRequest());

        // 模拟成功
        breaker.recordSuccess();

        // 状态应回到 CLOSED
        assertEquals(CircuitBreakerState.CLOSED, breaker.getState());
    }
}