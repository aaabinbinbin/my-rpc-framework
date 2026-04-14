package com.rpc.transport.netty.client.connection.pool;

import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.transport.netty.client.connection.RpcConnection;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：连接池测试")
class ConnectionPoolTest {
    @DisplayName("验证创建第二个连接当第一个Is打满场景")
    @Test
    void shouldCreateSecondConnectionWhenFirstIsSaturated() throws Exception {
        TestConnectionPool pool = new TestConnectionPool(1, 2);
        try {
            RpcConnection first = pool.getConnection("127.0.0.1", 8080);
            first.tryAcquireRequestSlot();

            RpcConnection second = pool.getConnection("127.0.0.1", 8080);

            assertNotSame(first, second);
            assertEquals(2, pool.size());
            assertEquals(2, pool.createdConnections.get());
        } finally {
            pool.closeAll();
        }
    }

    @DisplayName("验证复用最少繁忙连接当地址池已经扩容场景")
    @Test
    void shouldReuseLeastBusyConnectionWhenAddressPoolAlreadyExpanded() throws Exception {
        TestConnectionPool pool = new TestConnectionPool(2, 2);
        try {
            RpcConnection first = pool.getConnection("127.0.0.1", 8080);
            first.tryAcquireRequestSlot();
            first.tryAcquireRequestSlot();

            RpcConnection second = pool.getConnection("127.0.0.1", 8080);
            second.tryAcquireRequestSlot();

            RpcConnection selected = pool.getConnection("127.0.0.1", 8080);

            assertSame(second, selected);
            assertEquals(2, pool.size());
            assertEquals(2, pool.createdConnections.get());
        } finally {
            pool.closeAll();
        }
    }

    @DisplayName("验证淘汰空闲连接场景")
    @Test
    void shouldEvictIdleConnections() throws Exception {
        TestConnectionPool pool = new TestConnectionPool(1, 2, 10L);
        try {
            RpcConnection first = pool.getConnection("127.0.0.1", 8080);
            first.tryAcquireRequestSlot();
            RpcConnection second = pool.getConnection("127.0.0.1", 8080);
            first.releaseRequestSlot();

            first.setLastUseTime(1L);
            second.setLastUseTime(1L);
            pool.runIdleEviction(100L);

            assertEquals(0, pool.size());
        } finally {
            pool.closeAll();
        }
    }

    @DisplayName("验证拒绝当总连接限制Is达到场景")
    @Test
    void shouldRejectWhenTotalConnectionLimitIsReached() throws Exception {
        TestConnectionPool pool = new TestConnectionPool(1, 1, 0L, 1);
        try {
            RpcConnection first = pool.getConnection("127.0.0.1", 8080);
            first.tryAcquireRequestSlot();

            ClientOverloadedException thrown = org.junit.jupiter.api.Assertions.assertThrows(
                    ClientOverloadedException.class,
                    () -> pool.getConnection("127.0.0.1", 8081)
            );

            assertEquals("Connection pool total connection limit exceeded", thrown.getMessage());
        } finally {
            pool.closeAll();
        }
    }

    @DisplayName("验证强制执行总连接限制跨地址在并发场景")
    @Test
    void shouldEnforceTotalConnectionLimitAcrossAddressesUnderConcurrency() throws Exception {
        TestConnectionPool pool = new TestConnectionPool(1, 1, 0L, 1);
        ExecutorService executor = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger accepted = new AtomicInteger();
        AtomicInteger rejected = new AtomicInteger();

        try {
            for (int i = 0; i < 2; i++) {
                int port = 8080 + i;
                executor.submit(() -> {
                    await(start);
                    try {
                        RpcConnection connection = pool.getConnection("127.0.0.1", port);
                        connection.tryAcquireRequestSlot();
                        accepted.incrementAndGet();
                    } catch (ClientOverloadedException e) {
                        rejected.incrementAndGet();
                    } catch (Exception e) {
                        throw new IllegalStateException(e);
                    }
                });
            }

            start.countDown();
            executor.shutdown();
            org.junit.jupiter.api.Assertions.assertTrue(
                    executor.awaitTermination(3, java.util.concurrent.TimeUnit.SECONDS));

            assertEquals(1, accepted.get());
            assertEquals(1, rejected.get());
            assertEquals(1, pool.size());
        } finally {
            executor.shutdownNow();
            pool.closeAll();
        }
    }

    private static void await(CountDownLatch latch) {
        try {
            latch.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }

    private static final class TestConnectionPool extends ConnectionPool {
        private final AtomicInteger createdConnections = new AtomicInteger();
        private final int maxInflightRequestsPerConnection;

        private TestConnectionPool(int maxInflightRequestsPerConnection, int maxConnectionsPerAddress) {
            this(maxInflightRequestsPerConnection, maxConnectionsPerAddress, 0L, Integer.MAX_VALUE);
        }

        private TestConnectionPool(int maxInflightRequestsPerConnection, int maxConnectionsPerAddress, long idleConnectionTtlMillis) {
            this(maxInflightRequestsPerConnection, maxConnectionsPerAddress, idleConnectionTtlMillis, Integer.MAX_VALUE);
        }

        private TestConnectionPool(int maxInflightRequestsPerConnection,
                                   int maxConnectionsPerAddress,
                                   long idleConnectionTtlMillis,
                                   int maxTotalConnections) {
            super(new Bootstrap(),
                    maxInflightRequestsPerConnection,
                    maxConnectionsPerAddress,
                    maxTotalConnections,
                    idleConnectionTtlMillis,
                    0L);
            this.maxInflightRequestsPerConnection = maxInflightRequestsPerConnection;
        }

        @Override
        protected RpcConnection connect(String host, int port) {
            createdConnections.incrementAndGet();
            return new RpcConnection(new EmbeddedChannel(), host, port, maxInflightRequestsPerConnection);
        }

        private void runIdleEviction(long nowMillis) {
            evictIdleConnections(nowMillis);
        }
    }
}
