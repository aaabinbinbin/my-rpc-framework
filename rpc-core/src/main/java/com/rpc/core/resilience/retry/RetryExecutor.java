package com.rpc.core.resilience.retry;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.RetryStrategy;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 请求级重试执行器。
 *
 * 这个类负责在一次业务请求失败后，
 * 按照 RetryStrategy 的规则决定是否继续重试、等待多久再重试。
 *
 * 注意它处理的是“请求重试”，不是“连接重连”。
 * - 请求重试：同一业务调用是否再次尝试。
 * - 连接重连：底层网络连接断开后是否恢复通道。
 */
@Slf4j
public class RetryExecutor {
    /** 重试策略，决定什么异常可以重试、重试间隔是多少。 */
    private final RetryStrategy retryStrategy;
    /** 默认最大重试次数。 */
    private final int maxRetries;

    public RetryExecutor(RetryStrategy retryStrategy, int maxRetries) {
        this.retryStrategy = retryStrategy;
        this.maxRetries = maxRetries;
    }

    /** 使用默认最大重试次数执行重试。 */
    public RpcResponse executeWithRetry(RpcRequest request, Callable<RpcResponse> callable) throws Exception {
        return executeWithRetry(request, callable, maxRetries);
    }

    /**
     * 执行带重试的调用。
     *
     * 失败后会先询问 RetryStrategy 是否允许重试，
     * 允许则等待指定时间后继续，否则直接抛出异常。
     */
    public RpcResponse executeWithRetry(RpcRequest request,
                                        Callable<RpcResponse> callable,
                                        int maxRetriesOverride) throws Exception {
        int retryCount = 0;
        while (true) {
            try {
                return callable.call();
            } catch (RpcException e) {
                if (!retryStrategy.shouldRetry(e, retryCount, maxRetriesOverride)) {
                    throw e;
                }
                retryCount++;
                TimeUnit.MILLISECONDS.sleep(retryStrategy.getDelay(retryCount));
            } catch (Exception e) {
                RpcException wrapped = new RpcException(ErrorCode.SERVER_ERROR, "Unknown rpc invoke error", e);
                if (!retryStrategy.shouldRetry(wrapped, retryCount, maxRetriesOverride)) {
                    throw e;
                }
                retryCount++;
                TimeUnit.MILLISECONDS.sleep(retryStrategy.getDelay(retryCount));
            }
        }
    }
}
