package com.rpc.core.transport.netty.client.handler.heartbeat;

import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.transport.netty.client.connection.RpcConnection;
import com.rpc.core.transport.netty.client.connection.pool.ConnectionPool;
import io.netty.bootstrap.Bootstrap;
import io.netty.channel.embedded.EmbeddedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Netty 客户端重连处理器测试。
 *
 * <p>测试目标：验证 provider 地址已经从服务目录移除时，客户端只清理连接池，
 * 不再调度无意义的重连任务。</p>
 */
@DisplayName("Netty 客户端重连处理器测试")
class ReconnectHandlerTest {

    @DisplayName("服务目录已移除地址时应跳过重连")
    @Test
    void shouldSkipReconnectWhenAddressIsRemovedFromServiceDirectory() {
        AtomicReference<InetSocketAddress> removedAddress = new AtomicReference<>();
        AtomicInteger reconnectAttempts = new AtomicInteger();
        TrackingConnectionPool connectionPool = new TrackingConnectionPool(removedAddress, reconnectAttempts);
        RpcClientConfig config = RpcClientConfig.builder()
                .reconnectEnabled(true)
                .reconnectInitialDelaySeconds(1)
                .reconnectMaxDelaySeconds(1)
                .reconnectJitterEnabled(false)
                .build();

        InetSocketAddress remoteAddress = new InetSocketAddress("127.0.0.1", 9001);
        EmbeddedChannel channel = new EmbeddedChannel(new ReconnectHandler(
                () -> connectionPool,
                address -> false,
                new java.util.concurrent.atomic.AtomicBoolean(false),
                config
        )) {
            @Override
            public InetSocketAddress remoteAddress() {
                return remoteAddress;
            }
        };
        channel.pipeline().fireChannelInactive();

        assertEquals(remoteAddress, removedAddress.get());
        assertEquals(0, reconnectAttempts.get());

        channel.finishAndReleaseAll();
    }

    private static final class TrackingConnectionPool extends ConnectionPool {
        private final AtomicReference<InetSocketAddress> removedAddress;
        private final AtomicInteger reconnectAttempts;

        private TrackingConnectionPool(AtomicReference<InetSocketAddress> removedAddress,
                                       AtomicInteger reconnectAttempts) {
            super(new Bootstrap(), 1, 1, 1, 0L, 0L);
            this.removedAddress = removedAddress;
            this.reconnectAttempts = reconnectAttempts;
        }

        @Override
        public RpcConnection getConnection(String host, int port) {
            reconnectAttempts.incrementAndGet();
            return null;
        }

        @Override
        public void removeConnection(String host, int port) {
            removedAddress.set(new InetSocketAddress(host, port));
        }
    }
}
