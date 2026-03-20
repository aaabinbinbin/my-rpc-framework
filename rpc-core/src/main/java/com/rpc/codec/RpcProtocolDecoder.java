package com.rpc.codec;

import com.rpc.protocol.*;
import com.rpc.serialize.Serializer;
import com.rpc.serialize.factory.SerializerFactory;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * RPC 协议解码器
 * 将字节流解码为 RpcMessage
 */
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    /**
     * 构造参数说明：
     * maxFrameLength: 最大帧长度（1MB）
     * lengthFieldOffset: 长度字段偏移量（20 字节，从第 21 字节开始）,在 header 中的偏移
     * lengthFieldLength: 长度字段长度（4 字节）
     * lengthAdjustment: 长度调整值（0 - 不需要调整）
     * initialBytesToStrip: 跳过的字节数（0，不跳过）
     */
    public RpcProtocolDecoder() {
        super(1024 * 1024, 20, 4, 0, 0);
    }

    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        // 1. 调用父类方法，获取完整的帧
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }
        // 2. 读取消息头（24 字节）
        RpcHeader header = new RpcHeader();
        header.setMagicNumber(frame.readInt());          // 4 字节
        header.setVersion(frame.readByte());             // 1 字节
        header.setSerializerType(frame.readByte());      // 1 字节
        header.setMessageType(frame.readByte());         // 1 字节
        header.setReserved(frame.readByte());            // 1 字节
        header.setRequestId(frame.readLong());           // 8 字节
        header.setChecksum(frame.readUnsignedInt());     // 4 字节
        header.setBodyLength(frame.readInt());           // 4 字节
        // 3. 读取并校验魔数、版本号
        int magicNumber = header.getMagicNumber();
        if (magicNumber != RpcHeader.MAGIC_NUMBER) {
            log.error("魔数校验失败：{}", Integer.toHexString(magicNumber));
            frame.release();
            throw new IllegalArgumentException("非法的 RPC 消息，魔数不匹配");
        }
        byte version = header.getVersion();
        if (version != RpcHeader.VERSION) {
            log.error("不支持的协议版本：{}，当前支持版本：{}", header.getVersion(), RpcHeader.VERSION);
            frame.release();
            // 抛出异常，Netty 会关闭连接
            throw new UnsupportedOperationException("不支持的协议版本：" + header.getVersion() + "，当前支持版本：" + RpcHeader.VERSION);
        }
        // 4. 读取消息体
        byte[] bodyBytes = new byte[header.getBodyLength()];
        frame.readBytes(bodyBytes, 0, header.getBodyLength());
        // 5. 校验数据完整性 - CRC32
        CRC32 crc32 = new CRC32();
        crc32.update(bodyBytes);
        long calculatedChecksum = crc32.getValue();

        if (calculatedChecksum != header.getChecksum()) {
            log.error("CRC32 校验失败！期望：{}, 实际：{}", header.getChecksum(), calculatedChecksum);
            frame.release();
            throw new IOException("数据完整性校验失败，可能已损坏");
        }
        // 6. 反序列化（根据消息类型选择目标类）
        Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
        Object body;
        byte messageType = header.getMessageType();
        if (messageType == RpcMessageType.REQUEST) {
            body = serializer.deserialize(bodyBytes, RpcRequest.class);
        } else if (messageType == RpcMessageType.RESPONSE) {
            body = serializer.deserialize(bodyBytes, RpcResponse.class);
        } else if (messageType == RpcMessageType.HEARTBEAT_REQUEST || messageType == RpcMessageType.HEARTBEAT_RESPONSE) {
            body = serializer.deserialize(bodyBytes, RpcHeartbeat.class);
        } else {
            // 其他类型（如心跳）使用 Object.class
            body = serializer.deserialize(bodyBytes, Object.class);
        }
        frame.release();
        // 7. 构建 RpcMessage
        RpcMessage message = new RpcMessage();
        message.setHeader(header);
        message.setBody(body);
        log.debug("解码完成：requestId={}, bodyLength={}, Checksum={}", header.getRequestId(), header.getBodyLength(), calculatedChecksum);
        return message;
    }
}
