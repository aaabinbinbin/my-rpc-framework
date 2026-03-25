package com.rpc.faulttolerance.retry;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.faulttolerance.RetryStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认重试策略实现
 * 特性：
 * 1. 仅对可重试异常进行重试
 * 2. 指数退避算法
 * 3. 添加随机抖动避免惊群效应
 */
@Slf4j
public class DefaultRetryStrategy implements RetryStrategy {
    /** 基础延迟时间（毫秒） */
    private static final long BASE_DELAY_MS = 100;

    /** 最大延迟时间（毫秒） */
    private static final long MAX_DELAY_MS = 5000;

    /** 退避因子 */
    private static final double BACKOFF_MULTIPLIER = 2.0;

    /** 随机抖动范围（±20%） */
    private static final double JITTER_FACTOR = 0.2;

    @Override
    public boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries) {
        // 1. 检查是否达到最大重试次数
        if (currentRetry >= maxRetries) {
            log.debug("已达到最大重试次数 {}，不再重试", maxRetries);
            return false;
        }

        // 2. 检查异常是否可重试
        if (!exception.isRetryable()) {
            log.debug("异常不可重试：{}", exception.getErrorCode());
            return false;
        }

        // 3. 根据错误码精细控制
        ErrorCode errorCode = exception.getErrorCode();
        switch (errorCode) {
            case NETWORK_TIMEOUT:
            case CONNECTION_REFUSED:
            case CONNECTION_RESET:
            case CHANNEL_UNAVAILABLE:
                log.info("网络异常，准备重试：{} (第{}/{}次)",
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;

            case SERVER_BUSY:
            case SERVER_ERROR:
                // 服务端错误可以尝试，但要谨慎
                log.info("服务端异常，准备重试：{} (第{}/{}次)",
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;

            default:
                log.debug("错误码不支持重试：{}", errorCode);
                return false;
        }
    }

    @Override
    public long getDelay(int currentRetry) {
        // 指数退避公式：delay = baseDelay * (multiplier ^ retryCount)
        long delay = (long) (BASE_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, currentRetry));

        // 限制最大延迟
        delay = Math.min(delay, MAX_DELAY_MS);

        // 添加随机抖动：delay * (1 ± jitterFactor)
        double jitter = 1.0 + (ThreadLocalRandom.current().nextDouble(-JITTER_FACTOR, JITTER_FACTOR));
        delay = (long) (delay * jitter);

        log.debug("计算重试延迟：第{}次，delay={}ms", currentRetry, delay);
        return delay;
    }
}
