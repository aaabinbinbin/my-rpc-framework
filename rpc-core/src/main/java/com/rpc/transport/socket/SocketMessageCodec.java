package com.rpc.transport.socket;

import com.rpc.protocol.RpcMessage;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class SocketMessageCodec {
    private SocketMessageCodec() {
    }

    public static void writeMessage(DataOutputStream outputStream, RpcMessage message) throws IOException {
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
        byte[] payload = inputStream.readNBytes(length);
        if (payload.length != length) {
            throw new IOException("Socket message truncated");
        }
        return serializer.deserialize(payload, RpcMessage.class);
    }
}
