package com.rpc.transport.netty.client.handler.heart;

import com.rpc.transport.netty.client.connection.pool.ConnectionPool;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.util.concurrent.DefaultThreadFactory;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 断线重连处理器
 *
 * 职责：
 * 1. 监听 channelInactive 事件
 * 2. 触发重连逻辑
 * 3. 实现指数退避重试策略
 */
@Slf4j
public class ReconnectHandler extends ChannelInboundHandlerAdapter {
    private final ConnectionPool connectionPool;

    // 重连相关配置
    private static final int MAX_RETRY_TIMES = 5;           // 最大重试次数
    private static final int INITIAL_DELAY = 2;             // 初始延迟（秒）
    private static final int MAX_DELAY = 60;                // 最大延迟（秒）

    // 重连状态
    private final AtomicInteger retryCount = new AtomicInteger(0);
    private final ScheduledExecutorService scheduler =
            Executors.newScheduledThreadPool(1, new DefaultThreadFactory("reconnect-scheduler"));

    public ReconnectHandler(ConnectionPool connectionPool) {
        this.connectionPool = connectionPool;
    }

    /**
     * channelInactive 在连接断开时触发，触发重连逻辑
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        log.warn("与服务器断开连接：{}", ctx.channel().remoteAddress());

        // 清理连接池中的旧连接
        if (connectionPool != null) {
            InetSocketAddress address = (InetSocketAddress) ctx.channel().remoteAddress();
            String host = address.getHostString();
            int port = address.getPort();
            connectionPool.removeConnection(host, port);
            // 触发重连，并传入实际断开的地址
            scheduleReconnect(host, port);
        }

        super.channelInactive(ctx);
    }

    /**
     * 调度重连任务
     */
    private void scheduleReconnect(String host, int port) {
        int currentRetry = retryCount.get();

        if (currentRetry >= MAX_RETRY_TIMES) {
            log.error("重连失败，已达到最大重试次数 {}", MAX_RETRY_TIMES);
            closeScheduler();
            return;
        }

        // 计算延迟时间（指数退避）
        int delay = calculateBackoffDelay(currentRetry);

        log.info("将在 {} 秒后尝试第 {} 次重连...", delay, currentRetry + 1);

        scheduler.schedule(() -> {
            try {
                reconnect(host, port);
            } catch (Exception e) {
                log.error("重连异常", e);
                retryCount.incrementAndGet();
                scheduleReconnect(host, port);  // 继续尝试
            }
        }, delay, TimeUnit.SECONDS);
    }

    /**
     * 执行重连
     */
    private void reconnect(String host, int port) {
        log.info("开始重连到 {}:{}", host, port);

        try {
            // 从连接池获取新连接（会触发创建新连接）
            if (connectionPool != null) {
                connectionPool.getConnection(host, port);

                // 重连成功，重置计数
                retryCount.set(0);
                log.info("重连成功！");
            }
        } catch (Exception e) {
            log.error("重连失败：{}", e.getMessage());
            retryCount.incrementAndGet();
            scheduleReconnect(host, port);  // 继续尝试
        }
    }

    /**
     * 计算退避延迟（指数退避 + 随机抖动）
     */
    private int calculateBackoffDelay(int retryCount) {
        // 指数增长：2, 4, 8, 16, 32...
        int exponentialDelay = INITIAL_DELAY * (1 << retryCount);

        // 限制最大延迟
        int cappedDelay = Math.min(exponentialDelay, MAX_DELAY);

        // 添加随机抖动（避免多个客户端同时重连）
        int jitter = (int) (Math.random() * 2);  // 0-2 秒随机

        return cappedDelay + jitter;
    }

    /**
     * 关闭调度器
     */
    private void closeScheduler() {
        if (!scheduler.isShutdown()) {
            scheduler.shutdown();
            try {
                if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    scheduler.shutdownNow();
                }
            } catch (InterruptedException e) {
                scheduler.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause)
            throws Exception {
        log.error("重连处理器异常", cause);
        ctx.close();
    }
}
