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
 *
 * 这个类负责把内存中的 RpcMessage 编码成网络字节流，
 * 是 protocol 层从“对象世界”走向“字节世界”的关键一步。
 */
@Slf4j
public class RpcProtocolEncoder extends MessageToByteEncoder<RpcMessage> {
    @Override
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        super.write(ctx, msg, promise);
    }

    /**
     * 把 RpcMessage 编码到 ByteBuf。
     *
     * 编码顺序必须和解码器读取顺序严格一致，
     * 否则对端就无法按同样结构把字节还原回来。
     */
    @Override
    public void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) throws Exception {
        RpcHeader header = msg.getHeader();
        Object body = msg.getBody();

        Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
        byte[] bodyBytes = serializer.serialize(body);

        header.setBodyLength(bodyBytes.length);

        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        header.setChecksum(crc32.getValue());

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
