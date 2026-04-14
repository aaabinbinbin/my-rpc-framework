package com.rpc.core.protocol.codec;

import com.rpc.core.extension.serialize.Serializer;
import com.rpc.core.extension.serialize.factory.SerializerFactory;
import com.rpc.core.protocol.message.RpcHeader;
import com.rpc.core.protocol.message.RpcHeartbeat;
import com.rpc.core.protocol.message.RpcMessage;
import com.rpc.core.protocol.message.RpcMessageType;
import com.rpc.core.protocol.message.RpcRequest;
import com.rpc.core.protocol.message.RpcResponse;
import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.LengthFieldBasedFrameDecoder;
import lombok.extern.slf4j.Slf4j;

import java.io.IOException;
import java.util.zip.CRC32;

/**
 * RPC 协议解码器。
 *
 * 所处阶段：Netty 已经从 TCP 连接读取到字节流，当前类负责按自定义协议切出完整帧，
 * 并把字节流还原成 RpcMessage。
 *
 * 关键边界：
 * - 继承 LengthFieldBasedFrameDecoder 解决 TCP 粘包/拆包。
 * - magic/version/checksum 用于在进入业务处理前识别非法或损坏消息。
 * - frame 是引用计数对象，解码完成或异常时必须 release，避免堆外内存泄漏。
 */
@Slf4j
public class RpcProtocolDecoder extends LengthFieldBasedFrameDecoder {
    public RpcProtocolDecoder() {
        super(1024 * 1024, 20, 4, 0, 0);
    }

    /**
     * 解码一帧完整 RPC 消息。
     *
     * 协议读取顺序必须和 RpcProtocolEncoder 写入顺序保持一致。
     */
    @Override
    protected Object decode(ChannelHandlerContext ctx, ByteBuf in) throws Exception {
        ByteBuf frame = (ByteBuf) super.decode(ctx, in);
        if (frame == null) {
            return null;
        }

        try {
            RpcHeader header = new RpcHeader();
            header.setMagicNumber(frame.readInt());
            header.setVersion(frame.readByte());
            header.setSerializerType(frame.readByte());
            header.setMessageType(frame.readByte());
            header.setReserved(frame.readByte());
            header.setRequestId(frame.readLong());
            header.setChecksum(frame.readUnsignedInt());
            header.setBodyLength(frame.readInt());

            // magic number 用于快速拒绝非 RPC 协议数据。
            int magicNumber = header.getMagicNumber();
            if (magicNumber != RpcHeader.MAGIC_NUMBER) {
                throw new IllegalArgumentException("Invalid RPC magic number: " + Integer.toHexString(magicNumber));
            }

            // version 用于协议升级兼容，版本不一致时不能继续按当前结构解析。
            byte version = header.getVersion();
            if (version != RpcHeader.VERSION) {
                throw new UnsupportedOperationException(
                        "Unsupported RPC protocol version " + version + ", expected " + RpcHeader.VERSION);
            }

            byte[] bodyBytes = new byte[header.getBodyLength()];
            frame.readBytes(bodyBytes, 0, header.getBodyLength());

            // checksum 用于发现 body 字节损坏或协议读取错位。
            CRC32 crc32 = new CRC32();
            crc32.update(bodyBytes);
            long calculatedChecksum = crc32.getValue();
            if (calculatedChecksum != header.getChecksum()) {
                throw new IOException("Invalid RPC checksum: expected " + header.getChecksum()
                        + ", actual " + calculatedChecksum);
            }

            // body 类型由 messageType 决定，序列化器由 header.serializerType 决定。
            Serializer serializer = SerializerFactory.getSerializer(header.getSerializerType());
            Object body = deserializeBody(serializer, bodyBytes, header.getMessageType());

            RpcMessage message = new RpcMessage();
            message.setHeader(header);
            message.setBody(body);
            log.debug("Decoded rpc message requestId={}, bodyLength={}, checksum={}",
                    header.getRequestId(), header.getBodyLength(), calculatedChecksum);
            return message;
        } finally {
            frame.release();
        }
    }

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
