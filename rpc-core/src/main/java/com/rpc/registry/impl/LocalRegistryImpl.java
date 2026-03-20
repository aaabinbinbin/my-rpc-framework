package com.rpc.registry.impl;

import com.rpc.registry.LocalRegistry;
import com.rpc.registry.ServiceRegistry;
import com.rpc.transport.netty.server.statistics.ServiceStatistics;
import com.rpc.transport.netty.server.statistics.StatisticsManager;
import lombok.extern.slf4j.Slf4j;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 本地服务注册表实现
 * 使用 ConcurrentHashMap 保证线程安全
 */
@Slf4j
public class LocalRegistryImpl implements LocalRegistry {
    /**
     * 存储服务映射
     * key: 服务名称（接口全限定名）
     * value: 服务实现类
     */
    private static final Map<String, Object> SERVICE_MAP = new ConcurrentHashMap<>();

    // ZooKeeper 注册中心
    private final ServiceRegistry serviceRegistry;
    // 服务器地址
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
            throw new IllegalArgumentException("服务名称不能为空");
        }
        if (serviceInstance == null) {
            throw new IllegalArgumentException("服务实例不能为空");
        }
        
        // 1. 先注册到 ZooKeeper（外部系统优先）
        if (serviceRegistry != null) {
            try {
                InetSocketAddress address = new InetSocketAddress(host, port);
                serviceRegistry.register(serviceName, address);
                log.info("远程服务注册成功：{}@{}:{}", serviceName, host, port);
            } catch (Exception e) {
                log.error("远程服务注册失败：{}@{}:{}", serviceName, host, port, e);
                // 关键：如果 ZooKeeper 注册失败，不注册到本地，保证一致性
                throw new RuntimeException("远程服务注册失败，服务未注册", e);
            }
        }
        
        // 2. ZooKeeper 注册成功后，再注册到本地
        SERVICE_MAP.put(serviceName, serviceInstance);
        
        // 3. 注册统计信息（即使失败也不影响主流程）
        try {
            ServiceStatistics statistics = new ServiceStatistics(serviceName);
            StatisticsManager.getInstance().register(serviceName);
        } catch (Exception e) {
            log.warn("统计信息注册失败（不影响服务）: {}", serviceName, e);
        }
        
        log.info("服务注册成功：{} -> {}", serviceName, serviceInstance.getClass());
    }

    @Override
    public Object getService(String serviceName) {
        Object serviceInstance = SERVICE_MAP.get(serviceName);
        if (serviceInstance == null) {
            log.error("服务未找到：{}", serviceName);
            throw new RuntimeException("服务未找到：" + serviceName);
        }
        return serviceInstance;
    }

    @Override
    public void unregister(String serviceName) {
        // 1. 先从 ZooKeeper 注销（外部系统优先）
        if (serviceRegistry != null) {
            try {
                InetSocketAddress address = new InetSocketAddress(host, port);
                serviceRegistry.unregister(serviceName, address);
                log.info("远程服务注销成功：{}@{}:{}", serviceName, host, port);
            } catch (Exception e) {
                log.error("远程服务注销失败：{}@{}:{}", serviceName, host, port, e);
                // 关键：如果 ZooKeeper 注销失败，不删除本地数据，保证一致性
                throw new RuntimeException("远程服务注销失败，服务未注销", e);
            }
        }
        
        // 2. ZooKeeper 注销成功后，再从本地移除
        SERVICE_MAP.remove(serviceName);
        
        // 3. 移除统计信息（即使失败也不影响主流程）
        try {
            StatisticsManager.getInstance().remove(serviceName);
        } catch (Exception e) {
            log.warn("统计信息移除失败（不影响服务）: {}", serviceName, e);
        }
        
        log.info("服务注销成功：{}", serviceName);
    }

    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }
}
