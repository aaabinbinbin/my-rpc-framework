package com.rpc.core.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

@AllArgsConstructor
@Getter
public enum ErrorCode {
    SUCCESS(0, "Success", true),

    ILLEGAL_ARGUMENT(1001, "Illegal argument", false),
    SERVICE_NOT_FOUND(1002, "Service not found", false),
    METHOD_NOT_FOUND(1003, "Method not found", false),
    SERIALIZATION_ERROR(1004, "Serialization error", false),

    NETWORK_TIMEOUT(2001, "Network timeout", true),
    CONNECTION_REFUSED(2002, "Connection refused", true),
    CONNECTION_RESET(2003, "Connection reset", true),
    CHANNEL_UNAVAILABLE(2004, "Channel unavailable", true),

    SERVER_BUSY(3001, "Server busy", true),
    SERVER_ERROR(3002, "Server error", true),
    SERVICE_EXCEPTION(3003, "Service exception", false),

    CIRCUIT_BREAKER_OPEN(4001, "Circuit breaker open", false),
    RATE_LIMIT_EXCEEDED(4002, "Rate limit exceeded", false),
    SERVICE_DEGRADED(4003, "Service degraded", false);

    private final int code;
    private final String description;
    private final boolean retryable;
}
