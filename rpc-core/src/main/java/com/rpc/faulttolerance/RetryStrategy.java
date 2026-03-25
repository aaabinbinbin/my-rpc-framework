package com.rpc.faulttolerance;

import com.rpc.common.exception.RpcException;

/**
 * 重试策略接口
 * 定义重试决策的标准
 */
public interface RetryStrategy {
    /**
     * 判断是否应该重试
     * @param exception 抛出的异常
     * @param currentRetry 当前重试次数（从 0 开始）
     * @param maxRetries 最大重试次数
     * @return true-重试，false-放弃
     */
    boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries);

    /**
     * 计算下次重试的延迟时间（毫秒）
     * @param currentRetry 当前重试次数
     * @return 延迟时间
     */
    long getDelay(int currentRetry);
}
