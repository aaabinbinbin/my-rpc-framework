package com.rpc.core.discovery;

public interface ServiceDiscovery {
    ServiceInstancesSnapshot discover(String serviceName);

    ServiceInstancesSnapshot subscribe(String serviceName, ServiceChangeListener listener);

    void unsubscribe(String serviceName, ServiceChangeListener listener);

    void close();
}

