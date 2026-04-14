package com.rpc.core.resilience.ratelimit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 限流模块边界测试。
 *
 * <p>测试目标：验证固定窗口限流器和限流管理器在异常阈值、配置关闭、服务隔离、
 * 方法级阈值覆盖等场景下的行为，避免生产流量下出现配置已更新但旧限流器仍生效的问题。</p>
 */
@DisplayName("限流模块边界测试")
class RateLimiterBoundaryTest {

    @Test
    @DisplayName("固定窗口限流器会把非正数阈值归一化为每秒至少一次")
    void fixedWindowLimiterShouldNormalizeInvalidPermits() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(0);

        assertTrue(limiter.tryAcquire(), "归一化后的第一个请求应允许通过");
        assertFalse(limiter.tryAcquire(), "同一窗口内超过归一化阈值的请求应被拒绝");
    }

    @Test
    @DisplayName("固定窗口限流器在同一窗口内超过阈值后拒绝请求")
    void fixedWindowLimiterShouldRejectRequestsOverThreshold() {
        FixedWindowRateLimiter limiter = new FixedWindowRateLimiter(2);

        assertTrue(limiter.tryAcquire());
        assertTrue(limiter.tryAcquire());
        assertFalse(limiter.tryAcquire(), "第三个请求超过每秒两次的阈值，应被拒绝");
    }

    @Test
    @DisplayName("限流关闭时管理器应直接放行请求")
    void managerShouldAllowAllRequestsWhenDisabled() {
        RateLimiterManager manager = new RateLimiterManager();
        manager.configure(false, 1);

        assertTrue(manager.tryAcquire("userService"));
        assertTrue(manager.tryAcquire("userService"));
        assertTrue(manager.tryAcquire("userService", 1));
        assertTrue(manager.tryAcquire("userService", 1));
    }

    @Test
    @DisplayName("服务级限流器应按服务名隔离窗口")
    void managerShouldIsolateLimiterByServiceName() {
        RateLimiterManager manager = new RateLimiterManager();
        manager.configure(true, 1);

        assertTrue(manager.tryAcquire("userService"));
        assertFalse(manager.tryAcquire("userService"));
        assertTrue(manager.tryAcquire("orderService"), "不同服务名应使用独立限流窗口");
    }

    @Test
    @DisplayName("方法级阈值变化时应替换缓存中的限流器")
    void managerShouldReplaceMethodLimiterWhenThresholdChanged() {
        RateLimiterManager manager = new RateLimiterManager();
        manager.configure(true, 1);

        assertTrue(manager.tryAcquire("userService#getById", 1));
        assertFalse(manager.tryAcquire("userService#getById", 1));

        assertTrue(manager.tryAcquire("userService#getById", 2), "阈值变化后应创建新的限流器并重新计数");
        assertTrue(manager.tryAcquire("userService#getById", 2));
        assertFalse(manager.tryAcquire("userService#getById", 2));
    }
}
