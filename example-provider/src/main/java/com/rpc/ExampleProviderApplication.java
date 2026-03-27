package com.rpc;

import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;
import com.rpc.transport.RpcServer;
import com.rpc.transport.TransportType;
import com.rpc.transport.factory.RpcServerFactory;
import com.rpc.transport.netty.server.config.RpcServerConfig;

public class ExampleProviderApplication {
    public static void main(String[] args) throws Exception {
        String host = System.getProperty("rpc.host", "127.0.0.1");
        int port = Integer.parseInt(System.getProperty("rpc.port", "8080"));
        String registryAddress = System.getProperty("rpc.registry", "127.0.0.1:2181");

        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .transportType(TransportType.from(System.getProperty("rpc.transport", "netty")))
                .host(host)
                .port(port);

        ServiceRegistry registry = new ZooKeeperRegistryImpl(registryAddress, 5000);
        RpcServer server = RpcServerFactory.create(serverConfig, registry);
        server.getLocalRegistry().register(HelloService.class.getName(), new HelloServiceImpl());
        server.start();
    }
}
