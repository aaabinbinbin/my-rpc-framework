package com.rpc.core.invoke.invocation;

public enum ClusterStrategy {
    FAIL_FAST,
    FAIL_OVER;

    public static ClusterStrategy from(String value) {
        if (value == null || value.isBlank()) {
            return FAIL_OVER;
        }

        String normalized = value.trim()
                .replace("-", "")
                .replace("_", "")
                .toUpperCase();

        return switch (normalized) {
            case "FAILFAST" -> FAIL_FAST;
            case "FAILOVER" -> FAIL_OVER;
            default -> FAIL_OVER;
        };
    }
}

