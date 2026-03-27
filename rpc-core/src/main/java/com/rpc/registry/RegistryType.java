package com.rpc.registry;

public enum RegistryType {
    ZOOKEEPER;

    public static RegistryType from(String value) {
        if (value == null || value.isBlank()) {
            return ZOOKEEPER;
        }
        return RegistryType.valueOf(value.trim().toUpperCase());
    }
}
