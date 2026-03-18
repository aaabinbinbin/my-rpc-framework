package com.rpc.protocol.codec;

import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcMessage;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.MessageToByteEncoder;
import lombok.extern.slf4j.Slf4j;

import java.util.zip.CRC32;

/**
 * RPC 协议编码器
 * 将 RpcMessage 编码为字节流
 */
@Slf4j
public class RpcProtocolEncoder extends MessageToByteEncoder<RpcMessage> {
    @Override
    protected void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) throws Exception {
        RpcHeader header = msg.getHeader();
        Object body = msg.getBody();
        // 1. 获取序列化器
        Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
        // 2. 序列化消息体
        byte[] bodyBytes = serializer.serialize(body);
        // 3. 更新消息头的消息体长度
        header.setBodyLength(bodyBytes.length);
        // 4. 计算 CRC32 校验和
        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        header.setChecksum(crc32.getValue());
        // 5. 在 ByteBuf 里面写入消息头 - 24 字节（新增 CRC32 校验和）
        out.writeInt(header.getMagicNumber());           // 4 字节
        out.writeByte(header.getVersion());              // 1 字节
        out.writeByte(header.getSerializerType());       // 1 字节
        out.writeByte(header.getMessageType());          // 1 字节
        out.writeByte(header.getReserved());             // 1 字节
        out.writeLong(header.getRequestId());            // 8 字节
        out.writeInt((int) header.getChecksum());        // 4 字节
        out.writeInt(header.getBodyLength());            // 4 字节
        // 6. 写入消息体
        out.writeBytes(bodyBytes);
        log.debug("编码完成：requestId={}, bodyLength={}, magicNumber={}, checksum={}",
                header.getRequestId(), header.getBodyLength(), header.getMagicNumber(), header.getChecksum());
    }
}
