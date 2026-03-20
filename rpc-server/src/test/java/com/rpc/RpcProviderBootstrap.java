package com.rpc;

import com.rpc.config.RpcServerConfig;
import com.rpc.netty.RpcNettyServer;
import com.rpc.netty.registry.LocalRegistry;
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
                    .port(8080)
                    .bossThreads(1)
                    .workerThreads(4);

            // 2. 创建 RPC 服务器
            RpcNettyServer server = new RpcNettyServer(config);

            // 3. 注册服务
            LocalRegistry registry = server.getLocalRegistry();
            registry.register("com.rpc.HelloService",
                    new HelloServiceImpl());

            // 4. 启动服务器
            server.start();

        } catch (Exception e) {
            log.error("启动 RPC 服务器失败", e);
            System.exit(1);
        }
    }
}
