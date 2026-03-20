package com.rpc.registry.impl;

import com.rpc.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.*;
import org.apache.zookeeper.data.Stat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;

/**
 * 基于 ZooKeeper 的服务注册中心实现
 */
@Slf4j
public class ZooKeeperRegistryImpl implements ServiceRegistry {
    // ZooKeeper 根路径
    private static final String ZK_ROOT = "/rpc";

    // ZooKeeper 客户端
    private final ZooKeeper zooKeeper;

    // 缓存已注册的服务地址，避免重复注册
    private final Map<String, List<String>> registeredServices = new ConcurrentHashMap<>();

    /**
     * 构造方法
     * @param connectString ZooKeeper 连接字符串，如："8.134.204.101:2181"
     * @param sessionTimeout 会话超时时间（毫秒）
     */
    public ZooKeeperRegistryImpl(String connectString, int sessionTimeout) {
        try {
            // 创建 CountDownLatch 用于等待连接建立
            CountDownLatch countDownLatch = new CountDownLatch(1);

            // 创建 ZooKeeper 连接
            zooKeeper = new ZooKeeper(connectString, sessionTimeout, event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    log.info("ZooKeeper 连接成功");
                    countDownLatch.countDown();
                } else if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    log.warn("ZooKeeper 连接断开");
                } else if (event.getState() == Watcher.Event.KeeperState.Expired) {
                    log.error("ZooKeeper 会话过期");
                }
            });

            // 等待连接建立
            countDownLatch.await();

            // 确保根节点存在
            ensureRootPath();

        } catch (Exception e) {
            log.error("连接 ZooKeeper 失败", e);
            throw new RuntimeException("连接 ZooKeeper 失败", e);
        }
    }

    /**
     * 确保根节点存在
     */
    private void ensureRootPath() throws KeeperException, InterruptedException {
        Stat stat = zooKeeper.exists(ZK_ROOT, false);
        if (stat == null) {
            // 根节点不存在，创建持久节点
            zooKeeper.create(ZK_ROOT, "".getBytes(StandardCharsets.UTF_8),
                    ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
            log.info("创建 ZooKeeper 根节点：{}", ZK_ROOT);
        }
    }

    @Override
    public void register(String serviceName, InetSocketAddress address) {
        try {
            String servicePath = ZK_ROOT + "/" + serviceName;

            // 1. 确保服务父节点存在（持久节点）
            Stat stat = zooKeeper.exists(servicePath, false);
            if (stat == null) {
                zooKeeper.create(servicePath, "".getBytes(StandardCharsets.UTF_8),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
                log.info("创建服务节点：{}", servicePath);
            }

            // 2. 创建服务地址节点（临时节点）
            String addressPath = servicePath + "/" + addressToPath(address);
            Stat addrStat = zooKeeper.exists(addressPath, false);
            if (addrStat == null) {
                // 存储额外的元数据信息
                byte[] metadata = buildMetadata(address);
                zooKeeper.create(addressPath, metadata,
                        ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.EPHEMERAL);
                log.info("注册地址节点：{}", addressPath);

                // 记录已注册的服务
                registeredServices.computeIfAbsent(serviceName, k -> new ArrayList<>())
                        .add(addressToPath(address));
            }

        } catch (Exception e) {
            log.error("注册服务失败：{}@{}", serviceName, address, e);
            throw new RuntimeException("注册服务失败", e);
        }
    }

    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        try {
            String addressPath = ZK_ROOT + "/" + serviceName + "/" + addressToPath(address);

            // 删除临时节点
            Stat stat = zooKeeper.exists(addressPath, false);
            if (stat != null) {
                zooKeeper.delete(addressPath, -1);
                log.info("注销服务地址：{}", addressPath);
            }

            // 从缓存中移除
            List<String> addresses = registeredServices.get(serviceName);
            if (addresses != null) {
                addresses.remove(addressToPath(address));
            }

        } catch (Exception e) {
            log.error("注销服务失败：{}@{}", serviceName, address, e);
            throw new RuntimeException("注销服务失败", e);
        }
    }

    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        try {
            String servicePath = ZK_ROOT + "/" + serviceName;

            // 获取服务下的所有子节点（即所有服务提供者地址）
            List<String> children = zooKeeper.getChildren(servicePath, false);

            if (children.isEmpty()) {
                log.warn("未找到服务：{}", serviceName);
                return new ArrayList<>();
            }

            // 转换为 InetSocketAddress 列表
            List<InetSocketAddress> addresses = new ArrayList<>();
            for (String child : children) {
                addresses.add(pathToAddress(child));
            }

            log.info("发现服务 {}，共 {} 个提供者：{}", serviceName, children.size(), addresses);
            return addresses;

        } catch (Exception e) {
            log.error("查找服务失败：{}", serviceName, e);
            throw new RuntimeException("查找服务失败", e);
        }
    }

    @Override
    public void close() {
        try {
            if (zooKeeper != null && zooKeeper.getState().isAlive()) {
                zooKeeper.close();
                log.info("关闭 ZooKeeper 连接");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("关闭 ZooKeeper 连接失败", e);
        }
    }

    // ==================== 辅助方法 ====================

    /**
     * 将地址转换为路径节点名称
     * 例如：192.168.1.10:8080 -> 192.168.1.10-8080
     */
    private String addressToPath(InetSocketAddress address) {
        return address.getAddress().getHostAddress() + "-" + address.getPort();
    }

    /**
     * 将路径节点名称转换为地址
     * 例如：192.168.1.10-8080 -> 192.168.1.10:8080
     */
    private InetSocketAddress pathToAddress(String path) {
        String[] parts = path.split("-");
        String host = parts[0];
        int port = Integer.parseInt(parts[1]);
        return new InetSocketAddress(host, port);
    }

    /**
     * 构建元数据信息
     */
    private byte[] buildMetadata(InetSocketAddress address) {
        // 可以在这里添加更多元数据，如权重、版本等
        // 暂时返回空字节数组
        return "".getBytes(StandardCharsets.UTF_8);
    }
}
