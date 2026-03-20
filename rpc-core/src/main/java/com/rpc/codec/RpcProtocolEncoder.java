package com.rpc.codec;

import com.rpc.protocol.RpcHeader;
import com.rpc.protocol.RpcMessage;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPromise;
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
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {
        log.info("RpcProtocolEncoder.write() 收到消息类型：{}, 是否是 RpcMessage: {}",
                msg.getClass().getName(), msg instanceof RpcMessage);
        // 类型检查：检查消息是否是 RpcMessage 类型
        // 内存分配：自动分配合适的 ByteBuf
        // 异常处理：try-finally保证内存释放
        // 继续传递：编码完成后自动传递给下一个 Handler
        super.write(ctx, msg, promise);
    }

    @Override
    public void encode(ChannelHandlerContext ctx, RpcMessage msg, ByteBuf out) throws Exception {
        log.info("Rpc消息: {}, ByteBuf: {}", msg, out);
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
        out.writeInt(header.getMagicNumber());           // 4 字节 魔数
        out.writeByte(header.getVersion());              // 1 字节 版本号
        out.writeByte(header.getSerializerType());       // 1 字节 序列化器类型
        out.writeByte(header.getMessageType());          // 1 字节 消息类型 - RpcMessageType
        out.writeByte(header.getReserved());             // 1 字节 保留字段
        out.writeLong(header.getRequestId());            // 8 字节 请求id
        out.writeInt((int) header.getChecksum());        // 4 字节 消息体校验字段 - CRC32
        out.writeInt(header.getBodyLength());            // 4 字节 消息体字节长度
        // 6. 写入消息体
        out.writeBytes(bodyBytes);
        log.debug("编码完成：requestId={}, bodyLength={}, magicNumber={}, checksum={}",
                header.getRequestId(), header.getBodyLength(), header.getMagicNumber(), header.getChecksum());
    }
}
