package com.rpc.netty;

import com.rpc.HelloService;
import com.rpc.core.config.client.RpcClientConfig;
import com.rpc.core.config.server.RpcServerConfig;
import com.rpc.core.invoke.proxy.RpcProxyFactory;
import com.rpc.core.runtime.server.HelloServiceImpl;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.factory.RpcServerFactory;
import com.rpc.support.InMemoryServiceRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/**
 * RPC Netty 传输集成测试。
 *
 * <p>测试目标：启动真实 Netty 服务端和客户端代理，验证从代理调用到网络传输、
 * 服务端分发、本地服务执行、响应返回的最小闭环。</p>
 */
@DisplayName("RPC Netty 传输集成测试")
public class RpcNettyTransportIntegrationTest {
    private static final String SERVICE_NAME = "com.rpc.HelloService";

    private InMemoryServiceRegistry registry;
    private RpcServer server;
    private Thread serverThread;
    private AtomicReference<Throwable> serverStartFailure;

    @BeforeEach
    void setUp() throws Exception {
        int port = findAvailablePort();
        registry = new InMemoryServiceRegistry();
        serverStartFailure = new AtomicReference<>();

        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .transportType(TransportType.NETTY)
                .host("127.0.0.1")
                .port(port)
                .bossThreads(1)
                .workerThreads(2)
                .bizCoreThreads(2)
                .bizMaxThreads(2)
                .bizQueueCapacity(10);

        server = RpcServerFactory.create(serverConfig, registry);
        server.getLocalRegistry().register(SERVICE_NAME, new HelloServiceImpl());

        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                serverStartFailure.set(e);
            }
        }, "rpc-netty-transport-test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        waitUntilPortOpen("127.0.0.1", port, 3, TimeUnit.SECONDS);
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.shutdown();
        }
        if (registry != null) {
            registry.close();
        }
        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }
    }

    @DisplayName("验证通过 Netty 传输调用 Hello 服务")
    @Test
    void shouldInvokeHelloServiceOverNettyTransport() {
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(TransportType.NETTY)
                .connectTimeout(2000)
                .readTimeout(3000)
                .build();
        RpcProxyFactory proxyFactory = RpcProxyFactory.create(clientConfig, registry);

        HelloService helloService = proxyFactory.createProxyInstance(HelloService.class);

        String hello = helloService.sayHello("netty");
        Integer sum = helloService.add(11, 12);

        Assertions.assertEquals("Hello, netty!", hello);
        Assertions.assertEquals(Integer.valueOf(23), sum);
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    private void waitUntilPortOpen(String host, int port, long timeout, TimeUnit unit) throws Exception {
        long deadline = System.nanoTime() + unit.toNanos(timeout);
        IOException lastConnectFailure = null;
        while (System.nanoTime() < deadline) {
            Throwable startupFailure = serverStartFailure.get();
            if (startupFailure != null) {
                throw new IllegalStateException("Netty 测试服务启动失败", startupFailure);
            }
            try (Socket ignored = new Socket(host, port)) {
                return;
            } catch (IOException e) {
                lastConnectFailure = e;
                Thread.sleep(25L);
            }
        }
        throw new IllegalStateException("等待 Netty 测试服务端口启动超时", lastConnectFailure);
    }
}
