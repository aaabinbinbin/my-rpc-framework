package com.rpc.registry.factory;

import com.rpc.config.RpcFrameworkConfig;
import com.rpc.registry.RegistryType;
import com.rpc.registry.ServiceRegistry;
import com.rpc.registry.impl.ZooKeeperRegistryImpl;

public final class ServiceRegistryFactory {
    private ServiceRegistryFactory() {
    }

    public static ServiceRegistry create(RpcFrameworkConfig config) {
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
}
