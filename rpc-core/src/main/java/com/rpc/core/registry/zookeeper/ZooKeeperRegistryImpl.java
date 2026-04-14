package com.rpc.core.registry.zookeeper;

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
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 基于 ZooKeeper 的服务注册与发现实现。
 *
 * 所处阶段：provider 启动时注册服务、consumer 调用前发现服务、服务实例变化时推送更新。
 * 主要职责：维护 /rpc/{serviceName}/{host-port} 节点，使用临时节点表达 provider 存活状态，并在会话过期后恢复注册和订阅。
 *
 * 注意事项：ZooKeeper watcher 是一次性的，收到子节点变化后必须重新 watchServiceChildren 注册下一次 watcher。
 */
@Slf4j
public class ZooKeeperRegistryImpl implements ServiceRegistry, ServiceDiscovery {
    /** RPC 框架在 ZooKeeper 中使用的根路径。 */
    private static final String ZK_ROOT = "/rpc";

    /** ZooKeeper 连接串。 */
    private final String connectString;
    /** ZooKeeper session 超时时间。 */
    private final int sessionTimeout;
    /** ZooKeeper 客户端工厂，测试中可注入 fake client。 */
    private final ZkClientFactory zkClientFactory;
    /** 本客户端已注册的服务实例，用于 session 过期后恢复临时节点。 */
    private final Map<String, Set<String>> registeredServices = new ConcurrentHashMap<>();
    /** consumer 侧服务订阅监听器，用于子节点变化后回调 ServiceDirectory。 */
    private final Map<String, Set<ServiceChangeListener>> listeners = new ConcurrentHashMap<>();
    /** 防止多个 ZooKeeper 事件同时触发重复重连。 */
    private final AtomicBoolean reconnecting = new AtomicBoolean(false);
    /** 注册中心关闭标记。 */
    private final AtomicBoolean closed = new AtomicBoolean(false);

    /** 当前可用 ZooKeeper 客户端，session 过期后会被替换。 */
    private volatile ZkClient zkClient;
    /** 当前连接 watcher，保留字段便于调试和防止 watcher 被过早 GC。 */
    private volatile Watcher connectionWatcher;

    /**
     * 创建 ZooKeeper 注册中心实现。
     */
    public ZooKeeperRegistryImpl(String connectString, int sessionTimeout) {
        this(connectString, sessionTimeout, (address, timeout, watcher) ->
                new ZooKeeperClientAdapter(new ZooKeeper(address, timeout, watcher)));
    }

    /**
     * 创建 ZooKeeper 注册中心实现，允许测试注入客户端工厂。
     */
    ZooKeeperRegistryImpl(String connectString, int sessionTimeout, ZkClientFactory zkClientFactory) {
        this.connectString = connectString;
        this.sessionTimeout = sessionTimeout;
        this.zkClientFactory = zkClientFactory;
        this.zkClient = connect();
    }

    /**
     * 注册 provider 服务实例。
     *
     * 边界处理：先确保服务路径存在，再创建临时节点；节点已存在时跳过创建并刷新本地恢复表。
     */
    @Override
    public void register(String serviceName, InetSocketAddress address) {
        withRecovery(() -> {
            ensureServicePath(serviceName);
            String addressValue = addressToString(address);
            String addressPath = buildAddressPath(serviceName, address);

            ZkClient client = currentClient();
            if (client.exists(addressPath, false) == null) {
                client.create(addressPath,
                        addressValue.getBytes(StandardCharsets.UTF_8),
                        ZooDefs.Ids.OPEN_ACL_UNSAFE,
                        CreateMode.EPHEMERAL);
            }

            registeredServices
                    .computeIfAbsent(serviceName, key -> new CopyOnWriteArraySet<>())
                    .add(addressValue);
            log.info("Registered service in ZooKeeper: {} -> {}", serviceName, address);
            return null;
        });
    }

    /**
     * 注销 provider 服务实例。
     *
     * 边界处理：节点不存在时忽略；本地恢复表同步删除，避免 session 恢复时重新注册已注销实例。
     */
    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        withRecovery(() -> {
            String addressValue = addressToString(address);
            String addressPath = buildAddressPath(serviceName, address);
            ZkClient client = currentClient();
            if (client.exists(addressPath, false) != null) {
                client.delete(addressPath, -1);
            }

            Set<String> addresses = registeredServices.get(serviceName);
            if (addresses != null) {
                addresses.remove(addressValue);
                if (addresses.isEmpty()) {
                    registeredServices.remove(serviceName);
                }
            }
            log.info("Unregistered service in ZooKeeper: {} -> {}", serviceName, address);
            return null;
        });
    }

    /**
     * 查询服务实例地址列表。
     */
    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        return discover(serviceName).getAddresses();
    }

    /**
     * 发现服务实例快照。
     *
     * 边界处理：服务路径不存在时返回空快照，而不是抛异常。
     */
    @Override
    public ServiceInstancesSnapshot discover(String serviceName) {
        return withRecovery(() -> {
            String servicePath = buildServicePath(serviceName);
            ZkClient client = currentClient();
            if (client.exists(servicePath, false) == null) {
                return ServiceInstancesSnapshot.of(serviceName, List.of());
            }

            List<String> children = client.getChildren(servicePath, false);
            return ServiceInstancesSnapshot.of(serviceName, toAddresses(children));
        });
    }

    /**
     * 订阅服务实例变化。
     *
     * 注意事项：订阅成功后立即返回当前快照；watcher 注册失败时会回滚 listener，避免内存残留。
     */
    @Override
    public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
        Set<ServiceChangeListener> serviceListeners =
                listeners.computeIfAbsent(serviceName, key -> new CopyOnWriteArraySet<>());
        serviceListeners.add(listener);
        try {
            return watchServiceChildren(serviceName);
        } catch (Exception e) {
            serviceListeners.remove(listener);
            if (serviceListeners.isEmpty()) {
                listeners.remove(serviceName, serviceListeners);
            }
            log.warn("Failed to subscribe service in ZooKeeper: {}, reason={}", serviceName, e.getMessage());
            log.debug("Subscribe failure details for service {}", serviceName, e);
            throw new RuntimeException("Failed to subscribe service in ZooKeeper", e);
        }
    }

    /**
     * 取消服务订阅。
     */
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

    /**
     * 关闭 ZooKeeper 客户端。
     *
     * 注意事项：方法幂等，关闭后 currentClient 会抛出 IllegalStateException。
     */
    @Override
    public void close() {
        if (!closed.compareAndSet(false, true)) {
            return;
        }
        ZkClient client = zkClient;
        zkClient = null;
        if (client != null) {
            try {
                client.close();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Interrupted while closing ZooKeeper", e);
            }
        }
    }

    /**
     * 读取服务子节点并注册一次性 watcher。
     */
    private ServiceInstancesSnapshot watchServiceChildren(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        ZkClient client = currentClient();
        Stat stat = client.exists(servicePath, false);
        if (stat == null) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        List<String> children = client.getChildren(servicePath, event -> handleChildChange(serviceName, event));
        return notifyListeners(serviceName, children);
    }

    /**
     * 处理服务子节点变化事件。
     *
     * 边界处理：只处理 NodeChildrenChanged；没有订阅者时不重新注册 watcher，避免无效监听。
     */
    private void handleChildChange(String serviceName, WatchedEvent event) {
        if (event.getType() != Watcher.Event.EventType.NodeChildrenChanged) {
            return;
        }
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners == null || serviceListeners.isEmpty()) {
            log.debug("Skip watcher refresh because service has no subscribers: {}", serviceName);
            return;
        }
        try {
            watchServiceChildren(serviceName);
        } catch (Exception e) {
            log.error("Failed to refresh children watcher for service {}", serviceName, e);
        }
    }

    /**
     * 将最新服务实例快照通知给所有订阅者。
     */
    private ServiceInstancesSnapshot notifyListeners(String serviceName, List<String> children) {
        ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of(serviceName, toAddresses(children));
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners != null) {
            for (ServiceChangeListener listener : serviceListeners) {
                listener.onChange(snapshot);
            }
        }
        return snapshot;
    }

    /**
     * 确保根路径存在。
     */
    private void ensureRootPath() throws KeeperException, InterruptedException {
        ZkClient client = currentClient();
        if (client.exists(ZK_ROOT, false) == null) {
            client.create(ZK_ROOT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    /**
     * 确保服务路径存在。
     */
    private void ensureServicePath(String serviceName) throws KeeperException, InterruptedException {
        String servicePath = buildServicePath(serviceName);
        ZkClient client = currentClient();
        if (client.exists(servicePath, false) == null) {
            client.create(servicePath, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    /**
     * ZooKeeper session 过期后的重连恢复。
     *
     * 注意事项：恢复顺序为重新连接 -> 关闭旧连接 -> 恢复注册临时节点 -> 恢复订阅 watcher。
     */
    private void reconnectIfNecessary(ZkClient expectedClient) {
        if (closed.get()) {
            return;
        }
        synchronized (this) {
            if (closed.get() || zkClient != expectedClient || !reconnecting.compareAndSet(false, true)) {
                return;
            }
            try {
                log.warn("Reconnecting ZooKeeper after session expiration");
                ZkClient previousClient = zkClient;
                zkClient = connect();
                if (previousClient != null) {
                    try {
                        previousClient.close();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
                restoreRegisteredServices();
                restoreSubscriptions();
                log.info("ZooKeeper session recovered");
            } catch (RuntimeException e) {
                log.error("Failed to recover ZooKeeper session", e);
                throw e;
            } finally {
                reconnecting.set(false);
            }
        }
    }

    /**
     * 建立 ZooKeeper 连接并等待 SyncConnected。
     *
     * 边界处理：连接超时会关闭临时 client 并抛出异常，避免半初始化对象继续使用。
     */
    private ZkClient connect() {
        try {
            // 当前框架未使用 ZooKeeper SASL 认证，默认关闭可避免公网 IP 连接时在主机名规范化/认证准备阶段阻塞过久。
            System.setProperty("zookeeper.sasl.client",
                    System.getProperty("zookeeper.sasl.client", "false"));
            CountDownLatch latch = new CountDownLatch(1);
            final ZkClient[] clientHolder = new ZkClient[1];
            Watcher watcher = event -> handleConnectionEvent(clientHolder[0], event, latch);
            ZkClient client = zkClientFactory.create(connectString, sessionTimeout, watcher);
            clientHolder[0] = client;
            connectionWatcher = watcher;
            if (!latch.await(sessionTimeout, TimeUnit.MILLISECONDS)) {
                closeQuietly(client);
                throw new RuntimeException("Timed out waiting for ZooKeeper connection: " + connectString);
            }
            ensureRootPathWithClient(client);
            return client;
        } catch (Exception e) {
            throw new RuntimeException("Failed to connect ZooKeeper", e);
        }
    }

    /**
     * 处理 ZooKeeper 连接状态事件。
     */
    private void handleConnectionEvent(ZkClient sourceClient, WatchedEvent event, CountDownLatch latch) {
        if (event.getState() == Watcher.Event.KeeperState.SyncConnected) {
            log.info("ZooKeeper connected");
            latch.countDown();
            return;
        }
        if (event.getState() == Watcher.Event.KeeperState.Disconnected) {
            log.warn("ZooKeeper disconnected");
            return;
        }
        if (event.getState() == Watcher.Event.KeeperState.Expired) {
            log.warn("ZooKeeper session expired, schedule recovery");
            latch.countDown();
            if (!closed.get()) {
                Thread reconnectThread = new Thread(() -> reconnectIfNecessary(sourceClient), "zk-session-recover");
                reconnectThread.setDaemon(true);
                reconnectThread.start();
            }
        }
    }

    /**
     * 使用指定 client 确保根路径存在。
     */
    private void ensureRootPathWithClient(ZkClient client) throws KeeperException, InterruptedException {
        if (client.exists(ZK_ROOT, false) == null) {
            client.create(ZK_ROOT, new byte[0], ZooDefs.Ids.OPEN_ACL_UNSAFE, CreateMode.PERSISTENT);
        }
    }

    /**
     * 安静关闭 ZooKeeper client。
     */
    private void closeQuietly(ZkClient client) {
        try {
            client.close();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * 恢复本客户端曾经注册过的 provider 临时节点。
     */
    private void restoreRegisteredServices() {
        ensureRootPathUnchecked();
        registeredServices.forEach((serviceName, addresses) -> {
            ensureServicePathUnchecked(serviceName);
            for (String addressValue : addresses) {
                InetSocketAddress address = stringToAddress(addressValue);
                withRecovery(() -> {
                    String addressPath = buildAddressPath(serviceName, address);
                    ZkClient client = currentClient();
                    if (client.exists(addressPath, false) == null) {
                        client.create(addressPath,
                                addressValue.getBytes(StandardCharsets.UTF_8),
                                ZooDefs.Ids.OPEN_ACL_UNSAFE,
                                CreateMode.EPHEMERAL);
                    }
                    return null;
                });
            }
        });
    }

    /**
     * 恢复 consumer 侧服务订阅 watcher。
     */
    private void restoreSubscriptions() {
        listeners.forEach((serviceName, serviceListeners) -> {
            if (!serviceListeners.isEmpty()) {
                try {
                    watchServiceChildren(serviceName);
                } catch (Exception e) {
                    log.error("Failed to restore watcher for service {}", serviceName, e);
                }
            }
        });
    }

    /**
     * 运行时确保根路径存在，异常包装为 RuntimeException。
     */
    private void ensureRootPathUnchecked() {
        try {
            ensureRootPath();
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure ZooKeeper root path", e);
        }
    }

    /**
     * 运行时确保服务路径存在，异常包装为 RuntimeException。
     */
    private void ensureServicePathUnchecked(String serviceName) {
        try {
            ensureServicePath(serviceName);
        } catch (Exception e) {
            throw new RuntimeException("Failed to ensure service path: " + serviceName, e);
        }
    }

    /**
     * 获取当前可用 ZooKeeper client。
     */
    private ZkClient currentClient() {
        ZkClient client = zkClient;
        if (client == null) {
            throw new IllegalStateException("ZooKeeper client is closed");
        }
        return client;
    }

    /**
     * 执行 ZooKeeper 操作，并在 session 过期时尝试一次恢复后重试。
     */
    private <T> T withRecovery(ZkCallable<T> callable) {
        try {
            return callable.call();
        } catch (RuntimeException e) {
            throw e;
        } catch (KeeperException.SessionExpiredException e) {
            reconnectIfNecessary(zkClient);
            try {
                return callable.call();
            } catch (Exception retryException) {
                throw new RuntimeException("Failed to execute ZooKeeper operation after reconnect", retryException);
            }
        } catch (Exception e) {
            throw new RuntimeException("ZooKeeper operation failed", e);
        }
    }

    /**
     * 将 ZooKeeper 子节点名转换为地址列表。
     */
    private List<InetSocketAddress> toAddresses(List<String> children) {
        List<InetSocketAddress> addresses = new ArrayList<>();
        for (String child : children) {
            addresses.add(stringToAddress(child));
        }
        return addresses;
    }

    /**
     * 构造服务路径。
     */
    private String buildServicePath(String serviceName) {
        return ZK_ROOT + "/" + serviceName;
    }

    /**
     * 构造服务实例临时节点路径。
     */
    private String buildAddressPath(String serviceName, InetSocketAddress address) {
        return buildServicePath(serviceName) + "/" + addressToString(address);
    }

    /**
     * 将地址编码为 ZooKeeper 节点名。
     */
    private String addressToString(InetSocketAddress address) {
        return address.getHostString() + "-" + address.getPort();
    }

    /**
     * 将 ZooKeeper 节点名解码为地址。
     */
    private InetSocketAddress stringToAddress(String value) {
        String[] parts = value.split("-");
        return new InetSocketAddress(parts[0], Integer.parseInt(parts[1]));
    }

    /**
     * 可抛出 checked exception 的 ZooKeeper 操作回调。
     */
    @FunctionalInterface
    private interface ZkCallable<T> {
        T call() throws Exception;
    }
}
