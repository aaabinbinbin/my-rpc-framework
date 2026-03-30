package com.rpc.core.resilience.ratelimit;

public interface RateLimiter {
    boolean tryAcquire();
}

