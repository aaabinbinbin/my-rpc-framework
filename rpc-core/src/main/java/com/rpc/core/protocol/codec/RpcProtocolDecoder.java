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
 */
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    public RpcProtocolDecoder() {
        // 头长固定 24 字节，其中 bodyLength 位于偏移 20 处，
        // 因此这里直接复用 Netty 的定长帧解码器解决拆包/粘包问题。
        super(1024 * 1024, 20, 4, 0, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }

        RpcHeader header = new RpcHeader();
        // 按协议头字段顺序逐个读取，顺序必须与 encoder 写入顺序完全一致。
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
        // 校验和主要用来快速发现传输层数据损坏或编解码不一致。
        if (calculatedChecksum != header.getChecksum()) {
            frame.release();
            throw new IOException("Invalid RPC checksum: expected " + header.getChecksum()
                    + ", actual " + calculatedChecksum);
        }

        // 解码阶段根据 header 中的 serializerType 选择具体序列化器，
        // 这也是为什么协议层不能直接写死某一种序列化实现。
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

    private Object deserializeBody(Serializer serializer, byte[] bodyBytes, byte messageType) throws IOException {
        // 这里按 messageType 决定目标模型类，
        // 协议层只关心“该把字节解成哪种消息体”，不关心后续业务执行。
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
