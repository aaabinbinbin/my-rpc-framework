package com.rpc.core.transport.netty.server.statistics;

import com.rpc.core.observability.metrics.ClientRuntimeMetrics;
import com.rpc.core.observability.metrics.ClientRuntimeMetricsManager;
import lombok.extern.slf4j.Slf4j;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Netty 服务端统计管理器。
 *
 * 所处阶段：provider 注册服务、处理请求或需要定期打印统计信息时。
 * 主要职责：维护服务维度统计对象，并定期输出服务端和客户端运行时关键指标。
 */
@Slf4j
public class StatisticsManager {
    /** JVM 内共享统计管理器。 */
    private static final StatisticsManager INSTANCE = new StatisticsManager();

    /** serviceName -> 服务统计对象。 */
    private final Map<String, ServiceStatistics> statisticsMap = new ConcurrentHashMap<>();
    /** 定期打印统计信息的单线程调度器。 */
    private volatile ScheduledExecutorService statsExecutor;
    /** 防止重复启动定期报告。 */
    private volatile boolean reportingEnabled;

    /** 单例类不允许外部实例化。 */
    private StatisticsManager() {
    }

    /**
     * 获取统计管理器单例。
     */
    public static StatisticsManager getInstance() {
        return INSTANCE;
    }

    /**
     * 注册某个服务的统计对象。
     */
    public void register(String serviceName) {
        statisticsMap.put(serviceName, new ServiceStatistics(serviceName));
    }

    /**
     * 移除某个服务的统计对象。
     *
     * 适用场景：服务注销或服务端关闭时清理内存状态。
     */
    public void remove(String serviceName) {
        ServiceStatistics stats = statisticsMap.remove(serviceName);
        if (stats != null) {
            stats.reset();
            log.info("Removed statistics for service {}", serviceName);
        }
    }

    /**
     * 获取服务统计对象。
     */
    public ServiceStatistics getStatistics(String serviceName) {
        return statisticsMap.get(serviceName);
    }

    /**
     * 启动定期统计日志输出。
     *
     * 边界处理：已经启动时直接返回；调度器关闭后允许重新创建。
     */
    public synchronized void startPeriodicReport(long initialDelay, long period, TimeUnit unit) {
        if (reportingEnabled) {
            return;
        }
        if (statsExecutor == null || statsExecutor.isShutdown()) {
            statsExecutor = Executors.newSingleThreadScheduledExecutor();
        }
        statsExecutor.scheduleAtFixedRate(this::printAllStatistics, initialDelay, period, unit);
        reportingEnabled = true;
    }

    /**
     * 输出全部服务统计和客户端运行时指标。
     */
    public void printAllStatistics() {
        log.info("\n========== Netty Server Statistics ==========");
        for (ServiceStatistics stats : statisticsMap.values()) {
            stats.printStatistics();
        }
        printClientRuntimeMetrics();
        log.info("=============================================\n");
    }

    /**
     * 输出客户端运行时指标，便于在服务端集成测试和压测日志中统一观察。
     */
    private void printClientRuntimeMetrics() {
        ClientRuntimeMetrics.Snapshot snapshot = ClientRuntimeMetricsManager.getInstance().snapshot();
        log.info("Client runtime metrics: inflightRejects={}, pendingRejects={}, totalConnectionRejects={}, " +
                        "timeoutClears={}, reconnectScheduled={}, reconnectSucceeded={}, reconnectFailed={}",
                snapshot.getInflightLimitRejections(),
                snapshot.getPendingLimitRejections(),
                snapshot.getTotalConnectionLimitRejections(),
                snapshot.getRequestTimeoutClearCount(),
                snapshot.getReconnectScheduledCount(),
                snapshot.getReconnectSucceededCount(),
                snapshot.getReconnectFailedCount());
    }

    /**
     * 关闭统计任务并清空统计对象。
     *
     * 注意事项：方法幂等，可在服务端关闭流程中安全重复调用。
     */
    public synchronized void shutdown() {
        reportingEnabled = false;
        if (statsExecutor != null && !statsExecutor.isShutdown()) {
            statsExecutor.shutdownNow();
        }
        statsExecutor = null;
        statisticsMap.clear();
    }
}
