package com.rpc.core.invoke.cluster;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.common.exception.dedicated.CircuitBreakerException;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：集群调用器测试")
class ClusterInvokerTest {
    @DisplayName("验证失败快速失败不进行重试场景")
    @Test
    void shouldFailFastWithoutRetry() {
        AtomicInteger attempts = new AtomicInteger();
        ClusterInvoker invoker = ClusterInvokerFactory.create(
                ClusterStrategy.FAIL_FAST,
                new RetryExecutor(new DefaultRetryStrategy(), 3),
                failingCall(attempts),
                3
        );

        assertThrows(RpcException.class, () -> invoker.invoke(RpcRequest.builder().build(), null));
        assertEquals(1, attempts.get());
    }

    @DisplayName("验证重试当失败通过启用场景")
    @Test
    void shouldRetryWhenFailOverEnabled() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ClusterInvoker invoker = ClusterInvokerFactory.create(
                ClusterStrategy.FAIL_OVER,
                new RetryExecutor(new DefaultRetryStrategy(), 3),
                flakyCall(attempts),
                2
        );

        RpcResponse response = invoker.invoke(RpcRequest.builder().serviceName("svc").methodName("m").build(), null);
        assertEquals(200, response.getCode());
        assertEquals(3, attempts.get());
    }

    @DisplayName("验证重试当半开打开探测Is临时耗尽场景")
    @Test
    void shouldRetryWhenHalfOpenProbeIsTemporarilyExhausted() throws Exception {
        AtomicInteger attempts = new AtomicInteger();
        ClusterInvoker invoker = ClusterInvokerFactory.create(
                ClusterStrategy.FAIL_OVER,
                new RetryExecutor(new DefaultRetryStrategy(), 3),
                halfOpenProbeThenSuccess(attempts),
                1
        );

        RpcResponse response = invoker.invoke(RpcRequest.builder().serviceName("svc").methodName("m").build(), null);

        assertEquals(200, response.getCode());
        assertEquals(2, attempts.get());
    }

    @DisplayName("验证不重试当全部实例保持打开场景")
    @Test
    void shouldNotRetryWhenAllInstancesRemainOpen() {
        AtomicInteger attempts = new AtomicInteger();
        ClusterInvoker invoker = ClusterInvokerFactory.create(
                ClusterStrategy.FAIL_OVER,
                new RetryExecutor(new DefaultRetryStrategy(), 3),
                allOpen(attempts),
                1
        );

        CircuitBreakerException exception = assertThrows(
                CircuitBreakerException.class,
                () -> invoker.invoke(RpcRequest.builder().serviceName("svc").methodName("m").build(), null)
        );

        assertEquals(CircuitBreakerException.Reason.ALL_INSTANCES_OPEN, exception.getReason());
        assertEquals(1, attempts.get());
    }

    private Callable<RpcResponse> failingCall(AtomicInteger attempts) {
        return () -> {
            attempts.incrementAndGet();
            throw new RpcException(ErrorCode.SERVER_ERROR, "failed");
        };
    }

    private Callable<RpcResponse> flakyCall(AtomicInteger attempts) {
        return () -> {
            int current = attempts.incrementAndGet();
            if (current < 3) {
                throw new RpcException(ErrorCode.SERVER_ERROR, "failed");
            }
            return RpcResponse.success("ok", "req");
        };
    }

    private Callable<RpcResponse> halfOpenProbeThenSuccess(AtomicInteger attempts) {
        return () -> {
            int current = attempts.incrementAndGet();
            if (current == 1) {
                throw new CircuitBreakerException("svc", CircuitBreakerException.Reason.HALF_OPEN_PROBE_EXHAUSTED);
            }
            return RpcResponse.success("ok", "req");
        };
    }

    private Callable<RpcResponse> allOpen(AtomicInteger attempts) {
        return () -> {
            attempts.incrementAndGet();
            throw new CircuitBreakerException("svc", CircuitBreakerException.Reason.ALL_INSTANCES_OPEN);
        };
    }
}

