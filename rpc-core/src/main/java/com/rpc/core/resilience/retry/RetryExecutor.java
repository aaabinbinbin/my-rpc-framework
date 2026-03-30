package com.rpc.core.resilience.retry;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.RetryStrategy;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

@Slf4j
public class RetryExecutor {
    private final RetryStrategy retryStrategy;
    private final int maxRetries;

    public RetryExecutor(RetryStrategy retryStrategy, int maxRetries) {
        this.retryStrategy = retryStrategy;
        this.maxRetries = maxRetries;
    }

    public RpcResponse executeWithRetry(RpcRequest request, Callable<RpcResponse> callable) throws Exception {
        return executeWithRetry(request, callable, maxRetries);
    }

    public RpcResponse executeWithRetry(RpcRequest request,
                                        Callable<RpcResponse> callable,
                                        int maxRetriesOverride) throws Exception {
        // 这里是请求级重试，和连接级重连是两回事：
        // 重连只负责恢复底层通道，不会自动重放一个请求。
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
                // RetryStrategy 是围绕 RpcException 语义设计的，
                // 因此这里先把通用异常包装后再交给重试策略判断。
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

