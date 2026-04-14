package com.rpc.core.discovery;

/**
 * 服务实例变更监听器。
 *
 * 所处阶段：注册中心监听到 provider 列表变化后，通知 ServiceDirectory 更新本地快照。
 * 主要职责：把注册中心 watcher 回调转换成框架内部的不可变服务实例快照。
 */
@FunctionalInterface
public interface ServiceChangeListener {
    /**
     * 处理服务实例变化。
     *
     * 边界处理：snapshot 可能是空实例列表，表示服务当前无可用 provider。
     */
    void onChange(ServiceInstancesSnapshot snapshot);
}

