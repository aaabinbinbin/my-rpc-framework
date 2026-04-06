package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的内存版熔断器实现。
 *
 * 它维护 CLOSED、OPEN、HALF_OPEN 三种状态：
 * 1. CLOSED：正常放流量，同时统计失败率。
 * 2. OPEN：直接拒绝请求，等待恢复窗口到期。
 * 3. HALF_OPEN：放少量探测请求，根据探测结果决定恢复还是重新熔断。
 */
@Slf4j
public class CircuitBreakerImpl implements CircuitBreaker {
    /** 这里只是一个标识名，可能是 service:xxx，也可能是 instance:xxx。 */
    private final String serviceName;
    /** 触发熔断的失败率阈值。 */
    private final float failureRateThreshold;
    /** 至少调用多少次后，才开始进行失败率判断。 */
    private final int minNumberOfCalls;
    /** OPEN 状态保持多久后，可以转为 HALF_OPEN。 */
    private final long waitDurationInOpenState;
    /** HALF_OPEN 状态下允许放过多少个探测请求。 */
    private final int permittedNumberOfCallsInHalfOpenState;

    /** 当前统计窗口内的调用总数。 */
    private final AtomicInteger totalCalls = new AtomicInteger(0);
    /** 当前统计窗口内的失败数。 */
    private final AtomicInteger failedCalls = new AtomicInteger(0);
    /** HALF_OPEN 状态下已经放过的探测请求数。 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

    /** 当前熔断状态。 */
    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
    /** 最近一次失败时间，用来计算 OPEN 持续时长。 */
    private volatile long lastFailureTime = 0L;

    public CircuitBreakerImpl(String serviceName,
                              float failureRateThreshold,
                              int minNumberOfCalls,
                              long waitDurationInOpenState,
                              int permittedNumberOfCallsInHalfOpenState) {
        this.serviceName = serviceName;
        this.failureRateThreshold = failureRateThreshold;
        this.minNumberOfCalls = minNumberOfCalls;
        this.waitDurationInOpenState = waitDurationInOpenState;
        this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
    }

    @Override
    /**
     * 判断当前请求是否允许通过。
     * 这是 consumer 在真正发请求前会调用的核心入口。
     */
    public boolean allowRequest() {
        CircuitBreakerState currentState = getState();

        if (currentState == CircuitBreakerState.OPEN) {
            // OPEN 状态会直接拦住流量，直到等待窗口结束；
            // 之后进入 HALF_OPEN，放少量探测流量判断是否恢复。
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed >= waitDurationInOpenState) {
                log.info("Circuit breaker transitions OPEN -> HALF_OPEN: {}", serviceName);
                state = CircuitBreakerState.HALF_OPEN;
                halfOpenCalls.set(0);
                return true;
            }
            return false;
        }

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // HALF_OPEN 只允许有限数量的探测请求，而不是立刻恢复全部流量。
            int currentCalls = halfOpenCalls.incrementAndGet();
            if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {
                return true;
            }
            log.debug("Half-open probe limit reached for {}", serviceName);
            return false;
        }

        return true;
    }

    @Override
    /** 记录一次成功调用，并在 HALF_OPEN 场景下尝试恢复为 CLOSED。 */
    public void recordSuccess() {
        totalCalls.incrementAndGet();

        if (getState() == CircuitBreakerState.HALF_OPEN) {
            // 探测成功可以看作恢复信号，因此关闭熔断器并重置统计窗口。
            log.info("Circuit breaker transitions HALF_OPEN -> CLOSED: {}", serviceName);
            state = CircuitBreakerState.CLOSED;
            resetStatistics();
        }
    }

    @Override
    /** 记录一次失败调用，并根据当前状态决定是否进入或维持熔断。 */
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        CircuitBreakerState currentState = getState();
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 探测失败说明下游依然不健康，因此重新回到 OPEN。
            log.warn("Circuit breaker transitions HALF_OPEN -> OPEN: {}", serviceName);
            state = CircuitBreakerState.OPEN;
            return;
        }

        if (currentState == CircuitBreakerState.CLOSED) {
            checkAndUpdateState();
        }
    }

    @Override
    /** 获取当前状态，并在 OPEN 超时后自动转换到 HALF_OPEN。 */
    public CircuitBreakerState getState() {
        if (state == CircuitBreakerState.OPEN) {
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed >= waitDurationInOpenState) {
                state = CircuitBreakerState.HALF_OPEN;
                halfOpenCalls.set(0);
            }
        }
        return state;
    }

    @Override
    /** 手动重置熔断器，常用于恢复或测试。 */
    public void reset() {
        state = CircuitBreakerState.CLOSED;
        resetStatistics();
        log.info("Circuit breaker reset: {}", serviceName);
    }

    /** 在 CLOSED 状态下检查失败率，必要时切换为 OPEN。 */
    private void checkAndUpdateState() {
        int total = totalCalls.get();
        int failed = failedCalls.get();

        if (total < minNumberOfCalls) {
            return;
        }

        float failureRate = (float) failed / total * 100;
        log.debug("Circuit breaker stats service={}, total={}, failed={}, failureRate={}%",
                serviceName, total, failed, failureRate);

        if (failureRate >= failureRateThreshold) {
            log.warn("Circuit breaker opens for {}: failureRate={}%, threshold={}%",
                    serviceName, failureRate, failureRateThreshold);
            state = CircuitBreakerState.OPEN;
            // 进入 OPEN 后，后续会开始新的统计窗口。
            resetStatistics();
        }
    }

    /** 重置内部计数器。 */
    private void resetStatistics() {
        totalCalls.set(0);
        failedCalls.set(0);
        halfOpenCalls.set(0);
    }
}
