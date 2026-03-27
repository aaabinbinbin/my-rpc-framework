package com.rpc.bootstrap;

import com.rpc.config.RpcConfigLoader;
import com.rpc.config.RpcFrameworkConfig;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.factory.ServiceRegistryFactory;
import com.rpc.transport.RpcServer;
import com.rpc.transport.factory.RpcServerFactory;
import com.rpc.transport.netty.server.config.RpcServerConfig;

public class RpcProviderBootstrap implements AutoCloseable {
    private final ServiceRegistry serviceRegistry;
    private final RpcServer rpcServer;

    private RpcProviderBootstrap(ServiceRegistry serviceRegistry, RpcServer rpcServer) {
        this.serviceRegistry = serviceRegistry;
        this.rpcServer = rpcServer;
    }

    public static RpcProviderBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    public static RpcProviderBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        ServiceRegistry serviceRegistry = ServiceRegistryFactory.create(frameworkConfig);
        RpcServerConfig serverConfig = RpcServerConfig.custom()
                .transportType(frameworkConfig.getTransportType())
                .host(frameworkConfig.getServerHost())
                .port(frameworkConfig.getServerPort())
                .bossThreads(frameworkConfig.getBossThreads())
                .workerThreads(frameworkConfig.getWorkerThreads())
                .shutdownTimeout(frameworkConfig.getShutdownTimeout())
                .readerIdleTime(frameworkConfig.getServerReaderIdleTime())
                .writerIdleTime(frameworkConfig.getServerWriterIdleTime())
                .allIdleTime(frameworkConfig.getServerAllIdleTime());
        RpcServer rpcServer = RpcServerFactory.create(serverConfig, serviceRegistry);
        return new RpcProviderBootstrap(serviceRegistry, rpcServer);
    }

    public RpcProviderBootstrap registerService(Class<?> serviceInterface, Object serviceImpl) {
        rpcServer.getLocalRegistry().register(serviceInterface.getName(), serviceImpl);
        return this;
    }

    public void start() throws Exception {
        rpcServer.start();
    }

    @Override
    public void close() {
        rpcServer.shutdown();
        serviceRegistry.close();
    }
}
