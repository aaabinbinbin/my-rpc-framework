package com.rpc.core.resilience.retry;

import com.rpc.core.common.constant.ErrorCode;
import com.rpc.core.common.exception.RpcException;
import com.rpc.core.resilience.RetryStrategy;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ThreadLocalRandom;

@Slf4j
public class DefaultRetryStrategy implements RetryStrategy {
    private static final long BASE_DELAY_MS = 100;
    private static final long MAX_DELAY_MS = 5000;
    private static final double BACKOFF_MULTIPLIER = 2.0;
    private static final double JITTER_FACTOR = 0.2;

    @Override
    public boolean shouldRetry(RpcException exception, int currentRetry, int maxRetries) {
        if (currentRetry >= maxRetries) {
            log.debug("Max retry count reached: {}", maxRetries);
            return false;
        }

        if (!exception.isRetryable()) {
            log.debug("Exception is not retryable: {}", exception.getErrorCode());
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
                log.debug("Error code does not support retry: {}", errorCode);
                return false;
        }
    }

    @Override
    public long getDelay(int currentRetry) {
        long delay = (long) (BASE_DELAY_MS * Math.pow(BACKOFF_MULTIPLIER, currentRetry));
        delay = Math.min(delay, MAX_DELAY_MS);
        double jitter = 1.0 + ThreadLocalRandom.current().nextDouble(-JITTER_FACTOR, JITTER_FACTOR);
        delay = (long) (delay * jitter);
        log.debug("Retry delay calculated: retry={}, delay={}ms", currentRetry, delay);
        return delay;
    }
}
