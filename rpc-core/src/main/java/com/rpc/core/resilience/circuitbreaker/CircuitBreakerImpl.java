package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 默认的内存版熔断器实现。
 */
@Slf4j
public class CircuitBreakerImpl implements CircuitBreaker {
    private final String serviceName;
    private final float failureRateThreshold;
    private final int minNumberOfCalls;
    private final long waitDurationInOpenState;
    private final int permittedNumberOfCallsInHalfOpenState;

    private final AtomicInteger totalCalls = new AtomicInteger(0);
    private final AtomicInteger failedCalls = new AtomicInteger(0);
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;
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
    public boolean allowRequest() {
        CircuitBreakerState currentState = getState();

        if (currentState == CircuitBreakerState.OPEN) {
        // OPEN 状态会阻断流量，直到等待窗口结束；
        // 随后在 HALF_OPEN 状态放行少量探测流量，用来判断是否恢复。
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
        // HALF_OPEN 只允许有限数量的探测请求，
        // 而不是立刻恢复全部流量。
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
    public void recordSuccess() {
        totalCalls.incrementAndGet();

        if (getState() == CircuitBreakerState.HALF_OPEN) {
        // 探测成功会被视为恢复信号，并关闭熔断器。
            log.info("Circuit breaker transitions HALF_OPEN -> CLOSED: {}", serviceName);
            state = CircuitBreakerState.CLOSED;
            resetStatistics();
        }
    }

    @Override
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        CircuitBreakerState currentState = getState();
        if (currentState == CircuitBreakerState.HALF_OPEN) {
        // 探测失败说明下游仍然不健康，因此重新回到 OPEN。
            log.warn("Circuit breaker transitions HALF_OPEN -> OPEN: {}", serviceName);
            state = CircuitBreakerState.OPEN;
            return;
        }

        if (currentState == CircuitBreakerState.CLOSED) {
            checkAndUpdateState();
        }
    }

    @Override
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
    public void reset() {
        state = CircuitBreakerState.CLOSED;
        resetStatistics();
        log.info("Circuit breaker reset: {}", serviceName);
    }

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
        // 进入 OPEN 后，会从零开始新的统计窗口。
            resetStatistics();
        }
    }

    private void resetStatistics() {
        totalCalls.set(0);
        failedCalls.set(0);
        halfOpenCalls.set(0);
    }
}
