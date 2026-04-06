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
 * 简单连接池。
 *
 * 按 host:port 维度维护长连接，
 * 让同一个远端地址的多个请求可以复用同一条 Netty Channel。
 */
@Slf4j
public class ConnectionPool {
    /** host:port -> RpcConnection 映射。 */
    private final Map<String, RpcConnection> connectionMap = new ConcurrentHashMap<>();
    /** 用于创建新连接的 Netty Bootstrap。 */
    private final Bootstrap bootstrap;

    public ConnectionPool(Bootstrap bootstrap) {
        this.bootstrap = bootstrap;
    }

    /**
     * 获取某个地址的连接。
     *
     * 优先复用活动连接；
     * 如果连接不存在或已不可用，则重新创建并放回池中。
     */
    public RpcConnection getConnection(String host, int port) throws Exception {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.get(key);
        if (connection != null && connection.isActive()) {
            log.debug("Reuse active connection {}", key);
            connection.updateLastUseTime();
            return connection;
        }

        log.info("Create new connection {}", key);
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        Channel channel = future.channel();
        RpcConnection newConnection = new RpcConnection(channel, host, port);
        connectionMap.put(key, newConnection);
        return newConnection;
    }

    /**
     * 重新建立某个地址的连接。
     *
     * 重连前会先把旧连接移出池子，确保后续一定走新建连接流程。
     */
    public void reconnect(String host, int port) {
        log.info("Reconnect to {}:{}", host, port);
        try {
            connectionMap.remove(buildKey(host, port));
            getConnection(host, port);
            log.info("Reconnect succeeded");
        } catch (Exception e) {
            log.error("Reconnect failed", e);
        }
    }

    /** 从连接池移除并关闭某个连接。 */
    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        RpcConnection connection = connectionMap.remove(key);
        if (connection != null) {
            connection.getChannel().close();
            log.info("Removed connection {}", key);
        }
    }

    /** 关闭池中所有连接。 */
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

    /** 当前连接池中活动连接数。 */
    public int size() {
        return connectionMap.size();
    }

    private String buildKey(String host, int port) {
        return host + ":" + port;
    }
}
