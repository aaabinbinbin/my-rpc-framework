package com.rpc.core.invoke.cluster;

import com.rpc.core.resilience.retry.RetryExecutor;
import com.rpc.core.invoke.invocation.ClusterStrategy;
import com.rpc.core.protocol.RpcResponse;

import java.util.concurrent.Callable;

public final class ClusterInvokerFactory {
    private ClusterInvokerFactory() {
    }

    public static ClusterInvoker create(ClusterStrategy strategy,
                                        RetryExecutor retryExecutor,
                                        Callable<RpcResponse> invocation,
                                        int retryTimes) {
        // fail-fast 或显式关闭重试时，直接走单次调用；
        // 其余情况交给 fail-over 用 RetryExecutor 做请求级重试。
        if (strategy == ClusterStrategy.FAIL_FAST || retryTimes <= 0) {
            return new FailFastClusterInvoker(invocation);
        }
        return new FailOverClusterInvoker(retryExecutor, invocation, retryTimes);
    }
}

