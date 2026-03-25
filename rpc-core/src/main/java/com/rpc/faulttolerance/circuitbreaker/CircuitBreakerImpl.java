package com.rpc.faulttolerance.circuitbreaker;

import com.rpc.faulttolerance.CircuitBreaker;
import com.rpc.faulttolerance.CircuitBreakerState;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * 熔断器实现
 * 基于滑动窗口统计失败率
 */
@Slf4j
public class CircuitBreakerImpl implements CircuitBreaker {
    /** 服务名称 */
    private final String serviceName;

    /** 失败率阈值（百分比） */
    private final float failureRateThreshold;

    /** 最小请求数（达到此数量才开始统计） */
    private final int minNumberOfCalls;

    /** 熔断器打开后的休眠时间（毫秒） */
    private final long waitDurationInOpenState;

    /** 半开状态允许的最大请求数 */
    private final int permittedNumberOfCallsInHalfOpenState;

    // ========== 统计数据 ==========

    /** 总请求数（滑动窗口） */
    private final AtomicInteger totalCalls = new AtomicInteger(0);

    /** 失败请求数（滑动窗口） */
    private final AtomicInteger failedCalls = new AtomicInteger(0);

    /** 熔断器状态 */
    private volatile CircuitBreakerState state = CircuitBreakerState.CLOSED;

    /** 熔断器打开的时间戳 */
    private volatile long lastFailureTime = 0;

    /** 半开状态已通过的请求数 */
    private final AtomicInteger halfOpenCalls = new AtomicInteger(0);

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
            // 检查是否可以进入半开状态
            long elapsed = System.currentTimeMillis() - lastFailureTime;
            if (elapsed >= waitDurationInOpenState) {
                log.info("熔断器从 OPEN 进入 HALF_OPEN: {}", serviceName);
                state = CircuitBreakerState.HALF_OPEN;
                halfOpenCalls.set(0);
                return true;
            }
            return false;
        }

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态限制请求数
            int currentCalls = halfOpenCalls.incrementAndGet();
            if (currentCalls <= permittedNumberOfCallsInHalfOpenState) {
                return true;
            }
            log.debug("半开状态请求数超限，拒绝：{}", serviceName);
            return false;
        }

        // CLOSED 状态允许所有请求
        return true;
    }

    @Override
    public void recordSuccess() {
        totalCalls.incrementAndGet();

        CircuitBreakerState currentState = getState();

        if (currentState == CircuitBreakerState.HALF_OPEN) {
            // 半开状态下成功，进入关闭状态
            log.info("熔断器从 HALF_OPEN 进入 CLOSED: {}", serviceName);
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
            // 半开状态下失败，重新打开
            log.warn("熔断器从 HALF_OPEN 重新进入 OPEN: {}", serviceName);
            state = CircuitBreakerState.OPEN;
        } else if (currentState == CircuitBreakerState.CLOSED) {
            // 关闭状态下检查是否达到阈值
            checkAndUpdateState();
        }
    }

    @Override
    public CircuitBreakerState getState() {
        if (state == CircuitBreakerState.OPEN) {
            // 检查是否可以进入半开状态
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
        log.info("熔断器已重置：{}", serviceName);
    }

    /**
     * 检查并更新熔断器状态
     */
    private void checkAndUpdateState() {
        int total = totalCalls.get();
        int failed = failedCalls.get();

        // 未达到最小请求数，不统计
        if (total < minNumberOfCalls) {
            return;
        }

        // 计算失败率
        float failureRate = (float) failed / total * 100;

        log.debug("熔断器统计：service={}, total={}, failed={}, failureRate={}%",
                serviceName, total, failed, failureRate);

        // 超过阈值，打开熔断器
        if (failureRate >= failureRateThreshold) {
            log.warn("失败率超阈值，熔断器打开：{} (失败率={}%, 阈值={}%)",
                    serviceName, failureRate, failureRateThreshold);
            state = CircuitBreakerState.OPEN;
            resetStatistics();
        }
    }

    /**
     * 重置统计数据
     */
    private void resetStatistics() {
        totalCalls.set(0);
        failedCalls.set(0);
        halfOpenCalls.set(0);
    }
}
