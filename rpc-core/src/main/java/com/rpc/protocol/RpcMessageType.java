package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * RPC 消息类型
 */
@Getter
@AllArgsConstructor
public enum RpcMessageType {

    REQUEST((byte) 1, "请求消息"),
    RESPONSE((byte) 2, "响应消息"),

    HEARTBEAT_REQUEST((byte) 3, "心跳请求"),
    HEARTBEAT_RESPONSE((byte) 4, "心跳响应"),

    EXCEPTION((byte) 5, "异常消息");

    private final byte code;
    private final String description;

    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("未知消息类型：" + code);
    }
}
