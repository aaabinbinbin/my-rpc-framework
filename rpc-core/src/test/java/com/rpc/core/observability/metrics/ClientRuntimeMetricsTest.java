package com.rpc.core.observability.metrics;

import com.rpc.core.common.exception.dedicated.ClientOverloadedException;
import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.transport.netty.client.connection.RpcConnection;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import com.rpc.core.transport.netty.client.handler.heartbeat.ReconnectHandler;
import com.rpc.core.transport.netty.client.request.RequestManager;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：客户端运行时指标测试")
class ClientRuntimeMetricsTest {
    @AfterEach
    void tearDown() {
        ClientRuntimeMetricsManager.getInstance().reset();
    }

    @DisplayName("验证记录客户端预算并超时事件场景")
    @Test
    void shouldRecordClientBudgetAndTimeoutEvents() {
        RequestManager requestManager = new RequestManager(1);
        EmbeddedChannel channel = new EmbeddedChannel();
        requestManager.addRequest(1L, channel, 10L);

        assertThrows(ClientOverloadedException.class, () -> requestManager.addRequest(2L, channel, 10L));

        requestManager.clearTimeoutRequests(System.currentTimeMillis() + 20L);

        ClientRuntimeMetrics.Snapshot snapshot = ClientRuntimeMetricsManager.getInstance().snapshot();
        assertEquals(1, snapshot.getPendingLimitRejections());
        assertEquals(1, snapshot.getRequestTimeoutClearCount());

        channel.finishAndReleaseAll();
    }

    @DisplayName("验证记录总连接限制拒绝场景")
    @Test
    void shouldRecordTotalConnectionLimitRejections() throws Exception {
        TestConnectionPool pool = new TestConnectionPool();
        try {
            RpcConnection first = pool.getConnection("127.0.0.1", 8080);
            assertTrue(first.tryAcquireRequestSlot());

            assertThrows(ClientOverloadedException.class, () -> pool.getConnection("127.0.0.1", 8081));

            ClientRuntimeMetrics.Snapshot snapshot = ClientRuntimeMetricsManager.getInstance().snapshot();
            assertEquals(1, snapshot.getTotalConnectionLimitRejections());
        } finally {
            pool.closeAll();
        }
    }

    @DisplayName("验证记录重连生命周期场景")
    @Test
    void shouldRecordReconnectLifecycle() throws Exception {
        RpcClientConfig config = RpcClientConfig.builder().build();
        config.setReconnectInitialDelaySeconds(0);
        config.setReconnectMaxDelaySeconds(0);
        config.setReconnectJitterEnabled(false);
        TrackingConnectionPool pool = new TrackingConnectionPool();
        ReconnectHandler handler = new ReconnectHandler(
                () -> pool,
                address -> true,
                new AtomicBoolean(false),
                config
        );
        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9090);
        EmbeddedChannel channel = new EmbeddedChannel(handler) {
            @Override
            public InetSocketAddress remoteAddress() {
                return remoteAddress;
            }
        };

        try {
            channel.pipeline().fireChannelInactive();
            TimeUnit.MILLISECONDS.sleep(50L);

            ClientRuntimeMetrics.Snapshot snapshot = ClientRuntimeMetricsManager.getInstance().snapshot();
            assertEquals(1, snapshot.getReconnectScheduledCount());
            assertEquals(1, snapshot.getReconnectSucceededCount());
            assertEquals(0, snapshot.getReconnectFailedCount());
        } finally {
            channel.finishAndReleaseAll();
            pool.closeAll();
        }
    }

    private static final class TestConnectionPool extends ConnectionPool {
        private TestConnectionPool() {
            super(new Bootstrap(), 1, 1, 1, 0L, 0L);
        }

        @Override
        protected RpcConnection connect(String host, int port) {
            return new RpcConnection(new EmbeddedChannel(), host, port, 1);
        }
    }

    private static final class TrackingConnectionPool extends ConnectionPool {
        private TrackingConnectionPool() {
            super(new Bootstrap(), 1, 1, 1, 0L, 0L);
        }

        @Override
        public void removeConnection(String host, int port) {
        }

        @Override
        protected RpcConnection connect(String host, int port) {
            return new RpcConnection(new EmbeddedChannel(), host, port, 1);
        }

        @Override
        public RpcConnection getConnection(String host, int port) throws Exception {
            return connect(host, port);
        }
    }
}
