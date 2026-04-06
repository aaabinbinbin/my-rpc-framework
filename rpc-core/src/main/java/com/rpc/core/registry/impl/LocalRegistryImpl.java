package com.rpc.core.registry.impl;

import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * provider 进程内本地注册表实现。
 *
 * 这个类保存的是真正的服务对象实例，
 * 供 provider 在收到业务请求后根据 serviceName 做本地分发。
 *
 * 它和外部注册中心的区别一定要分清：
 * 1. 外部注册中心保存“服务 -> 地址”的关系，供 consumer 做服务发现。
 * 2. 本地注册表保存“服务 -> 对象实例”的关系，供 provider 做本地方法调用。
 */
@Slf4j
public class LocalRegistryImpl implements LocalRegistry {
    /**
     * 当前实现下，本地注册表以 JVM 进程为作用域。
     * 同一个进程内如果创建多个 provider 组件实例，
     * 仍然共享这一份本地服务对象映射。
     */
    private static final Map<String, Object> SERVICE_MAP = new ConcurrentHashMap<>();

    /** 外部注册中心客户端，存在时会把当前 provider 地址同步注册到注册中心。 */
    private final ServiceRegistry serviceRegistry;

    /** 当前 provider 对外暴露的 host。 */
    private final String host;

    /** 当前 provider 对外暴露的 port。 */
    private final int port;

    public LocalRegistryImpl(ServiceRegistry serviceRegistry, String host, int port) {
        this.serviceRegistry = serviceRegistry;
        this.host = host;
        this.port = port;
    }

    /**
     * 注册一个本地服务。
     * 这里实际上做了两件事：
     * 1. 把服务对象放进进程内的本地映射表。
     * 2. 如果存在外部注册中心，就同步把服务地址注册出去。
     */
    @Override
    public void register(String serviceName, Object serviceInstance) {
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalArgumentException("serviceName must not be empty");
        }
        if (serviceInstance == null) {
            throw new IllegalArgumentException("serviceInstance must not be null");
        }

        if (serviceRegistry != null) {
            try {
                serviceRegistry.register(serviceName, new InetSocketAddress(host, port));
                log.info("Registered service to registry center: {}@{}:{}", serviceName, host, port);
            } catch (Exception e) {
                log.error("Failed to register service to registry center: {}@{}:{}",
                        serviceName, host, port, e);
                throw new RuntimeException("Failed to register service to registry center", e);
            }
        }

        SERVICE_MAP.put(serviceName, serviceInstance);

        try {
            ServiceMetricsManager.getInstance().register(serviceName);
        } catch (Exception e) {
            log.warn("Failed to initialize metrics for service {}", serviceName, e);
        }

        log.info("Local service registered: {} -> {}", serviceName, serviceInstance.getClass().getName());
    }

    /**
     * 根据服务名获取本地服务对象。
     *
     * 如果 provider 能收到请求但这里取不到对象，
     * 往往说明服务导出流程存在问题。
     */
    @Override
    public Object getService(String serviceName) {
        Object serviceInstance = SERVICE_MAP.get(serviceName);
        if (serviceInstance == null) {
            log.error("Service not found in local registry: {}", serviceName);
            throw new RuntimeException("Service not found: " + serviceName);
        }
        return serviceInstance;
    }

    /**
     * 取消注册服务。
     *
     * 同样分两步：
     * 1. 从外部注册中心移除地址。
     * 2. 从本地映射表中移除对象。
     */
    @Override
    public void unregister(String serviceName) {
        if (serviceRegistry != null) {
            try {
                serviceRegistry.unregister(serviceName, new InetSocketAddress(host, port));
                log.info("Unregistered service from registry center: {}@{}:{}", serviceName, host, port);
            } catch (Exception e) {
                log.error("Failed to unregister service from registry center: {}@{}:{}",
                        serviceName, host, port, e);
                throw new RuntimeException("Failed to unregister service from registry center", e);
            }
        }

        SERVICE_MAP.remove(serviceName);

        try {
            ServiceMetricsManager.getInstance().remove(serviceName);
        } catch (Exception e) {
            log.warn("Failed to remove metrics for service {}", serviceName, e);
        }

        log.info("Local service unregistered: {}", serviceName);
    }

    /** 判断服务是否已在本地注册表中存在。 */
    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }

    /** 枚举当前进程内已经暴露出去的所有服务名。 */
    @Override
    public Iterable<String> serviceNames() {
        return SERVICE_MAP.keySet();
    }
}
