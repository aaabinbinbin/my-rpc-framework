package com.rpc.core.invoke.invocation;

public enum CircuitBreakerScope {
    SERVICE,
    METHOD;

    public static CircuitBreakerScope from(String value) {
        if (value == null || value.isBlank()) {
            return SERVICE;
        }
        return switch (value.trim().toUpperCase().replace("-", "_")) {
            case "METHOD" -> METHOD;
            default -> SERVICE;
        };
    }
}

