package com.rpc.core.invoke.cluster;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.retry.DefaultRetryStrategy;
import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ClusterInvokerTest {
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
}

