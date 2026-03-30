package com.rpc.core.resilience;

/**
 * 熔断器运行时状态。
 */
public enum CircuitBreakerState {
    CLOSED,
    OPEN,
    HALF_OPEN
}
