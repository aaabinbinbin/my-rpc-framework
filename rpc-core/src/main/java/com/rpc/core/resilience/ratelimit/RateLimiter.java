package com.rpc.core.resilience.ratelimit;

/**
 * 限流器抽象。
 *
 * 所处阶段：provider 过滤器或其他资源保护点判断是否允许当前请求继续执行时。
 * 主要职责：屏蔽具体限流算法，当前实现为固定窗口，后续可扩展令牌桶/漏桶。
 */
public interface RateLimiter {
    /**
     * 尝试获取一次请求许可。
     *
     * 返回 true 表示放行，false 表示当前窗口资源不足，应由上层返回限流错误或触发降级。
     */
    boolean tryAcquire();
}

