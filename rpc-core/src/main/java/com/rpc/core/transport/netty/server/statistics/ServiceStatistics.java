package com.rpc.core.transport.netty.server.statistics;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 面向单个服务的轻量运行时统计信息。
 */
@Slf4j
@Data
public class ServiceStatistics {
    private final String serviceName;
    private final LongAdder totalCalls = new LongAdder();
    private final LongAdder successCalls = new LongAdder();
    private final LongAdder failedCalls = new LongAdder();
    private final AtomicLong totalTimeCost = new AtomicLong(0);
    private final AtomicLong lastCallTime = new AtomicLong(0);

    public ServiceStatistics(String serviceName) {
        this.serviceName = serviceName;
    }

    public void recordStart() {
        totalCalls.increment();
        lastCallTime.set(System.currentTimeMillis());
    }

    public void recordSuccess(long startTime) {
        successCalls.increment();
        totalTimeCost.addAndGet(System.currentTimeMillis() - startTime);
    }

    public void recordFailed(long startTime) {
        failedCalls.increment();
        totalTimeCost.addAndGet(System.currentTimeMillis() - startTime);
    }

    public long getAverageResponseTime() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0;
        }
        return totalTimeCost.get() / total;
    }

    public double getSuccessRate() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0.0;
        }
        return (double) successCalls.sum() / total * 100;
    }

    public double getFailureRate() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0.0;
        }
        return (double) failedCalls.sum() / total * 100;
    }

    public void printStatistics() {
        log.info("========== Service Statistics: {} ==========", serviceName);
        log.info("totalCalls={}", totalCalls.sum());
        log.info("successCalls={}", successCalls.sum());
        log.info("failedCalls={}", failedCalls.sum());
        log.info("successRate={}%", getSuccessRate());
        log.info("failureRate={}%", getFailureRate());
        log.info("averageResponseTime={}ms", getAverageResponseTime());
        log.info("============================================");
    }

    public void reset() {
        totalCalls.reset();
        successCalls.reset();
        failedCalls.reset();
        totalTimeCost.set(0);
        lastCallTime.set(0);
    }
}
