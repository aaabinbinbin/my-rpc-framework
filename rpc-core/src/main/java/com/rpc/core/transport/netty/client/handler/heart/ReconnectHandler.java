package com.rpc.core.transport.netty.client.handler.heart;

import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 客户端断线重连处理器。
 *
 * 当连接异常断开时，这个 handler 负责按配置决定是否重连、
 * 最多重试多少次、每次等待多久，以及是否加入抖动避免雪崩式重连。
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {
    /** 连接池，重连成功后仍然通过它统一管理连接。 */
    private final ConnectionPool connectionPool;
    /** 标记当前关闭是否是主动关闭，主动关闭时不应触发重连。 */
    private final AtomicBoolean closing;
    /** 是否启用重连。 */
    private final boolean reconnectEnabled;
    /** 最大重连次数。 */
    private final int maxRetryTimes;
    /** 初始重连延迟。 */
    private final int initialDelaySeconds;
    /** 最大重连延迟。 */
    private final int maxDelaySeconds;
    /** 是否启用重连抖动。 */
    private final boolean jitterEnabled;
    /** 抖动最小秒数。 */
    private final int jitterMinSeconds;
    /** 抖动最大秒数。 */
    private final int jitterMaxSeconds;
    /** 当前重试次数。 */
    private final AtomicInteger retryCount = new AtomicInteger(0);
    /** 定时调度器，用于延迟执行重连任务。 */
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, new DefaultThreadFactory("reconnect-scheduler"));

    public ReconnectHandler(ConnectionPool connectionPool, AtomicBoolean closing, RpcClientConfig config) {
        this.connectionPool = connectionPool;
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
     * 当 Channel 失活时尝试重连。
     *
     * 如果当前是应用主动关闭，就直接退出；
     * 否则移除连接池中的旧连接，并开始调度重连。
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (closing.get()) {
            log.info("Client is closing, skip reconnect for {}", ctx.channel().remoteAddress());
            closeScheduler();
            super.channelInactive(ctx);
            return;
        }

        log.warn("Channel inactive, preparing reconnect: {}", ctx.channel().remoteAddress());
        if (connectionPool != null) {
            InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
            String host = address.getHostString();
            int port = address.getPort();
            connectionPool.removeConnection(host, port);
            scheduleReconnect(host, port);
        }
        super.channelInactive(ctx);
    }

    /**
     * 按退避策略调度下一次重连。
     *
     * 同时会检查：
     * 1. 是否已关闭。
     * 2. 是否启用了重连。
     * 3. 是否已经超过最大重试次数。
     */
    private void scheduleReconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }
        if (!reconnectEnabled) {
            log.info("Reconnect is disabled, skip reconnect scheduling");
            closeScheduler();
            return;
        }

        int currentRetry = retryCount.get();
        if (currentRetry >= maxRetryTimes) {
            log.error("Reconnect retry count exceeded limit: {}", maxRetryTimes);
            closeScheduler();
            return;
        }

        int delay = calculateBackoffDelay(currentRetry);
        log.info("Schedule reconnect in {} seconds, retry={}", delay, currentRetry + 1);
        scheduler.schedule(() -> {
            if (closing.get()) {
                closeScheduler();
                return;
            }
            reconnect(host, port);
        }, delay, TimeUnit.SECONDS);
    }

    /** 真正执行一次重连。成功后重置 retryCount，失败则继续调度下一次。 */
    private void reconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }

        log.info("Trying to reconnect to {}:{}", host, port);
        try {
            if (connectionPool != null) {
                connectionPool.getConnection(host, port);
                retryCount.set(0);
                log.info("Reconnect succeeded");
            }
        } catch (Exception e) {
            log.error("Reconnect failed: {}", e.getMessage());
            retryCount.incrementAndGet();
            scheduleReconnect(host, port);
        }
    }

    /**
     * 计算指数退避延迟，并可选叠加抖动。
     *
     * 抖动的目的是避免大量客户端在同一时刻一起重连压垮服务端。
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

    /** 关闭调度器，避免应用退出后仍然残留重连任务。 */
    private void closeScheduler() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("Reconnect handler caught exception", cause);
        ctx.close();
    }
}
