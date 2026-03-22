package com.rpc.heart;

import com.rpc.HelloService;
import com.rpc.config.RpcClientConfig;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.server.HelloServiceImpl;
import com.rpc.transport.netty.client.RpcNettyClient;
import com.rpc.transport.netty.server.RpcNettyServer;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * 心跳检测集成测试
 */
@Slf4j
public class HeartbeatIntegrationTest {
    private RpcNettyServer server;
    private RpcNettyClient client;
    private Thread serverThread;

    @Before
    public void setUp() throws Exception {
        log.info("========== 启动集成测试环境 ==========");

        // 1. 启动服务端
        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .host("127.0.0.1");
        ZooKeeperRegistryImpl registry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);

        server = new RpcNettyServer(serverConfig, registry);
        server.getLocalRegistry().register("com.rpc.HelloService", new HelloServiceImpl());

        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                log.error("服务端启动失败", e);
                throw new RuntimeException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 等待服务端启动
        Thread.sleep(2000);

        // 2. 启动客户端
        RpcClientConfig clientConfig = RpcClientConfig.custom();
        client = new RpcNettyClient(clientConfig, registry);

        log.info("========== 测试环境启动完成 ==========\n");
    }

    @After
    public void tearDown() {
        log.info("========== 清理测试环境 ==========");

        if (client != null) {
            client.close();
        }

        if (server != null) {
            server.shutdown();
        }

        if (serverThread != null && serverThread.isAlive()) {
            serverThread.interrupt();
        }

        log.info("========== 测试环境清理完成 ==========");
    }

    /**
     * 测试长时间空闲后的调用
     */
    @Test
    public void testCallAfterIdle() throws Exception {
        log.info("\n========== 测试：长时间空闲后调用 ==========");

        // 1. 创建代理
        RpcProxyFactory proxyFactory = new RpcProxyFactory();
        proxyFactory.initClient(client);
        HelloService proxy = proxyFactory.createProxy(HelloService.class);

        // 2. 第一次调用（建立连接）
        String result1 = proxy.sayHello("User1");
        log.info("第一次调用结果：{}", result1);

        // 3. 等待 35 秒（超过心跳间隔）
        log.info("等待 35 秒，让心跳机制工作...");
        Thread.sleep(35000);

        // 4. 第二次调用（连接应该还保持）
        String result2 = proxy.sayHello("User2");
        log.info("第二次调用结果：{}", result2);

        // 5. 验证结果
        assert result1.contains("User1");
        assert result2.contains("User2");

        log.info("✓ 长时间空闲后调用成功\n");
    }

    /**
     * 测试断线重连
     */
    @Test
    public void testCallAfterServerRestart() throws Exception {
        log.info("\n========== 测试：服务端重启后客户端自动重连 ==========");

        RpcProxyFactory proxyFactory = new RpcProxyFactory();
        proxyFactory.initClient(client);
        HelloService proxy = proxyFactory.createProxy(HelloService.class);

        try {
            // 3. 第一次调用（建立连接）
            String result1 = proxy.sayHello("User1");
            log.info("首次调用成功：{}", result1);
            assert result1.contains("User1");
        } catch (Exception e) {
            log.error("首次调用失败", e);
            throw e;
        }

        // 4. 停止服务端（模拟宕机）
        log.info("停止服务端，模拟故障...");
        server.shutdown();

        // 5. 等待一段时间，让客户端检测到连接断开（由 ReconnectHandler 触发重连）
        Thread.sleep(5000);

        // 6. 尝试调用 —— 应该失败或阻塞（可捕获异常，但不影响后续重连）
        try {
            proxy.sayHello("ShouldFail");
            log.warn("警告：服务端已停，但调用仍成功？网络未断开");
        } catch (Exception ignore) {
            log.info("调用失败，符合预期（服务端已关闭）");
        }

        // 7. 重启服务端
        log.info("重启服务端...");
        serverThread = new Thread(() -> {
            try {
                server.start();
            } catch (Exception e) {
                log.error("服务端第二次启动失败", e);
                throw new RuntimeException(e);
            }
        });
        serverThread.setDaemon(true);
        serverThread.start();

        // 等待服务端启动
        Thread.sleep(2000);

        // 8. 等待客户端自动重连（心跳检测 + ReconnectHandler 会尝试重连）
        log.info("等待客户端自动重连...");
        Thread.sleep(10000); // 给足时间重连

        // 9. 再次调用，验证是否恢复正常
        String result2 = proxy.sayHello("User2");
        log.info("重启后调用成功：{}", result2);
        assert result2.contains("User2");

        log.info("✓ 服务端重启后客户端成功自动重连并完成调用\n");

    }
}
