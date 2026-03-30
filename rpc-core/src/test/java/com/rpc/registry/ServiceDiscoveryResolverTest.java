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

import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertIterableEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ServiceDiscoveryResolverTest {
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

    @Test
    void shouldPreheatConfiguredServices() {
        InetSocketAddress address = new InetSocketAddress("127.0.0.1", 9092);
        FlakyDiscovery discovery = new FlakyDiscovery(address);
        ServiceDirectory directory = new ServiceDirectory(discovery, 30000L, true);

        directory.preheat(List.of("svcA", "svcB"));

        assertEquals(2, discovery.subscribeCount.get());
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AssertionError(e);
        }
    }

    private static final class FlakyDiscovery implements ServiceDiscovery {
        private final InetSocketAddress address;
        private final AtomicBoolean failDiscover = new AtomicBoolean(false);
        private final AtomicInteger subscribeCount = new AtomicInteger(0);

        private FlakyDiscovery(InetSocketAddress address) {
            this.address = address;
        }

        @Override
        public ServiceInstancesSnapshot discover(String serviceName) {
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
}

