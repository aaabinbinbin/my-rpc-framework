package com.rpc.core.registry.impl;

import com.rpc.core.discovery.ServiceChangeListener;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.discovery.ServiceInstancesSnapshot;
import com.rpc.core.registry.ServiceRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.ZooDefs;
import org.apache.zookeeper.ZooKeeper;
import org.apache.zookeeper.data.Stat;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;
import java.util.concurrent.CountDownLatch;

/**
 * 基于 ZooKeeper 的注册中心实现。
 *
 * 这个类同时实现了：
 * 1. ServiceRegistry：provider 侧把自己的地址注册到注册中心。
 * 2. ServiceDiscovery：consumer 侧从注册中心发现并订阅服务地址。
 *
 * 当前节点布局约定为：
 * /rpc/{serviceName}/{host-port}
 */
@Slf4j
public class ZooKeeperRegistryImpl implements ServiceRegistry, ServiceDiscovery {
    /**
     * 注册中心根节点。
     * provider 实例地址使用临时子节点保存，这样 provider 进程宕机或会话失效后会自动消失。
     */
    private static final String ZK_ROOT = "/rpc";

    /** ZooKeeper 客户端连接。 */
    private final ZooKeeper zooKeeper;
    /** 当前进程注册过的服务地址记录。 */
    private final Map<String, List<String>> registeredServices = new ConcurrentHashMap<>();
    /** consumer 侧监听器集合，按服务名分组。 */
    private final Map<String, Set<ServiceChangeListener>> listeners = new ConcurrentHashMap<>();

    public ZooKeeperRegistryImpl(String connectString, int sessionTimeout) {
        try {
            CountDownLatch countDownLatch = new CountDownLatch(1);
            zooKeeper = new ZooKeeper(connectString, sessionTimeout, event -> {
                if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
                    log.info("ZooKeeper connected");
                    countDownLatch.countDown();
                } else if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
                    log.warn("ZooKeeper disconnected");
                } else if (event.getState() == Watcher.Event.KeeperState.Expired) {
                    log.error("ZooKeeper session expired");
                }
            });

            countDownLatch.await();
            ensureRootPath();
        } catch (Exception e) {
            log.error("Failed to connect ZooKeeper", e);
            throw new RuntimeException("Failed to connect ZooKeeper", e);
        }
    }

    /**
     * provider 侧注册服务地址。
     *
     * 注册时会确保服务路径存在，然后为当前地址创建一个临时节点。
     */
    @Override
    public void register(String serviceName, InetSocketAddress address) {
        try {
            ensureServicePath(serviceName);
            String addressPath = buildAddressPath(serviceName, address);

            if (zooKeeper.exists(addressPath, false) == null) {
                zooKeeper.create(addressPath,
                        addressToString(address).getBytes(StandardCharsets.UTF_8),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
            }

            registeredServices
                    .computeIfAbsent(serviceName, key -> new ArrayList<>())
                    .add(addressToString(address));
            log.info("Registered service in ZooKeeper: {} -> {}", serviceName, address);
        } catch (Exception e) {
            log.error("Failed to register service in ZooKeeper: {}", serviceName, e);
            throw new RuntimeException("Failed to register service in ZooKeeper", e);
        }
    }

    /** provider 侧注销服务地址。 */
    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        try {
            String addressPath = buildAddressPath(serviceName, address);
            if (zooKeeper.exists(addressPath, false) != null) {
                zooKeeper.delete(addressPath, -1);
            }

            List<String> addresses = registeredServices.get(serviceName);
            if (addresses != null) {
                addresses.remove(addressToString(address));
                if (addresses.isEmpty()) {
                    registeredServices.remove(serviceName);
                }
            }
            log.info("Unregistered service in ZooKeeper: {} -> {}", serviceName, address);
        } catch (Exception e) {
            log.error("Failed to unregister service in ZooKeeper: {}", serviceName, e);
            throw new RuntimeException("Failed to unregister service in ZooKeeper", e);
        }
    }

    /** 兼容 lookup 风格调用，底层仍然复用 discover。 */
    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        return discover(serviceName).getAddresses();
    }

    /**
     * consumer 侧直接发现服务实例列表。
     *
     * 这里不挂 watcher，只做一次性读取。
     */
    @Override
    public ServiceInstancesSnapshot discover(String serviceName) {
        try {
            String servicePath = buildServicePath(serviceName);
            if (zooKeeper.exists(servicePath, false) == null) {
                return ServiceInstancesSnapshot.of(serviceName, List.of());
            }

            List<String> children = zooKeeper.getChildren(servicePath, false);
            List<InetSocketAddress> addresses = new ArrayList<>();
            for (String child : children) {
                addresses.add(stringToAddress(child));
            }
            return ServiceInstancesSnapshot.of(serviceName, addresses);
        } catch (Exception e) {
            log.error("Failed to discover service from ZooKeeper: {}", serviceName, e);
            throw new RuntimeException("Failed to discover service from ZooKeeper", e);
        }
    }

    /**
     * 订阅某个服务的实例变更。
     *
     * 首次订阅时会建立 watcher，并返回当前快照。
     */
    @Override
    public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
        listeners.computeIfAbsent(serviceName, key -> new CopyOnWriteArraySet<>()).add(listener);
        try {
            return watchServiceChildren(serviceName);
        } catch (Exception e) {
            log.error("Failed to subscribe service in ZooKeeper: {}", serviceName, e);
            throw new RuntimeException("Failed to subscribe service in ZooKeeper", e);
        }
    }

    /** 取消订阅。 */
    @Override
    public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null) {
            serviceListeners.remove(listener);
            if (serviceListeners.isEmpty()) {
                listeners.remove(serviceName);
            }
        }
    }

    /** 关闭 ZooKeeper 连接。 */
    @Override
    public void close() {
        try {
            zooKeeper.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing ZooKeeper", e);
        }
    }

    /** 确保根路径存在。 */
    private void ensureRootPath() throws KeeperException, InterruptedException {
        if (zooKeeper.exists(ZK_ROOT, false) == null) {
            zooKeeper.create(ZK_ROOT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    /** 确保某个服务的父路径存在。 */
    private void ensureServicePath(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        if (zooKeeper.exists(servicePath, false) == null) {
            zooKeeper.create(servicePath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    /**
     * 读取服务节点的子节点并重新注册 watcher。
     *
     * ZooKeeper 的 watcher 是一次性的，因此每次子节点变化后都要重新注册，
     * 否则后续变化就不会再收到通知。
     */
    private ServiceInstancesSnapshot watchServiceChildren(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        Stat stat = zooKeeper.exists(servicePath, false);
        if (stat == null) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        List<String> children = zooKeeper.getChildren(servicePath, event -> handleChildChange(serviceName, event));
        return notifyListeners(serviceName, children);
    }

    /** watcher 回调：当子节点变化时刷新当前服务快照。 */
    private void handleChildChange(String serviceName, WatchedEvent event) {
        if (event.getType() != Watcher.Event.EventType.NodeChildrenChanged) {
            return;
        }
        try {
            watchServiceChildren(serviceName);
        } catch (Exception e) {
            log.error("Failed to refresh children watcher for service {}", serviceName, e);
        }
    }

    /**
     * 把 ZooKeeper 子节点列表转换成地址快照，并通知所有监听器。
     */
    private ServiceInstancesSnapshot notifyListeners(String serviceName, List<String> children) {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (String child : children) {
            addresses.add(stringToAddress(child));
        }

        ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of(serviceName, addresses);
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null) {
            for (ServiceChangeListener listener : serviceListeners) {
                listener.onChange(snapshot);
            }
        }
        return snapshot;
    }

    /** 构造服务路径，例如 /rpc/com.rpc.HelloService。 */
    private String buildServicePath(String serviceName) {
        return ZK_ROOT + "/" + serviceName;
    }

    /** 构造地址节点路径，例如 /rpc/com.rpc.HelloService/127.0.0.1-8080。 */
    private String buildAddressPath(String serviceName, InetSocketAddress address) {
        return buildServicePath(serviceName) + "/" + addressToString(address);
    }

    /** 把地址对象转换成节点名字符串。 */
    private String addressToString(InetSocketAddress address) {
        return address.getHostString() + "-" + address.getPort();
    }

    /** 把节点名字符串还原成地址对象。 */
    private InetSocketAddress stringToAddress(String value) {
        String[] parts = value.split("-");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
