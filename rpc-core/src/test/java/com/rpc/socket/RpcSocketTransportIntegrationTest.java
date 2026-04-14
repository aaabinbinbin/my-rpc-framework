package com.rpc.socket;

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
 * RPC Socket 传输集成测试。
 *
 * <p>测试目标：启动 legacy Socket 服务端和客户端代理，验证非 Netty 传输路径仍能完成
 * 服务发现、请求发送、服务执行和响应返回。</p>
 */
@DisplayName("RPC Socket 传输集成测试")
public class RpcSocketTransportIntegrationTest {
    private static final String SERVICE_NAME = "com.rpc.HelloService";

    private InMemoryServiceRegistry registry;
    private RpcServer server;
    private Thread serverThread;
    private AtomicReference<Throwable> serverStartFailure;

    @BeforeEach
    public void setUp() throws Exception {
        int port = findAvailablePort();
        registry = new InMemoryServiceRegistry();
        serverStartFailure = new AtomicReference<>();

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
            } catch (Exception e) {
                serverStartFailure.set(e);
            }
        }, "rpc-socket-transport-test-server");
        serverThread.setDaemon(true);
        serverThread.start();

        waitUntilPortOpen("127.0.0.1", port, 3, TimeUnit.SECONDS);
    }

    @AfterEach
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

    @DisplayName("验证通过 Socket 传输调用 Hello 服务")
    @Test
    public void shouldInvokeHelloServiceOverSocketTransport() {
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(TransportType.SOCKET)
                .connectTimeout(2000)
                .readTimeout(3000)
                .build();
        RpcProxyFactory proxyFactory = RpcProxyFactory.create(clientConfig, registry);

        HelloService helloService = proxyFactory.createProxyInstance(HelloService.class);

        String hello = helloService.sayHello("socket");
        Integer sum = helloService.add(7, 8);

        Assertions.assertEquals("Hello, socket!", hello);
        Assertions.assertEquals(Integer.valueOf(15), sum);
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
                throw new IllegalStateException("Socket 测试服务启动失败", startupFailure);
            }
            try (Socket ignored = new Socket(host, port)) {
                return;
            } catch (IOException e) {
                lastConnectFailure = e;
                Thread.sleep(25L);
            }
        }
        throw new IllegalStateException("等待 Socket 测试服务端口启动超时", lastConnectFailure);
    }
}
