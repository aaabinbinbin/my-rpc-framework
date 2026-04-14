package com.rpc.core.registry.zookeeper;

import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;

import java.util.List;

/**
 * ZooKeeper 客户端最小能力抽象。
 *
 * 所处阶段：ZooKeeperRegistryImpl 访问注册中心时。
 * 主要职责：隔离 ZooKeeper 原生客户端，便于单元测试注入 fake client，也便于后续替换 Curator 等实现。
 */
interface ZkClient {
    /** 判断节点是否存在，可选择注册默认 watcher。 */
    Stat exists(String path, boolean watch) throws KeeperException, InterruptedException;

    /** 创建节点，调用方决定节点类型和 ACL。 */
    String create(String path, byte[] data, List<org.apache.zookeeper.data.ACL> acl, org.apache.zookeeper.CreateMode createMode)
            throws KeeperException, InterruptedException;

    /** 获取子节点列表，可选择使用默认 watcher。 */
    List<String> getChildren(String path, boolean watch) throws KeeperException, InterruptedException;

    /** 获取子节点列表，并绑定指定 watcher。 */
    List<String> getChildren(String path, Watcher watcher) throws KeeperException, InterruptedException;

    /** 删除指定版本节点。 */
    void delete(String path, int version) throws KeeperException, InterruptedException;

    /** 关闭底层 ZooKeeper 连接。 */
    void close() throws InterruptedException;
}
