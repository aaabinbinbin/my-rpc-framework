package com.rpc.core.resilience.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 固定窗口限流器。
 *
 * 它把时间按 1 秒切成一个个窗口，
 * 每个窗口内最多允许 permitsPerSecond 次请求通过。
 *
 * 这个实现简单直接，适合作为教学和基础限流方案。
 */
public class FixedWindowRateLimiter implements RateLimiter {
    /** 每秒允许通过的最大请求数。 */
    private final int permitsPerSecond;
    /** 当前窗口起始时间。 */
    private final AtomicLong windowStartMillis = new AtomicLong(System.currentTimeMillis());
    /** 当前窗口内已通过的请求数。 */
    private final AtomicInteger currentPermits = new AtomicInteger(0);

    public FixedWindowRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    /** 暴露当前限流阈值，便于管理器判断是否需要替换实例。 */
    public int getPermitsPerSecond() {
        return permitsPerSecond;
    }

    @Override
    /**
     * 尝试申请一个令牌。
     *
     * 如果时间已经进入新的 1 秒窗口，就重置计数；
     * 然后用自增后的计数和阈值比较，决定本次请求是否通过。
     */
    public boolean tryAcquire() {
        long now = System.currentTimeMillis();
        long currentWindowStart = windowStartMillis.get();
        if (now - currentWindowStart >= 1000
                && windowStartMillis.compareAndSet(currentWindowStart, now)) {
            currentPermits.set(0);
        }
        return currentPermits.incrementAndGet() <= permitsPerSecond;
    }
}
