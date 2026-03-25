package com.rpc.faulttolerance;

import com.rpc.common.constant.ErrorCode;
import com.rpc.common.exception.RpcException;
import com.rpc.faulttolerance.retry.DefaultRetryStrategy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

/**
 * 重试策略测试
 */
class RetryStrategyTest {

    private RetryStrategy retryStrategy;

    @BeforeEach
    void setUp() {
        retryStrategy = new DefaultRetryStrategy();
    }

    @Test
    void testShouldRetry_NetworkException() {
        RpcException exception = new RpcException(
                ErrorCode.NETWORK_TIMEOUT, "连接超时");

        assertTrue(retryStrategy.shouldRetry(exception, 0, 3));
        assertTrue(retryStrategy.shouldRetry(exception, 1, 3));
        assertTrue(retryStrategy.shouldRetry(exception, 2, 3));
        assertFalse(retryStrategy.shouldRetry(exception, 3, 3)); // 超限
    }

    @Test
    void testShouldRetry_BusinessException() {
        RpcException exception = new RpcException(
                ErrorCode.ILLEGAL_ARGUMENT, "参数错误");

        // 业务异常不可重试
        assertFalse(retryStrategy.shouldRetry(exception, 0, 3));
    }

    @Test
    void testGetDelay_ExponentialBackoff() {
        long delay0 = retryStrategy.getDelay(0);
        long delay1 = retryStrategy.getDelay(1);
        long delay2 = retryStrategy.getDelay(2);

        // 验证指数增长
        assertTrue(delay1 > delay0);
        assertTrue(delay2 > delay1);

        // 验证有随机抖动（不精确相等）
        System.out.println("Delay 0: " + delay0);
        System.out.println("Delay 1: " + delay1);
        System.out.println("Delay 2: " + delay2);
    }
}
