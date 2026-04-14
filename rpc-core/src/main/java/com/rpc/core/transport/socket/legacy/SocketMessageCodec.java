package com.rpc.core.transport.socket.legacy;

import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

/**
 * legacy Socket 传输消息编解码工具。
 *
 * 所处阶段：JDK Socket 客户端/服务端发送或接收 RpcMessage 时。
 * 主要职责：在没有 Netty frame decoder 的情况下，手动写入 serializerType、payloadLength 和 payload。
 *
 * 注意事项：该实现保留兼容和测试用途，高并发主路径应优先使用 Netty 协议编解码。
 */
public final class SocketMessageCodec {
    /** 工具类不允许实例化。 */
    private SocketMessageCodec() {
    }

    /**
     * 写出一条完整 Socket 消息。
     *
     * 格式：serializerType(1 byte) + payloadLength(4 bytes) + serialized RpcMessage payload。
     */
    public static void writeMessage(DataOutputStream outputStream, RpcMessage message) throws IOException {
        // Socket 传输没有 Netty 的 frame decoder，所以这里手动写出：
        // serializerType + payloadLength + payload
        // 让接收端知道该用哪种序列化器、应读取多少字节。
        byte serializerType = message.getHeader().getSerializerType();
        Serializer serializer = SerializerFactory.getSerializer(serializerType);
        byte[] payload = serializer.serialize(message);
        outputStream.writeByte(serializerType);
        outputStream.writeInt(payload.length);
        outputStream.write(payload);
        outputStream.flush();
    }

    /**
     * 读取一条完整 Socket 消息。
     *
     * 边界处理：严格按 payloadLength 读取，实际字节数不足时抛出 IOException，避免半包被反序列化。
     */
    public static RpcMessage readMessage(DataInputStream inputStream) throws IOException {
        byte serializerType = inputStream.readByte();
        Serializer serializer = SerializerFactory.getSerializer(serializerType);
        int length = inputStream.readInt();
        // 严格按长度读取，避免把半包数据当成完整消息继续反序列化。
        byte[] payload = inputStream.readNBytes(length);
        if (payload.length != length) {
            throw new IOException("Socket message truncated");
        }
        return serializer.deserialize(payload, RpcMessage.class);
    }
}

