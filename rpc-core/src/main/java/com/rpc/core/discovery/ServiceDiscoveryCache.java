package com.rpc.core.discovery;

import lombok.Getter;

import java.util.concurrent.ConcurrentHashMap;

public class ServiceDiscoveryCache {
    private final ConcurrentHashMap<String, CacheEntry> snapshots = new ConcurrentHashMap<>();

    public CacheEntry getEntry(String serviceName) {
        return snapshots.get(serviceName);
    }

    public ServiceInstancesSnapshot get(String serviceName) {
        CacheEntry entry = snapshots.get(serviceName);
        return entry == null ? null : entry.getSnapshot();
    }

    public ServiceInstancesSnapshot put(String serviceName, ServiceInstancesSnapshot snapshot) {
        snapshots.put(serviceName, new CacheEntry(snapshot, System.currentTimeMillis()));
        return snapshot;
    }

    public void clear() {
        snapshots.clear();
    }

    @Getter
    public static final class CacheEntry {
        private final ServiceInstancesSnapshot snapshot;
        private final long updatedAtMillis;

        private CacheEntry(ServiceInstancesSnapshot snapshot, long updatedAtMillis) {
            this.snapshot = snapshot;
            this.updatedAtMillis = updatedAtMillis;
        }
    }
}

