package com.rpc.faulttolerance;

/**
 * 熔断器状态枚举
 */
public enum CircuitBreakerState {
    /** 关闭状态 - 正常 */
    CLOSED,

    /** 打开状态 - 熔断 */
    OPEN,

    /** 半开状态 - 探测 */
    HALF_OPEN
}
