package com.rpc.core.registry.impl;

import com.rpc.core.observability.metrics.ServiceMetricsManager;
import com.rpc.core.registry.LocalRegistry;
import com.rpc.core.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 进程内注册表，用于让服务提供端持有服务实例。
 * 它保存真实服务对象，供本地反射调用使用，
 * 并可选地把同一个服务/地址对发布到外部注册中心。
 */
@Slf4j
public class LocalRegistryImpl implements LocalRegistry {
    /**
     * 当前设计下，服务提供端实例在进程内是单例的，
     * 因此同一个 JVM 里的多个服务端启动器会共享这张本地注册表。
     */
    private static final Map<String, Object> SERVICE_MAP = new ConcurrentHashMap<>();

    private final ServiceRegistry serviceRegistry;
    private final String host;
    private final int port;

    public LocalRegistryImpl(ServiceRegistry serviceRegistry, String host, int port) {
        this.serviceRegistry = serviceRegistry;
        this.host = host;
        this.port = port;
    }

    @Override
    public void register(String serviceName, Object serviceInstance) {
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalArgumentException("serviceName must not be empty");
        }
        if (serviceInstance == null) {
            throw new IllegalArgumentException("serviceInstance must not be null");
        }

        if (serviceRegistry != null) {
            // 本地暴露和外部发布是刻意拆开的：
            // 服务端调用依赖内存中的服务实例，消费端只关心从注册中心发现地址。
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

    @Override
    public Object getService(String serviceName) {
        Object serviceInstance = SERVICE_MAP.get(serviceName);
        if (serviceInstance == null) {
            log.error("Service not found in local registry: {}", serviceName);
            throw new RuntimeException("Service not found: " + serviceName);
        }
        return serviceInstance;
    }

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

    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }

    @Override
    public Iterable<String> serviceNames() {
        return SERVICE_MAP.keySet();
    }
}
