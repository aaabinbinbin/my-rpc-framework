package com.rpc.core.resilience;

/**
 * 熔断器实现的统一契约。
 */
public interface CircuitBreaker {
    boolean allowRequest();

    void recordSuccess();

    void recordFailure();

    CircuitBreakerState getState();

    void reset();
}
