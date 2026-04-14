package com.rpc.core.protocol.message;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RPC 协议头。
 *
 * 所处阶段：协议编解码层使用该对象描述一帧消息的元信息。
 * header 不承载业务参数，只承载解码、校验和请求匹配必需的数据。
 */
@Data
@Builder
@AllArgsConstructor
@NoArgsConstructor
public class RpcHeader {
    @Builder.Default
    /** 魔数，用于快速识别非 RPC 协议数据。 */
    private int magicNumber = 0x12345678;

    @Builder.Default
    /** 协议版本，用于后续协议升级兼容判断。 */
    private byte version = 1;

    /** body 使用的序列化器类型，解码 body 前必须先知道该字段。 */
    private byte serializerType;

    /** 消息类型，例如普通请求、普通响应、心跳请求、心跳响应。 */
    private byte messageType;

    /** 预留字段，便于后续协议扩展。 */
    private byte reserved;

    /** 网络请求级 ID，用于客户端 pending 表匹配响应。 */
    private long requestId;

    /** body 字节长度，用于解决 TCP 粘包拆包。 */
    private int bodyLength;

    /** body 校验和，用于发现消息体损坏或读取错位。 */
    private long checksum;

    /** 当前协议头固定长度。 */
    public static final int HEADER_LENGTH = 24;

    public static final int MAGIC_NUMBER = 0x12345678;

    public static final byte VERSION = 1;
}

