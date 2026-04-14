package com.rpc.core.transport.factory;

import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.config.server.RpcServerConfig;
import com.rpc.core.discovery.ServiceChangeListener;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.discovery.ServiceInstancesSnapshot;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.RpcTransport;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.netty.client.RpcNettyClient;
import com.rpc.core.transport.netty.server.RpcNettyServer;
import com.rpc.core.transport.socket.legacy.client.RpcSocketClient;
import com.rpc.core.transport.socket.legacy.server.RpcSocketServer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertInstanceOf;

@DisplayName("测试类：传输工厂类型选择测试")
class RpcTransportAndServerFactoryTest {
    @DisplayName("验证客户端传输类型为空时默认创建 Netty 客户端")
    @Test
    void shouldCreateNettyClientWhenTransportTypeMissing() {
        RpcClientConfig config = RpcClientConfig.custom();
        config.setTransportType(null);

        RpcTransport transport = RpcTransportFactory.create(config, new NoopDiscovery());

        try {
            assertInstanceOf(RpcNettyClient.class, transport);
        } finally {
            transport.close();
        }
    }

    @DisplayName("验证客户端传输类型为 Socket 时创建 legacy Socket 客户端")
    @Test
    void shouldCreateSocketClientWhenTransportTypeIsSocket() {
        RpcClientConfig config = RpcClientConfig.custom();
        config.setTransportType(TransportType.SOCKET);

        RpcTransport transport = RpcTransportFactory.create(config, new NoopDiscovery());

        try {
            assertInstanceOf(RpcSocketClient.class, transport);
        } finally {
            transport.close();
        }
    }

    @DisplayName("验证服务端传输类型为空时默认创建 Netty 服务端")
    @Test
    void shouldCreateNettyServerWhenTransportTypeMissing() {
        RpcServerConfig config = RpcServerConfig.custom();
        config.setTransportType(null);

        RpcServer server = RpcServerFactory.create(config, new NoopRegistry());

        assertInstanceOf(RpcNettyServer.class, server);
    }

    @DisplayName("验证服务端传输类型为 Socket 时创建 legacy Socket 服务端")
    @Test
    void shouldCreateSocketServerWhenTransportTypeIsSocket() {
        RpcServerConfig config = RpcServerConfig.custom();
        config.setTransportType(TransportType.SOCKET);

        RpcServer server = RpcServerFactory.create(config, new NoopRegistry());

        assertInstanceOf(RpcSocketServer.class, server);
    }

    private static final class NoopDiscovery implements ServiceDiscovery {
        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        }

        @Override
        public void close() {
        }
    }

    private static final class NoopRegistry implements ServiceRegistry {
        @Override
        public void register(String serviceName, InetSocketAddress address) {
        }

        @Override
        public void unregister(String serviceName, InetSocketAddress address) {
        }

        @Override
        public List<InetSocketAddress> lookup(String serviceName) {
            return List.of();
        }

        @Override
        public void close() {
        }
    }
}
