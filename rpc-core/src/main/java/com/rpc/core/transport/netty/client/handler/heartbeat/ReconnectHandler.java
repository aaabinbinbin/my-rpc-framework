package com.rpc.core.transport.netty.client.handler.heartbeat;

import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.observability.metrics.ClientRuntimeMetricsManager;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.core.transport.netty.client.scheduler.ReconnectSharedScheduler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Netty 客户端断线重连处理器。
 *
 * 所处阶段：客户端 channelInactive 或 pipeline 异常后。
 * 主要职责：从连接池移除失效连接，判断目标地址是否仍在服务目录中，按退避策略调度重连。
 *
 * 注意事项：重连只针对仍然可发现的 provider，避免服务下线后客户端持续无意义重连。
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {
    /** 延迟获取连接池，避免 handler 构造时连接池尚未初始化。 */
    private final Supplier<ConnectionPool> connectionPoolSupplier;
    /** 判断某个地址是否仍可重连，通常来自 ServiceDirectory 当前快照。 */
    private final Predicate<InetSocketAddress> reconnectableAddressPredicate;
    /** 客户端关闭标记，关闭过程中不再调度重连。 */
    private final AtomicBoolean closing;
    /** 重连总开关。 */
    private final boolean reconnectEnabled;
    /** 最大重试次数。 */
    private final int maxRetryTimes;
    /** 首次重连延迟秒数。 */
    private final int initialDelaySeconds;
    /** 最大退避延迟秒数。 */
    private final int maxDelaySeconds;
    /** 是否启用随机抖动。 */
    private final boolean jitterEnabled;
    /** 抖动最小秒数。 */
    private final int jitterMinSeconds;
    /** 抖动最大秒数。 */
    private final int jitterMaxSeconds;
    /** 重连任务共享调度器。 */
    private final ReconnectSharedScheduler scheduler = ReconnectSharedScheduler.getInstance();
    /** 当前连续重连失败次数。 */
    private final AtomicInteger retryCount = new AtomicInteger(0);
    /** 防止同一个 handler 重复调度多个重连任务。 */
    private final AtomicBoolean reconnectScheduled = new AtomicBoolean(false);
    /** 防止共享调度器重复 release。 */
    private final AtomicBoolean schedulerReleased = new AtomicBoolean(false);
    /** 当前已调度但尚未执行或取消的重连任务。 */
    private volatile ScheduledFuture<?> reconnectTask;

    /**
     * 创建重连处理器。
     */
    public ReconnectHandler(Supplier<ConnectionPool> connectionPoolSupplier,
                            Predicate<InetSocketAddress> reconnectableAddressPredicate,
                            AtomicBoolean closing,
                            RpcClientConfig config) {
        this.connectionPoolSupplier = connectionPoolSupplier;
        this.reconnectableAddressPredicate = reconnectableAddressPredicate;
        this.closing = closing;
        this.reconnectEnabled = config.isReconnectEnabled();
        this.maxRetryTimes = config.getReconnectMaxRetryTimes();
        this.initialDelaySeconds = config.getReconnectInitialDelaySeconds();
        this.maxDelaySeconds = config.getReconnectMaxDelaySeconds();
        this.jitterEnabled = config.isReconnectJitterEnabled();
        this.jitterMinSeconds = config.getReconnectJitterMinSeconds();
        this.jitterMaxSeconds = config.getReconnectJitterMaxSeconds();
    }

    /**
     * channel 失活后移除旧连接并尝试调度重连。
     *
     * 边界处理：客户端关闭、连接池为空、地址已从服务目录移除时都不会重连。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (closing.get()) {
            log.debug("Client is closing, skip reconnect for {}", ctx.channel().remoteAddress());
            closeScheduler();
            super.channelInactive(ctx);
            return;
        }

        ConnectionPool connectionPool = connectionPoolSupplier.get();
        if (connectionPool != null && ctx.channel().remoteAddress() instanceof InetSocketAddress address) {
            log.info("Channel inactive, preparing reconnect: {}", address);
            connectionPool.removeConnection(address.getHostString(), address.getPort());
            if (!reconnectableAddressPredicate.test(address)) {
                log.info("Skip reconnect because address is no longer available in service directory: {}", address);
                closeScheduler();
                super.channelInactive(ctx);
                return;
            }
            scheduleReconnect(address.getHostString(), address.getPort());
        }
        super.channelInactive(ctx);
    }

    /**
     * 调度下一次重连任务。
     *
     * 边界处理：超过最大重试次数后释放调度器；重复调度会被 reconnectScheduled 拦截。
     */
    private void scheduleReconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }
        if (!reconnectEnabled) {
            log.debug("Reconnect is disabled, skip reconnect scheduling");
            closeScheduler();
            return;
        }
        if (!reconnectScheduled.compareAndSet(false, true)) {
            log.debug("Reconnect already scheduled for {}:{}", host, port);
            return;
        }

        int currentRetry = retryCount.get();
        if (currentRetry >= maxRetryTimes) {
            reconnectScheduled.set(false);
            log.error("Reconnect retry count exceeded limit: {}", maxRetryTimes);
            closeScheduler();
            return;
        }

        int delay = calculateBackoffDelay(currentRetry);
        ClientRuntimeMetricsManager.getInstance().getMetrics().recordReconnectScheduled();
        log.info("Schedule reconnect in {} seconds, retry={}", delay, currentRetry + 1);
        try {
            reconnectTask = scheduler.schedule(() -> {
                reconnectScheduled.set(false);
                if (closing.get()) {
                    closeScheduler();
                    return;
                }
                reconnect(host, port);
            }, delay, TimeUnit.SECONDS);
        } catch (RejectedExecutionException e) {
            reconnectScheduled.set(false);
            if (!closing.get()) {
                log.debug("Reconnect scheduler rejected task for {}:{}: {}", host, port, e.getMessage());
            }
        }
    }

    /**
     * 执行一次重连尝试。
     *
     * 注意事项：真正建立连接交给 ConnectionPool#getConnection，成功后会重置连续失败次数。
     */
    private void reconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }

        log.info("Trying to reconnect to {}:{}", host, port);
        try {
            ConnectionPool connectionPool = connectionPoolSupplier.get();
            if (connectionPool == null) {
                log.debug("Connection pool is unavailable, skip reconnect to {}:{}", host, port);
                return;
            }
            connectionPool.getConnection(host, port);
            retryCount.set(0);
            ClientRuntimeMetricsManager.getInstance().getMetrics().recordReconnectSucceeded();
            log.info("Reconnect succeeded for {}:{}", host, port);
        } catch (Exception e) {
            ClientRuntimeMetricsManager.getInstance().getMetrics().recordReconnectFailed();
            log.warn("Reconnect failed for {}:{}: {}", host, port, e.getMessage());
            retryCount.incrementAndGet();
            scheduleReconnect(host, port);
        }
    }

    /**
     * 计算指数退避延迟。
     *
     * 边界处理：delay 不超过 maxDelaySeconds；jitter 配置非法时使用安全范围兜底。
     */
    private int calculateBackoffDelay(int retryTimes) {
        int exponentialDelay = initialDelaySeconds * (1 << retryTimes);
        int cappedDelay = Math.min(exponentialDelay, maxDelaySeconds);
        int jitter = 0;
        if (jitterEnabled) {
            int safeMin = Math.max(0, jitterMinSeconds);
            int safeMax = Math.max(safeMin, jitterMaxSeconds);
            jitter = safeMin + (int) (Math.random() * (safeMax - safeMin + 1));
        }
        return cappedDelay + jitter;
    }

    /**
     * 取消当前重连任务并释放共享调度器引用。
     *
     * 注意事项：使用 schedulerReleased 保证幂等，避免重复 release 导致引用计数错误。
     */
    private void closeScheduler() {
        ScheduledFuture<?> task = reconnectTask;
        if (task != null) {
            task.cancel(false);
            reconnectTask = null;
        }
        if (schedulerReleased.compareAndSet(false, true)) {
            scheduler.release();
        }
    }

    /**
     * pipeline 异常处理。
     */
    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (closing.get()) {
            log.debug("Reconnect handler closed during shutdown: {}", cause.getMessage());
        } else {
            log.warn("Reconnect handler caught exception: {}", cause.getMessage());
        }
        ctx.close();
    }

    /**
     * handler 被移除时释放调度资源。
     */
    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        closeScheduler();
        super.handlerRemoved(ctx);
    }
}
