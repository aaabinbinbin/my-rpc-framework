package com.rpc.faulttolerance;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.resilience.CircuitBreakerState;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerImpl;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 故障容错集成测试。
 *
 * <p>测试目标：覆盖重试策略和熔断器的基础协作边界，包括可重试异常、
 * 不可重试异常、中断保护、熔断打开、半开探测和最小调用次数保护。</p>
 */
@DisplayName("故障容错集成测试")
class FaultToleranceIntegrationTest {
    private CircuitBreakerImpl circuitBreaker;
    private RetryExecutor retryExecutor;
    private RpcRequest testRequest;

    @BeforeEach
    void setUp() {
        circuitBreaker = new CircuitBreakerImpl("TestService", 50.0f, 5, 1000, 3);
        retryExecutor = new RetryExecutor(new DefaultRetryStrategy(), 3);

        testRequest = new RpcRequest();
        testRequest.setServiceName("TestService");
        testRequest.setMethodName("testMethod");
    }

    @DisplayName("网络类可重试异常应触发重试并最终返回成功响应")
    @Test
    void testRetryOnNetworkFailure() throws Exception {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<RpcResponse> mockCallable = () -> {
            int count = callCount.incrementAndGet();
            if (count <= 2) {
                throw new RpcException(ErrorCode.CONNECTION_REFUSED, "connection refused on call " + count);
            }

            RpcResponse response = new RpcResponse();
            response.setCode(200);
            response.setMessage("success");
            return response;
        };

        RpcResponse response = retryExecutor.executeWithRetry(testRequest, mockCallable);

        assertNotNull(response);
        assertEquals(200, response.getCode());
        assertEquals(3, callCount.get());
    }

    @DisplayName("非可重试 RPC 异常不应继续重试")
    @Test
    void testNoRetryForNonRetryableException() {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<RpcResponse> mockCallable = () -> {
            callCount.incrementAndGet();
            throw new RpcException(ErrorCode.ILLEGAL_ARGUMENT, "bad request");
        };

        RpcException exception = assertThrows(RpcException.class,
                () -> retryExecutor.executeWithRetry(testRequest, mockCallable));

        assertEquals(ErrorCode.ILLEGAL_ARGUMENT, exception.getErrorCode());
        assertEquals(1, callCount.get());
        assertFalse(exception.isRetryable());
    }

    @DisplayName("客户端繁忙异常不应继续重试")
    @Test
    void testNoRetryForClientBusyException() {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<RpcResponse> mockCallable = () -> {
            callCount.incrementAndGet();
            throw new RpcException(ErrorCode.CLIENT_BUSY, "client budget exhausted");
        };

        RpcException exception = assertThrows(RpcException.class,
                () -> retryExecutor.executeWithRetry(testRequest, mockCallable));

        assertEquals(ErrorCode.CLIENT_BUSY, exception.getErrorCode());
        assertEquals(1, callCount.get());
        assertFalse(exception.isRetryable());
    }

    @DisplayName("未知本地异常不应继续重试并应原样抛出")
    @Test
    void testNoRetryForUnknownLocalException() {
        AtomicInteger callCount = new AtomicInteger(0);

        Callable<RpcResponse> mockCallable = () -> {
            callCount.incrementAndGet();
            throw new IllegalArgumentException("bad local state");
        };

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> retryExecutor.executeWithRetry(testRequest, mockCallable));

        assertEquals("bad local state", exception.getMessage());
        assertEquals(1, callCount.get());
    }

    @DisplayName("重试遇到中断异常时应保留线程中断标记")
    @Test
    void testRetryPreservesInterruptStatus() {
        AtomicInteger callCount = new AtomicInteger(0);
        Thread.interrupted();

        InterruptedException exception = assertThrows(InterruptedException.class,
                () -> retryExecutor.executeWithRetry(testRequest, () -> {
                    callCount.incrementAndGet();
                    throw new InterruptedException("stop retry");
                }));

        assertEquals("stop retry", exception.getMessage());
        assertEquals(1, callCount.get());
        assertTrue(Thread.currentThread().isInterrupted());
        Thread.interrupted();
    }

    @DisplayName("失败率达到阈值后熔断器应打开")
    @Test
    void testCircuitBreakerOpensOnFailures() {
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @DisplayName("熔断器等待窗口结束后应进入半开并在成功探测后关闭")
    @Test
    void testCircuitBreakerRecovery() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());

        Thread.sleep(1100);
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());

        assertTrue(circuitBreaker.allowRequest());
        assertTrue(circuitBreaker.allowRequest());
        assertTrue(circuitBreaker.allowRequest());
        assertFalse(circuitBreaker.allowRequest());

        circuitBreaker.recordSuccess();
        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }

    @DisplayName("半开探测失败时熔断器应重新打开")
    @Test
    void testHalfOpenFailureReopensCircuitBreaker() throws InterruptedException {
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }
        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());

        Thread.sleep(1100);
        assertEquals(CircuitBreakerState.HALF_OPEN, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());

        circuitBreaker.recordFailure();
        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @DisplayName("未达到最小调用次数时熔断器不应打开")
    @Test
    void testMinNumberOfCallsProtection() {
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }
}
