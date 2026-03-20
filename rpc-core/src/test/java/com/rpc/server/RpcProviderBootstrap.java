package com.rpc.server;

import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.netty.server.RpcNettyServer;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import com.rpc.registry.LocalRegistry;
import lombok.extern.slf4j.Slf4j;

/**
 * RPC 服务提供者启动类
 */
@Slf4j
public class RpcProviderBootstrap {

    public static void main(String[] args) {
        try {
            // 1. 创建服务器配置
            RpcServerConfig config = RpcServerConfig.custom()
                    .host("8.134.204.101")
                    .port(8080)
                    .bossThreads(1)
                    .workerThreads(4);
            // 1. 创建 ZooKeeper 注册中心连接
            ServiceRegistry registry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);

            // 2. 创建 RPC 服务器
            RpcNettyServer server = new RpcNettyServer(config, registry);

            // 3. 注册服务
            server.getLocalRegistry().register(
                    "com.rpc.HelloService",
                    new HelloServiceImpl());

            // 4. 启动服务器
            server.start();

        } catch (Exception e) {
            log.error("启动 RPC 服务器失败", e);
            System.exit(1);
        }
    }
}
