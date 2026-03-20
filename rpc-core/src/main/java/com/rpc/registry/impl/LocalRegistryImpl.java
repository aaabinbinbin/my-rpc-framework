package com.rpc.registry.impl;

import com.rpc.registry.LocalRegistry;
import com.rpc.transport.netty.server.statistics.ServiceStatistics;
import com.rpc.transport.netty.server.statistics.StatisticsManager;
import lombok.extern.slf4j.Slf4j;

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

    @Override
    public void register(String serviceName, Object serviceInstance) {
        if (serviceName == null || serviceName.isEmpty()) {
            throw new IllegalArgumentException("服务名称不能为空");
        }
        if (serviceInstance == null) {
            throw new IllegalArgumentException("服务实例不能为空");
        }
        SERVICE_MAP.put(serviceName, serviceInstance);
        // 创建并存储统计信息
        ServiceStatistics statistics = new ServiceStatistics(serviceName);
        // 同步注册统计信息
        StatisticsManager.getInstance().register(serviceName);
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
        SERVICE_MAP.remove(serviceName);
        // 同步移除统计信息
        StatisticsManager.getInstance().remove(serviceName);
        log.info("服务注销成功：{}", serviceName);
    }

    @Override
    public boolean contains(String serviceName) {
        return SERVICE_MAP.containsKey(serviceName);
    }
}
