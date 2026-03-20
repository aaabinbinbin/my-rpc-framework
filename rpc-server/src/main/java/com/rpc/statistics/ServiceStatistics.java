package com.rpc.statistics;

import lombok.Data;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * 服务统计信息
 * 记录服务的调用次数、成功/失败次数、平均响应时间等
 */
@Slf4j
@Data
public class ServiceStatistics {
    /** 服务名称 */
    private final String serviceName;

    /** 总调用次数（原子操作） */
    private final LongAdder totalCalls = new LongAdder();

    /** 成功次数（原子操作） */
    private final LongAdder successCalls = new LongAdder();

    /** 失败次数（原子操作） */
    private final LongAdder failedCalls = new LongAdder();

    /** 总响应时间（毫秒） */
    private final AtomicLong totalTimeCost = new AtomicLong(0);

    /** 最后一次调用时间 */
    private final AtomicLong lastCallTime = new AtomicLong(0);

    public ServiceStatistics(String serviceName) {
        this.serviceName = serviceName;
    }

    /**
     * 记录调用开始
     */
    public void recordStart() {
        totalCalls.increment();
        lastCallTime.set(System.currentTimeMillis());
    }

    /**
     * 记录调用成功
     * @param startTime 开始时间
     */
    public void recordSuccess(long startTime) {
        successCalls.increment();
        long cost = System.currentTimeMillis() - startTime;
        totalTimeCost.addAndGet(cost);
    }

    /**
     * 记录调用失败
     * @param startTime 开始时间
     */
    public void recordFailed(long startTime) {
        failedCalls.increment();
        long cost = System.currentTimeMillis() - startTime;
        totalTimeCost.addAndGet(cost);
    }

    /**
     * 获取平均响应时间（毫秒）
     */
    public long getAverageResponseTime() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0;
        }
        return totalTimeCost.get() / total;
    }

    /**
     * 获取成功率
     */
    public double getSuccessRate() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0.0;
        }
        return (double) successCalls.sum() / total * 100;
    }

    /**
     * 获取失败率
     */
    public double getFailureRate() {
        long total = totalCalls.sum();
        if (total == 0) {
            return 0.0;
        }
        return (double) failedCalls.sum() / total * 100;
    }

    /**
     * 打印统计信息
     */
    public void printStatistics() {
        log.info("========== 服务统计：{} ==========", serviceName);
        log.info("总调用次数：{}", totalCalls.sum());
        log.info("成功次数：{}", successCalls.sum());
        log.info("失败次数：{}", failedCalls.sum());
        log.info("成功率：{}%", getSuccessRate());
        log.info("失败率：{}%", getFailureRate());
        log.info("平均响应时间：{} ms", getAverageResponseTime());
        log.info("==========================================");
    }

    /**
     * 重置统计数据
     */
    public void reset() {
        totalCalls.reset();
        successCalls.reset();
        failedCalls.reset();
        totalTimeCost.set(0);
        lastCallTime.set(0);
    }
}
