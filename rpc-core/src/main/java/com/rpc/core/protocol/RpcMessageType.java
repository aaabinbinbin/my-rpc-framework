package com.rpc.core.protocol;

import lombok.AllArgsConstructor;
import lombok.Getter;

@Getter
@AllArgsConstructor
public enum RpcMessageType {
    REQUEST((byte) 1, "REQUEST"),
    RESPONSE((byte) 2, "RESPONSE"),
    HEARTBEAT_REQUEST((byte) 3, "HEARTBEAT_REQUEST"),
    HEARTBEAT_RESPONSE((byte) 4, "HEARTBEAT_RESPONSE"),
    EXCEPTION((byte) 5, "EXCEPTION");

    private final byte code;
    private final String description;

    public static RpcMessageType fromCode(byte code) {
        for (RpcMessageType type : values()) {
            if (type.getCode() == code) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown rpc message type code: " + code);
    }
}
