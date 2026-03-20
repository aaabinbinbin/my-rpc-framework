package com.rpc.transport.netty.client.pool;

import com.rpc.transport.netty.client.connection.RpcConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 简单的连接池
 * 每个服务器地址维护一个连接
 */
@Slf4j
public class ConnectionPool {
    /**
     * 存储连接
     * key: serverAddress (host:port)
     * value: RpcConnection
     */
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();

    private final Bootstrap bootstrap;

    public ConnectionPool(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * 获取连接（如果没有则创建）
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);

        // 1. 尝试获取已有连接
        RpcConnection connection = connectionMap.get(key);

        if (connection != null && connection.isActive()) {
            log.debug("复用已有连接：{}", key);
            connection.updateLastUseTime();
            return connection;
        }

        // 2. 创建新连接
        log.info("创建新连接：{}", key);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();

        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);

        connectionMap.put(key, newConnection);

        return newConnection;
    }

    /**
     * 关闭并移除连接
     */
    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.remove(key);

        if (connection != null) {
            connection.getChannel().close();
            log.info("连接已关闭：{}", key);
        }
    }

    /**
     * 关闭所有连接
     */
    public void closeAll() {
        for (RpcConnection connection : connectionMap.values()) {
            try {
                connection.getChannel().close().sync();
            } catch (Exception e) {
                log.error("关闭连接失败", e);
            }
        }
        connectionMap.clear();
        log.info("所有连接已关闭");
    }

    /**
     * 构建连接键
     */
    private String buildKey(String host, int port) {
        return host + ":" + port;
    }

    /**
     * 获取连接池大小
     */
    public int size() {
        return connectionMap.size();
    }
}
