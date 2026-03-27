package com.rpc.bootstrap;

import com.rpc.config.RpcClientConfig;
import com.rpc.config.RpcConfigLoader;
import com.rpc.config.RpcFrameworkConfig;
import com.rpc.loadbalance.factory.LoadBalancerFactory;
import com.rpc.proxy.RpcProxyFactory;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.factory.ServiceRegistryFactory;
import com.rpc.transport.RpcTransport;
import com.rpc.transport.factory.RpcTransportFactory;

public class RpcConsumerBootstrap implements AutoCloseable {
    private final ServiceRegistry serviceRegistry;
    private final RpcTransport rpcTransport;

    private RpcConsumerBootstrap(ServiceRegistry serviceRegistry, RpcTransport rpcTransport) {
        this.serviceRegistry = serviceRegistry;
        this.rpcTransport = rpcTransport;
        RpcProxyFactory.initClient(rpcTransport);
    }

    public static RpcConsumerBootstrap fromConfig() {
        return fromConfig(RpcConfigLoader.load());
    }

    public static RpcConsumerBootstrap fromConfig(RpcFrameworkConfig frameworkConfig) {
        ServiceRegistry serviceRegistry = ServiceRegistryFactory.create(frameworkConfig);
        RpcClientConfig clientConfig = RpcClientConfig.builder()
                .transportType(frameworkConfig.getTransportType())
                .connectTimeout(frameworkConfig.getConnectTimeout())
                .readTimeout(frameworkConfig.getReadTimeout())
                .heartbeatInterval(frameworkConfig.getHeartbeatInterval())
                .writerIdleTime(frameworkConfig.getWriterIdleTime())
                .readerIdleTime(frameworkConfig.getReaderIdleTime())
                .retryTimes(frameworkConfig.getRetryTimes())
                .reconnectMaxRetryTimes(frameworkConfig.getReconnectMaxRetryTimes())
                .reconnectInitialDelaySeconds(frameworkConfig.getReconnectInitialDelaySeconds())
                .reconnectMaxDelaySeconds(frameworkConfig.getReconnectMaxDelaySeconds())
                .enableDegradation(frameworkConfig.isEnableDegradation())
                .degradationFailureThreshold(frameworkConfig.getDegradationFailureThreshold())
                .loadBalancer(LoadBalancerFactory.getLoadBalancer(frameworkConfig.getLoadBalancer()))
                .serializerName(frameworkConfig.getSerializer())
                .build();
        RpcTransport rpcTransport = RpcTransportFactory.create(clientConfig, serviceRegistry);
        return new RpcConsumerBootstrap(serviceRegistry, rpcTransport);
    }

    public <T> T getService(Class<T> serviceClass) {
        return RpcProxyFactory.createProxy(serviceClass);
    }

    @Override
    public void close() {
        rpcTransport.close();
    }
}
