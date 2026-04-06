package com.rpc.core.resilience.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

/**
 * provider 侧限流器管理器。
 *
 * 它负责为每个服务或方法 key 维护对应的限流器实例，
 * 并支持全局默认阈值和方法级阈值两种模式。
 */
public class RateLimiterManager {
    /** 每个服务或方法 key 对应一个限流器实例。 */
    private final ConcurrentHashMap<String, RateLimiter> serviceLimiters = new ConcurrentHashMap<>();
    /** 是否开启限流功能。 */
    private volatile boolean enabled;
    /** 默认每秒允许的请求数。 */
    private volatile int permitsPerSecond = 100;

    /** 更新限流总开关和默认阈值。 */
    public void configure(boolean enabled, int permitsPerSecond) {
        this.enabled = enabled;
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
        // 固定窗口限流器内部自带计数状态，因此配置变化时直接清空重建更稳妥。
        serviceLimiters.clear();
    }

    /** 按服务维度尝试获取一个令牌。 */
    public boolean tryAcquire(String serviceName) {
        if (!enabled) {
            return true;
        }
        // 每个 key 都有自己独立的限流窗口，互不影响。
        return serviceLimiters.computeIfAbsent(
                serviceName,
                key -> new FixedWindowRateLimiter(permitsPerSecond)
        ).tryAcquire();
    }

    /** 按方法级阈值尝试获取令牌，方法级配置优先于全局默认值。 */
    public boolean tryAcquire(String serviceName, int methodPermitsPerSecond) {
        if (!enabled) {
            return true;
        }
        int permits = Math.max(1, methodPermitsPerSecond);
        // 如果同一个 key 的阈值发生变化，就替换成新的限流器，保证缓存和生效配置一致。
        return serviceLimiters.compute(
                serviceName,
                (key, limiter) -> limiter instanceof FixedWindowRateLimiter fixedWindowRateLimiter
                        && fixedWindowRateLimiter.getPermitsPerSecond() == permits
                        ? limiter
                        : new FixedWindowRateLimiter(permits)
        ).tryAcquire();
    }
}
