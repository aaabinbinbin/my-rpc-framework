package com.rpc.core.config;

import com.rpc.core.registry.RegistryType;
import com.rpc.core.transport.TransportType;

final class RpcFrameworkConfigBinder {
    // 总 binder（绑定器）只负责按领域分发，避免再次回到“大一统配置解析器”。
    private final RpcServerConfigBinder serverConfigBinder = new RpcServerConfigBinder();
    private final RpcClientConfigBinder clientConfigBinder = new RpcClientConfigBinder();
    private final RpcFilterConfigBinder filterConfigBinder = new RpcFilterConfigBinder();

    RpcFrameworkConfig bind(RpcPropertySource propertySource) {
        RpcFrameworkConfig config = new RpcFrameworkConfig();
        // 先绑定公共配置，再分别绑定 registry（注册中心）、server（服务端）、
        // client（客户端）和 filter（过滤器），这样入口清晰，也方便后续继续拆分。
        bindCommon(propertySource, config);
        bindRegistry(propertySource, config);
        serverConfigBinder.bind(propertySource, config);
        clientConfigBinder.bind(propertySource, config);
        filterConfigBinder.bind(propertySource, config);
        return config;
    }

    private void bindCommon(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setTransportType(TransportType.from(
                propertySource.get(RpcConfigKeys.TRANSPORT, config.getTransportType().name())
        ));
        config.setSerializer(propertySource.get(RpcConfigKeys.SERIALIZER, config.getSerializer()));
        config.setLoadBalancer(propertySource.get(RpcConfigKeys.LOAD_BALANCER, config.getLoadBalancer()));
    }

    private void bindRegistry(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        // registry（注册中心）放在总 binder（绑定器）里处理，是因为它被
        // consumer（消费端）和 provider（提供端）共同依赖。
        config.setRegistryType(RegistryType.from(
                propertySource.get(RpcConfigKeys.REGISTRY_TYPE, config.getRegistryType().name())
        ));
        config.setRegistryAddress(propertySource.get(RpcConfigKeys.REGISTRY_ADDRESS, config.getRegistryAddress()));
        config.setRegistryTimeout(propertySource.getInt(RpcConfigKeys.REGISTRY_TIMEOUT, config.getRegistryTimeout()));
    }
}
