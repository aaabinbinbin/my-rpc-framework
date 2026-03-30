package com.rpc.core.resilience;

import com.rpc.core.common.exception.RpcException;

/**
 * 重试策略契约。
 */
public interface RetryStrategy {
    boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries);

    long getDelay(int currentRetry);
}
