package com.rpc.core.resilience.ratelimit;

import java.util.concurrent.ConcurrentHashMap;

public class RateLimiterManager {
    private final ConcurrentHashMap<String, RateLimiter> serviceLimiters = new ConcurrentHashMap<>();
    private volatile boolean enabled;
    private volatile int permitsPerSecond = 100;

    public void configure(boolean enabled, int permitsPerSecond) {
        this.enabled = enabled;
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    // 固定窗口限流器内部自带计数器，
    // 所以配置变更时直接重建每个 key 的限流器，比原地修改运行中状态更稳妥。
        serviceLimiters.clear();
    }

    public boolean tryAcquire(String serviceName) {
        if (!enabled) {
            return true;
        }
    // 每个服务或方法 key 都维护自己的独立限流器。
        return serviceLimiters.computeIfAbsent(
                serviceName,
                key -> new FixedWindowRateLimiter(permitsPerSecond)
        ).tryAcquire();
    }

    public boolean tryAcquire(String serviceName, int methodPermitsPerSecond) {
        if (!enabled) {
            return true;
        }
        int permits = Math.max(1, methodPermitsPerSecond);
    // 方法级覆盖在阈值变化时会替换限流器，
    // 保证缓存中的对象始终匹配这个 key 的最终生效配置。
        return serviceLimiters.compute(
                serviceName,
                (key, limiter) -> limiter instanceof FixedWindowRateLimiter fixedWindowRateLimiter
                        && fixedWindowRateLimiter.getPermitsPerSecond() == permits
                        ? limiter
                        : new FixedWindowRateLimiter(permits)
        ).tryAcquire();
    }
}

