package com.rpc.core.resilience.ratelimit;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

public class FixedWindowRateLimiter implements RateLimiter {
    private final int permitsPerSecond;
    private final AtomicLong windowStartMillis = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger currentPermits = new AtomicInteger(0);

    public FixedWindowRateLimiter(int permitsPerSecond) {
        this.permitsPerSecond = Math.max(1, permitsPerSecond);
    }

    public int getPermitsPerSecond() {
        return permitsPerSecond;
    }

    @Override
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

