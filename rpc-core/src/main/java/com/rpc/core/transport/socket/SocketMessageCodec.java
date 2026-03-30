package com.rpc.core.transport.socket;

import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class SocketMessageCodec {
    private SocketMessageCodec() {
    }

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

