package com.rpc.transport;

public enum TransportType {
    NETTY,
    SOCKET;

    public static TransportType from(String value) {
        if (value == null || value.isBlank()) {
            return NETTY;
        }
        return TransportType.valueOf(value.trim().toUpperCase());
    }
}
