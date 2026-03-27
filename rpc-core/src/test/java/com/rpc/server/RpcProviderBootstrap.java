package com.rpc.server;

import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.RpcServer;
import com.rpc.transport.TransportType;
import com.rpc.transport.factory.RpcServerFactory;
import com.rpc.transport.netty.server.config.RpcServerConfig;
import lombok.extern.slf4j.Slf4j;

@Slf4j
public class RpcProviderBootstrap {
    public static void main(String[] args) {
        try {
            RpcServerConfig config = RpcServerConfig.custom()
                    .transportType(TransportType.from(System.getProperty("rpc.transport", "netty")))
                    .host("8.134.204.101")
                    .port(8080)
                    .bossThreads(1)
                    .workerThreads(4);
            ServiceRegistry registry = new ZooKeeperRegistryImpl("8.134.204.101:2181", 5000);

            RpcServer server = RpcServerFactory.create(config, registry);
            server.getLocalRegistry().register("com.rpc.HelloService", new HelloServiceImpl());
            server.start();
        } catch (Exception e) {
            log.error("启动 RPC 服务失败", e);
            System.exit(1);
        }
    }
}
