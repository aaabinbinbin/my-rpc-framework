package com.rpc.core.registry.zookeeper;

import com.rpc.core.discovery.ServiceChangeListener;
import com.rpc.core.discovery.ServiceInstancesSnapshot;
import org.apache.zookeeper.CreateMode;
import org.apache.zookeeper.KeeperException;
import org.apache.zookeeper.WatchedEvent;
import org.apache.zookeeper.Watcher;
import org.apache.zookeeper.data.Stat;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：ZooKeeper注册中心实现测试")
class ZooKeeperRegistryImplTest {
    @DisplayName("验证恢复注册数据并订阅在会话过期场景")
    @Test
    void shouldRecoverRegistrationsAndSubscriptionsAfterSessionExpiration() throws Exception {
        FakeZkClientFactory factory = new FakeZkClientFactory();
        ZooKeeperRegistryImpl registry = new ZooKeeperRegistryImpl("127.0.0.1:2181", 3_000, factory);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        AtomicInteger notificationCount = new AtomicInteger();
        ServiceChangeListener listener = snapshot -> notificationCount.incrementAndGet();

        registry.register("demoService", address);
        ServiceInstancesSnapshot initialSnapshot = registry.subscribe("demoService", listener);

        assertEquals(1, initialSnapshot.getAddresses().size());
        assertTrue(factory.first().existsPath("/rpc/demoService/127.0.0.1-8080"));
        assertTrue(factory.first().hasChildrenWatcher("/rpc/demoService"));

        factory.first().emitState(Watcher.Event.KeeperState.Expired);
        waitFor(() -> factory.createdCount() == 2);
        waitFor(() -> factory.second().existsPath("/rpc/demoService/127.0.0.1-8080"));
        waitFor(() -> factory.second().hasChildrenWatcher("/rpc/demoService"));

        factory.second().addEphemeralAddress("/rpc/demoService", "127.0.0.1-8081");
        factory.second().emitChildrenChanged("/rpc/demoService");
        waitFor(() -> notificationCount.get() >= 2);

        assertTrue(factory.first().isClosed());
        assertEquals(2, factory.createdCount());
        assertTrue(factory.second().existsPath("/rpc/demoService/127.0.0.1-8080"));

        registry.close();
    }

    @DisplayName("验证回滚监听器当初始订阅失败场景")
    @Test
    void shouldRollbackListenerWhenInitialSubscribeFails() {
        FailingSubscribeZkClientFactory factory = new FailingSubscribeZkClientFactory();
        ZooKeeperRegistryImpl registry = new ZooKeeperRegistryImpl("127.0.0.1:2181", 3_000, factory);
        registry.register("brokenService", new InetSocketAddress("127.0.0.1", 8088));

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> registry.subscribe("brokenService", snapshot -> {
                }));

        assertTrue(exception.getMessage().contains("Failed to subscribe"));
        assertFalse(factory.client().hasListeners("/rpc/brokenService"));

        registry.close();
    }

    @DisplayName("验证超时当初始连接事件不到达场景")
    @Test
    void shouldTimeoutWhenInitialConnectionEventDoesNotArrive() {
        SilentConnectZkClientFactory factory = new SilentConnectZkClientFactory();

        RuntimeException exception = assertThrows(RuntimeException.class,
                () -> new ZooKeeperRegistryImpl("127.0.0.1:2181", 20, factory));

        assertTrue(exception.getMessage().contains("Failed to connect ZooKeeper"));
        assertTrue(factory.client().isClosed());
    }

    private static void waitFor(Check check) throws Exception {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline) {
            if (check.done()) {
                return;
            }
            Thread.sleep(25L);
        }
        assertTrue(check.done(), "Condition was not satisfied before timeout");
    }

    @FunctionalInterface
    private interface Check {
        boolean done() throws Exception;
    }

    private static final class FakeZkClientFactory implements ZkClientFactory {
        private final List<FakeZkClient> clients = new CopyOnWriteArrayList<>();

        @Override
        public ZkClient create(String connectString, int sessionTimeout, Watcher watcher) {
            FakeZkClient client = new FakeZkClient(watcher);
            clients.add(client);
            client.emitState(Watcher.Event.KeeperState.SyncConnected);
            return client;
        }

        FakeZkClient first() {
            return clients.get(0);
        }

        FakeZkClient second() {
            return clients.get(1);
        }

        int createdCount() {
            return clients.size();
        }
    }

    private static final class FailingSubscribeZkClientFactory implements ZkClientFactory {
        private final FailingSubscribeZkClient client = new FailingSubscribeZkClient();

        @Override
        public ZkClient create(String connectString, int sessionTimeout, Watcher watcher) {
            client.setConnectionWatcher(watcher);
            client.emitState(Watcher.Event.KeeperState.SyncConnected);
            return client;
        }

        FailingSubscribeZkClient client() {
            return client;
        }
    }

    private static final class SilentConnectZkClientFactory implements ZkClientFactory {
        private SilentConnectZkClient client;

        @Override
        public ZkClient create(String connectString, int sessionTimeout, Watcher watcher) {
            client = new SilentConnectZkClient(watcher);
            return client;
        }

        SilentConnectZkClient client() {
            return client;
        }
    }

    private static class FakeZkClient implements ZkClient {
        private final Watcher connectionWatcher;
        private final Map<String, byte[]> nodes = new ConcurrentHashMap<>();
        private final Map<String, Watcher> childWatchers = new ConcurrentHashMap<>();
        private volatile boolean closed;

        private FakeZkClient(Watcher connectionWatcher) {
            this.connectionWatcher = connectionWatcher;
        }

        @Override
        public Stat exists(String path, boolean watch) {
            return nodes.containsKey(path) ? new Stat() : null;
        }

        @Override
        public String create(String path, byte[] data, List<org.apache.zookeeper.data.ACL> acl, CreateMode createMode)
                throws KeeperException {
            if (nodes.putIfAbsent(path, data) != null) {
                throw new KeeperException.NodeExistsException(path);
            }
            return path;
        }

        @Override
        public List<String> getChildren(String path, boolean watch) {
            return childNames(path);
        }

        @Override
        public List<String> getChildren(String path, Watcher watcher) {
            childWatchers.put(path, watcher);
            return childNames(path);
        }

        @Override
        public void delete(String path, int version) {
            nodes.remove(path);
        }

        @Override
        public void close() {
            closed = true;
        }

        void emitState(Watcher.Event.KeeperState state) {
            connectionWatcher.process(new WatchedEvent(Watcher.Event.EventType.None, state, null));
        }

        void emitChildrenChanged(String path) {
            Watcher watcher = childWatchers.get(path);
            assertNotNull(watcher, "Children watcher must be registered before emitting change");
            watcher.process(new WatchedEvent(Watcher.Event.EventType.NodeChildrenChanged,
                    Watcher.Event.KeeperState.SyncConnected,
                    path));
        }

        void addEphemeralAddress(String servicePath, String addressValue) {
            nodes.putIfAbsent(servicePath + "/" + addressValue, addressValue.getBytes());
        }

        boolean existsPath(String path) {
            return nodes.containsKey(path);
        }

        boolean hasChildrenWatcher(String path) {
            return childWatchers.containsKey(path);
        }

        boolean hasListeners(String servicePath) {
            return childWatchers.containsKey(servicePath);
        }

        boolean isClosed() {
            return closed;
        }

        private List<String> childNames(String path) {
            String prefix = path + "/";
            Set<String> children = ConcurrentHashMap.newKeySet();
            for (String nodePath : new ArrayList<>(nodes.keySet())) {
                if (nodePath.startsWith(prefix)) {
                    String child = nodePath.substring(prefix.length());
                    if (!child.isEmpty() && !child.contains("/")) {
                        children.add(child);
                    }
                }
            }
            return new ArrayList<>(children);
        }
    }

    private static final class FailingSubscribeZkClient extends FakeZkClient {
        private Watcher connectionWatcher;

        private FailingSubscribeZkClient() {
            super(event -> {
            });
        }

        @Override
        public List<String> getChildren(String path, Watcher watcher) {
            throw new RuntimeException(new KeeperException.ConnectionLossException());
        }

        void setConnectionWatcher(Watcher watcher) {
            this.connectionWatcher = watcher;
        }

        @Override
        void emitState(Watcher.Event.KeeperState state) {
            connectionWatcher.process(new WatchedEvent(Watcher.Event.EventType.None, state, null));
        }
    }

    private static final class SilentConnectZkClient extends FakeZkClient {
        private SilentConnectZkClient(Watcher connectionWatcher) {
            super(connectionWatcher);
        }
    }
}
