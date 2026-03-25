package com.rpc.faulttolerance.retry;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.faulttolerance.RetryStrategy;
import com.rpc.protocol.RpcRequest;
import com.rpc.protocol.RpcResponse;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Callable;
import java.util.concurrent.TimeUnit;

/**
 * 重试执行器
 * 封装重试逻辑，提供统一的重试入口
 */
@Slf4j
public class RetryExecutor {
    /** 重试策略 */
    private final RetryStrategy retryStrategy;

    /** 最大重试次数 */
    private final int maxRetries;

    public RetryExecutor(RetryStrategy retryStrategy, int maxRetries) {
        this.retryStrategy = retryStrategy;
        this.maxRetries = maxRetries;
    }

    /**
     * 执行带重试的 RPC 调用
     * @param request RPC 请求
     * @param callable 实际调用逻辑
     * @return RPC 响应
     * @throws Exception 重试失败后抛出的异常
     */
    public RpcResponse executeWithRetry(RpcRequest request,
                                        Callable<RpcResponse> callable) throws Exception {
        int retryCount = 0;
        RpcException lastException = null;

        while (true) {
            try {
                // 执行实际调用
                log.debug("执行 RPC 调用：{}.{} (尝试第{}次)",
                        request.getServiceName(),
                        request.getMethodName(),
                        retryCount + 1);

                return callable.call();

            } catch (RpcException e) {
                lastException = e;
                log.warn("RPC 调用失败：{} - {}", e.getErrorCode(), e.getMessage());

                // 判断是否重试
                if (retryStrategy.shouldRetry(e, retryCount, maxRetries)) {
                    retryCount++;

                    // 计算延迟时间
                    long delay = retryStrategy.getDelay(retryCount);
                    log.info("将在 {}ms 后重试 (第{}/{}次)...", delay, retryCount, maxRetries);

                    // 等待延迟
                    TimeUnit.MILLISECONDS.sleep(delay);

                } else {
                    // 不重试，直接抛出
                    log.error("放弃重试，总共失败{}次", retryCount + 1);
                    throw e;
                }
            } catch (Exception e) {
                // 非 RpcException，包装后重试
                log.error("未知异常", e);
                RpcException wrapped = new RpcException(ErrorCode.SERVER_ERROR,
                        "未知异常：" + e.getMessage(), e);

                if (retryStrategy.shouldRetry(wrapped, retryCount, maxRetries)) {
                    retryCount++;
                    long delay = retryStrategy.getDelay(retryCount);
                    TimeUnit.MILLISECONDS.sleep(delay);
                } else {
                    throw e;
                }
            }
        }
    }
}
