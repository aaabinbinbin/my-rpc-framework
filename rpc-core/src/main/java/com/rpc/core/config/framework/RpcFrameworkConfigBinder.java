package com.rpc.core.config.framework;

import com.rpc.core.config.client.RpcClientConfigBinder;
import com.rpc.core.config.filter.RpcFilterConfigBinder;
import com.rpc.core.config.server.RpcServerConfigBinder;
import com.rpc.core.config.source.RpcPropertySource;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.transport.TransportType;

/**
 * 框架总配置绑定器。
 *
 * 所处阶段：RpcConfigLoader 读出 Properties 后，真正生成 RpcFrameworkConfig 之前。
 * 主要职责：先绑定 transport、serializer、loadBalancer、registry 这类公共配置，再分发给 server/client/filter 绑定器。
 *
 * 设计原因：把大配置解析器拆成小绑定器，降低单类复杂度，也让用户默认配置精简时仍能明确知道配置归属。
 */
final class RpcFrameworkConfigBinder {
    /** 服务端配置绑定器，负责 provider 侧端口、线程池、限流降级等配置。 */
    private final RpcServerConfigBinder serverConfigBinder = new RpcServerConfigBinder();
    /** 客户端配置绑定器，负责 consumer 侧连接、重试、发现缓存、熔断等配置。 */
    private final RpcClientConfigBinder clientConfigBinder = new RpcClientConfigBinder();
    /** 过滤器链配置绑定器，负责三段 filter 链和 order 覆盖。 */
    private final RpcFilterConfigBinder filterConfigBinder = new RpcFilterConfigBinder();

    /**
     * 绑定完整框架配置。
     *
     * 边界处理：绑定顺序固定为公共配置 -> 注册中心 -> server/client/filter，后续绑定器可读取公共默认值。
     */
    public RpcFrameworkConfig bind(RpcPropertySource propertySource) {
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

    /**
     * 绑定传输、序列化和负载均衡等客户端/服务端共享配置。
     */
    private void bindCommon(RpcPropertySource propertySource, RpcFrameworkConfig config) {
        config.setTransportType(TransportType.from(
                propertySource.get(RpcConfigKeys.TRANSPORT, config.getTransportType().name())
        ));
        config.setSerializer(propertySource.get(RpcConfigKeys.SERIALIZER, config.getSerializer()));
        config.setLoadBalancer(propertySource.get(RpcConfigKeys.LOAD_BALANCER, config.getLoadBalancer()));
    }

    /**
     * 绑定注册中心配置。
     *
     * 设计原因：注册中心同时被 provider 注册和 consumer 发现依赖，因此放在总绑定器中处理。
     */
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
