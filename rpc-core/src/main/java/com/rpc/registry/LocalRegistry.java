package com.rpc.registry;

/**
 * 本地服务注册表
 * 用于存储和管理本地服务实例
 */
public interface LocalRegistry {
    /**
     * 注册服务
     * @param serviceName 服务名称（接口全限定名）
     * @param serviceImpl 服务实现类
     */
    void register(String serviceName, Class<?> serviceImpl);

    /**
     * 获取服务实现类
     * @param serviceName 服务名称
     * @return 服务实现类
     */
    Class<?> getService(String serviceName);

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
