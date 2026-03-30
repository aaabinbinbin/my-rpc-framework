package com.rpc.core.discovery;

@FunctionalInterface
public interface ServiceChangeListener {
    void onChange(ServiceInstancesSnapshot snapshot);
}

