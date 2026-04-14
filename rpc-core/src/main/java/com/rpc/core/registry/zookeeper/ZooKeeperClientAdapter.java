package com.rpc.core.registry.zookeeper;

import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.ACL;
import org.apache.zookeeper.data.Stat;

import java.util.List;

/**
 * ZooKeeper 原生客户端适配器。
 *
 * 所处阶段：ZooKeeperRegistryImpl 执行注册、发现、订阅等操作时。
 * 主要职责：把 ZooKeeper SDK 的方法适配为项目内部 ZkClient 接口，便于单元测试替换为 fake client。
 */
final class ZooKeeperClientAdapter implements ZkClient {
    /** 被适配的 ZooKeeper 原生客户端。 */
    private final ZooKeeper delegate;

    /**
     * 创建 ZooKeeper 客户端适配器。
     */
    ZooKeeperClientAdapter(ZooKeeper delegate) {
        this.delegate = delegate;
    }

    /** 委托 ZooKeeper 判断节点是否存在。 */
    @Override
    public Stat exists(String path, boolean watch) throws KeeperException, InterruptedException {
        return delegate.exists(path, watch);
    }

    /** 委托 ZooKeeper 创建节点。 */
    @Override
    public String create(String path, byte[] data, List<ACL> acl, CreateMode createMode)
            throws KeeperException, InterruptedException {
        return delegate.create(path, data, acl, createMode);
    }

    /** 委托 ZooKeeper 获取子节点列表，可使用默认 watcher。 */
    @Override
    public List<String> getChildren(String path, boolean watch) throws KeeperException, InterruptedException {
        return delegate.getChildren(path, watch);
    }

    /** 委托 ZooKeeper 获取子节点列表，并绑定指定 watcher。 */
    @Override
    public List<String> getChildren(String path, Watcher watcher) throws KeeperException, InterruptedException {
        return delegate.getChildren(path, watcher);
    }

    /** 委托 ZooKeeper 删除节点。 */
    @Override
    public void delete(String path, int version) throws KeeperException, InterruptedException {
        delegate.delete(path, version);
    }

    /** 关闭底层 ZooKeeper 连接。 */
    @Override
    public void close() throws InterruptedException {
        delegate.close();
    }
}
