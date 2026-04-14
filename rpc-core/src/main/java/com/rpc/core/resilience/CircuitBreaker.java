package com.rpc.core.resilience;

/**
 * 熔断器实现的统一契约。
 *
 * 所处阶段：consumer 发起远程调用前后使用。
 * 调用前通过 allowRequest 判断是否允许继续请求；
 * 调用完成后通过 recordSuccess / recordFailure 更新健康状态。
 */
public interface CircuitBreaker {
    /** 判断当前请求是否允许通过；OPEN 状态通常会快速拒绝。 */
    boolean allowRequest();

    /** 记录一次成功调用，用于恢复或维持 CLOSED 状态。 */
    void recordSuccess();

    /** 记录一次失败调用，用于按失败率打开熔断器。 */
    void recordFailure();

    /** 获取当前熔断状态。 */
    CircuitBreakerState getState();

    /** 手动重置熔断器状态和统计窗口。 */
    void reset();
}
