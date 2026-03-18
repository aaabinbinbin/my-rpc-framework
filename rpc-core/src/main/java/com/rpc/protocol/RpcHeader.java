package com.rpc.protocol;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Rpc协议消息头
 * 固定20字节
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcHeader {
    /** 魔数，4 字节 */
    private int magicNumber = 0x12345678;

    /** 版本号，1 字节 */
    private byte version = 1;

    /** 序列化器类型，1 字节 */
    private byte serializerType;

    /** 消息类型，1 字节 */
    private byte messageType;

    /** 保留字段，1 字节 */
    private byte reserved;

    /** 请求 ID，8 字节 */
    private long requestId;

    /** 消息体长度，4 字节 */
    private int bodyLength;

    /** 消息头总长度：20 字节 */
    public static final int HEADER_LENGTH = 20;

    /** 魔数常量 */
    public static final int MAGIC_NUMBER = 0x12345678;

    /** 协议版本 */
    public static final byte VERSION = 1;
}
