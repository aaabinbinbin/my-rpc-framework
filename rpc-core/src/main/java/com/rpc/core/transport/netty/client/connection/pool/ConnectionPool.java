package com.rpc.core.transport.netty.client.connection.pool;

import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.observability.metrics.ClientRuntimeMetricsManager;
import com.rpc.core.transport.netty.client.connection.RpcConnection;
import com.rpc.core.transport.netty.client.scheduler.ConnectionPoolSharedScheduler;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * consumer 侧连接池。
 *
 * 所处阶段：transport 层真实发送请求前，根据 provider 地址获取可用连接。
 * 主要职责：
 * - 按地址维护连接组，支持同一 provider 多连接。
 * - 控制单地址连接数和客户端全局连接数，防止连接膨胀。
 * - 优先选择活跃且 inflight 较少的连接。
 * - 定期回收没有 inflight 请求的空闲连接。
 *
 * 注意事项：
 * - 本类只管理连接资源，不管理 requestId 和 pending future。
 * - 创建连接前会先占用全局连接预算，失败时必须回滚计数。
 * - 空闲回收不能关闭仍有请求在途的连接。
 */
@Slf4j
public class ConnectionPool {
    private static final int LOCK_STRIPES = 64;

    private final Map<String, AddressConnectionGroup> connectionGroups = new ConcurrentHashMap<>();
    private final Object[] connectionLocks = createConnectionLocks();
    private final Bootstrap bootstrap;
    private final int maxInflightRequestsPerConnection;
    private final int maxConnectionsPerAddress;
    private final int maxTotalConnections;
    private final long idleConnectionTtlMillis;
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicInteger totalConnectionCount = new AtomicInteger(0);
    private final ConnectionPoolSharedScheduler scheduler;
    private ScheduledFuture<?> idleEvictionTask;

    public ConnectionPool(Bootstrap bootstrap,
                          int maxInflightRequestsPerConnection,
                          int maxConnectionsPerAddress,
                          int maxTotalConnections,
                          long idleConnectionTtlMillis,
                          long idleConnectionEvictIntervalMillis) {
        this.bootstrap = bootstrap;
        this.maxInflightRequestsPerConnection = Math.max(1, maxInflightRequestsPerConnection);
        this.maxConnectionsPerAddress = Math.max(1, maxConnectionsPerAddress);
        this.maxTotalConnections = Math.max(this.maxConnectionsPerAddress, maxTotalConnections);
        this.idleConnectionTtlMillis = Math.max(0L, idleConnectionTtlMillis);
        if (this.idleConnectionTtlMillis > 0 && idleConnectionEvictIntervalMillis > 0) {
            this.scheduler = ConnectionPoolSharedScheduler.getInstance();
            long intervalMillis = Math.max(1L, idleConnectionEvictIntervalMillis);
            this.idleEvictionTask = scheduler.scheduleAtFixedRate(
                    this::evictIdleConnectionsSafely,
                    intervalMillis,
                    intervalMillis,
                    TimeUnit.MILLISECONDS
            );
        } else {
            this.scheduler = null;
        }
    }

    public RpcConnection getConnection(String host, int port) throws Exception {
        if (closed.get()) {
            throw new IllegalStateException("Connection pool is closed");
        }

        String key = buildKey(host, port);
        AddressConnectionGroup group = connectionGroups.computeIfAbsent(key, ignored -> new AddressConnectionGroup());
        RpcConnection preferred = selectActiveConnection(group);
        if (preferred != null && preferred.hasCapacity()) {
            preferred.updateLastUseTime();
            return preferred;
        }

        Object lock = lockFor(key);
        synchronized (lock) {
            if (closed.get()) {
                throw new IllegalStateException("Connection pool is closed");
            }

            group = connectionGroups.computeIfAbsent(key, ignored -> new AddressConnectionGroup());
            pruneInactiveConnections(group);

            RpcConnection existing = selectActiveConnection(group);
            if (existing != null && (group.connections.size() >= maxConnectionsPerAddress || existing.hasCapacity())) {
                existing.updateLastUseTime();
                return existing;
            }

            if (group.connections.size() < maxConnectionsPerAddress) {
                acquireTotalConnectionSlot();
                log.info("Create new connection {} ({}/{})", key, group.connections.size() + 1, maxConnectionsPerAddress);
                try {
                    RpcConnection newConnection = connect(host, port);
                    group.connections.add(newConnection);
                    newConnection.updateLastUseTime();
                    return newConnection;
                } catch (Exception e) {
                    totalConnectionCount.decrementAndGet();
                    throw e;
                }
            }

            RpcConnection fallback = selectActiveConnection(group);
            if (fallback != null) {
                fallback.updateLastUseTime();
                return fallback;
            }

            log.info("Recreate connection {}", key);
            acquireTotalConnectionSlot();
            try {
                RpcConnection recreated = connect(host, port);
                group.connections.add(recreated);
                recreated.updateLastUseTime();
                return recreated;
            } catch (Exception e) {
                totalConnectionCount.decrementAndGet();
                throw e;
            }
        }
    }

    public void reconnect(String host, int port) {
        if (closed.get()) {
            return;
        }
        log.info("Reconnect to {}:{}", host, port);
        try {
            removeConnection(host, port);
            getConnection(host, port);
            log.info("Reconnect succeeded");
        } catch (Exception e) {
            log.error("Reconnect failed", e);
        }
    }

    public void removeConnection(String host, int port) {
        String key = buildKey(host, port);
        AddressConnectionGroup group = connectionGroups.remove(key);
        if (group == null) {
            return;
        }
        closeConnections(group.snapshot());
        log.info("Removed connections {}", key);
    }

    public void closeAll() {
        closed.set(true);
        cancelIdleEviction();
        for (AddressConnectionGroup group : connectionGroups.values()) {
            closeConnections(group.snapshot());
        }
        connectionGroups.clear();
        log.info("All pooled connections closed");
    }

    public int size() {
        return totalConnectionCount.get();
    }

    protected RpcConnection connect(String host, int port) throws Exception {
        ChannelFuture future = bootstrap.connect(new InetSocketAddress(host, port)).sync();
        Channel channel = future.channel();
        return new RpcConnection(channel, host, port, maxInflightRequestsPerConnection);
    }

    protected void evictIdleConnections(long nowMillis) {
        if (closed.get() || idleConnectionTtlMillis <= 0) {
            return;
        }

        for (Map.Entry<String, AddressConnectionGroup> entry : connectionGroups.entrySet()) {
            String key = entry.getKey();
            AddressConnectionGroup group = entry.getValue();
            Object lock = lockFor(key);
            synchronized (lock) {
                List<RpcConnection> evicted = new ArrayList<>();
                group.connections.removeIf(connection -> {
                    if (!connection.isActive()) {
                        evicted.add(connection);
                        return true;
                    }
                    if (connection.getInflightRequestCount() > 0) {
                        return false;
                    }
                    if (nowMillis - connection.getLastUseTime() <= idleConnectionTtlMillis) {
                        return false;
                    }
                    evicted.add(connection);
                    return true;
                });
                if (group.connections.isEmpty()) {
                    connectionGroups.remove(key, group);
                }
                closeConnections(evicted);
            }
        }
    }

    private RpcConnection selectActiveConnection(AddressConnectionGroup group) {
        List<RpcConnection> candidates = group.snapshot();
        if (candidates.isEmpty()) {
            return null;
        }

        pruneInactiveConnections(group);
        candidates = group.snapshot();
        if (candidates.isEmpty()) {
            return null;
        }

        int start = Math.floorMod(group.cursor.getAndIncrement(), candidates.size());
        RpcConnection best = null;
        int bestInflight = Integer.MAX_VALUE;
        for (int i = 0; i < candidates.size(); i++) {
            RpcConnection candidate = candidates.get((start + i) % candidates.size());
            if (!candidate.isActive()) {
                continue;
            }
            int inflight = candidate.getInflightRequestCount();
            if (best == null || inflight < bestInflight) {
                best = candidate;
                bestInflight = inflight;
                if (inflight == 0) {
                    break;
                }
            }
        }
        return best;
    }

    private void pruneInactiveConnections(AddressConnectionGroup group) {
        group.connections.removeIf(connection -> {
            if (!connection.isActive()) {
                totalConnectionCount.decrementAndGet();
                return true;
            }
            return false;
        });
    }

    private void closeConnections(List<RpcConnection> connections) {
        for (RpcConnection connection : connections) {
            try {
                connection.getChannel().close().sync();
            } catch (Exception e) {
                log.error("Failed to close connection", e);
            } finally {
                totalConnectionCount.updateAndGet(current -> current > 0 ? current - 1 : 0);
            }
        }
    }

    private void acquireTotalConnectionSlot() {
        while (true) {
            int current = totalConnectionCount.get();
            if (current >= maxTotalConnections) {
                ClientRuntimeMetricsManager.getInstance().getMetrics().recordTotalConnectionLimitRejection();
                throw new ClientOverloadedException(
                        ClientOverloadedException.Reason.TOTAL_CONNECTION_LIMIT_EXCEEDED,
                        "Connection pool total connection limit exceeded"
                );
            }
            if (totalConnectionCount.compareAndSet(current, current + 1)) {
                return;
            }
        }
    }

    private void evictIdleConnectionsSafely() {
        try {
            evictIdleConnections(System.currentTimeMillis());
        } catch (RuntimeException e) {
            log.warn("Failed to evict idle connections", e);
        }
    }

    private void cancelIdleEviction() {
        if (idleEvictionTask != null) {
            idleEvictionTask.cancel(false);
            idleEvictionTask = null;
        }
        if (scheduler != null) {
            scheduler.release();
        }
    }

    private String buildKey(String host, int port) {
        return host + ":" + port;
    }

    private Object lockFor(String key) {
        return connectionLocks[Math.floorMod(key.hashCode(), connectionLocks.length)];
    }

    private static Object[] createConnectionLocks() {
        Object[] locks = new Object[LOCK_STRIPES];
        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
        return locks;
    }

    private static final class AddressConnectionGroup {
        private final CopyOnWriteArrayList<RpcConnection> connections = new CopyOnWriteArrayList<>();
        private final AtomicInteger cursor = new AtomicInteger(0);

        private List<RpcConnection> snapshot() {
            return new ArrayList<>(connections);
        }
    }
}
