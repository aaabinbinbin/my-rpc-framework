package com.rpc.registry;

import com.rpc.transport.netty.server.statistics.ServiceStatistics;

/**
 * 本地服务注册表
 * 用于存储和管理本地服务实例
 */
public interface LocalRegistry {
    /**
     * 注册服务
     * @param serviceName 服务名称（接口全限定名）
     * @param serviceInstance 服务实现实例（单例）
     */
    void register(String serviceName, Object serviceInstance);

    /**
     * 获取服务实现类
     * @param serviceName 服务名称
     * @return 服务实例
     */
    Object getService(String serviceName);

    /**
     * 注销服务
     * @param serviceName 服务名称
     */
    void unregister(String serviceName);

    /**
     * 检查服务是否已注册
     */
    boolean contains(String serviceName);
}
