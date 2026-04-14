package com.rpc.core.discovery;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 服务目录测试。
 *
 * <p>测试目标：独立验证 consumer 侧服务目录的订阅、缓存刷新、注册中心失败回退、
 * 地址记忆和关闭清理逻辑，确保服务发现数据流在调用链中有明确语义。</p>
 */
@DisplayName("服务目录测试")
class ServiceDirectoryTest {

    @Test
    @DisplayName("首次获取快照应订阅服务并缓存初始实例")
    void shouldSubscribeAndCacheInitialSnapshotOnFirstAccess() {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        discovery.put("userService", address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000, true);

        ServiceInstancesSnapshot snapshot = directory.getSnapshot("userService");
        ServiceInstancesSnapshot cached = directory.getSnapshot("userService");

        assertEquals(List.of(address), snapshot.getAddresses());
        assertSame(snapshot, cached, "缓存未过期时应直接返回同一份快照对象");
        assertEquals(1, discovery.subscribeCount("userService"));
        assertEquals(0, discovery.discoverCount("userService"));
    }

    @Test
    @DisplayName("订阅回调推送新快照后服务目录应立即更新缓存")
    void shouldUpdateCacheWhenSubscriptionCallbackArrives() {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 8080);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 8081);
        discovery.put("userService", first);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000, true);

        directory.getSnapshot("userService");
        discovery.publish("userService", ServiceInstancesSnapshot.of("userService", List.of(second)));

        assertEquals(List.of(second), directory.getSnapshot("userService").getAddresses());
    }

    @Test
    @DisplayName("缓存过期后刷新失败且允许旧数据时应回退到旧快照")
    void shouldFallbackToStaleSnapshotWhenRefreshFails() throws InterruptedException {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        discovery.put("userService", address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 1, true);

        ServiceInstancesSnapshot initial = directory.getSnapshot("userService");
        Thread.sleep(5);
        discovery.failDiscover(true);
        ServiceInstancesSnapshot fallback = directory.getSnapshot("userService");

        assertSame(initial, fallback);
        assertEquals(1, discovery.discoverCount("userService"));
    }

    @Test
    @DisplayName("缓存过期后刷新失败且不允许旧数据时应抛出异常")
    void shouldThrowWhenRefreshFailsAndStaleFallbackDisabled() throws InterruptedException {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        discovery.put("userService", new InetSocketAddress("127.0.0.1", 8080));
        ServiceDirectory directory = new ServiceDirectory(discovery, 1, false);

        directory.getSnapshot("userService");
        Thread.sleep(5);
        discovery.failDiscover(true);

        assertThrows(RuntimeException.class, () -> directory.getSnapshot("userService"));
    }

    @Test
    @DisplayName("地址记忆应允许从服务名反查已知 provider 地址")
    void shouldRememberAddressServiceMapping() {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        InetSocketAddress knownAddress = new InetSocketAddress("127.0.0.1", 8080);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000, true);
        discovery.put("userService", knownAddress);
        directory.rememberAddressService(knownAddress, "userService");

        assertTrue(directory.containsAddress(knownAddress));
        assertFalse(directory.containsAddress(new InetSocketAddress("127.0.0.1", 8081)));
    }

    @Test
    @DisplayName("关闭服务目录应取消订阅并清理本地缓存")
    void shouldUnsubscribeAndClearStateWhenClosed() {
        FakeServiceDiscovery discovery = new FakeServiceDiscovery();
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 8080);
        discovery.put("userService", address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000, true);

        directory.getSnapshot("userService");
        directory.close();

        assertEquals(1, discovery.unsubscribeCount("userService"));
        assertFalse(directory.containsAddress(address));
    }

    private static final class FakeServiceDiscovery implements ServiceDiscovery {
        private final Map<String, ServiceInstancesSnapshot> snapshots = new ConcurrentHashMap<>();
        private final Map<String, ServiceChangeListener> listeners = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> subscribeCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> discoverCounts = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> unsubscribeCounts = new ConcurrentHashMap<>();
        private volatile boolean failDiscover;

        void put(String serviceName, InetSocketAddress address) {
            snapshots.put(serviceName, ServiceInstancesSnapshot.of(serviceName, List.of(address)));
        }

        void publish(String serviceName, ServiceInstancesSnapshot snapshot) {
            snapshots.put(serviceName, snapshot);
            listeners.get(serviceName).onChange(snapshot);
        }

        void failDiscover(boolean failDiscover) {
            this.failDiscover = failDiscover;
        }

        int subscribeCount(String serviceName) {
            return subscribeCounts.getOrDefault(serviceName, new AtomicInteger()).get();
        }

        int discoverCount(String serviceName) {
            return discoverCounts.getOrDefault(serviceName, new AtomicInteger()).get();
        }

        int unsubscribeCount(String serviceName) {
            return unsubscribeCounts.getOrDefault(serviceName, new AtomicInteger()).get();
        }

        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            discoverCounts.computeIfAbsent(serviceName, ignored -> new AtomicInteger()).incrementAndGet();
            if (failDiscover) {
                throw new RuntimeException("discovery unavailable");
            }
            return snapshots.getOrDefault(serviceName, ServiceInstancesSnapshot.of(serviceName, List.of()));
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            subscribeCounts.computeIfAbsent(serviceName, ignored -> new AtomicInteger()).incrementAndGet();
            listeners.put(serviceName, listener);
            return snapshots.getOrDefault(serviceName, ServiceInstancesSnapshot.of(serviceName, List.of()));
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
            unsubscribeCounts.computeIfAbsent(serviceName, ignored -> new AtomicInteger()).incrementAndGet();
            listeners.remove(serviceName, listener);
        }

        @Override
        public void close() {
            listeners.clear();
        }
    }
}
