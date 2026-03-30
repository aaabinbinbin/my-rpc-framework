package com.rpc.core.transport.netty.server.statistics;

import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * 维护 Netty 服务端的按服务运行时统计信息。
 */
@Slf4j
public class StatisticsManager {
    private static final StatisticsManager INSTANCE = new StatisticsManager();
    private static volatile boolean enabled = false;

    private final Map<String, ServiceStatistics> statisticsMap = new ConcurrentHashMap<>();
    private final ScheduledExecutorService statsExecutor = Executors.newSingleThreadScheduledExecutor();

    private StatisticsManager() {
        if (enabled) {
            startPeriodicReport(1, 10, TimeUnit.SECONDS);
        }
    }

    public static StatisticsManager getInstance() {
        return INSTANCE;
    }

    public void register(String serviceName) {
        statisticsMap.put(serviceName, new ServiceStatistics(serviceName));
    }

    public void remove(String serviceName) {
        ServiceStatistics stats = statisticsMap.remove(serviceName);
        if (stats != null) {
            stats.reset();
            log.info("Removed statistics for service {}", serviceName);
        }
    }

    public ServiceStatistics getStatistics(String serviceName) {
        return statisticsMap.get(serviceName);
    }

    public void startPeriodicReport(long initialDelay, long period, TimeUnit unit) {
        // 定时打印是一个附加观测能力，不参与核心调用链。
        statsExecutor.scheduleAtFixedRate(this::printAllStatistics, initialDelay, period, unit);
    }

    public void printAllStatistics() {
        log.info("\n========== Netty Server Statistics ==========");
        for (ServiceStatistics stats : statisticsMap.values()) {
            stats.printStatistics();
        }
        log.info("=============================================\n");
    }

    public void shutdown() {
        if (!statsExecutor.isShutdown()) {
            statsExecutor.shutdown();
        }
        statisticsMap.clear();
    }
}
