package com.rpc.core.discovery;

/**
 * 服务发现抽象。
 *
 * 这一层对 consumer 暴露的是“如何拿到某个服务当前可用的 provider 地址列表”。
 * 当前项目里由 ZooKeeper 实现，但这里保留接口抽象，方便后续切换其他注册中心。
 */
public interface ServiceDiscovery {
    /** 直接发现某个服务的当前实例快照。 */
    ServiceInstancesSnapshot discover(String serviceName);

    /** 订阅某个服务的实例变更，并返回首次快照。 */
    ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener);

    /** 取消订阅某个服务的实例变更。 */
    void unsubscribe(String serviceName, ServiceChangeListener listener);

    /** 关闭底层资源，例如注册中心连接。 */
    void close();
}
