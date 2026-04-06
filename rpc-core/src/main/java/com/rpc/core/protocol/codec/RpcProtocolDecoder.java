package com.rpc.core.protocol.codec;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcHeartbeat;
import com.rpc.core.protocol.RpcMessage;
import com.rpc.core.protocol.RpcMessageType;
import com.rpc.core.protocol.RpcRequest;
import com.rpc.core.protocol.RpcResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * RPC 协议解码器。
 *
 * 这个类负责把网络字节流重新还原成 RpcMessage，
 * 是 protocol 层从“字节世界”回到“对象世界”的关键一步。
 */
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    public RpcProtocolDecoder() {
        // 协议头长度固定为 24 字节，bodyLength 位于偏移 20 处，
        // 因此可以直接复用 Netty 的长度字段解码器解决拆包和粘包问题。
        super(1024 * 1024, 20, 4, 0, 0);
    }

    /**
     * 从字节流中解码出 RpcMessage。
     *
     * 解码步骤：
     * 1. 先截取完整帧。
     * 2. 读取并校验协议头。
     * 3. 读取消息体字节并校验 checksum。
     * 4. 根据 serializerType 和 messageType 反序列化为具体对象。
     */
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }

        RpcHeader header = new RpcHeader();
        header.setMagicNumber(frame.readInt());
        header.setVersion(frame.readByte());
        header.setSerializerType(frame.readByte());
        header.setMessageType(frame.readByte());
        header.setReserved(frame.readByte());
        header.setRequestId(frame.readLong());
        header.setChecksum(frame.readUnsignedInt());
        header.setBodyLength(frame.readInt());

        int magicNumber = header.getMagicNumber();
        if (magicNumber != RpcHeader.MAGIC_NUMBER) {
            frame.release();
            throw new IllegalArgumentException("Invalid RPC magic number: " + Integer.toHexString(magicNumber));
        }

        byte version = header.getVersion();
        if (version != RpcHeader.VERSION) {
            frame.release();
            throw new UnsupportedOperationException(
                    "Unsupported RPC protocol version " + version + ", expected " + RpcHeader.VERSION);
        }

        byte[] bodyBytes = new byte[header.getBodyLength()];
        frame.readBytes(bodyBytes, 0, header.getBodyLength());

        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        long calculatedChecksum = crc32.getValue();
        if (calculatedChecksum != header.getChecksum()) {
            frame.release();
            throw new IOException("Invalid RPC checksum: expected " + header.getChecksum()
                    + ", actual " + calculatedChecksum);
        }

        Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
        Object body = deserializeBody(serializer, bodyBytes, header.getMessageType());

        frame.release();

        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(body);
        log.debug("Decoded rpc message requestId={}, bodyLength={}, checksum={}",
                header.getRequestId(), header.getBodyLength(), calculatedChecksum);
        return message;
    }

    /**
     * 根据消息类型决定把字节反序列化成哪一种消息体对象。
     *
     * 这里体现了协议层的两个核心输入：
     * 1. serializerType 决定用哪个序列化器。
     * 2. messageType 决定目标消息模型是请求、响应还是心跳。
     */
    private Object deserializeBody(Serializer serializer, byte[] bodyBytes, byte messageType) throws IOException {
        if (messageType == RpcMessageType.REQUEST.getCode()) {
            return serializer.deserialize(bodyBytes, RpcRequest.class);
        }
        if (messageType == RpcMessageType.RESPONSE.getCode()) {
            return serializer.deserialize(bodyBytes, RpcResponse.class);
        }
        if (messageType == RpcMessageType.HEARTBEAT_REQUEST.getCode()
                || messageType == RpcMessageType.HEARTBEAT_RESPONSE.getCode()) {
            return serializer.deserialize(bodyBytes, RpcHeartbeat.class);
        }
        return serializer.deserialize(bodyBytes, Object.class);
    }
}
