package com.rpc.core.registry;

/**
 * provider 本地注册表抽象。
 *
 * 注意这里不是“注册中心”，而是 provider 进程内部维护的一张映射表：
 * serviceName -> serviceInstance
 *
 * 当请求真正到达某个 provider 之后，
 * 服务端就是靠这张表把 serviceName 解析成实际要调用的本地对象。
 */
public interface LocalRegistry {
    /** 注册一个本地服务实例。 */
    void register(String serviceName, Object serviceInstance);

    /** 根据服务名获取本地服务对象。 */
    Object getService(String serviceName);

    /** 取消注册一个本地服务。 */
    void unregister(String serviceName);

    /** 判断某个服务是否已经注册。 */
    boolean contains(String serviceName);

    /** 遍历当前已注册的所有服务名。 */
    Iterable<String> serviceNames();
}
