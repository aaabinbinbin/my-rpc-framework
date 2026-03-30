package com.rpc.faulttolerance;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
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

    @Test
    @DisplayName("retry should succeed after transient failures")
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

    @Test
    @DisplayName("retry should stop on non-retryable exception")
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

    @Test
    @DisplayName("circuit breaker should open after enough failures")
    void testCircuitBreakerOpensOnFailures() {
        for (int i = 0; i < 10; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreakerState.OPEN, circuitBreaker.getState());
        assertFalse(circuitBreaker.allowRequest());
    }

    @Test
    @DisplayName("circuit breaker should recover after wait window and success")
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

    @Test
    @DisplayName("failure during half-open should reopen circuit breaker")
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

    @Test
    @DisplayName("circuit breaker should stay closed before min call threshold")
    void testMinNumberOfCallsProtection() {
        for (int i = 0; i < 3; i++) {
            circuitBreaker.recordFailure();
        }

        assertEquals(CircuitBreakerState.CLOSED, circuitBreaker.getState());
        assertTrue(circuitBreaker.allowRequest());
    }
}
