package com.rpc.core.registry.factory;

import com.rpc.core.config.RpcFrameworkConfig;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.registry.RegistryType;
import com.rpc.core.registry.ServiceRegistry;
import com.rpc.core.registry.impl.ZooKeeperRegistryImpl;

public final class ServiceRegistryFactory {
    private ServiceRegistryFactory() {
    }

    public static ServiceRegistry create(RpcFrameworkConfig config) {
        return createRegistry(config);
    }

    public static ServiceRegistry createRegistry(RpcFrameworkConfig config) {
        RegistryType registryType = config.getRegistryType();
        if (registryType == null) {
            registryType = RegistryType.ZOOKEEPER;
        }

    // Bootstrap 和上层代码依赖工厂而不是直接 new 具体实现，
    // 这样切换注册中心后端时，影响范围就能收敛在工厂层。
        switch (registryType) {
            case ZOOKEEPER:
            default:
                return new ZooKeeperRegistryImpl(config.getRegistryAddress(), config.getRegistryTimeout());
        }
    }

    public static ServiceDiscovery createDiscovery(RpcFrameworkConfig config) {
    // 注册与发现虽然当前共用 ZooKeeper 实现，
    // 但对外仍拆成不同抽象，方便后续分别演进。
        return (ServiceDiscovery) createRegistry(config);
    }
}

