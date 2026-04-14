package com.rpc.core.registry;

import com.rpc.core.discovery.ServiceChangeListener;
import com.rpc.core.discovery.ServiceDirectory;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.discovery.ServiceInstancesSnapshot;
import com.rpc.core.resilience.circuitbreaker.CircuitBreakerManager;
import com.rpc.core.extension.loadbalance.impl.RandomLoadBalancer;
import com.rpc.support.InMemoryServiceRegistry;
import com.rpc.core.transport.netty.client.invocation.RpcServiceResolver;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import org.junit.jupiter.api.DisplayName;

@DisplayName("测试类：服务发现解析器测试")
class ServiceDiscoveryResolverTest {
    @DisplayName("验证更新缓存地址在订阅变更场景")
    @Test
    void shouldUpdateCachedAddressesAfterSubscriptionChange() throws Exception {
        InMemoryServiceRegistry registry = new InMemoryServiceRegistry();
        String serviceName = "com.rpc.test.HelloService";
        InetSocketAddress first = new InetSocketAddress("127.0.0.1", 8080);
        InetSocketAddress second = new InetSocketAddress("127.0.0.1", 8081);
        registry.register(serviceName, first);

        RpcServiceResolver resolver = new RpcServiceResolver(
                new ServiceDirectory(registry, 30000L, true),
                new RandomLoadBalancer(),
                CircuitBreakerManager.getInstance()
        );

        assertEquals(first, resolver.resolve(serviceName));

        registry.unregister(serviceName, first);
        registry.register(serviceName, second);

        assertEquals(second, resolver.resolve(serviceName));
    }

    @DisplayName("验证保持使用缓存快照当发现临时失败场景")
    @Test
    void shouldKeepUsingCachedSnapshotWhenDiscoveryTemporarilyFails() {
        String serviceName = "com.rpc.test.FailoverService";
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9090);
        FlakyDiscovery discovery = new FlakyDiscovery(address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 1L, true);

        ServiceInstancesSnapshot initial = directory.getSnapshot(serviceName);
        assertIterableEquals(List.of(address), initial.getAddresses());

        discovery.failDiscover.set(true);
        sleep(5);

        ServiceInstancesSnapshot fallback = directory.refresh(serviceName);
        assertIterableEquals(List.of(address), fallback.getAddresses());
    }

    @DisplayName("验证失败当缓存过期并旧缓存兜底禁用场景")
    @Test
    void shouldFailWhenCacheExpiredAndStaleFallbackDisabled() {
        String serviceName = "com.rpc.test.StrictService";
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9091);
        FlakyDiscovery discovery = new FlakyDiscovery(address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 1L, false);

        directory.getSnapshot(serviceName);
        discovery.failDiscover.set(true);
        sleep(5);

        assertThrows(RuntimeException.class, () -> directory.refresh(serviceName));
    }

    @DisplayName("验证预热已配置Services场景")
    @Test
    void shouldPreheatConfiguredServices() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9092);
        FlakyDiscovery discovery = new FlakyDiscovery(address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30000L, true);

        directory.preheat(List.of("svcA", "svcB"));

        assertEquals(2, discovery.subscribeCount.get());
    }

    @DisplayName("验证刷新已订阅Services当地址缺失来自缓存场景")
    @Test
    void shouldRefreshSubscribedServicesWhenAddressMissingFromCache() {
        String serviceName = "com.rpc.test.RefreshOnMissService";
        InetSocketAddress stale = new InetSocketAddress("127.0.0.1", 9100);
        InetSocketAddress latest = new InetSocketAddress("127.0.0.1", 9101);
        FlakyDiscovery discovery = new FlakyDiscovery(stale);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30000L, true);

        directory.getSnapshot(serviceName);
        discovery.address = latest;

        assertTrue(directory.containsAddress(latest));
        assertEquals(1, discovery.discoverCount.get());
        assertFalse(directory.containsAddress(stale));
        assertEquals(2, discovery.discoverCount.get());
    }

    @DisplayName("验证刷新仅记忆服务当旧缓存地址断开场景")
    @Test
    void shouldRefreshOnlyRememberedServiceWhenStaleAddressDisconnects() {
        String firstService = "svc-a";
        String secondService = "svc-b";
        InetSocketAddress firstAddress = new InetSocketAddress("127.0.0.1", 9200);
        InetSocketAddress secondAddress = new InetSocketAddress("127.0.0.1", 9300);
        MultiServiceDiscovery discovery = new MultiServiceDiscovery(Map.of(
                firstService, firstAddress,
                secondService, secondAddress
        ));
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000L, true);

        directory.getSnapshot(firstService);
        directory.getSnapshot(secondService);
        directory.rememberAddressService(firstAddress, firstService);

        InetSocketAddress replacement = new InetSocketAddress("127.0.0.1", 9201);
        discovery.update(firstService, replacement);

        assertTrue(directory.containsAddress(replacement));
        int firstRefreshes = discovery.discoverCount(firstService);
        int secondRefreshes = discovery.discoverCount(secondService);

        assertFalse(directory.containsAddress(firstAddress));
        assertEquals(firstRefreshes + 1, discovery.discoverCount(firstService));
        assertEquals(secondRefreshes, discovery.discoverCount(secondService));
    }

    @DisplayName("验证清理记忆地址按TTL场景")
    @Test
    void shouldPruneRememberedAddressesByTtl() throws Exception {
        ServiceDirectory directory = newServiceDirectory(new NoopDiscovery(), 30_000L, true, 10L, 100);
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9400);

        directory.rememberAddressService(address, "svc-ttl");
        sleep(20L);
        directory.containsAddress(new InetSocketAddress("127.0.0.1", 9499));

        assertEquals(0, rememberedAddressCount(directory));
    }

    @DisplayName("验证限制记忆地址大小场景")
    @Test
    void shouldCapRememberedAddressesSize() throws Exception {
        ServiceDirectory directory = newServiceDirectory(new NoopDiscovery(), 30_000L, true, 60_000L, 2);

        directory.rememberAddressService(new InetSocketAddress("127.0.0.1", 9500), "svc-1");
        sleep(2L);
        directory.rememberAddressService(new InetSocketAddress("127.0.0.1", 9501), "svc-2");
        sleep(2L);
        directory.rememberAddressService(new InetSocketAddress("127.0.0.1", 9502), "svc-3");

        assertEquals(2, rememberedAddressCount(directory));
    }

    @DisplayName("验证去重并发刷新用于同时服务场景")
    @Test
    void shouldDeduplicateConcurrentRefreshForSameService() throws Exception {
        String serviceName = "svc-single-flight";
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9600);
        BlockingDiscovery discovery = new BlockingDiscovery(address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30_000L, true);
        ExecutorService executor = Executors.newFixedThreadPool(3);
        try {
            List<Future<ServiceInstancesSnapshot>> futures = new ArrayList<>();
            for (int i = 0; i < 3; i++) {
                futures.add(executor.submit(() -> directory.refresh(serviceName)));
            }

            assertTrue(discovery.awaitFirstCall(5, TimeUnit.SECONDS));
            sleep(50L);
            assertEquals(1, discovery.discoverCount.get());
            discovery.release();

            for (Future<ServiceInstancesSnapshot> future : futures) {
                assertIterableEquals(List.of(address), future.get(5, TimeUnit.SECONDS).getAddresses());
            }
            assertEquals(1, discovery.discoverCount.get());
        } finally {
            executor.shutdownNow();
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static ServiceDirectory newServiceDirectory(ServiceDiscovery discovery,
                                                        long cacheTtlMillis,
                                                        boolean allowStaleOnFailure,
                                                        long rememberedAddressTtlMillis,
                                                        int maxRememberedAddresses) throws Exception {
        Constructor<ServiceDirectory> constructor = ServiceDirectory.class.getDeclaredConstructor(
                ServiceDiscovery.class,
                long.class,
                boolean.class,
                long.class,
                int.class
        );
        constructor.setAccessible(true);
        return constructor.newInstance(
                discovery,
                cacheTtlMillis,
                allowStaleOnFailure,
                rememberedAddressTtlMillis,
                maxRememberedAddresses
        );
    }

    private static int rememberedAddressCount(ServiceDirectory directory) throws Exception {
        Field field = ServiceDirectory.class.getDeclaredField("addressServices");
        field.setAccessible(true);
        Map<?, ?> addressServices = (Map<?, ?>) field.get(directory);
        return addressServices.size();
    }

    private static final class FlakyDiscovery implements ServiceDiscovery {
        private volatile InetSocketAddress address;
        private final AtomicBoolean failDiscover = new AtomicBoolean(false);
        private final AtomicInteger subscribeCount = new AtomicInteger(0);
        private final AtomicInteger discoverCount = new AtomicInteger(0);

        private FlakyDiscovery(InetSocketAddress address) {
            this.address = address;
        }

        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            discoverCount.incrementAndGet();
            if (failDiscover.get()) {
                throw new RuntimeException("registry unavailable");
            }
            return ServiceInstancesSnapshot.of(serviceName, List.of(address));
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            subscribeCount.incrementAndGet();
            ServiceInstancesSnapshot snapshot = ServiceInstancesSnapshot.of(serviceName, List.of(address));
            listener.onChange(snapshot);
            return snapshot;
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        }

        @Override
        public void close() {
        }
    }

    private static final class MultiServiceDiscovery implements ServiceDiscovery {
        private final Map<String, InetSocketAddress> addresses = new ConcurrentHashMap<>();
        private final Map<String, AtomicInteger> discoverCounts = new ConcurrentHashMap<>();

        private MultiServiceDiscovery(Map<String, InetSocketAddress> addresses) {
            this.addresses.putAll(addresses);
        }

        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            discoverCounts.computeIfAbsent(serviceName, ignored -> new AtomicInteger()).incrementAndGet();
            InetSocketAddress address = addresses.get(serviceName);
            return ServiceInstancesSnapshot.of(serviceName, address == null ? List.of() : List.of(address));
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            ServiceInstancesSnapshot snapshot = discover(serviceName);
            listener.onChange(snapshot);
            return snapshot;
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        }

        @Override
        public void close() {
        }

        private void update(String serviceName, InetSocketAddress address) {
            addresses.put(serviceName, address);
        }

        private int discoverCount(String serviceName) {
            AtomicInteger counter = discoverCounts.get(serviceName);
            return counter == null ? 0 : counter.get();
        }
    }

    private static final class NoopDiscovery implements ServiceDiscovery {
        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            return ServiceInstancesSnapshot.of(serviceName, List.of());
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        }

        @Override
        public void close() {
        }
    }

    private static final class BlockingDiscovery implements ServiceDiscovery {
        private final InetSocketAddress address;
        private final AtomicInteger discoverCount = new AtomicInteger();
        private final CountDownLatch firstCallLatch = new CountDownLatch(1);
        private final CountDownLatch releaseLatch = new CountDownLatch(1);

        private BlockingDiscovery(InetSocketAddress address) {
            this.address = address;
        }

        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
            discoverCount.incrementAndGet();
            firstCallLatch.countDown();
            try {
                if (!releaseLatch.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("timed out waiting to release discovery");
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError(e);
            }
            return ServiceInstancesSnapshot.of(serviceName, List.of(address));
        }

        @Override
        public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
            return discover(serviceName);
        }

        @Override
        public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        }

        @Override
        public void close() {
        }

        private boolean awaitFirstCall(long timeout, TimeUnit unit) throws InterruptedException {
            return firstCallLatch.await(timeout, unit);
        }

        private void release() {
            releaseLatch.countDown();
        }
    }
}

