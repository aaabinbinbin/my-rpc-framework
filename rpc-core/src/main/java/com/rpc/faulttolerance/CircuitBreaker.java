package com.rpc.faulttolerance;

/**
 * 熔断器接口
 * 定义熔断器的基本行为
 */
public interface CircuitBreaker {
    /**
     * 判断是否允许请求通过
     * @return true-允许，false-拒绝
     */
    boolean allowRequest();

    /**
     * 记录成功调用
     */
    void recordSuccess();

    /**
     * 记录失败调用
     */
    void recordFailure();

    /**
     * 获取当前状态
     * @return 熔断器状态
     */
    CircuitBreakerState getState();

    /**
     * 手动重置熔断器
     */
    void reset();
}
