package com.rpc.core.protocol.codec;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.protocol.RpcHeader;
import com.rpc.core.protocol.RpcMessage;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

import java.util.zip.CRC32;

/**
 * RPC 协议编码器。
 */
@Slf4j
public class RpcProtocolEncoder extends MessageToByteEncoder<RpcMessage> {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
    }

    @Override
    public void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) throws Exception {
        RpcHeader header = msg.getHeader();
        Object body = msg.getBody();

        // 编码时根据 header 里声明的 serializerType 选择序列化器，
        // 这样同一套协议可以承载多种序列化实现。
        Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
        byte[] bodyBytes = serializer.serialize(body);

        header.setBodyLength(bodyBytes.length);

        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        header.setChecksum(crc32.getValue());

        // 头字段写入顺序必须与 decoder 中的读取顺序严格一致。
        out.writeInt(header.getMagicNumber());
        out.writeByte(header.getVersion());
        out.writeByte(header.getSerializerType());
        out.writeByte(header.getMessageType());
        out.writeByte(header.getReserved());
        out.writeLong(header.getRequestId());
        out.writeInt((int) header.getChecksum());
        out.writeInt(header.getBodyLength());
        out.writeBytes(bodyBytes);

        log.debug("Encoded rpc message requestId={}, bodyLength={}, magicNumber={}, checksum={}",
                header.getRequestId(), header.getBodyLength(), header.getMagicNumber(), header.getChecksum());
    }
}
