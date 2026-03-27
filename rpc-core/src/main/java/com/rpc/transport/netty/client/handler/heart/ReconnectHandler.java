package com.rpc.transport.netty.client.handler.heart;

import com.rpc.config.RpcClientConfig;
import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
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

@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionPool connectionPool;
    private final AtomicBoolean closing;
    private final int maxRetryTimes;
    private final int initialDelaySeconds;
    private final int maxDelaySeconds;
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, new DefaultThreadFactory("reconnect-scheduler"));

    public ReconnectHandler(ConnectionPool connectionPool, AtomicBoolean closing, RpcClientConfig config) {
        this.connectionPool = connectionPool;
        this.closing = closing;
        this.maxRetryTimes = config.getReconnectMaxRetryTimes();
        this.initialDelaySeconds = config.getReconnectInitialDelaySeconds();
        this.maxDelaySeconds = config.getReconnectMaxDelaySeconds();
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (closing.get()) {
            log.info("客户端正在主动关闭，跳过重连: {}", ctx.channel().remoteAddress());
            closeScheduler();
            super.channelInactive(ctx);
            return;
        }

        log.warn("与服务器断开连接: {}", ctx.channel().remoteAddress());

        if (connectionPool != null) {
            InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
            String host = address.getHostString();
            int port = address.getPort();
            connectionPool.removeConnection(host, port);
            scheduleReconnect(host, port);
        }

        super.channelInactive(ctx);
    }

    private void scheduleReconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }

        int currentRetry = retryCount.get();
        if (currentRetry >= maxRetryTimes) {
            log.error("重连失败，已达到最大重试次数 {}", maxRetryTimes);
            closeScheduler();
            return;
        }

        int delay = calculateBackoffDelay(currentRetry);
        log.info("将在 {} 秒后尝试第 {} 次重连", delay, currentRetry + 1);

        scheduler.schedule(() -> {
            if (closing.get()) {
                closeScheduler();
                return;
            }
            reconnect(host, port);
        }, delay, TimeUnit.SECONDS);
    }

    private void reconnect(String host, int port) {
        if (closing.get()) {
            closeScheduler();
            return;
        }

        log.info("开始重连到 {}:{}", host, port);

        try {
            if (connectionPool != null) {
                connectionPool.getConnection(host, port);
                retryCount.set(0);
                log.info("重连成功");
            }
        } catch (Exception e) {
            log.error("重连失败: {}", e.getMessage());
            retryCount.incrementAndGet();
            scheduleReconnect(host, port);
        }
    }

    private int calculateBackoffDelay(int retryTimes) {
        int exponentialDelay = initialDelaySeconds * (1 << retryTimes);
        int cappedDelay = Math.min(exponentialDelay, maxDelaySeconds);
        int jitter = (int) (Math.random() * 2);
        return cappedDelay + jitter;
    }

    private void closeScheduler() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdownNow();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        log.error("重连处理器异常", cause);
        ctx.close();
    }
}
