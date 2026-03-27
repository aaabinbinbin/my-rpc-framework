package com.rpc.transport.socket;

import com.rpc.protocol.RpcMessage;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;

public final class SocketMessageCodec {
    private static final Serializer SERIALIZER = SerializerFactory.DEFAULT_SERIALIZER;

    private SocketMessageCodec() {
    }

    public static void writeMessage(DataOutputStream outputStream, RpcMessage message) throws IOException {
        byte[] payload = SERIALIZER.serialize(message);
        outputStream.writeInt(payload.length);
        outputStream.write(payload);
        outputStream.flush();
    }

    public static RpcMessage readMessage(DataInputStream inputStream) throws IOException {
        int length = inputStream.readInt();
        byte[] payload = inputStream.readNBytes(length);
        if (payload.length != length) {
            throw new IOException("Socket message truncated");
        }
        return SERIALIZER.deserialize(payload, RpcMessage.class);
    }
}
