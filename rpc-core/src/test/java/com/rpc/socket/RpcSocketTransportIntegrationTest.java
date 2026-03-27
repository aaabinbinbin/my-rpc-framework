package com.rpc.socket;

import com.rpc.HelloService;
import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.server.HelloServiceImpl;
import com.rpc.support.InMemoryServiceRegistry;
import com.rpc.transport.RpcServer;
import com.rpc.transport.TransportType;
import com.rpc.transport.factory.RpcServerFactory;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.io.IOException;
import java.net.ServerSocket;

public class RpcSocketTransportIntegrationTest {
    private static final String SERVICE_NAME = "com.rpc.HelloService";

    private InMemoryServiceRegistry registry;
    private RpcServer server;
    private Thread serverThread;

    @Before
    public void setUp() throws Exception {
        int port = findAvailablePort();
        registry = new InMemoryServiceRegistry();

        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .transportType(TransportType.SOCKET)
                .host("127.0.0.1")
                .port(port)
                .workerThreads(2);

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

        Thread.sleep(300);
    }

    @After
    public void tearDown() {
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
    public void shouldInvokeHelloServiceOverSocketTransport() {
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(TransportType.SOCKET)
                .connectTimeout(2000)
                .readTimeout(3000)
                .build();
        RpcProxyFactory.initClient(clientConfig, registry);

        HelloService helloService = RpcProxyFactory.createProxy(HelloService.class);

        String hello = helloService.sayHello("socket");
        Integer sum = helloService.add(7, 8);

        Assert.assertEquals("Hello, socket!", hello);
        Assert.assertEquals(Integer.valueOf(15), sum);
    }

    private int findAvailablePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
