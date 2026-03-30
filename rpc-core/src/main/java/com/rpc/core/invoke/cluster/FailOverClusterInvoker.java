package com.rpc.core.invoke.cluster;

import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

import java.util.concurrent.Callable;

public class FailOverClusterInvoker implements ClusterInvoker {
    private final RetryExecutor retryExecutor;
    private final Callable<RpcResponse> invocation;
    private final int retryTimes;

    public FailOverClusterInvoker(RetryExecutor retryExecutor, Callable<RpcResponse> invocation, int retryTimes) {
        this.retryExecutor = retryExecutor;
        this.invocation = invocation;
        this.retryTimes = retryTimes;
    }

    @Override
    public RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception {
        // fail-over 的核心不是自己写循环，而是复用统一 RetryExecutor，
        // 这样重试规则可以和其他调用路径共用同一套策略。
        return retryExecutor.executeWithRetry(request, invocation, retryTimes);
    }
}

