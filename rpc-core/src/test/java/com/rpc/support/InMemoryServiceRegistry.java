package com.rpc.support;

import com.rpc.registry.ServiceRegistry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

public class InMemoryServiceRegistry implements ServiceRegistry {
    private final Map<String, List<InetSocketAddress>> services = new ConcurrentHashMap<>();

    @Override
    public void register(String serviceName, InetSocketAddress address) {
        services.compute(serviceName, (key, addresses) -> {
            List<InetSocketAddress> next = addresses == null ? new ArrayList<>() : new ArrayList<>(addresses);
            if (!next.contains(address)) {
                next.add(address);
            }
            return next;
        });
    }

    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        services.computeIfPresent(serviceName, (key, addresses) -> {
            List<InetSocketAddress> next = new ArrayList<>(addresses);
            next.remove(address);
            return next.isEmpty() ? null : next;
        });
    }

    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        return new ArrayList<>(services.getOrDefault(serviceName, List.of()));
    }

    @Override
    public void close() {
        services.clear();
    }
}
