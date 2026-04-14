package com.rpc.core.resilience.circuitbreaker;

import com.rpc.core.resilience.CircuitBreaker;
import com.rpc.core.resilience.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 熔断器默认实现。
 *
 * 所处阶段：consumer 调用下游服务或 provider 实例前，用于判断是否允许继续请求。
 * 状态流转：
 * - CLOSED：正常放行并统计成功/失败。
 * - OPEN：快速失败，等待冷却时间。
 * - HALF_OPEN：放少量探测请求，成功恢复，失败重新打开。
 *
 * 注意事项：
 * - 本实现使用原子变量维护计数和状态，状态切换处用 transitionLock 保证复合逻辑一致。
 * - 半开状态必须限制探测并发，避免下游刚恢复时被瞬时流量再次打垮。
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
    private final AtomicInteger halfOpenSuccessCalls = new AtomicInteger(0);
    private final Object transitionLock = new Object();

    private final AtomicReference<CircuitBreakerState> state = new AtomicReference<>(CircuitBreakerState.CLOSED);
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

    /** 判断当前请求是否允许通过；OPEN 状态到达等待时间后会尝试进入 HALF_OPEN。 */
    @Override
    public boolean allowRequest() {
        CircuitBreakerState currentState = state.get();
        if (currentState == CircuitBreakerState.CLOSED) {
            return true;
        }

        synchronized (transitionLock) {
            currentState = state.get();
            if (currentState == CircuitBreakerState.CLOSED) {
                return true;
            }

            if (currentState == CircuitBreakerState.OPEN && !tryTransitionToHalfOpenLocked()) {
                return false;
            }

            int currentCalls = halfOpenCalls.incrementAndGet();
            if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {
                return true;
            }
            log.debug("Half-open probe limit reached for {}", serviceName);
            return false;
        }
    }

    /** 记录一次成功调用；HALF_OPEN 下成功会推动熔断器恢复 CLOSED。 */
    @Override
    public void recordSuccess() {
        totalCalls.incrementAndGet();

        if (state.get() == CircuitBreakerState.HALF_OPEN) {
            halfOpenSuccessCalls.incrementAndGet();
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.CLOSED)) {
                log.info("Circuit breaker transitions HALF_OPEN -> CLOSED: {}", serviceName);
                resetStatistics();
            }
        }
    }

    /** 记录一次失败调用；CLOSED 下按失败率判断是否打开，HALF_OPEN 下失败会立即重新打开。 */
    @Override
    public void recordFailure() {
        totalCalls.incrementAndGet();
        failedCalls.incrementAndGet();
        lastFailureTime = System.currentTimeMillis();

        CircuitBreakerState currentState = state.get();
        if (currentState == CircuitBreakerState.HALF_OPEN) {
            if (state.compareAndSet(CircuitBreakerState.HALF_OPEN, CircuitBreakerState.OPEN)) {
                log.warn("Circuit breaker transitions HALF_OPEN -> OPEN: {}", serviceName);
            }
            return;
        }

        if (currentState == CircuitBreakerState.CLOSED) {
            checkAndUpdateState();
        }
    }

    /** 获取当前状态；OPEN 状态下会顺便检查是否可以进入 HALF_OPEN。 */
    @Override
    public CircuitBreakerState getState() {
        CircuitBreakerState currentState = state.get();
        if (currentState != CircuitBreakerState.OPEN) {
            return currentState;
        }

        synchronized (transitionLock) {
            currentState = state.get();
            if (currentState != CircuitBreakerState.OPEN) {
                return currentState;
            }

            if (tryTransitionToHalfOpenLocked()) {
                return CircuitBreakerState.HALF_OPEN;
            }
            return state.get();
        }
    }

    @Override
    public void reset() {
        state.set(CircuitBreakerState.CLOSED);
        resetStatistics();
        log.info("Circuit breaker reset: {}", serviceName);
    }

    /** 在持有 transitionLock 的前提下尝试从 OPEN 切换到 HALF_OPEN。 */
    private boolean tryTransitionToHalfOpenLocked() {
        long elapsed = System.currentTimeMillis() - lastFailureTime;
        if (elapsed < waitDurationInOpenState) {
            return false;
        }

        if (state.compareAndSet(CircuitBreakerState.OPEN, CircuitBreakerState.HALF_OPEN)) {
            log.info("Circuit breaker transitions OPEN -> HALF_OPEN: {}", serviceName);
            halfOpenCalls.set(0);
            halfOpenSuccessCalls.set(0);
            return true;
        }
        return state.get() == CircuitBreakerState.HALF_OPEN;
    }

    /** 根据当前窗口内失败率判断是否需要从 CLOSED 切换到 OPEN。 */
    private void checkAndUpdateState() {
        int total = totalCalls.get();
        int failed = failedCalls.get();

        if (total < minNumberOfCalls) {
            return;
        }

        float failureRate = (float) failed / total * 100;
        log.debug("Circuit breaker stats service={}, total={}, failed={}, failureRate={}%",
                serviceName, total, failed, failureRate);

        if (failureRate >= failureRateThreshold
                && state.compareAndSet(CircuitBreakerState.CLOSED, CircuitBreakerState.OPEN)) {
            log.warn("Circuit breaker opens for {}: failureRate={}%, threshold={}%",
                    serviceName, failureRate, failureRateThreshold);
            resetStatistics();
        }
    }

    /** 重置统计窗口；状态切换完成后重新开始计算失败率。 */
    private void resetStatistics() {
        totalCalls.set(0);
        failedCalls.set(0);
        halfOpenCalls.set(0);
        halfOpenSuccessCalls.set(0);
    }
}
