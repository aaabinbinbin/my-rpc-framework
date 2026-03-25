package com.rpc.common.constant;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 错误码枚举
 * 定义所有可能的错误类型及其属性
 */
@AllArgsConstructor
@Getter
public enum ErrorCode {
    // ========== 成功 ==========
    SUCCESS(0, "成功", true),

    // ========== 客户端异常（不可重试） ==========
    ILLEGAL_ARGUMENT(1001, "参数非法", false),
    SERVICE_NOT_FOUND(1002, "服务未找到", false),
    METHOD_NOT_FOUND(1003, "方法未找到", false),
    SERIALIZATION_ERROR(1004, "序列化失败", false),

    // ========== 网络异常（可重试） ==========
    NETWORK_TIMEOUT(2001, "网络超时", true),
    CONNECTION_REFUSED(2002, "连接被拒绝", true),
    CONNECTION_RESET(2003, "连接被重置", true),
    CHANNEL_UNAVAILABLE(2004, "通道不可用", true),

    // ========== 服务端异常（部分可重试） ==========
    SERVER_BUSY(3001, "服务器繁忙", true),
    SERVER_ERROR(3002, "服务器内部错误", true),
    SERVICE_EXCEPTION(3003, "服务执行异常", false),

    // ========== 熔断降级（不可重试） ==========
    CIRCUIT_BREAKER_OPEN(4001, "熔断器已打开", false),
    RATE_LIMIT_EXCEEDED(4002, "超过限流阈值", false),
    SERVICE_DEGRADED(4003, "服务已降级", false);

    /** 错误码数值 */
    private final int code;

    /** 错误描述 */
    private final String description;

    /** 是否可重试 */
    private final boolean retryable;
}
