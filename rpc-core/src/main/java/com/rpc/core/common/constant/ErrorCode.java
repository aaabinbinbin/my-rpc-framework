package com.rpc.core.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 调用链统一错误码。
 *
 * 所处阶段：服务端响应封装、客户端异常映射、重试策略、熔断降级判断都会使用该枚举。
 * 主要职责：把不同层的异常归一为可观测、可判断、可传输的错误语义。
 *
 * 注意事项：retryable 表示错误本身是否适合重试，不代表一定会重试；最终是否重试还要结合幂等性、重试次数和熔断状态。
 */
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
    CLIENT_BUSY(3004, "Client busy", false),

    CIRCUIT_BREAKER_OPEN(4001, "Circuit breaker open", false),
    RATE_LIMIT_EXCEEDED(4002, "Rate limit exceeded", false),
    SERVICE_DEGRADED(4003, "Service degraded", false);

    /** 传输到响应体中的稳定数字码，便于跨语言或跨进程判断错误类型。 */
    private final int code;
    /** 面向日志和默认异常消息的简短描述。 */
    private final String description;
    /** 标识该错误类型是否具备重试价值，供 RetryStrategy 做基础判断。 */
    private final boolean retryable;
}
