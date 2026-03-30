package com.rpc.netty;

import com.rpc.HelloService;
import com.rpc.core.config.RpcClientConfig;
import com.rpc.core.invoke.proxy.RpcProxyFactory;
import com.rpc.core.runtime.server.HelloServiceImpl;
import com.rpc.support.InMemoryServiceRegistry;
import com.rpc.core.transport.RpcServer;
import com.rpc.core.transport.TransportType;
import com.rpc.core.transport.factory.RpcServerFactory;
import com.rpc.core.transport.netty.server.config.RpcServerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.ServerSocket;

public class RpcNettyTransportIntegrationTest {
    private static final String SERVICE_NAME = "com.rpc.HelloService";

    private InMemoryServiceRegistry registry;
    private RpcServer server;
    private Thread serverThread;

    @BeforeEach
    void setUp() throws Exception {
        int port = findAvailablePort();
        registry = new InMemoryServiceRegistry();

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
            } catch (Exception ignored) {
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        Thread.sleep(500);
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
}

