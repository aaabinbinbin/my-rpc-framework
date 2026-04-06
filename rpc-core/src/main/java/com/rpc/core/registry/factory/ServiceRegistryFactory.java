package com.rpc.core.registry.factory;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.impl.ZooKeeperRegistryImpl;

/**
 * 注册中心 / 服务发现工厂。
 *
 * 上层 bootstrap 和业务代码不应该直接 new 某个具体注册中心实现，
 * 而应该统一通过工厂按配置拿到注册与发现能力。
 *
 * 这样将来切换注册中心后端时，改动范围就主要集中在工厂和具体实现层。
 */
public final class ServiceRegistryFactory {
    private ServiceRegistryFactory() {
    }

    /** 对外暴露的创建入口，语义上表示“创建服务注册能力”。 */
    public static ServiceRegistry create(RpcFrameworkConfig config) {
        return createRegistry(config);
    }

    /**
     * 根据配置选择具体注册中心实现。
     *
     * 当前项目默认和主要实现是 ZooKeeper，
     * 但这里保留了按 RegistryType 分发的扩展点。
     */
    public static ServiceRegistry createRegistry(RpcFrameworkConfig config) {
        RegistryType registryType = config.getRegistryType();
        if (registryType == null) {
            registryType = RegistryType.ZOOKEEPER;
        }

        switch (registryType) {
            case ZOOKEEPER:
            default:
                return new ZooKeeperRegistryImpl(config.getRegistryAddress(), config.getRegistryTimeout());
        }
    }

    /**
     * 创建服务发现能力。
     *
     * 当前实现中，注册和发现共用同一个 ZooKeeper 实现类，
     * 但对外仍然保持 ServiceRegistry / ServiceDiscovery 两套抽象，
     * 方便后续把“注册地址管理”和“消费端发现”独立演进。
     */
    public static ServiceDiscovery createDiscovery(RpcFrameworkConfig config) {
        return (ServiceDiscovery) createRegistry(config);
    }
}
