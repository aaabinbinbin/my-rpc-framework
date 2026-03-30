package com.rpc.support;

import com.rpc.core.discovery.ServiceChangeListener;
import com.rpc.core.discovery.ServiceDiscovery;
import com.rpc.core.discovery.ServiceInstancesSnapshot;
import com.rpc.core.registry.ServiceRegistry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

public class InMemoryServiceRegistry implements ServiceRegistry, ServiceDiscovery {
    private final Map<String, List<InetSocketAddress>> services = new ConcurrentHashMap<>();
    private final Map<String, Set<ServiceChangeListener>> listeners = new ConcurrentHashMap<>();

    @Override
    public void register(String serviceName, InetSocketAddress address) {
        services.compute(serviceName, (key, addresses) -> {
            List<InetSocketAddress> next = addresses == null ? new ArrayList<>() : new ArrayList<>(addresses);
            if (!next.contains(address)) {
                next.add(address);
            }
            return next;
        });
        notifyListeners(serviceName);
    }

    @Override
    public void unregister(String serviceName, InetSocketAddress address) {
        services.computeIfPresent(serviceName, (key, addresses) -> {
            List<InetSocketAddress> next = new ArrayList<>(addresses);
            next.remove(address);
            return next.isEmpty() ? null : next;
        });
        notifyListeners(serviceName);
    }

    @Override
    public List<InetSocketAddress> lookup(String serviceName) {
        return new ArrayList<>(services.getOrDefault(serviceName, List.of()));
    }

    @Override
    public ServiceInstancesSnapshot discover(String serviceName) {
        return ServiceInstancesSnapshot.of(serviceName, lookup(serviceName));
    }

    @Override
    public ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener) {
        listeners.computeIfAbsent(serviceName, key -> new CopyOnWriteArraySet<>()).add(listener);
        ServiceInstancesSnapshot snapshot = discover(serviceName);
        listener.onChange(snapshot);
        return snapshot;
    }

    @Override
    public void unsubscribe(String serviceName, ServiceChangeListener listener) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners == null) {
            return;
        }

        serviceListeners.remove(listener);
        if (serviceListeners.isEmpty()) {
            listeners.remove(serviceName);
        }
    }

    @Override
    public void close() {
        services.clear();
        listeners.clear();
    }

    private void notifyListeners(String serviceName) {
        Set<ServiceChangeListener> serviceListeners = listeners.get(serviceName);
        if (serviceListeners == null || serviceListeners.isEmpty()) {
            return;
        }

        ServiceInstancesSnapshot snapshot = discover(serviceName);
        for (ServiceChangeListener listener : serviceListeners) {
            listener.onChange(snapshot);
        }
    }
}

