package com.rpc.core.resilience.retry;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.common.exception.dedicated.CircuitBreakerException;
import com.rpc.core.resilience.RetryStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

/**
 * 默认重试策略。
 *
 * 所处阶段：fail-over 集群调用中，单次实例调用失败后判断是否继续重试。
 * 主要职责：根据 RpcException 的 ErrorCode、retryable 标记和当前重试次数判断是否重试，并计算退避延迟。
 *
 * 注意事项：半开探测名额耗尽属于熔断竞争，不代表业务失败，可允许换实例重试。
 */
@Slf4j
public class DefaultRetryStrategy implements RetryStrategy {
    /** 初始退避延迟。 */
    private static final long BASE_DELAY_MS = 100;
    /** 最大退避延迟，防止长时间阻塞调用线程。 */
    private static final long MAX_DELAY_MS = 5000;
    /** 指数退避倍数。 */
    private static final double BACKOFF_MULTIPLIER = 2.0;
    /** 随机抖动比例，降低大量请求同时重试造成的尖峰。 */
    private static final double JITTER_FACTOR = 0.2;

    /**
     * 判断当前异常是否应该重试。
     *
     * 边界处理：达到最大重试次数直接拒绝；不可重试错误码直接拒绝；网络类和服务端临时错误允许重试。
     */
    @Override
    public boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries) {
        if (currentRetry >= maxRetries) {
            log.debug("Max retry count reached: {}, exception={}", maxRetries, describeException(exception));
            return false;
        }

        if (exception instanceof CircuitBreakerException circuitBreakerException
                && circuitBreakerException.getReason() == CircuitBreakerException.Reason.HALF_OPEN_PROBE_EXHAUSTED) {
            log.info("Retrying after circuit breaker contention: reason={}, attempt={}/{}",
                    circuitBreakerException.getReason(), currentRetry + 1, maxRetries);
            return true;
        }

        if (!exception.isRetryable()) {
            log.debug("Exception is not retryable: {}", describeException(exception));
            return false;
        }

        ErrorCode errorCode = exception.getErrorCode();
        switch (errorCode) {
            case NETWORK_TIMEOUT:
            case CONNECTION_REFUSED:
            case CONNECTION_RESET:
            case CHANNEL_UNAVAILABLE:
                log.info("Retrying for network error: {} ({}/{})",
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;

            case SERVER_BUSY:
            case SERVER_ERROR:
                log.info("Retrying for server error: {} ({}/{})",
                        errorCode.getDescription(), currentRetry + 1, maxRetries);
                return true;

            default:
                log.debug("Error code does not support retry: {}", describeException(exception));
                return false;
        }
    }

    /**
     * 计算当前重试轮次的退避时间。
     *
     * 设计原因：指数退避 + jitter 可以降低故障恢复时的重试风暴。
     */
    @Override
    public long getDelay(int currentRetry) {
        long delay = (long) (BASE_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, currentRetry));
        delay = Math.min(delay, MAX_DELAY_MS);
        double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-JITTER_FACTOR, JITTER_FACTOR);
        delay = (long) (delay * jitter);
        log.debug("Retry delay calculated: retry={}, delay={}ms", currentRetry, delay);
        return delay;
    }

    /**
     * 生成适合日志输出的异常摘要。
     */
    private String describeException(RpcException exception) {
        if (exception instanceof CircuitBreakerException circuitBreakerException) {
            return exception.getErrorCode() + "[" + circuitBreakerException.getReason() + "]";
        }
        return String.valueOf(exception.getErrorCode());
    }
}
