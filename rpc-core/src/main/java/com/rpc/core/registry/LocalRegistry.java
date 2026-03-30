package com.rpc.core.registry;

public interface LocalRegistry {
    void register(String serviceName, Object serviceInstance);

    Object getService(String serviceName);

    void unregister(String serviceName);

    boolean contains(String serviceName);

    Iterable<String> serviceNames();
}

