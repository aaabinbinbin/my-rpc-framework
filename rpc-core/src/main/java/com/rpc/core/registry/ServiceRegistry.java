package com.rpc.core.registry;

import java.net.InetSocketAddress;
import java.util.List;

/**
 * 服务注册中心契约。
 */
public interface ServiceRegistry {
    void register(String serviceName, InetSocketAddress address);

    void unregister(String serviceName, InetSocketAddress address);

    List<InetSocketAddress> lookup(String serviceName);

    void close();
}
