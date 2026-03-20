package com.rpc.transport.netty.server.statistics;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 统计服务信息
 */
@Slf4j
public class StatisticsManager {
    private static final StatisticsManager INSTANCE = new StatisticsManager();
    // 添加开关标志，默认启用
    private static volatile boolean enabled = false;

    private final Map<String, ServiceStatistics> statisticsMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();

    private StatisticsManager() {
        if (enabled) {
            // 启动定时任务（每分钟打印一次统计）
            startPeriodicReport(1, 10, TimeUnit.SECONDS);
        }
    }

    public static StatisticsManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册服务统计
     */
    public void register(String serviceName) {
        ServiceStatistics stats = new ServiceStatistics(serviceName);
        statisticsMap.put(serviceName, stats);
    }

    /**
     * 移除服务统计
     */
    public void remove(String serviceName) {
        ServiceStatistics stats = statisticsMap.remove(serviceName);
        if (stats != null) {
            stats.reset();
            log.info("已移除服务统计：{}", serviceName);
        }
    }

    /**
     * 获取服务统计
     */
    public ServiceStatistics getStatistics(String serviceName) {
        return statisticsMap.get(serviceName);
    }

    /**
     * 启动定期报告
     */
    public void startPeriodicReport(long initialDelay, long period, TimeUnit unit) {
        statsExecutor.scheduleAtFixedRate(() -> {
            printAllStatistics();
        }, initialDelay, period, unit);
    }

    /**
     * 打印所有统计信息
     */
    public void printAllStatistics() {
        log.info("\n========== 所有服务统计信息 ==========");
        for (ServiceStatistics stats : statisticsMap.values()) {
            stats.printStatistics();
        }
        log.info("=======================================\n");
    }

    /**
     * 关闭统计管理器
     */
    public void shutdown() {
        if (!statsExecutor.isShutdown()) {
            statsExecutor.shutdown();
        }
        statisticsMap.clear();
    }
}
