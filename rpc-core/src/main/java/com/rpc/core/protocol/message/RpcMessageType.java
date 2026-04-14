package com.rpc.core.protocol.message;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 协议消息类型。
 *
 * 所处阶段：协议编码和解码时写入/读取 RpcHeader。
 * 主要职责：区分请求、响应、心跳和异常消息，保证 pipeline 能按类型分发处理。
 */
@Getter
@AllArgsConstructor
public enum RpcMessageType {
    /** 普通 RPC 请求。 */
    REQUEST((byte) 1, "REQUEST"),
    /** 普通 RPC 响应。 */
    RESPONSE((byte) 2, "RESPONSE"),
    /** 客户端发起的心跳请求。 */
    HEARTBEAT_REQUEST((byte) 3, "HEARTBEAT_REQUEST"),
    /** 服务端返回的心跳响应。 */
    HEARTBEAT_RESPONSE((byte) 4, "HEARTBEAT_RESPONSE"),
    /** 协议层或服务端执行阶段产生的异常消息。 */
    EXCEPTION((byte) 5, "EXCEPTION");

    /** 写入协议头的稳定类型码。 */
    private final byte code;
    /** 面向日志和调试的类型描述。 */
    private final String description;

    /**
     * 根据协议头类型码解析消息类型。
     *
     * 边界处理：未知类型直接抛出 IllegalArgumentException，避免后续 handler 按错误类型处理。
     */
    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rpc message type code: " + code);
    }
}
