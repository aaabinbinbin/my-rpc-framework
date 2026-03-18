package com.rpc.protocol;

/**
 * 消息类型枚举
 */
public interface RpcMessageTyp {
    /** 请求消息 */
    byte REQUEST = 1;

    /** 响应消息 */
    byte RESPONSE = 2;

    /** 心跳请求 */
    byte HEARTBEAT_REQUEST = 3;

    /** 心跳响应 */
    byte HEARTBEAT_RESPONSE = 4;
}
