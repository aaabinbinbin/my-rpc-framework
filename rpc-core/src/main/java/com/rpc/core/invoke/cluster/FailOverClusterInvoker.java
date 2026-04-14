package com.rpc.core.invoke.cluster;

import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import com.rpc.core.transport.netty.client.invocation.RpcTransportInvoker;

import java.util.concurrent.Callable;

/**
 * fail-over 集群策略实现。
 *
 * 这种策略会在单次调用失败后，通过 RetryExecutor 按重试策略继续尝试，
 * 以提高在多实例场景下的调用成功率。
 */
public class FailOverClusterInvoker implements ClusterInvoker {
    /** 重试执行器，统一处理重试规则和间隔。 */
    private final RetryExecutor retryExecutor;
    /** 单次调用动作。 */
    private final Callable<RpcResponse> invocation;
    /** 本次 cluster 调用允许的最大重试次数。 */
    private final int retryTimes;

    public FailOverClusterInvoker(RetryExecutor retryExecutor, Callable<RpcResponse> invocation, int retryTimes) {
        this.retryExecutor = retryExecutor;
        this.invocation = invocation;
        this.retryTimes = retryTimes;
    }

    /**
     * 通过 RetryExecutor 执行 fail-over。
     *
     * 这里自己不写循环重试逻辑，而是复用统一重试执行器，
     * 保证不同调用路径下的重试规则保持一致。
     */
    @Override
    public RpcResponse invoke(RpcRequest request, RpcTransportInvoker transportInvoker) throws Exception {
        return retryExecutor.executeWithRetry(request, invocation, retryTimes);
    }
}
