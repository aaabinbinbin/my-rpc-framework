package com.rpc.core.transport.netty.client.connection.pool;

import com.rpc.core.transport.netty.client.connection.RpcConnection;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 host:port 维度维护的简单连接池。
 */
@Slf4j
public class ConnectionPool {
    // 当前按 host:port 维度维护长连接；一个远端地址只复用一条 channel。
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();
    private final Bootstrap bootstrap;

    public ConnectionPool(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.get(key);
        if (connection != null && connection.isActive()) {
            // channel 仍可用时直接复用，避免每个请求都重新建连。
            log.debug("Reuse active connection {}", key);
            connection.updateLastUseTime();
            return connection;
        }

        log.info("Create new connection {}", key);
        // 连接创建失败会直接抛异常，由上层决定是否重试或熔断。
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        connectionMap.put(key, newConnection);
        return newConnection;
    }

    public void reconnect(String host, int port) {
        log.info("Reconnect to {}:{}", host, port);
        try {
            // 重连前先把旧连接移出池子，确保后续 getConnection() 一定会重新建连。
            connectionMap.remove(buildKey(host, port));
            getConnection(host, port);
            log.info("Reconnect succeeded");
        } catch (Exception e) {
            log.error("Reconnect failed", e);
        }
    }

    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.remove(key);
        if (connection != null) {
            connection.getChannel().close();
            log.info("Removed connection {}", key);
        }
    }

    public void closeAll() {
        for (RpcConnection connection : connectionMap.values()) {
            try {
                connection.getChannel().close().sync();
            } catch (Exception e) {
                log.error("Failed to close connection", e);
            }
        }
        connectionMap.clear();
        log.info("All pooled connections closed");
    }

    public int size() {
        return connectionMap.size();
    }

    private String buildKey(String host, int port) {
        return host + ":" + port;
    }
}
