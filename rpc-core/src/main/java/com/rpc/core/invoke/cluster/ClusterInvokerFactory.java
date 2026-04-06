package com.rpc.core.invoke.cluster;

import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.protocol.RpcResponse;

import java.util.concurrent.Callable;

/**
 * 集群调用器工厂。
 *
 * “集群”在这里不是部署层概念，
 * 而是一次请求在面对多个 provider 实例时，失败后应该按什么策略处理。
 */
public final class ClusterInvokerFactory {
    private ClusterInvokerFactory() {
    }

    /**
     * 根据 clusterStrategy 创建具体的 ClusterInvoker。
     *
     * 当前支持：
     * 1. FAIL_FAST：失败立即返回，不做请求级重试。
     * 2. FAIL_OVER：失败后复用 RetryExecutor 做请求级重试。
     */
    public static ClusterInvoker create(ClusterStrategy strategy,
                                        RetryExecutor retryExecutor,
                                        Callable<RpcResponse> invocation,
                                        int retryTimes) {
        if (strategy == ClusterStrategy.FAIL_FAST || retryTimes <= 0) {
            return new FailFastClusterInvoker(invocation);
        }
        return new FailOverClusterInvoker(retryExecutor, invocation, retryTimes);
    }
}
