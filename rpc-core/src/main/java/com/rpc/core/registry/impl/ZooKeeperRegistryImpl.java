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

@Slf4j
public class ZooKeeperRegistryImpl implements ServiceRegistry, ServiceDiscovery {
    /**
     * 节点布局：
     * /rpc/{serviceName}/{host-port}
     * 提供者实例地址使用临时子节点保存，这样进程宕机或 ZooKeeper 会话失效后会自动消失。
     */
    private static final String ZK_ROOT = "/rpc";

    private final ZooKeeper zooKeeper;
    private final Map<String, List<String>> registeredServices = new ConcurrentHashMap<>();
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

    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        return discover(serviceName).getAddresses();
    }

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

    @Override
    public void close() {
        try {
            zooKeeper.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Interrupted while closing ZooKeeper", e);
        }
    }

    private void ensureRootPath() throws KeeperException, InterruptedException {
        if (zooKeeper.exists(ZK_ROOT, false) == null) {
            zooKeeper.create(ZK_ROOT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    private void ensureServicePath(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        if (zooKeeper.exists(servicePath, false) == null) {
            zooKeeper.create(servicePath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    private ServiceInstancesSnapshot watchServiceChildren(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        Stat stat = zooKeeper.exists(servicePath, false);
        if (stat == null) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        // ZooKeeper watcher 是一次性的，因此每次刷新都要重新注册，
        // 这样后续子节点变化仍然能继续收到通知。
        List<String> children = zooKeeper.getChildren(servicePath, event -> handleChildChange(serviceName, event));
        return notifyListeners(serviceName, children);
    }

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

    private String buildServicePath(String serviceName) {
        return ZK_ROOT + "/" + serviceName;
    }

    private String buildAddressPath(String serviceName, InetSocketAddress address) {
        return buildServicePath(serviceName) + "/" + addressToString(address);
    }

    private String addressToString(InetSocketAddress address) {
        return address.getHostString() + "-" + address.getPort();
    }

    private InetSocketAddress stringToAddress(String value) {
        String[] parts = value.split("-");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }
}
