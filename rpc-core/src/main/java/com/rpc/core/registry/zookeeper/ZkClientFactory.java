package com.rpc.core.registry.zookeeper;

import org.apache.zookeeper.Watcher;

/**
 * ZooKeeper 客户端工厂。
 *
 * 所处阶段：注册中心实现初始化时。
 * 主要职责：集中创建 ZkClient，避免 ZooKeeperRegistryImpl 直接依赖具体构造逻辑。
 */
interface ZkClientFactory {
    /**
     * 创建 ZooKeeper 客户端。
     *
     * 边界处理：连接字符串或超时非法时由底层客户端抛出异常，启动阶段直接失败。
     */
    ZkClient create(String connectString, int sessionTimeout, Watcher watcher) throws Exception;
}
